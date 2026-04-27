# MaidMic ProGuard 规则
# 保留 JNI 方法和 Lua 插件 API

# JNI 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留 Lua 脚本 API
-keep class org.luaj.** { *; }

# 保留 Shizuku API
-keep class rikka.shizuku.** { *; }

# MaidMic 核心类
-keep class com.maidmic.** { *; }
