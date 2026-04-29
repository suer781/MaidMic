// NativeAudioProcessor.kt — JNI 音频处理单例 + 引擎选择
// ============================================================
// 支持切换处理引擎：
//   PASSTHROUGH — 直通模式，不做任何处理
//   ECHIO_EQ    — Echio 均衡引擎（增益/低音/高音/混响/变调）
//   FREQ_CURVE  — 频响曲线引擎（HxCore 风格多段均衡 + 前级预渲染）

package aoeck.dwyai.com

import android.content.SharedPreferences

// ============================================================
// 频响曲线预设（借鉴 HXAudio/HubeRSoundX 的 HxCore 算法）
// ============================================================
// 10 个标准频段：31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz
// 值 = 增益 (dB)
data class FreqCurvePreset(
    val name: String,
    val description: String,
    /** 主 EQ 各频段增益 (dB) */
    val bands: FloatArray,
    /** 前级预渲染增益 (dB)，可为 null */
    val preRender: FloatArray? = null
)

object CurvePresets {
    /** 标准 Iso 频段中心频率 */
    val STANDARD_FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    /** 平坦（不做处理） */
    val FLAT = FreqCurvePreset("平坦", "无频响调整", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))

    /** JBL Flip 风格（低频增强） */
    val JBL_FLIP = FreqCurvePreset("JBL Flip",
        "模拟 JBL Flip 音箱频响，低频澎湃",
        floatArrayOf(0f, 0f, 10f, 7f, 1.2f, 0f, -2f, -2.5f, 0f, 0f))

    /** JBL PartyBox 风格（超重低音） */
    val JBL_PARTYBOX = FreqCurvePreset("JBL PartyBox",
        "模拟 JBL PartyBox 超重低音",
        floatArrayOf(0f, 9f, 3.6f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))

    /** 环绕声（左声道） */
    val SURROUND_L = FreqCurvePreset("环绕声 L",
        "左声道环绕声频响",
        floatArrayOf(0f, 6f, 3f, 0f, 5f, 2f, 5f, 3f, 0f, 0f))

    /** 环绕声（右声道） */
    val SURROUND_R = FreqCurvePreset("环绕声 R",
        "右声道环绕声频响",
        floatArrayOf(0f, 5f, 0f, 0f, 2.5f, 0f, 3f, 3f, 0f, 0f))

    /** 人声增强 */
    val VOICE_BOOST = FreqCurvePreset("人声增强",
        "突出中频人声，适合语音场景",
        floatArrayOf(4f, 1.5f, -0.44f, 0.7f, 2f, -4.5f, 0.2f, 1.7f, 3f, 0.8f))

    /** 耳机优化（Hi-Fi 监听曲线） */
    val HEADPHONE_HIFI = FreqCurvePreset("耳机 Hi-Fi",
        "Harman 风格监听曲线，细节通透",
        floatArrayOf(2f, 1f, 0f, -0.5f, 0f, 1f, 2f, 3f, 4f, 5f),
        floatArrayOf(0f, 0f, -2f, -1f, 0f, 1f, 0f, -1f, 0f, 2f))

    /** 低音增强（带前级预渲染） */
    val BASS_BOOST_PRE = FreqCurvePreset("低音增强+",
        "前级预渲染 + 多段 EQ，深度低频",
        floatArrayOf(0f, 3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        floatArrayOf(0f, 6f, 4f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))

    /** 所有预设列表 */
    val ALL = listOf(FLAT, JBL_FLIP, JBL_PARTYBOX, SURROUND_L, SURROUND_R,
                     VOICE_BOOST, HEADPHONE_HIFI, BASS_BOOST_PRE)
}

// ============================================================
// 音频处理引擎枚举
// ============================================================
enum class AudioEngine(val key: String, val displayName: String, val description: String) {
    PASSTHROUGH("passthrough", "直通模式", "不进行任何音频处理"),
    ECHIO_EQ("echio_eq", "Echio 均衡", "增益/低音/高音/混响/变调"),
    FREQ_CURVE("freq_curve", "频响曲线", "HxCore 风格 10 段均衡 + 前级预渲染"),
}

