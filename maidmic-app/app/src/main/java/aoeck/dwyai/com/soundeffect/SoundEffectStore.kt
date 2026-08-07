// SoundEffectStore.kt — 音效元数据 + 音频文件存储
// ============================================================
// 负责 SoundEffect 的持久化：
//   - 元数据：SharedPreferences("maidmic_sound_effects") 存一个 JSON 数组 (org.json)。
//   - 音频文件：context.filesDir/sound_effects/<id>.<ext>
//
// API：
//   loadAll(context)                —— 读取全部音效 (按 createdAt 升序)
//   save(context, effect)           —— 新增/覆盖一条音效元数据
//   delete(context, id)             —— 删除音频文件 + 元数据
//   rename(context, id, newName)    —— 仅改元数据名
//   updateGroup(context, id, group) —— 修改分组
//   search(context, query)          —— 按名称包含匹配搜索
//   groups(context)                 —— 全部分组名去重列表
//   effectDir(context)              —— filesDir/sound_effects 目录
//   effectFile(context, fileName)   —— 按相对路径解析绝对 File
//   relativePath(id, ext)           —— 生成 "sound_effects/<id>.<ext>"
//   defaultName(createdAt)          —— "音效 yyyy-MM-dd HH:mm"
//
// 设计要点：
//   - 无外部依赖：org.json + SharedPreferences。
//   - 线程安全：SharedPreferences.commit 同步落盘；内存列表操作加锁。
//   - 容错：单条 JSON 解析失败不污染整列表，跳过并记录日志。

package aoeck.dwyai.com.soundeffect

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
 * 音效存储。所有方法均为静态式调用 (object)。
 */
object SoundEffectStore {

    private const val TAG = "SoundEffectStore"

    /** SharedPreferences 名。 */
    private const val PREFS_NAME = "maidmic_sound_effects"

    /** 存放 JSON 数组的 key。 */
    private const val KEY_EFFECTS = "effects_json"

    /** 音频文件子目录名 (相对 filesDir)。 */
    const val EFFECT_DIR = "sound_effects"

    /** 支持的音频扩展名 (小写)。 */
    val SUPPORTED_EXTS = setOf("wav", "mp3", "flac", "ogg", "m4a", "aac")

    /** 默认分组名 (与 SoundEffect.DEFAULT_GROUP 一致)。 */
    private const val DEFAULT_GROUP = SoundEffect.DEFAULT_GROUP

    // ============================================================
    // 公共 API
    // ============================================================

