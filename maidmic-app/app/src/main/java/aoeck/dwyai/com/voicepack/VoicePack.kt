// VoicePack.kt — 语音包数据模型
// ============================================================
// MaidMic "语音包" = 一段已录制的 WAV + 录制时的引擎链快照。
// 用于：悬浮球双击播放最近一条、语音包列表管理、还原链设置。
//
// 设计要点：
//   - 纯数据 data class，无 Android 依赖，方便序列化与测试。
//   - chainSnapshot 记录录制时的引擎与模块链状态，便于"应用此语音包的链设置"。
//   - moduleId 对应 BUILTIN_MODULES (ui/editor/ModuleChainEditor.kt) 的 id。
//   - params 用 Map<String, Float> 统一存储数值参数 (BOOL 参数 0/1)。

package aoeck.dwyai.com.voicepack

/**
 * 语音包元数据。
 *
 * @param id 唯一 ID (UUID 字符串)。
 * @param name 显示名，默认 "语音包 yyyy-MM-dd HH:mm"。
 * @param wavFile WAV 文件相对路径，形如 "voicepacks/<id>.wav"。
 * @param durationMs 音频时长 (毫秒)。
 * @param sampleRate 采样率，默认 48000。
 * @param createdAt 创建时间戳 (System.currentTimeMillis())。
 * @param chainSnapshot 录制时的引擎链快照。
 */
data class VoicePack(
    val id: String,
    val name: String,
    val wavFile: String,
    val durationMs: Long,
    val sampleRate: Int,
    val createdAt: Long,
    val chainSnapshot: ChainSnapshot,
)

/**
 * 引擎链快照：记录录制时使用的引擎 + 模块链状态。
 *
 * @param engine 引擎标识，如 "ECHIO_EQ" (对应 AudioEngine.name)。
 * @param modules 模块链状态列表 (按管线顺序)。
 */
data class ChainSnapshot(
    val engine: String,
    val modules: List<ModuleState>,
)

/**
 * 单个模块的状态快照。
 *
 * @param moduleId 模块 ID，对应 BUILTIN_MODULES 中的 DspModuleInfo.id。
 * @param params 参数键值对 (key → 数值)。BOOL 参数以 0/1 表示。
 * @param bypass 是否旁路该模块。
 */
data class ModuleState(
    val moduleId: Int,
    val params: Map<String, Float>,
    val bypass: Boolean,
)
