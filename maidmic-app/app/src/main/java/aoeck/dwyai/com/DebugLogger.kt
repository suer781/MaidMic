// DebugLogger.kt — MaidMic 应用内日志系统
// ============================================================
// 环形缓冲区 + 分级日志，可在开发者设置页查看。
// 支持 logcat 输出 + 内存缓存双通道。

package aoeck.dwyai.com

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel(val tag: String) {
    DEBUG("D"),
    INFO("I"),
    WARN("W"),
    ERROR("E")
}

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,       // 模块名
    val message: String,
    val throwable: String? = null,
    val thread: String = Thread.currentThread().name  // 线程名
) {
    val formattedTime: String
        get() = dateFormat.format(Date(timestamp))

    companion object {
        private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }
}

/**
 * MaidMic 应用内日志记录器。
 * 同时输出到 logcat（方便 adb logcat 追踪）
 * 和内存环形缓冲区（方便在开发者设置页查看）。
 *
 * 使用示例：
 *   AppLogger.i("EqPage", "用户点击了测试按钮")
 *   AppLogger.e("AudioInit", "录音初始化失败", exception)
 */
object AppLogger {

    /** 最大内存日志条目数 */
    private const val MAX_ENTRIES = 500

    /** 环形缓冲区 */
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)

    /** 添加日志条目 */
    private fun add(entry: LogEntry) {
        synchronized(buffer) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirstOrNull()
            }
            buffer.addLast(entry)
        }
    }

    /** 获取全部日志（从旧到新） */
    fun getAll(): List<LogEntry> = synchronized(buffer) {
        buffer.toList()
    }

    /** 获取指定级别的日志 */
    fun getByLevel(level: LogLevel): List<LogEntry> = synchronized(buffer) {
        buffer.filter { it.level == level }
    }

    /** 获取最近 N 条日志 */
    fun getRecent(n: Int): List<LogEntry> = synchronized(buffer) {
        buffer.takeLast(n.coerceAtMost(buffer.size))
    }

    /** 清空日志 */
    fun clear() = synchronized(buffer) {
        buffer.clear()
    }

    /** DEBUG */
    fun d(tag: String, msg: String) {
        Log.d("MaidMic/$tag", msg)
        add(LogEntry(level = LogLevel.DEBUG, tag = tag, message = msg))
    }

    /** INFO */
    fun i(tag: String, msg: String) {
        Log.i("MaidMic/$tag", msg)
        add(LogEntry(level = LogLevel.INFO, tag = tag, message = msg))
    }

    /** WARN */
    fun w(tag: String, msg: String, t: Throwable? = null) {
        Log.w("MaidMic/$tag", msg, t)
        add(LogEntry(level = LogLevel.WARN, tag = tag, message = msg,
            throwable = t?.message))
    }

    /** ERROR */
    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e("MaidMic/$tag", msg, t)
        add(LogEntry(level = LogLevel.ERROR, tag = tag, message = msg,
            throwable = t?.message))
    }

    /** 记录设备/系统信息 */
    fun logDeviceInfo(context: android.content.Context) {
        val info = buildString {
            appendLine("===== 设备信息 =====")
            appendLine("型号: ${android.os.Build.MODEL}")
            appendLine("厂商: ${android.os.Build.MANUFACTURER}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("包名: ${context.packageName}")
            try {
                val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                appendLine("版本: ${pkgInfo.versionName} (${pkgInfo.longVersionCode})")
            } catch (_: Exception) {}
            appendLine("===== 日志开始 =====")
        }
        i("System", info.trimEnd())
    }
}
