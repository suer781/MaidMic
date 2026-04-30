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

// ============================================================
// 频响曲线预设（借鉴 HXAudio/HubeRSoundX 的 HxCore 算法）
// ============================================================
data class FreqCurvePreset(
    val name: String,
    val description: String,
    val bands: FloatArray,
    val preRender: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FreqCurvePreset) return false
        return name == other.name
    }
    override fun hashCode() = name.hashCode()
}

object CurvePresets {
    val STANDARD_FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val FLAT = FreqCurvePreset("平坦", "无频响调整", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
    val JBL_FLIP = FreqCurvePreset("JBL Flip", "模拟 JBL Flip 音箱频响，低频澎湃",
        floatArrayOf(0f, 0f, 10f, 7f, 1.2f, 0f, -2f, -2.5f, 0f, 0f))
    val JBL_PARTYBOX = FreqCurvePreset("JBL PartyBox", "模拟 JBL PartyBox 超重低音",
        floatArrayOf(0f, 9f, 3.6f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
    val SURROUND_L = FreqCurvePreset("环绕声 L", "左声道环绕声频响",
        floatArrayOf(0f, 6f, 3f, 0f, 5f, 2f, 5f, 3f, 0f, 0f))
    val SURROUND_R = FreqCurvePreset("环绕声 R", "右声道环绕声频响",
        floatArrayOf(0f, 5f, 0f, 0f, 2.5f, 0f, 3f, 3f, 0f, 0f))
    val VOICE_BOOST = FreqCurvePreset("人声增强", "突出中频人声，适合语音场景",
        floatArrayOf(4f, 1.5f, -0.44f, 0.7f, 2f, -4.5f, 0.2f, 1.7f, 3f, 0.8f))
    val HEADPHONE_HIFI = FreqCurvePreset("耳机 Hi-Fi", "Harman 风格监听曲线，细节通透",
        floatArrayOf(2f, 1f, 0f, -0.5f, 0f, 1f, 2f, 3f, 4f, 5f),
        floatArrayOf(0f, 0f, -2f, -1f, 0f, 1f, 0f, -1f, 0f, 2f))
    val BASS_BOOST_PRE = FreqCurvePreset("低音增强+", "前级预渲染 + 多段 EQ，深度低频",
        floatArrayOf(0f, 3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        floatArrayOf(0f, 6f, 4f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
    val ALL = listOf(FLAT, JBL_FLIP, JBL_PARTYBOX, SURROUND_L, SURROUND_R,
                     VOICE_BOOST, HEADPHONE_HIFI, BASS_BOOST_PRE)
}

enum class AudioEngine(val key: String, val displayName: String, val description: String) {
    PASSTHROUGH("passthrough", "直通模式", "不进行任何音频处理"),
    ECHIO_EQ("echio_eq", "Echio 均衡", "增益/低音/高音/混响/变调"),
    FREQ_CURVE("freq_curve", "频响曲线", "HxCore 风格 10 段均衡 + 前级预渲染"),
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
    private var currentEngine: AudioEngine = AudioEngine.FREQ_CURVE
    var currentCurvePreset: Int = 0
        private set

    // 降级参数缓存（JNI 加载后推送给 C++）
    private var pendingParams: EqParams? = null
    private var engineHealth: EngineHealth = EngineHealth.BROKEN

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
            val savedCurve = currentCurvePreset
            val savedParams = pendingParams
            if (loaded) {
                // 设一个明显非零参数确保引擎会处理
                nativeSetEqParams(5f, 0f, 0f, 0f, 0, 0f, 0f, 0f, 0f)
                // 也设频响曲线为非零
                val testBands = floatArrayOf(5f, 5f, 5f, 5f, 5f, 5f, 5f, 5f, 5f, 5f)
                nativeSetFreqCurve(testBands, null, 48000)
            }

            val output = ByteArray(sampleCount * 2)
            processAudio(input, output, sampleCount * 2)

            // 恢复引擎状态和参数（确保不自测污染用户设置）
            if (loaded) {
                if (savedEngine == AudioEngine.FREQ_CURVE) {
                    setCurvePreset(savedCurve)
                } else {
                    // 恢复 ECHIO_EQ 参数
                    savedParams?.let {
                        nativeSetEqParams(it.gainDb, it.bassDb, it.trebleDb, it.reverbMix,
                            it.pitchSemitones, it.formantShift, it.distortion,
                            it.echoDelayMs, it.echoDecay)
                    } ?: nativeSetEqParams(0f, 0f, 0f, 0f, 0, 0f, 0f, 0f, 0f)
                }
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
        val saved = prefs.getString(KEY_ENGINE, AudioEngine.FREQ_CURVE.key) ?: AudioEngine.FREQ_CURVE.key
        currentEngine = AudioEngine.entries.find { it.key == saved } ?: AudioEngine.FREQ_CURVE
        currentCurvePreset = prefs.getInt(KEY_CURVE_PRESET, 0)
        AppLogger.i("Engine", "从存储恢复: ${currentEngine.key}, 曲线预设=$currentCurvePreset")
    }

    fun saveEngine(prefs: SharedPreferences) {
        prefs.edit().putString(KEY_ENGINE, currentEngine.key)
            .putInt(KEY_CURVE_PRESET, currentCurvePreset).apply()
    }

    fun setCurvePreset(index: Int) {
        currentCurvePreset = index.coerceIn(0, CurvePresets.ALL.size - 1)
        val preset = CurvePresets.ALL[currentCurvePreset]
        if (loaded) {
            nativeSetFreqCurve(preset.bands, preset.preRender, 48000)
        }
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
            // 初始化默认频响曲线
            setCurvePreset(0)
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

        AppLogger.i("Engine", "setEqParams: gain=$g bass=$b bass=$t reverb=$r pitch=$p formant=$f dist=$d echo=${ed}ms decay=$ec")
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
                    AudioEngine.FREQ_CURVE -> {
                        nativeProcessFreqCurve(input, output, size)
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
    // JNI 声明
    // ============================================================

    private external fun nativeSetEqParams(
        gainDb: Float, bassDb: Float, trebleDb: Float,
        reverbMix: Float, pitchSemitones: Int,
        formantShift: Float, distortion: Float,
        echoDelayMs: Float, echoDecay: Float
    )
    private external fun nativeProcessAudio(input: ByteArray, output: ByteArray, size: Int)
    private external fun nativeSetFreqCurve(bands: FloatArray, preRender: FloatArray?, sampleRate: Int)
    private external fun nativeProcessFreqCurve(input: ByteArray, output: ByteArray, size: Int)
    private external fun nativeSetCompressor(thresholdDb: Float, ratio: Float, makeupGainDb: Float)

    private const val KEY_ENGINE = "audio_engine"
    private const val KEY_CURVE_PRESET = "curve_preset"
}
