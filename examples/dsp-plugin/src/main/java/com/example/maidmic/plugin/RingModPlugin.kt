// examples/dsp-plugin/RingModPlugin.kt — 示例 DSP 插件：环形调制机器人音
// ============================================================
// Tier 2 插件参考实现：实现 DspAudioPlugin 接口，实时处理音频块。
// 环形调制 = 载波正弦 × 输入 → 经典"机器人/金属"音色。
//
// 构建后得到 plugin_ringmod.apk，放入
// Android/data/aoeck.dwyai.com/files/maidmic_plugins_ext/
// 再到 设置 → 插件 → 扩展插件 打开开关即可生效。

package com.example.maidmic.plugin

import aoeck.dwyai.com.plugins.core.DspAudioPlugin
import kotlin.math.PI
import kotlin.math.sin

class RingModPlugin : DspAudioPlugin {

    override val pluginId = "example.ringmod"
    override val pluginName = "环形调制机器人（示例）"
    override val pluginAuthor = "MaidMic"
    override val pluginDescription = "载波 30Hz 环形调制，机器人音色。Tier 2 DSP 插件示例。"

    private var sampleRate = 48000
    private var carrierPhase = 0.0
    private val carrierHz = 30.0

    override fun init(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.carrierPhase = 0.0
    }

    override fun process(samples: FloatArray, frames: Int, channels: Int) {
        val inc = 2.0 * PI * carrierHz / sampleRate
        for (i in 0 until frames) {
            carrierPhase += inc
            if (carrierPhase > 2.0 * PI) carrierPhase -= 2.0 * PI
            val carrier = sin(carrierPhase).toFloat()
            for (c in 0 until channels) {
                val idx = i * channels + c
                samples[idx] = samples[idx] * carrier
            }
        }
    }

    override fun release() {}
}
