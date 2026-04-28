// maidmic-engine/src/api/maidmic_jni.cpp
// MaidMic JNI 桥 — Kotlin/Java ↔ C 引擎互操作
// MaidMic JNI Bridge — Kotlin/Java ↔ C Engine Interop
//
// 通过 JNI 暴露引擎核心功能给 Android App (Kotlin/Java)。
// 所有 JNI 函数遵循标准命名约定：
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
// 管线 API JNI 包装
// Pipeline API JNI Wrappers
// ============================================================
// 包名: aoeck.dwyai.com
// 类名: MaidMicEngine (暂定，可在 Kotlin 端调整)
//
// 遵循命名约定:
//   Java_aoeck_dwyai_com_MaidMicEngine_<method>

// 创建管线实例（返回 native handle 作为 long）
// Create pipeline instance (returns native handle as long)
JNIEXPORT jlong JNICALL
Java_aoeck_dwyai_com_MaidMicEngine_nativeCreatePipeline(
    JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    maidmic_pipeline_t* pipeline = maidmic_pipeline_create(MAIDMIC_PIPELINE_MODE_SIMPLE);
    LOGI("Pipeline created: %p", (void*)pipeline);
    return (jlong)(intptr_t)pipeline;
}

// 销毁管线实例
// Destroy pipeline instance
JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_MaidMicEngine_nativeDestroyPipeline(
    JNIEnv* env, jobject thiz, jlong native_handle) {
    (void)env;
    (void)thiz;
    maidmic_pipeline_t* pipeline = (maidmic_pipeline_t*)(intptr_t)native_handle;
    if (pipeline) {
        maidmic_pipeline_destroy(pipeline);
        LOGI("Pipeline destroyed");
    }
}

// 获取管线中模块数量
// Get module count in pipeline
JNIEXPORT jint JNICALL
Java_aoeck_dwyai_com_MaidMicEngine_nativeGetModuleCount(
    JNIEnv* env, jobject thiz, jlong native_handle) {
    (void)env;
    (void)thiz;
    maidmic_pipeline_t* pipeline = (maidmic_pipeline_t*)(intptr_t)native_handle;
    if (!pipeline) return 0;
    return (jint)maidmic_pipeline_get_module_count(pipeline);
}

// 处理一帧音频（走 JNI 缓冲区）
// Process one audio frame (via JNI buffer)
JNIEXPORT jboolean JNICALL
Java_aoeck_dwyai_com_MaidMicEngine_nativeProcessAudio(
    JNIEnv* env, jobject thiz,
    jlong native_handle,
    jbyteArray input_buffer,
    jint input_size,
    jbyteArray output_buffer,
    jint output_size) {
    (void)thiz;
    maidmic_pipeline_t* pipeline = (maidmic_pipeline_t*)(intptr_t)native_handle;
    if (!pipeline) return JNI_FALSE;

    // 准备 maidmic_buffer_t
    maidmic_buffer_t input;
    maidmic_buffer_t output;

    input.data = env->GetByteArrayElements(input_buffer, NULL);
    input.data_bytes = (uint32_t)input_size;
    input.meta.sample_rate = 48000;
    input.meta.channels = 1;
    input.meta.format = MAIDMIC_SAMPLE_S16;
    input.meta.frame_count = (uint32_t)(input_size / sizeof(int16_t));
    input.meta.timestamp_ns = 0;
    input.meta.sequence = 0;
    input.owned = false;

    output.data = env->GetByteArrayElements(output_buffer, NULL);
    output.data_bytes = (uint32_t)output_size;
    output.meta = input.meta;
    output.owned = false;

    // 处理音频
    jboolean result = maidmic_pipeline_process(pipeline, &input, &output)
        ? JNI_TRUE : JNI_FALSE;

    // 释放 JNI 引用
    env->ReleaseByteArrayElements(input_buffer, (jbyte*)input.data, JNI_ABORT);
    env->ReleaseByteArrayElements(output_buffer, (jbyte*)output.data, 0);

    return result;
}

#ifdef __cplusplus
}
#endif
