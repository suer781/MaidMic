// PipelineController.kt — 模块链管线控制器（全局单例）
// ============================================================
// 封装 maidmic_jni.cpp 中的 pipeline JNI 调用，提供 Kotlin 友好的 API。
//
// 职责：
//   1. 管理"编辑器即默认管线"：持有录音处理实际使用的默认管线句柄
//      （nativeGetDefaultPipeline 返回的 g_default_pipeline），模块链的
//      增删/排序/旁路/参数全部实时作用于该管线（Task 4：消除双管线不同步）。
//   2. 在 Kotlin 侧镜像模块链状态（ModuleInstance 列表），供 UI 显示与快照
//   3. 提供 snapshotCurrentChain() 供语音包录制时快照（Task 5）
//   4. 提供 resetDspState() 清模块状态，避免包间残响
//
// 注意：
//   - 默认管线句柄由引擎管理生命周期（JNI_OnUnload 统一销毁），本控制器不销毁。
//   - initDefaultChain 会把默认管线清空并按 DEFAULT_CHAIN 重建，
//     故录音 nativeProcessAudio 实际处理链 == 编辑器链，参数实时生效。
//   - 模块 ID 与 maidmic-engine/include/maidmic/module.h 完全对齐。

package aoeck.dwyai.com

import android.content.SharedPreferences
import aoeck.dwyai.com.voicepack.ChainSnapshot
import aoeck.dwyai.com.voicepack.ModuleState

object PipelineController {

    // ============================================================
    // 模块 ID 常量（对齐 module.h）
    // ============================================================
    const val MODULE_GAIN = 1                  // 增益
    const val MODULE_COMPRESSOR = 3            // 压缩器
    const val MODULE_PITCH = 4                 // 变调
    const val MODULE_REVERB = 5                // 混响
    const val MODULE_DISTORTION = 7            // 失真
    const val MODULE_BASS = 11                 // 低音 Shelving
    const val MODULE_TREBLE = 12               // 高音 Shelving
    const val MODULE_FORMANT = 13              // 共振峰偏移
    const val MODULE_ECHO = 14                 // 回声/延迟

    // ============================================================
    // 默认模块链顺序（编辑器链，initDefaultChain 重建默认管线时按此挂载）
    // ============================================================
    // Gain → Compressor → Bass → Treble → Reverb → Pitch → Formant → Distortion → Echo
    // 注意：此链与 C++ ensure_default_pipeline 预置链完全一致（双链统一）。
    // initDefaultChain 会清空 C++ 预置模块再按本链重建，
    // 因此录音处理链与编辑器链始终保持一致。
    val DEFAULT_CHAIN = listOf(
        MODULE_GAIN, MODULE_COMPRESSOR, MODULE_BASS, MODULE_TREBLE,
        MODULE_REVERB, MODULE_PITCH, MODULE_FORMANT, MODULE_DISTORTION, MODULE_ECHO
    )

    // C++ ensure_default_pipeline 预置模块数（9 个，与 DEFAULT_CHAIN 一致）。
    // initDefaultChain 接管默认管线时需先清空这些预置模块再重建；
    // 若 C++ 侧调整预置数量，需同步更新此值。
    private const val DEFAULT_PIPELINE_PREPOPULATED_MODULES = 9

    // ============================================================
    // 模块实例数据类
    // ============================================================
    /**
     * 管线中的模块实例。
     *
     * @param id 实例 ID（递增分配，唯一，对应 UI 的 nodeId）
     * @param moduleId 模块类型 ID（对齐 module.h）
     * @param bypass 是否旁路
     * @param params 参数键值对（key 对齐 C++ 端参数名）
     */
    data class ModuleInstance(
        val id: Int,
        val moduleId: Int,
        var bypass: Boolean = false,
        val params: MutableMap<String, Float> = mutableMapOf()
    )

    // ============================================================
    // 状态
    // ============================================================

    /** 默认管线句柄（录音实际处理管线 g_default_pipeline；0 表示不可用） */
    private var pipelineHandle: Long = 0L

    /** 实例 ID 自增计数器 */
    private var nextInstanceId: Int = 1

    /** 模块链镜像（Kotlin 侧状态，按管线顺序） */
    private val _chain: MutableList<ModuleInstance> = mutableListOf()

    /** 只读链视图（供 UI 读取） */
    val chain: List<ModuleInstance> get() = _chain.toList()

    /** 管线是否已初始化 */
    val isInitialized: Boolean get() = pipelineHandle != 0L && _chain.isNotEmpty()

    // ============================================================
    // 通用管线操作（基于句柄）
    // ============================================================
    // 以下方法直接封装 JNI 调用，可作用于任意管线句柄。
    // 同时维护 Kotlin 侧镜像，供 UI 与快照使用。

