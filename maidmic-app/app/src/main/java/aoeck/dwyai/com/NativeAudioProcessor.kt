// NativeAudioProcessor.kt — JNI 音频处理单例 + 引擎选择 + 容错降级
// ============================================================
// 三层容错：
//   1. JNI 正常 — 全功能 DSP（C++ 处理）
//   2. JNI 加载失败 — Kotlin 纯软件降级（仅增益）
//   3. 全失败 — 直通（什么都不做）
//
// 自检：processAudio 前后对比样本，验证引擎确实在干活。

package aoeck.dwyai.com

import android.content.SharedPreferences
import android.util.Log

enum class AudioEngine(val key: String, val displayName: String, val description: String) {
    PASSTHROUGH("passthrough", "直通模式", "不进行任何音频处理"),
    ECHIO_EQ("echio_eq", "Echio 均衡", "增益/低音/高音/混响/变调"),
}

/** 引擎健康状态 */
enum class EngineHealth {
    OK,              // JNI 工作正常
    FALLBACK,        // JNI 加载失败，使用 Kotlin 降级
    BROKEN,          // 完全不可用
}

object NativeAudioProcessor {

    private var loaded = false
    private var jniLoadAttempted = false
    // Task 1: 默认引擎改为 ECHIO_EQ，冷启动即走 nativeProcessAudio 全 Step1~8
    private var currentEngine: AudioEngine = AudioEngine.ECHIO_EQ

    // 降级参数缓存（JNI 加载后推送给 C++）
    private var pendingParams: EqParams? = null
    private var engineHealth: EngineHealth = EngineHealth.BROKEN

    // 上次已推送并记录日志的参数（用于高频滑块更新时的日志降级）
    private var lastLogParams: EqParams? = null

    private data class EqParams(
        val gainDb: Float, val bassDb: Float, val trebleDb: Float,
        val reverbMix: Float, val pitchSemitones: Int,
        val formantShift: Float, val distortion: Float,
        val echoDelayMs: Float, val echoDecay: Float
    )

    // ============================================================
    // 引擎健康状态
    // ============================================================
    fun getHealth(): EngineHealth = engineHealth

