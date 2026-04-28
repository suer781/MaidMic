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
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // JNI 库编译参数
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
        }
    }

    // NDK 编译
    externalNativeBuild {
        cmake {
            path = file("../../maidmic-engine/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Jetpack Compose
    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
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
    // Jetpack Compose
    val composeVersion = "1.6.0"
    implementation("androidx.compose.ui:ui:$composeVersion")
    implementation("androidx.compose.ui:ui-tooling-preview:$composeVersion")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Android 核心
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Shizuku — 非 root 提权（从 jitpack 获取，已在 settings.gradle.kts 中配置）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // LuaJ — Lua 插件运行时
    implementation("org.luaj:luaj-jse:3.0.1")

    // 测试
    testImplementation("junit:junit:4.13.2")
}