    /** 获取录音处理使用的默认管线句柄（0 表示不可用） */
    fun create(): Long {
        // nativeGetDefaultPipeline 依赖 JNI；确保 .so 已加载，加载失败时降级为镜像-only
        NativeAudioProcessor.ensureLoaded()
        if (NativeAudioProcessor.getHealth() != EngineHealth.OK) {
            AppLogger.e("Pipeline", "JNI 不可用（${NativeAudioProcessor.getHealth()}），无法获取默认管线，模块链编辑降级为镜像-only（不生效于录音）")
            return 0L
        }
        val handle = NativeAudioProcessor.nativeGetDefaultPipeline()
        if (handle == 0L) {
            AppLogger.e("Pipeline", "nativeGetDefaultPipeline 返回 0：默认管线不可用")
        }
        return handle
    }

    /**
     * 默认管线句柄由引擎管理生命周期（JNI_OnUnload 统一销毁），不可手动销毁。
     * 保留此方法仅为接口兼容，实际为 no-op。
     */
    @Suppress("UNUSED_PARAMETER")
    fun destroy(handle: Long) {
        // no-op：Task 4 起编辑器即默认管线，不调用 nativePipelineDestroy
    }

    /**
     * 添加模块到管线末尾。
     * @param handle 管线句柄
     * @param moduleId 模块类型 ID
     * @return 新增实例的 ID（用于 UI nodeId）；失败返回 -1
     */
    fun addModule(handle: Long, moduleId: Int): Int {
        val index = NativeAudioProcessor.nativePipelineAddModule(handle, moduleId)
        if (index < 0) {
            AppLogger.e("Pipeline", "addModule 失败: moduleId=$moduleId")
            return -1
        }
        return index
    }

    /** 按索引移除模块 */
    fun removeModule(handle: Long, index: Int) {
        NativeAudioProcessor.nativePipelineRemoveModule(handle, index)
    }

    /** 重排序模块（from → to） */
    fun reorder(handle: Long, from: Int, to: Int) {
        NativeAudioProcessor.nativePipelineReorder(handle, from, to)
    }

    /** 交换两个模块位置 */
    fun swap(handle: Long, i: Int, j: Int) {
        NativeAudioProcessor.nativePipelineSwap(handle, i, j)
    }

    /** 设置模块参数（按索引） */
    fun setParam(handle: Long, index: Int, paramKey: String, value: Float) {
        NativeAudioProcessor.nativePipelineSetParam(handle, index, paramKey, value)
    }

    /** 处理音频（自定义管线） */
    fun process(handle: Long, input: ByteArray, output: ByteArray, size: Int) {
        NativeAudioProcessor.nativePipelineProcess(handle, input, output, size)
    }

    /** 重置管线中所有模块的状态 */
    fun reset(handle: Long) {
        NativeAudioProcessor.nativePipelineReset(handle)
    }

    // ============================================================
    // 默认管线管理（编辑器即默认管线）
    // ============================================================
    // 管理录音处理实际使用的默认管线句柄（g_default_pipeline）。
    // 初始化时清空 C++ 预置模块并按 DEFAULT_CHAIN 重建，参数从 maidmic_eq prefs 读取。

    /** 确保默认管线句柄已获取 */
    private fun ensurePipeline() {
        if (pipelineHandle == 0L) {
            pipelineHandle = create()
        }
    }

