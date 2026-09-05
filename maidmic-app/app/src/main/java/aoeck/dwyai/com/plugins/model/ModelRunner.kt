// plugins/model/ModelRunner.kt — 模型插件离线转换流程
// ============================================================
// 把模型插件应用到语音包：读 WAV → convert → 写新 WAV → 存为新语音包。
// 全程后台线程（模型推理可耗时，非实时）。

package aoeck.dwyai.com.plugins.model

import aoeck.dwyai.com.AppLogger
import aoeck.dwyai.com.PipelineController
import aoeck.dwyai.com.plugins.core.ModelVoicePlugin
import aoeck.dwyai.com.voicepack.VoicePack
import aoeck.dwyai.com.voicepack.VoicePackStore
import aoeck.dwyai.com.voicepack.WavWriter
import java.io.File
import java.util.UUID

object ModelRunner {

    private const val TAG = "ModelRunner"

    /**
     * 对指定语音包应用模型插件，生成新语音包。
     * @param onDone 主线程回调（转换成功的新包 / 失败错误信息）
     */
    fun applyToPack(
        context: android.content.Context,
        model: ModelVoicePlugin,
        pack: VoicePack,
        onDone: (VoicePack?, String?) -> Unit,
    ) {
        Thread {
            try {
                val srcFile = VoicePackStore.wavFile(context, pack.wavFile)
                val wav = WavReader.read(srcFile)

                // 模型契约：单声道 16-bit；立体声取平均下混
                val mono = if (wav.channels == 2) {
                    ShortArray(wav.pcm.size / 2) { i ->
                        ((wav.pcm[i * 2].toInt() + wav.pcm[i * 2 + 1].toInt()) / 2).toShort()
                    }
                } else {
                    wav.pcm
                }

                val converted = model.convert(mono, wav.sampleRate)
                if (converted.isEmpty()) {
                    throw IllegalStateException("模型输出为空")
                }

                // 写新 WAV
                val newId = UUID.randomUUID().toString()
                val relPath = VoicePackStore.relativeWavPath(newId)
                val outFile = VoicePackStore.wavFile(context, relPath)
                outFile.parentFile?.mkdirs()
                val writer = WavWriter(outFile, wav.sampleRate, channels = 1, bitsPerSample = 16)
                writer.start()
                val bytes = ByteArray(converted.size * 2)
                for (i in converted.indices) {
                    val v = converted[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                writer.writePcm16(bytes)
                writer.finish()

                // 存语音包元数据（链快照取当前链：模型输出仍可在主界面继续处理）
                val newPack = VoicePack(
                    id = newId,
                    name = VoicePackStore.defaultName() + " · " + model.pluginName,
                    wavFile = relPath,
                    durationMs = writer.estimatedDurationMs(),
                    sampleRate = wav.sampleRate,
                    createdAt = System.currentTimeMillis(),
                    chainSnapshot = PipelineController.snapshotCurrentChain(),
                )
                VoicePackStore.save(context, newPack)
                AppLogger.i(TAG, "模型转换完成: ${model.pluginId} → 包 ${newId} (${converted.size} 样本)")
                onDone(newPack, null)
            } catch (e: Exception) {
                AppLogger.e(TAG, "模型转换失败: ${model.pluginId}", e)
                onDone(null, e.message ?: "模型转换失败")
            }
        }.start()
    }
}
