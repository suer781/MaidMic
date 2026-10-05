// plugins/core/DspPluginChain.kt — DSP 插件链（Tier 2 实时处理）
// ============================================================
// 管理已加载/启用的 DspAudioPlugin 实例，并挂接进音频处理路径：
//   NativeAudioProcessor.processAudio → 引擎管线 → DSP 插件链（串行）
//
// 线程模型：
//   - process() 可能在多个音频路径并发调用，用 processLock 串行化；
//     只读 activePlugins 快照（原子引用），不加锁、不分配（实例的 process 实现方负责预分配）
//   - enable/disable/restore 在 UI/后台线程调用：构建新链表后原子替换
//   - 插件异常时在音频线程摘链，release() 切到主线程 Handler 执行
//
// 容错：单插件 process 抛异常 → 自动停用该插件（防止坏插件持续打断音频）。

package aoeck.dwyai.com.plugins.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import aoeck.dwyai.com.AppLogger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

object DspPluginChain {

    private const val TAG = "DspPluginChain"
    private const val KEY_ACTIVE_IDS = "dsp_plugin_active"

    /** 链上插件（顺序即处理顺序） */
    data class LoadedDsp(val pkg: ExtPluginPackage, val plugin: DspAudioPlugin)

    /** 原子快照（音频线程无锁读取） */
    private val chain = AtomicReference<List<LoadedDsp>>(emptyList())

    /** 已加载实例缓存（id → LoadedDsp），供启用/停用切换 */
    private val pool = HashMap<String, LoadedDsp>()

    /** 当前采样率/声道（重配置用） */
    @Volatile private var currentSampleRate = 48000
    @Volatile private var currentChannels = 1

    /** 应用上下文（disableInternal 持久化用） */
    @Volatile private var appContext: Context? = null

    /** 主线程 Handler（release 切线程） */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** process 串行化锁（多音频路径并发时防止插件内部状态竞争） */
    private val processLock = ReentrantLock()

    /** 当前链（UI 只读） */
    fun snapshot(): List<LoadedDsp> = chain.get()

    /**
     * 音频线程入口：按链顺序原地处理。
     * 引擎（Echio / 直通）处理完后调用。
     * 多路径并发时串行化，避免插件实例被并发调用。
     */
    fun processThrough(samples: FloatArray, frames: Int, channels: Int) {
        val list = chain.get()
        if (list.isEmpty()) return
        if (!processLock.tryLock()) {
            // 拿不到锁说明另一音频路径正在处理：阻塞等待，保证串行
            processLock.lock()
        }
        try {
            for (loaded in list) {
                try {
                    loaded.plugin.process(samples, frames, channels)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "DSP 插件 ${loaded.pkg.id} 处理异常，自动停用", e)
                    disableInternal(loaded)
                    break
                }
            }
        } finally {
            processLock.unlock()
        }
    }

    /** 启用插件（后台加载 dex + init，完成后原子入链） */
    fun enable(context: Context, pkg: ExtPluginPackage, sampleRate: Int, channels: Int,
               onDone: (Boolean, String?) -> Unit) {
        appContext = context.applicationContext
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
                    currentSampleRate = sampleRate
                    currentChannels = channels
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
        appContext = context.applicationContext
        synchronized(pool) {
            pool.remove(id)?.plugin?.let { p ->
                val toRelease = p
                mainHandler.post { runCatching { toRelease.release() } }
            }
            setChain(context)
            persist(context)
            AppLogger.i(TAG, "DSP 插件已停用: $id")
        }
    }

    /**
     * 启动恢复：根据持久化 id 重新加载上次启用的 DSP 插件。
     * 在 UGC 已开启时调用（否则不加载任意 dex）。
     */
    fun restore(context: Context, sampleRate: Int, channels: Int) {
        appContext = context.applicationContext
        if (!PluginSecurity.isUgcEnabled(context)) return
        val prefs = context.getSharedPreferences("maidmic_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_ACTIVE_IDS, emptySet()) ?: emptySet()
        if (saved.isEmpty()) return
        val packages = DexPluginLoader.scan(context)
        val byId = packages.associateBy { it.id }
        saved.forEach { id ->
            val pkg = byId[id] ?: return@forEach
            if (pool.containsKey(id)) return@forEach
            enable(context, pkg, sampleRate, channels) { ok, msg ->
                if (!ok) AppLogger.e(TAG, "恢复 DSP 插件失败: $id $msg")
            }
        }
    }

    /**
     * 采样率/声道变化时重配所有已加载插件（重新调用 init）。
     * 变化时同步更新 currentSampleRate/currentChannels。
     */
    fun reconfigure(sampleRate: Int, channels: Int) {
        if (sampleRate == currentSampleRate && channels == currentChannels) return
        val list = chain.get()
        if (list.isEmpty()) {
            currentSampleRate = sampleRate
            currentChannels = channels
            return
        }
        synchronized(pool) {
            list.forEach { ld ->
                runCatching { ld.plugin.init(sampleRate, channels) }
                    .onFailure { e ->
                        AppLogger.e(TAG, "DSP 插件 ${ld.pkg.id} 重配失败: ${e.message}", e)
                        disableInternal(ld)
                    }
            }
            currentSampleRate = sampleRate
            currentChannels = channels
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
                val toRelease = p
                mainHandler.post { runCatching { toRelease.release() } }
            }
            chain.updateAndGet { cur -> cur.filter { it.pkg.id != loaded.pkg.id } }
        }
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences("maidmic_prefs", Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_ACTIVE_IDS, pool.keys.toSet()).apply()
        }
    }

    /** 释放全部（App 退出/引擎重置） */
    fun releaseAll() {
        synchronized(pool) {
            pool.values.forEach { ld ->
                val toRelease = ld.plugin
                mainHandler.post { runCatching { toRelease.release() } }
            }
            pool.clear()
            chain.set(emptyList())
        }
    }
}