// MainActivity.kt — MaidMic 主界面
// ============================================================
// 主 Activity：引导页 → EQ 主控台 → 底部导航 + 设置页

package aoeck.dwyai.com

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import aoeck.dwyai.com.bridge.root.RootMicBridge

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

    // ---------- 启动日志 ----------
    AppLogger.logDeviceInfo(context)
    AppLogger.i("Main", "MaidMic 启动")

    val hasMicPerm = ContextCompat.checkSelfPermission(context,
        Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    AppLogger.i("Main", "录音权限: ${if (hasMicPerm) "已授权" else "未授权"}")
    AppLogger.i("Main", "引擎: ${NativeAudioProcessor.getEngine().key}")
    // ----------------------------

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

    // 通知权限自动申请（Android 13+）
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        AppLogger.i("Main", "通知权限申请结果: ${if (granted) "已授权" else "已拒绝"}")
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            val hasNotif = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotif) {
                AppLogger.i("Main", "尝试申请通知权限")
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // 保存 UGC 状态
    LaunchedEffect(isUgcEnabled) {
        prefs.edit().putBoolean(KEY_UGC_ENABLED, isUgcEnabled).apply()
        AppLogger.i("UGC", "UGC 插件: ${if (isUgcEnabled) "启用" else "禁用"}")
    }

    // 全局 Shizuku 授权监听（持久化，跨页面有效）
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            val msg = if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku 已授权" else "Shizuku 授权被拒绝"
            AppLogger.i("Shizuku", msg)
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    // 启动时恢复引擎设置 + 应用当前预设参数到引擎
    LaunchedEffect(Unit) {
        NativeAudioProcessor.loadEngine(prefs)
        AppLogger.i("Engine", "引擎已加载: ${NativeAudioProcessor.getEngine().key}")
        // 确保 JNI 已加载
        NativeAudioProcessor.ensureLoaded()
        // 确保 DSP 参数已初始化（即使使用默认预设）
        val eqPrefs = context.getSharedPreferences("maidmic_eq", Context.MODE_PRIVATE)
        NativeAudioProcessor.setEqParams(
            eqPrefs.getFloat("gain", 0f),
            eqPrefs.getFloat("bass", 0f),
            eqPrefs.getFloat("treble", 0f),
            eqPrefs.getFloat("reverb", 0f),
            eqPrefs.getInt("pitch", 0),
            eqPrefs.getFloat("formant", 0f),
            eqPrefs.getFloat("distortion", 0f),
            eqPrefs.getFloat("echo_delay", 0f),
            eqPrefs.getFloat("echo_decay", 0f)
        )
        // 如果是 FREQ_CURVE 引擎，恢复曲线预设
        if (NativeAudioProcessor.getEngine() == AudioEngine.FREQ_CURVE) {
            val savedCurve = prefs.getInt("curve_preset", 0)
            NativeAudioProcessor.setCurvePreset(savedCurve)
            AppLogger.i("Engine", "频响曲线预设已恢复: index=$savedCurve")
        }
        AppLogger.i("Engine", "DSP参数已初始化到引擎")
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
                NavItem.Basic -> EqPage(context = context, onOpenSettings = { showSettings = true })
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
    // 当前权限状态（响应式 — 随权限回调更新）
    var hasMicState by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    ) }
    var hasNotifState by remember { mutableStateOf(
        if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true
    ) }
    val hasMic = hasMicState
    val hasNotif = hasNotifState

    // 权限请求（多个权限一次请求）
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true
        val notifGranted = if (Build.VERSION.SDK_INT >= 33)
            results[Manifest.permission.POST_NOTIFICATIONS] == true else true
        AppLogger.i("Onboarding", "权限结果: 麦克风=${if(micGranted)"✓" else "✗"} 通知=${if(notifGranted)"✓" else "✗"}")
        // 更新响应式状态，触发 UI 重绘
        hasMicState = micGranted ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        hasNotifState = notifGranted ||
            (Build.VERSION.SDK_INT < 33) ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    // Shizuku 授权监听
    var shizukuGranted by remember { mutableStateOf(false) }
    var shizukuChecked by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            shizukuGranted = grantResult == PackageManager.PERMISSION_GRANTED
            shizukuChecked = true
            AppLogger.i("Shizuku", "授权结果: ${if(shizukuGranted)"已授权" else "已拒绝"}")
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    // 自动请求权限（首次进入时）
    LaunchedEffect(Unit) {
        val needMic = !hasMic
        val needNotif = !hasNotif && Build.VERSION.SDK_INT >= 33
        if (needMic || needNotif) {
            AppLogger.i("Onboarding", "请求权限: 麦克风=$needMic 通知=$needNotif")
            permLauncher.launch(
                if (needNotif) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                else arrayOf(Manifest.permission.RECORD_AUDIO)
            )
        }
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

            // Root 状态检测（异步）
            var rootStatus by remember { mutableStateOf<RootMicBridge.RootStatus?>(null) }
            LaunchedEffect(Unit) {
                rootStatus = RootMicBridge(context).checkRoot()
                AppLogger.i("Onboarding", "Root检测: granted=${rootStatus?.granted} ver=${rootStatus?.magiskVer}")
            }

            // 方案A: Root（显示实际 root 状态）
            MicModeCard(
                "方案A: Root AudioFlinger",
                if (rootStatus == null) "检测中..."
                else if (rootStatus!!.granted) "✓ Root 已授权 (${rootStatus!!.magiskVer})"
                else "✗ 未检测到 root",
                if (rootStatus?.granted == true) Icons.Default.CheckCircle else Icons.Default.Lock
            ) {
                if (rootStatus?.granted == true) {
                    val bridge = RootMicBridge(context)
                    bridge.start()
                    Toast.makeText(context, "Root 模式已启动", Toast.LENGTH_SHORT).show()
                    AppLogger.i("Root", "启动 Root 音频环回")
                } else {
                    Toast.makeText(context, "本机无 root 权限", Toast.LENGTH_SHORT).show()
                }
            }
            Spacer(Modifier.height(8.dp))

            // 方案B: Shizuku（带授权状态反馈）
            MicModeCard(
                "方案B: Shizuku AAudio",
                if (shizukuChecked) {
                    if (shizukuGranted) "✓ 已授权" else "✗ 未授权"
                } else "推荐 · 非 root",
                Icons.Default.Security
            ) {
                try {
                    shizukuChecked = false
                    Shizuku.requestPermission(0)
                    Toast.makeText(context, "请求 Shizuku 权限...", Toast.LENGTH_SHORT).show()
                    AppLogger.i("Shizuku", "请求授权")
                } catch (_: Exception) {
                    Toast.makeText(context, "Shizuku 未安装", Toast.LENGTH_SHORT).show()
                    AppLogger.e("Shizuku", "Shizuku 未安装或不可用")
                }
            }
            Spacer(Modifier.height(8.dp))

            // 方案C: 无障碍服务
            MicModeCard("方案C: 无障碍服务", "最兼容 · 无需额外权限", Icons.Default.Visibility) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }

            Spacer(Modifier.height(24.dp))
            // "开始使用"按钮 — 必须有麦克风权限
            Button(
                onClick = {
                    val nowHasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (nowHasMic) {
                        AppLogger.i("Onboarding", "权限完备，进入主界面")
                        onDone()
                    } else {
                        AppLogger.w("Onboarding", "麦克风权限尚未授予，再次请求")
                        Toast.makeText(context, "请先授予录音权限", Toast.LENGTH_SHORT).show()
                        permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (hasMic) Color(0xFFCE93D8) else Color(0xFF555555)
                )
            ) {
                Text(if (hasMic) "开始使用" else "请先授予录音权限", color = Color.Black, fontWeight = FontWeight.Bold)
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
// EQ 调节页面 — HXAudio 风格
// ============================================================

data class EqPreset(val name: String, val gain: Float, val bass: Float, val treble: Float,
                      val reverb: Float, val pitch: Int,
                      val formant: Float = 0f, val distortion: Float = 0f,
                      val echoDelay: Float = 0f, val echoDecay: Float = 0f)

private val eqPresets = listOf(
    EqPreset("原声", 0f, 0f, 0f, 0f, 0, 0f, 0f, 0f, 0f),
    EqPreset("增强", 3f, 2f, 3f, 0.1f, 0, 0f, 0f, 0f, 0f),
    EqPreset("低沉", 2f, 5f, -3f, 0.2f, -3, 3f, 0f, 0f, 0f),
    EqPreset("萝莉", 4f, -2f, 4f, 0.1f, 6, -3f, 0f, 0f, 0f),
    EqPreset("大叔", 2f, 3f, -2f, 0.3f, -5, 4f, 0f, 0f, 0f),
    EqPreset("混响", -1f, 1f, 1f, 0.8f, 0, 0f, 0f, 0f, 0f),
    EqPreset("机器人", 3f, 0f, 0f, 0.2f, 1, 0f, 0.5f, 0f, 0f),
    EqPreset("恶魔", 2f, 4f, -4f, 0.3f, -6, 5f, 0.4f, 80f, 0.3f),
    EqPreset("花栗鼠", 6f, -4f, 5f, 0f, 10, -4f, 0f, 0f, 0f),
    EqPreset("幽灵", 0f, -2f, 2f, 0.7f, 3, 0f, 0f, 200f, 0.5f),
)

// 频段标签（用于曲线图 X 轴）
val bandLabels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

// ============================================================
// 设备检测
// ============================================================

fun detectAudioDevice(context: Context): String {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "手机扬声器"
    return when {
        am.isWiredHeadsetOn -> "🎧 有线耳机"
        am.isBluetoothA2dpOn || am.isBluetoothScoOn -> "🔵 蓝牙设备"
        else -> "🔊 手机扬声器"
    }
}

// ============================================================
// 主 EQ 页面（HXAudio 风格）
// ============================================================

@Composable
fun EqPage(context: Context, onOpenSettings: () -> Unit = {}) {
    val prefs = context.getSharedPreferences("maidmic_eq", Context.MODE_PRIVATE)
    val appPrefs = context.getSharedPreferences("maidmic_prefs", Context.MODE_PRIVATE)

    var selectedPreset by remember { mutableIntStateOf(prefs.getInt("preset", 0)) }
    var gain by remember { mutableFloatStateOf(prefs.getFloat("gain", 0f)) }
    var bass by remember { mutableFloatStateOf(prefs.getFloat("bass", 0f)) }
    var treble by remember { mutableFloatStateOf(prefs.getFloat("treble", 0f)) }
    var reverb by remember { mutableFloatStateOf(prefs.getFloat("reverb", 0f)) }
    var pitch by remember { mutableIntStateOf(prefs.getInt("pitch", 0)) }
    var formant by remember { mutableFloatStateOf(prefs.getFloat("formant", 0f)) }
    var distortion by remember { mutableFloatStateOf(prefs.getFloat("distortion", 0f)) }
    var echoDelay by remember { mutableFloatStateOf(prefs.getFloat("echo_delay", 0f)) }
    var echoDecay by remember { mutableFloatStateOf(prefs.getFloat("echo_decay", 0f)) }

    var currentEngine by remember { mutableStateOf(NativeAudioProcessor.getEngine()) }
    var currentCurveIdx by remember { mutableIntStateOf(NativeAudioProcessor.currentCurvePreset) }
    var bypass by remember { mutableStateOf(false) }
    var testState by remember { mutableStateOf(TestState.IDLE) }
    var testProgress by remember { mutableIntStateOf(0) }

    // 设备检测
    val deviceLabel = remember { mutableStateOf(detectAudioDevice(context)) }

    fun save() {
        prefs.edit()
            .putInt("preset", selectedPreset).putFloat("gain", gain)
            .putFloat("bass", bass).putFloat("treble", treble)
            .putFloat("reverb", reverb).putInt("pitch", pitch)
            .putFloat("formant", formant).putFloat("distortion", distortion)
            .putFloat("echo_delay", echoDelay).putFloat("echo_decay", echoDecay)
            .apply()
        NativeAudioProcessor.ensureLoaded()
        if (bypass) return
        NativeAudioProcessor.setEqParams(gain, bass, treble, reverb, pitch,
            formant, distortion, echoDelay, echoDecay)
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

    // 频响曲线数据（用于 Canvas 绘图）
    val curveValues = remember(bass, treble, gain) {
        // 模拟 10 段频响曲线值，基于 bass/treble/gain 参数
        val base = gain
        listOf(
            base + bass * 1.2f,   // 31Hz
            base + bass * 1.0f,   // 62Hz
            base + bass * 0.6f,   // 125Hz
            base + bass * 0.2f,   // 250Hz
            base,                   // 500Hz
            base,                   // 1kHz
            base + treble * 0.3f,  // 2kHz
            base + treble * 0.6f,  // 4kHz
            base + treble * 0.8f,  // 8kHz
            base + treble * 1.0f,  // 16kHz
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        // ============================================================
        // 顶部：标题 + 引擎 + 引擎健康 + 设置齿轮
        // ============================================================
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MaidMic", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    // 引擎健康指示灯
                    val health = NativeAudioProcessor.getHealth()
                    val (healthColor, healthIcon) = when (health) {
                        EngineHealth.OK -> Color(0xFF4CAF50) to Icons.Default.CheckCircle
                        EngineHealth.FALLBACK -> Color(0xFFFFA726) to Icons.Default.Warning
                        EngineHealth.BROKEN -> Color.Red to Icons.Default.Error
                    }
                    Icon(healthIcon, null, tint = healthColor, modifier = Modifier.size(12.dp))
                }
                Text(currentEngine.displayName, fontSize = 11.sp, color = Color(0xFFCE93D8))
            }
            // 设置齿轮（永久常驻）
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, "设置", tint = Color(0xFFCE93D8))
            }
        }

        Spacer(Modifier.height(6.dp))

        // ============================================================
        // 设备检测栏
        // ============================================================
        Surface(
            color = Color(0xFF2A2930),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Speaker, null, modifier = Modifier.size(14.dp), tint = Color(0xFF80CBC4))
                Spacer(Modifier.width(6.dp))
                Text(deviceLabel.value, fontSize = 11.sp, color = Color(0xFF80CBC4))
                Spacer(Modifier.weight(1f))
                // 引擎选择精简版
                AudioEngine.entries.forEach { engine ->
                    Text(
                        if (currentEngine == engine) "● ${engine.displayName}" else "○ ${engine.displayName}",
                        fontSize = 10.sp,
                        color = if (currentEngine == engine) Color(0xFFCE93D8) else Color(0xFF555555),
                        modifier = Modifier.clickable { applyEngine(engine) }.padding(horizontal = 4.dp)
                    )
                }
            }
        }

        // ============================================================
        // 旁路开关（独立控制行）
        // ============================================================
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (bypass) Color(0xFF4A2561) else Color(0xFF2A2930)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (bypass) Icons.Default.PowerSettingsNew else Icons.Default.FlashOn,
                    null,
                    tint = if (bypass) Color(0xFFCE93D8) else Color(0xFF666666),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (bypass) "已旁路 - 音频直通" else "旁路模式",
                    fontSize = 12.sp,
                    color = if (bypass) Color(0xFFCE93D8) else Color(0xFF888888),
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = bypass,
                    onCheckedChange = { b ->
                        bypass = b
                        if (b) {
                            NativeAudioProcessor.setEngine(AudioEngine.PASSTHROUGH)
                        } else {
                            NativeAudioProcessor.setEngine(currentEngine)
                        }
                    },
                    modifier = Modifier.height(24.dp),
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFCE93D8))
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ============================================================
        // 频响曲线图 (Canvas)
        // ============================================================
        if (currentEngine != AudioEngine.FREQ_CURVE) {
            EqCurveGraph(
                values = curveValues,
                labels = bandLabels,
                modifier = Modifier.fillMaxWidth().height(140.dp)
            )
            Spacer(Modifier.height(8.dp))
        }

        // ============================================================
        // 主控件区 — 根据引擎模式
        // ============================================================
        when (currentEngine) {
            AudioEngine.PASSTHROUGH -> {
                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("直通模式", fontSize = 16.sp, color = Color(0xFF666666))
                        Text("音频不经处理直接透传", fontSize = 12.sp, color = Color(0xFF444444))
                    }
                }
            }

            AudioEngine.ECHIO_EQ -> {
                // 预设滚动行
                Text("预设", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFAAAAAA))
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    eqPresets.forEachIndexed { i, preset ->
                        val isSel = i == selectedPreset
                        Surface(
                            onClick = {
                                selectedPreset = i; gain = preset.gain; bass = preset.bass
                                treble = preset.treble; reverb = preset.reverb; pitch = preset.pitch
                                formant = preset.formant; distortion = preset.distortion
                                echoDelay = preset.echoDelay; echoDecay = preset.echoDecay
                                save()
                            },
                            color = if (isSel) Color(0xFF4A2561) else Color(0xFF2A2930),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                        ) {
                            Text(
                                preset.name,
                                fontSize = 12.sp,
                                color = if (isSel) Color(0xFFCE93D8) else Color(0xFFAAAAAA),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 快速滑块（紧凑）
                EqSlider("增益", gain, -10f..10f) { gain = it; save() }
                EqSlider("低音", bass, -10f..10f) { bass = it; save() }
                EqSlider("高音", treble, -10f..10f) { treble = it; save() }

                // 效果折叠区域
                var showEffects by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showEffects = !showEffects },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("附加效果", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFAAAAAA))
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (showEffects) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFF666666)
                    )
                }
                if (showEffects) {
                    EqSlider("混响", reverb, 0f..1f) { reverb = it; save() }
                    Text("变调: $pitch", fontSize = 11.sp, color = Color(0xFF888888))
                    Slider(value = pitch.toFloat(), onValueChange = { pitch = it.toInt(); save() },
                        valueRange = -12f..12f, steps = 23,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFCE93D8), activeTrackColor = Color(0xFFCE93D8)))
                    EqSlider("共振峰", formant, -12f..12f) { formant = it; save() }
                    EqSlider("失真", distortion, 0f..1f) { distortion = it; save() }
                    EqSlider("回声(ms)", echoDelay, 0f..500f) { echoDelay = it; save() }
                    EqSlider("回声衰减", echoDecay, 0f..0.9f) { echoDecay = it; save() }
                }
            }

            AudioEngine.FREQ_CURVE -> {
                Text("曲线预设", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFAAAAAA))
                Spacer(Modifier.height(6.dp))
                CurvePresets.ALL.forEachIndexed { index, preset ->
                    val isSel = index == currentCurveIdx
                    Surface(
                        onClick = { applyCurve(index) },
                        color = if (isSel) Color(0xFF4A2561) else Color(0xFF2A2930),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (isSel) {
                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFFCE93D8), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Column {
                                Text(preset.name, fontSize = 13.sp,
                                    color = if (isSel) Color(0xFFCE93D8) else Color.White)
                                Text(preset.description, fontSize = 10.sp, color = Color(0xFF888888))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ============================================================
        // 测试变声按钮
        // 测试变声按钮
        Surface(
            onClick = {
                if (testState == TestState.IDLE) {
                    AppLogger.i("Test", "用户点击测试变声按钮")
                    // 检查录音权限
                    val hasMic = ContextCompat.checkSelfPermission(context,
                        Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (!hasMic) {
                        AppLogger.e("Test", "测试失败：无录音权限")
                        Toast.makeText(context, "测试失败：缺少录音权限", Toast.LENGTH_SHORT).show()
                    } else {
                        startVoiceTest(context, 3,
                            { s -> testState = s; AppLogger.i("Test", "状态: $s") },
                            { p -> testProgress = p },
                            { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                        )
                    }
                }
            },
            color = when (testState) {
                TestState.IDLE -> Color(0xFF2A2930)
                TestState.RECORDING -> Color(0xFF4A1A1A)
                TestState.PLAYING -> Color(0xFF1A4A1A)
            },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    when (testState) {
                        TestState.IDLE -> Icons.Default.Mic
                        TestState.RECORDING -> Icons.Default.FiberManualRecord
                        TestState.PLAYING -> Icons.Default.PlayArrow
                    },
                    null,
                    tint = when (testState) {
                        TestState.IDLE -> Color(0xFFCE93D8)
                        TestState.RECORDING -> Color.Red
                        TestState.PLAYING -> Color(0xFF4CAF50)
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when (testState) {
                            TestState.IDLE -> "测试变声效果"
                            TestState.RECORDING -> "录音中 ${testProgress}s..."
                            TestState.PLAYING -> "回放中..."
                        },
                        fontSize = 13.sp, color = Color.White
                    )
                    if (testState == TestState.IDLE) {
                        Text("录制3秒 → 引擎处理 → 回放", fontSize = 10.sp, color = Color(0xFF666666))
                    }
                }
                if (testState != TestState.IDLE) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                        color = if (testState == TestState.RECORDING) Color.Red else Color(0xFF4CAF50))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("授权和方案配置 → 关于页右上角设置",
            fontSize = 10.sp, color = Color(0xFF444444),
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

// ============================================================
// 频响曲线图 (Canvas 绘图)
// ============================================================

@Composable
private fun EqCurveGraph(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier
) {
    val primaryColor = Color(0xFFCE93D8)
    val gridColor = Color(0xFF333333)
    val textColor = Color(0xFF666666)
    val curveColor = Color(0xFFCE93D8)
    val fillColor = Color(0xFF4A2561)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padL = 32f
        val padR = 16f
        val padT = 16f
        val padB = 24f
        val graphW = w - padL - padR
        val graphH = h - padT - padB
        val bandCount = values.size
        if (bandCount < 2 || graphW <= 0 || graphH <= 0) return@Canvas

        // Y 轴范围 (-10dB ~ +10dB)
        val yMin = -10f
        val yMax = 10f
        val yRange = yMax - yMin

        // 网格线 (0dB 中心线 + ±5dB, ±10dB)
        val gridLines = listOf(-10f, -5f, 0f, 5f, 10f)
        for (g in gridLines) {
            val y = padT + graphH * (1f - (g - yMin) / yRange)
            drawLine(
                color = if (g == 0f) Color(0xFF555555) else gridColor,
                start = androidx.compose.ui.geometry.Offset(padL, y),
                end = androidx.compose.ui.geometry.Offset(w - padR, y),
                strokeWidth = if (g == 0f) 1.5f else 0.5f
            )
        }

        // 绘制曲线路径
        val path = androidx.compose.ui.graphics.Path()
        val points = values.mapIndexed { i, v ->
            val x = padL + graphW * i.toFloat() / (bandCount - 1).toFloat()
            val y = padT + graphH * (1f - (v.coerceIn(-10f, 10f) - yMin) / yRange)
            androidx.compose.ui.geometry.Offset(x, y)
        }

        if (points.isNotEmpty()) {
            // 填充区域
            val fillPath = androidx.compose.ui.graphics.Path()
            fillPath.moveTo(points[0].x, padT + graphH)
            points.forEach { fillPath.lineTo(it.x, it.y) }
            fillPath.lineTo(points.last().x, padT + graphH)
            fillPath.close()
            drawPath(fillPath, fillColor.copy(alpha = 0.3f))

            // 连线
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                path.lineTo(points[i].x, points[i].y)
            }
            drawPath(path, curveColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // 锚点
            points.forEach { pt ->
                drawCircle(curveColor, radius = 4f, center = pt)
                drawCircle(Color.White, radius = 2f, center = pt)
            }
        }

        // X 轴标签 — 跳过，用底部频率文字替代
        // 用 drawLine 标记频段位置
        labels.forEachIndexed { i, label ->
            val x = padL + graphW * i.toFloat() / (bandCount - 1).toFloat()
            if (i % 2 == 0) { // 只画偶数索引的刻度
                drawLine(Color(0xFF444444), Offset(x, h - padB), Offset(x, h - padB + 4f), strokeWidth = 1f)
            }
        }
    }
}

// ============================================================
// EQ 滑块组件（精简）
// ============================================================

@Composable
fun EqSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label  ${"%.1f".format(value)}", fontSize = 11.sp, color = Color(0xFF888888), modifier = Modifier.width(80.dp))
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = 19,
            modifier = Modifier.weight(1f).height(24.dp),
            colors = SliderDefaults.colors(thumbColor = Color(0xFFCE93D8), activeTrackColor = Color(0xFFCE93D8))
        )
    }
}

// ============================================================
// 测试变声 — 录音 → 引擎处理 → 回放
// ============================================================
// 设置页面（全面参数配置 — 所有数值皆可调）
@Composable
fun SettingsPage(
    onBack: () -> Unit,
    onOpenDeveloperSettings: () -> Unit = {},
    onEngineChanged: (AudioEngine) -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("maidmic_prefs", Context.MODE_PRIVATE)
    val audioPrefs = context.getSharedPreferences("maidmic_audio_params", Context.MODE_PRIVATE)

    // === 音频格式参数 ===
    var sampleRate by remember { mutableIntStateOf(audioPrefs.getInt("sample_rate", 48000)) }
    var bitDepth by remember { mutableIntStateOf(audioPrefs.getInt("bit_depth", 16)) }
    var channels by remember { mutableIntStateOf(audioPrefs.getInt("channels", 2)) }
    var bitrate by remember { mutableIntStateOf(audioPrefs.getInt("bitrate", 192)) }
    var encoderMode by remember { mutableIntStateOf(audioPrefs.getInt("encoder_mode", 0)) }

    // === 缓冲与处理参数 ===
    var bufferSize by remember { mutableIntStateOf(audioPrefs.getInt("buffer_size", 256)) }
    var frameSize by remember { mutableIntStateOf(audioPrefs.getInt("frame_size", 1024)) }
    var overlapRatio by remember { mutableIntStateOf(audioPrefs.getInt("overlap_ratio", 50)) }

    // === 引擎 ===
    var currentEngine by remember { mutableStateOf(NativeAudioProcessor.getEngine()) }

    fun saveAudioParams() {
        audioPrefs.edit()
            .putInt("sample_rate", sampleRate)
            .putInt("bit_depth", bitDepth)
            .putInt("channels", channels)
            .putInt("bitrate", bitrate)
            .putInt("encoder_mode", encoderMode)
            .putInt("buffer_size", bufferSize)
            .putInt("frame_size", frameSize)
            .putInt("overlap_ratio", overlapRatio)
            .apply()
    }

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

        // ============================================================
        // 音频格式（采样率/位深度/声道数/比特率）
        // ============================================================
        Text("音频格式", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
        Spacer(Modifier.height(8.dp))

        // 采样率
        AudioParamRow(label = "采样率", value = when(sampleRate) {
            44100 -> "44.1 kHz"
            48000 -> "48 kHz"
            96000 -> "96 kHz"
            else -> "${sampleRate/1000} kHz"
        }) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf(44100, 48000, 96000).forEach { rate ->
                    FilterChip(
                        selected = sampleRate == rate,
                        onClick = { sampleRate = rate; saveAudioParams() },
                        label = { Text("${rate/1000}k", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFCE93D8), selectedLabelColor = Color.Black
                        )
                    )
                }
            }
        }

        // 位深度
        AudioParamRow(label = "位深度", value = when(bitDepth) {
            16 -> "16-bit Int"
            24 -> "24-bit Int"
            32 -> "32-bit Float"
            else -> "$bitDepth-bit"
        }) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(16 to "16bit", 24 to "24bit", 32 to "32 Float").forEach { (depth, lbl) ->
                    FilterChip(
                        selected = bitDepth == depth,
                        onClick = { bitDepth = depth; saveAudioParams() },
                        label = { Text(lbl, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFCE93D8), selectedLabelColor = Color.Black
                        )
                    )
                }
            }
        }

        // 声道数
        AudioParamRow(label = "声道数", value = if (channels == 1) "单声道" else "立体声") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1 to "单声道", 2 to "立体声").forEach { (cnt, lbl) ->
                    FilterChip(
                        selected = channels == cnt,
                        onClick = { channels = cnt; saveAudioParams() },
                        label = { Text(lbl, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFCE93D8), selectedLabelColor = Color.Black
                        )
                    )
                }
            }
        }

        // 比特率
        AudioParamRow(label = "比特率", value = "${bitrate} kbps") {
            Slider(
                value = bitrate.toFloat(),
                onValueChange = { bitrate = it.toInt(); saveAudioParams() },
                valueRange = 64f..320f, steps = 15,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(thumbColor = Color(0xFFCE93D8), activeTrackColor = Color(0xFFCE93D8))
            )
        }

        Spacer(Modifier.height(16.dp))

        // ============================================================
        // 缓冲与处理（缓冲区/帧长度/重叠率）
        // ============================================================
        Text("缓冲与处理", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
        Spacer(Modifier.height(8.dp))

        // 缓冲区大小
        val bufLatencyMs = bufferSize * 1000 / sampleRate
        AudioParamRow(label = "缓冲区大小", value = "${bufferSize} 样本 (~${bufLatencyMs}ms)") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf(64, 128, 256, 512, 1024).forEach { size ->
                    FilterChip(
                        selected = bufferSize == size,
                        onClick = { bufferSize = size; saveAudioParams() },
                        label = { Text("$size", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFCE93D8), selectedLabelColor = Color.Black
                        )
                    )
                }
            }
        }

        // 帧长度/窗长
        AudioParamRow(label = "帧长度 (窗长)", value = "$frameSize 点") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf(256, 512, 1024, 2048).forEach { size ->
                    FilterChip(
                        selected = frameSize == size,
                        onClick = { frameSize = size; saveAudioParams() },
                        label = { Text("$size", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFCE93D8), selectedLabelColor = Color.Black
                        )
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                when (frameSize) {
                    256 -> "高时间分辨率，适合快速变化信号"
                    512 -> "均衡时频分辨率"
                    1024 -> "标准 FFT 窗，频率精度好"
                    else -> "高频率分辨率，适合稳态信号"
                }, fontSize = 10.sp, color = Color(0xFF666666)
            )
        }

        // 重叠率 + 帧移联动显示
        AudioParamRow(label = "重叠率", value = "$overlapRatio%") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0, 25, 50, 75).forEach { ratio ->
                    FilterChip(
                        selected = overlapRatio == ratio,
                        onClick = { overlapRatio = ratio; saveAudioParams() },
                        label = { Text("$ratio%", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFCE93D8), selectedLabelColor = Color.Black
                        )
                    )
                }
            }
        }

        // 帧移（只读，自动计算）
        val hopSize = (frameSize * (100 - overlapRatio) / 100).coerceAtLeast(1)
        AudioParamRow(label = "帧移 (Hop Size)", value = "$hopSize 点（自动）") {
            Text("← 由帧长度与重叠率决定", fontSize = 10.sp, color = Color(0xFF555555))
        }

        Spacer(Modifier.height(16.dp))

        // ============================================================
        // 编码选项（CBR/VBR/ABR）
        // ============================================================
        Text("编码选项", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
        Spacer(Modifier.height(8.dp))

        AudioParamRow(
            label = "码率控制",
            value = when (encoderMode) { 0 -> "CBR 固定"; 1 -> "VBR 可变"; else -> "ABR 平均" }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0 to "CBR", 1 to "VBR", 2 to "ABR").forEach { (idx, lbl) ->
                    FilterChip(
                        selected = encoderMode == idx,
                        onClick = { encoderMode = idx; saveAudioParams() },
                        label = { Text(lbl, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFCE93D8), selectedLabelColor = Color.Black
                        )
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                when (encoderMode) {
                    0 -> "固定比特率，文件大小可控"
                    1 -> "可变比特率，空间效率高"
                    else -> "平均比特率，折中方案"
                }, fontSize = 10.sp, color = Color(0xFF666666)
            )
        }

        Spacer(Modifier.height(20.dp))

        // ============================================================
        // 音频方案（保留原有权限配置）
        // ============================================================
        Text("音频方案", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
        Spacer(Modifier.height(8.dp))

        SettingsCard(icon = Icons.Default.Lock, title = "Root AudioFlinger", desc = "需要 ROOT 权限") {
            val rootBridge = RootMicBridge(context)
            val status = rootBridge.checkRoot()
            if (status.granted) {
                Toast.makeText(context, "Root 已授权 (${status.magiskVer})", Toast.LENGTH_SHORT).show()
                rootBridge.start()
            } else {
                Toast.makeText(context, "未检测到 root 权限", Toast.LENGTH_SHORT).show()
            }
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

        // ============================================================
        // 音频引擎（保留）
        // ============================================================
        Text("音频引擎", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioEngine.entries.forEach { engine ->
                FilterChip(
                    selected = currentEngine == engine,
                    onClick = { currentEngine = engine; onEngineChanged(engine) },
                    label = { Text(engine.displayName, fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFCE93D8), selectedLabelColor = Color.Black
                    )
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(currentEngine.description, fontSize = 11.sp, color = Color(0xFF666666))

        // 频响曲线预设（仅 FREQ_CURVE 时显示）
        if (currentEngine == AudioEngine.FREQ_CURVE) {
            Spacer(Modifier.height(12.dp))
            Text("曲线预设", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
            Spacer(Modifier.height(6.dp))
            CurvePresets.ALL.forEachIndexed { index, preset ->
                Card(
                    onClick = {
                        NativeAudioProcessor.setCurvePreset(index)
                        prefs.edit().putInt("curve_preset", index).apply()
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (index == NativeAudioProcessor.currentCurvePreset) Color(0xFF2A1A2E) else Color(0xFF1E1E1E)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (index == NativeAudioProcessor.currentCurvePreset) {
                            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFFCE93D8), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preset.name, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                color = if (index == NativeAudioProcessor.currentCurvePreset) Color(0xFFCE93D8) else Color.White)
                            Text(preset.description, fontSize = 11.sp, color = Color(0xFF888888))
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF555555), modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(20.dp))

        // ============================================================
        // 其他（开发者选项）
        // ============================================================
        Text("其他", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFBBBBBB))
        Spacer(Modifier.height(8.dp))
        SettingsCard(icon = Icons.Default.DeveloperMode, title = "开发者选项", desc = "高级功能 · 谨慎操作") {
            onOpenDeveloperSettings()
        }
        Spacer(Modifier.height(40.dp)) // 底部留白
    }
}

// ============================================================
// 音频参数行组件（统一布局）
// ============================================================
@Composable
fun AudioParamRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                Text(value, fontSize = 12.sp, color = Color(0xFFCE93D8))
            }
            Spacer(Modifier.height(6.dp))
            content()
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
    var rootStatus by remember { mutableStateOf<RootMicBridge.RootStatus?>(null) }

    // 检查 root 状态
    LaunchedEffect(Unit) {
        val bridge = RootMicBridge(context)
        rootStatus = bridge.checkRoot()
    }

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

        Spacer(Modifier.height(6.dp))

        // Root 授权状态
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (rootStatus?.granted == true) Color(0xFF1B5E20) else Color(0xFF1E1E1E)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (rootStatus?.granted == true) Icons.Default.CheckCircle else Icons.Default.Lock,
                    null,
                    tint = if (rootStatus?.granted == true) Color.Green else Color(0xFF888888),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (rootStatus?.granted == true) "Root 已授权" else "Root 未检测到",
                        fontSize = 12.sp,
                        color = if (rootStatus?.granted == true) Color.Green else Color(0xFF888888)
                    )
                    if (rootStatus?.granted == true) {
                        Text(
                            rootStatus?.magiskVer ?: "",
                            fontSize = 10.sp,
                            color = Color(0xFF66BB6A)
                        )
                    }
                }
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

// ============================================================
// 测试变声 — 录音 → 引擎处理 → 回放
// ============================================================

enum class TestState { IDLE, RECORDING, PLAYING }

private fun startVoiceTest(
    context: Context,
    durationSec: Int,
    onStateChange: (TestState) -> Unit,
    onProgress: (Int) -> Unit,
    onError: (String) -> Unit = {}
) {
    val sampleRate = 48000
    val bufferSize = 4096
    val totalSamples = sampleRate * durationSec
    val allPcm = mutableListOf<ByteArray>()

    // API 兼容性检查
    val apiLevel = Build.VERSION.SDK_INT
    AppLogger.i("Test", "API level=$apiLevel, sampleRate=$sampleRate, bufferSize=$bufferSize")

    // 不同 Android 版本使用不同的 AudioRecord 构建方式
    val useNewBuilder = apiLevel >= 23 // AudioRecord.Builder 从 API 23 可用

    // 先检查权限
    val hasMic = ContextCompat.checkSelfPermission(context,
        Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    if (!hasMic) {
        val msg = "缺少录音权限，请在设置中授予"
        AppLogger.e("Test", msg)
        onError(msg)
        return
    }

    Thread {
        AppLogger.i("Test", "开始录音 (${durationSec}s)")
        onStateChange(TestState.RECORDING)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
        } catch (e: Exception) {
            AppLogger.e("Test", "AudioRecord 创建失败", e)
            onError("录音创建失败: ${e.message}")
            onStateChange(TestState.IDLE)
            return@Thread
        }

        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            val stateName = recorder?.state?.let { "$it" } ?: "null"
            AppLogger.e("Test", "AudioRecord 未初始化 (state=$stateName)")
            onError("录音器初始化失败 (state=$stateName)")
            recorder?.release()
            onStateChange(TestState.IDLE)
            return@Thread
        }

        recorder.startRecording()
        AppLogger.i("Test", "录音器已启动")
        val buf = ByteArray(bufferSize)
        var totalRead = 0
        var secondsElapsed = 0

        while (totalRead < totalSamples * 2) {
            val read = recorder.read(buf, 0, bufferSize)
            if (read > 0) {
                allPcm.add(buf.copyOf(read))
                totalRead += read
                val elapsed = totalRead / (sampleRate * 2)
                if (elapsed > secondsElapsed) {
                    secondsElapsed = elapsed
                    onProgress(secondsElapsed.coerceAtMost(durationSec))
                }
            } else if (read < 0) {
                AppLogger.e("Test", "录音读取错误: read=$read")
                onError("录音错误 (code=$read)")
                break
            }
        }
        AppLogger.i("Test", "录音完成: ${allPcm.size} 块, ${totalRead} 字节")
        recorder.stop()
        recorder.release()

        AppLogger.i("Test", "开始引擎处理...")
        onStateChange(TestState.PLAYING)
        NativeAudioProcessor.ensureLoaded()
        val processed = allPcm.map { chunk ->
            val out = ByteArray(chunk.size)
            NativeAudioProcessor.processAudio(chunk, out, chunk.size)
            out
        }
        AppLogger.i("Test", "引擎处理完成: ${processed.size} 块")

        val totalSize = processed.sumOf { it.size }

        // 用 getMinBufferSize 计算最佳播放缓冲
        val playBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(bufferSize)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(playBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            AppLogger.e("Test", "AudioTrack 创建失败", e)
            onError("回放创建失败: ${e.message}")
            null
        }

        if (track == null) {
            onStateChange(TestState.IDLE)
            return@Thread
        }

        AppLogger.i("Test", "开始回放...")
        track.play()
        for (chunk in processed) {
            track.write(chunk, 0, chunk.size)
        }
        Thread.sleep(500)
        track.stop()
        track.release()
        AppLogger.i("Test", "回放完成")

        onStateChange(TestState.IDLE)
    }.start()
}
