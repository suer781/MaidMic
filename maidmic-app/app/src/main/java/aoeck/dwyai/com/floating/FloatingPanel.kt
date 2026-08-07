// FloatingPanel.kt — 悬浮球展开面板（Task 10）
// ============================================================
// 单击悬浮球后展开的面板：顶部四菜单导航栏 + 内容区。
//
// 布局：
//   ┌──────────────────────────────────────┐
//   │ [录音] [EQ与增益] [变音] [快捷音效库]  │  ← 顶部导航栏（用户明确要求在上方）
//   ├──────────────────────────────────────┤
//   │                                      │
//   │           内容区（按 tab 切换）         │  ← 默认显示"录音"界面
//   │                                      │
//   └──────────────────────────────────────┘
//
// 实现要点：
//   - ComposeView 包装 Compose 内容，通过 WindowManager 添加（TYPE_APPLICATION_OVERLAY）
//   - 面板宽度 280dp，高度 wrapContent（内容区最多 320dp 可滚动）
//   - 位置：悬浮球上方（空间不足则下方），首次布局后根据实际测量高度精修
//   - FLAG_WATCH_OUTSIDE_TOUCH：点面板外区域 → onCollapse 回调
//   - 默认选中"录音" tab（用户明确要求）
//   - 录制中：EQ/变音/音效三个 tab 变灰不可点击 + "录制中..."提示
//   - 深色主题，与 MainActivity MaidMicDarkColors 配色一致
//
// 四菜单内容（简便的开关启用插件式，非完整编辑器）：
//   1. 录音：PTT 提示 + 提前录音按钮（最长 60s）+ 最近语音包快捷区
//   2. EQ与增益：启用开关 + 增益/低音/高音 3 滑块
//   3. 变音：启用开关 + 萝莉/大叔/机器人/原声 4 预设 + 变调/共振峰 2 滑块
//   4. 快捷音效库：炸麦/空灵/电话音/回声/原声 5 个一键预设
//
// 由 FloatingBallService 在 BallInteractionCallback.onExpandPanel 中创建并挂载，
// 在 onCollapsePanel 中移除。录音/播放器由 Service 注入（通过 provider lambda）。

package aoeck.dwyai.com.floating

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import aoeck.dwyai.com.AppLogger
import aoeck.dwyai.com.NativeAudioProcessor
import aoeck.dwyai.com.soundeffect.SoundEffect
import aoeck.dwyai.com.soundeffect.SoundEffectPlayer
import aoeck.dwyai.com.soundeffect.SoundEffectStore
import aoeck.dwyai.com.voicepack.VoicePack
import aoeck.dwyai.com.voicepack.VoicePackPlayer
import aoeck.dwyai.com.voicepack.VoicePackRecorder
import aoeck.dwyai.com.voicepack.VoicePackStore
import kotlinx.coroutines.delay

// ============================================================
// 颜色常量（与 MainActivity MaidMicDarkColors 对齐）
// ============================================================

private val PrimaryColor = Color(0xFFCE93D8)        // 主题紫（选中态高亮）
private val PrimaryContainerColor = Color(0xFF4A2561) // 选中 tab 背景
private val SurfaceColor = Color(0xF21C1B1F)        // 面板背景（半透明）
private val SurfaceVariantColor = Color(0xFF2A2930)  // 未选中 tab / 卡片背景
private val TextMainColor = Color(0xFFE6E1E5)
private val TextSubColor = Color(0xFFCAC4D0)
private val TextDimColor = Color(0xFF888888)
private val TextFadeColor = Color(0xFF666666)
private val DisabledColor = Color(0xFF49454F)        // 录制中变灰的 tab 背景
private val RecordingColor = Color(0xFFEF5350)       // 录制态红
private val ReadyColor = Color(0xFF66BB6A)           // 就绪态绿

private val MaidMicPanelColors = darkColorScheme(
    primary = PrimaryColor,
    onPrimary = Color(0xFF1A0D2E),
    surface = SurfaceColor,
    onSurface = TextMainColor,
    surfaceVariant = SurfaceVariantColor,
    onSurfaceVariant = TextSubColor,
)

