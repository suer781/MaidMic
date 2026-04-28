// MainActivity.kt — MaidMic 主界面
// ============================================================
// 主 Activity：引导页 → EQ 主控台 → 底部导航 + 设置页

package aoeck.dwyai.com

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import aoeck.dwyai.com.ui.editor.ModuleChainEditor
import aoeck.dwyai.com.ui.editor.PipelineNode
import aoeck.dwyai.com.ui.plugins.PluginMarketPage
import aoeck.dwyai.com.ui.settings.developer.DeveloperSettingsPage

// ============================================================
// 导航项
// ============================================================

sealed class NavItem(val label: String, val icon: ImageVector) {
    object Basic : NavItem("基础", Icons.Default.Home)
    object Editor : NavItem("模块链", Icons.Default.Tune)
    object Market : NavItem("插件市场", Icons.Default.Store)
    object About : NavItem("关于", Icons.Default.Info)
}

private const val PREFS_NAME = "maidmic_prefs"
private const val KEY_ONBOARDING_DONE = "onboarding_done"
private const val KEY_DEV_MODE = "dev_mode"
private const val KEY_UGC_ENABLED = "ugc_enabled"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaidMicTheme {
                MaidMicMain(context = this@MainActivity)
            }
        }
    }
}

// ============================================================
// 主题
// ============================================================

@Composable
fun MaidMicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFBB86FC),
            secondary = Color(0xFF03DAC6),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
        ),
        content = content
    )
}

