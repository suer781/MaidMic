// maidmic-engine/src/api/maidmic_jni.cpp
// MaidMic JNI 桥 — Kotlin/Java ↔ C 引擎互操作
// MaidMic JNI Bridge — Kotlin/Java ↔ C Engine Interop
//
// JNI 函数命名遵循标准约定：
//   Java_<package>_<class>_<method>
// 包名: aoeck.dwyai.com

#include <jni.h>
#include <android/log.h>
#include "maidmic/pipeline.h"

#define LOG_TAG "MaidMic-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef __cplusplus
extern "C" {
#endif

// ============================================================
// JNI 初始化
// JNI Initialization
// ============================================================

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)vm;
    (void)reserved;
    LOGI("MaidMic JNI loaded successfully");
    return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM* vm, void* reserved) {
    (void)vm;
    (void)reserved;
    LOGI("MaidMic JNI unloaded");
}

// ============================================================
// 全局引擎实例管理
// Global engine instance management
// ============================================================
// 简化版本：只维护一个全局 pipeline 实例，
// 各 bridge 共享。
// Simple version: one global pipeline, shared across bridges.

static maidmic_pipeline_t* g_pipeline = NULL;

// ============================================================
// ShizukuMicBridge — native 方法
// Package: aoeck.dwyai.com.bridge.shizuku
// Class:   ShizukuMicBridge
// ============================================================

JNIEXPORT jlong JNICALL
Java_aoeck_dwyai_com_bridge_shizuku_ShizukuMicBridge_nativeCreateEngine(
    JNIEnv* env, jobject thiz,
    jint sample_rate, jint channels, jint bit_depth, jint buffer_size) {
    (void)env; (void)thiz; (void)sample_rate; (void)channels; (void)bit_depth; (void)buffer_size;

    if (!g_pipeline) {
        g_pipeline = maidmic_pipeline_create(MAIDMIC_PIPELINE_MODE_SIMPLE);
        LOGI("Engine created: %p", (void*)g_pipeline);
    }
    return (jlong)(intptr_t)g_pipeline;
}

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_bridge_shizuku_ShizukuMicBridge_nativeDestroyEngine(
    JNIEnv* env, jobject thiz, jlong engine_ptr) {
    (void)env; (void)thiz;
    maidmic_pipeline_t* p = (maidmic_pipeline_t*)(intptr_t)engine_ptr;
    if (p) {
        maidmic_pipeline_destroy(p);
        if (p == g_pipeline) g_pipeline = NULL;
        LOGI("Engine destroyed");
    }
}

JNIEXPORT jint JNICALL
Java_aoeck_dwyai_com_bridge_shizuku_ShizukuMicBridge_nativeReadAudio(
    JNIEnv* env, jobject thiz,
    jlong engine_ptr, jbyteArray buffer, jint frame_count) {
    (void)env; (void)thiz; (void)engine_ptr; (void)buffer; (void)frame_count;
    // TODO: implement audio reading from RingBuffer
    LOGI("nativeReadAudio called (stub)");
    return 0;
}

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_bridge_shizuku_ShizukuMicBridge_nativeUpdateConfig(
    JNIEnv* env, jobject thiz,
    jlong engine_ptr,
    jint sample_rate, jint channels, jint bit_depth, jint buffer_size) {
    (void)env; (void)thiz; (void)engine_ptr;
    LOGI("nativeUpdateConfig: %dHz %dch %dbit %dframes",
         (int)sample_rate, (int)channels, (int)bit_depth, (int)buffer_size);
}

JNIEXPORT jfloat JNICALL
Java_aoeck_dwyai_com_bridge_shizuku_ShizukuMicBridge_nativeGetLatencyMs(
    JNIEnv* env, jobject thiz, jlong engine_ptr) {
    (void)env; (void)thiz;
    maidmic_pipeline_t* p = (maidmic_pipeline_t*)(intptr_t)engine_ptr;
    return p ? (jfloat)maidmic_pipeline_get_latency_ms(p) : 0.0f;
}

// ============================================================
// AccessibilityMicBridge — native 方法
// Package: aoeck.dwyai.com.bridge.accessibility
// Class:   AccessibilityMicBridge
// ============================================================

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_bridge_accessibility_AccessibilityMicBridge_nativeProcess(
    JNIEnv* env, jobject thiz,
    jlong engine_ptr, jbyteArray input, jbyteArray output, jint size) {
    (void)env; (void)thiz; (void)engine_ptr; (void)input; (void)output; (void)size;
    LOGI("nativeProcess called (stub)");
}

// ============================================================
// RootMicBridge — native 方法
// Package: aoeck.dwyai.com.bridge.root
// Class:   RootMicBridge
// ============================================================

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_bridge_root_RootMicBridge_nativeProcess(
    JNIEnv* env, jobject thiz,
    jlong engine_ptr, jbyteArray input, jbyteArray output, jint size) {
    (void)env; (void)thiz; (void)engine_ptr; (void)input; (void)output; (void)size;
    LOGI("nativeProcess (root) called (stub)");
}

JNIEXPORT jint JNICALL
Java_aoeck_dwyai_com_bridge_root_RootMicBridge_nativeWriteToVirtualDevice(
    JNIEnv* env, jobject thiz,
    jint fd, jbyteArray data, jint size) {
    (void)env; (void)thiz; (void)fd; (void)data; (void)size;
    LOGI("nativeWriteToVirtualDevice called (stub)");
    return -1;
}

// ============================================================
// LuaPluginSandbox — native 方法
// Package: aoeck.dwyai.com.plugins.lua
// Class:   LuaPluginSandbox
// ============================================================

JNIEXPORT jdouble JNICALL
Java_aoeck_dwyai_com_plugins_lua_LuaPluginSandbox_nativeGetEngineParam(
    JNIEnv* env, jobject thiz, jstring key) {
    (void)env; (void)thiz; (void)key;
    LOGI("nativeGetEngineParam called (stub)");
    return 0.0;
}

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_plugins_lua_LuaPluginSandbox_nativeSetEngineParam(
    JNIEnv* env, jobject thiz, jstring key, jfloat value) {
    (void)env; (void)thiz; (void)key; (void)value;
    LOGI("nativeSetEngineParam called (stub)");
}

JNIEXPORT jstring JNICALL
Java_aoeck_dwyai_com_plugins_lua_LuaPluginSandbox_nativeLoadPreset(
    JNIEnv* env, jobject thiz, jstring plugin_id, jstring preset_name) {
    (void)env; (void)thiz; (void)plugin_id; (void)preset_name;
    LOGI("nativeLoadPreset called (stub)");
    return NULL;
}

#ifdef __cplusplus
}
#endif
