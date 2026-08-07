// WavWriter.kt — PCM16 流式 WAV 文件写入器
// ============================================================
// 把 PCM16 (mono/48k 默认) 数据流式写入标准 WAV 文件。
//
// 设计要点：
//   - 流式写：start() 写 44 字节头 (data size 占位为 0)，
//     writePcm16() 追加 PCM 数据并累计字节数，
//     finish() 用 RandomAccessFile 回填 data size + RIFF size。
//     避免一次性把整段音频载入内存。
//   - 标准 44 字节 WAV 头：RIFF chunk + fmt subchunk (PCMWAVEFORMAT) + data subchunk。
//   - 所有多字节字段均为小端序 (WAV 规范)。
//   - 线程不安全：调用方需保证单线程或自行加锁。
//
// 用法：
//   val w = WavWriter(File(filesDir, "voicepacks/xxx.wav"))
//   w.start()
//   w.writePcm16(pcmBytes)
//   ...
//   w.finish()

package aoeck.dwyai.com.voicepack

import aoeck.dwyai.com.AppLogger
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * PCM16 → 标准 WAV 流式写入器。
 *
 * @param file 目标 WAV 文件。父目录应已存在 (调用方负责 mkdirs)。
 * @param sampleRate 采样率，默认 48000。
 * @param channels 声道数，默认 1 (mono)。
 * @param bitsPerSample 每样本位数，默认 16 (PCM16)。
 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int = 48000,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16,
) {
    companion object {
        private const val TAG = "WavWriter"

        // 标准 WAV 头长度 (RIFF 12 + fmt 24 + data 8)
        const val HEADER_SIZE = 44

        // PCM 格式标识
        private const val AUDIO_FORMAT_PCM = 1
    }

    private var fos: FileOutputStream? = null
    private var bufOut: BufferedOutputStream? = null
    private var totalDataBytes: Long = 0L
    private var started = false
    private var finished = false

    /**
     * 写入 44 字节 WAV 头，data size 占位为 0。
     * 若文件已存在会被覆盖。
     */
    fun start() {
        check(!started && !finished) { "WavWriter 已 start 过，请新建实例" }
        file.parentFile?.mkdirs()

        fos = FileOutputStream(file, false)
        // 缓冲写盘：录音线程每块（约 21ms 音频）调用一次 writePcm16，
        // 不缓冲则每次都是一次磁盘系统调用；缓冲后攒满 32KB 才落盘一次，
        // 显著降低录音线程的 I/O 延迟抖动（突发磁盘写延迟是"卡卡"来源之一）。
        bufOut = BufferedOutputStream(fos, 32 * 1024)
        val header = ByteArray(HEADER_SIZE)
        var p = 0

        // ---- RIFF chunk descriptor ----
        // ChunkID "RIFF"
        header[p++] = 'R'.code.toByte()
        header[p++] = 'I'.code.toByte()
        header[p++] = 'F'.code.toByte()
        header[p++] = 'F'.code.toByte()
        // ChunkSize = 36 + data size，先占位 0，finish() 回填
        writeLeInt(header, p, 36)
        p += 4
        // Format "WAVE"
        header[p++] = 'W'.code.toByte()
        header[p++] = 'A'.code.toByte()
        header[p++] = 'V'.code.toByte()
        header[p++] = 'E'.code.toByte()

        // ---- fmt subchunk ----
        // Subchunk1ID "fmt "
        header[p++] = 'f'.code.toByte()
        header[p++] = 'm'.code.toByte()
        header[p++] = 't'.code.toByte()
        header[p++] = ' '.code.toByte()
        // Subchunk1Size = 16 (PCMWAVEFORMAT)
        writeLeInt(header, p, 16)
        p += 4
        // AudioFormat = 1 (PCM)
        writeLeShort(header, p, AUDIO_FORMAT_PCM)
        p += 2
        // NumChannels
        writeLeShort(header, p, channels)
        p += 2
        // SampleRate
        writeLeInt(header, p, sampleRate)
        p += 4
        // ByteRate = SampleRate * NumChannels * BitsPerSample/8
        val byteRate = sampleRate * channels * bitsPerSample / 8
        writeLeInt(header, p, byteRate)
        p += 4
        // BlockAlign = NumChannels * BitsPerSample/8
        val blockAlign = channels * bitsPerSample / 8
        writeLeShort(header, p, blockAlign)
        p += 2
        // BitsPerSample
        writeLeShort(header, p, bitsPerSample)
        p += 2

        // ---- data subchunk ----
        // Subchunk2ID "data"
        header[p++] = 'd'.code.toByte()
        header[p++] = 'a'.code.toByte()
        header[p++] = 't'.code.toByte()
        header[p++] = 'a'.code.toByte()
        // Subchunk2Size = data size，先占位 0，finish() 回填
        writeLeInt(header, p, 0)
        p += 4

        check(p == HEADER_SIZE) { "WAV 头长度计算错误: $p != $HEADER_SIZE" }

        fos!!.write(header)
        fos!!.flush()
        started = true
        AppLogger.i(TAG, "start: ${file.absolutePath} ${sampleRate}Hz ${channels}ch ${bitsPerSample}bit")
    }

    /**
     * 追加 PCM16 数据。可多次调用。字节序约定为小端 (与 WAV/PCM 规范一致)。
     * 调用方负责保证传入的即目标声道/采样率的 PCM，本类不做重采样。
     */
    fun writePcm16(bytes: ByteArray) {
        check(started && !finished) { "WavWriter 未 start 或已 finish" }
        if (bytes.isEmpty()) return
        bufOut!!.write(bytes)
        totalDataBytes += bytes.size
    }

    /**
     * 回填 data size + RIFF size，关闭文件。
     * 幂等：重复调用不会出错。
     */
    fun finish() {
        if (finished) return
        if (!started) {
            // 未 start 直接 finish，视为空文件，仍创建一个合法的 0 长度 WAV
            start()
        }
        bufOut?.flush()
        bufOut?.close()
        fos?.close()  // 兜底：防止 bufOut 未创建时资源泄漏
        bufOut = null
        fos = null

        // 用 RandomAccessFile 回填头部两个 size 字段
        // RIFF ChunkSize (offset 4) = 36 + data size
        // data Subchunk2Size (offset 40) = data size
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(4)
                raf.write(intToLeBytes((36 + totalDataBytes).toInt()))
                raf.seek(40)
                raf.write(intToLeBytes(totalDataBytes.toInt()))
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "finish: 回填 WAV 头失败 data=$totalDataBytes", e)
            throw e
        }

        finished = true
        AppLogger.i(
            TAG,
            "finish: ${file.name} 数据=${totalDataBytes}B 时长=${totalDataBytes * 1000 / (sampleRate * channels * bitsPerSample / 8)}ms"
        )
    }

    /** 已写入的 PCM 数据字节数 (不含头)。 */
    fun dataBytesWritten(): Long = totalDataBytes

    /** 估算时长 (毫秒)，基于已写入字节数。 */
    fun estimatedDurationMs(): Long {
        val bytesPerSec = sampleRate.toLong() * channels * bitsPerSample / 8
        if (bytesPerSec <= 0) return 0
        return totalDataBytes * 1000 / bytesPerSec
    }

    // ---- 小端序写入辅助 ----

    private fun writeLeShort(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeLeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun intToLeBytes(value: Int): ByteArray = ByteArray(4).also {
        it[0] = (value and 0xFF).toByte()
        it[1] = ((value shr 8) and 0xFF).toByte()
        it[2] = ((value shr 16) and 0xFF).toByte()
        it[3] = ((value shr 24) and 0xFF).toByte()
    }
}
