// VoicePackPlayer.kt — 语音包外放播放器
// ============================================================
// Task 6.2: 用 AudioTrack 播放语音包 WAV 文件，实现"外放试听"。
//
// 设计要点：
//   - AudioAttributes 使用 USAGE_MEDIA + CONTENT_TYPE_MUSIC：
//       * 走媒体音量流，使用用户当前媒体音量，**不调系统音量**（项目铁律）。
//       * 不调用 AudioManager.setStreamVolume / adjustStreamVolume。
//   - 流式播放：边从文件读取 PCM 边写入 AudioTrack，避免一次性载入大文件占内存。
//   - 在独立线程播放（"VoicePackPlayer-Thread"），不阻塞 UI。
//   - 播放控制：play() / stop() / isPlaying()，play 完成后回调 onComplete。
//   - WAV 头解析：扫描 chunks 找到 "fmt "/"data"，兼容标准 44 字节头与带额外 chunk 的 WAV。
//   - 采样率/声道/位深从 WAV 头读取（与 VoicePack.sampleRate 互为校验）。
//   - 线程安全：playing 标记 @Volatile；stop() 可在任意线程调用，会中断播放循环。
//   - 错误处理：文件不存在 / 头解析失败 / AudioTrack 初始化失败均走 onComplete 兜底回调。
//
// 用法：
//   val player = VoicePackPlayer()
//   player.play(context, voicePack) { /* onComplete，主线程回调 */ }
//   ...
//   player.stop()          // 主动停止
//   player.isPlaying()     // 查询状态
//   // 页面退出时记得 stop() 释放资源

package aoeck.dwyai.com.voicepack

import aoeck.dwyai.com.AppLogger
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File
import java.io.FileInputStream

/**
 * 语音包外放播放器。
 *
 * 播放走 USAGE_MEDIA 媒体音量流，**不调系统音量**（项目铁律）。
 * 在独立线程流式播放，[onComplete] 在主线程回调。
 */
class VoicePackPlayer {

    companion object {
        private const val TAG = "VoicePackPlayer"

        // 单次读取 PCM 的缓冲区字节数（流式播放粒度）
        // 4096 字节 = 2048 samples (16bit mono)，约 42ms @48k，足够低延迟且 IO 次数适中。
        private const val READ_BUFFER_BYTES = 4096
    }

    // 主线程 Handler：保证回调在主线程触发
    private val mainHandler = Handler(Looper.getMainLooper())

    // 播放状态（@Volatile 供 UI 线程查询 + 播放线程循环判断）
    @Volatile private var playing = false
    private var playThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var onCompleteCallback: (() -> Unit)? = null

    // 播放令牌：每次 play() 自增。播放线程在 finally 中只有当自己仍是"当前令牌"时
    // 才清 playing 状态 + 触发回调，避免"切换曲目时旧 playLoop 的 finishPlay 把新播放也停掉"。
    @Volatile private var playToken: Int = 0

    /** 当前是否正在播放。供 UI 查询以切换播放/停止按钮态。 */
    fun isPlaying(): Boolean = playing

    /**
     * 播放指定语音包的 WAV 文件。
     *
     * - 若当前正在播放，会先 [stop] 再启动新播放。
     * - [onComplete] 在播放结束（自然播完 / 出错 / 被 stop）后于主线程回调，仅触发一次。
     * - WAV 文件不存在时立即回调 onComplete。
     *
     * @param context 用于定位 WAV 文件（filesDir/voicepacks/...）。
     * @param voicePack 目标语音包（取其 wavFile 相对路径）。
     * @param onComplete 播放完成回调（主线程）。
     */
    fun play(context: Context, voicePack: VoicePack, onComplete: () -> Unit) {
        // 先停掉当前播放（防止重叠）。注意 stopInternal 会自增 playToken 使旧 playLoop 失效。
        stopInternal(notifyComplete = false)

        val file = VoicePackStore.wavFile(context, voicePack.wavFile)
        if (!file.exists()) {
            AppLogger.e(TAG, "play: WAV 文件不存在 ${file.absolutePath}")
            // 兜底回调，避免 UI 卡在"播放中"状态
            mainHandler.post(onComplete)
            return
        }

        // 领取本次播放令牌
        val myToken = ++playToken
        onCompleteCallback = onComplete
        playing = true
        playThread = Thread(
            { playLoop(file, voicePack, myToken) },
            "VoicePackPlayer-Thread"
        ).apply {
            isDaemon = true
            start()
        }
        AppLogger.i(TAG, "play: 启动播放 id=${voicePack.id} file=${file.name} token=$myToken")
    }