    /**
     * 初始化默认模块链。
     * 获取录音处理使用的默认管线句柄，清空 C++ 预置模块后按 DEFAULT_CHAIN 重建，
     * 使编辑器链与录音 nativeProcessAudio 处理链为同一条管线（增删/排序/旁路/参数实时生效）。
     * 模块参数从 maidmic_eq prefs 读取（与 EqPage 共享），使 UI 显示用户当前设置。
     *
     * @param eqPrefs maidmic_eq SharedPreferences（可为 null，此时用零值）
     */
    fun initDefaultChain(eqPrefs: SharedPreferences? = null) {
        ensurePipeline()
        if (pipelineHandle == 0L) {
            AppLogger.e("Pipeline", "initDefaultChain: 默认管线不可用")
            return
        }

        // 清空默认管线（含 C++ ensure_default_pipeline 预置模块），随后按编辑器链重建
        clearChain()

        // 从 prefs 读取当前参数（与 EqPage 共享 maidmic_eq）
        val gainDb = eqPrefs?.getFloat("gain", 0f) ?: 0f
        val bassDb = eqPrefs?.getFloat("bass", 0f) ?: 0f
        val trebleDb = eqPrefs?.getFloat("treble", 0f) ?: 0f
        val reverbMix = eqPrefs?.getFloat("reverb", 0f) ?: 0f
        val pitchSemitones = eqPrefs?.getInt("pitch", 0)?.toFloat() ?: 0f
        val formantShift = eqPrefs?.getFloat("formant", 0f) ?: 0f
        val distortion = eqPrefs?.getFloat("distortion", 0f) ?: 0f
        val echoDelayMs = eqPrefs?.getFloat("echo_delay", 0f) ?: 0f
        val echoDecay = eqPrefs?.getFloat("echo_decay", 0f) ?: 0f
        // 压缩机参数暂无 prefs 存储，使用默认值
        val compThreshold = -20f
        val compRatio = 2f
        val compMakeup = 0f

        // 按默认顺序添加模块
        DEFAULT_CHAIN.forEach { moduleId ->
            val params: MutableMap<String, Float> = when (moduleId) {
                MODULE_GAIN -> mutableMapOf("gain_db" to gainDb)
                MODULE_COMPRESSOR -> mutableMapOf(
                    "comp_threshold" to compThreshold,
                    "comp_ratio" to compRatio,
                    "comp_makeup" to compMakeup
                )
                MODULE_BASS -> mutableMapOf("bass_db" to bassDb)
                MODULE_TREBLE -> mutableMapOf("treble_db" to trebleDb)
                MODULE_REVERB -> mutableMapOf("reverb_mix" to reverbMix)
                MODULE_PITCH -> mutableMapOf("pitch_semitones" to pitchSemitones)
                MODULE_FORMANT -> mutableMapOf("formant_shift" to formantShift)
                MODULE_DISTORTION -> mutableMapOf("distortion" to distortion)
                MODULE_ECHO -> mutableMapOf(
                    "echo_delay_ms" to echoDelayMs,
                    "echo_decay" to echoDecay
                )
                else -> mutableMapOf()
            }

            val index = addModule(pipelineHandle, moduleId)
            if (index >= 0) {
                // 推送参数到默认管线（录音实际处理管线）
                params.forEach { (key, value) ->
                    setParam(pipelineHandle, index, key, value)
                }
            }
            _chain.add(ModuleInstance(id = nextInstanceId++, moduleId = moduleId, params = params))
        }

        AppLogger.i("Pipeline", "默认链已初始化: ${_chain.size} 个模块 (handle=$pipelineHandle)")
    }

    /** 清空链镜像并从默认管线移除所有模块（含 C++ 预置模块） */
    private fun clearChain() {
        if (pipelineHandle == 0L) {
            _chain.clear()
            return
        }
        // 首次接管默认管线时，镜像为空而 native 已有 C++ 预置模块，
        // 需记录以在镜像清空后继续移除预置模块。
        val hadNativePrepopulated = _chain.isEmpty()
        // 先按镜像从后往前移除（保持 native 与镜像同步，避免索引错位）
        while (_chain.isNotEmpty()) {
            val lastIndex = _chain.lastIndex
            removeModule(pipelineHandle, lastIndex)
            _chain.removeAt(lastIndex)
        }
        if (hadNativePrepopulated) {
            // C++ ensure_default_pipeline 预置模块：反复移除索引 0（移除后后续节点前移），
            // 数量见 DEFAULT_PIPELINE_PREPOPULATED_MODULES
            repeat(DEFAULT_PIPELINE_PREPOPULATED_MODULES) {
                removeModule(pipelineHandle, 0)
            }
        }
    }

    // ============================================================
    // 镜像操作（供 UI 回调使用）
    // ============================================================
    // 这些方法操作默认管线（编辑器即默认管线）的镜像，同时同步到 native 管线。

    /**
     * 添加模块到默认管线末尾。
     * @param moduleId 模块类型 ID
     * @return 新增实例的 ID；失败返回 -1
     */
    fun addModule(moduleId: Int): Int {
        ensurePipeline()
        if (pipelineHandle == 0L) return -1

        val index = addModule(pipelineHandle, moduleId)
        if (index < 0) return -1

        // 使用默认参数
        val defaultParams = defaultParamsFor(moduleId)
        defaultParams.forEach { (key, value) ->
            setParam(pipelineHandle, index, key, value)
        }
        val instance = ModuleInstance(
            id = nextInstanceId++,
            moduleId = moduleId,
            params = defaultParams.toMutableMap()
        )
        _chain.add(instance)
        AppLogger.i("Pipeline", "添加模块: ${moduleDisplayName(moduleId)} (instance=${instance.id}, index=$index)")
        return instance.id
    }

    /** 按索引移除模块（同步 native + 镜像） */
    fun removeModule(index: Int) {
        if (index < 0 || index >= _chain.size) return
        if (pipelineHandle != 0L) {
            removeModule(pipelineHandle, index)
        }
        val removed = _chain.removeAt(index)
        AppLogger.i("Pipeline", "移除模块: ${moduleDisplayName(removed.moduleId)} (index=$index)")
    }

    /** 按实例 ID 移除模块 */
    fun removeModuleById(id: Int) {
        val index = _chain.indexOfFirst { it.id == id }
        if (index >= 0) removeModule(index)
    }