// ============================================================
// 主界面
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaidMicMain(context: Context) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var showOnboarding by remember { mutableStateOf(!prefs.getBoolean(KEY_ONBOARDING_DONE, false)) }
    var currentNav: NavItem by remember { mutableStateOf(NavItem.Basic) }
    var pipelineNodes by remember { mutableStateOf(listOf<PipelineNode>()) }
    var isDagMode by remember { mutableStateOf(false) }
    var isUgcEnabled by remember { mutableStateOf(prefs.getBoolean(KEY_UGC_ENABLED, false)) }
    var showDeveloperSettings by remember { mutableStateOf(false) }
    var devModeEnabled by remember { mutableStateOf(prefs.getBoolean(KEY_DEV_MODE, false)) }
    var showSettings by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }

    // 保存 UGC 状态
    LaunchedEffect(isUgcEnabled) {
        prefs.edit().putBoolean(KEY_UGC_ENABLED, isUgcEnabled).apply()
    }

    if (showOnboarding) {
        OnboardingPage(
            context = context,
            onDone = {
                prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
                showOnboarding = false
            }
        )
        return
    }

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

    // 设置页面
    if (showSettings) {
        SettingsPage(
            isUgcEnabled = isUgcEnabled,
            onUgcToggle = { isUgcEnabled = it },
            showEditor = showEditor,
            onShowEditor = { showEditor = it },
            onBack = { showSettings = false },
            onOpenDeveloperSettings = {
                showSettings = false
                showDeveloperSettings = true
                prefs.edit().putBoolean(KEY_DEV_MODE, true).apply()
                devModeEnabled = true
            }
        )
        return
    }

    // 模块链编辑器（作为全屏页面打开）
    if (showEditor) {
        ModuleChainEditor(
            isDagMode = isDagMode,
            nodes = pipelineNodes,
            onAddModule = { },
            onRemoveModule = { id -> pipelineNodes = pipelineNodes.filter { it.nodeId != id } },
            onReorderModule = { _, _ -> },
            onToggleBypass = { id ->
                        pipelineNodes = pipelineNodes.map {
                            if (it.nodeId == id) it.copy(bypass = !it.bypass) else it
                        }
                    },
                    onParamChange = { _, _, _ -> }
                )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navItems = buildList {
                    add(NavItem.Basic)
                    if (devModeEnabled) {
                        add(NavItem.Editor)
                        add(NavItem.Market)
                    }
                    add(NavItem.About)
                }
                navItems.forEach { item ->
                    NavigationBarItem(
                        selected = currentNav == item,
                        onClick = { currentNav = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (currentNav) {
                NavItem.Basic -> EqPage(context = context)
                NavItem.Editor -> ModuleChainEditor(
                    isDagMode = isDagMode,
                    nodes = pipelineNodes,
                    onAddModule = { },
                    onRemoveModule = { id -> pipelineNodes = pipelineNodes.filter { it.nodeId != id } },
                    onReorderModule = { _, _ -> },
                    onToggleBypass = { id ->
                        pipelineNodes = pipelineNodes.map {
                            if (it.nodeId == id) it.copy(bypass = !it.bypass) else it
                        }
                    },
                    onParamChange = { _, _, _ -> }
                )
                NavItem.Market -> PluginMarketPage(
                    isUgcEnabled = isUgcEnabled,
                    onInstall = { },
                    onOpenDeveloperSettings = { showDeveloperSettings = true }
                )
                NavItem.About -> AboutPage(
                    context = context,
                    onOpenDeveloperSettings = {
                        showDeveloperSettings = true
                        prefs.edit().putBoolean(KEY_DEV_MODE, true).apply()
                        devModeEnabled = true
                    },
                    onOpenSettings = { showSettings = true }
                )
            }
        }
    }
}

// ============================================================
// 引导页 — 自动请求权限 + 模式选择
// ============================================================

@Composable
fun OnboardingPage(context: Context, onDone: () -> Unit) {
    // 自动请求：录音 + 通知
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val hasNotif = if (Build.VERSION.SDK_INT >= 33)
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    else true

    // 自动请求权限
    LaunchedEffect(Unit) {
        if (!hasMic) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        if (!hasNotif && Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF121212)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Default.Mic, null, modifier = Modifier.size(72.dp), tint = Color(0xFFBB86FC))
            Spacer(Modifier.height(12.dp))
            Text("MaidMic", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("虚拟麦克风 · Echio 引擎", fontSize = 13.sp, color = Color(0xFF999999))
            Spacer(Modifier.height(24.dp))

            // 权限状态
            PermissionRow("录音权限", if (hasMic) "✓ 已授予" else "请求中...", if (hasMic) Color.Green else Color(0xFFBB86FC))
            if (Build.VERSION.SDK_INT >= 33) {
                Spacer(Modifier.height(8.dp))
                PermissionRow("通知权限", if (hasNotif) "✓ 已授予" else "请求中...", if (hasNotif) Color.Green else Color(0xFFBB86FC))
            }

            Spacer(Modifier.height(24.dp))
            Text("选择虚拟麦克风方案", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFCCCCCC))
            Spacer(Modifier.height(12.dp))

            // 三种方案（可点击跳转配置）
            MicModeCard("方案A: Root AudioFlinger", "需 root 权限", Icons.Default.Lock) {
                Toast.makeText(context, "Root 模式需要 ROOT 权限", Toast.LENGTH_SHORT).show()
            }
            Spacer(Modifier.height(8.dp))
            MicModeCard("方案B: Shizuku AAudio", "推荐 · 非 root", Icons.Default.Security) {
                try { Shizuku.requestPermission(0); Toast.makeText(context, "请求 Shizuku 权限...", Toast.LENGTH_SHORT).show() }
                catch (_: Exception) { Toast.makeText(context, "Shizuku 未安装", Toast.LENGTH_SHORT).show() }
            }
            Spacer(Modifier.height(8.dp))
            MicModeCard("方案C: 无障碍服务", "最兼容", Icons.Default.Visibility) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }

            Spacer(Modifier.height(24.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBB86FC))) {
                Text("开始使用", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PermissionRow(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp, color = Color.White)
        Text(value, fontSize = 14.sp, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun MicModeCard(title: String, desc: String, icon: ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFFBB86FC), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
                Text(desc, fontSize = 12.sp, color = Color(0xFF888888))
            }
            Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF666666), modifier = Modifier.size(20.dp))
        }
    }
}

// ============================================================
// EQ 调节页面（保持不变）
// ============================================================

data class EqPreset(val name: String, val gain: Float, val bass: Float, val treble: Float, val reverb: Float, val pitch: Int)

private val eqPresets = listOf(
    EqPreset("原声", 0f, 0f, 0f, 0f, 0),
    EqPreset("增强", 3f, 2f, 3f, 0.1f, 0),
    EqPreset("低沉", 2f, 5f, -3f, 0.2f, -3),
    EqPreset("萝莉", 4f, -2f, 4f, 0.1f, 6),
    EqPreset("大叔", 2f, 3f, -2f, 0.3f, -5),
    EqPreset("混响", -1f, 1f, 1f, 0.8f, 0),
)