    /** 自检：生成一个已知正弦波，处理它，验证输出不同于输入 */
    fun selfTest(): Boolean {
        return try {
            // 生成 100 样本的 1kHz 正弦波 @48kHz
            val sampleCount = 100
            val input = ByteArray(sampleCount * 2) // 16-bit
            for (i in 0 until sampleCount) {
                val sample = (Math.sin(2.0 * Math.PI * i * 1000.0 / 48000.0) * 8000).toInt().toShort()
                input[i * 2] = (sample.toInt() and 0xFF).toByte()
                input[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }

            // 保存原引擎状态和参数
            val savedEngine = currentEngine
            val savedParams = pendingParams
            if (loaded) {
                // 设一个明显非零参数确保引擎会处理
                nativeSetEqParams(5f, 0f, 0f, 0f, 0, 0f, 0f, 0f, 0f)
            }

            val output = ByteArray(sampleCount * 2)
            processAudio(input, output, sampleCount * 2)

            // 恢复引擎状态和参数（确保不自测污染用户设置）
            if (loaded) {
                // 恢复 ECHIO_EQ 参数
                savedParams?.let {
                    nativeSetEqParams(it.gainDb, it.bassDb, it.trebleDb, it.reverbMix,
                        it.pitchSemitones, it.formantShift, it.distortion,
                        it.echoDelayMs, it.echoDecay)
                } ?: nativeSetEqParams(0f, 0f, 0f, 0f, 0, 0f, 0f, 0f, 0f)
            }
            currentEngine = savedEngine

            // 验证输出和输入不同（引擎确实修改了音频）
            var diff = 0
            for (i in 0 until minOf(input.size, output.size)) {
                if (input[i] != output[i]) { diff++; if (diff >= 5) break }
            }
            val passed = diff >= 5
            AppLogger.i("SelfTest", "引擎自检: ${if (passed) "通过" else "失败"} (差异样本数=$diff)")
            passed
        } catch (e: Exception) {
            AppLogger.e("SelfTest", "自检异常", e)
            // 确保恢复引擎状态
            false
        }
    }

    // ============================================================
    // 引擎切换
    // ============================================================

    fun getEngine(): AudioEngine = currentEngine

    fun setEngine(engine: AudioEngine): Boolean {
        AppLogger.i("Engine", "切换引擎: ${currentEngine.key} -> ${engine.key}")
        currentEngine = engine
        return true
    }

    fun loadEngine(prefs: SharedPreferences) {
        // Task 1: 缺省引擎改为 ECHIO_EQ（冷启动即可走 nativeProcessAudio 全 Step1~8）
        val saved = prefs.getString(KEY_ENGINE, AudioEngine.ECHIO_EQ.key) ?: AudioEngine.ECHIO_EQ.key
        currentEngine = AudioEngine.entries.find { it.key == saved } ?: AudioEngine.ECHIO_EQ
        AppLogger.i("Engine", "从存储恢复: ${currentEngine.key}")
    }

    fun saveEngine(prefs: SharedPreferences) {
        prefs.edit().putString(KEY_ENGINE, currentEngine.key).apply()
    }

    // ============================================================
    // JNI 加载（带三层降级）
    // ============================================================

    fun ensureLoaded() {
        if (loaded) return
        if (jniLoadAttempted) {
            // 已经试过加载但失败了，直接使用降级
            return
        }

        jniLoadAttempted = true
        try {
            System.loadLibrary("maidmic_jni")
            loaded = true
            engineHealth = EngineHealth.OK
            AppLogger.i("Engine", "JNI加载成功，引擎健康")
            // 推送缓存的参数
            pendingParams?.let {
                nativeSetEqParams(it.gainDb, it.bassDb, it.trebleDb, it.reverbMix,
                    it.pitchSemitones, it.formantShift, it.distortion,
                    it.echoDelayMs, it.echoDecay)
                pendingParams = null
                AppLogger.i("Engine", "缓存参数已推送")
            }
            // 运行自检
            selfTest()
        } catch (e: UnsatisfiedLinkError) {
            loaded = false
            engineHealth = EngineHealth.FALLBACK
            AppLogger.e("Engine", "JNI加载失败，使用Kotlin降级", e)
        } catch (e: Exception) {
            loaded = false
            engineHealth = EngineHealth.BROKEN
            AppLogger.e("Engine", "引擎完全不可用", e)
        }
    }

    /** 重置引擎状态（给开发者选项使用） */
    fun resetEngine() {
        loaded = false
        jniLoadAttempted = false
        engineHealth = EngineHealth.BROKEN
        pendingParams = null
        AppLogger.i("Engine", "引擎状态已重置")
    }

    // ============================================================
    // 参数设置（容错：JNI 未加载时缓存参数）
    // ============================================================

    fun setEqParams(gainDb: Float, bassDb: Float, trebleDb: Float, reverbMix: Float, pitchSemitones: Int,
                    formantShift: Float = 0f, distortion: Float = 0f,
                    echoDelayMs: Float = 0f, echoDecay: Float = 0f) {
        // 参数有效性检查（防止 NaN/Infinity 崩溃 C++ 引擎）
        val safe = { v: Float -> v.takeIf { it.isFinite() } ?: 0f }
        val g = safe(gainDb); val b = safe(bassDb); val t = safe(trebleDb)
        val r = safe(reverbMix).coerceIn(0f, 1f)
        val p = pitchSemitones.coerceIn(-12, 12)
        val f = safe(formantShift).coerceIn(-12f, 12f)
        val d = safe(distortion).coerceIn(0f, 1f)
        val ed = safe(echoDelayMs).coerceIn(0f, 2000f)
        val ec = safe(echoDecay).coerceIn(0f, 0.9f)

        if (!loaded) {
            // 缓存参数，等 JNI 加载后再推送
            pendingParams = EqParams(g, b, t, r, p, f, d, ed, ec)
            AppLogger.w("Engine", "setEqParams: JNI未加载，已缓存 (${engineHealth})")
            return
        }

        val now = EqParams(g, b, t, r, p, f, d, ed, ec)
        // 高频滑块拖动时避免每帧刷环形缓冲：仅当参数跨大步长变化时记录 INFO，
        // 微调（<0.05）时只同步 native，不写内存日志。
        val last = lastLogParams
        val changedSignificantly = last == null ||
            kotlin.math.abs(last.gainDb - g) > 0.05f ||
            kotlin.math.abs(last.bassDb - b) > 0.05f ||
            kotlin.math.abs(last.trebleDb - t) > 0.05f ||
            kotlin.math.abs(last.reverbMix - r) > 0.05f ||
            last.pitchSemitones != p ||
            kotlin.math.abs(last.formantShift - f) > 0.05f ||
            kotlin.math.abs(last.distortion - d) > 0.05f ||
            kotlin.math.abs(last.echoDelayMs - ed) > 0.05f ||
            kotlin.math.abs(last.echoDecay - ec) > 0.05f
        lastLogParams = now
        if (changedSignificantly) {
            AppLogger.i("Engine", "setEqParams: gain=$g bass=$b treble=$t reverb=$r pitch=$p formant=$f dist=$d echo=${ed}ms decay=$ec")
        }
        nativeSetEqParams(g, b, t, r, p, f, d, ed, ec)
    }

    fun setReverbPitch(reverbMix: Float, pitchSemitones: Int,
                       formantShift: Float = 0f, distortion: Float = 0f,
                       echoDelayMs: Float = 0f, echoDecay: Float = 0f) {
        setEqParams(0f, 0f, 0f, reverbMix, pitchSemitones,
                    formantShift, distortion, echoDelayMs, echoDecay)
    }

    /** 设置压缩机参数 */
    fun setCompressor(thresholdDb: Float, ratio: Float, makeupGainDb: Float) {
        val t = thresholdDb.coerceIn(-60f, 0f)
        val r = ratio.coerceIn(1f, 20f)
        val m = makeupGainDb.coerceIn(0f, 20f)
        if (!loaded) {
            AppLogger.w("Engine", "setCompressor: JNI未加载")
            return
        }
        AppLogger.i("Engine", "setCompressor: threshold=$t ratio=$r makeup=$m")
        nativeSetCompressor(t, r, m)
    }

    // ============================================================
    // 音频处理（三层降级）
    // ============================================================

    fun processAudio(input: ByteArray, output: ByteArray, size: Int) {
        if (size <= 0) return
        if (size > input.size || size > output.size) {
            AppLogger.w("Engine", "processAudio: size($size)超出缓冲区(${input.size}/${output.size})")
            System.arraycopy(input, 0, output, 0, minOf(size, input.size, output.size))
            return
        }

        when {
            // 第一层: JNI 全功能处理
            loaded -> {
                when (currentEngine) {
                    AudioEngine.PASSTHROUGH -> {
                        System.arraycopy(input, 0, output, 0, size)
                    }
                    AudioEngine.ECHIO_EQ -> {
                        nativeProcessAudio(input, output, size)
                    }
                }
            }
            // 第二层: Kotlin 纯软件降级（仅增益）
            engineHealth == EngineHealth.FALLBACK -> {
                processFallback(input, output, size)
            }
            // 第三层: 直通（什么都不做）
            else -> {
                System.arraycopy(input, 0, output, 0, size)
            }
        }
    }

    /** Kotlin 纯软件降级处理（仅增益 + 样本拷贝） */
    private fun processFallback(input: ByteArray, output: ByteArray, size: Int) {
        val gain = pendingParams?.gainDb ?: 0f
        if (kotlin.math.abs(gain) < 0.5f) {
            // 增益接近零，直通
            System.arraycopy(input, 0, output, 0, size)
            return
        }
        val gainLinear = Math.pow(10.0, (gain / 20.0).toDouble()).toFloat()
        val sampleCount = size / 2
        for (i in 0 until sampleCount) {
            val idx = i * 2
            if (idx + 1 >= size) break
            val sample = ((input[idx].toInt() and 0xFF) or ((input[idx + 1].toInt() and 0xFF) shl 8)).toShort()
            val processed = (sample * gainLinear).toInt().coerceIn(-32768, 32767)
            output[idx] = (processed and 0xFF).toByte()
            output[idx + 1] = ((processed shr 8) and 0xFF).toByte()
        }
    }

    // ============================================================
    // 重置 DSP 模块状态
    // ============================================================
    // 清除管线中各模块的内部状态（如混响尾音、延迟缓冲），
    // 避免语音包间残留残响。委托给 PipelineController 处理。
    fun resetDspState() {
        if (!loaded) {
            AppLogger.w("Engine", "resetDspState: JNI未加载，跳过")
            return
        }
        PipelineController.resetDspState()
        AppLogger.i("Engine", "DSP 模块状态已重置")
    }

    // ============================================================
    // JNI 声明
    // ============================================================

    private external fun nativeSetEqParams(
        gainDb: Float, bassDb: Float, trebleDb: Float,
        reverbMix: Float, pitchSemitones: Int,
        formantShift: Float, distortion: Float,
        echoDelayMs: Float, echoDecay: Float
    )
    private external fun nativeProcessAudio(input: ByteArray, output: ByteArray, size: Int)
    private external fun nativeSetCompressor(thresholdDb: Float, ratio: Float, makeupGainDb: Float)

    // ============================================================
    // Task 6b 新增 JNI 声明（引擎侧 maidmic_jni.cpp 对应实现）
    // ============================================================
    // 与 maidmic_jni.cpp 中新增的 JNI 函数一一对应：
    //   nativeSetAutoTune       → Java_aoeck_dwyai_com_NativeAudioProcessor_nativeSetAutoTune
    //   nativeSetNoiseGate      → ..._nativeSetNoiseGate
    //   nativeSetLimiter        → ..._nativeSetLimiter
    //   nativeSetPresence       → ..._nativeSetPresence
    //   nativeSetVoiceprintMask → ..._nativeSetVoiceprintMask
    //   nativeGetEngineStats    → ..._nativeGetEngineStats（返回 {totalNs, totalFrames, callCount}）
    //   nativeNeonEnabled       → ..._nativeNeonEnabled
    // 可选模块（AutoTune/Presence/VoiceprintMask）在引擎侧默认加入默认管线但 bypass，
    // 调用对应 nativeSet* 即启用；AutoTune 的 enabled 参数控制 bypass。

    /** 设置自动调音参数（enabled 控制模块旁路，scale=音阶，retune/speed 为 0~1） */
    external fun nativeSetAutoTune(enabled: Boolean, scale: Int, retune: Float, speed: Float)

    /** 设置噪声门参数（阈值 dB、attack/release ms） */
    external fun nativeSetNoiseGate(thresholdDb: Float, attackMs: Float, releaseMs: Float)

    /** 设置限制器参数（阈值 dB、release ms） */
    external fun nativeSetLimiter(thresholdDb: Float, releaseMs: Float)

    /** 设置存在感模块参数（dB），调用即启用该模块 */
    external fun nativeSetPresence(presenceDb: Float)

    /** 设置声纹掩码模块参数（强度 0~1、模式），调用即启用该模块 */
    external fun nativeSetVoiceprintMask(strength: Float, mode: Int)

    /** 设置颤音模块参数（rate Hz、depth 半音），enabled 控制旁路；模块不在链中时自动挂载 */
    external fun nativeSetVibrato(rateHz: Float, depthSt: Float, enabled: Boolean)

    /** 设置合唱模块参数（mix 0~1、rate Hz、depth ms）；mix=0 自动旁路 */
    external fun nativeSetChorus(mix: Float, rateHz: Float, depthMs: Float)

    /** 设置降比特模块参数（bits 1~16、down 1~32、mix 0~1）；mix=0 自动旁路 */
    external fun nativeSetBitcrusher(bits: Float, down: Float, mix: Float)

    /** 获取默认管线累计处理统计：{totalNs, totalFrames, callCount} */
    external fun nativeGetEngineStats(): LongArray

    /** 查询当前构建是否启用 ARM NEON SIMD */
    external fun nativeNeonEnabled(): Boolean

    // ============================================================
    // 新模块参数设置包装（容错：JNI 未加载时静默跳过）
    // ============================================================

    /** 设置颤音（rate Hz、depth 半音）；enabled=false 关闭 */
    fun setVibrato(rateHz: Float, depthSt: Float, enabled: Boolean) {
        if (!loaded) return
        nativeSetVibrato(rateHz, depthSt, enabled)
    }

    /** 设置合唱（mix 0~1、rate Hz、depth ms）；mix=0 关闭 */
    fun setChorus(mix: Float, rateHz: Float, depthMs: Float) {
        if (!loaded) return
        nativeSetChorus(mix, rateHz, depthMs)
    }

    /** 设置降比特（bits 1~16、down 1~32、mix 0~1）；mix=0 关闭 */
    fun setBitcrusher(bits: Float, down: Float, mix: Float) {
        if (!loaded) {
            AppLogger.w("Engine", "setBitcrusher: JNI未加载，跳过")
            return
        }
        nativeSetBitcrusher(bits, down, mix)
    }

    // ============================================================
    // Pipeline 管理 JNI 声明（对齐 maidmic_jni.cpp）
    // ============================================================
    // 以下 11 个函数对应 maidmic_jni.cpp 中的 nativePipeline* 系列。
    // 声明为 public（不带 internal）以避免 Kotlin 对 external 函数可能的名称修饰，
    // 确保 JNI 符号 Java_aoeck_dwyai_com_NativeAudioProcessor_nativePipeline* 正确链接。
    // 供同模块的 PipelineController 直接调用。

    /**
     * 获取录音处理使用的默认管线句柄（g_default_pipeline，nativeProcessAudio 内部使用）。
     * 由引擎管理生命周期（JNI_OnUnload 统一销毁），不可用 nativePipelineDestroy 释放；
     * 0 表示默认管线不可用。
     */
    external fun nativeGetDefaultPipeline(): Long

    /** 创建管线实例，返回句柄（0 表示失败） */
    external fun nativePipelineCreate(): Long

    /** 销毁管线实例 */
    external fun nativePipelineDestroy(pipelinePtr: Long)

    /** 添加模块到管线末尾，返回节点索引（-1 表示失败） */
    external fun nativePipelineAddModule(pipelinePtr: Long, moduleId: Int): Int

    /** 按索引移除模块 */
    external fun nativePipelineRemoveModule(pipelinePtr: Long, index: Int)

    /** 重排序模块（from → to，通过 swap 实现） */
    external fun nativePipelineReorder(pipelinePtr: Long, from: Int, to: Int)

    /** 交换两个模块位置 */
    external fun nativePipelineSwap(pipelinePtr: Long, i: Int, j: Int)

    /** 设置模块参数（按索引，参数 key 为字符串，值为 float） */
    external fun nativePipelineSetParam(pipelinePtr: Long, index: Int, paramKey: String, value: Float)

    /** 按索引设置模块旁路状态（对应 maidmic_pipeline_set_module_bypass，实时生效于录音） */
    external fun nativePipelineSetModuleBypass(pipelinePtr: Long, index: Int, bypass: Boolean)

    /** 处理音频（自定义管线） */
    external fun nativePipelineProcess(pipelinePtr: Long, input: ByteArray, output: ByteArray, size: Int)

    /** 重置管线中所有模块的状态 */
    external fun nativePipelineReset(pipelinePtr: Long)

    private const val KEY_ENGINE = "audio_engine"

    /** 音效库页当前已应用的预设音效包名（写入 "maidmic_eq" prefs，供 EffectsLibraryPage 持久化"已应用"状态） */
    const val KEY_APPLIED_EFFECT_PACK = "applied_effect_pack"
}
