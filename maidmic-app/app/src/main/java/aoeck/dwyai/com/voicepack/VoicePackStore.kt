// VoicePackStore.kt — 语音包元数据 + WAV 文件存储
// ============================================================
// 负责 VoicePack 的持久化：
//   - 元数据：SharedPreferences("maidmic_voicepacks") 存一个 JSON 数组 (org.json)。
//   - WAV 文件：context.filesDir/voicepacks/<id>.wav
//
// API：
//   loadAll(context)             —— 读取全部语音包 (按 createdAt 升序)
//   save(context, pack)          —— 新增/覆盖一条语音包元数据 (不写 WAV，WAV 由 WavWriter 写)
//   delete(context, id)          —— 删除 WAV 文件 + 元数据
//   rename(context, id, newName) —— 仅改元数据名
//   getLatest(context)           —— 最近一条 (悬浮球双击播放用)
//
// 设计要点：
//   - 无外部依赖：org.json + SharedPreferences。
//   - JSON 往返不丢数据：嵌套 ChainSnapshot/ModuleState/params 全部序列化。
//   - 线程安全：SharedPreferences.commit 同步落盘；内存列表操作加锁。
//   - 容错：单条 JSON 解析失败不污染整列表，跳过并记录日志。

package aoeck.dwyai.com.voicepack

import aoeck.dwyai.com.AppLogger
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 语音包存储。所有方法均为静态式调用 (object)。
 */
object VoicePackStore {

    private const val TAG = "VoicePackStore"

    /** SharedPreferences 名。 */
    private const val PREFS_NAME = "maidmic_voicepacks"

    /** 存放 JSON 数组的 key。 */
    private const val KEY_PACKS = "packs_json"

    /** WAV 文件子目录名 (相对 filesDir)。 */
    const val VOICEPACK_DIR = "voicepacks"

    // ============================================================
    // 公共 API
    // ============================================================