@Composable
fun EqPage(context: Context) {
    val prefs = context.getSharedPreferences("maidmic_eq", Context.MODE_PRIVATE)

    var selectedPreset by remember { mutableIntStateOf(prefs.getInt("preset", 0)) }
    var gain by remember { mutableFloatStateOf(prefs.getFloat("gain", 0f)) }
    var bass by remember { mutableFloatStateOf(prefs.getFloat("bass", 0f)) }
    var treble by remember { mutableFloatStateOf(prefs.getFloat("treble", 0f)) }
    var reverb by remember { mutableFloatStateOf(prefs.getFloat("reverb", 0f)) }
    var pitch by remember { mutableIntStateOf(prefs.getInt("pitch", 0)) }

    // 每次变化自动保存并更新引擎
    fun save() {
        prefs.edit()
            .putInt("preset", selectedPreset)
            .putFloat("gain", gain)
            .putFloat("bass", bass)
            .putFloat("treble", treble)
            .putFloat("reverb", reverb)
            .putInt("pitch", pitch)
            .apply()
        // 同步到引擎
        NativeAudioProcessor.ensureLoaded()
        NativeAudioProcessor.setEqParams(gain, bass, treble, reverb, pitch)
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("MaidMic", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("虚拟麦克风 · Echio 引擎", fontSize = 12.sp, color = Color(0xFF999999))
        Spacer(Modifier.height(16.dp))

        Text("预设", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            eqPresets.forEachIndexed { i, preset ->
                FilterChip(selected = i == selectedPreset, onClick = {
                    selectedPreset = i; gain = preset.gain; bass = preset.bass; treble = preset.treble; reverb = preset.reverb; pitch = preset.pitch; save()
                }, label = { Text(preset.name, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFBB86FC), selectedLabelColor = Color.Black))
            }
        }

        Spacer(Modifier.height(20.dp))
        EqSlider("音量增益", gain, -10f..10f) { gain = it; save() }
        EqSlider("低音", bass, -10f..10f) { bass = it; save() }
        EqSlider("高音", treble, -10f..10f) { treble = it; save() }
        EqSlider("混响", reverb, 0f..1f) { reverb = it; save() }
        Text("变调（半音）: $pitch", fontSize = 13.sp, color = Color(0xFFBBBBBB))
        Slider(value = pitch.toFloat(), onValueChange = { pitch = it.toInt(); save() }, valueRange = -12f..12f, steps = 23)

        Spacer(Modifier.height(12.dp))

        // 启动/停止按钮
        val isLoopbackRunning = remember { mutableStateOf(AudioLoopback.isActive()) }
        Button(
            onClick = {
                if (isLoopbackRunning.value) {
                    AudioLoopback.stop()
                    isLoopbackRunning.value = false
                    Toast.makeText(context, "音频处理已停止", Toast.LENGTH_SHORT).show()
                } else {
                    NativeAudioProcessor.ensureLoaded()
                    NativeAudioProcessor.setEqParams(gain, bass, treble, reverb, pitch)
                    AudioLoopback.start()
                    isLoopbackRunning.value = true
                    Toast.makeText(context, "音频处理已启动", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isLoopbackRunning.value) Color(0xFFB71C1C) else Color(0xFFBB86FC)
            )
        ) {
            Icon(
                if (isLoopbackRunning.value) Icons.Default.Stop else Icons.Default.PlayArrow,
                null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (isLoopbackRunning.value) "停止处理" else "开始处理",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        }
    }
}

@Composable
fun EqSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(label, fontSize = 13.sp, color = Color(0xFFBBBBBB))
    Slider(value = value, onValueChange = onChange, valueRange = range, steps = 19)
    Spacer(Modifier.height(6.dp))
}

// ============================================================
// 设置页面（权限、外观、模块链入口）
// ============================================================
// 设置页面（权限、外观、模块链入口）
@Composable
fun SettingsPage(
    isUgcEnabled: Boolean,
    onUgcToggle: (Boolean) -> Unit,
    showEditor: Boolean,
    onShowEditor: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenDeveloperSettings: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        // 顶栏
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
            Spacer(Modifier.width(8.dp))
            Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(16.dp))

        // 权限配置
        Text("音频方案", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
        Spacer(Modifier.height(8.dp))

        val context = LocalContext.current
        SettingsCard(icon = Icons.Default.Lock, title = "Root AudioFlinger", desc = "需要 ROOT 权限") {
            Toast.makeText(context, "Root 模式待实现", Toast.LENGTH_SHORT).show()
        }
        Spacer(Modifier.height(6.dp))
        SettingsCard(icon = Icons.Default.Security, title = "Shizuku AAudio", desc = "推荐 · 非 root") {
            try { Shizuku.requestPermission(0); Toast.makeText(context, "请求 Shizuku 权限...", Toast.LENGTH_SHORT).show() }
            catch (_: Exception) { Toast.makeText(context, "Shizuku 未安装", Toast.LENGTH_SHORT).show() }
        }
        Spacer(Modifier.height(6.dp))
        SettingsCard(icon = Icons.Default.Visibility, title = "无障碍服务", desc = "最兼容") {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        Spacer(Modifier.height(20.dp))

        // 模块链编辑器（仅在 UGC 插件开启时显示）
        if (isUgcEnabled) {
            Text("编辑器", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
            Spacer(Modifier.height(8.dp))
            Card(
                onClick = { onShowEditor(true) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, null, tint = Color(0xFFBB86FC))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("模块链编辑器", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
                        Text("编排 DSP 处理顺序", fontSize = 12.sp, color = Color(0xFF888888))
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF666666))
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // 开发者选项入口
        Text("其他", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
        Spacer(Modifier.height(8.dp))
        SettingsCard(icon = Icons.Default.DeveloperMode, title = "开发者选项", desc = "高级功能 · 谨慎操作") {
            onOpenDeveloperSettings()
        }
    }
}

@Composable
fun SettingsCard(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFFBB86FC), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
                Text(desc, fontSize = 12.sp, color = Color(0xFF888888))
            }
            Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF666666))
        }
    }
}

