// NativeAudioProcessor.kt — JNI 音频处理单例 + 引擎选择
// ============================================================
// 支持切换处理引擎：
//   PASSTHROUGH — 直通模式，不做任何处理
//   ECHIO_EQ    — Echio 均衡引擎（增益/低音/高音/混响/变调）

package aoeck.dwyai.com

import android.content.Context
import android.content.SharedPreferences

// ============================================================
// 音频处理引擎枚举
// ============================================================
enum class AudioEngine(val key: String, val displayName: String, val description: String) {
    PASSTHROUGH("passthrough", "直通模式", "不进行任何音频处理"),
    ECHIO_EQ("echio_eq", "Echio 均衡", "增益/低音/高音/混响/变调"),
}

object NativeAudioProcessor {

    private var loaded = false
    private var currentEngine: AudioEngine = AudioEngine.ECHIO_EQ

    // ============================================================
    // 引擎切换
    // ============================================================

    /** 获取当前引擎 */
    fun getEngine(): AudioEngine = currentEngine

    /** 切换引擎，返回是否成功 */
    fun setEngine(engine: AudioEngine): Boolean {
        currentEngine = engine
        return true
    }

    /** 从持久化存储恢复引擎设置 */
    fun loadEngine(prefs: SharedPreferences) {
        val saved = prefs.getString(KEY_ENGINE, AudioEngine.ECHIO_EQ.key) ?: AudioEngine.ECHIO_EQ.key
        currentEngine = AudioEngine.entries.find { it.key == saved } ?: AudioEngine.ECHIO_EQ
    }

    /** 保存引擎设置到持久化存储 */
    fun saveEngine(prefs: SharedPreferences) {
        prefs.edit().putString(KEY_ENGINE, currentEngine.key).apply()
    }

    // ============================================================
    // JNI 加载与处理
    // ============================================================

    fun ensureLoaded() {
        if (!loaded) {
            try {
                System.loadLibrary("maidmic_jni")
                loaded = true
            } catch (_: UnsatisfiedLinkError) {
                loaded = false
            }
        }
    }

    /** 设置 EQ 参数（增益 dB, 低音 dB, 高音 dB, 混响 0~1, 变调半音） */
    fun setEqParams(gainDb: Float, bassDb: Float, trebleDb: Float, reverbMix: Float, pitchSemitones: Int) {
        if (!loaded) return
        nativeSetEqParams(gainDb, bassDb, trebleDb, reverbMix, pitchSemitones)
    }

    /** 处理一帧音频（16-bit PCM）
     *  根据当前引擎模式决定是否处理：
     *    PASSTHROUGH → 直接拷贝输入到输出
     *    ECHIO_EQ    → 调用 JNI 原生处理
     */
    fun processAudio(input: ByteArray, output: ByteArray, size: Int) {
        if (size <= 0) return

        when (currentEngine) {
            AudioEngine.PASSTHROUGH -> {
                // 直通：简单拷贝
                System.arraycopy(input, 0, output, 0, size)
            }
            AudioEngine.ECHIO_EQ -> {
                if (!loaded) {
                    System.arraycopy(input, 0, output, 0, size)
                    return
                }
                nativeProcessAudio(input, output, size)
            }
        }
    }

    private external fun nativeSetEqParams(
        gainDb: Float, bassDb: Float, trebleDb: Float,
        reverbMix: Float, pitchSemitones: Int
    )

    private external fun nativeProcessAudio(input: ByteArray, output: ByteArray, size: Int)

    private const val KEY_ENGINE = "audio_engine"
}