    /** 读取全部语音包，按 createdAt 升序 (旧的在前)。 */
    fun loadAll(context: Context): List<VoicePack> {
        val json = prefs(context).getString(KEY_PACKS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<VoicePack>(arr.length())
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.optJSONObject(i) ?: continue
                    out += jsonToVoicePack(obj)
                } catch (e: Exception) {
                    // 单条损坏不污染整列表
                    AppLogger.e(TAG, "loadAll: 第 $i 条解析失败，跳过", e)
                }
            }
            out.sortedBy { it.createdAt }
        } catch (e: Exception) {
            AppLogger.e(TAG, "loadAll: JSON 解析失败", e)
            emptyList()
        }
    }

    /**
     * 新增或覆盖一条语音包元数据。
     * 注意：本方法只写元数据，不写 WAV 文件 —— WAV 由调用方通过 [WavWriter] 写入。
     * 若同 id 已存在则覆盖。
     */
    fun save(context: Context, pack: VoicePack) {
        synchronized(this) {
            val all = loadAll(context).toMutableList()
            val idx = all.indexOfFirst { it.id == pack.id }
            if (idx >= 0) all[idx] = pack else all += pack
            persist(context, all)
        }
        AppLogger.i(TAG, "save: ${pack.id} name=${pack.name} dur=${pack.durationMs}ms")
    }

    /**
     * 删除指定 id 的语音包：删 WAV 文件 + 删元数据。
     * 不存在时静默返回 false。
     */
    fun delete(context: Context, id: String): Boolean {
        synchronized(this) {
            val all = loadAll(context).toMutableList()
            val idx = all.indexOfFirst { it.id == id }
            if (idx < 0) return false
            val pack = all.removeAt(idx)
            persist(context, all)

            // 删 WAV 文件
            val wav = wavFile(context, pack.wavFile)
            if (wav.exists()) {
                val ok = wav.delete()
                AppLogger.i(TAG, "delete: ${pack.id} wav=${wav.name} ok=$ok")
            }
        }
        return true
    }

    /**
     * 重命名指定 id 的语音包 (仅改元数据)。
     * @return 是否成功 (id 不存在则 false)。
     */
    fun rename(context: Context, id: String, newName: String): Boolean {
        synchronized(this) {
            val all = loadAll(context).toMutableList()
            val idx = all.indexOfFirst { it.id == id }
            if (idx < 0) return false
            val renamed = all[idx].copy(name = newName)
            all[idx] = renamed
            persist(context, all)
        }
        AppLogger.i(TAG, "rename: $id -> $newName")
        return true
    }

    /**
     * 获取最近一条语音包 (按 createdAt 降序取第一个)。
     * 供悬浮球双击播放使用。无数据返回 null。
     */
    fun getLatest(context: Context): VoicePack? {
        return loadAll(context).maxByOrNull { it.createdAt }
    }

    // ============================================================
    // 路径辅助
    // ============================================================

    /** voicepacks 子目录的绝对路径 File。 */
    fun voicepackDir(context: Context): File {
        return File(context.filesDir, VOICEPACK_DIR)
    }

    /**
     * 根据 [wavFile] 相对路径解析出绝对 File。
     * wavFile 形如 "voicepacks/<id>.wav"，相对 filesDir。
     */
    fun wavFile(context: Context, wavFile: String): File {
        val f = File(wavFile)
        return if (f.isAbsolute) f else File(context.filesDir, wavFile)
    }

    /**
     * 为新语音包生成相对 WAV 路径："voicepacks/<id>.wav"。
     */
    fun relativeWavPath(id: String): String = "$VOICEPACK_DIR/$id.wav"

    /**
     * 生成默认显示名："语音包 yyyy-MM-dd HH:mm"。
     */
    fun defaultName(createdAt: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return "语音包 ${sdf.format(Date(createdAt))}"
    }

    // ============================================================
    // JSON 序列化 / 反序列化
    // ============================================================

    private fun voicePackToJson(pack: VoicePack): JSONObject {
        val obj = JSONObject()
        obj.put("id", pack.id)
        obj.put("name", pack.name)
        obj.put("wavFile", pack.wavFile)
        obj.put("durationMs", pack.durationMs)
        obj.put("sampleRate", pack.sampleRate)
        obj.put("createdAt", pack.createdAt)

        // chainSnapshot 嵌套
        val snap = JSONObject()
        snap.put("engine", pack.chainSnapshot.engine)
        val mods = JSONArray()
        for (m in pack.chainSnapshot.modules) {
            val mo = JSONObject()
            mo.put("moduleId", m.moduleId)
            mo.put("bypass", m.bypass)
            val po = JSONObject()
            for ((k, v) in m.params) {
                po.put(k, v.toDouble())
            }
            mo.put("params", po)
            mods.put(mo)
        }
        snap.put("modules", mods)
        obj.put("chainSnapshot", snap)
        return obj
    }

    private fun jsonToVoicePack(obj: JSONObject): VoicePack {
        val snapObj = obj.getJSONObject("chainSnapshot")
        val modsArr = snapObj.optJSONArray("modules") ?: JSONArray()
        val mods = ArrayList<ModuleState>(modsArr.length())
        for (i in 0 until modsArr.length()) {
            val mo = modsArr.getJSONObject(i)
            val po = mo.optJSONObject("params") ?: JSONObject()
            val params = HashMap<String, Float>(po.length())
            val keys = po.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                params[k] = po.opt(k)?.toString()?.toFloatOrNull() ?: 0f
            }
            mods += ModuleState(
                moduleId = mo.getInt("moduleId"),
                params = params,
                bypass = mo.optBoolean("bypass", false),
            )
        }
        return VoicePack(
            id = obj.getString("id"),
            name = obj.getString("name"),
            wavFile = obj.getString("wavFile"),
            durationMs = obj.optLong("durationMs", 0L),
            sampleRate = obj.optInt("sampleRate", 48000),
            createdAt = obj.optLong("createdAt", 0L),
            chainSnapshot = ChainSnapshot(
                engine = snapObj.optString("engine", "ECHIO_EQ"),
                modules = mods,
            ),
        )
    }

    // ============================================================
    // 内部：持久化
    // ============================================================

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 把整个列表序列化为 JSON 数组并同步落盘 (commit)。 */
    private fun persist(context: Context, all: List<VoicePack>) {
        val arr = JSONArray()
        for (p in all) {
            try {
                arr.put(voicePackToJson(p))
            } catch (e: Exception) {
                AppLogger.e(TAG, "persist: 序列化失败 id=${p.id}，跳过", e)
            }
        }
        prefs(context).edit().putString(KEY_PACKS, arr.toString()).commit()
    }
}
