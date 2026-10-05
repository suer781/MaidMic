// maidmic-app/app/src/main/java/com/maidmic/plugins/lua/LuaPluginSandbox.kt
// MaidMic Lua 插件沙箱
// ============================================================
// UGC 插件运行在沙箱中，权限分级管理。
//
// 插件模型：参数型效果插件（不做逐样本音频处理——LuaJ 解释器性能不可行）。
// 脚本通过 maidmic.* API 与引擎交互：
//   - maidmic.get_param("gain_db")          // 获取引擎参数（按参数 key 全链查找）
//   - maidmic.set_param("pitch_semitones", 7) // 设置引擎参数
//   - maidmic.load_preset("clean")          // 读取插件预设数据 JSON
//   - maidmic.log("message")                // 写日志
//
// 脚本全局约定（由 PluginManager 调用）：
//   plugin_info = { name="...", author="...", version=1, description="..." }
//   function activate()   ... end  -- 激活时调用
//   function deactivate() ... end  -- 停用时调用（可选）
//
// 安全：
//   - 使用 debugGlobals() 仅为了安装指令计数钩子（强制中断死循环），
//     安装完成后立即把 luajava/load/package/require/dofile/loadfile/io/os/debug
//     全部置 NIL，脚本无法访问任何系统能力。
//   - http_get/exec 占位已移除：当前版本对任何权限级都不注册网络/Shell API。
//   - loadOnly 模式：只编译不执行顶层代码（供扫描解析，避免"扫描即 RCE"）。

package aoeck.dwyai.com.plugins.lua

import android.util.Log
import org.luaj.vm2.*
import org.luaj.vm2.lib.*
import org.luaj.vm2.lib.jse.*

/**
 * Lua 插件沙箱
 *
 * 每个插件在自己的沙箱中运行，互不干扰。
 * 沙箱限制：
 * - 不能访问文件系统
 * - 不能发起网络请求
 * - 不能执行 Shell 命令
 * - 只能通过 maidmic.* API 与引擎参数交互
 * - 长任务由调用方放后台线程（激活/停用均为一次性短任务）
 * - 死循环由 debug.sethook 指令计数钩子强制中断
 */