    /**
     * 停止播放并释放资源。可在任意线程调用。
     * 若当前有未触发的 onComplete 回调，会触发它（主线程）。
     */
    fun stop() {
        stopInternal(notifyComplete = true)
    }

    // ============================================================
    // 内部：播放主循环
    // ============================================================

    private fun playLoop(file: File, voicePack: VoicePack, myToken: Int) {
        // 播放线程设为音频优先级（nice=-16）：减少调度抖动，降低 AudioTrack 欠载导致的卡顿
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var track: AudioTrack? = null
        try {
            // ---- 1. 解析 WAV 头 ----
            val header = readWavHeader(file)
            AppLogger.i(
                TAG,
                "playLoop: ${file.name} sr=${header.sampleRate} ch=${header.channels} " +
                    "bits=${header.bitsPerSample} dataOffset=${header.dataOffset} " +
                    "(pack.sampleRate=${voicePack.sampleRate})"
            )

            val encoding = when (header.bitsPerSample) {
                8 -> AudioFormat.ENCODING_PCM_8BIT
                16 -> AudioFormat.ENCODING_PCM_16BIT
                24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                32 -> AudioFormat.ENCODING_PCM_FLOAT
                else -> AudioFormat.ENCODING_PCM_16BIT
            }
            val channelMask = if (header.channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

            // ---- 2. 创建 AudioTrack ----
            // USAGE_MEDIA：媒体音量流，使用用户当前音量，**不调系统音量**（项目铁律）。
            val minBuf = AudioTrack.getMinBufferSize(header.sampleRate, channelMask, encoding)
            if (minBuf <= 0) {
                AppLogger.e(TAG, "playLoop: getMinBufferSize 失败=$minBuf sr=${header.sampleRate}")
                return
            }
            val bufferSize = (minBuf * 2).coerceAtLeast(READ_BUFFER_BYTES)

            track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(header.sampleRate)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                AppLogger.e(TAG, "playLoop: AudioTrack 创建失败", e)
                null
            }

            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                val state = track?.state?.toString() ?: "null"
                AppLogger.e(TAG, "playLoop: AudioTrack 未初始化 state=$state")
                track?.release()
                return
            }
            audioTrack = track

            // ---- 3. 启动播放 + 流式写 PCM ----
            // 循环条件同时检查 playing 与令牌：被 stop / 切换曲目后立即退出。
            track.play()
            FileInputStream(file).use { fis ->
                // 定位到 data chunk 起始
                fis.channel.position(header.dataOffset)
                val buf = ByteArray(READ_BUFFER_BYTES)
                while (playing && myToken == playToken) {
                    val read = fis.read(buf)
                    if (read <= 0) break
                    // 写入 AudioTrack（阻塞写，等队列有空位；自然形成背压）
                    track.write(buf, 0, read, AudioTrack.WRITE_BLOCKING)
                }
            }

            // ---- 4. 自然播完：让已写入队列的数据 drain 完毕 ----
            // stop() 会保留已入队数据继续播完（MODE_STREAM 行为），避免尾部截断。
            // 仅当本次播放仍是当前令牌时才 stop（被切换/停止的已由 stopInternal 处理）。
            if (playing && myToken == playToken) {
                try {
                    track.stop()
                } catch (_: IllegalStateException) {
                    // 极少数情况下 stop 重复调用，忽略
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "playLoop: 播放异常", e)
        } finally {
            // 释放自己持有的 track（若已被 stopInternal 释放过，此处捕获异常忽略）
            try { track?.release() } catch (_: Exception) {}
            if (audioTrack === track) audioTrack = null
            // 关键：仅当自己仍是"当前令牌"时才清 playing + 回调，
            // 否则说明已被新 play() / stop() 接管，不能干扰新状态。
            if (myToken == playToken) {
                finishPlay()
            }
        }
    }

    // ============================================================
    // 内部：停止 / 完成
    // ============================================================

