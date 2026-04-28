// MainActivity.kt — MaidMic 主界面
// ============================================================
// 主 Activity，搭载 Jetpack Compose UI。
// 包含：权限引导 → EQ 主控台 → 底部导航（基础/关于 + 开发者专属）

package aoeck.dwyai.com

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
// 底部导航项
// ============================================================

sealed class NavItem(val label: String, val icon: ImageVector) {
    object Basic : NavItem("基础", Icons.Default.Home)
    object Editor : NavItem("模块链", Icons.Default.Tune)
    object Market : NavItem("插件市场", Icons.Default.Store)
    object About : NavItem("关于", Icons.Default.Info)
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "录音权限已授予", Toast.LENGTH_SHORT).show()
        }
    }

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
    val prefs = context.getSharedPreferences("maidmic_prefs", Context.MODE_PRIVATE)

    // 首次启动引导
    var showOnboarding by remember { mutableStateOf(!prefs.getBoolean("onboarding_done", false)) }

    // 当前导航项
    var currentNav: NavItem by remember { mutableStateOf(NavItem.Basic) }

    // 管线模块列表
    var pipelineNodes by remember { mutableStateOf(listOf<PipelineNode>()) }
    var isDagMode by remember { mutableStateOf(false) }
    var isUgcEnabled by remember { mutableStateOf(false) }
    var showDeveloperSettings by remember { mutableStateOf(false) }
    var devModeEnabled by remember { mutableStateOf(prefs.getBoolean("dev_mode", false)) }

    // ============================================================
    // 引导页
    // ============================================================
    if (showOnboarding) {
        OnboardingPage(
            context = context,
            onDone = {
                prefs.edit().putBoolean("onboarding_done", true).apply()
                showOnboarding = false
            }
        )
        return
    }

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
                        prefs.edit().putBoolean("dev_mode", true).apply()
                        devModeEnabled = true
                    }
                )
            }
        }
    }
}

// ============================================================
// 引导页 — 权限配置
// ============================================================

