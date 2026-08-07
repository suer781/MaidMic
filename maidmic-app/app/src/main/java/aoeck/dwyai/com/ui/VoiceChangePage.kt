// VoiceChangePage.kt — 变声页（化繁为简清理版）
// ============================================================
// 布局结构（整页单列滚动，无强制分割、无嵌套滚动）：
//   1. 页面标题 + 副标题引导
//   2. 预设选择区（一行 5 个，随内容自适应，无横向滚动）
//   3. 变调滑块（-12 ~ +12 半音）
//   4. 共振峰滑块（-12 ~ +12）
//   5. 试听与存包（合并为一张卡：按住试听 + 录音存包）
// 所有参数实时同步到 NativeAudioProcessor 并持久化到 SharedPreferences("maidmic_eq")。

package aoeck.dwyai.com.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.os.Process
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import aoeck.dwyai.com.AppLogger
import aoeck.dwyai.com.NativeAudioProcessor
import aoeck.dwyai.com.TestState
import aoeck.dwyai.com.ui.components.AnimatedSlider
import aoeck.dwyai.com.ui.components.GradientCard
import aoeck.dwyai.com.ui.components.PresetChip
import aoeck.dwyai.com.ui.components.PressableButton
import aoeck.dwyai.com.ui.components.WaveformView
import aoeck.dwyai.com.ui.theme.MaidMicSpacing
import aoeck.dwyai.com.util.HapticHelper
import aoeck.dwyai.com.voicepack.VoicePack
import aoeck.dwyai.com.voicepack.VoicePackRecorder
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

// ============================================================
// 预设定义
// ============================================================

/** 变声预设参数定义 */
private data class VoicePreset(
    val name: String,
    val index: Int,
    val pitch: Int,
    val formant: Float,
    val distortion: Float,
    val clearAll: Boolean = false  // 原声预设：清零所有效果
)

/** 五个预设：萝莉 / 大叔 / 机器人 / 原声 / 自定义 */
private val VOICE_PRESETS = listOf(
    VoicePreset("萝莉", 0, pitch = 4, formant = 2f, distortion = 0f),
    VoicePreset("大叔", 1, pitch = -4, formant = -2f, distortion = 0f),
    VoicePreset("机器人", 2, pitch = 0, formant = 0f, distortion = 0.3f),
    VoicePreset("原声", 3, pitch = 0, formant = 0f, distortion = 0f, clearAll = true),
    VoicePreset("自定义", 4, pitch = 0, formant = 0f, distortion = 0f),
)

// ============================================================
// 数值格式化器
// ============================================================

/** 半音格式化（整数显示，正数带 + 号） */
private val pitchFormatter: (Float) -> String = { v ->
    val n = v.toInt()
    val sign = if (n > 0) "+" else ""
    "$sign$n 半音"
}

/** 共振峰格式化（一位小数，正数带 + 号） */
private val formantFormatter: (Float) -> String = { v ->
    val sign = if (v > 0f) "+" else ""
    "$sign${"%.1f".format(v)}"
}

// ============================================================
// 变声页
// ============================================================

