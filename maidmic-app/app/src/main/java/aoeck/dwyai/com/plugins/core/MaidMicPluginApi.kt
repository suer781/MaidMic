// plugins/core/MaidMicPluginApi.kt — 万物皆插件：统一插件契约
// ============================================================
// 三层插件能力模型（capability tiers）：
//
//   Tier 1  PARAM  (Lua 沙箱)     参数型效果 —— 见 PluginManager / PLUGIN_API.md
//   Tier 2  DSP    (dex, UGC 门)  自定义音频处理：实时链内逐块处理
//   Tier 3  MODEL  (dex, UGC 门)  自定义模型（RVC / ONNX / 统计模型）：
//                                 离线对语音包做整段转换
//
// Tier 2/3 均为 Kotlin 接口，插件以 dex/apk 形式分发（DexClassLoader 加载），
// 必须在开发者设置开启 UGC 后才会加载（任意代码执行风险，用户自担）。
//
// RVC 接入路径：实现 ModelVoicePlugin.convert()，内部用 ONNX Runtime /
// ncnn 跑内容编码器 + F0 + 声码器即可，宿主不感知模型类型——
// 架构上模型插件与引擎完全解耦，输入输出都是 PCM。

package aoeck.dwyai.com.plugins.core

import android.content.Context

/**
 * Tier 2 —— 自定义 DSP 插件（实时，链内）。
 *
 * 生命周期：load → [init]（采样率/声道确定）→ N × [process] → [release]。
 * [process] 在录音/播放音频线程调用，必须无阻塞、无分配（推荐预分配缓冲），
 * 原地修改 samples 后返回（约定返回值仅用于校验，宿主以原地内容为准）。
 */
interface DspAudioPlugin {
    val pluginId: String
    val pluginName: String
    val pluginAuthor: String
    val pluginDescription: String

    /** 音频流开始时调用（采样率/声道变化会重新 init） */
    fun init(sampleRate: Int, channels: Int)

    /**
     * 逐块处理（原地）。samples 为交错样本，float 域 [-1, 1)。
     * frames × channels = samples.size。
     */
    fun process(samples: FloatArray, frames: Int, channels: Int)

    /** 释放资源（插件停用或宿主退出） */
    fun release()
}

/**
 * Tier 3 —— 自定义模型插件（离线，整段转换）。
 *
 * 生命周期：load → [loadModel]（可下载/解压模型文件）→ N × [convert] → [release]。
 * convert 在后台线程调用，可耗时（非实时）；输入输出均为 16-bit PCM 单声道。
 * RVC / so-vits / 统计模型等任意推理后端都实现此接口，宿主只认 PCM 进出。
 */
interface ModelVoicePlugin {
    val pluginId: String
    val pluginName: String
    val pluginAuthor: String
    val pluginDescription: String

    /**
     * 加载模型。modelFile 为插件目录下的模型文件（.onnx/.bin/.zip 等，
     * 由插件自行解释）；返回 false = 模型不可用（UI 报错）。
     */
    fun loadModel(modelFile: java.io.File?): Boolean

    /**
     * 整段转换：输入 16-bit PCM，返回转换后的 16-bit PCM（长度可不同）。
     * 实现方自行管理重采样到模型采样率、F0、声码器等。
     */
    fun convert(input: ShortArray, sampleRate: Int): ShortArray

    /** 释放模型资源 */
    fun release()
}

/** 插件安全门槛（Tier 2/3 是任意代码执行，须用户显式开启 UGC） */
object PluginSecurity {
    private const val KEY_UGC_ENABLED = "ugc_enabled"

    fun isUgcEnabled(context: Context): Boolean =
        context.getSharedPreferences("maidmic_prefs", Context.MODE_PRIVATE)
            .getBoolean(KEY_UGC_ENABLED, false)
}
