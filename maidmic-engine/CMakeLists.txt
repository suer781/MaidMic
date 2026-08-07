# maidmic-engine/CMakeLists.txt
# MaidMic Echio 引擎 CMake 构建
#
# 为 Android NDK 交叉编译构建：
#   - arm64-v8a (现代设备，优先)
#   - armeabi-v7a (老旧设备)
#   - x86_64 (模拟器)
#
# 构建命令 (在 maidmic-app 目录下):
#   ./gradlew :maidmic-engine:build

cmake_minimum_required(VERSION 3.22)
project(maidmic-engine VERSION 1.0.0 LANGUAGES C CXX)

# ============================================================
# 编译选项
# Compiler options
# ============================================================

# 优化级别：O2 是延迟和体积的平衡
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wall -Wextra")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2 -Wall -Wextra -fno-exceptions -fno-rtti")

# 启用 NEON SIMD 指令（在 ARMv8 上自动启用，ARMv7 需要手动指定）
# Enable NEON SIMD instructions (auto on ARMv8, manual on ARMv7)
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -mfpu=neon")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon")
endif()

# ============================================================
# 源码文件
# Source files
# ============================================================

# 引擎核心
set(ENGINE_CORE_SRC
    src/core/ring_buffer.c
    src/core/pipeline.c
)

# DSP 模块
set(ENGINE_DSP_SRC
    src/dsp/gain.c
    src/dsp/compressor.c
    src/dsp/bass.c
    src/dsp/treble.c
    src/dsp/reverb.c
    src/dsp/pitch.c
    src/dsp/formant.c
    src/dsp/distortion.c
    src/dsp/echo.c
    src/dsp/noisegate.c
    src/dsp/limiter.c
    src/dsp/presence.c
)

# 人声处理模块（变声 / 声纹 / 自动修音等）
# Voice processing modules (voice transform / voiceprint / autotune, etc.)
set(ENGINE_VOICE_SRC
    src/voice/lpc.c
    src/voice/pitch_detector.c
    src/voice/voice_transform.c
    src/voice/autotune.c
    src/voice/voiceprint_mask.c
)

# 加速层（NEON SIMD 实现，供 LPC 等使用）
# Acceleration layer (NEON SIMD implementations, used by LPC etc.)
set(ENGINE_ACCEL_SRC
    src/accel/accel.c
)

# API / JNI 桥
set(ENGINE_API_SRC
    src/api/maidmic_api.c     # C API 导出
    src/api/maidmic_jni.cpp   # JNI 桥 → Java/Kotlin
)

# ============================================================
# 目标库
# Target library
# ============================================================

# 引擎静态库（给其他模块链接用）
# Engine static library (for linking by other modules)
add_library(maidmic_engine STATIC
    ${ENGINE_CORE_SRC}
    ${ENGINE_DSP_SRC}
    ${ENGINE_VOICE_SRC}
    ${ENGINE_ACCEL_SRC}
)

# JNI 共享库（Android App 直接加载）
# JNI shared library (directly loaded by Android App)
add_library(maidmic_jni SHARED
    ${ENGINE_API_SRC}
)

# ============================================================
# 头文件路径
# Header include paths
# ============================================================

target_include_directories(maidmic_engine PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_include_directories(maidmic_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# ============================================================
# 链接
# Linking
# ============================================================

target_link_libraries(maidmic_jni
    maidmic_engine
    log  # Android 日志库
)

# ============================================================
# 主机（非 Android）测试程序
# Host (non-Android) test program
# ============================================================
# Android 构建下不编译（避免与 JNI 冲突）；不影响 maidmic_jni / maidmic_engine 目标。
# 本机可用任意 C 编译器（gcc/clang/NDK clang）直接验证：
#   cmake -B build-host && cmake --build build-host
# 说明：整包默认构建会同时编译 maidmic_jni（需 Android NDK 环境）；
# 仅编译并运行主机测试可指定 target：
#   cmake --build build-host --target host_mm_test
#   ./build-host/host_mm_test [--in <file.pcm>] [--pitch <半音>] [--formant <半音>]
if(NOT CMAKE_SYSTEM_NAME STREQUAL "Android")
    add_executable(host_mm_test test/host_mm_test.c)
    target_link_libraries(host_mm_test PRIVATE maidmic_engine)
    target_include_directories(host_mm_test PRIVATE include)
    # Linux/Unix 需要显式链接数学库（DSP 使用 sin/powf/fabsf 等）；
    # MSVC 的 CRT 已含数学函数，无需（也不存在）libm
    if(NOT MSVC)
        target_link_libraries(host_mm_test PRIVATE m)
    endif()
endif()
