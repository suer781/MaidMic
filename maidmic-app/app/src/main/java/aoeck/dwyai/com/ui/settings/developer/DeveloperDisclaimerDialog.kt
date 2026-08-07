// maidmic-app/app/src/main/java/com/maidmic/ui/settings/developer/DeveloperDisclaimerDialog.kt
// MaidMic 开发者选项免责声明
// ============================================================
// 这个对话框在用户首次打开开发者选项时弹出。
// 用户必须手动输入下方的声明文字来确认。
// 没错，一个字一个字打进去，不是点个按钮就完事的。
//
// 内容涉及 UGC 插件系统、P2P/PCDN、原生代码执行等风险。
// 我们要确保用户真的读过并理解了。

package aoeck.dwyai.com.ui.settings.developer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import aoeck.dwyai.com.AppLogger
import aoeck.dwyai.com.LogLevel
import aoeck.dwyai.com.NativeAudioProcessor
import aoeck.dwyai.com.EngineHealth
import aoeck.dwyai.com.util.HapticHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext

// 免责声明文案（走个过场：确认时只需填任意内容）
// Disclaimer text (a formality: any input confirms)
const val DISCLAIMER_TEXT = "你一定！一定！要启用吗（思考）\n" +
    "我先提前把话说在前面，我还没做好\n" +
    "其次就是我没做好（理直气壮）\n" +
    "哦，对了还有就是我没想好（你不会怪我的吧）\n" +
    "你想走个过场你就填吧，一填一个不吱声\n\n" +
    "哦对了，差点忘了表明未来恶意插件的态度了\n" +
    "未来如果出现了恶意插件恶意代码造成的损失\n" +
    "贡献开发者和本软件作者没有义务去为第三方插件造成的任何的损失负责"
const val DISCLAIMER_TEXT_EN = "I have read and understand the potential consequences of downloading malicious plugins"

/**
 * 开发者免责声明弹窗
 * 
 * 用户必须手动输入免责声明文本才能启用 UGC 插件功能。
 * 这是最后一道防线——确保用户明白他们在做什么。
 * 
 * @param isChinese 界面语言（中文/英文）
 * @param onConfirmed 用户确认后的回调
 * @param onDismiss 用户取消退出
 */
@Composable
fun DeveloperDisclaimerDialog(
    isChinese: Boolean,
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit
) {
    // 用户输入的内容
    var typedText by remember { mutableStateOf("") }
    // 是否输入了错误文字（用于显示红色提示）
    var hasError by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isChinese) "⚠️ 开发者模式 - 免责声明" else "⚠️ Developer Mode - Disclaimer",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
            ) {
                // 声明文案（用户自定义）
                Text(
                    text = if (isChinese) DISCLAIMER_TEXT else DISCLAIMER_TEXT_EN,
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 输入框（走个过场：填任意内容即可确认）
                // Input field (just a formality: any input confirms)
                OutlinedTextField(
                    value = typedText,
                    onValueChange = {
                        typedText = it
                        hasError = false  // 用户重新输入时清除错误状态
                    },
                    label = { Text(if (isChinese) "走个过场，随便填点内容" else "Type anything to confirm") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = hasError,
                    supportingText = if (hasError) {
                        { Text(if (isChinese) "至少填点内容吧（走个过场）" else "Type something to confirm") }
                    } else null,
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (typedText.isNotBlank()) {
                                onConfirmed()
                            } else {
                                hasError = true
                            }
                        }
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (typedText.isNotBlank()) {
                        onConfirmed()
                    } else {
                        hasError = true
                    }
                }
            ) {
                Text(if (isChinese) "我知道了" else "Got it")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isChinese) "算了" else "Cancel")
            }
        }
    )
}

/**
 * 开发者选项页面
 * 
 * 包含 UGC 插件的开关（需要输入免责声明）、
 * DAG/简易编辑模式切换、以及其他调试功能。
 * 
 * 注意：这个页面默认隐藏，用户需要在"设置 → 关于"页面连续点击版本号 5 次才会出现。
 * 没错，跟 Android 原生开发者选项一样。
 */
