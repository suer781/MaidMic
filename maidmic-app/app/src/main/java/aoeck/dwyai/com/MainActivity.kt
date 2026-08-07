// MainActivity.kt — MaidMic 主界面
// ============================================================
// 主 Activity：引导页 → 底部导航（变声 / EQ / 音效库 / 设置）

package aoeck.dwyai.com

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import rikka.shizuku.Shizuku
import aoeck.dwyai.com.ui.editor.ModuleChainEditor
import aoeck.dwyai.com.ui.editor.PipelineNode
import aoeck.dwyai.com.ui.editor.DspModuleInfo
import aoeck.dwyai.com.ui.editor.findModuleById
import aoeck.dwyai.com.ui.editor.getDefaultParams
import aoeck.dwyai.com.ui.VoiceChangePage
import aoeck.dwyai.com.ui.EqPage as NewEqPage
import aoeck.dwyai.com.ui.EffectsLibraryPage
import aoeck.dwyai.com.ui.SettingsPage as NewSettingsPage
import aoeck.dwyai.com.ui.settings.developer.DeveloperSettingsPage
import aoeck.dwyai.com.streaming.ConnectionManager
import aoeck.dwyai.com.streaming.ConnectionState
import aoeck.dwyai.com.streaming.MicMode
import aoeck.dwyai.com.streaming.PeerDevice
import aoeck.dwyai.com.floating.FloatingBallService
import aoeck.dwyai.com.floating.OverlayPermissionHelper
import aoeck.dwyai.com.ui.theme.MaidMicTheme
import aoeck.dwyai.com.ui.theme.MaidMicMotion
import aoeck.dwyai.com.util.HapticHelper
import aoeck.dwyai.com.util.HighRefreshRateHelper

// ============================================================
// 导航项
// ============================================================

sealed class NavItem(val label: String, val icon: ImageVector) {
    object VOICE_CHANGE : NavItem("变声", Icons.Default.Mic)
    object EQ : NavItem("EQ", Icons.Default.Equalizer)
    object EFFECTS : NavItem("音效库", Icons.Default.LibraryMusic)
    object SETTINGS : NavItem("设置", Icons.Default.Settings)
}

// 导航顺序（用于 AnimatedContent 切换方向判断；sealed class 无 enum.ordinal）
private fun NavItem.order(): Int = when (this) {
    NavItem.VOICE_CHANGE -> 0
    NavItem.EQ -> 1
    NavItem.EFFECTS -> 2
    NavItem.SETTINGS -> 3
}

private const val PREFS_NAME = "maidmic_prefs"
private const val KEY_ONBOARDING_DONE = "onboarding_done"
private const val KEY_UGC_ENABLED = "ugc_enabled"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HighRefreshRateHelper.applyHighRefreshRate(this)
        setContent {
            MaidMicTheme {
                MaidMicMain(context = this@MainActivity)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val prefs = getSharedPreferences("maidmic_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("hide_recents", false)) {
            finishAndRemoveTask()
        }
    }
}

// ============================================================
// 主界面
// ============================================================

/**
 * 将 PipelineController 的模块链镜像转换为 UI 使用的 PipelineNode 列表。
 * 参数值从镜像读取，参数元数据（label/min/max/unit）从 getDefaultParams 获取。
 */
