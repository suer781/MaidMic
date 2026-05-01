// AudioRouter.kt — 统一音频管线编排
// ============================================================
// 将 AudioSource → AudioProcessor → AudioSink 串联起来。
// 源、处理器、输出均可运行时替换。
//
// 线程安全：内部 capture thread 和 stop() 通过 volatile + join 保证。

package aoeck.dwyai.com.audio

import aoeck.dwyai.com.AppLogger
import aoeck.dwyai.com.NativeAudioProcessor

class AudioRouter(
    /** 音频源 */
    var source: AudioSource? = null,
    /** 音频输出 */
    var sink: AudioSink? = null
) {
    companion object {
        private const val TAG = "AudioRouter"
    }

    @Volatile
    private var running = false
    private var captureThread: Thread? = null

    /** 是否正在运行 */
    val isRunning: Boolean get() = running

    // ============================================================
    // 生命周期
    // ============================================================

    /**
     * 启动音频管线：source → processor(NativeAudioProcessor) → sink
     * 运行在独立线程。
     */
    fun start(): Boolean {
        if (running) {
            AppLogger.w(TAG, "已在运行，跳过")
            return true
        }
        if (source == null || sink == null) {
            AppLogger.e(TAG, "source 或 sink 未设置")
            return false
        }

        // 确保引擎已加载
        NativeAudioProcessor.ensureLoaded()

        // 启动源和输出
        if (!source!!.start()) {
            AppLogger.e(TAG, "source.start() 失败")
            return false
        }
        if (!sink!!.start()) {
            AppLogger.e(TAG, "sink.start() 失败")
            source!!.stop()
            return false
        }

        running = true
        captureThread = Thread({
            captureLoop()
        }, "maidmic-router-${source!!.displayName}")
        captureThread?.start()

        AppLogger.i(TAG, "管线启动: ${source!!.displayName} → Echio → ${sink!!.displayName}")
        return true
    }

    /**
     * 停止音频管线。
     */
    fun stop() {
        if (!running) return
        running = false

        captureThread?.join(3000)
        captureThread = null

        source?.stop()
        sink?.stop()

        AppLogger.i(TAG, "管线停止")
    }

    /**
     * 释放所有资源。
     */
    fun release() {
        stop()
        source?.release()
        sink?.release()
        source = null
        sink = null
    }

    // ============================================================
    // 内部
    // ============================================================

    private fun captureLoop() {
        val src = source ?: return
        val snk = sink ?: return

        // 缓冲区大小基于源/输出配置
        val bufSize = 4096  // 足够容纳 ~46ms @44.1kHz mono 16bit
        val buffer = ByteArray(bufSize)
        val processed = ByteArray(bufSize)

        AppLogger.i(TAG, "捕获循环开始 (buffer=${bufSize}bytes)")

        while (running) {
            try {
                val bytesRead = src.read(buffer)
                if (bytesRead > 0) {
                    // Echio 引擎变声
                    NativeAudioProcessor.processAudio(buffer, processed, bytesRead)

                    // 输出
                    snk.write(processed, bytesRead)
                } else if (bytesRead < 0) {
                    // 源已关闭
                    AppLogger.w(TAG, "源返回 $bytesRead，停止管线")
                    break
                }
            } catch (e: Exception) {
                if (running) {
                    AppLogger.e(TAG, "捕获循环异常", e)
                }
                break
            }
        }

        AppLogger.i(TAG, "捕获循环结束")
    }
}