    /**
     * 停止播放并清理。
     * @param notifyComplete 是否触发未完成的 onComplete 回调。
     *                        play() 内部切换曲目时传 false（避免旧回调污染新状态），
     *                        外部 stop() 传 true。
     */
    private fun stopInternal(notifyComplete: Boolean) {
        playing = false
        // 自增令牌使任何 in-flight 的 playLoop 失效（其 finally 不再触发 finishPlay）
        playToken++
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            // track.stop() 会让正在 WRITE_BLOCKING 阻塞的 write 立即返回，解除线程阻塞
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
        }
        // 中断可能在 IO 阻塞的播放线程
        playThread?.let {
            it.interrupt()
            playThread = null
        }
        if (notifyComplete) {
            finishPlay()
        } else {
            // 切换曲目场景：清掉旧回调，不触发（新 play 会设置新回调）
            onCompleteCallback = null
        }
    }

    /** 触发 onComplete 回调（主线程，仅一次）。 */
    private fun finishPlay() {
        playing = false
        val cb = onCompleteCallback
        onCompleteCallback = null
        if (cb != null) {
            mainHandler.post { cb() }
        }
    }

    // ============================================================
    // WAV 头解析（扫描 chunks）
    // ============================================================

    /** 解析出的 WAV 关键信息。 */
    private data class WavHeader(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,  // data chunk 数据起始绝对字节偏移
    )

    /**
     * 读取 WAV 文件头，提取采样率/声道/位深 + 定位 data chunk。
     * 兼容标准 44 字节头与带额外 chunk（如 LIST/INFO）的 WAV。
     */
    private fun readWavHeader(file: File): WavHeader {
        FileInputStream(file).use { fis ->
            // RIFF descriptor: "RIFF" + size(4) + "WAVE"
            val riff = ByteArray(12)
            require(fis.read(riff) == 12) { "WAV 文件过短（<12B）" }
            val riffId = String(riff, 0, 4, Charsets.US_ASCII)
            val waveId = String(riff, 8, 4, Charsets.US_ASCII)
            require(riffId == "RIFF" && waveId == "WAVE") { "非 WAV 文件（RIFF/WAVE 标识缺失）" }

            var sampleRate = 48000
            var channels = 1
            var bitsPerSample = 16
            var dataOffset = -1L

            val chunkHeader = ByteArray(8) // 4B chunkID + 4B chunkSize(LE)
            while (fis.read(chunkHeader) == 8) {
                val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val chunkSize = leInt(chunkHeader, 4).toLong() and 0xFFFFFFFFL
                when (chunkId) {
                    "fmt " -> {
                        // PCM fmt chunk 至少 16 字节（PCMWAVEFORMAT）
                        val want = chunkSize.coerceAtMost(16L).toInt()
                        val fmt = ByteArray(want)
                        require(fis.read(fmt) == want) { "fmt chunk 读取失败" }
                        if (want >= 16) {
                            channels = leShort(fmt, 2)
                            sampleRate = leInt(fmt, 4)
                            bitsPerSample = leShort(fmt, 14)
                        }
                        // 跳过 fmt 剩余字节（WAVE_FORMAT_EXTENSIBLE 等会更大）
                        val remain = chunkSize - want
                        if (remain > 0) fis.skip(remain)
                        // chunks 按偶数字节对齐（odd size 后有 1 padding byte）
                        if (chunkSize % 2L != 0L) fis.skip(1)
                    }
                    "data" -> {
                        // 当前 fis 位置即 data 数据起始
                        dataOffset = fis.channel.position()
                        // 不再继续读，data 内容由播放循环按需读取
                        break
                    }
                    else -> {
                        // 跳过未知 chunk（LIST/ fact / ...）
                        val skip = chunkSize + if (chunkSize % 2L != 0L) 1L else 0L
                        fis.skip(skip)
                    }
                }
            }

            require(dataOffset >= 0) { "WAV 无 data chunk" }
            require(channels in 1..2) { "不支持的声道数: $channels" }
            require(bitsPerSample in listOf(8, 16, 24, 32)) { "不支持的位深: $bitsPerSample" }

            return WavHeader(sampleRate, channels, bitsPerSample, dataOffset)
        }
    }

    /** 小端序读取 2 字节为无符号 short（Int 返回）。 */
    private fun leShort(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    /** 小端序读取 4 字节为 int。 */
    private fun leInt(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)
}
