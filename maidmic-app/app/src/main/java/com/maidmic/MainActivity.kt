// MainActivity.kt — MaidMic 主界面
// ============================================================
// 主 Activity，搭载 Jetpack Compose UI。
// 包含底部导航：主控台、模块链编辑器、插件市场、设置

package com.maidmic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import com.maidmic.ui.editor.ModuleChainEditor
import com.maidmic.ui.editor.PipelineNode
import com.maidmic.ui.plugins.PluginMarketPage
import com.maidmic.ui.settings.developer.DeveloperSettingsPage

// ============================================================
// 底部导航项
// Bottom navigation items
// ============================================================

sealed class NavItem(val label: String, val icon: ImageVector) {
    object Dashboard : NavItem("主控台", Icons.Default.Home)
    object Editor : NavItem("模块链", Icons.Default.Tune)
    object Market : NavItem("插件市场", Icons.Default.Store)
    object Settings : NavItem("设置", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    // 权限请求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled in onResume */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 请求录音权限（核心权限）
        requestAudioPermission()

        setContent {
            MaidMicTheme {
                MaidMicMain()
            }
        }
    }

    private fun requestAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED -> {
                // 已有权限
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                // 用户之前拒绝过，引导去设置
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

// ============================================================
// Compose UI 主题
// Compose UI theme
// ============================================================

@Composable
fun MaidMicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFFBB86FC),
            secondary = androidx.compose.ui.graphics.Color(0xFF03DAC6),
            background = androidx.compose.ui.graphics.Color(0xFF121212),
            surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
        ),
        content = content
    )
}

// ============================================================
// 主界面
// Main interface
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaidMicMain() {
    // 当前选中的导航项
    var currentNav by remember { mutableStateOf(NavItem.Dashboard) }
    
    // 管线中的模块列表（UI 状态）
    var pipelineNodes by remember { mutableStateOf(listOf<PipelineNode>()) }
    
    // 是否为 DAG 模式（从开发者设置读取）
    var isDagMode by remember { mutableStateOf(false) }
    
    // UGC 插件是否启用
    var isUgcEnabled by remember { mutableStateOf(false) }
    
    // 是否显示开发者选项
    var showDeveloperSettings by remember { mutableStateOf(false) }

    // ============================================================
    // 开发者选项页面
    // ============================================================
    if (showDeveloperSettings) {
        DeveloperSettingsPage(
            isChinese = true,
            isUgcEnabled = isUgcEnabled,
            onUgcToggle = { isUgcEnabled = it },
            currentEditorMode = if (isDagMode) "dag" else "simple",
            onEditorModeChange = { mode -> isDagMode = mode == "dag" },
            onBack = { showDeveloperSettings = false }
        )
        return
    }

    Scaffold(
        // 底部导航栏
        bottomBar = {
            NavigationBar {
                listOf(
                    NavItem.Dashboard,
                    NavItem.Editor,
                    NavItem.Market,
                    NavItem.Settings
                ).forEach { item ->
                    NavigationBarItem(
                        selected = currentNav == item,
                        onClick = { currentNav = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label, fontSize = androidx.compose.ui.unit.sp(11)) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (currentNav) {
                NavItem.Dashboard -> DashboardPage()
                NavItem.Editor -> ModuleChainEditor(
                    isDagMode = isDagMode,
                    nodes = pipelineNodes,
                    onAddModule = { /* TODO: 弹出模块选择对话框 */ },
                    onRemoveModule = { id -> pipelineNodes = pipelineNodes.filter { it.nodeId != id } },
                    onReorderModule = { from, to -> /* TODO: 交换模块顺序 */ },
                    onToggleBypass = { id ->
                        pipelineNodes = pipelineNodes.map {
                            if (it.nodeId == id) it.copy(bypass = !it.bypass) else it
                        }
                    },
                    onParamChange = { _, _, _ -> /* TODO: 更新参数 */ }
                )
                NavItem.Market -> PluginMarketPage(
                    isUgcEnabled = isUgcEnabled,
                    onInstall = { /* TODO: 安装插件 */ },
                    onOpenDeveloperSettings = { showDeveloperSettings = true }
                )
                NavItem.Settings -> SettingsPage(
                    onOpenDeveloperSettings = { showDeveloperSettings = true }
                )
            }
        }
    }
}

// ============================================================
// 主控台页面（占位）
// Dashboard page (placeholder)
// ============================================================

@Composable
fun DashboardPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            "MaidMic",
            style = MaterialTheme.typography.headlineLarge
        )
        Text(
            "虚拟麦克风 · Echio 引擎",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        // 三路虚拟麦克风状态卡片
        // Three virtual mic status cards
        listOf(
            "方案A: Root AudioFlinger" to "需 root",
            "方案B: Shizuku AAudio" to "推荐",
            "方案C: 无障碍服务" to "最兼容"
        ).forEach { (name, badge) ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(name)
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            badge,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            fontSize = androidx.compose.ui.unit.sp(12)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// 设置页面（占位）
// Settings page (placeholder)
// ============================================================

@Composable
fun SettingsPage(onOpenDeveloperSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        // 开发者选项入口（点击 7 次版本号弹出）
        var clickCount by remember { mutableStateOf(0) }
        
        Card(
            onClick = {
                clickCount++
                if (clickCount >= 7) {
                    clickCount = 0
                    onOpenDeveloperSettings()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Icon(Icons.Default.DeveloperMode, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("开发者选项", fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    Text("版本 0.1.0-alpha（点击 7 次进入）", 
                         fontSize = androidx.compose.ui.unit.sp(12),
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            "更多设置项即将推出",
            fontSize = androidx.compose.ui.unit.sp(12),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