@Composable
fun VoiceChangePage(
    context: Context,
    modifier: Modifier = Modifier,
    // 变声节拍开关：由 MainActivity 统一持有（与设置页共享单一状态源）
    enableLraRhythm: Boolean = false
) {
    // ---------- 持久化存储 ----------
    // maidmic_eq：EQ + 变声参数（与 EqPage 共享）
    val eqPrefs = remember { context.getSharedPreferences("maidmic_eq", Context.MODE_PRIVATE) }

    // ---------- 变声参数状态（从存储恢复） ----------
    var pitch by remember { mutableIntStateOf(eqPrefs.getInt("pitch", 0)) }
    var formant by remember { mutableFloatStateOf(eqPrefs.getFloat("formant", 0f)) }
    var selectedPreset by remember { mutableIntStateOf(eqPrefs.getInt("preset", 4)) }

    // ---------- 试听状态管理 ----------
    var testState by remember { mutableStateOf(TestState.IDLE) }
    // LRA 变声节拍：仅 LRA 设备 + 设置中开启时启用
    val hapticInfo = remember { HapticHelper.isAvailable() }
    val lraRhythmActive = enableLraRhythm && hapticInfo.third
    // 录音控制标志（跨线程 AtomicBoolean，松手设 false 触发停止）
    val recordingFlag = remember { AtomicBoolean(false) }
    // 用于 pointerInput(Unit) 内读取最新 testState，避免捕获过期值
    val testStateLatest by rememberUpdatedState(testState)

    // ---------- 录音存包状态（VoicePackRecorder：录音 → 实时变声 → 存包） ----------
    val packRecorder = remember { VoicePackRecorder(context) }
    var packRecording by remember { mutableStateOf(false) }
    // 最长录 10 秒自动停止，避免忘记停止导致一直录音
    LaunchedEffect(packRecording) {
        if (packRecording) {
            delay(10_000)
            packRecorder.stopRecording(0)
        }
    }
    // 离开页面时停止录音并存包，避免切换导航后后台持续录音
    DisposableEffect(Unit) {
        onDispose {
            packRecorder.stopRecording(0)
        }
    }

    // ---------- Hero Moment: 呼吸边框动画 ----------
    val breathingTransition = rememberInfiniteTransition(label = "breathing")
    val breathingPhase by breathingTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingPhase"
    )
    val heroBorderWidth by animateDpAsState(
        targetValue = if (testState == TestState.RECORDING) {
            (2f + breathingPhase * 2f).dp
        } else {
            0.dp
        },
        label = "heroBorderWidth"
    )
    val heroBorderColor by animateColorAsState(
        targetValue = if (testState == TestState.RECORDING)
            Color(0xFFFFAB91) else Color.Transparent,
        label = "heroBorderColor"
    )

    // ---------- 引擎自检（确保 JNI 已加载，幂等） ----------
    LaunchedEffect(Unit) {
        NativeAudioProcessor.ensureLoaded()
    }

    // ---------- 参数推送 + 持久化 ----------
    // 读取当前 maidmic_eq 所有参数 → 更新 pitch/formant → setEqParams 推送 → 写 SharedPreferences
    fun pushParams(p: Int = pitch, f: Float = formant) {
        NativeAudioProcessor.ensureLoaded()
        NativeAudioProcessor.setEqParams(
            gainDb = eqPrefs.getFloat("gain", 0f),
            bassDb = eqPrefs.getFloat("bass", 0f),
            trebleDb = eqPrefs.getFloat("treble", 0f),
            reverbMix = eqPrefs.getFloat("reverb", 0f),
            pitchSemitones = p,
            formantShift = f,
            distortion = eqPrefs.getFloat("distortion", 0f),
            echoDelayMs = eqPrefs.getFloat("echo_delay", 0f),
            echoDecay = eqPrefs.getFloat("echo_decay", 0f)
        )
        eqPrefs.edit()
            .putInt("pitch", p)
            .putFloat("formant", f)
            .apply()
    }

    // ---------- 预设切换 ----------
    fun applyPreset(index: Int) {
        val preset = VOICE_PRESETS[index]
        selectedPreset = index

        if (index == 4) {
            // 自定义：不改变当前参数，仅记录预设选择
            eqPrefs.edit().putInt("preset", index).apply()
            return
        }

        // 设置 pitch / formant（及 distortion / 清零）
        pitch = preset.pitch
        formant = preset.formant
        val editor = eqPrefs.edit().putInt("preset", index)
        if (preset.clearAll) {
            // 原声：清零所有效果参数
            editor.putFloat("gain", 0f)
                .putFloat("bass", 0f)
                .putFloat("treble", 0f)
                .putFloat("reverb", 0f)
                .putFloat("distortion", 0f)
                .putFloat("echo_delay", 0f)
                .putFloat("echo_decay", 0f)
        } else {
            editor.putFloat("distortion", preset.distortion)
        }
        editor.apply()
        // 推送参数到引擎（读取含刚写入的 distortion 等）
        pushParams(preset.pitch, preset.formant)
        AppLogger.i("VoiceChange", "应用预设: ${preset.name} pitch=${preset.pitch} formant=${preset.formant} dist=${preset.distortion}")
    }

    // ---------- 手动调节时切换为自定义预设 ----------
    fun switchToCustomIfPreset() {
        if (selectedPreset != 4) {
            selectedPreset = 4
            eqPrefs.edit().putInt("preset", 4).apply()
        }
    }

    // ============================================================
    // 页面布局（化繁为简：整页单列滚动，无强制分割、无嵌套滚动）
    // ============================================================
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MaidMicSpacing.s)
    ) {
        Spacer(Modifier.height(MaidMicSpacing.s))

        // ============ 页面标题 ============
        Text(
            text = "变声",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "选择预设 · 调节参数 · 试听与存包",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(MaidMicSpacing.s))

        // ============================================================
        // 1. 预设选择区（一行 5 个，随内容自适应，无横向滚动）
        // ============================================================
        GradientCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "预设",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(MaidMicSpacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaidMicSpacing.xs)
            ) {
                VOICE_PRESETS.forEach { preset ->
                    PresetChip(
                        label = preset.name,
                        selected = selectedPreset == preset.index,
                        onClick = { applyPreset(preset.index) },
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        }
        Spacer(Modifier.height(MaidMicSpacing.s))

        // ============================================================
        // 2. 变调滑块（-12 ~ +12 半音）
        // ============================================================
        GradientCard(modifier = Modifier.fillMaxWidth()) {
            AnimatedSlider(
                label = "变调",
                value = pitch.toFloat(),
                onValueChange = {
                    pitch = it.roundToInt()
                    switchToCustomIfPreset()
                    pushParams()
                },
                valueRange = -12f..12f,
                valueFormatter = pitchFormatter
            )
        }
        Spacer(Modifier.height(MaidMicSpacing.s))

        // ============================================================
        // 3. 共振峰滑块（-12 ~ +12）
        // ============================================================
        GradientCard(modifier = Modifier.fillMaxWidth()) {
            AnimatedSlider(
                label = "共振峰",
                value = formant,
                onValueChange = {
                    formant = it
                    switchToCustomIfPreset()
                    pushParams()
                },
                valueRange = -12f..12f,
                valueFormatter = formantFormatter
            )
        }
        Spacer(Modifier.height(MaidMicSpacing.s))

        // ============================================================
        // 4. 试听与存包（合并为一张卡：试听 + 录音存包）
        // ============================================================
        GradientCard(
            modifier = Modifier
                .fillMaxWidth()
                .border(heroBorderWidth, heroBorderColor, RoundedCornerShape(24.dp)),
            cornerRadius = 24
        ) {
            Text(
                text = "试听与存包",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(MaidMicSpacing.xs))
            Text(
                text = when (testState) {
                    TestState.IDLE -> "按住下方按钮录音，松手后试听变声效果"
                    TestState.RECORDING -> "录音中... 松手结束"
                    TestState.PLAYING -> "正在播放变声效果..."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(MaidMicSpacing.xs))

            WaveformView(
                modifier = Modifier.height(64.dp),
                isActive = testState != TestState.IDLE,
                color = MaterialTheme.colorScheme.primary,
                enableLraRhythm = lraRhythmActive
            )
            Spacer(Modifier.height(MaidMicSpacing.m))

            // 试听按钮居中（触控区 ≥ 48dp）
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    if (testStateLatest == TestState.IDLE) {
                                        HapticHelper.mechanical()
                                        startPressHoldRecording(
                                            context = context,
                                            flag = recordingFlag,
                                            onStateChange = { testState = it }
                                        )
                                        try {
                                            tryAwaitRelease()
                                        } finally {
                                            recordingFlag.set(false)
                                        }
                                    }
                                }
                            )
                        }
                        .heightIn(min = 56.dp)
                        .width(180.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = if (testState == TestState.RECORDING)
                        Color(0xFFFFAB91)
                    else MaterialTheme.colorScheme.primary,
                    contentColor = if (testState == TestState.RECORDING)
                        Color(0xFF2D1509)
                    else MaterialTheme.colorScheme.onPrimary
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when (testState) {
                                TestState.IDLE -> "按住录音"
                                TestState.RECORDING -> "松手试听"
                                TestState.PLAYING -> "播放中..."
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
            Spacer(Modifier.height(MaidMicSpacing.s))

            PressableButton(
                text = if (packRecording) "停止并保存" else "开始录音并存包",
                onClick = {
                    if (packRecording) {
                        packRecorder.stopRecording(0)
                    } else {
                        // 开始录音：录音 → 变声处理 → 写 WAV → 存包
                        packRecorder.startRecording(object : VoicePackRecorder.Callback {
                            override fun onRecordingStart() {
                                packRecording = true
                                Toast.makeText(context, "录音中... 再次点击停止并保存", Toast.LENGTH_SHORT).show()
                            }

                            override fun onRecordingStop(voicePack: VoicePack?) {
                                packRecording = false
                                if (voicePack != null) {
                                    Toast.makeText(context, "语音包已保存: ${voicePack.name}", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "录音过短，未保存", Toast.LENGTH_SHORT).show()
                                }
                            }

                            override fun onError(message: String) {
                                packRecording = false
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(MaidMicSpacing.m))
    }
}

// ============================================================
// 按住录音 → 松手处理播放（参考 MainActivity.startVoiceTest 逻辑）
// ============================================================

/**
 * 按住式实时试听：按下启动录音线程，松手（flag → false）停止录音，
 * 随后 NativeAudioProcessor.processAudio 逐块处理 → AudioTrack 播放。
 *
 * 状态流转：IDLE → RECORDING → PLAYING → IDLE
 *
 * @param context 应用上下文（权限检查、AudioRecord 创建）
 * @param flag AtomicBoolean，true=录音中，松手设 false 触发停止
 * @param onStateChange 状态回调（更新 Compose 状态）
 */
private fun startPressHoldRecording(
    context: Context,
    flag: AtomicBoolean,
    onStateChange: (TestState) -> Unit
) {
    // 防重入：若已有录音/播放在进行（flag 仍为 true），忽略
    if (!flag.compareAndSet(false, true)) return

    // 处理块大小：2048 样本/块 @ 48kHz（16-bit mono → 4096 字节 ≈ 42.7ms），
    // 块适中：过大增加处理延迟，过小造成频繁 JNI 调用。
    val sampleRate = 48000
    val bufferSize = 4096
    val maxBytes = sampleRate * 10 * 2  // 最长 10s（16-bit mono）

    // 权限检查
    val hasMic = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasMic) {
        AppLogger.e("VoiceTest", "缺少录音权限（RECORD_AUDIO）")
        HapticHelper.error()
        flag.set(false)
        onStateChange(TestState.IDLE)
        return
    }

    // 录音→处理→回放全程在后台线程执行，不阻塞 UI 线程。
    // 如需更低延迟可把线程优先级提到 Process.THREAD_PRIORITY_AUDIO(-16)。
    Thread {
        // 音频线程优先级（-16），降低录音/处理/回放时序抖动
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        AppLogger.i("VoiceTest", "开始按住录音 (sr=$sampleRate buf=$bufferSize)")
        onStateChange(TestState.RECORDING)
        HapticHelper.basic()

        // 录音/回放前请求音频焦点（AUDIOFOCUS_GAIN_TRANSIENT），结束后在 finally 放弃。
        // 备选方案：setMode(MODE_IN_COMMUNICATION) 后恢复原模式；
        // 此处选 requestAudioFocus，避免全局改动音频模式影响其他应用。
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        val focusGranted = audioManager.requestAudioFocus(
            null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        var recorder: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            // ---- 创建 AudioRecord ----
            // 缓冲 ≥ max(getMinBufferSize, 2×块字节)，避免 read 因缓冲过小频繁阻塞
            val minRecBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val recBufSize = if (minRecBuf > 0) maxOf(minRecBuf, bufferSize * 2) else bufferSize * 2
            recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    recBufSize
                )
            } catch (e: Exception) {
                AppLogger.e("VoiceTest", "AudioRecord 创建失败", e)
                HapticHelper.error()
                Toast.makeText(context, "录音创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
                return@Thread
            }

            if (recorder == null) {
                return@Thread
            }
            val rec = recorder
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                AppLogger.e("VoiceTest", "AudioRecord 未初始化 state=${rec.state}")
                HapticHelper.error()
                Toast.makeText(context, "录音器初始化失败", Toast.LENGTH_SHORT).show()
                return@Thread
            }

            // ---- 录音循环（flag 为 true 时持续读，松手后 flag → false 退出） ----
            rec.startRecording()
            val buf = ByteArray(bufferSize)
            val allPcm = mutableListOf<ByteArray>()
            var totalRead = 0
            try {
                while (flag.get() && totalRead < maxBytes) {
                    val read = rec.read(buf, 0, bufferSize)
                    if (read > 0) {
                        allPcm.add(buf.copyOf(read))
                        totalRead += read
                    } else if (read < 0) {
                        AppLogger.e("VoiceTest", "录音读取错误: read=$read")
                        Toast.makeText(context, "录音读取错误 (code=$read)", Toast.LENGTH_SHORT).show()
                        break
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("VoiceTest", "录音读取异常", e)
                Toast.makeText(context, "录音读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            rec.stop()
            rec.release()
            recorder = null
            AppLogger.i("VoiceTest", "录音结束: ${allPcm.size} 块, $totalRead 字节")

            // 空录音保护（极短按压）
            if (allPcm.isEmpty()) {
                return@Thread
            }

            // ---- 引擎处理（逐块 processAudio，已在后台线程，不阻塞 UI） ----
            onStateChange(TestState.PLAYING)
            HapticHelper.success()
            NativeAudioProcessor.ensureLoaded()
            val processed = allPcm.map { chunk ->
                val out = ByteArray(chunk.size)
                NativeAudioProcessor.processAudio(chunk, out, chunk.size)
                out
            }
            AppLogger.i("VoiceTest", "引擎处理完成: ${processed.size} 块")

            // ---- 回放（AudioTrack STREAM 模式，48kHz / MONO / PCM16） ----
            // 缓冲 ≥ max(getMinBufferSize, 2×块字节)，避免 write 阻塞掉块导致卡顿
            val minTrackBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val trackBufSize = if (minTrackBuf > 0) maxOf(minTrackBuf, bufferSize * 2) else bufferSize * 2
            track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(trackBufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                AppLogger.e("VoiceTest", "AudioTrack 创建失败", e)
                Toast.makeText(context, "回放创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
                null
            }
            if (track == null) {
                return@Thread
            }
            val tr = track

            tr.play()
            val totalFrames = processed.sumOf { it.size } / 2 // 16-bit mono：2 字节/帧
            for (chunk in processed) {
                tr.write(chunk, 0, chunk.size)
            }
            // 等待播放完成：轮询 playbackHeadPosition 到达已写入帧数，
            // 避免未播完即 stop() 截断长录音（50ms 轮询，最长等待 15s = 最长录音 10s + 余量）
            val deadline = SystemClock.elapsedRealtime() + 15_000L
            while (tr.playbackHeadPosition < totalFrames && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(50)
            }
            AppLogger.i("VoiceTest", "回放完成: head=${tr.playbackHeadPosition}/$totalFrames 帧")
        } catch (e: Exception) {
            AppLogger.e("VoiceTest", "录音/处理/回放异常", e)
            Toast.makeText(context, "变声试听失败: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            // 收尾顺序：flush() → stop() → release()（异常时也确保释放）
            try { track?.flush() } catch (_: Exception) {}
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            // 恢复音频焦点
            @Suppress("DEPRECATION")
            if (focusGranted) {
                audioManager.abandonAudioFocus(null)
            }
            flag.set(false)
            onStateChange(TestState.IDLE)
        }
        AppLogger.i("VoiceTest", "试听结束")
    }.apply { isDaemon = true; start() }
}
