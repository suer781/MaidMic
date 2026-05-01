// SpeakerSink.kt — 扬声器音频输出 (AudioSink 实现)
// ============================================================
// 将 PCM 音频数据输出到扬声器的 AudioSink 实现。

package aoeck.dwyai.com.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import aoeck.dwyai.com.AppLogger

class SpeakerSink(
    override val sampleRate: Int = 44100,
    override val channelConfig: Int = AudioFormat.CHANNEL_OUT_MONO,
    override val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) : AudioSink {

    companion object {
        private const val TAG = "SpeakerSink"
    }

    private var audioTrack: AudioTrack? = null
    @Volatile
    override var isRunning: Boolean = false
        private set

    override val displayName: String = "扬声器"

    override fun write(buffer: ByteArray, size: Int) {
        try {
            audioTrack?.write(buffer, 0, size)
        } catch (e: Exception) {
            AppLogger.e(TAG, "write 失败", e)
        }
    }

    override fun start(): Boolean {
        if (isRunning) return true
        if (audioTrack != null) release()

        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufSize = minBuf.coerceAtLeast(2048)

        audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            AppLogger.e(TAG, "创建 AudioTrack 失败", e)
            return false
        }

        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            AppLogger.e(TAG, "AudioTrack 初始化失败")
            audioTrack?.release()
            audioTrack = null
            return false
        }

        try {
            audioTrack?.play()
        } catch (e: Exception) {
            AppLogger.e(TAG, "play 失败", e)
            audioTrack?.release()
            audioTrack = null
            return false
        }

        isRunning = true
        AppLogger.i(TAG, "扬声器已启动")
        return true
    }

    override fun stop() {
        if (!isRunning) return
        isRunning = false
        try { audioTrack?.stop() } catch (_: Exception) {}
    }

    override fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
    }
}