// ============================================================
// ServiceLifecycleOwner — 为 ComposeView 提供 Lifecycle + SavedStateRegistry
// ============================================================
// ComposeView 必须在 ViewTree 中找到 LifecycleOwner / SavedStateRegistryOwner
// 才能正常运行。Service 不是 LifecycleOwner，故手动实现一个并 attach。
private class ServiceLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun performCreate(savedState: Bundle? = null) {
        savedStateRegistryController.performRestore(savedState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun performStart() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    fun performResume() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun performPause() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun performStop() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun performDestroy() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
}

// ============================================================
// FloatingPanelScope — 面板作用域：持有状态 + 暴露给 Composable 的方法
// ============================================================
// 封装所有面板需要的"能力"：读写 EQ/变音参数、控制录音/播放、分享语音包。
// Service 在创建面板时构造此 scope，注入 recorder/player provider 和状态回调。
// Composable 通过 scope 读取状态、调用方法。
class FloatingPanelScope(
    private val context: Context,
    private val recorderProvider: () -> VoicePackRecorder,
    private val playerProvider: () -> VoicePackPlayer,
    private val onStartManualRecording: () -> Unit,
    private val onStopManualRecording: () -> Unit,
    private val onPlayCompleted: () -> Unit,
    // Task 7: 音效快捷播放器由 Service 持有（单例，跨面板展开/收起复用），经 provider 注入
    private val effectPlayerProvider: () -> SoundEffectPlayer,
    // Task 7: 播放中音效 id 的 Service 级 state（面板收回再展开仍保留播放中状态）
    private val effectPlayingId: MutableState<String?>,
) {
    companion object {
        private const val TAG = "FloatingPanelScope"
        private const val EQ_PREFS = "maidmic_eq"
    }

    private val eqPrefs = context.getSharedPreferences(EQ_PREFS, Context.MODE_PRIVATE)

    // ===== 状态刷新触发器（bump 后触发 Composable 重组，重读 isRecording 等）=====
    val refreshTick = mutableStateOf(0L)
    private fun tick() { refreshTick.value = System.nanoTime() }

    // ===== EQ 状态（Compose state，与 prefs/engine 双向同步）=====
    val eqEnabled = mutableStateOf(eqPrefs.getBoolean("eq_enabled", true))
    val gainDb = mutableFloatStateOf(eqPrefs.getFloat("gain", 0f))
    val bassDb = mutableFloatStateOf(eqPrefs.getFloat("bass", 0f))
    val trebleDb = mutableFloatStateOf(eqPrefs.getFloat("treble", 0f))

    // ===== 变音状态 =====
    val voiceEnabled = mutableStateOf(eqPrefs.getBoolean("voice_enabled", true))
    val pitchSemitones = mutableIntStateOf(eqPrefs.getInt("pitch", 0))
    val formantShift = mutableFloatStateOf(eqPrefs.getFloat("formant", 0f))

    // ===== 快捷音效库当前选中索引（-1 = 无）=====
    val effectPresetIdx = mutableIntStateOf(eqPrefs.getInt("effect_preset_idx", -1))

    // ===== 从 prefs 全量刷新 Compose 状态（面板展开时调用）=====
    fun refreshFromPrefs() {
        eqEnabled.value = eqPrefs.getBoolean("eq_enabled", true)
        gainDb.value = eqPrefs.getFloat("gain", 0f)
        bassDb.value = eqPrefs.getFloat("bass", 0f)
        trebleDb.value = eqPrefs.getFloat("treble", 0f)
        voiceEnabled.value = eqPrefs.getBoolean("voice_enabled", true)
        pitchSemitones.value = eqPrefs.getInt("pitch", 0)
        formantShift.value = eqPrefs.getFloat("formant", 0f)
        effectPresetIdx.value = eqPrefs.getInt("effect_preset_idx", -1)
        tick()
    }

    // ===== 实时状态查询（非 Compose state，依赖 refreshTick 触发重组）=====
    fun isRecording(): Boolean = recorderProvider().isRecording()
    fun isPlaying(): Boolean = playerProvider().isPlaying()
    fun getLatestPack(): VoicePack? = VoicePackStore.getLatest(context)

    // ============================================================
    // EQ 与增益操作
    // ============================================================

    /** EQ 开关：关闭时 Bass/Treble 模块旁路（推 0 到引擎，但保留 slider 值） */
    fun setEqEnabled(enabled: Boolean) {
        eqEnabled.value = enabled
        eqPrefs.edit().putBoolean("eq_enabled", enabled).apply()
        pushEqToEngine()
        AppLogger.d(TAG, "EQ 开关: $enabled")
    }

    fun setGain(v: Float) {
        gainDb.value = v
        eqPrefs.edit().putFloat("gain", v).apply()
        pushEqToEngine()
    }

    fun setBass(v: Float) {
        bassDb.value = v
        eqPrefs.edit().putFloat("bass", v).apply()
        pushEqToEngine()
    }

    fun setTreble(v: Float) {
        trebleDb.value = v
        eqPrefs.edit().putFloat("treble", v).apply()
        pushEqToEngine()
    }

    /** 将当前 EQ 参数（含开关旁路逻辑）推送到引擎 */
    private fun pushEqToEngine() {
        val bass = if (eqEnabled.value) bassDb.value else 0f
        val treble = if (eqEnabled.value) trebleDb.value else 0f
        NativeAudioProcessor.ensureLoaded()
        NativeAudioProcessor.setEqParams(
            gainDb.value, bass, treble,
            eqPrefs.getFloat("reverb", 0f),
            eqPrefs.getInt("pitch", 0),
            eqPrefs.getFloat("formant", 0f),
            eqPrefs.getFloat("distortion", 0f),
            eqPrefs.getFloat("echo_delay", 0f),
            eqPrefs.getFloat("echo_decay", 0f)
        )
    }

    // ============================================================
    // 变音操作
    // ============================================================

    /** 变音开关：关闭时 Pitch/Formant 旁路（推 0 到引擎） */
    fun setVoiceEnabled(enabled: Boolean) {
        voiceEnabled.value = enabled
        eqPrefs.edit().putBoolean("voice_enabled", enabled).apply()
        pushVoiceToEngine()
        AppLogger.d(TAG, "变音开关: $enabled")
    }

    fun setPitch(v: Int) {
        pitchSemitones.value = v
        eqPrefs.edit().putInt("pitch", v).apply()
        pushVoiceToEngine()
    }

    fun setFormant(v: Float) {
        formantShift.value = v
        eqPrefs.edit().putFloat("formant", v).apply()
        pushVoiceToEngine()
    }

    /** 将当前变音参数（含开关旁路逻辑）推送到引擎 */
    private fun pushVoiceToEngine() {
        val pitch = if (voiceEnabled.value) pitchSemitones.value else 0
        val formant = if (voiceEnabled.value) formantShift.value else 0f
        NativeAudioProcessor.ensureLoaded()
        NativeAudioProcessor.setEqParams(
            gainDb.value,
            if (eqEnabled.value) bassDb.value else 0f,
            if (eqEnabled.value) trebleDb.value else 0f,
            eqPrefs.getFloat("reverb", 0f),
            pitch,
            formant,
            eqPrefs.getFloat("distortion", 0f),
            eqPrefs.getFloat("echo_delay", 0f),
            eqPrefs.getFloat("echo_decay", 0f)
        )
    }

    // ============================================================
    // 变音预设（4 个：萝莉 / 大叔 / 机器人 / 原声）
    // ============================================================
    data class VoicePreset(
        val name: String,
        val pitch: Int,
        val formant: Float,
        val distortion: Float,
    )

    val voicePresets = listOf(
        VoicePreset("萝莉", pitch = 4, formant = 2f, distortion = 0f),
        VoicePreset("大叔", pitch = -4, formant = -2f, distortion = 0f),
        VoicePreset("机器人", pitch = 0, formant = 0f, distortion = 0.3f),
        VoicePreset("原声", pitch = 0, formant = 0f, distortion = 0f),
    )

    /** 点击预设 → 一次性设置 pitch/formant/distortion + 同步滑块 */
    fun applyVoicePreset(idx: Int) {
        val preset = voicePresets[idx]
        pitchSemitones.value = preset.pitch
        formantShift.value = preset.formant
        eqPrefs.edit()
            .putInt("pitch", preset.pitch)
            .putFloat("formant", preset.formant)
            .putFloat("distortion", preset.distortion)
            .apply()
        pushVoiceToEngine()
        AppLogger.i(TAG, "应用变音预设: ${preset.name}")
    }

    // ============================================================
    // 快捷音效库预设（5 个：炸麦 / 空灵 / 电话音 / 回声 / 原声）
    // ============================================================
    data class EffectPreset(
        val name: String,
        val desc: String,
        val icon: String, // emoji
        val gain: Float, val bass: Float, val treble: Float,
        val reverb: Float, val pitch: Int, val formant: Float,
        val distortion: Float, val echoDelay: Float, val echoDecay: Float,
    )

    val effectPresets = listOf(
        EffectPreset("炸麦", "失真 + 高增益", "🔥",
            gain = 6f, bass = 0f, treble = 0f, reverb = 0f,
            pitch = 0, formant = 0f, distortion = 0.8f, echoDelay = 0f, echoDecay = 0f),
        EffectPreset("空灵", "混响 + 轻变调", "✨",
            gain = 0f, bass = 0f, treble = 0f, reverb = 0.4f,
            pitch = 2, formant = 1f, distortion = 0f, echoDelay = 0f, echoDecay = 0f),
        EffectPreset("电话音", "带宽限制效果", "📞",
            gain = 3f, bass = -10f, treble = 6f, reverb = 0f,
            pitch = 0, formant = 0f, distortion = 0f, echoDelay = 0f, echoDecay = 0f),
        EffectPreset("回声", "延迟回声", "🔊",
            gain = 0f, bass = 0f, treble = 0f, reverb = 0f,
            pitch = 0, formant = 0f, distortion = 0f, echoDelay = 300f, echoDecay = 0.5f),
        EffectPreset("原声", "清除所有效果", "♻️",
            gain = 0f, bass = 0f, treble = 0f, reverb = 0f,
            pitch = 0, formant = 0f, distortion = 0f, echoDelay = 0f, echoDecay = 0f),
    )

    /** 一键应用快捷音效预设：设置全部参数 + 持久化 + 推送引擎 + Toast 提示 */
    fun applyEffectPreset(idx: Int) {
        val preset = effectPresets[idx]
        eqPrefs.edit()
            .putFloat("gain", preset.gain)
            .putFloat("bass", preset.bass)
            .putFloat("treble", preset.treble)
            .putFloat("reverb", preset.reverb)
            .putInt("pitch", preset.pitch)
            .putFloat("formant", preset.formant)
            .putFloat("distortion", preset.distortion)
            .putFloat("echo_delay", preset.echoDelay)
            .putFloat("echo_decay", preset.echoDecay)
            .putInt("effect_preset_idx", idx)
            .apply()
        // 同步 Compose 状态
        gainDb.value = preset.gain
        bassDb.value = preset.bass
        trebleDb.value = preset.treble
        pitchSemitones.value = preset.pitch
        formantShift.value = preset.formant
        effectPresetIdx.value = idx
        NativeAudioProcessor.ensureLoaded()
        NativeAudioProcessor.setEqParams(
            preset.gain, preset.bass, preset.treble,
            preset.reverb, preset.pitch, preset.formant,
            preset.distortion, preset.echoDelay, preset.echoDecay
        )
        Toast.makeText(context, "已应用：${preset.name}", Toast.LENGTH_SHORT).show()
        tick()
        AppLogger.i(TAG, "应用快捷音效: ${preset.name}")
    }

    // ============================================================
    // 录音控制（提前录音按钮 → 委托给 Service 的回调）
    // ============================================================
    fun startManualRecording() {
        if (recorderProvider().isRecording()) {
            Toast.makeText(context, "已在录音中", Toast.LENGTH_SHORT).show()
            return
        }
        onStartManualRecording()
    }

    fun stopManualRecording() {
        onStopManualRecording()
    }

    // ============================================================
    // 播放（最近语音包外放）
    // ============================================================
    fun playPack(pack: VoicePack) {
        playerProvider().play(context, pack) {
            onPlayCompleted()
            tick()
        }
    }

    fun stopPlayback() {
        playerProvider().stop()
        tick()
    }

    // ============================================================
    // 快捷音效播放（Task 7: 播放器与播放中状态由 Service 持有，跨面板展开/收起存活）
    // ============================================================

    /** 获取 Service 持有的音效播放器单例（面板重复展开不重复创建播放器） */
    fun getEffectPlayer(): SoundEffectPlayer = effectPlayerProvider()

    /** 当前播放中的音效 id（null = 未播放；Service 级 state，面板收回再展开仍保留） */
    val playingEffectId: MutableState<String?>
        get() = effectPlayingId

    // ============================================================
    // 分享语音包（FileProvider content URI + ACTION_SEND）
    // ============================================================
    fun sharePack(pack: VoicePack) {
        try {
            val file = VoicePackStore.wavFile(context, pack.wavFile)
            if (!file.exists()) {
                Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
                return
            }
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享语音包：${pack.name}"))
            AppLogger.i(TAG, "sharePack: ${pack.id}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "sharePack 失败", e)
            Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

// ============================================================
// FloatingPanel — 面板容器：管理 ComposeView + WindowManager 生命周期
// ============================================================
class FloatingPanel(
    private val context: Context,
    private val windowManager: WindowManager,
    private val scope: FloatingPanelScope,
    private val onCollapse: () -> Unit,
) {
    companion object {
        const val PANEL_WIDTH_DP = 280
        const val PANEL_CONTENT_MAX_HEIGHT_DP = 320
        private const val TAG = "FloatingPanel"
    }

    private var panelView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var lifecycleOwner: ServiceLifecycleOwner? = null
    private var isShowing = false
    private var positionRefined = false

    fun isShowing(): Boolean = isShowing

    /**
     * 展开面板，定位在悬浮球上方（空间不足则下方）。
     * @param ballX 球左上角 X（屏幕坐标）
     * @param ballY 球左上角 Y（屏幕坐标）
     * @param ballSize 球直径（px）
     */
    fun show(ballX: Int, ballY: Int, ballSize: Int) {
        if (isShowing) return
        try {
            // 展开前刷新一次状态（同步外部可能修改的 prefs）
            scope.refreshFromPrefs()

            val dm = context.resources.displayMetrics
            val panelWidthPx = dpToPx(PANEL_WIDTH_DP.toFloat()).toInt()
            // 高度估算值（用于判定上方空间是否足够，首次布局后精修）
            val panelEstHeightPx = dpToPx(PANEL_CONTENT_MAX_HEIGHT_DP + 60f).toInt()
            val marginPx = dpToPx(8f).toInt()

            val ballCenterX = ballX + ballSize / 2
            val panelX = (ballCenterX - panelWidthPx / 2)
                .coerceIn(0, (dm.widthPixels - panelWidthPx).coerceAtLeast(0))

            // 优先放上方（按估算高度判定），空间不足则放下方
            val panelY = if (ballY >= panelEstHeightPx + marginPx) {
                ballY - panelEstHeightPx - marginPx
            } else {
                ballY + ballSize + marginPx
            }

            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.TRANSLUCENT
                // FLAG_NOT_FOCUSABLE: 不抢焦点
                // FLAG_WATCH_OUTSIDE_TOUCH: 点面板外区域时收到 ACTION_OUTSIDE → 收起
                // FLAG_LAYOUT_NO_LIMITS: 允许超出屏幕边界（避免边缘裁剪）
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                width = panelWidthPx
                height = WindowManager.LayoutParams.WRAP_CONTENT
                gravity = Gravity.TOP or Gravity.START
                x = panelX
                y = panelY
            }
            layoutParams = params

            // 为 ComposeView 创建 LifecycleOwner + SavedStateRegistryOwner
            val owner = ServiceLifecycleOwner()
            owner.performCreate()
            owner.performStart()
            owner.performResume()
            lifecycleOwner = owner

            val view = ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    MaterialTheme(colorScheme = MaidMicPanelColors) {
                        FloatingPanelContent(scope = scope)
                    }
                }
                // 监听面板外点击 → 收起（仅处理 ACTION_OUTSIDE，其余事件交给 Compose）
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_OUTSIDE) {
                        AppLogger.d(TAG, "面板外点击 → 收起")
                        onCollapse()
                        true
                    } else {
                        false
                    }
                }
            }
            panelView = view

            // 首次布局后根据实际测量高度精修 Y 位置（避免估算偏差导致间隙过大）
            view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                ) {
                    if (positionRefined) return
                    val measuredHeight = bottom - top
                    if (measuredHeight <= 0) return
                    positionRefined = true
                    val aboveSpace = ballY
                    val newY = if (aboveSpace >= measuredHeight + marginPx) {
                        ballY - measuredHeight - marginPx
                    } else {
                        ballY + ballSize + marginPx
                    }
                    try {
                        params.y = newY
                        windowManager.updateViewLayout(view, params)
                        AppLogger.d(TAG, "面板位置精修: y=$newY h=$measuredHeight")
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "精修位置失败: ${e.message}")
                    }
                    v.removeOnLayoutChangeListener(this)
                }
            })

            windowManager.addView(view, params)
            isShowing = true
            AppLogger.i(TAG, "面板已展开: x=$panelX y=$panelY ball=($ballX,$ballY) size=$ballSize")
        } catch (e: Exception) {
            AppLogger.e(TAG, "展开面板失败", e)
            cleanup()
        }
    }

    /** 收起面板 */
    fun dismiss() {
        if (!isShowing) return
        cleanup()
        AppLogger.i(TAG, "面板已收起")
    }

    private fun cleanup() {
        panelView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                AppLogger.w(TAG, "removeView 异常: ${e.message}")
            }
            view.disposeComposition()
        }
        lifecycleOwner?.let { owner ->
            owner.performPause()
            owner.performStop()
            owner.performDestroy()
        }
        panelView = null
        lifecycleOwner = null
        layoutParams = null
        positionRefined = false
        isShowing = false
    }

    /** 通知面板外部状态变化（录音/播放开始或停止）— 触发 Composable 重组 */
    fun notifyExternalStateChange() {
        scope.refreshTick.value = System.nanoTime()
    }

    private fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
}

