// NativeAudioProcessor.kt — JNI 音频处理单例
// ============================================================
// 封装 EQ 参数设置和音频处理调用

package aoeck.dwyai.com

object NativeAudioProcessor {

    private var loaded = false

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

    /** 处理一帧音频（16-bit PCM，原地修改 output 数组） */
    fun processAudio(input: ByteArray, output: ByteArray, size: Int) {
        if (!loaded || size <= 0) {
            if (size > 0) System.arraycopy(input, 0, output, 0, size)
            return
        }
        nativeProcessAudio(input, output, size)
    }

    private external fun nativeSetEqParams(
        gainDb: Float, bassDb: Float, trebleDb: Float,
        reverbMix: Float, pitchSemitones: Int
    )

    private external fun nativeProcessAudio(input: ByteArray, output: ByteArray, size: Int)
}