    /** 读取全部音效，按 createdAt 升序 (旧的在前)。 */
    fun loadAll(context: Context): List<SoundEffect> {
        val json = prefs(context).getString(KEY_EFFECTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<SoundEffect>(arr.length())
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.optJSONObject(i) ?: continue
                    out += jsonToSoundEffect(obj)
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
     * 新增或覆盖一条音效元数据。
     * 注意：本方法只写元数据，不写音频文件 —— 音频文件由调用方负责写入 effectDir。
     * 若同 id 已存在则覆盖。
     */
    fun save(context: Context, effect: SoundEffect) {
        synchronized(this) {
            val all = loadAll(context).toMutableList()
            val idx = all.indexOfFirst { it.id == effect.id }
            if (idx >= 0) all[idx] = effect else all += effect
            persist(context, all)
        }
        AppLogger.i(TAG, "save: ${effect.id} name=${effect.name} group=${effect.group}")
    }

    /**
     * 删除指定 id 的音效：删音频文件 + 删元数据。
     * 不存在时静默返回 false。
     */
    fun delete(context: Context, id: String): Boolean {
        synchronized(this) {
            val all = loadAll(context).toMutableList()
            val idx = all.indexOfFirst { it.id == id }
            if (idx < 0) return false
            val effect = all.removeAt(idx)
            persist(context, all)

            // 删音频文件
            val file = effectFile(context, effect.fileName)
            if (file.exists()) {
                val ok = file.delete()
                AppLogger.i(TAG, "delete: ${effect.id} file=${file.name} ok=$ok")
            }
        }
        return true
    }

    /**
     * 重命名指定 id 的音效 (仅改元数据)。
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
     * 修改指定 id 音效的分组 (仅改元数据)。
     * 空白分组名会归一为默认分组 [DEFAULT_GROUP]，避免产生空分组。
     * @return 是否成功 (id 不存在则 false)。
     */
    fun updateGroup(context: Context, id: String, newGroup: String): Boolean {
        val group = newGroup.trim().ifEmpty { DEFAULT_GROUP }
        synchronized(this) {
            val all = loadAll(context).toMutableList()
            val idx = all.indexOfFirst { it.id == id }
            if (idx < 0) return false
            val updated = all[idx].copy(group = group)
            all[idx] = updated
            persist(context, all)
        }
        AppLogger.i(TAG, "updateGroup: $id -> $group")
        return true
    }

    /**
     * 按名称包含匹配搜索音效 (忽略大小写)。
     * 空白查询返回空列表。
     */
    fun search(context: Context, query: String): List<SoundEffect> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val lower = q.lowercase(Locale.getDefault())
        return loadAll(context).filter { it.name.contains(lower, ignoreCase = true) }
    }

    /** 返回所有分组名去重列表 (保持首次出现顺序，剔除空白分组)。 */
    fun groups(context: Context): List<String> {
        return loadAll(context).map { it.group }.filter { it.isNotBlank() }.distinct()
    }

    // ============================================================
    // 路径辅助
    // ============================================================

    /** sound_effects 子目录的绝对路径 File。 */
    fun effectDir(context: Context): File {
        return File(context.filesDir, EFFECT_DIR)
    }

    /**
     * 根据 [fileName] 相对路径解析出绝对 File。
     * fileName 形如 "sound_effects/<id>.<ext>"，相对 filesDir；也支持直接传绝对路径。
     */
    fun effectFile(context: Context, fileName: String): File {
        val f = File(fileName)
        return if (f.isAbsolute) f else File(context.filesDir, fileName)
    }

    /**
     * 为新音效生成相对文件路径："sound_effects/<id>.<ext>"。
     */
    fun relativePath(id: String, ext: String): String = "$EFFECT_DIR/$id.$ext"

    /**
     * 生成默认显示名："音效 yyyy-MM-dd HH:mm"。
     */
    fun defaultName(createdAt: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return "音效 ${sdf.format(Date(createdAt))}"
    }

    // ============================================================
    // JSON 序列化 / 反序列化
    // ============================================================

    private fun soundEffectToJson(effect: SoundEffect): JSONObject {
        val obj = JSONObject()
        obj.put("id", effect.id)
        obj.put("name", effect.name)
        obj.put("fileName", effect.fileName)
        obj.put("durationMs", effect.durationMs)
        obj.put("fileSizeBytes", effect.fileSizeBytes)
        obj.put("group", effect.group)
        obj.put("sourceType", effect.sourceType.name)
        obj.put("createdAt", effect.createdAt)
        return obj
    }

    private fun jsonToSoundEffect(obj: JSONObject): SoundEffect {
        return SoundEffect(
            id = obj.getString("id"),
            name = obj.getString("name"),
            fileName = obj.getString("fileName"),
            durationMs = obj.optLong("durationMs", 0L),
            fileSizeBytes = obj.optLong("fileSizeBytes", 0L),
            group = obj.optString("group", DEFAULT_GROUP).ifBlank { DEFAULT_GROUP },
            sourceType = runCatching {
                SoundEffectSource.valueOf(obj.optString("sourceType", SoundEffectSource.SINGLE.name))
            }.getOrDefault(SoundEffectSource.SINGLE),
            createdAt = obj.optLong("createdAt", 0L),
        )
    }

    // ============================================================
    // 内部：持久化
    // ============================================================

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 把整个列表序列化为 JSON 数组并同步落盘 (commit)。 */
    private fun persist(context: Context, all: List<SoundEffect>) {
        val arr = JSONArray()
        for (e in all) {
            try {
                arr.put(soundEffectToJson(e))
            } catch (ex: Exception) {
                AppLogger.e(TAG, "persist: 序列化失败 id=${e.id}，跳过", ex)
            }
        }
        prefs(context).edit().putString(KEY_EFFECTS, arr.toString()).commit()
    }
}
