// plugins/core/DspPluginChain.kt — DSP 插件链（Tier 2 实时处理）
// ============================================================
// 管理已加载/启用的 DspAudioPlugin 实例，并挂接进音频处理路径：
//   NativeAudioProcessor.processAudio → 引擎管线 → DSP 插件链（串行）
//
// 线程模型：
//   - process() 在音频线程调用：只读 activePlugins 快照（原子引用），
//     不加锁、不分配（实例的 process 实现方负责预分配）
//   - enable/disable 在 UI/后台线程调用：构建新链表后原子替换
//
// 容错：单插件 process 抛异常 → 自动停用该插件（防止坏插件持续打断音频）。

package aoeck.dwyai.com.plugins.core

import android.content.Context
import aoeck.dwyai.com.AppLogger
import aoeck.dwyai.com.NativeAudioProcessor
import java.util.concurrent.atomic.AtomicReference

object DspPluginChain {

    private const val TAG = "DspPluginChain"
    private const val KEY_ACTIVE_IDS = "dsp_plugin_active"

    /** 链上插件（顺序即处理顺序） */
    data class LoadedDsp(val pkg: ExtPluginPackage, val plugin: DspAudioPlugin)

    /** 原子快照（音频线程无锁读取） */
    private val chain = AtomicReference<List<LoadedDsp>>(emptyList())

    /** 已加载实例缓存（id → LoadedDsp），供启用/停用切换 */
    private val pool = HashMap<String, LoadedDsp>()

    /** 当前链（UI 只读） */
    fun snapshot(): List<LoadedDsp> = chain.get()

    /**
     * 音频线程入口：按链顺序原地处理。
     * 引擎（Echio / 直通）处理完后调用。
     */
    fun processThrough(samples: FloatArray, frames: Int, channels: Int) {
        val list = chain.get()
        if (list.isEmpty()) return
        for (loaded in list) {
            try {
                loaded.plugin.process(samples, frames, channels)
            } catch (e: Exception) {
                AppLogger.e(TAG, "DSP 插件 ${loaded.pkg.id} 处理异常，自动停用", e)
                disableInternal(loaded)
                break
            }
        }
    }

    /** 启用插件（后台加载 dex + init，完成后原子入链） */
    fun enable(context: Context, pkg: ExtPluginPackage, sampleRate: Int, channels: Int,
               onDone: (Boolean, String?) -> Unit) {
        Thread {
            try {
                synchronized(pool) {
                    if (pool.containsKey(pkg.id)) {
                        setChain(context)
                        onDone(true, null)
                        return@Thread
                    }
                    val plugin = DexPluginLoader.loadDspPlugin(context, pkg)
                    plugin.init(sampleRate, channels)
                    pool[pkg.id] = LoadedDsp(pkg, plugin)
                    setChain(context)
                    persist(context)
                    AppLogger.i(TAG, "DSP 插件已启用: ${pkg.id}")
                    onDone(true, null)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "DSP 插件启用失败: ${pkg.id}", e)
                onDone(false, e.message)
            }
        }.start()
    }

    /** 停用并释放插件 */
    fun disable(context: Context, id: String) {
        synchronized(pool) {
            pool.remove(id)?.plugin?.let { p ->
                try {
                    p.release()
                } catch (_: Exception) {
                }
            }
            setChain(context)
            persist(context)
            AppLogger.i(TAG, "DSP 插件已停用: $id")
        }
    }

    /** 由 pool 中启用的插件重建链（启用集合持久化于 maidmic_prefs） */
    private fun setChain(context: Context) {
        val prefs = context.getSharedPreferences("maidmic_prefs", Context.MODE_PRIVATE)
        val active = prefs.getStringSet(KEY_ACTIVE_IDS, emptySet()) ?: emptySet()
        val list = active.mapNotNull { id -> pool[id] }
        chain.set(list)
    }

    private fun persist(context: Context) {
        val prefs = context.getSharedPreferences("maidmic_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_ACTIVE_IDS, pool.keys.toSet()).apply()
    }

    private fun disableInternal(loaded: LoadedDsp) {
        synchronized(pool) {
            pool.remove(loaded.pkg.id)?.plugin?.let { p ->
                try {
                    p.release()
                } catch (_: Exception) {
                }
            }
            chain.updateAndGet { cur -> cur.filter { it.pkg.id != loaded.pkg.id } }
        }
    }

    /** 释放全部（App 退出/引擎重置） */
    fun releaseAll() {
        synchronized(pool) {
            pool.values.forEach { ld ->
                try {
                    ld.plugin.release()
                } catch (_: Exception) {
                }
            }
            pool.clear()
            chain.set(emptyList())
        }
    }
}
