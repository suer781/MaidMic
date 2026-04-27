// MaidMic settings.gradle.kts
// ============================================================
// Gradle 项目设置：声明模块和仓库源

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Shizuku 的仓库
        maven { setUrl("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Shizuku 和 LuaJ 等依赖
        maven { setUrl("https://jitpack.io") }
    }
}

rootProject.name = "MaidMic"

// App 模块
include(":app")
