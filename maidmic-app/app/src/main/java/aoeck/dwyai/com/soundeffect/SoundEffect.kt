// SoundEffect.kt — 音效数据模型
// ============================================================
// MaidMic "音效" = 一条导入的音频 (wav/mp3/flac/ogg/m4a/aac) + 元数据。
// 用于：音效列表管理、音效分组、音效快捷触发播放。
//
// 设计要点：
//   - 纯数据 data class，无 Android 依赖，方便序列化与测试。
//   - fileName 是相对 filesDir 的相对路径，形如 "sound_effects/<id>.<ext>"。
//   - sourceType 区分单个导入与 zip 批量导入两种来源。
//   - group 默认 "未分组"，按分组维度展示音效列表。

package aoeck.dwyai.com.soundeffect

/**
 * 音效来源类型。
 *
 * @param SINGLE 单个文件导入。
 * @param ZIP zip 压缩包批量导入。
 */
enum class SoundEffectSource {
    SINGLE,
    ZIP,
}

/**
 * 音效元数据。
 *
 * @param id 唯一 ID (UUID 字符串)。
 * @param name 显示名，默认 "音效 yyyy-MM-dd HH:mm"。
 * @param fileName 音频文件相对路径，形如 "sound_effects/<id>.<ext>"。
 * @param durationMs 音频时长 (毫秒)。
 * @param fileSizeBytes 文件大小 (字节)。
 * @param group 分组名，默认 "未分组"。
 * @param sourceType 来源类型 (单个导入 / zip 批量导入)。
 * @param createdAt 创建时间戳 (System.currentTimeMillis())。
 */
data class SoundEffect(
    val id: String,
    val name: String,
    val fileName: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val group: String = DEFAULT_GROUP,
    val sourceType: SoundEffectSource,
    val createdAt: Long,
) {
    companion object {
        /** 默认分组名。 */
        const val DEFAULT_GROUP = "未分组"
    }
}
