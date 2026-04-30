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
import android.util.Log

object AudioLoopback {

    private const val TAG = "AudioLoopback"
    private const val SAMPLE_RATE = 48000
    private const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    val BUFFER_OPTIONS = listOf(64, 128, 256, 512, 1024)
    var bufferFrames: Int = 256; private set
    var useSpeaker: Boolean = true; private set
    var isRunning: Boolean = false; private set
    var aecEnabled: Boolean = true; private set

    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var savedAudioMode: Int = AudioManager.MODE_NORMAL

    fun theoreticalLatencyMs(): Int = (bufferFrames * 1000) / SAMPLE_RATE
    fun setBufferFrames(frames: Int) { bufferFrames = frames.coerceIn(64, 1024) }
    fun setUseSpeaker(speaker: Boolean) { useSpeaker = speaker }
    fun setAecEnabled(enabled: Boolean) { aecEnabled = enabled }

    /** 启动音频处理（带 AEC 回声消除） */
    fun start(context: Context) {
        if (isRunning) return
        NativeAudioProcessor.ensureLoaded()

        val bufSizeBytes = bufferFrames * 2
        val minRecBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
            .coerceAtLeast(bufSizeBytes)

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

        // AudioTrack 输出
        val minTrkBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, ENCODING).coerceAtLeast(bufSizeBytes)
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
        recordThread = Thread({ processingLoop(bufSizeBytes) }, "maidmic-loopback")
        recordThread?.start()
        Log.i(TAG, "Loopback started: ${bufferFrames}fr ~${theoreticalLatencyMs()}ms AEC=${echoCanceler?.enabled == true}")
    }

    fun stop(context: Context) {
        isRunning = false
        recordThread?.join(1000)
        recordThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        audioRecord?.release(); audioRecord = null
        audioTrack?.release(); audioTrack = null
        echoCanceler?.release(); echoCanceler = null
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = savedAudioMode
        } catch (_: Exception) {}
        Log.i(TAG, "Loopback stopped")
    }

    private fun processingLoop(bufSizeBytes: Int) {
        val inputBuf = ByteArray(bufSizeBytes)
        val outputBuf = ByteArray(bufSizeBytes)
        while (isRunning) {
            val read = audioRecord?.read(inputBuf, 0, bufSizeBytes) ?: -1
            if (read <= 0) { Thread.sleep(1); continue }
            NativeAudioProcessor.processAudio(inputBuf, outputBuf, read)
            audioTrack?.write(outputBuf, 0, read)
        }
    }
}
