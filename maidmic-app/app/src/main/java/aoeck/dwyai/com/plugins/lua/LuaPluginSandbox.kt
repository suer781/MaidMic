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
// 权限在插件 manifest 中声明，用户安装时看到。
// 网络与 Shell 能力（http_get/exec）仅占位：SIGNED/DANGEROUS 等级，
// 当前版本不开放（防止 UGC 脚本越权，后续按签名体系再启用）。

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
 */
class LuaPluginSandbox(
    val pluginId: String,
    val pluginName: String,
    private val permissionLevel: PluginPermissionLevel
) {

    companion object {
        private const val TAG = "LuaPlugin"
        private const val MAX_EXECUTION_TIME_MS = 50L  // 单次调用告警阈值
    }

    // Lua 运行时
    private val globals = JsePlatform.standardGlobals()
    private var loaded = false

    /**
     * 加载插件脚本（执行顶层代码，读入 plugin_info / activate 等全局定义）
     */
    fun load(luaSource: String) {
        try {
            // 设置沙箱 API
            setupSandbox()

            // 加载插件
            globals.load(luaSource, "@$pluginName.lua").call()
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
            if (!v.isnil()) map[key] = v.tojstring()
        }
        return map
    }

    /** 调用脚本的 activate()（不存在则跳过）。返回 false = 调用出错。 */
    fun callActivate(): Boolean = callLifecycle("activate")

    /** 调用脚本的 deactivate()（不存在则静默跳过） */
    fun callDeactivate(): Boolean = callLifecycle("deactivate")

    /** 生命周期函数通用调用：无该函数 → true（视为无操作）；出错 → false */
    private fun callLifecycle(fnName: String): Boolean {
        if (!loaded) return true
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
     * 根据权限等级开放不同功能。
     */
    private fun setupSandbox() {
        val maidmic = LuaTable()

        // maidmic.get_param(key) — 获取引擎参数（按参数 key 在默认管线全链查找）
        maidmic.set("get_param", object : OneArgFunction() {
            override fun call(key: LuaValue): LuaValue {
                val value = nativeGetEngineParam(key.checkjstring())
                return LuaValue.valueOf(value)
            }
        })

        // maidmic.set_param(key, value) — 设置引擎参数
        maidmic.set("set_param", object : TwoArgFunction() {
            override fun call(key: LuaValue, value: LuaValue): LuaValue {
                nativeSetEngineParam(key.checkjstring(), value.tofloat())
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

        // === 需要网络权限的功能（🟡 签名插件以上）—— 占位不开放 ===
        if (permissionLevel >= PluginPermissionLevel.SIGNED) {
            maidmic.set("http_get", object : OneArgFunction() {
                override fun call(url: LuaValue): LuaValue {
                    // TODO: 实现 HTTP GET（经 P2P 代理，非直连）
                    return LuaValue.valueOf("")
                }
            })
        }

        // === 需要高危权限的功能（🔴 高危权限）—— 占位不开放 ===
        if (permissionLevel >= PluginPermissionLevel.DANGEROUS) {
            maidmic.set("exec", object : OneArgFunction() {
                override fun call(cmd: LuaValue): LuaValue {
                    // TODO: 高危，需要用户逐条确认
                    return LuaValue.valueOf("")
                }
            })
        }

        globals.set("maidmic", maidmic)
        
        // 移除危险全局函数
        // Remove dangerous global functions
        globals.set("dofile", LuaValue.NIL)
        globals.set("loadfile", LuaValue.NIL)
        globals.set("require", LuaValue.NIL)  // 按需开放
        globals.set("io", LuaValue.NIL)
        globals.set("os", LuaValue.NIL)
        globals.set("debug", LuaValue.NIL)
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