@Composable
fun OnboardingPage(context: Context, onDone: () -> Unit) {
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) Toast.makeText(context, "录音权限已授予", Toast.LENGTH_SHORT).show()
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val hasNotification = if (Build.VERSION.SDK_INT >= 33)
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    else true

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = Color(0xFFBB86FC)
            )
            Spacer(Modifier.height(16.dp))
            Text("欢迎使用 MaidMic", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(
                "使用前请授予必要权限",
                fontSize = 14.sp,
                color = Color(0xFF999999),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))

            // 麦克风权限
            PermissionCard(
                icon = Icons.Default.Mic,
                title = "录音权限",
                desc = "捕获麦克风音频输入",
                granted = hasMic,
                onClick = {
                    if (!hasMic) {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        Toast.makeText(context, "权限已授予", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            Spacer(Modifier.height(12.dp))

            // 通知权限
            if (Build.VERSION.SDK_INT >= 33) {
                PermissionCard(
                    icon = Icons.Default.Notifications,
                    title = "通知权限",
                    desc = "后台音频处理通知",
                    granted = hasNotification,
                    onClick = {
                        if (!hasNotification) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            Toast.makeText(context, "权限已授予", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
            }

            // Shizuku 提示
            PermissionCard(
                icon = Icons.Default.Security,
                title = "Shizuku（可选）",
                desc = "方案B需要·建议安装",
                granted = false,
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                    } catch (_: Exception) { }
                }
            )
            Spacer(Modifier.height(32.dp))

            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBB86FC))
            ) {
                Text("开始使用", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PermissionCard(icon: ImageVector, title: String, desc: String, granted: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) Color(0xFF1B5E20) else Color(0xFF1E1E1E)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = if (granted) Color.Green else Color.White)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, color = Color.White, fontSize = 14.sp)
                Text(desc, fontSize = 12.sp, color = Color(0xFF999999))
            }
            Text(
                if (granted) "✓" else "→",
                color = if (granted) Color.Green else Color(0xFFBB86FC),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ============================================================
// EQ 调节页面
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
    var selectedPreset by remember { mutableStateOf(0) }
    var gain by remember { mutableFloatStateOf(eqPresets[0].gain) }
    var bass by remember { mutableFloatStateOf(eqPresets[0].bass) }
    var treble by remember { mutableFloatStateOf(eqPresets[0].treble) }
    var reverb by remember { mutableFloatStateOf(eqPresets[0].reverb) }
    var pitch by remember { mutableIntStateOf(eqPresets[0].pitch) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        // 标题
        Text("MaidMic", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("虚拟麦克风 · Echio 引擎", fontSize = 12.sp, color = Color(0xFF999999))
        Spacer(Modifier.height(16.dp))

        // 预设选择
        Text("预设", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            eqPresets.forEachIndexed { i, preset ->
                FilterChip(
                    selected = i == selectedPreset,
                    onClick = {
                        selectedPreset = i
                        gain = preset.gain
                        bass = preset.bass
                        treble = preset.treble
                        reverb = preset.reverb
                        pitch = preset.pitch
                    },
                    label = { Text(preset.name, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFBB86FC),
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // 音量增益
        Text("音量增益", fontSize = 13.sp, color = Color(0xFFBBBBBB))
        Slider(value = gain, onValueChange = { gain = it }, valueRange = -10f..10f, steps = 19)
        Spacer(Modifier.height(8.dp))

        // 低音
        Text("低音", fontSize = 13.sp, color = Color(0xFFBBBBBB))
        Slider(value = bass, onValueChange = { bass = it }, valueRange = -10f..10f, steps = 19)
        Spacer(Modifier.height(8.dp))

        // 高音
        Text("高音", fontSize = 13.sp, color = Color(0xFFBBBBBB))
        Slider(value = treble, onValueChange = { treble = it }, valueRange = -10f..10f, steps = 19)
        Spacer(Modifier.height(8.dp))

        // 混响
        Text("混响", fontSize = 13.sp, color = Color(0xFFBBBBBB))
        Slider(value = reverb, onValueChange = { reverb = it }, valueRange = 0f..1f, steps = 9)
        Spacer(Modifier.height(8.dp))

        // 变调
        Text("变调（半音）: $pitch", fontSize = 13.sp, color = Color(0xFFBBBBBB))
        Slider(value = pitch.toFloat(), onValueChange = { pitch = it.toInt() }, valueRange = -12f..12f, steps = 23)

        Spacer(Modifier.height(16.dp))

        // 模式选择卡片（轻量）
        val micMethods = listOf(
            Triple("方案A: Root AudioFlinger", "需 root", "root"),
            Triple("方案B: Shizuku AAudio", "推荐", "shizuku"),
            Triple("方案C: 无障碍服务", "最兼容", "accessibility")
        )
        micMethods.forEach { (name, badge, mode) ->
            Card(
                onClick = {
                    when (mode) {
                        "shizuku" -> {
                            try {
                                Shizuku.requestPermission(0)
                                Toast.makeText(context, "请求 Shizuku 权限...", Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {
                                Toast.makeText(context, "Shizuku 未安装", Toast.LENGTH_SHORT).show()
                            }
                        }
                        "root" -> Toast.makeText(context, "Root 模式需要 ROOT 权限", Toast.LENGTH_LONG).show()
                        "accessibility" -> {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF2A2A2A)) {
                        Text(badge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = Color(0xFFBB86FC))
                    }
                }
            }
        }
    }
}

// ============================================================
// 关于页面（紧凑、不可滚动）
// ============================================================

@Composable
fun AboutPage(context: Context, onOpenDeveloperSettings: () -> Unit) {
    var devClickCount by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 应用图标（点击11次进入开发者界面）
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Color(0xFFBB86FC))
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
            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(40.dp), tint = Color.Black)
        }

        Spacer(Modifier.height(8.dp))

        // 应用名称 + 版本
        Text("MaidMic", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("v0.1.0-alpha", fontSize = 12.sp, color = Color(0xFF999999))

        Spacer(Modifier.height(4.dp))

        // 作者
        Text(
            "作者: 我是真的会谢",
            fontSize = 13.sp,
            color = Color(0xFFBBBBBB)
        )

        Spacer(Modifier.height(12.dp))

        // 应用说明
        Text(
            "开源 Android 虚拟麦克风应用\n搭载自研 Echio 变声引擎",
            fontSize = 13.sp,
            color = Color(0xFF888888),
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )

        Spacer(Modifier.height(16.dp))

        // 社交链接（一行三个，紧凑）
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Bilibili
            SocialIcon(
                label = "B站",
                emoji = "📺",
                url = "https://b23.tv/JvcdN4I",
                context = context
            )
            // 抖音
            SocialIcon(
                label = "抖音",
                emoji = "🎵",
                url = "https://v.douyin.com/cT8XUPBO",
                context = context
            )
            // GitHub
            SocialIcon(
                label = "GitHub",
                emoji = "💻",
                url = "https://github.com/suer781",
                context = context
            )
        }
    }
}

@Composable
fun SocialIcon(label: String, emoji: String, url: String, context: Context) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
                }
            }
            .padding(12.dp)
    ) {
        Text(emoji, fontSize = 28.sp)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, color = Color(0xFFBB86FC))
    }
}