// ============================================================
// FloatingPanelContent — 面板根 Composable
// ============================================================

@Composable
private fun FloatingPanelContent(scope: FloatingPanelScope) {
    // 默认选中"录音" tab（用户明确要求）
    var selectedTab by remember { mutableIntStateOf(0) }

    // 订阅刷新触发器 + 读取实时状态
    val refreshKey = scope.refreshTick.value
    val isRecording = scope.isRecording()
    val isPlaying = scope.isPlaying()
    val latestPack = scope.getLatestPack()

    Surface(
        modifier = Modifier.width(280.dp),
        color = SurfaceColor,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ===== 顶部四菜单导航栏 =====
            PanelTabRow(
                selectedIndex = selectedTab,
                isRecording = isRecording,
                onTabSelected = { idx ->
                    // 录制中时仅允许切换到"录音"tab（其余三个变灰不可点）
                    if (!isRecording || idx == 0) {
                        selectedTab = idx
                    }
                },
            )
            Divider(color = Color(0xFF333333), thickness = 1.dp)

            // 录制中状态条（非录音 tab 时显示提示）
            if (isRecording && selectedTab != 0) {
                // 录制中被强制留在录音 tab，这里理论不会触发，但兜底
                RecordingStatusBar()
            }

            // ===== 内容区（按 tab 切换，可滚动）=====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = FloatingPanel.PANEL_CONTENT_MAX_HEIGHT_DP.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                when (selectedTab) {
                    0 -> RecordingTab(
                        scope = scope,
                        isRecording = isRecording,
                        isPlaying = isPlaying,
                        latestPack = latestPack,
                        refreshKey = refreshKey,
                    )
                    1 -> EqTab(scope)
                    2 -> VoiceChangeTab(scope)
                    3 -> EffectsTab(scope)
                }
            }
        }
    }
}

