// plugins/lua/PluginManager.kt — Lua 效果插件管理器
// ============================================================
// 插件系统（UGC 效果插件）核心：扫描、安装、激活/停用、状态持久化。
//
// 插件模型：参数型效果插件（Lua 脚本）
//   - 脚本通过 maidmic.set_param(key, value) 组合引擎参数实现效果
//     （如电话音、花栗鼠、低沉大叔），引擎侧经 JNI 落到默认管线模块
//   - 不做逐样本音频处理：LuaJ 解释器对 48kHz 逐样本循环性能不可行，
//     且需要音频数据跨 JNI 双向拷贝——参数型是性能与安全的平衡点
//
// 脚本约定（全局符号）：
//   plugin_info = { name="...", author="...", version=1, description="..." }
//   function activate()   ... end   -- 激活时调用（设置引擎参数）
//   function deactivate() ... end   -- 停用时调用（恢复参数，可选）
//   function on_param(key, value) ... end  -- 保留：面板传参（暂未接线）
//
// 存储布局：
//   <externalFilesDir>/maidmic_plugins/<file>.lua          用户/内置插件脚本
//   <externalFilesDir>/maidmic_plugins/<pluginId>/presets/  插件预设数据（load_preset 读取）
//
// 安全：脚本在 LuaPluginSandbox 沙箱运行（无 io/os/debug/require），
//       只能经 maidmic.* 与引擎参数交互；激活在后台线程执行，卡死不阻塞 UI。

package aoeck.dwyai.com.plugins.lua

import android.content.Context
import aoeck.dwyai.com.AppLogger
import aoeck.dwyai.com.NativeAudioProcessor
import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * 插件管理器（应用级单例）。
 *
 * 用法（设置页）：
 * ```kotlin
 * val pm = PluginManager.get(context)
 * pm.refresh()                       // 后台扫描
 * pm.plugins                         // List<PluginInfo>（Compose state）
 * pm.activate(plugin.id)             // 后台激活
 * pm.activePluginId.value            // 当前激活插件（null=无）
 * ```
 */
class PluginManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PluginManager"
        /** 插件根目录名（位于 app external files 下，卸载随应用清除） */
        private const val PLUGINS_DIR_NAME = "maidmic_plugins"
        /** 激活状态持久化 key */
        private const val KEY_ACTIVE_PLUGIN = "active_plugin_id"

        @Volatile private var instance: PluginManager? = null

        fun get(context: Context): PluginManager =
            instance ?: synchronized(this) {
                instance ?: PluginManager(context.applicationContext).also { instance = it }
            }
    }

    /** 插件元数据（扫描结果） */
    data class PluginInfo(
        val id: String,          // 文件名去扩展名（稳定、可持久化）
        val name: String,
        val author: String,
        val version: Int,
        val description: String,
        val source: Source,
        val file: File,
    ) {
        enum class Source { BUILT_IN, USER }
    }

    /** 插件运行状态 */
    enum class PluginState { IDLE, ACTIVATING, ACTIVE, ERROR }

    // ===== Compose 可观察状态 =====
    /** 已扫描插件列表 */
    val plugins = mutableStateOf<List<PluginInfo>>(emptyList())
    /** 当前激活插件 id（null = 无激活插件） */
    val activePluginId = mutableStateOf<String?>(null)
    /** 运行状态 per 插件 id */
    val states = mutableStateOf<Map<String, PluginState>>(emptyMap())
    /** 最近一次错误信息（UI 显示） */
    val lastError = mutableStateOf<String?>(null)

    /** 当前激活的沙箱实例（停用时调用 deactivate） */
    private var activeSandbox: LuaPluginSandbox? = null
    /** 激活互斥锁（防止并发激活/停用交错） */
    private val activationLock = Any()

    private val prefs =
        context.getSharedPreferences("maidmic_prefs", Context.MODE_PRIVATE)

    /** 插件根目录 */
    fun pluginsDir(): File =
        File(context.getExternalFilesDir(null), PLUGINS_DIR_NAME).apply { mkdirs() }

    // ============================================================
    // 扫描
    // ============================================================

    /**
     * 扫描插件（后台线程）：
     *   1. 把 assets 内置插件释放到插件目录（已存在则跳过，用户可自行修改）
     *   2. 扫描目录下所有 .lua，解析 plugin_info 元数据
     */
    fun refresh() {
        Thread {
            try {
                releaseBuiltIns()
                val dir = pluginsDir()
                val found = mutableListOf<PluginInfo>()
                dir.listFiles { f -> f.isFile && f.extension.equals("lua", true) }
                    ?.sortedBy { it.nameWithoutExtension }
                    ?.forEach { file ->
                        val info = inspect(file) ?: return@forEach
                        found += info
                    }
                plugins.value = found
                AppLogger.i(TAG, "扫描完成：${found.size} 个插件 (${found.joinToString { it.id }})")
            } catch (e: Exception) {
                AppLogger.e(TAG, "扫描插件失败", e)
                lastError.value = "扫描插件失败: ${e.message}"
            }
        }.start()
    }

    /** 释放 assets 内置插件（同名跳过） */
    private fun releaseBuiltIns() {
        val dir = pluginsDir()
        context.assets.list("plugins")?.forEach { name ->
            if (!name.endsWith(".lua", true)) return@forEach
            val out = File(dir, name)
            if (!out.exists()) {
                runCatching {
                    context.assets.open("plugins/$name").use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    AppLogger.i(TAG, "释放内置插件: $name")
                }.onFailure { AppLogger.e(TAG, "释放内置插件失败: $name", it) }
            }
        }
    }

    /** 加载脚本并解析元数据（轻量：只在扫描线程执行一次） */
    private fun inspect(file: File): PluginInfo? {
        return try {
            val sandbox = LuaPluginSandbox(
                pluginId = file.nameWithoutExtension,
                pluginName = file.nameWithoutExtension,
                permissionLevel = PluginPermissionLevel.SANDBOX,
            )
            sandbox.load(file.readText())
            val info = sandbox.metadata()
            PluginInfo(
                id = file.nameWithoutExtension,
                name = info?.get("name") as? String ?: file.nameWithoutExtension,
                author = info?.get("author") as? String ?: "未知",
                version = (info?.get("version") as? Double)?.toInt() ?: 1,
                description = info?.get("description") as? String ?: "",
                source = PluginInfo.Source.USER,  // 释放到目录后统一视为用户插件
                file = file,
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "插件解析失败: ${file.name}", e)
            null  // 解析失败的插件不进列表（避免激活必然报错）
        }
    }

    // ============================================================
    // 激活 / 停用
    // ============================================================

    /**
     * 激活插件（后台线程）：
     *   先停用当前插件 → 加载新脚本 → 调用 activate()。
     *   任一步失败：Toast 语义由 UI 层根据 lastError/state 呈现。
     */
    fun activate(pluginId: String) {
        NativeAudioProcessor.ensureLoaded()
        val info = plugins.value.find { it.id == pluginId } ?: return
        setState(pluginId, PluginState.ACTIVATING)
        Thread {
            synchronized(activationLock) {
                try {
                    // 停掉当前激活插件（恢复其参数）
                    activeSandbox?.let { current ->
                        runCatching { current.callDeactivate() }
                            .onFailure { AppLogger.w(TAG, "旧插件 deactivate 异常: ${it.message}") }
                        activeSandbox = null
                    }

                    // 加载新插件
                    val sandbox = LuaPluginSandbox(
                        pluginId = info.id,
                        pluginName = info.name,
                        permissionLevel = PluginPermissionLevel.SANDBOX,
                    )
                    sandbox.load(info.file.readText())
                    // 告知 JNI 预设数据目录（maidmic.load_preset 读取）
                    sandbox.nativeSetPluginDir(pluginsDir().absolutePath)

                    // 调用 activate()
                    sandbox.callActivate()

                    activeSandbox = sandbox
                    activePluginId.value = info.id
                    setState(info.id, PluginState.ACTIVE)
                    lastError.value = null
                    AppLogger.i(TAG, "插件已激活: ${info.id}")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "插件激活失败: $pluginId", e)
                    lastError.value = "激活失败: ${e.message}"
                    activeSandbox = null
                    activePluginId.value = null
                    setState(pluginId, PluginState.ERROR)
                }
            }
        }.start()
    }

    /** 停用当前插件（调用 deactivate 恢复参数） */
    fun deactivate() {
        val id = activePluginId.value ?: return
        Thread {
            synchronized(activationLock) {
                try {
                    activeSandbox?.callDeactivate()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "deactivate 异常: ${e.message}")
                }
                activeSandbox = null
                activePluginId.value = null
                setState(id, PluginState.IDLE)
                AppLogger.i(TAG, "插件已停用: $id")
            }
        }.start()
    }

    /** 当前插件是否激活（供开关 UI） */
    fun isActive(id: String): Boolean = activePluginId.value == id

    // ===== 状态辅助 =====
    private fun setState(id: String, state: PluginState) {
        states.value = states.value + (id to state)
    }

    // ===== 持久化 =====
    fun saveActiveState() {
        prefs.edit().putString(KEY_ACTIVE_PLUGIN, activePluginId.value).apply()
    }

    /** 恢复上次激活的插件（App 启动后由设置页/主界面调用一次） */
    fun restoreLastActive() {
        if (activePluginId.value != null) return
        val saved = prefs.getString(KEY_ACTIVE_PLUGIN, null) ?: return
        if (plugins.value.none { it.id == saved }) return
        activate(saved)
    }
}