private fun buildPipelineNodes(): List<PipelineNode> {
    return PipelineController.chain.map { inst ->
        val moduleInfo = findModuleById(inst.moduleId)
            ?: DspModuleInfo(inst.moduleId, "Module(${inst.moduleId})", "未知模块", "❓")
        // 获取参数元数据，再用镜像中的当前值覆盖
        val paramList = getDefaultParams(inst.moduleId).map { p ->
            val currentValue = inst.params[p.key] ?: p.value
            p.copy(value = currentValue)
        }.toMutableList()
        PipelineNode(
            nodeId = inst.id,
            module = moduleInfo,
            bypass = inst.bypass,
            params = paramList
        )
    }
}

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
    var currentNav: NavItem by remember { mutableStateOf(NavItem.VOICE_CHANGE) }
    var pipelineNodes by remember { mutableStateOf(listOf<PipelineNode>()) }
    var isDagMode by remember { mutableStateOf(false) }
    var isUgcEnabled by remember { mutableStateOf(prefs.getBoolean(KEY_UGC_ENABLED, false)) }
    // 变声节拍开关：单一状态源（SettingsPage / VoiceChangePage 均引用此状态）
    var enableLraRhythm by remember { mutableStateOf(prefs.getBoolean("enable_lra_rhythm", false)) }
    var showDeveloperSettings by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }

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

    // 保存变声节拍状态（与设置页 / 变声页共享单一状态源）
    LaunchedEffect(enableLraRhythm) {
        prefs.edit().putBoolean("enable_lra_rhythm", enableLraRhythm).apply()
    }

    // ============================================================
    // 双设备模式 — 麦克风端 / 接收端
    // ============================================================
    val connectionManager = remember { ConnectionManager(context) }
    var micMode by remember { mutableStateOf(MicMode.RECEIVER) }
    var connState by remember { mutableStateOf(ConnectionState.IDLE) }
    var connectedDevice by remember { mutableStateOf<PeerDevice?>(null) }
    var localIp by remember { mutableStateOf("") }
    var targetIp by remember { mutableStateOf("") }

    // 监听连接状态
    LaunchedEffect(micMode) {
        connectionManager.onStateChange = { newState ->
            connState = newState
            if (newState == ConnectionState.CONNECTED) {
                connectedDevice = connectionManager.connectedDevice
            } else if (newState == ConnectionState.DISCONNECTED) {
                connectedDevice = null
            }
        }
    }

    // ============================================================
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
        // Task 1: 首次启动（maidmic_eq 无 pitch 键）写入萌妹默认参数
        // 避免冷启动 ECHIO_EQ 全 0 → 数学直通（打开不变声）
        if (!eqPrefs.contains("pitch")) {
            AppLogger.i("Engine", "首次启动: 写入萌妹默认参数 (pitch=+4, formant=+2, reverb=0.18, treble=+2.0, gain=+1.0)")
            eqPrefs.edit()
                .putInt("preset", 0)
                .putFloat("gain", 1.0f)
                .putFloat("bass", 0f)
                .putFloat("treble", 2.0f)
                .putFloat("reverb", 0.18f)
                .putInt("pitch", 4)
                .putFloat("formant", 2.0f)
                .putFloat("distortion", 0f)
                .putFloat("echo_delay", 0f)
                .putFloat("echo_decay", 0f)
                .apply()
        }
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
        AppLogger.i("Engine", "DSP参数已初始化到引擎")

        // Task 3: 初始化默认模块链（同步 UI 状态）
        // C++ 端 ensure_default_pipeline 已在 JNI_OnLoad 创建默认管线（9 个模块）。
        // Kotlin 侧初始化 PipelineController 镜像，使模块链编辑器显示默认链节点。
        // 参数从 maidmic_eq prefs 读取，与 EqPage 保持一致。
        PipelineController.initDefaultChain(eqPrefs)
        pipelineNodes = buildPipelineNodes()
        AppLogger.i("Pipeline", "默认模块链已同步到 UI: ${pipelineNodes.size} 个节点")
    }

    // ============================================================
    // Task 7.4: App 启动时恢复悬浮球状态
    // 开关为开且权限已授予 → 自动启动 FloatingBallService
    // 否则保持关闭（若权限被撤销，同步将开关重置为关，保持 UI 一致）
    // ============================================================
    LaunchedEffect(Unit) {
        val enabled = prefs.getBoolean("floating_ball_enabled", false)
        if (enabled) {
            if (OverlayPermissionHelper.canDrawOverlays(context)) {
                val intent = Intent(context, FloatingBallService::class.java)
                ContextCompat.startForegroundService(context, intent)
                AppLogger.i("FloatingBall", "App 启动：悬浮球开关为开，自动启动 Service")
            } else {
                // 权限被撤销 → 同步重置开关为关，避免 UI 显示与实际不符
                prefs.edit().putBoolean("floating_ball_enabled", false).apply()
                AppLogger.w("FloatingBall", "App 启动：悬浮球开关为开但无权限，已重置为关")
            }
        }
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
        BackHandler { showDeveloperSettings = false }
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

    // 模块链编辑器（作为全屏页面打开）
    if (showEditor) {
        BackHandler { showEditor = false }
        ModuleChainEditor(
            isDagMode = isDagMode,
            nodes = pipelineNodes,
            onAddModule = { moduleId ->
                PipelineController.addModule(moduleId)
                pipelineNodes = buildPipelineNodes()
            },
            onRemoveModule = { nodeId ->
                PipelineController.removeModuleById(nodeId)
                pipelineNodes = buildPipelineNodes()
            },
            onReorderModule = { from, to ->
                PipelineController.reorder(from, to)
                pipelineNodes = buildPipelineNodes()
            },
            onToggleBypass = { nodeId ->
                val node = pipelineNodes.find { it.nodeId == nodeId }
                val newBypass = !(node?.bypass ?: false)
                PipelineController.setBypassById(nodeId, newBypass)
                pipelineNodes = buildPipelineNodes()
            },
            onParamChange = { nodeId, key, value ->
                PipelineController.setParamById(nodeId, key, value)
                pipelineNodes = buildPipelineNodes()
            }
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navItems = listOf(NavItem.VOICE_CHANGE, NavItem.EQ, NavItem.EFFECTS, NavItem.SETTINGS)
                navItems.forEach { item ->
                    NavigationBarItem(
                        selected = currentNav == item,
                        onClick = {
                            if (currentNav != item) {
                                HapticHelper.basic()
                                currentNav = item
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = currentNav,
            transitionSpec = {
                val direction = if (targetState.order() > initialState.order()) 1 else -1
                (slideInHorizontally(animationSpec = MaidMicMotion.PageTransitionSpringIntOffset) { fullWidth -> fullWidth * direction } +
                    fadeIn(animationSpec = MaidMicMotion.PageTransitionSpring)) togetherWith
                (slideOutHorizontally(animationSpec = MaidMicMotion.PageTransitionSpringIntOffset) { fullWidth -> -fullWidth * direction } +
                    fadeOut(animationSpec = MaidMicMotion.PageTransitionSpring))
            },
            modifier = Modifier.padding(padding),
            label = "NavTransition"
        ) { nav ->
            when (nav) {
                NavItem.VOICE_CHANGE -> VoiceChangePage(context = context, enableLraRhythm = enableLraRhythm)
                NavItem.EQ -> NewEqPage(context = context)
                NavItem.EFFECTS -> EffectsLibraryPage(context = context)
                NavItem.SETTINGS -> NewSettingsPage(
                    context = context,
                    enableLraRhythm = enableLraRhythm,
                    onLraRhythmToggle = { enableLraRhythm = it },
                    onOpenDeveloperSettings = { showDeveloperSettings = true }
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
            Text("录音后处理变声 · Echio DSP 引擎", fontSize = 13.sp, color = Color(0xFF999999))
            Spacer(Modifier.height(24.dp))

            // 权限状态（录音权限为运行前提，保持不变）
            PermissionRow("录音权限", if (hasMic) "✓ 已授予" else "请求中...", if (hasMic) Color.Green else Color(0xFFCE93D8))
            if (Build.VERSION.SDK_INT >= 33) {
                Spacer(Modifier.height(8.dp))
                PermissionRow("通知权限", if (hasNotif) "✓ 已授予" else "请求中...", if (hasNotif) Color.Green else Color(0xFFCE93D8))
            }

            Spacer(Modifier.height(24.dp))
            // 主路径介绍：录音后处理变声
            Text(
                "按住录音，松手即可听到变声效果 —— 录音后处理变声。",
                fontSize = 13.sp,
                color = Color(0xFFAAAAAA)
            )
            Spacer(Modifier.height(24.dp))

            // 后台保活折叠区（默认收起）：引导用户开启电池优化白名单
            var showKeepAlive by remember { mutableStateOf(false) }
            // 是否已加入电池优化白名单（忽略电池优化 = 系统不主动回收后台）
            fun isIgnoringBatteryOptimizations(): Boolean {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
                return pm.isIgnoringBatteryOptimizations(context.packageName)
            }
            var keepAliveGranted by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }
            // 从系统设置返回后刷新保活状态
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        keepAliveGranted = isIgnoringBatteryOptimizations()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            Card(
                onClick = { showKeepAlive = !showKeepAlive },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (showKeepAlive) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        null,
                        tint = Color(0xFFCE93D8),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (showKeepAlive) "收起" else "后台保活",
                            fontSize = 14.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "保持后台运行，录音与悬浮球服务不被打断",
                            fontSize = 12.sp,
                            color = Color(0xFF888888)
                        )
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF666666), modifier = Modifier.size(20.dp))
                }
            }

            if (showKeepAlive) {
                Spacer(Modifier.height(12.dp))

                // 保活权限：电池优化白名单
                MicModeCard(
                    "后台保活",
                    if (keepAliveGranted) "✓ 已开启（系统将保留后台运行）"
                    else "未开启 · 点击允许忽略电池优化",
                    if (keepAliveGranted) Icons.Default.CheckCircle else Icons.Default.BatterySaver
                ) {
                    if (!keepAliveGranted) {
                        try {
                            // 直接请求"忽略电池优化"（部分机型支持）
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        } catch (_: Exception) {
                            // 不支持直接请求 → 跳应用详情页，由用户手动开启
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "开启后 MaidMic 在后台运行更稳定，悬浮球与录音服务不易被系统回收。",
                    fontSize = 12.sp,
                    color = Color(0xFF888888)
                )
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
// 测试变声 — 录音 → 引擎处理 → 回放
// ============================================================

enum class TestState { IDLE, RECORDING, PLAYING }

private fun startVoiceTest(
    context: Context,
    durationSec: Int,
    onStateChange: (TestState) -> Unit,
    onProgress: (Int) -> Unit,
    onError: (String) -> Unit = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
) {
    // 处理块大小：2048 样本/块 @ 48kHz（16-bit mono → 4096 字节 ≈ 42.7ms），
    // 块适中：过大增加首字延迟，过小造成频繁 JNI 调用。
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

    // 录音→处理→回放全程在后台线程执行，不阻塞 UI 线程。
    // 如需更低延迟可把线程优先级提到 Process.THREAD_PRIORITY_AUDIO(-16)。
    Thread {
        AppLogger.i("Test", "开始录音 (${durationSec}s)")
        onStateChange(TestState.RECORDING)

        // 录音/回放前请求音频焦点（AUDIOFOCUS_GAIN_TRANSIENT），结束后在 finally 放弃。
        // 备选方案：setMode(MODE_IN_COMMUNICATION) 后恢复原模式；
        // 此处选 requestAudioFocus，避免全局改动音频模式影响其他应用。
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        val focusGranted = audioManager.requestAudioFocus(
            null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        AppLogger.i("Test", "请求音频焦点: ${if (focusGranted) "成功" else "失败"}")

        var recorder: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            // ---- 录音 ----
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
                AppLogger.e("Test", "AudioRecord 创建失败", e)
                onError("录音创建失败: ${e.message}")
                null
            }

            if (recorder == null) {
                return@Thread
            }
            val rec = recorder
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                AppLogger.e("Test", "AudioRecord 未初始化 (state=${rec.state})")
                onError("录音器初始化失败 (state=${rec.state})")
                return@Thread
            }

            rec.startRecording()
            AppLogger.i("Test", "录音器已启动")
            val buf = ByteArray(bufferSize)
            var totalRead = 0
            var secondsElapsed = 0
            try {
                while (totalRead < totalSamples * 2) {
                    val read = rec.read(buf, 0, bufferSize)
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
            } catch (e: Exception) {
                AppLogger.e("Test", "录音读取异常", e)
                onError("录音读取失败: ${e.message}")
            }
            rec.stop()
            rec.release()
            recorder = null
            AppLogger.i("Test", "录音完成: ${allPcm.size} 块, ${totalRead} 字节")

            // ---- 引擎处理（逐块，已在后台线程，不阻塞 UI） ----
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
            if (totalSize == 0) {
                AppLogger.w("Test", "无有效音频数据，跳过回放")
                return@Thread
            }

            // ---- 回放（AudioTrack STREAM 模式，48kHz / MONO / PCM16） ----
            // 缓冲 ≥ max(getMinBufferSize, 2×块字节)，避免 write 阻塞掉块导致卡顿
            val minTrackBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val trackBufSize = if (minTrackBuf > 0) maxOf(minTrackBuf, bufferSize * 2) else bufferSize * 2
            track = try {
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
                    .setBufferSizeInBytes(trackBufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                AppLogger.e("Test", "AudioTrack 创建失败", e)
                onError("回放创建失败: ${e.message}")
                null
            }
            if (track == null) {
                return@Thread
            }
            val tr = track

            AppLogger.i("Test", "开始回放...")
            tr.play()
            val totalFrames = totalSize / 2 // 16-bit mono：2 字节/帧
            for (chunk in processed) {
                tr.write(chunk, 0, chunk.size)
            }
            // 等待播放完成：轮询 playbackHeadPosition 到达已写入帧数，
            // 避免未播完即 stop() 截断长录音（50ms 轮询，最长等待 20s）
            val deadline = SystemClock.elapsedRealtime() + 20_000L
            while (tr.playbackHeadPosition < totalFrames && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(50)
            }
            AppLogger.i("Test", "回放完成: head=${tr.playbackHeadPosition}/$totalFrames 帧")
        } catch (e: Exception) {
            AppLogger.e("Test", "录音/处理/回放异常", e)
            onError("变声测试失败: ${e.message}")
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
            onStateChange(TestState.IDLE)
        }
        AppLogger.i("Test", "测试结束")
    }.start()
}
