// examples/dsp-plugin 构建脚本
// 用法：本目录下 ./gradlew assembleRelease → build/outputs/plugin_ringmod.apk
// 产物直接拷贝到手机 Android/data/aoeck.dwyai.com/files/maidmic_plugins_ext/

import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.library") version "8.2.2"
    id("org.jetbrains.kotlin.android") version "1.9.22"
}

// 本机用户级 gradle.properties 为规避主工程 CMake 中文路径问题重定向了 buildDir。
// 本示例无 CMake，固定回项目 build/ 目录，保证产物路径与文档一致。
buildDir = file("build")

// 解析 Android SDK 路径：优先 local.properties 的 sdk.dir，其次 ANDROID_HOME / ANDROID_SDK_ROOT。
fun findSdkDir(): File {
    val localProps = rootProject.file("local.properties")
    if (localProps.exists()) {
        val props = Properties()
        localProps.inputStream().use { props.load(it) }
        val sdkDir = props.getProperty("sdk.dir")
        if (!sdkDir.isNullOrBlank()) {
            return File(sdkDir)
        }
    }
    val env = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (!env.isNullOrBlank()) {
        return File(env)
    }
    throw IllegalStateException(
        "找不到 Android SDK：请在 local.properties 中设置 sdk.dir（如 sdk.dir=C\\:\\\\AndroidSdk）" +
            "或设置 ANDROID_HOME 环境变量"
    )
}

fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

android {
    namespace = "com.example.maidmic.plugin"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 接口由宿主运行时提供（见 src/.../core/DspAudioPlugin.kt 副本），无需外部依赖
}

// ---- 打包任务：AAR → classes.jar → d8 → classes.dex → plugin_ringmod.apk ----
val makePluginApk = tasks.register("makePluginApk") {
    group = "maidmic"
    description = "将 release AAR 转为 plugin_ringmod.apk（classes.dex + plugin.json）"
    dependsOn("bundleReleaseAar") // 字符串形式延迟解析，确保 AAR 已生成
    doLast {
        val aarFile = layout.buildDirectory.get()
            .file("outputs/aar/dsp-plugin-release.aar").asFile
        if (!aarFile.exists()) throw IllegalStateException("AAR 不存在: $aarFile")
        val work = layout.buildDirectory.get().dir("plugin_work").asFile
        work.mkdirs()

        // 1. 解出 classes.jar
        val classesJar = File(work, "classes.jar")
        ZipFile(aarFile).use { zip ->
            zip.getInputStream(zip.getEntry("classes.jar")).use { input ->
                classesJar.outputStream().use { input.copyTo(it) }
            }
        }

        // 2. 用 build-tools 的 d8 转 dex（不硬编码 SDK 路径）
        val dexFile = File(work, "classes.dex")
        val sdkDir = findSdkDir()
        val buildToolsDir = File(sdkDir, "build-tools")
        if (!buildToolsDir.isDirectory) {
            throw IllegalStateException("找不到 build-tools 目录: $buildToolsDir")
        }
        val buildTools = buildToolsDir.listFiles()
            ?.filter { it.isDirectory && it.name.matches(Regex("\\d+.*")) }
            ?.sortedByDescending { it.name }
            ?: emptyList()
        val d8Dir = buildTools.firstOrNull { File(it, "d8.bat").exists() || File(it, "d8").exists() }
            ?: throw IllegalStateException(
                "在 $buildToolsDir 下找不到 d8（请安装 Android SDK build-tools）"
            )
        val d8Exe = File(d8Dir, if (isWindows()) "d8.bat" else "d8")

        val cmd = mutableListOf<String>()
        if (isWindows()) {
            // Windows 下 .bat 不能直接由 ProcessBuilder 执行，需经 cmd.exe /c
            cmd += listOf("cmd.exe", "/c")
        }
        cmd += d8Exe.absolutePath
        cmd += listOf("--release", "--min-api", "26", "--output", work.absolutePath, classesJar.absolutePath)

        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val log = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) throw IllegalStateException("d8 失败:\n$log")

        // 3. 打包 plugin.apk（classes.dex + plugin.json）
        val apkFile = layout.buildDirectory.get()
            .file("outputs/plugin_ringmod.apk").asFile
        apkFile.parentFile.mkdirs()
        ZipOutputStream(apkFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            dexFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("plugin.json"))
            val manifest = """
                {
                  "id": "example.ringmod",
                  "entry": "com.example.maidmic.plugin.RingModPlugin",
                  "name": "环形调制机器人（示例）",
                  "author": "MaidMic",
                  "description": "载波 30Hz 环形调制，机器人音色。Tier 2 DSP 插件示例。"
                }
            """.trimIndent()
            zip.write(manifest.toByteArray())
            zip.closeEntry()
        }
        println(">>> 插件已生成: ${apkFile.absolutePath}")
    }
}

// assembleRelease → makePluginApk → bundleReleaseAar
afterEvaluate {
    tasks.named("assembleRelease") {
        dependsOn(makePluginApk)
    }
}