// SoundEffectImporter.kt — 音效导入器
// ============================================================
// 提供两个导入入口，供 UI 调用，返回 ImportResult 供展示：
//   importFromUri(context, uri)  —— 单个音频文件导入 (SINGLE)
//   importFromZip(context, uri)  —— zip 音效包批量导入 (ZIP)
//
// Zip 音效包 manifest 规则 (可选，UTF-8 编码)：
//   {
//     "name": "音效包名",          // 仅记录/日志用
//     "group": "默认分组名",       // 未指定时音频条目归入 "未分组"
//     "description": "描述",       // 不存储
//     "icon": "..."                // 不存储，忽略
//   }
//   音频条目分组优先级：条目所在子目录名 > manifest.group > "未分组"。
//   manifest.json 解析失败不影响导入，仅记日志。
//
// 设计要点：
//   - 导入的音频文件一律以 <uuid>.<ext> 命名写入 effectDir，天然避免重名；
//     显示名若与已有音效完全重复则追加序号后缀 (如 "xx (2)")。
//   - 复制采用 64KB 块循环写入，避免大文件一次性读入内存。
//   - 时长用 MediaMetadataRetriever 获取，失败时为 0。

package aoeck.dwyai.com.soundeffect

import aoeck.dwyai.com.AppLogger
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * 导入结果，供 UI 展示。
 *
 * @param successCount 成功导入的条数。
 * @param failureCount 失败/跳过的条数。
 * @param errorMessage 致命错误 (如损坏的 zip) 时的描述，非致命失败时为 null。
 */
data class ImportResult(
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val errorMessage: String? = null,
)

/**
 * 音效导入器。所有方法均为静态式调用 (object)。
 */
object SoundEffectImporter {

    private const val TAG = "SoundEffectImporter"

    /** 复制缓冲区大小 (64KB)。 */
    private const val COPY_BUFFER_SIZE = 64 * 1024

    /** zip 内 manifest 文件名。 */
    private const val MANIFEST_NAME = "manifest.json"

    /** 缓冲区过大保护：单次复制上限 512MB，超出视为异常数据。 */
    private const val MAX_ENTRY_SIZE = 512L * 1024 * 1024

    // ============================================================
    // 入口 1：单个音频文件导入
    // ============================================================

