// StreamingSink.kt — UDP音频发送端 (Device A 用)
// ============================================================
// 实现 AudioSink 接口
// 流程: DSP处理后数据 → 分片(1469字节/包) → UDP发送
// Device A 管线: MicSource → NativeAudioProcessor → StreamingSink → UDP

package aoeck.dwyai.com.streaming

import android.media.AudioFormat
import android.util.Log
import aoeck.dwyai.com.audio.AudioSink

class StreamingSink(
    private val connectionManager: ConnectionManager
) : AudioSink {

    private val TAG = "StreamingSink"
    private val MAX_UDP_PAYLOAD = 1469  // 1472 - 3字节magic

    @Volatile
    private var running = false

    override val sampleRate: Int get() = 48000
    override val channelConfig: Int get() = AudioFormat.CHANNEL_OUT_MONO
    override val audioFormat: Int get() = AudioFormat.ENCODING_PCM_16BIT
    override val isRunning: Boolean get() = running
    override val displayName: String = "UDP发送端"

    override fun start(): Boolean {
        running = true
        Log.i(TAG, "StreamingSink started")
        return true
    }

    override fun stop() {
        running = false
        Log.i(TAG, "StreamingSink stopped")
    }

    override fun release() {
        stop()
    }

    /**
     * DSP处理后的PCM数据 → 分片UDP发送
     * 大块数据(如4096字节)自动拆成多个MAX_UDP_PAYLOAD大小的包
     */
    override fun write(buffer: ByteArray, size: Int) {
        var offset = 0
        while (offset < size) {
            val chunkSize = minOf(MAX_UDP_PAYLOAD, size - offset)
            val chunk = if (chunkSize == buffer.size) buffer else buffer.copyOfRange(offset, offset + chunkSize)
            if (!connectionManager.sendAudio(chunk, chunkSize)) {
                Log.w(TAG, "Send failed at offset $offset")
                return
            }
            offset += chunkSize
        }
    }
}
