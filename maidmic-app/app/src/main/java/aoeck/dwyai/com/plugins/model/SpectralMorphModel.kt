// plugins/model/SpectralMorphModel.kt — 内置参考模型：谱包络音色变换
// ============================================================
// Tier 3 模型插件的**内置参考实现**（无外部依赖，纯 Kotlin）：
//
//   算法：STFT → 每帧谱包络估计（对数域滑动平滑）→ 包络频率搬移
//         （female/male 色谱比例 r）→ 重塑输出谱 → iFFT + 重叠相加。
//   这是经典的"谱包络搬移"式音色变换（统计声学模型的最简形态），
//   与引擎内的 LPC 极点旋转互为补充：本模型全频带搬移（含高频），
//   且展示模型插件的完整接口形态。
//
//   真 RVC（HuBERT 内容特征 + F0 + 声码器）等模型插件实现同一个
//   ModelVoicePlugin 接口即可被宿主加载——宿主只认 PCM 进出，
//   不感知模型内部（ONNX Runtime / ncnn 均可作为其推理后端）。

package aoeck.dwyai.com.plugins.model

import aoeck.dwyai.com.plugins.core.ModelVoicePlugin
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class SpectralMorphModel : ModelVoicePlugin {

    override val pluginId = "built_in.spectral_morph"
    override val pluginName = "音色变换模型（内置参考）"
    override val pluginAuthor = "MaidMic"
    override val pluginDescription =
        "STFT 谱包络搬移式音色变换（无依赖参考实现）。展示模型插件接口，" +
            "真 RVC 模型插件实现同一接口即可替换。"

    /** 包络搬移比例：>1 共振峰上移（偏女声/童声），<1 下移（偏男声） */
    @Volatile private var warpRatio = 1.15f

    private val fftSize = 1024
    private val hopSize = 256
    private lateinit var window: FloatArray

    override fun loadModel(modelFile: File?): Boolean {
        // 内置模型无外部文件；若提供 json 配置可读取 warpRatio
        window = FloatArray(fftSize) { i ->
            0.5f - 0.5f * cos(2.0 * PI * i / fftSize).toFloat()
        }
        return true
    }

    /** 设置包络搬移比例（半音 → 比例），供宿主/配置调用 */
    fun setWarpSemitones(st: Float) {
        warpRatio = Math.pow(2.0, st / 12.0).toFloat()
    }

    override fun convert(input: ShortArray, sampleRate: Int): ShortArray {
        if (!this::window.isInitialized) loadModel(null)

        val n = input.size
        val x = FloatArray(n) { input[it] / 32768.0f }
        val y = FloatArray(n + fftSize)
        val olaWeight = FloatArray(n + fftSize)

        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)

        var frameStart = 0
        while (frameStart + fftSize <= n) {
            // ---- 分析：加窗 → FFT ----
            for (i in 0 until fftSize) {
                re[i] = x[frameStart + i] * window[i]
                im[i] = 0f
            }
            fft(re, im, false)

            // ---- 谱包络估计（对数域滑动平滑，近似恒 Q）----
            val half = fftSize / 2
            val logMag = FloatArray(half)
            for (i in 0 until half) {
                logMag[i] = ln(sqrt(re[i] * re[i] + im[i] * im[i]) + 1e-10f)
            }
            val envelope = FloatArray(half)
            for (i in 0 until half) {
                // 平滑窗宽随频率增长（bin/16，下限 4）→ 近似 1/3 倍频程
                val w = max(4, i / 16)
                val lo = max(0, i - w)
                val hi = min(half, i + w + 1)
                var acc = 0f
                for (j in lo until hi) acc += logMag[j]
                envelope[i] = acc / (hi - lo)
            }

            // ---- 包络搬移：新包络 E'(f) = E(f·r)，重塑谱 = mag × E'/E ----
            for (i in 1 until half) {
                val src = i / warpRatio
                if (src < 0f || src > half - 1) continue
                val i0 = src.toInt()
                val i1 = min(i0 + 1, half - 1)
                val frac = src - i0
                val target = envelope[i0] + (envelope[i1] - envelope[i0]) * frac
                val correction = exp(target - envelope[i])
                re[i] *= correction
                im[i] *= correction
                // 共轭对称
                re[fftSize - i] = re[i]
                im[fftSize - i] = -im[i]
            }

            // ---- 合成：iFFT + 加窗重叠相加（Hann² @ hop/4 归一 2/3）----
            fft(re, im, true)
            val norm = (2.0f / 3.0f) * warpCompensation
            for (i in 0 until fftSize) {
                y[frameStart + i] += re[i] * window[i] * norm
                olaWeight[frameStart + i] += window[i] * window[i]
            }
            frameStart += hopSize
        }

        // 除以 OLA 权重（精确重构，无 COLA 近似误差）
        val out = ShortArray(n)
        for (i in 0 until n) {
            val w = if (olaWeight[i] > 1e-4f) olaWeight[i] else 1f
            var v = (y[i] / w) * 32767.0f
            if (v > 32767.0f) v = 32767.0f
            if (v < -32768.0f) v = -32768.0f
            out[i] = v.toInt().toShort()
        }
        return out
    }

    /** 包络搬移造成的能量变化补偿（经验值：与 log(r) 成比例） */
    private val warpCompensation: Float
        get() = 1.0f / sqrt(warpRatio)

    override fun release() {}

    // ============================================================
    // 迭代基-2 FFT（同址；inverse = 共轭法）
    // ============================================================

    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        // 位反转置换
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j and bit.inv()
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        // 蝶形
        val sign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val ang = sign * 2.0 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1.0f
                var curIm = 0.0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            for (i in 0 until n) {
                re[i] = re[i] / n
                im[i] = im[i] / n
            }
        }
    }
}
