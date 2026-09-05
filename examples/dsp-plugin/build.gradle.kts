// examples/dsp-plugin 构建脚本
// 用法：本目录下 ./gradlew assembleRelease → build/outputs/plugin_ringmod.apk
// 产物直接拷贝到手机 Android/data/aoeck.dwyai.com/files/maidmic_plugins_ext/

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
    }
}

apply(plugin = "com.android.library")
apply(plugin = "org.jetbrains.kotlin.android")

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "com.example.maidmic.plugin"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        kotlinOptions { jvmTarget = "17" }
    }
}

dependencies {
    // 接口由宿主运行时提供（见 src/.../core/DspAudioPlugin.kt 副本），无需外部依赖
}

// ---- 打包任务：AAR → classes.jar → d8 → classes.dex → plugin_ringmod.apk ----
androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        if (variantName != "Release") return@onVariants
        val tasks = project.tasks
        val bundle = tasks.named("bundleReleaseAar")
        val outDir = layout.buildDirectory.dir("outputs")
        val makeApk = tasks.register("makePluginApk") {
            group = "maidmic"
            dependsOn(bundle)
            doLast {
                val aarFile = layout.buildDirectory.get()
                    .file("outputs/aar/dsp-plugin-release.aar").asFile
                if (!aarFile.exists()) throw IllegalStateException("AAR 不存在: $aarFile")
                val work = layout.buildDirectory.get().dir("plugin_work").asFile
                work.mkdirs()

                // 1. 解出 classes.jar
                val classesJar = File(work, "classes.jar")
                java.util.zip.ZipFile(aarFile).use { zip ->
                    zip.getInputStream(zip.getEntry("classes.jar")).use { input ->
                        classesJar.outputStream().use { input.copyTo(it) }
                    }
                }

                // 2. d8 转 dex（优先 ANDROID_HOME/build-tools 内的 d8）
                val dexFile = File(work, "classes.dex")
                val buildTools = listOf(
                    System.getenv("ANDROID_HOME")?.let { ah -> File("$ah/build-tools") },
                    File("C:/AndroidSdk/build-tools"),
                ).filterNotNull().flatMap { bt -> bt.listFiles()?.sortedByDescending { it.name } ?: emptyList() }
                val d8 = buildTools.firstOrNull { File(it, "d8.bat").exists() || File(it, "d8").exists() }
                    ?: throw IllegalStateException("找不到 d8（请设置 ANDROID_HOME）")
                val d8Exe = File(d8, if (System.getProperty("os.name").contains("win")) "d8.bat" else "d8")
                val proc = ProcessBuilder(d8Exe.absolutePath, "--release", "--min-api", "26",
                    "--output", work.absolutePath, classesJar.absolutePath)
                    .redirectErrorStream(true).start()
                val log = proc.inputStream.bufferedReader().readText()
                if (proc.waitFor() != 0) throw IllegalStateException("d8 失败:\n$log")

                // 3. 打包 plugin.apk（classes.dex + plugin.json）
                java.util.zip.ZipOutputStream(File(outDir.get().asFile
                    .resolve("plugin_ringmod.apk").absolutePath).outputStream()).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
                    dexFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    zip.putNextEntry(java.util.zip.ZipEntry("plugin.json"))
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
                println(">>> 插件已生成: ${outDir.get().asFile.resolve("plugin_ringmod.apk").absolutePath}")
            }
        }
        bundle.get().finalizedBy(makeApk)
    }
}
