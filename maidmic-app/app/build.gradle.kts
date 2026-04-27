// MaidMic App 模块 build.gradle.kts
// ============================================================
// Android App 构建配置：NDK 编译、依赖、多渠道

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "aoeck.dwyai.com"
    compileSdk = 34
    defaultConfig {
        applicationId = "aoeck.dwyai.com"
        minSdk = 26  // Android 8.0 — Shizuku 和 AAudio 的最低要求
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-alpha"

        // NDK 架构配置
        ndk {
            // 支持的 ABI：arm64-v8a 为主（现代设备），armeabi-v7a 为兼容
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // JNI 库名
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            // debug 版不改包名，adb install 直接覆盖
        }
    }

    // NDK 编译配置
    externalNativeBuild {
        cmake {
            path = file("../maidmic-engine/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Kotlin 和 Compose
    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    // Java 兼容
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 允许访问 aar（Shizuku）
    repositories {
        maven { setUrl("https://jitpack.io") }
    }
}

dependencies {
    // ============================================================
    // Jetpack Compose — UI 框架
    // ============================================================
    val composeVersion = "1.6.0"
    implementation("androidx.compose.ui:ui:$composeVersion")
    implementation("androidx.compose.ui:ui-tooling-preview:$composeVersion")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // ============================================================
    // Android 核心
    // ============================================================
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // ============================================================
    // Shizuku — 非 root 提权
    // ============================================================
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // ============================================================
    // LuaJ — Lua 插件运行时
    // ============================================================
    implementation("org.luaj:luaj-jse:3.0.1")

    // ============================================================
    // 测试
    // ============================================================
    testImplementation("junit:junit:4.13.2")
}
