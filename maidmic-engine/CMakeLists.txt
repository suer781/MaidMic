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
    # 其他 DSP 模块文件会在后续添加：
    # src/dsp/eq.c
    # src/dsp/pitch_psola.c
    # src/dsp/reverb.c
    # src/dsp/compressor.c
    # src/dsp/distortion.c
    # src/dsp/chorus.c
    # src/dsp/delay.c
    # src/dsp/noise_gate.c
    # src/dsp/limiter.c
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
