// AudioSink.kt — 统一音频输出接口
// ============================================================
// 所有音频输出的抽象层。
// 实现类：SpeakerSink, AppAudioSink, NullSink, ...

package aoeck.dwyai.com.audio

import android.media.AudioFormat

interface AudioSink {
    /** 写入 PCM 数据用于播放 */
    fun write(buffer: ByteArray, size: Int)

    /** 启动音频输出 */
    fun start(): Boolean

    /** 停止音频输出 */
    fun stop()

    /** 释放所有资源 */
    fun release()

    /** 采样率 (Hz) */
    val sampleRate: Int

    /** 声道配置 */
    val channelConfig: Int

    /** 编码格式 */
    val audioFormat: Int

    /** 是否正在输出 */
    val isRunning: Boolean

    /** 显示名称 */
    val displayName: String
}