@Composable
fun DeveloperSettingsPage(
    isChinese: Boolean,
    isUgcEnabled: Boolean,
    onUgcToggle: (Boolean) -> Unit,
    currentEditorMode: String,  // "simple" 或 "dag"
    onEditorModeChange: (String) -> Unit,
    onBack: () -> Unit
) {
    // 是否显示免责声明弹窗
    var showDisclaimer by remember { mutableStateOf(false) }
    // UGC 功能尚未实现：确认免责声明后弹"还没做好"提示
    var showNotReadyDialog by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 标题 + 退出按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, contentDescription = if (isChinese) "关闭" else "Close")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isChinese) "⚙ 开发者选项" else "⚙ Developer Options",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        Text(
            text = if (isChinese) "这些功能仅供高级用户使用。操作不当可能导致设备不稳定。" else "For advanced users only. Misuse may cause instability.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // ============================================================
        // UGC 插件开关
        // UGC Plugin Toggle
        // ============================================================
        // 默认关闭，开启需要输入免责声明
        // Off by default, requires disclaimer text to enable
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isChinese) "UGC 插件" else "UGC Plugins",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (isChinese)
                                "功能尚未完成，敬请期待。开启需填写免责声明（走个过场）。"
                            else
                                "Feature not ready yet. Enabling requires a disclaimer (just a formality).",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Switch(
                        checked = isUgcEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && !isUgcEnabled) {
                                // 开启时弹出免责声明
                                showDisclaimer = true
                            } else {
                                onUgcToggle(false)
                            }
                        }
                    )
                }
                
                if (isUgcEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    // 当前状态显示
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = if (isChinese) "⚠ 已启用。请谨慎安装插件。" else "⚠ Enabled. Install plugins with caution.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // ============================================================
        // 模块链编辑器模式切换
        // Module Chain Editor Mode Switch
        // ============================================================
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isChinese) "模块链编辑器模式" else "Module Chain Editor Mode",
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (isChinese)
                        "切换编辑器的拓扑模式。切换时保留所有模块和参数。"
                    else
                        "Switch editor topology mode. Preserves all modules and parameters.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 简易模式按钮
                    FilterChip(
                        selected = currentEditorMode == "simple",
                        onClick = { onEditorModeChange("simple") },
                        label = { Text(if (isChinese) "简易 (线性)" else "Simple (Linear)") },
                        modifier = Modifier.weight(1f)
                    )
                    
                    // DAG 模式按钮
                    FilterChip(
                        selected = currentEditorMode == "dag",
                        onClick = { onEditorModeChange("dag") },
                        label = { Text("DAG") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        
        // ============================================================
        // 触感诊断（Haptic Diagnostics）
        // ============================================================
        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isChinese) "🔧 触感诊断" else "🔧 Haptic Diagnostics",
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))

                val hapticInfo = remember { HapticHelper.isAvailable() }

                DiagnosticRow(
                    label = if (isChinese) "振动器" else "Vibrator",
                    value = if (hapticInfo.first) "✓" else "✗",
                    isOk = hapticInfo.first
                )
                DiagnosticRow(
                    label = if (isChinese) "VibrationEffect" else "VibrationEffect",
                    value = if (hapticInfo.second) "✓" else "✗",
                    isOk = hapticInfo.second
                )
                DiagnosticRow(
                    label = if (isChinese) "LRA 线性马达" else "LRA Motor",
                    value = if (hapticInfo.third) "✓" else "✗",
                    isOk = hapticInfo.third
                )
                DiagnosticRow(
                    label = if (isChinese) "触感开关" else "Haptic Enabled",
                    value = if (HapticHelper.isEnabled()) "ON" else "OFF",
                    isOk = HapticHelper.isEnabled()
                )

                Spacer(modifier = Modifier.height(12.dp))

                var isTesting by remember { mutableStateOf(false) }
                // 使用组合作用域：页面销毁时协程随组合取消，finally 会兜底停止震动
                val testScope = rememberCoroutineScope()

                // 组件销毁时若测试仍在进行，立即停止震动，避免残留
                DisposableEffect(Unit) {
                    onDispose {
                        HapticHelper.stop()
                    }
                }

                Button(
                    onClick = {
                        if (isTesting) return@Button
                        isTesting = true
                        testScope.launch {
                            try {
                                // 进入前先清残留震动，避免与上一轮测试重叠
                                HapticHelper.stop()
                                HapticHelper.basic()
                                delay(300)
                                HapticHelper.stop()
                                HapticHelper.success()
                                delay(300)
                                HapticHelper.stop()
                                HapticHelper.warning()
                                delay(300)
                                HapticHelper.stop()
                                HapticHelper.error()
                                delay(300)
                                HapticHelper.stop()
                                // 连续震动会无限循环，限时展示约 1.5s 后由调用方停止
                                HapticHelper.continuous()
                                delay(1500)
                                HapticHelper.stop()
                                HapticHelper.mechanical()
                                delay(300)
                                HapticHelper.stop()
                                HapticHelper.lraRhythm()
                            } finally {
                                // 正常结束、协程取消或异常时都确保停止震动并复位按钮
                                HapticHelper.stop()
                                isTesting = false
                            }
                        }
                    },
                    enabled = !isTesting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isTesting)
                            (if (isChinese) "测试中..." else "Testing...")
                        else
                            (if (isChinese) "测试全部振动语义" else "Test All Semantics")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (hapticInfo.third) {
                    Button(
                        onClick = {
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                HapticHelper.mechanical()
                                delay(300)
                                HapticHelper.lraRhythm()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isChinese) "测试 LRA 独占" else "Test LRA Exclusive")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        // ============================================================
        // 应用内日志查看器
        // In-app Log Viewer
        // ============================================================
        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // 日志级别过滤（提到Column作用域，供内部所有组件访问）
                var logFilter by remember { mutableStateOf<LogLevel?>(null) }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isChinese) "📋 运行日志" else "📋 Runtime Log",
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = logFilter == null,
                        onClick = { logFilter = null },
                        label = { Text("ALL", fontSize = 10.sp) },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    FilterChip(
                        selected = logFilter == LogLevel.ERROR,
                        onClick = { logFilter = LogLevel.ERROR },
                        label = { Text("ERROR", fontSize = 10.sp, color = Color.Red) },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    FilterChip(
                        selected = logFilter == LogLevel.WARN,
                        onClick = { logFilter = LogLevel.WARN },
                        label = { Text("WARN", fontSize = 10.sp) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 日志列表
                val logs = remember { derivedStateOf {
                    val all = AppLogger.getAll()
                    if (logFilter == null) all else all.filter { it.level == logFilter }
                } }

                Surface(
                    color = Color(0xFF0D0D0D),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                ) {
                    val scrollState = rememberScrollState()
                    LaunchedEffect(logs.value.size) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(6.dp)
                    ) {
                        if (logs.value.isEmpty()) {
                            Text(
                                if (isChinese) "暂无日志" else "No logs",
                                fontSize = 11.sp, color = Color(0xFF666666),
                                modifier = Modifier.padding(4.dp)
                            )
                        } else {
                            logs.value.forEach { entry ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(entry.formattedTime, fontSize = 8.sp,
                                        color = Color(0xFF555555), fontFamily = FontFamily.Monospace)
                                    Text(" ${entry.level.tag} ", fontSize = 8.sp,
                                        color = when (entry.level) {
                                            LogLevel.ERROR -> Color.Red
                                            LogLevel.WARN -> Color(0xFFFFA726)
                                            LogLevel.INFO -> Color(0xFF4CAF50)
                                            LogLevel.DEBUG -> Color(0xFF888888)
                                        },
                                        fontFamily = FontFamily.Monospace)
                                    Text("${entry.thread.takeLast(12).padStart(12)} ", fontSize = 7.sp,
                                        color = Color(0xFF555555), fontFamily = FontFamily.Monospace)
                                    Text("/${entry.tag}: ", fontSize = 8.sp,
                                        color = Color(0xFF80CBC4), fontFamily = FontFamily.Monospace)
                                    Text(entry.message, fontSize = 8.sp,
                                        color = Color(0xFFCCCCCC), fontFamily = FontFamily.Monospace,
                                        maxLines = 1)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 按钮行
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { AppLogger.clear() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isChinese) "清空日志" else "Clear", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            AppLogger.i("Dev", "用户手动刷新日志")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isChinese) "刷新" else "Refresh", fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ============================================================
        // ADB 调试接口
        // ADB Debugging Interface
        // ============================================================
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isChinese) "🔌 ADB 调试" else "🔌 ADB Debug",
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))

                val adbFilter = "MaidMic"
                val pkgName = "aoeck.dwyai.com"

                // 设备信息
                InfoRow("型号", "${android.os.Build.MODEL}")
                InfoRow("Android", "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                InfoRow("包名", pkgName)

                Spacer(modifier = Modifier.height(8.dp))

                // ADB 命令
                Text(
                    if (isChinese) "常用 ADB 命令 (用于终端 / PC)：" else "Common ADB commands:",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))

                AdbCommand("adb logcat -s MaidMic/$adbFilter:* *:S")
                AdbCommand("adb shell dumpsys package $pkgName")
                AdbCommand("adb shell am start -n $pkgName/aoeck.dwyai.com.MainActivity")
                AdbCommand("adb shell am force-stop $pkgName")

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    if (isChinese)
                        "提示: 连接 PC 后在终端执行上述命令追踪日志\n" +
                        "或使用 \"adb logcat -c\" 先清空旧日志"
                    else
                        "Tip: Run these commands on your PC after connecting via ADB.\n" +
                        "Use 'adb logcat -c' to clear old logs first.",
                    fontSize = 10.sp, color = Color(0xFF666666)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 引擎健康状态
                val health = NativeAudioProcessor.getHealth()
                val healthText = when (health) {
                    aoeck.dwyai.com.EngineHealth.OK -> "✓ JNI 引擎正常"
                    aoeck.dwyai.com.EngineHealth.FALLBACK -> "⚠ Kotlin 降级模式"
                    aoeck.dwyai.com.EngineHealth.BROKEN -> "✗ 引擎不可用"
                }
                InfoRow("引擎状态", healthText)

                Spacer(modifier = Modifier.height(8.dp))

                // 引擎自检按钮
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val ok = NativeAudioProcessor.selfTest()
                            val msg = if (ok) "✓ 自检通过" else "✗ 自检失败"
                            AppLogger.i("Dev", msg)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("引擎自检", fontSize = 12.sp) }

                    OutlinedButton(
                        onClick = {
                            NativeAudioProcessor.resetEngine()
                            NativeAudioProcessor.ensureLoaded()
                            AppLogger.i("Dev", "引擎已重置并重新加载")
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("重置引擎", fontSize = 12.sp) }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 测试日志按钮（方便生成日志确认系统工作）
                OutlinedButton(
                    onClick = {
                        AppLogger.i("ADB", "ADB 调试接口 - 手动测试日志")
                        AppLogger.d("ADB", "设备: ${android.os.Build.MODEL}")
                        AppLogger.w("ADB", "这是一个 WARN 级别测试")
                        AppLogger.e("ADB", "这是一个 ERROR 级别测试（模拟）")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isChinese) "生成测试日志" else "Generate test logs", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
    
    // 免责声明弹窗
    if (showDisclaimer) {
        DeveloperDisclaimerDialog(
            isChinese = isChinese,
            onConfirmed = {
                showDisclaimer = false
                // UGC 插件尚未实现：确认免责声明后也不启用，
                // 强制保持关闭并弹窗提示用户去看其他功能
                onUgcToggle(false)
                showNotReadyDialog = true
            },
            onDismiss = {
                showDisclaimer = false
                // 用户取消了，保持关闭状态
            }
        )
    }

    // UGC 功能未完成提示弹窗
    if (showNotReadyDialog) {
        AlertDialog(
            onDismissRequest = { showNotReadyDialog = false },
            title = {
                Text(
                    text = if (isChinese) "还没做好" else "Not ready yet",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (isChinese)
                        "还没做好这个功能，去看看其他的？"
                    else
                        "This feature isn't ready yet. Check out the others?",
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showNotReadyDialog = false }) {
                    Text(if (isChinese) "好的" else "OK")
                }
            }
        )
    }
}

// ============================================================
// 信息行组件（用于 ADB 调试卡）
// ============================================================
@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", fontSize = 12.sp, color = Color(0xFF888888), modifier = Modifier.width(60.dp))
        Text(value, fontSize = 12.sp, color = Color(0xFF80CBC4))
    }
}

// ============================================================
// ADB 命令展示组件（带等宽字体）
// ============================================================
@Composable
fun AdbCommand(command: String) {
    Surface(
        color = Color(0xFF0D0D0D),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Text(
            command,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFCE93D8),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, isOk: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}
