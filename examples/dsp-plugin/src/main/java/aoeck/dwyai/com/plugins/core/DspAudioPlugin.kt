// MaidMic Tier 2 DSP 插件示例的接口副本
// 与宿主 App 的 aoeck.dwyai.com.plugins.core.DspAudioPlugin 保持一致。
// 插件构建时 compileOnly 本文件（宿主运行时提供实现加载）。

package aoeck.dwyai.com.plugins.core

interface DspAudioPlugin {
    val pluginId: String
    val pluginName: String
    val pluginAuthor: String
    val pluginDescription: String

    fun init(sampleRate: Int, channels: Int)

    fun process(samples: FloatArray, frames: Int, channels: Int)

    fun release()
}