    /**
     * 从 content:// uri 导入单个音频文件。
     * 失败时清理已写入的半成品文件，并返回 failure 结果。
     */
    fun importFromUri(context: Context, uri: Uri): ImportResult {
        // 1. 取显示名
        val displayName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: ""
        // 2. 校验扩展名
        val ext = extOf(displayName)
        if (ext.isEmpty() || ext !in SoundEffectStore.SUPPORTED_EXTS) {
            return ImportResult(failureCount = 1, errorMessage = "不支持的文件格式：$ext")
        }

        var targetFile: File? = null
        return try {
            val id = UUID.randomUUID().toString()
            val dir = SoundEffectStore.effectDir(context).apply { mkdirs() }
            targetFile = File(dir, "$id.$ext")

            // 3. 复制内容 (64KB 块，避免一次性读入大文件)
            copyInputStreamToFile(
                requireNotNull(context.contentResolver.openInputStream(uri)) {
                    "无法打开输入流：$uri"
                },
                targetFile,
            )

            // 4. 文件大小
            val fileSizeBytes = targetFile.length()

            // 5. 时长
            val durationMs = queryDurationMs(targetFile)

            // 6. 构造并保存
            val effect = SoundEffect(
                id = id,
                name = nameWithoutExt(displayName),
                fileName = SoundEffectStore.relativePath(id, ext),
                durationMs = durationMs,
                fileSizeBytes = fileSizeBytes,
                group = SoundEffect.DEFAULT_GROUP,
                sourceType = SoundEffectSource.SINGLE,
                createdAt = System.currentTimeMillis(),
            )
            SoundEffectStore.save(context, effect)
            AppLogger.i(TAG, "importFromUri: 成功导入 id=$id name=${effect.name}")
            ImportResult(successCount = 1)
        } catch (e: Exception) {
            AppLogger.e(TAG, "importFromUri: 导入失败 uri=$uri", e)
            cleanupQuietly(targetFile)
            ImportResult(failureCount = 1, errorMessage = "导入失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ============================================================
    // 入口 2：zip 音效包批量导入
    // ============================================================

    /**
     * 从 content:// uri 导入 zip 音效包。
     * 实现：第一遍遍历收集 manifest 内容与合法音频条目的完整条目名列表；
     * 第二遍重新打开输入流，按列表顺序逐个解压目标条目 (ZipInputStream 顺序流)。
     */
    fun importFromZip(context: Context, uri: Uri): ImportResult {
        // ---- 第一遍：预扫描 ----
        var manifestJson: JSONObject? = null
        val audioEntries = ArrayList<String>()
        var scanError: Exception? = null
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                val zis = ZipInputStream(BufferedInputStream(input))
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val entryName = entry.name
                    if (entry.isDirectory) {
                        zis.closeEntry()
                        continue
                    }
                    // 只收 manifest 与音频文件，其余 (如 icon、图片) 直接跳过
                    if (entryName == MANIFEST_NAME) {
                        // 读取全部 manifest 字节 (UTF-8)
                        manifestJson = readEntryText(zis)
                    } else if (extOf(entryName.substringAfterLast('/')) in SoundEffectStore.SUPPORTED_EXTS) {
                        audioEntries.add(entryName)
                    }
                    zis.closeEntry()
                }
            }
        } catch (e: Exception) {
            // ZipException 等 → 损坏
            AppLogger.e(TAG, "importFromZip: 预扫描失败 uri=$uri", e)
            scanError = e
        }
        if (scanError != null) {
            return ImportResult(failureCount = 0, errorMessage = "音效包已损坏或无法读取")
        }
        if (audioEntries.isEmpty()) {
            return ImportResult(failureCount = 0, errorMessage = "音效包中没有支持的音频文件")
        }

        // 解析 manifest (可选)：name 仅记录日志，group 作默认分组；description/icon 不存储
        val manifest = manifestJson
        manifest?.optString("name")?.let { AppLogger.i(TAG, "importFromZip: 音效包名=$it") }
        val manifestGroup = manifest?.optString("group")?.trim()?.ifEmpty { null }

        // ---- 第二遍：按列表顺序逐个解压导入 ----
        var success = 0
        var failure = 0
        val existingNames = SoundEffectStore.loadAll(context).map { it.name }.toMutableSet()
        val pending = ArrayDeque(audioEntries)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                val zis = ZipInputStream(BufferedInputStream(input))
                // 目标条目按出现顺序排列 (与第一遍收集顺序一致，保证线性扫描)
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val entryName = entry.name
                    if (entryName == MANIFEST_NAME) {
                        zis.closeEntry()
                        continue
                    }
                    if (pending.isNotEmpty() && entryName == pending.first()) {
                        pending.removeFirst()
                        if (importZipEntry(context, zis, entryName, manifestGroup, existingNames)) {
                            success++
                        } else {
                            failure++
                        }
                    }
                    zis.closeEntry()
                }
            }
        } catch (e: Exception) {
            // 中途损坏：已导入的保留，未处理的计入失败
            AppLogger.e(TAG, "importFromZip: 解压导入中断 uri=$uri", e)
            return ImportResult(
                successCount = success,
                failureCount = failure + pending.size,
                errorMessage = "音效包已损坏或无法读取",
            )
        }
        return ImportResult(successCount = success, failureCount = failure)
    }

    // ============================================================
    // 辅助：zip 条目导入
    // ============================================================

    /**
     * 解压当前 ZipInputStream 指向的音频条目并保存。
     * 分组：条目子目录名 > manifest.group > "未分组"。
     * 失败返回 false (已清理半成品文件)。
     */
    private fun importZipEntry(
        context: Context,
        zis: ZipInputStream,
        entryName: String,
        manifestGroup: String?,
        existingNames: MutableSet<String>,
    ): Boolean {
        // 扩展名校验
        val fileName = entryName.substringAfterLast('/')
        val ext = extOf(fileName)
        if (ext.isEmpty() || ext !in SoundEffectStore.SUPPORTED_EXTS) return false

        // 分组：子目录名 > manifest.group > "未分组"
        val slashIdx = entryName.lastIndexOf('/')
        val dirGroup = if (slashIdx > 0) entryName.substring(0, slashIdx).trim() else null
        val group = listOfNotNull(dirGroup, manifestGroup)
            .firstOrNull { it.isNotBlank() }
            ?.substringBefore('/') // 只取第一段子目录名
            ?.trim()
            ?: SoundEffect.DEFAULT_GROUP

        var targetFile: File? = null
        return try {
            val id = UUID.randomUUID().toString()
            val dir = SoundEffectStore.effectDir(context).apply { mkdirs() }
            targetFile = File(dir, "$id.$ext")

            // 解压写入 (限制单条目大小，防 zip 炸弹)
            copyStreamToFile(zis, targetFile)

            val fileSizeBytes = targetFile.length()
            val durationMs = queryDurationMs(targetFile)

            // 显示名去重：与已有完全重复则加序号后缀
            val displayName = nameWithoutExt(fileName)
            val uniqueName = dedupeName(displayName, existingNames)
            existingNames.add(uniqueName)

            val effect = SoundEffect(
                id = id,
                name = uniqueName,
                fileName = SoundEffectStore.relativePath(id, ext),
                durationMs = durationMs,
                fileSizeBytes = fileSizeBytes,
                group = group,
                sourceType = SoundEffectSource.ZIP,
                createdAt = System.currentTimeMillis(),
            )
            SoundEffectStore.save(context, effect)
            AppLogger.i(TAG, "importFromZip: 导入条目 $entryName -> id=$id group=$group")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "importFromZip: 条目导入失败 $entryName", e)
            cleanupQuietly(targetFile)
            false
        }
    }

    /**
     * 显示名去重：若与已有名字完全重复，追加 " (2)"、" (3)"... 序号后缀。
     */
    private fun dedupeName(base: String, existingNames: Set<String>): String {
        if (base !in existingNames) return base
        var idx = 2
        while (true) {
            val candidate = "$base ($idx)"
            if (candidate !in existingNames) return candidate
            idx++
        }
    }

    // ============================================================
    // 辅助：基础能力
    // ============================================================

    /** 通过 ContentResolver 查询文件的 DISPLAY_NAME。 */
    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "queryDisplayName 失败 uri=$uri", e)
            null
        }
    }

    /** 取文件名最后一个小数点后的后缀 (小写)。无扩展名返回空串。 */
    private fun extOf(fileName: String): String {
        val lastDot = fileName.lastIndexOf('.')
        return if (lastDot >= 0 && lastDot < fileName.length - 1) {
            fileName.substring(lastDot + 1).lowercase()
        } else {
            ""
        }
    }

    /** 去掉扩展名的原始文件名。 */
    private fun nameWithoutExt(fileName: String): String {
        val lastDot = fileName.lastIndexOf('.')
        return if (lastDot > 0) fileName.substring(0, lastDot) else fileName
    }

    /** 用 MediaMetadataRetriever 查询音频时长 (ms)，失败返回 0。 */
    private fun queryDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val text = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            text?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            AppLogger.w(TAG, "queryDurationMs 失败 file=${file.name}", e)
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 读取当前 ZipInputStream 条目的全部内容为 UTF-8 文本。 */
    private fun readEntryText(zis: ZipInputStream): JSONObject? {
        return try {
            val bytes = readAllLimited(zis)
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            AppLogger.w(TAG, "readEntryText: manifest 解析失败，忽略", e)
            null
        }
    }

    /** 读取当前流全部字节 (带大小上限保护)。 */
    private fun readAllLimited(zis: ZipInputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = zis.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_SIZE) throw ZipException("条目内容过大，疑似 zip 炸弹")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 把 ContentResolver 输入流复制到目标文件 (64KB 块)。 */
    private fun copyInputStreamToFile(input: java.io.InputStream, target: File) {
        BufferedInputStream(input).use { bis ->
            BufferedOutputStream(FileOutputStream(target)).use { bos ->
                copyLoop(bis, bos)
            }
        }
    }

    /** 把 ZipInputStream 当前条目内容复制到目标文件 (64KB 块)。 */
    private fun copyStreamToFile(zis: ZipInputStream, target: File) {
        BufferedOutputStream(FileOutputStream(target)).use { bos ->
            copyLoop(zis, bos)
        }
    }

    /** 循环复制字节，带总大小上限保护。 */
    private fun copyLoop(input: java.io.InputStream, output: java.io.OutputStream) {
        val buf = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_SIZE) throw ZipException("文件内容过大")
            output.write(buf, 0, n)
        }
        output.flush()
    }

    /** 静默删除半成品文件 (不存在则忽略)。 */
    private fun cleanupQuietly(file: File?) {
        if (file != null && file.exists()) {
            runCatching { file.delete() }
        }
    }
}