object NativeAudioProcessor {

    private var loaded = false
    private var currentEngine: AudioEngine = AudioEngine.ECHIO_EQ

    /** 当前频响曲线预设索引 */
    var currentCurvePreset: Int = 0
        private set

    // ============================================================
    // 引擎切换
    // ============================================================

    fun getEngine(): AudioEngine = currentEngine

    fun setEngine(engine: AudioEngine): Boolean {
        currentEngine = engine
        return true
    }

    fun loadEngine(prefs: SharedPreferences) {
        val saved = prefs.getString(KEY_ENGINE, AudioEngine.ECHIO_EQ.key) ?: AudioEngine.ECHIO_EQ.key
        currentEngine = AudioEngine.entries.find { it.key == saved } ?: AudioEngine.ECHIO_EQ
        currentCurvePreset = prefs.getInt(KEY_CURVE_PRESET, 0)
    }

    fun saveEngine(prefs: SharedPreferences) {
        prefs.edit().putString(KEY_ENGINE, currentEngine.key)
            .putInt(KEY_CURVE_PRESET, currentCurvePreset).apply()
    }

    /** 切换频响曲线预设 */
    fun setCurvePreset(index: Int) {
        currentCurvePreset = index.coerceIn(0, CurvePresets.ALL.size - 1)
        val preset = CurvePresets.ALL[currentCurvePreset]
        nativeSetFreqCurve(preset.bands, preset.preRender, 48000)
    }

    // ============================================================
    // JNI 加载与处理
    // ============================================================

    fun ensureLoaded() {
        if (!loaded) {
            try {
                System.loadLibrary("maidmic_jni")
                loaded = true
                // 初始化默认频响曲线
                setCurvePreset(0)
            } catch (_: UnsatisfiedLinkError) {
                loaded = false
            }
        }
    }

    /** 设置 EQ 参数（Echio 引擎使用） */
    fun setEqParams(gainDb: Float, bassDb: Float, trebleDb: Float, reverbMix: Float, pitchSemitones: Int,
                    formantShift: Float = 0f, distortion: Float = 0f,
                    echoDelayMs: Float = 0f, echoDecay: Float = 0f) {
        if (!loaded) return
        nativeSetEqParams(gainDb, bassDb, trebleDb, reverbMix, pitchSemitones,
                          formantShift, distortion, echoDelayMs, echoDecay)
    }

    /** 设置混响、变调和新效果参数（频响曲线引擎共享使用） */
    fun setReverbPitch(reverbMix: Float, pitchSemitones: Int,
                       formantShift: Float = 0f, distortion: Float = 0f,
                       echoDelayMs: Float = 0f, echoDecay: Float = 0f) {
        if (!loaded) return
        nativeSetEqParams(0f, 0f, 0f, reverbMix, pitchSemitones,
                          formantShift, distortion, echoDelayMs, echoDecay)
    }

    /** 处理一帧音频（16-bit PCM） */
    fun processAudio(input: ByteArray, output: ByteArray, size: Int) {
        if (size <= 0) return

        when (currentEngine) {
            AudioEngine.PASSTHROUGH -> {
                System.arraycopy(input, 0, output, 0, size)
            }
            AudioEngine.ECHIO_EQ -> {
                if (!loaded) {
                    System.arraycopy(input, 0, output, 0, size)
                    return
                }
                nativeProcessAudio(input, output, size)
            }
            AudioEngine.FREQ_CURVE -> {
                if (!loaded) {
                    System.arraycopy(input, 0, output, 0, size)
                    return
                }
                nativeProcessFreqCurve(input, output, size)
            }
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

    private external fun nativeSetFreqCurve(
        bands: FloatArray, preRender: FloatArray?, sampleRate: Int
    )

    private external fun nativeProcessFreqCurve(input: ByteArray, output: ByteArray, size: Int)

    private const val KEY_ENGINE = "audio_engine"
    private const val KEY_CURVE_PRESET = "curve_preset"
}
