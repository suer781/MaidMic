// AudioLoopback.kt — 麦克风 → DSP → 扬声器/听筒环回
// ============================================================
// 用户可调：延时缓冲大小、扬声器/听筒输出

package aoeck.dwyai.com

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log

object AudioLoopback {

    private const val TAG = "AudioLoopback"
    private const val SAMPLE_RATE = 48000
    private const val CHANNELS = 1
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    // 可选的 buffer 大小（帧数）
    val BUFFER_OPTIONS = listOf(64, 128, 256, 512, 1024)

    // 当前设置
    var bufferFrames: Int = 256
        private set
    var useSpeaker: Boolean = false
        private set
    var isRunning: Boolean = false
        private set

    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var savedAudioMode: Int = AudioManager.MODE_NORMAL

    /** 理论延迟（毫秒） */
    fun theoreticalLatencyMs(): Int = (bufferFrames * 1000) / SAMPLE_RATE

    /** 更新 buffer 大小（下次启动生效） */
    fun setBufferFrames(frames: Int) {
        bufferFrames = frames.coerceIn(64, 1024)
    }

    /** 切换扬声器/听筒（下次启动生效） */
    fun setUseSpeaker(speaker: Boolean) {
        useSpeaker = speaker
    }

    /** 启动音频处理 */
    fun start(context: Context) {
        if (isRunning) return

        NativeAudioProcessor.ensureLoaded()

        val bufSizeBytes = bufferFrames * 2 // 16-bit
        val minRecBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
            .coerceAtLeast(bufSizeBytes)
        val minTrkBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, ENCODING).coerceAtLeast(bufSizeBytes)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNELS, ENCODING, minRecBuf
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            audioRecord?.release(); audioRecord = null; return
        }

        val usage = if (useSpeaker)
            AudioAttributes.USAGE_MEDIA
        else
            AudioAttributes.USAGE_VOICE_COMMUNICATION

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(ENCODING).setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minTrkBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // 路由到听筒（非扬声器模式）
        if (!useSpeaker) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                savedAudioMode = am.mode
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set audio route: ${e.message}")
            }
        }

        isRunning = true
        audioRecord?.startRecording()
        audioTrack?.play()

        recordThread = Thread({ processingLoop(bufSizeBytes) }, "maidmic-loopback")
        recordThread?.start()

        Log.i(TAG, "Loopback started: ${bufferFrames}frames ~${theoreticalLatencyMs()}ms, speaker=$useSpeaker")
    }

    /** 停止 */
    fun stop(context: Context) {
        isRunning = false
        recordThread?.join(1000)
        recordThread = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioTrack?.release()
        audioRecord = null
        audioTrack = null

        // 恢复音频模式
        if (!useSpeaker) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.mode = savedAudioMode
            } catch (_: Exception) {}
        }

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
