// SettingsPage.kt — 设置页（Task 6 完整实现）
// ============================================================
// 设计原则：M3 分组容器 + Dieter Rams "诚实" 原则
//   - 开关即开关，不做隐藏式触发；每项控制与其行为一一对应
//   - 每个分组用 GradientCard 包裹，LazyColumn 垂直滚动
//   - 悬浮球开关为核心功能：权限检测 / Service 启停 / 持久化 完整实现
//
// 五大分组（自上而下）：
//   1. 悬浮球（开关 + 长按触发时长）
//   2. 录音（采样率 / 最大录音时长 / 隐藏最近任务）
//   3. 音频引擎（引擎选择 RadioButton）
//   4. 插件（UGC 插件 / 模块链编辑器入口）
//   5. 关于（版本 / GitHub / 爱发电 / 鸣谢）
// 开发者入口为隐藏入口：关于组版本号连点 5 次（2 秒窗口）直接进入开发者设置，无界面痕迹

@file:OptIn(ExperimentalMaterial3Api::class)

package aoeck.dwyai.com.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import aoeck.dwyai.com.AppLogger
import aoeck.dwyai.com.AudioEngine
import aoeck.dwyai.com.NativeAudioProcessor
import aoeck.dwyai.com.floating.FloatingBallService
import aoeck.dwyai.com.floating.OverlayPermissionHelper
import aoeck.dwyai.com.ui.components.AnimatedSlider
import aoeck.dwyai.com.ui.components.GradientCard
import aoeck.dwyai.com.ui.components.PressableButton
import aoeck.dwyai.com.ui.components.SectionHeader
import aoeck.dwyai.com.ui.theme.MaidMicSpacing
import aoeck.dwyai.com.util.HapticHelper

