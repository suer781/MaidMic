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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import aoeck.dwyai.com.ui.editor.ModuleChainEditor
import aoeck.dwyai.com.ui.editor.PipelineNode
import aoeck.dwyai.com.ui.CreditsPage
import aoeck.dwyai.com.ui.settings.developer.DeveloperSettingsPage

// ============================================================
// 导航项
// ============================================================

sealed class NavItem(val label: String, val icon: ImageVector) {
    object Basic : NavItem("基础", Icons.Default.Home)
    object Editor : NavItem("模块链", Icons.Default.Tune)
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
// 主题 — 深色音频工作室风格
// ============================================================

private val MaidMicDarkColors = darkColorScheme(
    primary = Color(0xFFCE93D8),         // 柔和紫罗兰
    onPrimary = Color(0xFF1A0D2E),
    primaryContainer = Color(0xFF4A2561),
    onPrimaryContainer = Color(0xFFF3E5F5),
    secondary = Color(0xFF80CBC4),       // 柔和青色
    onSecondary = Color(0xFF00201E),
    secondaryContainer = Color(0xFF004D47),
    onSecondaryContainer = Color(0xFFA7F3EC),
    tertiary = Color(0xFFFFAB91),        // 暖橙（用于强调）
    onTertiary = Color(0xFF2D1509),
    background = Color(0xFF100F14),      // 更深背景
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),         // 表面色
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2A2930),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