// ============================================================
// PanelTabRow — 顶部四菜单导航栏
// ============================================================
@Composable
private fun PanelTabRow(
    selectedIndex: Int,
    isRecording: Boolean,
    onTabSelected: (Int) -> Unit,
) {
    val tabs = listOf("录音", "EQ与增益", "变音", "快捷音效库")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { idx, label ->
            // 录制中：非录音 tab 变灰不可点
            val tabEnabled = !isRecording || idx == 0
            val isSelected = selectedIndex == idx
            Surface(
                color = when {
                    isSelected -> PrimaryContainerColor
                    !tabEnabled -> DisabledColor
                    else -> SurfaceVariantColor
                },
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = tabEnabled) { onTabSelected(idx) },
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 录音 tab 录制中显示红点
                    if (idx == 0 && isRecording) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(RecordingColor)
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        text = label,
                        fontSize = 9.sp,
                        color = when {
                            isSelected -> PrimaryColor
                            !tabEnabled -> TextFadeColor
                            else -> TextSubColor
                        },
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 录制中状态条 */
@Composable
private fun RecordingStatusBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RecordingColor.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(RecordingColor)
        )
        Spacer(Modifier.width(6.dp))
        Text("录制中...", fontSize = 10.sp, color = RecordingColor, fontWeight = FontWeight.Medium)
    }
}

