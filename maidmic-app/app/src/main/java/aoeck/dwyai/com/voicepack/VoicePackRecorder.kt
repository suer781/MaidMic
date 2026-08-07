// VoicePackRecorder.kt — 预录变声链路：录音 → 变声 → 存包
// ============================================================
// Task 5: 实现"边录边变声边写 WAV"的完整链路。
//
// 设计要点：
//   - 录音用 AudioRecord (48k / mono / 16bit)，逐块读取。
//   - 录音与变声处理在同一个录音线程内串行执行：
//       AudioRecord.read(buf) → NativeAudioProcessor.processAudio(buf→out)
//                              → WavWriter.writePcm16(out)
//     无需中间临时 PCM 文件（流式设计，省一次磁盘 IO）。
//   - 录音前快照当前 DSP 链（ChainSnapshot），随 VoicePack 一起持久化，
//     便于"应用此语音包的链设置"。
//   - 录音前调用 NativeAudioProcessor.resetDspState() 清模块状态，
//     避免上一个语音包的混响/回声残响泄漏到新包。
//   - PTT 模式：stopRecording(delayMs > 0) 用 Handler.postDelayed 延迟停止，
//     松手后继续录 0.5s 尾音。
//   - overwrite 模式：复用最近一条语音包的 id 和 WAV 文件名，覆盖录音。
//   - 录制中状态可查询（isRecording()），UI 据此禁用 DSP 链菜单。
//   - 不调系统音量（项目铁律）。
//   - 错误处理：权限/初始化失败/线程异常均回调 onError 并清理资源。
//
// 用法：
//   val recorder = VoicePackRecorder(context)
//   recorder.startRecording(object : VoicePackRecorder.Callback {
//       override fun onRecordingStart() { ... }
//       override fun onRecordingStop(pack: VoicePack?) { ... }
//       override fun onError(msg: String) { ... }
//   })
//   // ... PTT 松手：
//   recorder.stopRecording(delayMs = 500)

package aoeck.dwyai.com.voicepack

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID
import aoeck.dwyai.com.AppLogger
import aoeck.dwyai.com.NativeAudioProcessor

/**
 * 预录变声录音器：录音 → 变声 → 存包。
 *
 * @param context 应用上下文（用于权限、文件目录、SharedPreferences）。
 */
class VoicePackRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VoicePackRecorder"

        // 录音格式：48kHz / 单声道 / 16bit PCM
        private const val SAMPLE_RATE = 48000
        private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // 处理块大小：1024 samples = 2048 bytes（16bit mono）
        // 与 native pipeline 的处理粒度对齐，平衡延迟与 CPU 开销。
        private const val PROCESS_BLOCK_SAMPLES = 1024
        private const val PROCESS_BLOCK_BYTES = PROCESS_BLOCK_SAMPLES * 2 // 2048
    }

    /**
     * 录音回调。所有方法均在主线程回调（内部通过 Handler 转发）。
     */
    interface Callback {
        /** 录音正式开始（AudioRecord.startRecording 之后）。 */
        fun onRecordingStart()
        /** 录音停止，携带生成的 VoicePack；失败或空录音时为 null。 */
        fun onRecordingStop(voicePack: VoicePack?)
        /** 出错。录音会被终止并清理资源。 */
        fun onError(message: String)
    }

    // 主线程 Handler：用于延迟停止 + 主线程回调
    private val mainHandler = Handler(Looper.getMainLooper())

    // 录音状态（@Volatile 供 UI 线程查询）
    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null
    private var wavWriter: WavWriter? = null
    private var recordingThread: Thread? = null
    private var recordingStartTime = 0L
    private var currentChainSnapshot: ChainSnapshot? = null
    private var callback: Callback? = null

    // 录音目标文件信息（overwrite 模式下复用最近一条的 id 与 wavFile）
    private var packId: String = ""
    private var wavRelativePath: String = ""
    private var wavFile: File? = null

    // 延迟停止的 Runnable 引用（用于取消）
    private var pendingStopRunnable: Runnable? = null

    /** 当前是否正在录音。供 UI 查询以禁用 DSP 链菜单。 */
    fun isRecording(): Boolean = recording

    // ============================================================
    // 启动录音
    // ============================================================

    /**
     * 开始录音。
     *
     * @param callback 录音回调。
     * @param overwrite true 时覆盖最近一条语音包（复用其 id 与 WAV 文件名）。
     */
    fun startRecording(callback: Callback, overwrite: Boolean = false) {
        if (recording) {
            AppLogger.w(TAG, "startRecording: 已在录音中，忽略")
            return
        }
        this.callback = callback

        // 1. 检查 RECORD_AUDIO 权限
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            val msg = "缺少录音权限（RECORD_AUDIO），请在系统设置中授予"
            AppLogger.e(TAG, "startRecording: $msg")
            mainHandler.post { callback.onError(msg) }
            return
        }

        // 2. 快照当前 DSP 链（在录音开始前捕获参数，保证一致性）
        currentChainSnapshot = captureChainSnapshot()
        AppLogger.i(TAG, "startRecording: 引擎=${currentChainSnapshot?.engine} 模块数=${currentChainSnapshot?.modules?.size}")

        // 3. 确定 id 与文件路径（overwrite 模式复用最近一条）
        if (overwrite) {
            val latest = VoicePackStore.getLatest(context)
            if (latest != null) {
                packId = latest.id
                wavRelativePath = latest.wavFile
                AppLogger.i(TAG, "startRecording: overwrite 模式，复用 id=${packId} wav=${wavRelativePath}")
            } else {
                // 没有可覆盖的语音包，降级为新建
                packId = UUID.randomUUID().toString()
                wavRelativePath = VoicePackStore.relativeWavPath(packId)
                AppLogger.i(TAG, "startRecording: overwrite 但无历史包，新建 id=${packId}")
            }
        } else {
            packId = UUID.randomUUID().toString()
            wavRelativePath = VoicePackStore.relativeWavPath(packId)
        }
        wavFile = VoicePackStore.wavFile(context, wavRelativePath)
        wavFile?.parentFile?.mkdirs()

        // 4. 重置 DSP 模块状态，避免上一个包的混响/回声残响泄漏到新包
        //    Task 3 添加
        NativeAudioProcessor.resetDspState()

        // 5. 启动录音线程（AudioRecord 创建、循环读取都在子线程，避免阻塞 UI）
        recordingStartTime = System.currentTimeMillis()
        recordingThread = Thread({ recordingLoop() }, "VoicePackRecorder-Thread").apply {
            isDaemon = true
            start()
        }
    }

    // ============================================================
    // 录音线程主循环
    // ============================================================

    private fun recordingLoop() {
        val cb = callback ?: return

        // 录音+变声线程设为音频优先级（nice=-16）：
        // 录音处理为实时任务，调度抖动会导致 read 阻塞时长波动、块边界不齐，听感卡顿
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (_: Exception) {}

        try {
            // ---- 创建 AudioRecord ----
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBuf <= 0) {
                val msg = "AudioRecord.getMinBufferSize 失败: $minBuf"
                AppLogger.e(TAG, msg)
                mainHandler.post { cb.onError(msg) }
                return
            }
            // 内部 buffer 放大到至少 0.5 秒容量（minBuf*4 兜底）：
            // 录音线程偶发的调度抖动/GC/磁盘写延迟若超过 minBuf*2 的余量，
            // AudioRecord 环形缓冲会溢出丢块 → 录制音频周期性缺一段（听感"卡卡"）。
            // 阻塞式 read 不受影响：数据一就绪即返回，大缓冲仅在处理落后时兜底。
            val bufferBytes = maxOf(minBuf * 4, 48000)  // 48000B = 0.5s @48k mono 16bit
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferBytes,
                )
            } catch (e: Exception) {
                val msg = "AudioRecord 创建失败: ${e.message}"
                AppLogger.e(TAG, msg, e)
                mainHandler.post { cb.onError(msg) }
                return
            }
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                val stateName = recorder?.state?.toString() ?: "null"
                val msg = "AudioRecord 未初始化 (state=$stateName)"
                AppLogger.e(TAG, msg)
                recorder?.release()
                mainHandler.post { cb.onError(msg) }
                return
            }
            audioRecord = recorder

            // ---- 创建 WavWriter 并写头 ----
            val writer = WavWriter(wavFile!!, SAMPLE_RATE, channels = 1, bitsPerSample = 16)
            writer.start()
            wavWriter = writer

            // ---- 启动录音 ----
            recorder.startRecording()
            recording = true
            AppLogger.i(TAG, "录音已启动 id=$packId sr=$SAMPLE_RATE buf=$bufferBytes")
            mainHandler.post { cb.onRecordingStart() }

            // ---- 录音 + 变声 + 写盘循环 ----
            val inBuf = ByteArray(PROCESS_BLOCK_BYTES)
            val outBuf = ByteArray(PROCESS_BLOCK_BYTES)
            while (recording) {
                val read = recorder.read(inBuf, 0, PROCESS_BLOCK_BYTES)
                when {
                    read > 0 -> {
                        // 逐块变声处理（与录音在同一线程串行执行）
                        NativeAudioProcessor.processAudio(inBuf, outBuf, read)
                        // 只写入实际读到的字节数（read 可能 < PROCESS_BLOCK_BYTES）
                        writer.writePcm16(if (read == PROCESS_BLOCK_BYTES) outBuf else outBuf.copyOf(read))
                    }
                    read == 0 -> {
                        // 罕见，继续读
                    }
                    else -> {
                        // read < 0：错误码
                        if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                            // 通常是 stop 被调用，正常退出
                            break
                        }
                        AppLogger.e(TAG, "录音读取错误: read=$read")
                        mainHandler.post { cb.onError("录音读取错误 (code=$read)") }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "录音线程异常", e)
            mainHandler.post { cb.onError("录音异常: ${e.message}") }
        } finally {
            // ---- 清理资源 + 落盘 VoicePack ----
            val pack = finishRecording()
            recording = false
            mainHandler.post { cb.onRecordingStop(pack) }
        }
    }

    // ============================================================
    // 停止录音
    // ============================================================

    /**
     * 停止录音。
     *
     * @param delayMs 延迟停止毫秒数。>0 时用 Handler.postDelayed 延迟实际停止
     *                （PTT 松手尾音用）。延迟期间继续录音与变声处理。
     *                若已有延迟停止在等待，重复调用会刷新延迟。
     */
    fun stopRecording(delayMs: Long = 0) {
        if (!recording) {
            // 可能是延迟停止尚未触发但用户再次操作，取消待停止任务
            pendingStopRunnable?.let { mainHandler.removeCallbacks(it); pendingStopRunnable = null }
            AppLogger.w(TAG, "stopRecording: 未在录音，忽略 (delayMs=$delayMs)")
            return
        }
        if (delayMs > 0) {
            // 取消旧的延迟停止任务（若存在），重新计时
            pendingStopRunnable?.let { mainHandler.removeCallbacks(it) }
            val r = Runnable {
                recording = false // 让录音循环在下次迭代时退出
                pendingStopRunnable = null
                AppLogger.i(TAG, "stopRecording: 延迟 $delayMs ms 后触发停止")
            }
            pendingStopRunnable = r
            mainHandler.postDelayed(r, delayMs)
            AppLogger.i(TAG, "stopRecording: 安排延迟停止 ${delayMs}ms")
        } else {
            // 立即停止
            pendingStopRunnable?.let { mainHandler.removeCallbacks(it); pendingStopRunnable = null }
            recording = false
            AppLogger.i(TAG, "stopRecording: 立即停止")
        }
    }

    // ============================================================
    // 录音结束：清理资源 + 构造并保存 VoicePack
    // ============================================================

    private fun finishRecording(): VoicePack? {
        // 停止 AudioRecord
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            AppLogger.w(TAG, "finishRecording: AudioRecord.stop 异常: ${e.message}")
        }
        audioRecord?.release()
        audioRecord = null

        // flush + finish WavWriter（回填 WAV 头部 size 字段）
        val writer = wavWriter
        var durationMs = 0L
        if (writer != null) {
            try {
                writer.finish()
                durationMs = writer.estimatedDurationMs()
            } catch (e: Exception) {
                AppLogger.e(TAG, "finishRecording: WavWriter.finish 失败", e)
            }
        }
        wavWriter = null

        // 构造 VoicePack 元数据
        val snap = currentChainSnapshot
        if (snap == null || wavFile == null || !wavFile!!.exists()) {
            AppLogger.w(TAG, "finishRecording: 无快照或 WAV 文件不存在，返回 null")
            return null
        }

        val pack = VoicePack(
            id = packId,
            name = VoicePackStore.defaultName(recordingStartTime),
            wavFile = wavRelativePath,
            durationMs = durationMs,
            sampleRate = SAMPLE_RATE,
            createdAt = recordingStartTime,
            chainSnapshot = snap,
        )

        // 持久化元数据（WAV 文件已由 WavWriter 写好）
        try {
            VoicePackStore.save(context, pack)
            AppLogger.i(TAG, "finishRecording: VoicePack 已保存 id=${pack.id} dur=${pack.durationMs}ms")
        } catch (e: Exception) {
            AppLogger.e(TAG, "finishRecording: VoicePackStore.save 失败", e)
            return null
        }
        return pack
    }

    // ============================================================
    // 快照当前 DSP 链
    // ============================================================

    /**
     * 从 SharedPreferences("maidmic_eq") 读取当前 DSP 参数，构造 ChainSnapshot。
     *
     * 模块 ID 与 BUILTIN_MODULES (ui/editor/ModuleChainEditor.kt) 对齐：
     *   1=Gain, 3=Compressor, 4=Pitch, 5=Reverb, 7=Distortion,
     *   11=Bass, 12=Treble, 13=Formant, 14=Echo
     *
     * 参数 key 与 C++ 端 set_eq_params / set_compressor_params 完全对齐。
     */
    private fun captureChainSnapshot(): ChainSnapshot {
        val prefs = context.getSharedPreferences("maidmic_eq", Context.MODE_PRIVATE)

        val modules = mutableListOf<ModuleState>()

        // 1=Gain: gain_db
        modules += ModuleState(
            moduleId = 1,
            params = mapOf("gain_db" to prefs.getFloat("gain", 0f)),
            bypass = false,
        )
        // 3=Compressor: comp_threshold / comp_ratio / comp_makeup
        //    maidmic_eq 暂不持久化压缩器参数，记 0 占位（可后续扩展）
        modules += ModuleState(
            moduleId = 3,
            params = mapOf(
                "comp_threshold" to prefs.getFloat("comp_threshold", 0f),
                "comp_ratio" to prefs.getFloat("comp_ratio", 0f),
                "comp_makeup" to prefs.getFloat("comp_makeup", 0f),
            ),
            bypass = false,
        )
        // 4=Pitch Shift: pitch_semitones（prefs 存为 Int）
        modules += ModuleState(
            moduleId = 4,
            params = mapOf("pitch_semitones" to prefs.getInt("pitch", 0).toFloat()),
            bypass = false,
        )
        // 5=Reverb: reverb_mix
        modules += ModuleState(
            moduleId = 5,
            params = mapOf("reverb_mix" to prefs.getFloat("reverb", 0f)),
            bypass = false,
        )
        // 7=Distortion: distortion
        modules += ModuleState(
            moduleId = 7,
            params = mapOf("distortion" to prefs.getFloat("distortion", 0f)),
            bypass = false,
        )
        // 11=Bass: bass_db
        modules += ModuleState(
            moduleId = 11,
            params = mapOf("bass_db" to prefs.getFloat("bass", 0f)),
            bypass = false,
        )
        // 12=Treble: treble_db
        modules += ModuleState(
            moduleId = 12,
            params = mapOf("treble_db" to prefs.getFloat("treble", 0f)),
            bypass = false,
        )
        // 13=Formant: formant_shift
        modules += ModuleState(
            moduleId = 13,
            params = mapOf("formant_shift" to prefs.getFloat("formant", 0f)),
            bypass = false,
        )
        // 14=Echo: echo_delay_ms / echo_decay
        modules += ModuleState(
            moduleId = 14,
            params = mapOf(
                "echo_delay_ms" to prefs.getFloat("echo_delay", 0f),
                "echo_decay" to prefs.getFloat("echo_decay", 0f),
            ),
            bypass = false,
        )

        val engineKey = NativeAudioProcessor.getEngine().key
        return ChainSnapshot(engine = engineKey, modules = modules)
    }
}
