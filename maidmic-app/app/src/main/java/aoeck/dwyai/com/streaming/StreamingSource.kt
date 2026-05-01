// StreamingSource.kt — TCP音频接收源 (Device B 用)
// ============================================================
// 实现 AudioSource 接口
// 流程: TCP接收 → read(buffer) → (外部pipeline处理)
// Device B 管线: StreamingSource → SpeakerSink(本地播放) 或 bridge注入

package aoeck.dwyai.com.streaming

import android.media.AudioFormat
import android.util.Log
import aoeck.dwyai.com.audio.AudioSource

class StreamingSource(
    private val connectionManager: ConnectionManager
) : AudioSource {

    private val TAG = "StreamingSource"

    @Volatile
    private var running = false

    override val sampleRate: Int get() = 48000
    override val channelConfig: Int get() = AudioFormat.CHANNEL_IN_MONO
    override val audioFormat: Int get() = AudioFormat.ENCODING_PCM_16BIT
    override val isRunning: Boolean get() = running
    override val displayName: String = "流式接收"

    override fun start(): Boolean {
        running = true
        Log.i(TAG, "StreamingSource started")
        return true
    }

    override fun stop() {
        running = false
        Log.i(TAG, "StreamingSource stopped")
    }

    override fun release() {
        stop()
    }

    /** 从TCP读取音频数据（阻塞直到数据到达或断开） */
    override fun read(buffer: ByteArray): Int {
        if (!running) return -1
        val read = connectionManager.receiveAudio(buffer)
        if (read <= 0) {
            Log.w(TAG, "Receive failed or disconnected")
        }
        return read
    }
}
