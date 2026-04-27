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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 用户必须逐字输入的声明文本
// The exact text user must type to acknowledge
const val DISCLAIMER_TEXT = "我已阅读并且已知可能会下载到恶意插件会带来的后果"
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
    
    val disclaimerText = if (isChinese) DISCLAIMER_TEXT else DISCLAIMER_TEXT_EN
    
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
                // 风险说明
                // Risk explanation
                Text(
                    text = if (isChinese)
                        "启用 UGC 插件后，您可以安装由第三方开发者编写的插件。"
                        + "这些插件可能包含恶意代码，可能导致：\n\n"
                        + "• 个人隐私数据泄露\n"
                        + "• 设备性能下降或异常\n"
                        + "• 音频数据被截取或篡改\n"
                        + "• 设备稳定性问题\n\n"
                        + "MaidMic 团队不对任何因使用第三方插件导致的损失负责。\n\n"
                        + "请在下方输入以下声明以确认："
                    else
                        "Enabling UGC plugins allows installation of plugins written by third-party developers. "
                        + "These plugins may contain malicious code that could lead to:\n\n"
                        + "• Personal data leakage\n"
                        + "• Device performance degradation\n"
                        + "• Audio data interception or tampering\n"
                        + "• Device instability\n\n"
                        + "The MaidMic team is not responsible for any losses caused by third-party plugins.\n\n"
                        + "Please type the following statement to confirm:",
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 需要输入的声明文本（等宽字体，清晰展示）
                // The text that needs to be typed (monospace for clarity)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "\"$disclaimerText\"",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 输入框
                // Input field
                OutlinedTextField(
                    value = typedText,
                    onValueChange = {
                        typedText = it
                        hasError = false  // 用户重新输入时清除错误状态
                    },
                    label = { Text(if (isChinese) "在此输入上述声明" else "Type the statement above") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = hasError,
                    supportingText = if (hasError) {
                        { Text(if (isChinese) "输入内容与声明不匹配，请重试" else "Input does not match, please try again") }
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
                            if (typedText.trim() == disclaimerText) {
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
                    if (typedText.trim() == disclaimerText) {
                        onConfirmed()
                    } else {
                        hasError = true
                    }
                }
            ) {
                Text(if (isChinese) "确认启用" else "Enable")
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
 * 注意：这个页面默认隐藏，用户需要在"关于"页面连续点击版本号 7 次才会出现。
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
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 标题
        Text(
            text = if (isChinese) "⚙ 开发者选项" else "⚙ Developer Options",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        
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
                                "允许安装第三方编写的插件（Lua/WASM）。可能存在安全风险。"
                            else
                                "Allow third-party plugins (Lua/WASM). May pose security risks.",
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
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // ============================================================
        // 其他开发者选项预留
        // Other developer options placeholder
        // ============================================================
        Text(
            text = if (isChinese) "更多开发者功能即将推出" else "More developer features coming soon",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
    
    // 免责声明弹窗
    if (showDisclaimer) {
        DeveloperDisclaimerDialog(
            isChinese = isChinese,
            onConfirmed = {
                showDisclaimer = false
                onUgcToggle(true)
            },
            onDismiss = {
                showDisclaimer = false
                // 用户取消了，保持关闭状态
            }
        )
    }
}
