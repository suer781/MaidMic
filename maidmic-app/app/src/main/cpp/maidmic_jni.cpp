// maidmic-jni.cpp — MaidMic JNI 桥接
// ============================================================
// 连接 Kotlin/Java 层和 C++ Echio 引擎。
// 每个 native 方法对应 Kotlin 端的 external fun。
//
// 编译后生成 libmaidmic_jni.so，由 Android App 加载。

#include <jni.h>
#include <android/log.h>
#include <cstring>

#include "maidmic/pipeline.h"
#include "maidmic/module.h"
#include "maidmic/types.h"

#define LOG_TAG "MaidMicJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================
// 外部模块声明（在 maidmic-engine/src/dsp/ 中实现）
// External module declarations
// ============================================================
extern const maidmic_module_t maidmic_module_gain;

// ============================================================
// 引擎实例（全局单例）
// Engine instance (global singleton)
// ============================================================
static maidmic_pipeline_t* g_pipeline = nullptr;
static uint32_t g_sample_rate = 48000;
static uint16_t g_channels = 1;
static int g_bit_depth = 16;

// ============================================================
// JNI 函数实现
// JNI function implementations
// ============================================================

extern "C" {

// 创建引擎实例
// 对应 Kotlin: nativeCreateEngine(sampleRate, channels, bitDepth, bufferSize)
JNIEXPORT jlong JNICALL
Java_aoeck_dwyai_com_bridge_shizuku_ShizukuMicBridge_nativeCreateEngine(
    JNIEnv* env, jobject /*thiz*/,
    jint sample_rate, jint channels, jint bit_depth, jint /*buffer_size*/
) {
    LOGI("Creating engine: %dHz %dch %dbit", sample_rate, channels, bit_depth);
    
    g_sample_rate = sample_rate;
    g_channels = channels;
    g_bit_depth = bit_depth;
    
    // 创建管线（默认简易模式）
    g_pipeline = maidmic_pipeline_create(MAIDMIC_PIPELINE_MODE_SIMPLE);
    if (!g_pipeline) {
        LOGE("Failed to create pipeline");
        return 0;
    }
    
    // 注册内置模块（示例：Gain）
    // Register built-in modules
    maidmic_pipeline_add_module(g_pipeline, &maidmic_module_gain);
    
    return reinterpret_cast<jlong>(g_pipeline);
}

// 销毁引擎
JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_bridge_shizuku_ShizukuMicBridge_nativeDestroyEngine(
    JNIEnv* env, jobject /*thiz*/, jlong engine_ptr
) {
    auto* pipeline = reinterpret_cast<maidmic_pipeline_t*>(engine_ptr);
    if (pipeline) {
        maidmic_pipeline_destroy(pipeline);
        LOGI("Engine destroyed");
    }
    if (engine_ptr == reinterpret_cast<jlong>(g_pipeline)) {
        g_pipeline = nullptr;
    }
}

// 从 RingBuffer 读取音频（处理后的）
JNIEXPORT jint JNICALL
Java_aoeck_dwyai_com_bridge_shizuku_ShizukuMicBridge_nativeReadAudio(
    JNIEnv* env, jobject /*thiz*/, jlong engine_ptr,
    jbyteArray buffer, jint frame_count
) {
    // 这个函数只是示例——实际实现需要从 RingBuffer 读取
    // 并把数据拷贝到 Java byte array
    // 当前返回 0（无数据），等 RingBuffer 接入 JNI 后实现
    (void)engine_ptr;
    (void)frame_count;
    
    jsize len = env->GetArrayLength(buffer);
    memset(env->GetByteArrayElements(buffer, nullptr), 0, len);
    env->ReleaseByteArrayElements(buffer, nullptr, 0);
    
    return 0;
}

// 更新音频配置
JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_bridge_shizuku_ShizukuMicBridge_nativeUpdateConfig(
    JNIEnv* env, jobject /*thiz*/, jlong engine_ptr,
    jint sample_rate, jint channels, jint bit_depth, jint buffer_size
) {
    (void)engine_ptr;
    (void)buffer_size;
    g_sample_rate = sample_rate;
    g_channels = channels;
    g_bit_depth = bit_depth;
    LOGI("Config updated: %dHz %dch", sample_rate, channels);
}

// 获取延迟估计
JNIEXPORT jfloat JNICALL
Java_aoeck_dwyai_com_bridge_shizuku_ShizukuMicBridge_nativeGetLatencyMs(
    JNIEnv* env, jobject /*thiz*/, jlong engine_ptr
) {
    auto* pipeline = reinterpret_cast<maidmic_pipeline_t*>(engine_ptr);
    if (pipeline) {
        return maidmic_pipeline_get_latency_ms(pipeline);
    }
    return 0.0f;
}

// ============================================================
// Lua 沙箱 JNI
// ============================================================

JNIEXPORT jdouble JNICALL
Java_aoeck_dwyai_com_plugins_lua_LuaPluginSandbox_nativeGetEngineParam(
    JNIEnv* env, jobject /*thiz*/, jstring key
) {
    const char* key_str = env->GetStringUTFChars(key, nullptr);
    (void)key_str;  // TODO: 从引擎获取参数
    env->ReleaseStringUTFChars(key, key_str);
    return 0.0;
}

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_plugins_lua_LuaPluginSandbox_nativeSetEngineParam(
    JNIEnv* env, jobject /*thiz*/, jstring key, jfloat value
) {
    const char* key_str = env->GetStringUTFChars(key, nullptr);
    (void)key_str;  // TODO: 设置引擎参数
    (void)value;
    env->ReleaseStringUTFChars(key, key_str);
}

JNIEXPORT jstring JNICALL
Java_aoeck_dwyai_com_plugins_lua_LuaPluginSandbox_nativeLoadPreset(
    JNIEnv* env, jobject /*thiz*/, jstring plugin_id, jstring preset_name
) {
    (void)plugin_id;
    (void)preset_name;  // TODO: 从插件包加载预设 JSON
    return env->NewStringUTF("{}");
}

// ============================================================
// Root 桥接 JNI
// ============================================================

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_bridge_root_RootMicBridge_nativeProcess(
    JNIEnv* env, jobject /*thiz*/, jlong engine_ptr,
    jbyteArray input, jbyteArray output, jint size
) {
    auto* pipeline = reinterpret_cast<maidmic_pipeline_t*>(engine_ptr);
    if (!pipeline) return;
    
    jbyte* in_data = env->GetByteArrayElements(input, nullptr);
    jbyte* out_data = env->GetByteArrayElements(output, nullptr);
    
    // 构建 maidmic_buffer_t 并调用 pipeline 处理
    maidmic_buffer_t buf;
    buf.meta.sample_rate = g_sample_rate;
    buf.meta.channels = g_channels;
    buf.meta.format = MAIDMIC_SAMPLE_S16;
    buf.meta.frame_count = size / (g_channels * (g_bit_depth / 8));
    buf.data = reinterpret_cast<void*>(in_data);
    buf.data_bytes = size;
    buf.owned = false;
    
    maidmic_buffer_t out_buf = buf;
    out_buf.data = reinterpret_cast<void*>(out_data);
    
    maidmic_pipeline_process(pipeline, &buf, &out_buf);
    
    env->ReleaseByteArrayElements(input, in_data, JNI_ABORT);
    env->ReleaseByteArrayElements(output, out_data, 0);
}

JNIEXPORT jint JNICALL
Java_aoeck_dwyai_com_bridge_root_RootMicBridge_nativeWriteToVirtualDevice(
    JNIEnv* env, jobject /*thiz*/, jint fd, jbyteArray data, jint size
) {
    (void)env;
    (void)fd;
    (void)data;
    (void)size;
    return -1;  // TODO: 通过 AudioFlinger API 写入虚拟设备
}

} // extern "C"