@Composable
fun SettingsPage(
    context: Context,
    modifier: Modifier = Modifier,
    // === 由 MainActivity 持有的单一状态源（回调上报） ===
    enableLraRhythm: Boolean = false,
    onLraRhythmToggle: (Boolean) -> Unit = {},
    // === 子页面入口回调（由 MainActivity 置对应 flag） ===
    // 注：模块链编辑器入口已暂时隐藏（与 UGC 插件配套，UGC 未实现），
    //     恢复时在此重新添加 onOpenEditor 回调。
    onOpenDeveloperSettings: () -> Unit = {}
) {
    // maidmic_prefs：应用级开关与引擎选择
    val prefs = context.getSharedPreferences("maidmic_prefs", Context.MODE_PRIVATE)
    // maidmic_eq：录音参数（采样率 / 最大录音时长）
    val eqPrefs = context.getSharedPreferences("maidmic_eq", Context.MODE_PRIVATE)

    // 版本号连点计数（2 秒窗口内累计，超时重置）——隐藏入口，界面无任何痕迹
    var versionTapCount by remember { mutableIntStateOf(0) }
    var lastVersionTapAt by remember { mutableStateOf(0L) }

    // 版本号连点 5 次（2 秒窗口）直接进入开发者设置：不持久化、不显示任何入口
    fun handleVersionTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastVersionTapAt > 2000L) versionTapCount = 0
        lastVersionTapAt = now
        versionTapCount++
        if (versionTapCount >= 5) {
            versionTapCount = 0
            AppLogger.i("Settings", "版本号连点 5 次，直接进入开发者设置")
            onOpenDeveloperSettings()
        }
    }

    // === 引擎状态（与 NativeAudioProcessor 单例同步） ===
    var currentEngine by remember { mutableStateOf(NativeAudioProcessor.getEngine()) }

    // === 悬浮球状态 ===
    var floatingBallEnabled by remember {
        mutableStateOf(prefs.getBoolean("floating_ball_enabled", false))
    }
    // 悬浮窗权限申请结果回调：从系统"显示在其他应用上层"设置页返回后重新检测
    // 已授权 → 启动 Service + 开关置位；未授权 → 开关保持关 + Toast 提示
    val overlayPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (OverlayPermissionHelper.canDrawOverlays(context)) {
            val intent = Intent(context, FloatingBallService::class.java)
            ContextCompat.startForegroundService(context, intent)
            floatingBallEnabled = true
            prefs.edit().putBoolean("floating_ball_enabled", true).apply()
            AppLogger.i("FloatingBall", "悬浮窗权限已授予，启动 Service")
            Toast.makeText(context, "悬浮球已开启", Toast.LENGTH_SHORT).show()
        } else {
            floatingBallEnabled = false
            prefs.edit().putBoolean("floating_ball_enabled", false).apply()
            AppLogger.w("FloatingBall", "悬浮窗权限未授予，开关保持关闭")
            Toast.makeText(context, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = MaidMicSpacing.s, vertical = MaidMicSpacing.s),
        verticalArrangement = Arrangement.spacedBy(MaidMicSpacing.s)
    ) {
        // ---------- 顶部标题 ----------
        item {
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // ============================================================
        // 1. 悬浮球组
        // 开关默认关；开启需 SYSTEM_ALERT_WINDOW 权限
        // ============================================================
        item {
            GradientCard(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = "悬浮球")
                Spacer(Modifier.height(MaidMicSpacing.xs))
                SwitchRow(
                    label = "开启悬浮球",
                    subtitle = if (floatingBallEnabled)
                        "悬浮球运行中，可拖动移动"
                    else
                        "开启后显示悬浮球，需悬浮窗权限",
                    checked = floatingBallEnabled,
                    onCheckedChange = { enable ->
                        HapticHelper.basic()
                        if (enable) {
                            // 开启：先检测悬浮窗权限
                            if (OverlayPermissionHelper.canDrawOverlays(context)) {
                                // 已授权 → 直接启动 Service
                                val intent = Intent(context, FloatingBallService::class.java)
                                ContextCompat.startForegroundService(context, intent)
                                floatingBallEnabled = true
                                prefs.edit().putBoolean("floating_ball_enabled", true).apply()
                                AppLogger.i("FloatingBall", "悬浮球已开启")
                                Toast.makeText(context, "悬浮球已开启", Toast.LENGTH_SHORT).show()
                            } else {
                                // 未授权 → 跳转系统"显示在其他应用上层"设置页
                                // 返回结果由 overlayPermLauncher 回调处理
                                // 此处不置位开关，保持关，避免误导用户
                                AppLogger.i("FloatingBall", "无悬浮窗权限，跳转系统设置")
                                val permIntent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                overlayPermLauncher.launch(permIntent)
                            }
                        } else {
                            // 关闭：停止 Service，球消失
                            val intent = Intent(context, FloatingBallService::class.java)
                            context.stopService(intent)
                            floatingBallEnabled = false
                            prefs.edit().putBoolean("floating_ball_enabled", false).apply()
                            AppLogger.i("FloatingBall", "悬浮球已关闭")
                        }
                    }
                )

                // 长按触发时长滑块（仅悬浮球开启时显示）
                // 范围 500~5000ms，默认 3000ms，步进 100ms
                if (floatingBallEnabled) {
                    Spacer(Modifier.height(MaidMicSpacing.s))
                    var holdDurationMs by remember {
                        mutableIntStateOf(prefs.getInt("hold_duration_ms", 3000))
                    }
                    AnimatedSlider(
                        label = "长按触发时长",
                        value = holdDurationMs.toFloat(),
                        onValueChange = { v ->
                            // 取整到 100ms 步进
                            val snapped = ((v / 100).toInt() * 100).coerceIn(500, 5000)
                            holdDurationMs = snapped
                            prefs.edit().putInt("hold_duration_ms", snapped).apply()
                        },
                        valueRange = 500f..5000f,
                        valueFormatter = { "${it.toInt()} ms" }
                    )
                    Text(
                        text = "长按 ${holdDurationMs}ms 触发录音（双击播放最近语音包）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ============================================================
        // 2. 录音组（从旧 LegacySettingsPage 迁移核心项，简化版）
        // ============================================================
        item {
            GradientCard(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = "录音")
                Spacer(Modifier.height(MaidMicSpacing.xs))

                // 采样率 dropdown：44100 / 48000
                var sampleRate by remember { mutableIntStateOf(eqPrefs.getInt("sample_rate", 48000)) }
                var srExpanded by remember { mutableStateOf(false) }
                val srLabel = when (sampleRate) {
                    44100 -> "44.1 kHz"
                    48000 -> "48 kHz"
                    else -> "${sampleRate / 1000} kHz"
                }
                ExposedDropdownMenuBox(
                    expanded = srExpanded,
                    onExpandedChange = { srExpanded = it }
                ) {
                    OutlinedTextField(
                        value = srLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("采样率") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(srExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = srExpanded,
                        onDismissRequest = { srExpanded = false }
                    ) {
                        listOf(44100, 48000).forEach { rate ->
                            DropdownMenuItem(
                                text = {
                                    Text(if (rate == 44100) "44.1 kHz" else "${rate / 1000} kHz")
                                },
                                onClick = {
                                    sampleRate = rate
                                    eqPrefs.edit().putInt("sample_rate", rate).apply()
                                    srExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(MaidMicSpacing.s))

                // 最大录音时长 slider：10~60s，默认 30s
                var maxDuration by remember {
                    mutableIntStateOf(eqPrefs.getInt("max_recording_duration", 30))
                }
                AnimatedSlider(
                    label = "最大录音时长",
                    value = maxDuration.toFloat(),
                    onValueChange = { v ->
                        val snapped = v.toInt().coerceIn(10, 60)
                        maxDuration = snapped
                        eqPrefs.edit().putInt("max_recording_duration", snapped).apply()
                    },
                    valueRange = 10f..60f,
                    valueFormatter = { "${it.toInt()} s" }
                )

                Spacer(Modifier.height(MaidMicSpacing.s))

                // 隐藏最近任务开关
                var hideRecents by remember { mutableStateOf(prefs.getBoolean("hide_recents", false)) }
                SwitchRow(
                    label = "隐藏最近任务",
                    subtitle = "从最近任务列表中隐藏本应用",
                    checked = hideRecents,
                    onCheckedChange = { hide ->
                        HapticHelper.basic()
                        hideRecents = hide
                        prefs.edit().putBoolean("hide_recents", hide).apply()
                    }
                )
            }
        }

        // ============================================================
        // 触感反馈组
        //   - 触感反馈开关（默认开）
        // ============================================================
        item {
            GradientCard(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = "触感反馈")
                Spacer(Modifier.height(MaidMicSpacing.xs))
                var hapticEnabled by remember {
                    mutableStateOf(prefs.getBoolean("haptic_enabled", true))
                }
                SwitchRow(
                    label = "开启触感反馈",
                    subtitle = "操作时触发振动反馈，LRA 设备上体验更佳",
                    checked = hapticEnabled,
                    onCheckedChange = { enabled ->
                        hapticEnabled = enabled
                        prefs.edit().putBoolean("haptic_enabled", enabled).apply()
                        HapticHelper.setEnabled(enabled)
                        if (enabled) HapticHelper.basic()
                    }
                )

                // LRA 变声节拍开关（仅 LRA 设备可见；状态由 MainActivity 统一持有）
                val hapticCapabilities = remember { HapticHelper.isAvailable() }
                if (hapticCapabilities.third) {
                    Spacer(Modifier.height(MaidMicSpacing.xs))
                    SwitchRow(
                        label = "变声节拍",
                        subtitle = "试听时同步波形微节拍（LRA 独占）",
                        checked = enableLraRhythm,
                        onCheckedChange = { enabled ->
                            onLraRhythmToggle(enabled)
                            HapticHelper.basic()
                        }
                    )
                }
            }
        }

        // ============================================================
        // 3. 引擎组
        //   - 引擎选择 RadioButton → setEngine + saveEngine
        // ============================================================
        item {
            GradientCard(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = "音频引擎")
                Spacer(Modifier.height(MaidMicSpacing.xs))

                // 引擎选择（RadioButton）
                AudioEngine.entries.forEach { engine ->
                    val selectEngine = {
                        HapticHelper.basic()
                        currentEngine = engine
                        NativeAudioProcessor.setEngine(engine)
                        NativeAudioProcessor.saveEngine(prefs)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = selectEngine)
                            .padding(vertical = MaidMicSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentEngine == engine,
                            onClick = selectEngine
                        )
                        Spacer(Modifier.width(MaidMicSpacing.xs))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                engine.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                engine.description,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ============================================================
        // 4. 模块链编辑器组（暂时隐藏）
        //   - 模块链编辑器与 UGC 模块插件配套使用，UGC 尚未实现，入口暂隐藏；
        //     待 UGC 插件功能落地后与开发者设置页的 UGC 开关一同恢复。
        // ============================================================

        // ============================================================
        // 5. 关于组
        //   - 版本信息 / GitHub / 爱发电
        // ============================================================
        item {
            GradientCard(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = "关于")
                Spacer(Modifier.height(MaidMicSpacing.xs))

                // 版本号：连续点击 5 次（2 秒窗口）直接进入开发者设置（隐藏入口）
                Text(
                    "MaidMic v0.1.0-alpha",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { handleVersionTap() }
                        .padding(vertical = MaidMicSpacing.xs)
                )
                Spacer(Modifier.height(MaidMicSpacing.xs))
                Text(
                    "作者: 我是真的会谢",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(MaidMicSpacing.s))

                // GitHub 链接
                LinkRow("GitHub 仓库", "https://github.com/aoeck/MaidMic", context)

                Spacer(Modifier.height(MaidMicSpacing.xs))

                // 爱发电链接
                LinkRow("爱发电支持", "https://afdian.net/a/aoeck", context)
            }
        }

        // 底部留白
        item { Spacer(Modifier.height(MaidMicSpacing.m)) }
    }
}

// ============================================================
// 内部复用组件
// ============================================================

/** 开关行：左侧标签 + 副标题，右侧 Switch（Dieter Rams 诚实原则：开关即开关） */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/** 超链接行：下划线 Text，点击打开 URL（失败时 Toast 提示） */
@Composable
private fun LinkRow(label: String, url: String, context: Context) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
                }
            }
            .padding(vertical = MaidMicSpacing.xs)
    )
}
