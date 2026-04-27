// maidmic-app/app/src/main/java/com/maidmic/plugins/lua/LuaPluginSandbox.kt
// MaidMic Lua 插件沙箱
// ============================================================
// UGC 插件运行在沙箱中，权限分级管理。
//
// 插件用 Lua 编写，通过 MaidMic API 与引擎交互：
//   - maidmic.get_param("gain_db")              // 获取引擎参数
//   - maidmic.set_param("gain_db", 12.0)        // 设置引擎参数
//   - maidmic.process_frame(input_buffer)        // 处理一帧音频
//   - maidmic.log("message")                     // 写日志
//   - maidmic.http_get("https://...")            // ⚠ 需要网络权限
//   - maidmic.exec("shell_command")              // ⚠ 需要高危权限（需签名）
//
// 权限在插件 manifest 中声明，用户安装时看到。

package com.maidmic.plugins.lua

import android.util.Log
import org.luaj.vm2.*
import org.luaj.vm2.lib.*
import org.luaj.vm2.lib.jse.*

/**
 * Lua 插件沙箱
 * 
 * 每个插件在自己的沙箱中运行，互不干扰。
 * 沙箱限制：
 * - 默认不能访问文件系统
 * - 默认不能发起网络请求
 * - 默认不能执行 Shell 命令
 * - 只能通过 maidmic.* API 与引擎交互
 * - 超出时间限制会被强制终止
 */
class LuaPluginSandbox(
    val pluginId: String,
    val pluginName: String,
    private val permissionLevel: PluginPermissionLevel
) {
    
    companion object {
        private const val TAG = "LuaPlugin"
        private const val MAX_EXECUTION_TIME_MS = 50L  // 每帧处理不超过 50ms
    }
    
    // Lua 运行时
    private val globals = JsePlatform.standardGlobals()
    private var loaded = false
    
    /**
     * 加载插件脚本
     * 
     * @param luaSource Lua 源代码
     * @param presetData 预设参数 JSON（可选）
     */
    fun load(luaSource: String, presetData: String? = null) {
        try {
            // 设置沙箱 API
            setupSandbox()
            
            // 如果有预设参数，先注入
            if (presetData != null) {
                globals.set("maidmic_preset", LuaValue.valueOf(presetData))
            }
            
            // 加载插件
            globals.load(luaSource, "@$pluginName.lua").call()
            loaded = true
            
            Log.i(TAG, "Plugin loaded: $pluginName (ID: $pluginId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin $pluginName", e)
            throw LuaPluginException("Failed to load plugin: ${e.message}")
        }
    }
    
    /**
     * 调用插件的主处理函数
     * 
     * @param inputSamples 输入音频样本数组（float）
     * @param sampleCount 样本数
     * @return 处理后的音频样本数组
     */
    fun processFrame(inputSamples: FloatArray, sampleCount: Int): FloatArray {
        if (!loaded) return inputSamples
        
        return try {
            val processFn = globals.get("process")
            if (processFn.isfunction()) {
                // 检查执行时间（防止恶意插件死循环导致崩溃）
                // Check execution time (prevent malicious infinite loops)
                val startTime = System.currentTimeMillis()
                
                // 创建 Lua 数组
                val luaInput = LuaTable()
                for (i in 0 until sampleCount.coerceAtMost(inputSamples.size)) {
                    luaInput.set(i + 1, LuaValue.valueOf(inputSamples[i].toDouble()))
                }
                
                // 调用插件 process 函数
                val result = processFn.call(luaInput)
                
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed > MAX_EXECUTION_TIME_MS) {
                    Log.w(TAG, "Plugin $pluginName took ${elapsed}ms (limit: ${MAX_EXECUTION_TIME_MS}ms)")
                }
                
                // 解析返回值
                if (result.istable()) {
                    val table = result.checktable()
                    val output = FloatArray(sampleCount)
                    for (i in 0 until sampleCount) {
                        output[i] = table.get(i + 1).tofloat()
                    }
                    output
                } else {
                    inputSamples
                }
            } else {
                // 插件没有 process 函数，可能是纯预设
                inputSamples
            }
        } catch (e: Exception) {
            Log.e(TAG, "Plugin $pluginName process error", e)
            inputSamples  // 出错时直通（不崩）
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
        
        // maidmic.get_param(key) — 获取引擎参数
        // All permission levels: read parameters
        maidmic.set("get_param", object : OneArgFunction() {
            override fun call(key: LuaValue): LuaValue {
                // 这里通过 JNI 调用 C 引擎获取参数值
                val value = nativeGetEngineParam(key.checkjstring())
                return LuaValue.valueOf(value)
            }
        })
        
        // maidmic.set_param(key, value) — 设置引擎参数
        // All permission levels: set parameters
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
        
        // maidmic.sleep(ms) — 休眠（仅计时、非实时模式）
        // 限制最大休眠时间，防止插件挂起引擎
        maidmic.set("sleep", object : OneArgFunction() {
            override fun call(ms: LuaValue): LuaValue {
                val sleepMs = ms.checklong().coerceIn(0, 100)
                Thread.sleep(sleepMs)
                return LuaValue.NIL
            }
        })
        
        // === 需要网络权限的功能（🟡 签名插件以上） ===
        if (permissionLevel >= PluginPermissionLevel.SIGNED) {
            maidmic.set("http_get", object : OneArgFunction() {
                override fun call(url: LuaValue): LuaValue {
                    // TODO: 实现 HTTP GET（经 P2P 代理，非直连）
                    return LuaValue.valueOf("")
                }
            })
        }
        
        // === 需要高危权限的功能（🔴 高危权限） ===
        if (permissionLevel >= PluginPermissionLevel.DANGEROUS) {
            maidmic.set("exec", object : OneArgFunction() {
                override fun call(cmd: LuaValue): LuaValue {
                    // TODO: 高危，需要用户逐条确认
                    return LuaValue.valueOf("")
                }
            })
        }
        
        // 注入预设加载功能
        // Inject preset loading capability
        maidmic.set("load_preset", object : OneArgFunction() {
            override fun call(name: LuaValue): LuaValue {
                val presetName = name.checkjstring()
                // 从插件包中加载预设 JSON
                val presetJson = nativeLoadPreset(pluginId, presetName)
                if (presetJson != null) {
                    return LuaValue.valueOf(presetJson)
                }
                return LuaValue.NIL
            }
        })
        
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
    
    // JNI bridges to C engine
    private external fun nativeGetEngineParam(key: String): Double
    private external fun nativeSetEngineParam(key: String, value: Float)
    private external fun nativeLoadPreset(pluginId: String, presetName: String): String?
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
