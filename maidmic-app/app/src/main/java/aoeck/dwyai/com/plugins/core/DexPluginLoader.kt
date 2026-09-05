// plugins/core/DexPluginLoader.kt — dex/apk 插件加载器
// ============================================================
// 从插件目录（<externalFilesDir>/maidmic_plugins_ext/）加载 .apk/.dex/.jar
// 形式的 Tier 2/3 插件：
//
//   插件包结构（zip/apk）：
//     classes.dex            实现接口的 Kotlin/Java 类
//     plugin.json            清单：{ "id": "...", "entry": "com.x.MyPlugin" }
//
// 加载机制：DexClassLoader + 优化缓存目录（codeCacheDir/plugin_dex）。
// 安全：仅在 UGC 开启时由上层调用；加载的代码拥有 App 全部权限——
//       这就是权限分级里 NATIVE 级（风险自担）的原因。

package aoeck.dwyai.com.plugins.core

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File
import org.json.JSONObject

/** 扫描到的插件包描述 */
data class ExtPluginPackage(
    val file: File,
    val id: String,
    val entryClass: String,
    val name: String,
    val description: String,
    val author: String,
)

object DexPluginLoader {

    private const val EXT_DIR_NAME = "maidmic_plugins_ext"

    /** 插件包目录 */
    fun extDir(context: Context): File =
        File(context.getExternalFilesDir(null), EXT_DIR_NAME).apply { mkdirs() }

    /** 扫描目录下所有插件包并解析清单（无 plugin.json 或缺 entry 的包跳过） */
    fun scan(context: Context): List<ExtPluginPackage> {
        val out = mutableListOf<ExtPluginPackage>()
        extDir(context).listFiles { f ->
            f.isFile && (f.extension == "apk" || f.extension == "dex" || f.extension == "jar")
        }?.sortedBy { it.name }?.forEach { f ->
            parseManifest(f)?.let { out += it }
        }
        return out
    }

    /** 读取包内 plugin.json 清单（apk/jar 从 zip 内读；裸 dex 从同名 .json 读） */
    private fun parseManifest(file: File): ExtPluginPackage? {
        return try {
            if (file.extension == "apk" || file.extension == "jar" || file.extension == "zip") {
                java.util.zip.ZipFile(file).use { zip ->
                    val entry = zip.getEntry("plugin.json") ?: return null
                    val json = zip.getInputStream(entry).bufferedReader().readText()
                    fromJson(file, JSONObject(json))
                }
            } else {
                val meta = File(file.parentFile, file.nameWithoutExtension + ".json")
                if (meta.exists()) fromJson(file, JSONObject(meta.readText())) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fromJson(file: File, json: JSONObject): ExtPluginPackage? {
        val entry = json.optString("entry")
        if (entry.isBlank()) return null
        return ExtPluginPackage(
            file = file,
            id = json.optString("id", file.nameWithoutExtension),
            entryClass = entry,
            name = json.optString("name", file.nameWithoutExtension),
            description = json.optString("description", ""),
            author = json.optString("author", "未知"),
        )
    }

    /**
     * 实例化插件包内实现 [iface] 的类。
     * @throws Exception 类缺失 / 不是目标接口 / 构造失败
     */
    private fun instantiate(context: Context, pkg: ExtPluginPackage): Any {
        val dexOut = File(context.codeCacheDir, "plugin_dex").apply { mkdirs() }
        val loader = DexClassLoader(
            pkg.file.absolutePath,
            dexOut.absolutePath,
            null,
            context.classLoader,
        )
        val cls = loader.loadClass(pkg.entryClass)
        return cls.getDeclaredConstructor().newInstance()
    }

    /** 实例化 DSP 插件（Tier 2） */
    fun loadDspPlugin(context: Context, pkg: ExtPluginPackage): DspAudioPlugin =
        instantiate(context, pkg) as DspAudioPlugin

    /** 实例化模型插件（Tier 3） */
    fun loadModelPlugin(context: Context, pkg: ExtPluginPackage): ModelVoicePlugin =
        instantiate(context, pkg) as ModelVoicePlugin

    /** 无副作用探测：加载 entry 类并检查是否实现 DSP/模型接口 */
    fun probe(context: Context, pkg: ExtPluginPackage): Pair<Boolean, Boolean> {
        return try {
            val dexOut = File(context.codeCacheDir, "plugin_dex").apply { mkdirs() }
            val loader = DexClassLoader(
                pkg.file.absolutePath,
                dexOut.absolutePath,
                null,
                context.classLoader,
            )
            val cls = loader.loadClass(pkg.entryClass)
            Pair(
                DspAudioPlugin::class.java.isAssignableFrom(cls),
                ModelVoicePlugin::class.java.isAssignableFrom(cls),
            )
        } catch (e: Exception) {
            Pair(false, false)
        }
    }
}