    /** 重排序模块（from → to） */
    fun reorder(from: Int, to: Int) {
        if (from < 0 || from >= _chain.size) return
        if (to < 0 || to >= _chain.size) return
        if (from == to) return
        if (pipelineHandle != 0L) {
            reorder(pipelineHandle, from, to)
        }
        val item = _chain.removeAt(from)
        _chain.add(to, item)
        AppLogger.i("Pipeline", "重排序: $from → $to")
    }

    /** 按索引设置参数 */
    fun setParam(index: Int, paramKey: String, value: Float) {
        if (index < 0 || index >= _chain.size) return
        _chain[index].params[paramKey] = value
        if (pipelineHandle != 0L) {
            setParam(pipelineHandle, index, paramKey, value)
        }
    }

    /** 按实例 ID 设置参数 */
    fun setParamById(id: Int, paramKey: String, value: Float) {
        val index = _chain.indexOfFirst { it.id == id }
        if (index >= 0) setParam(index, paramKey, value)
    }

    /** 按索引设置旁路状态（同步 native 模块 bypass，实时生效于录音） */
    fun setBypass(index: Int, bypass: Boolean) {
        if (index < 0 || index >= _chain.size) return
        _chain[index].bypass = bypass
        // 编辑器即默认管线：bypass 需同步到 native 节点（maidmic_pipeline_set_module_bypass），
        // 使录音处理实时旁路该模块（Task 4）
        if (pipelineHandle != 0L) {
            NativeAudioProcessor.nativePipelineSetModuleBypass(pipelineHandle, index, bypass)
        }
    }

    /** 按实例 ID 设置旁路状态 */
    fun setBypassById(id: Int, bypass: Boolean) {
        val index = _chain.indexOfFirst { it.id == id }
        if (index >= 0) setBypass(index, bypass)
    }

    /** 处理音频（通过默认管线，与录音 nativeProcessAudio 一致） */
    fun process(input: ByteArray, output: ByteArray, size: Int) {
        ensurePipeline()
        if (pipelineHandle == 0L) {
            System.arraycopy(input, 0, output, 0, size)
            return
        }
        process(pipelineHandle, input, output, size)
    }

    // ============================================================
    // 快照与状态重置
    // ============================================================

    /**
     * 获取当前模块链快照（供 Task 5 语音包录制用）。
     * 返回 ChainSnapshot，包含引擎标识与各模块状态。
     */
    fun snapshotCurrentChain(): ChainSnapshot {
        val engine = NativeAudioProcessor.getEngine().name
        val modules = _chain.map { inst ->
            ModuleState(
                moduleId = inst.moduleId,
                params = inst.params.toMap(),
                bypass = inst.bypass
            )
        }
        return ChainSnapshot(engine = engine, modules = modules)
    }

    /**
     * 重置 DSP 模块状态（清模块内部状态，避免包间残响）。
     * 重置默认管线中所有模块的内部状态（混响尾音、延迟缓冲等）。
     */
    fun resetDspState() {
        ensurePipeline()
        if (pipelineHandle != 0L) {
            reset(pipelineHandle)
            AppLogger.i("Pipeline", "DSP 模块状态已重置 (handle=$pipelineHandle)")
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /** 各模块默认参数（对齐 C++ 端默认值） */
    private fun defaultParamsFor(moduleId: Int): Map<String, Float> = when (moduleId) {
        MODULE_GAIN -> mapOf("gain_db" to 0f)
        MODULE_COMPRESSOR -> mapOf("comp_threshold" to -20f, "comp_ratio" to 2f, "comp_makeup" to 0f)
        MODULE_BASS -> mapOf("bass_db" to 0f)
        MODULE_TREBLE -> mapOf("treble_db" to 0f)
        MODULE_REVERB -> mapOf("reverb_mix" to 0f)
        MODULE_PITCH -> mapOf("pitch_semitones" to 0f)
        MODULE_FORMANT -> mapOf("formant_shift" to 0f)
        MODULE_DISTORTION -> mapOf("distortion" to 0f)
        MODULE_ECHO -> mapOf("echo_delay_ms" to 0f, "echo_decay" to 0f)
        else -> emptyMap()
    }

    /** 模块显示名（日志用） */
    private fun moduleDisplayName(moduleId: Int): String = when (moduleId) {
        MODULE_GAIN -> "Gain"
        MODULE_COMPRESSOR -> "Compressor"
        MODULE_PITCH -> "Pitch"
        MODULE_REVERB -> "Reverb"
        MODULE_DISTORTION -> "Distortion"
        MODULE_BASS -> "Bass"
        MODULE_TREBLE -> "Treble"
        MODULE_FORMANT -> "Formant"
        MODULE_ECHO -> "Echo"
        else -> "Module($moduleId)"
    }
}