class LuaPluginSandbox(
    val pluginId: String,
    val pluginName: String,
    private val permissionLevel: PluginPermissionLevel,
    private val loadOnly: Boolean = false
) {

    companion object {
        private const val TAG = "LuaPlugin"
        private const val MAX_EXECUTION_TIME_MS = 50L  // 单次调用告警阈值

        // 指令计数钩子：每 HOOK_COUNT 条 VM 指令触发一次，累计超过 HOOK_MAX_STEPS 次即中断。
        // 上限 = 100000 × 1000 = 1e8 条 VM 指令 ≈ 纯死循环约 1 秒内被中断，
        // 正常插件（几十条指令）完全不受影响。
        private const val HOOK_COUNT = 100000
        private const val HOOK_MAX_STEPS = 1000

        // 在脚本运行前安装超时钩子（随后 debug 表会被置 NIL，脚本无法篡改钩子）
        private val TIMEOUT_BOOTSTRAP = """
            do
              local step = 0
              debug.sethook(function()
                step = step + 1
                if step > $HOOK_MAX_STEPS then
                  error("maidmic: script execution timeout", 0)
                end
              end, "", $HOOK_COUNT)
            end
        """.trimIndent()
    }

    // Lua 运行时：debugGlobals() 仅为安装超时钩子；危险全局随后全部置 NIL
    private val globals = JsePlatform.debugGlobals()
    private var loaded = false

    /**
     * 加载插件脚本（执行顶层代码，读入 plugin_info / activate 等全局定义）。
     * [loadOnly] 模式下只编译不执行（供 inspect 扫描，不触发任何脚本逻辑）。
     */
    fun load(luaSource: String) {
        try {
            // 设置沙箱 API（含超时钩子 + 危险全局置 NIL）
            setupSandbox()

            // 加载插件：loadOnly 只编译，不 call
            val chunk = globals.load(luaSource, "@$pluginName.lua")
            if (!loadOnly) {
                chunk.call()
            }
            loaded = true

            Log.i(TAG, "Plugin loaded: $pluginName (ID: $pluginId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin $pluginName", e)
            throw LuaPluginException("Failed to load plugin: ${e.message}")
        }
    }

    /** 读取脚本的 plugin_info 全局表（无则返回 null） */
    fun metadata(): Map<String, Any?>? {
        if (!loaded) return null
        val info = globals.get("plugin_info") ?: return null
        if (!info.istable()) return null
        val map = mutableMapOf<String, Any?>()
        for (key in arrayOf("name", "author", "version", "description")) {
            val v = info.get(key)
            if (v.isnil()) continue
            if (key == "version") {
                val num = v.tonumber()
                if (!num.isnil()) map[key] = num.todouble()
            } else {
                map[key] = v.tojstring()
            }
        }
        return map
    }

    /** 调用脚本的 activate()（不存在则跳过）。返回 false = 调用出错。 */
    fun callActivate(): Boolean = callLifecycle("activate")

    /** 调用脚本的 deactivate()（不存在则静默跳过） */
    fun callDeactivate(): Boolean = callLifecycle("deactivate")

    /** 生命周期函数通用调用：无该函数 → true（视为无操作）；出错 → false */
    private fun callLifecycle(fnName: String): Boolean {
        if (!loaded || loadOnly) return true
        return try {
            val fn = globals.get(fnName)
            if (fn.isfunction()) {
                val start = System.currentTimeMillis()
                fn.call()
                val elapsed = System.currentTimeMillis() - start
                if (elapsed > MAX_EXECUTION_TIME_MS) {
                    Log.w(TAG, "$fnName() took ${elapsed}ms (limit: ${MAX_EXECUTION_TIME_MS}ms)")
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "$fnName() 执行出错", e)
            false
        }
    }

    /**
     * 设置沙箱 API
     *
     * 把 maidmic.* API 注入 Lua 全局环境。
     * 先安装指令计数钩子（强制中断死循环），再把危险全局全部置 NIL。
     */
    private fun setupSandbox() {
        // 1. 安装指令计数钩子（依赖 debug 表，安装后立即移除 debug）
        runCatching {
            globals.load(TIMEOUT_BOOTSTRAP, "@sandbox_timeout").call()
        }.onFailure {
            Log.e(TAG, "安装沙箱超时钩子失败", it)
        }

        // 2. 注入 maidmic API
        val maidmic = LuaTable()

        // maidmic.get_param(key) — 获取引擎参数（按参数 key 在默认管线全链查找）
        maidmic.set("get_param", object : OneArgFunction() {
            override fun call(key: LuaValue): LuaValue {
                val value = nativeGetEngineParam(key.checkjstring())
                return LuaValue.valueOf(value)
            }
        })

        // maidmic.set_param(key, value) — 设置引擎参数（NaN/Inf 直接拒绝）
        maidmic.set("set_param", object : TwoArgFunction() {
            override fun call(key: LuaValue, value: LuaValue): LuaValue {
                val k = key.checkjstring()
                val v = value.tofloat()
                if (!v.isFinite()) {
                    Log.w("LuaPlugin[$pluginName]", "set_param('$k') 拒绝非有限值: $v")
                    return LuaValue.NIL
                }
                nativeSetEngineParam(k, v)
                return LuaValue.NIL
            }
        })

        // maidmic.log(msg) — 写日志
        maidmic.set("log", object : OneArgFunction() {
            override fun call(msg: LuaValue): LuaValue {
                Log.i("LuaPlugin[$pluginName]", msg.checkjstring())
                return LuaValue.NIL
            }
        })

        // maidmic.load_preset(name) — 读取插件预设数据（<插件目录>/<pluginId>/presets/<name>.json）
        maidmic.set("load_preset", object : OneArgFunction() {
            override fun call(name: LuaValue): LuaValue {
                val presetName = name.checkjstring()
                val presetJson = nativeLoadPreset(pluginId, presetName)
                return if (presetJson != null) LuaValue.valueOf(presetJson) else LuaValue.NIL
            }
        })

        globals.set("maidmic", maidmic)

        // 3. 移除危险全局函数（含 luajava/load/package/require/dofile/loadfile/io/os/debug/coroutine）
        globals.set("luajava", LuaValue.NIL)
        globals.set("load", LuaValue.NIL)
        globals.set("loadstring", LuaValue.NIL)
        globals.set("dofile", LuaValue.NIL)
        globals.set("loadfile", LuaValue.NIL)
        globals.set("require", LuaValue.NIL)
        globals.set("package", LuaValue.NIL)
        globals.set("io", LuaValue.NIL)
        globals.set("os", LuaValue.NIL)
        globals.set("debug", LuaValue.NIL)
        // coroutine 可创建新协程/新 LuaThread，可能绕过指令计数钩子，一并移除
        globals.set("coroutine", LuaValue.NIL)
    }

    fun isLoaded(): Boolean = loaded

    // JNI bridges to C engine（external 声明为 public，保证 JNI 符号无 Kotlin 修饰）
    external fun nativeGetEngineParam(key: String): Double
    external fun nativeSetEngineParam(key: String, value: Float)
    external fun nativeLoadPreset(pluginId: String, presetName: String): String?
    external fun nativeSetPluginDir(path: String)
}

/**
 * 插件权限等级
 * Plugin permission levels
 */
enum class PluginPermissionLevel(val level: Int) {
    SANDBOX(0),      // 🟢 沙箱：基础 API，无系统调用
    SIGNED(1),       // 🟡 签名：可网络请求，需开发者签名
    NATIVE(2),       // 🟠 原生：可加载 .so，风险自担
    DANGEROUS(3);    // 🔴 高危：可 Shell 执行，弹出警告
}

class LuaPluginException(message: String) : Exception(message)