// ============================================================
// 录音 Tab（默认界面）
// ============================================================
@Composable
private fun RecordingTab(
    scope: FloatingPanelScope,
    isRecording: Boolean,
    isPlaying: Boolean,
    latestPack: VoicePack?,
    refreshKey: Long,
) {
    // ===== 录音计时器（最长 60s）=====
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var recordStart by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordStart = System.currentTimeMillis()
            elapsedMs = 0L
            while (true) {
                elapsedMs = System.currentTimeMillis() - recordStart
                if (elapsedMs >= 60_000L) {
                    // 达到 60s 上限，自动停止
                    scope.stopManualRecording()
                    break
                }
                delay(100L)
            }
        } else {
            recordStart = 0L
            elapsedMs = 0L
        }
    }

    // ===== PTT 提示 =====
    Surface(
        color = SurfaceVariantColor,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = null,
                tint = PrimaryColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "按住悬浮球说话，松手后自动处理",
                fontSize = 10.sp,
                color = TextSubColor,
            )
        }
    }

    Spacer(Modifier.height(10.dp))

    // ===== 提前录音按钮 / 录音中状态 =====
    if (isRecording) {
        // 录音中：显示计时器 + 波形 + 停止按钮
        Surface(
            color = Color(0xFF4A1A1A),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().clickable { scope.stopManualRecording() },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Stop, null, tint = RecordingColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("录音中", fontSize = 12.sp, color = RecordingColor, fontWeight = FontWeight.Bold)
                    Text(formatDuration(elapsedMs) + " / 01:00", fontSize = 10.sp, color = TextDimColor)
                }
                WaveformAnimation(active = true, modifier = Modifier.size(width = 48.dp, height = 20.dp))
            }
        }
    } else {
        // 未录音：显示"提前录音"按钮
        Surface(
            color = SurfaceVariantColor,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().clickable { scope.startManualRecording() },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Mic, null, tint = PrimaryColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("提前录音", fontSize = 12.sp, color = TextMainColor, fontWeight = FontWeight.Medium)
                    Text("手动开始/停止（最长 60s）", fontSize = 9.sp, color = TextDimColor)
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    // ===== 最近语音包快捷区域 =====
    Text("最近语音包", fontSize = 10.sp, color = TextDimColor, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    if (latestPack != null) {
        LatestPackCard(
            pack = latestPack,
            isPlaying = isPlaying,
            onPlay = {
                if (isPlaying) scope.stopPlayback() else scope.playPack(latestPack)
            },
            onShare = { scope.sharePack(latestPack) },
        )
    } else {
        Surface(
            color = SurfaceVariantColor.copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "暂无语音包\n长按悬浮球或点击「提前录音」开始录制",
                fontSize = 10.sp,
                color = TextFadeColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
            )
        }
    }
}

/** 最近语音包卡片 */
@Composable
private fun LatestPackCard(
    pack: VoicePack,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
) {
    Surface(
        color = if (isPlaying) PrimaryContainerColor else SurfaceVariantColor,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.GraphicEq,
                null,
                tint = if (isPlaying) PrimaryColor else TextDimColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pack.name,
                    fontSize = 11.sp,
                    color = TextMainColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(formatDuration(pack.durationMs), fontSize = 9.sp, color = TextDimColor)
            }
            // 播放/停止按钮
            Surface(
                color = PrimaryColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(28.dp).clickable { onPlay() },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        null,
                        tint = PrimaryColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            // 分享按钮
            Surface(
                color = PrimaryColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(28.dp).clickable { onShare() },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Share, null, tint = PrimaryColor, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

// ============================================================
// EQ 与增益 Tab
// ============================================================
@Composable
private fun EqTab(scope: FloatingPanelScope) {
    val eqEnabled by scope.eqEnabled
    val gain by scope.gainDb
    val bass by scope.bassDb
    val treble by scope.trebleDb

    // 启用开关行
    SwitchRow(
        label = "启用 EQ",
        checked = eqEnabled,
        onToggle = { scope.setEqEnabled(it) },
    )
    Spacer(Modifier.height(8.dp))

    if (!eqEnabled) {
        Text("EQ 已关闭，低音/高音模块旁路", fontSize = 9.sp, color = TextFadeColor)
        Spacer(Modifier.height(4.dp))
    }

    // 3 个滑块（关闭时变灰不可拖）
    PanelSlider(
        label = "增益",
        valueText = "${"%.1f".format(gain)}dB",
        value = gain,
        range = -24f..24f,
        enabled = true, // 增益始终启用（开关只控制 Bass/Treble）
        onChange = { scope.setGain(it) },
    )
    PanelSlider(
        label = "低音",
        valueText = "${"%.1f".format(bass)}dB",
        value = bass,
        range = -12f..12f,
        enabled = eqEnabled,
        onChange = { scope.setBass(it) },
    )
    PanelSlider(
        label = "高音",
        valueText = "${"%.1f".format(treble)}dB",
        value = treble,
        range = -12f..12f,
        enabled = eqEnabled,
        onChange = { scope.setTreble(it) },
    )
}

// ============================================================
// 变音 Tab
// ============================================================
@Composable
private fun VoiceChangeTab(scope: FloatingPanelScope) {
    val voiceEnabled by scope.voiceEnabled
    val pitch by scope.pitchSemitones
    val formant by scope.formantShift

    // 启用开关行
    SwitchRow(
        label = "启用变音",
        checked = voiceEnabled,
        onToggle = { scope.setVoiceEnabled(it) },
    )
    Spacer(Modifier.height(8.dp))

    // 预设按钮组（4 个：萝莉 / 大叔 / 机器人 / 原声）
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        scope.voicePresets.forEachIndexed { idx, preset ->
            Surface(
                color = SurfaceVariantColor,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).clickable(enabled = voiceEnabled) {
                    scope.applyVoicePreset(idx)
                },
            ) {
                Text(
                    preset.name,
                    fontSize = 10.sp,
                    color = if (voiceEnabled) TextSubColor else TextFadeColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 6.dp).fillMaxWidth(),
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    if (!voiceEnabled) {
        Text("变音已关闭，变调/共振峰旁路", fontSize = 9.sp, color = TextFadeColor)
        Spacer(Modifier.height(4.dp))
    }

    // 变调滑块（整数半音，-12 ~ +12）
    PanelSlider(
        label = "变调",
        valueText = (if (pitch >= 0) "+" else "") + "$pitch",
        value = pitch.toFloat(),
        range = -12f..12f,
        steps = 23, // 24 个间隔 = 25 个离散值（-12, -11, ..., 12）
        enabled = voiceEnabled,
        onChange = { scope.setPitch(it.toInt()) },
    )
    // 共振峰滑块（-12 ~ +12）
    PanelSlider(
        label = "共振峰",
        valueText = (if (formant >= 0) "+" else "") + "${"%.1f".format(formant)}",
        value = formant,
        range = -12f..12f,
        enabled = voiceEnabled,
        onChange = { scope.setFormant(it) },
    )
}

// ============================================================
// 快捷音效库 Tab
// ============================================================
private const val EFFECTS_TAB_TAG = "EffectsTab"

@Composable
private fun EffectsTab(scope: FloatingPanelScope) {
    val currentIdx by scope.effectPresetIdx
    val context = LocalContext.current

    // ===== 我的音效：快捷播放器 + 播放中状态 + 音效列表 =====
    // Task 7: 播放器提升到 Service 持有（单例，跨面板展开/收起复用），经 scope 获取共享实例；
    // 面板收回（FloatingPanel.cleanup → disposeComposition）不再 stop 播放 —— 播放跨面板存活。
    val effectPlayer = remember { scope.getEffectPlayer() }
    // Task 7: 播放中音效 id 由 Service 级 state 持有（scope.playingEffectId），
    // 面板收回再展开仍显示播放中状态并可停止
    val playingEffectId by scope.playingEffectId
    // 音效列表：本 tab 进入组合时加载一次（loadAll 为 createdAt 升序 → 倒序取最近 6 条）
    val effectList = remember {
        SoundEffectStore.loadAll(context)
            .sortedByDescending { it.createdAt }
            .take(6)
    }

    // ===== DSP 预设区（5 个，原有逻辑不动）=====
    scope.effectPresets.forEachIndexed { idx, preset ->
        val isSelected = currentIdx == idx
        Surface(
            color = if (isSelected) PrimaryContainerColor else SurfaceVariantColor,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(preset.icon, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        preset.name,
                        fontSize = 12.sp,
                        color = if (isSelected) PrimaryColor else TextMainColor,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(preset.desc, fontSize = 9.sp, color = TextDimColor)
                }
                // 应用按钮
                Surface(
                    color = if (isSelected) PrimaryColor else PrimaryColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.clickable { scope.applyEffectPreset(idx) },
                ) {
                    Text(
                        if (isSelected) "已应用" else "应用",
                        fontSize = 10.sp,
                        color = if (isSelected) Color.Black else PrimaryColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }

    // ===== 我的音效子区（用户导入的音效快捷播放）=====
    Spacer(Modifier.height(8.dp))
    Text("我的音效", fontSize = 10.sp, color = TextDimColor, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))

    if (effectList.isEmpty()) {
        // 空态
        Text(
            "还没有音效，去 App 音效库导入",
            fontSize = 10.sp,
            color = TextFadeColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
        )
    } else {
        effectList.forEach { effect ->
            MyEffectCard(
                effect = effect,
                isPlaying = playingEffectId == effect.id,
                onToggle = {
                    if (playingEffectId == effect.id) {
                        // 正在播放该条 → 停止（stop 会回调 onComplete 清空共享状态，
                        // 这里先显式置 null 双保险，随后回调再置 null 也无妨）
                        AppLogger.i(EFFECTS_TAB_TAG, "停止我的音效: ${effect.name}")
                        effectPlayer.stop()
                        scope.playingEffectId.value = null
                    } else {
                        // 播放该条：先记录目标 id 再 play。
                        // play 内部会先停掉旧播放（不触发旧回调，令牌机制兜底），
                        // 因此切换曲目不会出现"旧回调把新状态清掉"的竞态。
                        AppLogger.i(EFFECTS_TAB_TAG, "播放我的音效: ${effect.name}")
                        scope.playingEffectId.value = effect.id
                        val file = SoundEffectStore.effectFile(context, effect.fileName)
                        effectPlayer.play(file) {
                            // onComplete 在自然播完 / 出错 / 停止时由主线程回调 → 清空播放中状态。
                            // 写 Service 级 state（scope.playingEffectId 返回同一实例），
                            // 即使面板已收回再展开也正确显示/清除。
                            scope.playingEffectId.value = null
                        }
                    }
                },
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 我的音效卡片：整卡点击播放/停止；播放中高亮（背景色 + 边框） */
@Composable
private fun MyEffectCard(
    effect: SoundEffect,
    isPlaying: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        color = if (isPlaying) PrimaryContainerColor else SurfaceVariantColor,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isPlaying) PrimaryColor else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable { onToggle() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (isPlaying) PrimaryColor else TextDimColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = effect.name,
                    fontSize = 11.sp,
                    color = if (isPlaying) PrimaryColor else TextMainColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (effect.durationMs > 0) {
                    Text(
                        text = formatDuration(effect.durationMs),
                        fontSize = 9.sp,
                        color = TextDimColor,
                    )
                }
            }
        }
    }
}

// ============================================================
// 通用 Composable 组件
// ============================================================

/** 开关行（label + Switch） */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, color = TextMainColor, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            modifier = Modifier.height(20.dp),
            colors = SwitchDefaults.colors(checkedTrackColor = PrimaryColor),
        )
    }
}

/** 紧凑滑块（label + 当前值 + Slider） */
@Composable
private fun PanelSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 10.sp,
            color = if (enabled) TextDimColor else TextFadeColor,
            modifier = Modifier.width(36.dp),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            steps = steps,
            modifier = Modifier.weight(1f).height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = PrimaryColor,
                activeTrackColor = PrimaryColor,
                inactiveTrackColor = if (enabled) Color(0xFF49454F) else Color(0xFF333333),
            ),
        )
        Text(
            valueText,
            fontSize = 9.sp,
            color = if (enabled) PrimaryColor else TextFadeColor,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End,
        )
    }
}

/** 录音波形动画（5 根竖线条跳动） */
@Composable
private fun WaveformAnimation(active: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val barCount = 5
    val phases = (0 until barCount).map { i ->
        transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(280 + i * 70, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "bar_$i",
        )
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        phases.forEach { phase ->
            val h = if (active) (8 + 12 * phase.value) else 4f
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (active) RecordingColor else TextFadeColor)
            )
        }
    }
}

// ============================================================
// 时间格式化辅助（mm:ss）
// ============================================================
private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}