@Composable
fun MaidMicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaidMicDarkColors,
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
    var showCredits by remember { mutableStateOf(false) }

    // 保存 UGC 状态
    LaunchedEffect(isUgcEnabled) {
        prefs.edit().putBoolean(KEY_UGC_ENABLED, isUgcEnabled).apply()
    }

    // 启动时恢复引擎设置
    LaunchedEffect(Unit) {
        NativeAudioProcessor.loadEngine(prefs)
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
            onBack = { showSettings = false },
            onOpenDeveloperSettings = {
                showSettings = false
                showDeveloperSettings = true
                prefs.edit().putBoolean(KEY_DEV_MODE, true).apply()
                devModeEnabled = true
            },
            onEngineChanged = { engine ->
                NativeAudioProcessor.setEngine(engine)
                NativeAudioProcessor.saveEngine(prefs)
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

    // 开源鸣谢页面
    if (showCredits) {
        CreditsPage(onBack = { showCredits = false })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navItems = buildList {
                    add(NavItem.Basic)
                    if (devModeEnabled) {
                        add(NavItem.Editor)
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
                NavItem.About -> AboutPage(
                    context = context,
                    onOpenDeveloperSettings = {
                        showDeveloperSettings = true
                        prefs.edit().putBoolean(KEY_DEV_MODE, true).apply()
                        devModeEnabled = true
                    },
                    onOpenSettings = { showSettings = true },
                    onOpenCredits = { showCredits = true }
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
            Icon(Icons.Default.Mic, null, modifier = Modifier.size(72.dp), tint = Color(0xFFCE93D8))
            Spacer(Modifier.height(12.dp))
            Text("MaidMic", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("虚拟麦克风 · Echio 引擎", fontSize = 13.sp, color = Color(0xFF999999))
            Spacer(Modifier.height(24.dp))

            // 权限状态
            PermissionRow("录音权限", if (hasMic) "✓ 已授予" else "请求中...", if (hasMic) Color.Green else Color(0xFFCE93D8))
            if (Build.VERSION.SDK_INT >= 33) {
                Spacer(Modifier.height(8.dp))
                PermissionRow("通知权限", if (hasNotif) "✓ 已授予" else "请求中...", if (hasNotif) Color.Green else Color(0xFFCE93D8))
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
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCE93D8))) {
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
            Icon(icon, null, tint = Color(0xFFCE93D8), modifier = Modifier.size(24.dp))
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
    val appPrefs = context.getSharedPreferences("maidmic_prefs", Context.MODE_PRIVATE)

    var selectedPreset by remember { mutableIntStateOf(prefs.getInt("preset", 0)) }
    var gain by remember { mutableFloatStateOf(prefs.getFloat("gain", 0f)) }
    var bass by remember { mutableFloatStateOf(prefs.getFloat("bass", 0f)) }
    var treble by remember { mutableFloatStateOf(prefs.getFloat("treble", 0f)) }
    var reverb by remember { mutableFloatStateOf(prefs.getFloat("reverb", 0f)) }
    var pitch by remember { mutableIntStateOf(prefs.getInt("pitch", 0)) }

    // 引擎选择（同步到 app prefs）
    var currentEngine by remember { mutableStateOf(NativeAudioProcessor.getEngine()) }
    // 曲线预设选择
    var currentCurveIdx by remember { mutableIntStateOf(NativeAudioProcessor.currentCurvePreset) }

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
        NativeAudioProcessor.ensureLoaded()
        NativeAudioProcessor.setEqParams(gain, bass, treble, reverb, pitch)
    }

    fun applyEngine(engine: AudioEngine) {
        currentEngine = engine
        NativeAudioProcessor.setEngine(engine)
        NativeAudioProcessor.saveEngine(appPrefs)
    }

    fun applyCurve(index: Int) {
        currentCurveIdx = index
        NativeAudioProcessor.setCurvePreset(index)
        appPrefs.edit().putInt("curve_preset", index).apply()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 标题
        Text("MaidMic", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(currentEngine.displayName, fontSize = 12.sp, color = Color(0xFFCE93D8))
            Spacer(Modifier.width(6.dp))
            Text(currentEngine.description, fontSize = 11.sp, color = Color(0xFF666666))
        }
        Spacer(Modifier.height(12.dp))

        // 引擎选择器（紧凑横条）
        Text("音频引擎", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AudioEngine.entries.forEach { engine ->
                FilterChip(
                    selected = currentEngine == engine,
                    onClick = { applyEngine(engine) },
                    label = { Text(engine.displayName, fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFCE93D8),
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ============================================================
        // 根据引擎模式显示不同控件
        // ============================================================
        when (currentEngine) {
            AudioEngine.PASSTHROUGH -> {
                // 直通模式：什么也不显示
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.AutoMirrored.Filled.Forward, null, modifier = Modifier.size(48.dp), tint = Color(0xFF444444))
                        Spacer(Modifier.height(12.dp))
                        Text("直通模式 — 音频不经处理直接透传", fontSize = 14.sp, color = Color(0xFF666666))
                        Text("可在设置页切换其他引擎", fontSize = 12.sp, color = Color(0xFF444444))
                    }
                }
            }

            AudioEngine.ECHIO_EQ -> {
                // Echio 均衡：现有的 EQ 控件
                Text("预设", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    eqPresets.forEachIndexed { i, preset ->
                        FilterChip(selected = i == selectedPreset, onClick = {
                            selectedPreset = i; gain = preset.gain; bass = preset.bass; treble = preset.treble; reverb = preset.reverb; pitch = preset.pitch; save()
                        }, label = { Text(preset.name, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFCE93D8), selectedLabelColor = Color.Black))
                    }
                }
                Spacer(Modifier.height(20.dp))
                EqSlider("音量增益", gain, -10f..10f) { gain = it; save() }
                EqSlider("低音", bass, -10f..10f) { bass = it; save() }
                EqSlider("高音", treble, -10f..10f) { treble = it; save() }
                EqSlider("混响", reverb, 0f..1f) { reverb = it; save() }
                Text("变调（半音）: $pitch", fontSize = 13.sp, color = Color(0xFFBBBBBB))
                Slider(value = pitch.toFloat(), onValueChange = { pitch = it.toInt(); save() }, valueRange = -12f..12f, steps = 23)
            }

            AudioEngine.FREQ_CURVE -> {
                // 频响曲线：曲线预设选择
                Text("曲线预设", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CurvePresets.ALL.forEachIndexed { index, preset ->
                        val isSel = index == currentCurveIdx
                        Card(
                            onClick = { applyCurve(index) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSel) Color(0xFF2A1A2E) else Color(0xFF1E1E1E)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isSel) {
                                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFFCE93D8), modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.name, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                        color = if (isSel) Color(0xFFCE93D8) else Color.White)
                                    Text(preset.description, fontSize = 11.sp, color = Color(0xFF888888))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 曲线引擎也复用混响和变调
                Text("附加效果", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
                Spacer(Modifier.height(6.dp))
                EqSlider("混响", reverb, 0f..1f) { reverb = it
                    NativeAudioProcessor.ensureLoaded()
                    NativeAudioProcessor.setReverbPitch(reverb, pitch)
                    prefs.edit().putFloat("reverb", reverb).apply()
                }
                Text("变调（半音）: $pitch", fontSize = 13.sp, color = Color(0xFFBBBBBB))
                Slider(value = pitch.toFloat(), onValueChange = { pitch = it.toInt()
                    NativeAudioProcessor.ensureLoaded()
                    NativeAudioProcessor.setReverbPitch(reverb, pitch)
                    prefs.edit().putInt("pitch", pitch).apply()
                }, valueRange = -12f..12f, steps = 23)
            }
        }

        Spacer(Modifier.height(12.dp))

        // 底部提示
        Text(
            "授权和方案配置 → 关于页右上角设置",
            fontSize = 11.sp, color = Color(0xFF555555),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun EqSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text("${label}: ${"%.1f".format(value)}", fontSize = 13.sp, color = Color(0xFFBBBBBB))
    Slider(value = value, onValueChange = onChange, valueRange = range, steps = 19)
    Spacer(Modifier.height(6.dp))
}

// ============================================================
// 设置页面（权限、外观、模块链入口）
// ============================================================
// 设置页面（权限、外观、模块链入口）
@Composable
fun SettingsPage(
    onBack: () -> Unit,
    onOpenDeveloperSettings: () -> Unit = {},
    onEngineChanged: (AudioEngine) -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("maidmic_prefs", Context.MODE_PRIVATE)
    // 当前引擎
    var currentEngine by remember { mutableStateOf(NativeAudioProcessor.getEngine()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 顶栏
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Spacer(Modifier.width(8.dp))
            Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(16.dp))

        // 权限配置
        Text("音频方案", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
        Spacer(Modifier.height(8.dp))

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

        // 引擎选择
        Text("音频引擎", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioEngine.entries.forEach { engine ->
                FilterChip(
                    selected = currentEngine == engine,
                    onClick = {
                        currentEngine = engine
                        onEngineChanged(engine)
                    },
                    label = { Text(engine.displayName, fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFCE93D8),
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            currentEngine.description,
            fontSize = 11.sp,
            color = Color(0xFF666666)
        )

        // 频响曲线预设选择（仅 FREQ_CURVE 引擎时显示）
        if (currentEngine == AudioEngine.FREQ_CURVE) {
            Spacer(Modifier.height(12.dp))
            Text("曲线预设", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
            Spacer(Modifier.height(6.dp))
            // 预设卡片列表
            val presets = CurvePresets.ALL
            presets.forEachIndexed { index, preset ->
                Card(
                    onClick = {
                        NativeAudioProcessor.setCurvePreset(index)
                        // 同步保存
                        prefs.edit().putInt("curve_preset", index).apply()
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (index == NativeAudioProcessor.currentCurvePreset)
                            Color(0xFF2A1A2E) else Color(0xFF1E1E1E)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 选中指示器
                        if (index == NativeAudioProcessor.currentCurvePreset) {
                            Icon(
                                Icons.Default.CheckCircle,
                                null,
                                tint = Color(0xFFCE93D8),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                preset.name,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (index == NativeAudioProcessor.currentCurvePreset) Color(0xFFCE93D8) else Color.White
                            )
                            Text(
                                preset.description,
                                fontSize = 11.sp,
                                color = Color(0xFF888888)
                            )
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF555555), modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
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
            Icon(icon, null, tint = Color(0xFFCE93D8), modifier = Modifier.size(22.dp))
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
fun AboutPage(
    context: Context,
    onOpenDeveloperSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCredits: () -> Unit = {}
) {
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
                    Icon(Icons.Default.Settings, "设置", tint = Color(0xFFCE93D8))
                }
            }
        }

        // 应用图标（11次进开发者）
        Box(
            modifier = Modifier.size(80.dp).clip(CircleShape).background(Color(0xFFCE93D8))
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

        Spacer(Modifier.height(20.dp))

        // 开源鸣谢
        Text(
            text = "开源鸣谢",
            fontSize = 12.sp,
            color = Color(0xFFCE93D8),
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { onOpenCredits() }
        )
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
        Text(label, fontSize = 12.sp, color = Color(0xFFCE93D8))
    }
}
