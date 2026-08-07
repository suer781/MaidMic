// AudioLoopback.kt — 麦克风 → AEC → DSP → 扬声器环回
// ============================================================
// 支持 AEC (Acoustic Echo Cancellation) 消除扬声器回声，
// 实现外放变声不啸叫。

package aoeck.dwyai.com

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Process
import android.util.Log

object AudioLoopback {

    private const val TAG = "AudioLoopback"
    private const val SAMPLE_RATE = 48000
    private const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    // 处理块（帧）选项。默认 1024 帧 ≈ 21.3ms @48kHz：
    //   块太小时（如 256 帧=5.3ms）每块都要 read→JNI 处理→write，
    //   线程调度稍一抖动就 AudioTrack 欠载，听感就是"卡卡的"。
    //   1024 帧在稳定性与实时延迟（约 21ms）间取得平衡。
    val BUFFER_OPTIONS = listOf(256, 512, 1024, 2048)
    var bufferFrames: Int = 1024; private set
    var useSpeaker: Boolean = true; private set
    var isRunning: Boolean = false; private set
    var aecEnabled: Boolean = true; private set

    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    private val lock = Any()

    fun theoreticalLatencyMs(): Int = (bufferFrames * 1000) / SAMPLE_RATE
    fun setBufferFrames(frames: Int) { bufferFrames = frames.coerceIn(256, 2048) }
    fun setUseSpeaker(speaker: Boolean) { useSpeaker = speaker }
    fun setAecEnabled(enabled: Boolean) { aecEnabled = enabled }

    /** 启动音频处理（带 AEC 回声消除） */
    fun start(context: Context) {
        if (isRunning) return
        NativeAudioProcessor.ensureLoaded()

        val bufSizeBytes = bufferFrames * 2
        // 内部缓冲 ≥ 3× 处理块：read/write 的调度抖动（GC、JNI、其他线程）
        // 由缓冲吸收，避免 AudioTrack 欠载导致的"卡卡的"。
        val minRecBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
            .coerceAtLeast(bufSizeBytes * 3)

        // 使用 VOICE_COMMUNICATION 源（AEC 效果更好）
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNEL_MASK, ENCODING, minRecBuf
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed, trying MIC source")
            audioRecord?.release()
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_MASK, ENCODING, minRecBuf
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed (MIC too)")
                audioRecord?.release(); audioRecord = null; return
            }
        }

        // 启用 AEC (Acoustic Echo Canceler)
        val sessionId = audioRecord!!.audioSessionId
        if (aecEnabled && AcousticEchoCanceler.isAvailable()) {
            try {
                echoCanceler = AcousticEchoCanceler.create(sessionId)
                echoCanceler?.enabled = true
                Log.i(TAG, "AEC enabled (session=$sessionId)")
                AppLogger.i("AEC", "回声消除已启用")
            } catch (e: Exception) {
                Log.w(TAG, "AEC create failed: ${e.message}")
                echoCanceler = null
            }
        } else {
            Log.i(TAG, "AEC not available or disabled")
            AppLogger.i("AEC", "回声消除不可用，建议戴耳机")
        }

        // AudioTrack 输出：缓冲 ≥ 3× 处理块，吸收处理/调度抖动，降低欠载概率
        val minTrkBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, ENCODING).coerceAtLeast(bufSizeBytes * 3)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(ENCODING).setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minTrkBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // 强制扬声器模式（AEC 下外放也不会有回声）
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedAudioMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true // 外放
        } catch (e: Exception) {
            Log.w(TAG, "Failed audio route: ${e.message}")
        }

        isRunning = true
        audioRecord?.startRecording()
        audioTrack?.play()
        // 直接传入引用，处理线程不触碰共享字段，消除每循环 synchronized 锁
        recordThread = Thread({
            // 音频线程优先级（nice=-16）：必须用 Process.setThreadPriority 在
            // 线程体内设置。此前写 Thread.priority = Process.THREAD_PRIORITY_AUDIO
            // 是 bug——Thread.priority 仅接受 1..10，赋 -16 会抛
            // IllegalArgumentException（线程启动即崩溃）；且即便不抛，
            // 也不会真正设置 Linux 调度优先级，调度抖动会直接导致
            // AudioTrack 欠载，听感"卡卡的"。
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            processingLoop(audioRecord, audioTrack, bufSizeBytes)
        }, "maidmic-loopback")
        recordThread?.start()
        Log.i(TAG, "Loopback started: ${bufferFrames}fr ~${theoreticalLatencyMs()}ms AEC=${echoCanceler?.enabled == true}")
    }

    fun stop(context: Context) {
        isRunning = false
        recordThread?.interrupt()
        recordThread?.join(3000)
        recordThread = null
        synchronized(lock) {
            try { audioRecord?.stop() } catch (_: Exception) {}
            try { audioTrack?.stop() } catch (_: Exception) {}
            audioRecord?.release(); audioRecord = null
            audioTrack?.release(); audioTrack = null
            echoCanceler?.release(); echoCanceler = null
        }
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = savedAudioMode
        } catch (_: Exception) {}
        Log.i(TAG, "Loopback stopped")
    }

    private fun processingLoop(rec: AudioRecord?, trk: AudioTrack?, bufSizeBytes: Int) {
        if (rec == null || trk == null) return
        val inputBuf = ByteArray(bufSizeBytes)
        val outputBuf = ByteArray(bufSizeBytes)
        while (isRunning && !Thread.currentThread().isInterrupted) {
            val read = try { rec.read(inputBuf, 0, bufSizeBytes) } catch (_: Exception) { -1 }
            if (read <= 0) {
                // 停止后直接退出，避免忙循环
                if (isRunning) Thread.sleep(1)
                break
            }
            NativeAudioProcessor.processAudio(inputBuf, outputBuf, read)
            try { trk.write(outputBuf, 0, read) } catch (_: Exception) { break }
        }
    }
}
