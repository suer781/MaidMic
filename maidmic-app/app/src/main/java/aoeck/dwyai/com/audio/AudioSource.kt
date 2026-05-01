// AudioSource.kt — 统一音频源接口
// ============================================================
// 所有音频输入的抽象层。
// 实现类：MicSource, PlaybackCaptureSource, AppSandboxSource, ...

package aoeck.dwyai.com.audio

import android.media.AudioFormat

interface AudioSource {
    /** 读取 PCM 数据到 buffer，返回实际读取的字节数 */
    fun read(buffer: ByteArray): Int

    /** 启动音频源捕获 */
    fun start(): Boolean

    /** 停止音频源捕获 */
    fun stop()

    /** 释放所有资源 */
    fun release()

    /** 采样率 (Hz) */
    val sampleRate: Int

    /** 声道配置 (AudioFormat.CHANNEL_IN_MONO 等) */
    val channelConfig: Int

    /** 编码格式 (AudioFormat.ENCODING_PCM_16BIT 等) */
    val audioFormat: Int

    /** 是否正在捕获 */
    val isRunning: Boolean

    /** 显示名称 */
    val displayName: String
}
