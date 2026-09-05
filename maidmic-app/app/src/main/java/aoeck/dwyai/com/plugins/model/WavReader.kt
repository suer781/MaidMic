// plugins/model/WavReader.kt — 最小 WAV 读取器（16-bit PCM）
// ============================================================
// 供模型插件离线转换使用：读取语音包 WAV → ShortArray + 采样率。
// 支持标准 44 字节头 + 常见扩展头（按 chunk 解析，找 fmt + data）。

package aoeck.dwyai.com.plugins.model

import java.io.File
import java.io.RandomAccessFile

data class WavData(val sampleRate: Int, val channels: Int, val pcm: ShortArray)

object WavReader {

    /** 解析 16-bit PCM WAV；其他位深/格式抛 IllegalArgumentException */
    fun read(file: File): WavData {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length().toInt()
            val bytes = ByteArray(len)
            raf.readFully(bytes)

            if (len < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") {
                throw IllegalArgumentException("不是 WAV 文件")
            }

            var sampleRate = 48000
            var channels = 1
            var bitsPerSample = 16
            var dataOffset = -1
            var dataLength = -1

            // chunk 遍历（从 offset 12 开始）
            var pos = 12
            while (pos + 8 <= len) {
                val chunkId = String(bytes, pos, 4)
                val chunkSize = leInt(bytes, pos + 4)
                when (chunkId) {
                    "fmt " -> {
                        channels = leShort(bytes, pos + 10)
                        sampleRate = leInt(bytes, pos + 12)
                        bitsPerSample = leShort(bytes, pos + 22)
                    }
                    "data" -> {
                        dataOffset = pos + 8
                        dataLength = minOf(chunkSize, len - dataOffset)
                    }
                }
                pos += 8 + chunkSize + (chunkSize and 1)  // chunk 按字对齐
            }

            if (dataOffset < 0 || bitsPerSample != 16) {
                throw IllegalArgumentException("仅支持 16-bit PCM WAV（实际 ${bitsPerSample}bit）")
            }

            val sampleCount = dataLength / 2
            val pcm = ShortArray(sampleCount)
            for (i in 0 until sampleCount) {
                val o = dataOffset + i * 2
                pcm[i] = ((bytes[o].toInt() and 0xFF) or
                        ((bytes[o + 1].toInt() and 0xFF) shl 8)).toShort()
            }
            return WavData(sampleRate, channels, pcm)
        }
    }

    private fun leShort(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun leInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
