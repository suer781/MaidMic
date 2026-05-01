// MicSource.kt — 麦克风音频源 (AudioSource 实现)
// ============================================================
// 从物理麦克风捕获 PCM 音频数据的 AudioSource 实现。

package aoeck.dwyai.com.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import aoeck.dwyai.com.AppLogger

class MicSource(
    override val sampleRate: Int = 44100,
    override val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    override val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) : AudioSource {

    companion object {
        private const val TAG = "MicSource"
    }

    private var audioRecord: AudioRecord? = null
    @Volatile
    override var isRunning: Boolean = false
        private set

    override val displayName: String = "麦克风"

    override fun read(buffer: ByteArray): Int {
        val rec = audioRecord ?: return -1
        return try {
            rec.read(buffer, 0, buffer.size)
        } catch (e: Exception) {
            AppLogger.e(TAG, "read 失败", e)
            -1
        }
    }

    override fun start(): Boolean {
        if (isRunning) return true
        if (audioRecord != null) release()

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufSize = minBuf.coerceAtLeast(2048)

        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat,
                bufSize * 4
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "创建 AudioRecord 失败", e)
            return false
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            AppLogger.e(TAG, "AudioRecord 初始化失败")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            AppLogger.e(TAG, "startRecording 失败", e)
            audioRecord?.release()
            audioRecord = null
            return false
        }

        isRunning = true
        AppLogger.i(TAG, "麦克风已启动 (${sampleRate}Hz, buffer=${bufSize})")
        return true
    }

    override fun stop() {
        if (!isRunning) return
        isRunning = false
        try { audioRecord?.stop() } catch (_: Exception) {}
    }

    override fun release() {
        stop()
        audioRecord?.release()
        audioRecord = null
    }
}