// ============================================================
// 关于页面（紧凑、不可滚动、右上角齿轮）
// ============================================================

@Composable
fun AboutPage(context: Context, onOpenDeveloperSettings: () -> Unit, onOpenSettings: () -> Unit) {
    var devClickCount by remember { mutableIntStateOf(0) }
    var shizukuStatus by remember { mutableStateOf(checkShizukuStatus()) }

    // 监听 Shizuku 授权状态变化
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            shizukuStatus = grantResult == PackageManager.PERMISSION_GRANTED
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // 右上角齿轮
        Box(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, "设置", tint = Color(0xFFBB86FC))
                }
            }
        }

        // 应用图标（11次进开发者）
        Box(
            modifier = Modifier.size(80.dp).clip(CircleShape).background(Color(0xFFBB86FC))
                .clickable {
                    devClickCount++
                    if (devClickCount >= 11) {
                        devClickCount = 0
                        onOpenDeveloperSettings()
                        Toast.makeText(context, "开发者模式已开启", Toast.LENGTH_SHORT).show()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Mic, null, modifier = Modifier.size(40.dp), tint = Color.Black)
        }

        Spacer(Modifier.height(8.dp))
        Text("MaidMic", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("v0.1.0-alpha", fontSize = 12.sp, color = Color(0xFF999999))
        Spacer(Modifier.height(4.dp))
        Text("作者: 我是真的会谢", fontSize = 13.sp, color = Color(0xFF888888))

        Spacer(Modifier.height(10.dp))

        // Shizuku 授权状态
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (shizukuStatus) Color(0xFF1B5E20) else Color(0xFF1E1E1E)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (shizukuStatus) Icons.Default.CheckCircle else Icons.Default.Info, null,
                    tint = if (shizukuStatus) Color.Green else Color(0xFF888888),
                    modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (shizukuStatus) "Shizuku 已授权" else "Shizuku 未授权",
                    fontSize = 12.sp, color = if (shizukuStatus) Color.Green else Color(0xFF888888)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Text("开源 Android 虚拟麦克风\n搭载自研 Echio 变声引擎", fontSize = 13.sp, color = Color(0xFF666666), textAlign = TextAlign.Center, lineHeight = 18.sp)

        Spacer(Modifier.height(14.dp))

        // 社交链接
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SocialIcon("B站", "📺", "https://b23.tv/JvcdN4I", context)
            SocialIcon("抖音", "🎵", "https://v.douyin.com/cT8XUPBO", context)
            SocialIcon("GitHub", "💻", "https://github.com/suer781", context)
        }
    }
}

private fun checkShizukuStatus(): Boolean {
    return try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }
}

@Composable
fun SocialIcon(label: String, emoji: String, url: String, context: Context) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: Exception) { Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show() }
        }.padding(12.dp)
    ) {
        Text(emoji, fontSize = 28.sp)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, color = Color(0xFFBB86FC))
    }
}
