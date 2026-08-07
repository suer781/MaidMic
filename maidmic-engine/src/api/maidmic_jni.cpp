// maidmic-engine/src/api/maidmic_jni.cpp
// MaidMic JNI 桥 — 音频处理核心（模块化管线版本）
// ============================================================
// 原硬编码 process_audio_frame 已拆分为 9 个独立 DSP 模块，
// 通过 maidmic_pipeline_t 串联处理。
// nativeProcessAudio 内部走默认管线，签名保持不变，现有调用方不破。
//
// JNI 函数命名：Java_<package>_<class>_<method>

#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <cstdint>

#include "maidmic/pipeline.h"
#include "maidmic/module.h"

#define LOG_TAG "MaidMic-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef __cplusplus
extern "C" {
#endif

// ============================================================
// DSP 模块描述符声明（定义在各 .c 文件中）
// ============================================================

extern const maidmic_module_t maidmic_module_gain;
extern const maidmic_module_t maidmic_module_compressor;
extern const maidmic_module_t maidmic_module_bass;
extern const maidmic_module_t maidmic_module_treble;
extern const maidmic_module_t maidmic_module_reverb;
extern const maidmic_module_t maidmic_module_pitch;
extern const maidmic_module_t maidmic_module_formant;
extern const maidmic_module_t maidmic_module_distortion;
extern const maidmic_module_t maidmic_module_echo;

// Task 6b 新增模块（定义见 src/dsp/ 与 src/voice/）
extern const maidmic_module_t maidmic_module_noisegate;
extern const maidmic_module_t maidmic_module_limiter;
extern const maidmic_module_t maidmic_module_presence;
extern const maidmic_module_t maidmic_module_voice_transform;
extern const maidmic_module_t maidmic_module_voiceprint_mask;
extern const maidmic_module_t maidmic_module_autotune;

// ============================================================
// 新模块 ID 宏（module.h 尚未定义，各模块描述符中直接使用字面量 15/16/17/18）
// 此处补齐宏；若未来 module.h 补充同名宏，则直接沿用头文件定义。
// ============================================================
#ifndef MAIDMIC_MODULE_ID_VOICE_TRANSFORM
#define MAIDMIC_MODULE_ID_VOICE_TRANSFORM 15
#endif
#ifndef MAIDMIC_MODULE_ID_VOICEPRINT_MASK
#define MAIDMIC_MODULE_ID_VOICEPRINT_MASK 16
#endif
#ifndef MAIDMIC_MODULE_ID_PRESENCE
#define MAIDMIC_MODULE_ID_PRESENCE 17
#endif
#ifndef MAIDMIC_MODULE_ID_AUTOTUNE
#define MAIDMIC_MODULE_ID_AUTOTUNE 18
#endif

// ============================================================
// 模块 ID → 模块描述符查找
// ============================================================

static const maidmic_module_t* lookup_module_by_id(uint32_t id) {
    switch (id) {
        case MAIDMIC_MODULE_ID_GAIN:       return &maidmic_module_gain;
        case MAIDMIC_MODULE_ID_COMPRESSOR: return &maidmic_module_compressor;
        case MAIDMIC_MODULE_ID_BASS:       return &maidmic_module_bass;
        case MAIDMIC_MODULE_ID_TREBLE:     return &maidmic_module_treble;
        case MAIDMIC_MODULE_ID_REVERB:     return &maidmic_module_reverb;
        case MAIDMIC_MODULE_ID_PITCH:      return &maidmic_module_pitch;
        case MAIDMIC_MODULE_ID_FORMANT:    return &maidmic_module_formant;
        case MAIDMIC_MODULE_ID_DISTORTION: return &maidmic_module_distortion;
        case MAIDMIC_MODULE_ID_ECHO:       return &maidmic_module_echo;
        case MAIDMIC_MODULE_ID_NOISEGATE:  return &maidmic_module_noisegate;
        case MAIDMIC_MODULE_ID_LIMITER:    return &maidmic_module_limiter;
        case MAIDMIC_MODULE_ID_PRESENCE:   return &maidmic_module_presence;
        case MAIDMIC_MODULE_ID_VOICE_TRANSFORM:  return &maidmic_module_voice_transform;
        case MAIDMIC_MODULE_ID_VOICEPRINT_MASK:  return &maidmic_module_voiceprint_mask;
        case MAIDMIC_MODULE_ID_AUTOTUNE:   return &maidmic_module_autotune;
        default: return NULL;
    }
}

// ============================================================
// 默认管线（兼容旧 nativeProcessAudio / nativeSetEqParams 调用）
// ============================================================
// 在 JNI_OnLoad 或首次使用时创建。
// 默认链（与 Kotlin PipelineController.DEFAULT_CHAIN 一致，双链统一）：
//   Gain → Compressor → Bass → Treble → Reverb → Pitch → Formant → Distortion → Echo
// 不再预置 NoiseGate/Limiter/VoiceTransform 与可选模块（AutoTune/VoiceprintMask/Presence），
// 如需启用由 Kotlin 侧动态挂载（当前 UI 均不使用）。

// Task 4：默认管线现同时被 UI 线程（模块链编辑，经 nativePipeline* 操作本句柄）
// 与录音线程（nativeProcessAudio 的 process）访问。pipeline.c 未加锁，
// 当前依赖"编辑与处理不同时发生"的单线程假设；若未来出现并发编辑/处理场景，
// 需在 pipeline.c 内增加互斥保护（勿在此层自行加锁）。
static maidmic_pipeline_t* g_default_pipeline = NULL;

// 默认管线中各模块的节点 ID（用于参数设置）
// 注意：noisegate/voice_transform/limiter/autotune/voiceprint_mask/presence 不再预置，
// 以下字段仅在对应模块被 Kotlin 侧动态挂载后由 resolve_default_node 重新解析填充。
static struct {
    uint32_t gain;
    uint32_t noisegate;      // 未预置；动态挂载后解析
    uint32_t compressor;
    uint32_t bass;
    uint32_t treble;
    uint32_t reverb;
    uint32_t pitch;          // id 4（预置链与 Kotlin DEFAULT_CHAIN 均含）
    uint32_t formant;        // id 13（预置链与 Kotlin DEFAULT_CHAIN 均含）
    uint32_t voice_transform;// 未预置；动态挂载后解析
    uint32_t distortion;
    uint32_t echo;
    uint32_t limiter;        // 未预置；动态挂载后解析
    // 可选模块节点（未预置；动态挂载后解析）
    uint32_t autotune;       // id 18
    uint32_t voiceprint_mask;// id 16
    uint32_t presence;       // id 17
} g_default_nodes;

static void ensure_default_pipeline(void) {
    if (g_default_pipeline) return;
    g_default_pipeline = maidmic_pipeline_create(MAIDMIC_PIPELINE_MODE_SIMPLE);
    if (!g_default_pipeline) {
        LOGE("Failed to create default pipeline");
        return;
    }
    // 默认链（与 Kotlin PipelineController.DEFAULT_CHAIN 完全一致，消除双链不一致）：
    //   Gain → Compressor → Bass → Treble → Reverb → Pitch → Formant → Distortion → Echo
    // 注意：不再预置 NoiseGate/Limiter/VoiceTransform 及可选模块（AutoTune/VoiceprintMask/
    // Presence）。这些模块当前 UI 不使用；如未来需要，由 Kotlin 侧经 nativePipelineAddModule
    // 动态挂载，保证"编辑器链 == 录音处理链"唯一。
    g_default_nodes.gain           = maidmic_pipeline_add_module(g_default_pipeline, &maidmic_module_gain);
    g_default_nodes.compressor     = maidmic_pipeline_add_module(g_default_pipeline, &maidmic_module_compressor);
    g_default_nodes.bass           = maidmic_pipeline_add_module(g_default_pipeline, &maidmic_module_bass);
    g_default_nodes.treble         = maidmic_pipeline_add_module(g_default_pipeline, &maidmic_module_treble);
    g_default_nodes.reverb         = maidmic_pipeline_add_module(g_default_pipeline, &maidmic_module_reverb);
    g_default_nodes.pitch          = maidmic_pipeline_add_module(g_default_pipeline, &maidmic_module_pitch);
    g_default_nodes.formant        = maidmic_pipeline_add_module(g_default_pipeline, &maidmic_module_formant);
    g_default_nodes.distortion     = maidmic_pipeline_add_module(g_default_pipeline, &maidmic_module_distortion);
    g_default_nodes.echo           = maidmic_pipeline_add_module(g_default_pipeline, &maidmic_module_echo);
    LOGI("Default pipeline created: Gain→Comp→Bass→Treble→Reverb→Pitch→Formant→Dist→Echo (9 modules, = Kotlin DEFAULT_CHAIN)");
}

// ============================================================
// 默认管线节点动态解析（缓存 + 失效重查）
// ============================================================
// Kotlin 侧 PipelineController 已接管默认管线（编辑=默认管线），
// initDefaultChain 首次会清空并重建模块链。重建后各模块的 node_id 重新分配
// （maidmic_pipeline_add_module 内部 next_node_id 只增不复用，见 pipeline.c），
// 因此 g_default_nodes 中缓存的 node_id 会失效 —— 若仍按旧 node_id 调用
// maidmic_pipeline_set_param 会返回 false，参数静默不生效（表现为
// "Echo/DSP 没正常工作"）。故所有使用点必须动态解析节点：
//   1) 先用缓存 node_id 经 get_module_at 校验对应节点的 module->id 是否仍匹配；
//   2) 不匹配/缺失则按模块类型 ID（MAIDMIC_MODULE_ID_*）遍历管线重查并更新缓存；
//   3) 仍找不到返回 0（调用方跳过该模块）。
// 注意：pipeline.h 的 maidmic_pipeline_get_module_by_id 按 node_id 语义查找，
// 无法直接按模块类型 ID 重查，故此处重查用 get_module_at 遍历匹配 module->id。
// 这些 nativeSet* 入口调用频率低（UI 滑块/开关），遍历开销可忽略。

// 按模块类型 ID 遍历默认管线查找节点（SIMPLE 模式节点线性存储）
static const maidmic_dag_node_t* find_default_module(uint32_t module_id) {
    if (!g_default_pipeline) return NULL;
    uint32_t count = maidmic_pipeline_get_module_count(g_default_pipeline);
    for (uint32_t i = 0; i < count; i++) {
        const maidmic_dag_node_t* node = maidmic_pipeline_get_module_at(g_default_pipeline, i);
        if (node && node->module && node->module->id == module_id) {
            return node;
        }
    }
    return NULL;
}

// 解析默认管线中指定模块的节点 ID：缓存命中校验 + 失效重查
static uint32_t resolve_default_node(uint32_t module_id, uint32_t* cached) {
    if (!g_default_pipeline) return 0;
    // 1) 缓存命中校验：管线重建后旧 node_id 可能指向别的模块
    if (*cached != 0) {
        const maidmic_dag_node_t* node =
            maidmic_pipeline_get_module_at(g_default_pipeline, *cached);
        if (node && node->module && node->module->id == module_id) {
            return *cached;
        }
    }
    // 2) 缓存失效：按模块类型 ID 重查并更新缓存
    const maidmic_dag_node_t* node = find_default_module(module_id);
    if (node) {
        *cached = node->node_id;
        return *cached;
    }
    // 3) 模块不在默认管线中（可能被 Kotlin 侧删除）
    *cached = 0;
    return 0;
}

// 向默认管线指定模块设置参数（节点动态解析，找不到则静默跳过）
static void set_default_param(uint32_t module_id, uint32_t* cached,
                              const char* key, maidmic_param_t param) {
    uint32_t node = resolve_default_node(module_id, cached);
    if (node) {
        maidmic_pipeline_set_param(g_default_pipeline, node, key, param);
    }
}

// ============================================================
// Utils
// ============================================================

static inline float clamp(float v, float min, float max) {
    return v < min ? min : (v > max ? max : v);
}

// ============================================================
// 设置 EQ 参数（转发到默认管线各模块）
// ============================================================

void set_eq_params(float gain_db, float bass_db, float treble_db,
                   float reverb_mix, int pitch_semitones,
                   float formant_shift, float distortion,
                   float echo_delay_ms, float echo_decay) {
    ensure_default_pipeline();
    if (!g_default_pipeline) return;

    maidmic_param_t param;
    param.type = MAIDMIC_PARAM_FLOAT;

    // 管线可能被 Kotlin 侧重建，节点 id 必须动态解析
    // （set_default_param 内部经 resolve_default_node 校验缓存、失效重查）。
    // Gain
    param.value.as_float = gain_db;
    set_default_param(MAIDMIC_MODULE_ID_GAIN, &g_default_nodes.gain, "gain_db", param);

    // Bass
    param.value.as_float = bass_db;
    set_default_param(MAIDMIC_MODULE_ID_BASS, &g_default_nodes.bass, "bass_db", param);

    // Treble
    param.value.as_float = treble_db;
    set_default_param(MAIDMIC_MODULE_ID_TREBLE, &g_default_nodes.treble, "treble_db", param);

    // Reverb
    param.value.as_float = clamp(reverb_mix, 0.0f, 1.0f);
    set_default_param(MAIDMIC_MODULE_ID_REVERB, &g_default_nodes.reverb, "reverb_mix", param);

    // Pitch/Formant → 变声模块。默认管线（预置链与 Kotlin DEFAULT_CHAIN 一致）
    // 上挂载的是独立 Pitch(4)+Formant(13)，此处只写这两个模块。
    param.type = MAIDMIC_PARAM_FLOAT;
    param.value.as_float = clamp((float)pitch_semitones, -12.0f, 12.0f);
    set_default_param(MAIDMIC_MODULE_ID_PITCH, &g_default_nodes.pitch, "pitch_semitones", param);

    // Formant
    param.value.as_float = clamp(formant_shift, -12.0f, 12.0f);
    set_default_param(MAIDMIC_MODULE_ID_FORMANT, &g_default_nodes.formant, "formant_shift", param);

    // Distortion
    param.value.as_float = clamp(distortion, 0.0f, 1.0f);
    set_default_param(MAIDMIC_MODULE_ID_DISTORTION, &g_default_nodes.distortion, "distortion", param);

    // Echo
    param.value.as_float = clamp(echo_delay_ms, 0.0f, 2000.0f);
    set_default_param(MAIDMIC_MODULE_ID_ECHO, &g_default_nodes.echo, "echo_delay_ms", param);
    param.value.as_float = clamp(echo_decay, 0.0f, 0.9f);
    set_default_param(MAIDMIC_MODULE_ID_ECHO, &g_default_nodes.echo, "echo_decay", param);
}

// ============================================================
// 设置压缩机参数（转发到默认管线压缩机模块）
// ============================================================

void set_compressor_params(float threshold_db, float ratio, float makeup_gain_db) {
    ensure_default_pipeline();
    if (!g_default_pipeline) return;

    maidmic_param_t param;
    param.type = MAIDMIC_PARAM_FLOAT;

    // 管线可能被 Kotlin 侧重建，节点 id 必须动态解析
    param.value.as_float = clamp(threshold_db, -60.0f, 0.0f);
    set_default_param(MAIDMIC_MODULE_ID_COMPRESSOR, &g_default_nodes.compressor, "comp_threshold", param);

    param.value.as_float = clamp(ratio, 1.0f, 20.0f);
    set_default_param(MAIDMIC_MODULE_ID_COMPRESSOR, &g_default_nodes.compressor, "comp_ratio", param);

    param.value.as_float = clamp(makeup_gain_db, 0.0f, 20.0f);
    set_default_param(MAIDMIC_MODULE_ID_COMPRESSOR, &g_default_nodes.compressor, "comp_makeup", param);
}

// ============================================================
// 设置噪声门参数（转发到默认管线噪声门模块）
// ============================================================

void set_noisegate_params(float threshold_db, float attack_ms, float release_ms) {
    ensure_default_pipeline();
    if (!g_default_pipeline) return;

    maidmic_param_t param;
    param.type = MAIDMIC_PARAM_FLOAT;

    // 管线可能被 Kotlin 侧重建，节点 id 必须动态解析
    param.value.as_float = clamp(threshold_db, -100.0f, 0.0f);
    set_default_param(MAIDMIC_MODULE_ID_NOISEGATE, &g_default_nodes.noisegate, "gate_threshold", param);

    param.value.as_float = clamp(attack_ms, 0.0f, 200.0f);
    set_default_param(MAIDMIC_MODULE_ID_NOISEGATE, &g_default_nodes.noisegate, "gate_attack", param);

    param.value.as_float = clamp(release_ms, 0.0f, 2000.0f);
    set_default_param(MAIDMIC_MODULE_ID_NOISEGATE, &g_default_nodes.noisegate, "gate_release", param);
}

// ============================================================
// 设置限制器参数（转发到默认管线限制器模块）
// ============================================================

void set_limiter_params(float threshold_db, float release_ms) {
    ensure_default_pipeline();
    if (!g_default_pipeline) return;

    maidmic_param_t param;
    param.type = MAIDMIC_PARAM_FLOAT;

    // 管线可能被 Kotlin 侧重建，节点 id 必须动态解析
    param.value.as_float = clamp(threshold_db, -60.0f, 0.0f);
    set_default_param(MAIDMIC_MODULE_ID_LIMITER, &g_default_nodes.limiter, "limiter_threshold", param);

    param.value.as_float = clamp(release_ms, 1.0f, 1000.0f);
    set_default_param(MAIDMIC_MODULE_ID_LIMITER, &g_default_nodes.limiter, "limiter_release", param);
}

// ============================================================
// JNI: 设置 EQ 参数
// ============================================================

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativeSetEqParams(
    JNIEnv* env, jclass clazz,
    jfloat gain_db, jfloat bass_db, jfloat treble_db,
    jfloat reverb_mix, jint pitch_semitones,
    jfloat formant_shift, jfloat distortion,
    jfloat echo_delay_ms, jfloat echo_decay) {
    (void)env; (void)clazz;
    set_eq_params(gain_db, bass_db, treble_db, reverb_mix, pitch_semitones,
                  formant_shift, distortion, echo_delay_ms, echo_decay);
}

// ============================================================
// JNI: 设置压缩机参数
// ============================================================

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativeSetCompressor(
    JNIEnv* env, jclass clazz,
    jfloat threshold_db, jfloat ratio, jfloat makeup_gain_db) {
    (void)env; (void)clazz;
    set_compressor_params(threshold_db, ratio, makeup_gain_db);
}

// ============================================================
// JNI: 设置自动调音（AutoTune）参数（Task 6b 新增，可选模块）
// ============================================================
// enabled=false 时模块旁路（不参与处理）；enabled=true 时启用并设置参数。

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativeSetAutoTune(
    JNIEnv* env, jclass clazz,
    jboolean enabled, jint scale, jfloat retune, jfloat speed) {
    (void)env; (void)clazz;
    ensure_default_pipeline();
    if (!g_default_pipeline) return;

    // 管线可能被 Kotlin 侧重建，节点 id 必须动态解析
    uint32_t node = resolve_default_node(MAIDMIC_MODULE_ID_AUTOTUNE, &g_default_nodes.autotune);
    if (!node) {
        LOGE("nativeSetAutoTune: autotune module not found in default pipeline");
        return;
    }

    maidmic_param_t param;

    param.type = MAIDMIC_PARAM_BOOL;
    param.value.as_bool = enabled ? true : false;
    maidmic_pipeline_set_param(g_default_pipeline, node, "autotune_enabled", param);

    param.type = MAIDMIC_PARAM_INT;
    param.value.as_int = scale;
    maidmic_pipeline_set_param(g_default_pipeline, node, "autotune_scale", param);

    param.type = MAIDMIC_PARAM_FLOAT;
    param.value.as_float = clamp(retune, 0.0f, 1.0f);
    maidmic_pipeline_set_param(g_default_pipeline, node, "autotune_retune", param);

    param.value.as_float = clamp(speed, 0.0f, 1.0f);
    maidmic_pipeline_set_param(g_default_pipeline, node, "autotune_speed", param);

    // 管线级旁路：由 enabled 控制
    maidmic_pipeline_set_module_bypass(g_default_pipeline, node, enabled ? false : true);
}

// ============================================================
// JNI: 设置噪声门参数（Task 6b 新增）
// ============================================================

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativeSetNoiseGate(
    JNIEnv* env, jclass clazz,
    jfloat threshold_db, jfloat attack_ms, jfloat release_ms) {
    (void)env; (void)clazz;
    set_noisegate_params(threshold_db, attack_ms, release_ms);
}

// ============================================================
// JNI: 设置限制器参数（Task 6b 新增）
// ============================================================

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativeSetLimiter(
    JNIEnv* env, jclass clazz,
    jfloat threshold_db, jfloat release_ms) {
    (void)env; (void)clazz;
    set_limiter_params(threshold_db, release_ms);
}

// ============================================================
// JNI: 设置存在感（Presence）参数（Task 6b 新增，可选模块）
// ============================================================
// 调用即启用该模块（解除默认 bypass）。

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativeSetPresence(
    JNIEnv* env, jclass clazz, jfloat presence_db) {
    (void)env; (void)clazz;
    ensure_default_pipeline();
    if (!g_default_pipeline) return;

    // 管线可能被 Kotlin 侧重建，节点 id 必须动态解析
    uint32_t node = resolve_default_node(MAIDMIC_MODULE_ID_PRESENCE, &g_default_nodes.presence);
    if (!node) {
        LOGE("nativeSetPresence: presence module not found in default pipeline");
        return;
    }

    maidmic_param_t param;
    param.type = MAIDMIC_PARAM_FLOAT;
    param.value.as_float = clamp(presence_db, -24.0f, 24.0f);
    maidmic_pipeline_set_param(g_default_pipeline, node, "presence_db", param);
    maidmic_pipeline_set_module_bypass(g_default_pipeline, node, false);
}

// ============================================================
// JNI: 设置声纹掩码（VoiceprintMask）参数（Task 6b 新增，可选模块）
// ============================================================
// 调用即启用该模块（解除默认 bypass）。

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativeSetVoiceprintMask(
    JNIEnv* env, jclass clazz, jfloat strength, jint mode) {
    (void)env; (void)clazz;
    ensure_default_pipeline();
    if (!g_default_pipeline) return;

    // 管线可能被 Kotlin 侧重建，节点 id 必须动态解析
    uint32_t node = resolve_default_node(MAIDMIC_MODULE_ID_VOICEPRINT_MASK, &g_default_nodes.voiceprint_mask);
    if (!node) {
        LOGE("nativeSetVoiceprintMask: voiceprint_mask module not found in default pipeline");
        return;
    }

    maidmic_param_t param;

    param.type = MAIDMIC_PARAM_FLOAT;
    param.value.as_float = clamp(strength, 0.0f, 1.0f);
    maidmic_pipeline_set_param(g_default_pipeline, node, "vp_strength", param);

    param.type = MAIDMIC_PARAM_INT;
    param.value.as_int = mode;
    maidmic_pipeline_set_param(g_default_pipeline, node, "vp_mode", param);

    maidmic_pipeline_set_module_bypass(g_default_pipeline, node, false);
}

// ============================================================
// JNI: 获取引擎处理统计（Task 6b 新增）
// ============================================================
// 返回 long[] {totalNs, totalFrames, callCount}（maidmic_pipeline_get_stats 累计值）。

JNIEXPORT jlongArray JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativeGetEngineStats(
    JNIEnv* env, jclass clazz) {
    (void)clazz;
    ensure_default_pipeline();

    jlong stats[3] = {0, 0, 0};
    if (g_default_pipeline) {
        uint64_t total_ns = 0, total_frames = 0, call_count = 0;
        maidmic_pipeline_get_stats(g_default_pipeline, &total_ns, &total_frames, &call_count);
        stats[0] = (jlong)total_ns;
        stats[1] = (jlong)total_frames;
        stats[2] = (jlong)call_count;
    }
    jlongArray result = env->NewLongArray(3);
    if (result) {
        env->SetLongArrayRegion(result, 0, 3, stats);
    }
    return result;
}

// ============================================================
// JNI: 查询 NEON 是否启用（Task 6b 新增）
// ============================================================

JNIEXPORT jboolean JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativeNeonEnabled(
    JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return maidmic_neon_enabled() ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// JNI: 处理音频（走默认管线，签名保持不变）
// ============================================================

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativeProcessAudio(
    JNIEnv* env, jclass clazz,
    jbyteArray input, jbyteArray output, jint size) {
    (void)clazz;

    int sample_count = size / 2;  // 16-bit
    if (sample_count <= 0) return;

    ensure_default_pipeline();

    jbyte* in_data = env->GetByteArrayElements(input, NULL);
    jbyte* out_data = env->GetByteArrayElements(output, NULL);

    if (!g_default_pipeline) {
        // 管线创建失败，直通
        memcpy(out_data, in_data, size);
    } else {
        // 构建缓冲区描述符
        maidmic_buffer_t in_buf;
        maidmic_buffer_t out_buf;
        memset(&in_buf, 0, sizeof(in_buf));
        memset(&out_buf, 0, sizeof(out_buf));

        in_buf.data = in_data;
        in_buf.data_bytes = (uint32_t)size;
        in_buf.owned = false;
        in_buf.meta.sample_rate = 48000;
        in_buf.meta.channels = 1;
        in_buf.meta.format = MAIDMIC_SAMPLE_S16;
        in_buf.meta.frame_count = (uint32_t)sample_count;

        out_buf.data = out_data;
        out_buf.data_bytes = (uint32_t)size;
        out_buf.owned = false;
        out_buf.meta = in_buf.meta;

        maidmic_pipeline_process(g_default_pipeline, &in_buf, &out_buf);
    }

    env->ReleaseByteArrayElements(input, in_data, JNI_ABORT);
    env->ReleaseByteArrayElements(output, out_data, 0);
}

// ============================================================
// JNI: Pipeline 管理 API（新增）
// ============================================================
// 允许 Java 层创建自定义管线、添加/移除/重排模块、设置参数、处理音频。
// 管线句柄通过 jlong 传递。

// 获取默认管线句柄（Task 4：消除双管线不同步）
// ============================================================
// 返回 g_default_pipeline 的句柄，语义与 nativePipelineCreate 一致：
// (jlong)(intptr_t)maidmic_pipeline_t*（maidmic_pipeline_create 创建）。
// Kotlin 侧 PipelineController 应使用本句柄替代 nativePipelineCreate 做模块链编辑，
// 使增删/排序/旁路/参数改动实时作用于录音 nativeProcessAudio 使用的同一条默认管线。
//
// 注意：
//  - 句柄由 JNI 生命周期管理（JNI_OnUnload 统一销毁），切勿用 nativePipelineDestroy 释放；
//  - 线程安全：g_default_pipeline 现被 UI 线程（编辑）与录音线程（process）并发访问，
//    pipeline.c 无锁，依赖"编辑与处理不同时进行"的单线程假设（见声明处注释）。
JNIEXPORT jlong JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativeGetDefaultPipeline(
    JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    ensure_default_pipeline();
    return (jlong)(intptr_t)g_default_pipeline;  // 创建失败时为 0，Kotlin 侧按 0 判失败
}

// 创建管线实例，返回句柄
JNIEXPORT jlong JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativePipelineCreate(
    JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    maidmic_pipeline_t* pipeline = maidmic_pipeline_create(MAIDMIC_PIPELINE_MODE_SIMPLE);
    if (!pipeline) {
        LOGE("nativePipelineCreate: failed to create pipeline");
        return 0;
    }
    return (jlong)(intptr_t)pipeline;
}

// 销毁管线实例
JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativePipelineDestroy(
    JNIEnv* env, jclass clazz, jlong pipeline_ptr) {
    (void)env; (void)clazz;
    if (pipeline_ptr) {
        maidmic_pipeline_destroy((maidmic_pipeline_t*)(intptr_t)pipeline_ptr);
    }
}

// 添加模块到管线末尾，返回节点索引
JNIEXPORT jint JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativePipelineAddModule(
    JNIEnv* env, jclass clazz, jlong pipeline_ptr, jint module_id) {
    (void)env; (void)clazz;
    maidmic_pipeline_t* pipeline = (maidmic_pipeline_t*)(intptr_t)pipeline_ptr;
    if (!pipeline) return -1;

    const maidmic_module_t* module = lookup_module_by_id((uint32_t)module_id);
    if (!module) {
        LOGE("nativePipelineAddModule: unknown module ID %d", module_id);
        return -1;
    }

    uint32_t node_id = maidmic_pipeline_add_module(pipeline, module);
    if (node_id == 0) {
        LOGE("nativePipelineAddModule: add_module failed for ID %d", module_id);
        return -1;
    }

    // 返回节点在管线中的索引（= 数量 - 1）
    return (jint)(maidmic_pipeline_get_module_count(pipeline) - 1);
}

// 按索引移除模块
JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativePipelineRemoveModule(
    JNIEnv* env, jclass clazz, jlong pipeline_ptr, jint index) {
    (void)env; (void)clazz;
    maidmic_pipeline_t* pipeline = (maidmic_pipeline_t*)(intptr_t)pipeline_ptr;
    if (!pipeline) return;

    const maidmic_dag_node_t* node = maidmic_pipeline_get_module_at(pipeline, (uint32_t)index);
    if (node) {
        maidmic_pipeline_remove_module(pipeline, node->node_id);
    } else {
        LOGE("nativePipelineRemoveModule: invalid index %d", index);
    }
}

// 重排序（from → to，通过 swap 实现）
JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativePipelineReorder(
    JNIEnv* env, jclass clazz, jlong pipeline_ptr, jint from, jint to) {
    (void)env; (void)clazz;
    maidmic_pipeline_t* pipeline = (maidmic_pipeline_t*)(intptr_t)pipeline_ptr;
    if (!pipeline) return;

    const maidmic_dag_node_t* node_from = maidmic_pipeline_get_module_at(pipeline, (uint32_t)from);
    const maidmic_dag_node_t* node_to = maidmic_pipeline_get_module_at(pipeline, (uint32_t)to);
    if (node_from && node_to) {
        maidmic_pipeline_swap_modules(pipeline, node_from->node_id, node_to->node_id);
    } else {
        LOGE("nativePipelineReorder: invalid index from=%d to=%d", from, to);
    }
}

// 交换两个模块位置
JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativePipelineSwap(
    JNIEnv* env, jclass clazz, jlong pipeline_ptr, jint i, jint j) {
    (void)env; (void)clazz;
    maidmic_pipeline_t* pipeline = (maidmic_pipeline_t*)(intptr_t)pipeline_ptr;
    if (!pipeline) return;

    const maidmic_dag_node_t* node_i = maidmic_pipeline_get_module_at(pipeline, (uint32_t)i);
    const maidmic_dag_node_t* node_j = maidmic_pipeline_get_module_at(pipeline, (uint32_t)j);
    if (node_i && node_j) {
        maidmic_pipeline_swap_modules(pipeline, node_i->node_id, node_j->node_id);
    } else {
        LOGE("nativePipelineSwap: invalid index i=%d j=%d", i, j);
    }
}

// 设置模块参数（按索引）
JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativePipelineSetParam(
    JNIEnv* env, jclass clazz, jlong pipeline_ptr, jint index, jstring param_key, jfloat value) {
    (void)clazz;
    maidmic_pipeline_t* pipeline = (maidmic_pipeline_t*)(intptr_t)pipeline_ptr;
    if (!pipeline || !param_key) return;

    const char* key = env->GetStringUTFChars(param_key, NULL);
    if (!key) return;

    const maidmic_dag_node_t* node = maidmic_pipeline_get_module_at(pipeline, (uint32_t)index);
    if (node) {
        maidmic_param_t param;
        memset(&param, 0, sizeof(param));
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = value;
        maidmic_pipeline_set_param(pipeline, node->node_id, key, param);
    } else {
        LOGE("nativePipelineSetParam: invalid index %d", index);
    }

    env->ReleaseStringUTFChars(param_key, key);
}

// 按索引设置模块旁路（Task 4：编辑器即默认管线）
// ============================================================
// nativePipelineSetParam 固定传 MAIDMIC_PARAM_FLOAT，无法表达节点级 bypass 标志，
// 故新增本入口，直接转发到 maidmic_pipeline_set_module_bypass（按 node_id）。
// Kotlin 侧 PipelineController.setBypass 调用，使旁路改动实时作用于录音管线。
JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativePipelineSetModuleBypass(
    JNIEnv* env, jclass clazz, jlong pipeline_ptr, jint index, jboolean bypass) {
    (void)env; (void)clazz;
    maidmic_pipeline_t* pipeline = (maidmic_pipeline_t*)(intptr_t)pipeline_ptr;
    if (!pipeline) return;

    const maidmic_dag_node_t* node = maidmic_pipeline_get_module_at(pipeline, (uint32_t)index);
    if (node) {
        maidmic_pipeline_set_module_bypass(pipeline, node->node_id, bypass ? true : false);
    } else {
        LOGE("nativePipelineSetModuleBypass: invalid index %d", index);
    }
}

// 处理音频（自定义管线）
JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativePipelineProcess(
    JNIEnv* env, jclass clazz, jlong pipeline_ptr, jbyteArray input, jbyteArray output, jint size) {
    (void)clazz;

    int sample_count = size / 2;
    if (sample_count <= 0) return;

    maidmic_pipeline_t* pipeline = (maidmic_pipeline_t*)(intptr_t)pipeline_ptr;
    if (!pipeline) return;

    jbyte* in_data = env->GetByteArrayElements(input, NULL);
    jbyte* out_data = env->GetByteArrayElements(output, NULL);

    maidmic_buffer_t in_buf;
    maidmic_buffer_t out_buf;
    memset(&in_buf, 0, sizeof(in_buf));
    memset(&out_buf, 0, sizeof(out_buf));

    in_buf.data = in_data;
    in_buf.data_bytes = (uint32_t)size;
    in_buf.owned = false;
    in_buf.meta.sample_rate = 48000;
    in_buf.meta.channels = 1;
    in_buf.meta.format = MAIDMIC_SAMPLE_S16;
    in_buf.meta.frame_count = (uint32_t)sample_count;

    out_buf.data = out_data;
    out_buf.data_bytes = (uint32_t)size;
    out_buf.owned = false;
    out_buf.meta = in_buf.meta;

    maidmic_pipeline_process(pipeline, &in_buf, &out_buf);

    env->ReleaseByteArrayElements(input, in_data, JNI_ABORT);
    env->ReleaseByteArrayElements(output, out_data, 0);
}

// 重置管线中所有模块的状态
JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativePipelineReset(
    JNIEnv* env, jclass clazz, jlong pipeline_ptr) {
    (void)env; (void)clazz;
    maidmic_pipeline_t* pipeline = (maidmic_pipeline_t*)(intptr_t)pipeline_ptr;
    if (!pipeline) return;
    maidmic_pipeline_reset(pipeline);
}

// ============================================================
// JNI 初始化
// ============================================================

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)vm; (void)reserved;
    LOGI("MaidMic JNI loaded (modular pipeline)");
    // Task 6b: NEON 能力日志（maidmic_neon_enabled 声明于 module.h，实现于 lpc.c）
    __android_log_print(ANDROID_LOG_INFO, "MaidMicEngine", "NEON enabled: %d", maidmic_neon_enabled());
    // 提前创建默认管线，避免首次处理音频时延迟
    ensure_default_pipeline();
    return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM* vm, void* reserved) {
    (void)vm; (void)reserved;
    // 销毁默认管线
    if (g_default_pipeline) {
        maidmic_pipeline_destroy(g_default_pipeline);
        g_default_pipeline = NULL;
    }
    LOGI("MaidMic JNI unloaded");
}

// ============================================================
// 以下为存根函数（满足链接需要）
// Stub functions (required for linking)
// ============================================================

JNIEXPORT jlong JNICALL
Java_aoeck_dwyai_com_bridge_shizuku_ShizukuMicBridge_nativeCreateEngine(
    JNIEnv* env, jobject thiz, jint, jint, jint, jint) {
    (void)env; (void)thiz;
    ensure_default_pipeline();
    return (jlong)(intptr_t)g_default_pipeline;
}

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_bridge_shizuku_ShizukuMicBridge_nativeDestroyEngine(
    JNIEnv* env, jobject thiz, jlong) {
    (void)env; (void)thiz;
    // 默认管线由 JNI_OnUnload 管理，这里不销毁
}

JNIEXPORT jint JNICALL
Java_aoeck_dwyai_com_bridge_shizuku_ShizukuMicBridge_nativeReadAudio(
    JNIEnv* env, jobject thiz, jlong, jbyteArray, jint) {
    (void)env; (void)thiz;
    return 0;
}

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_bridge_shizuku_ShizukuMicBridge_nativeUpdateConfig(
    JNIEnv* env, jobject thiz, jlong, jint, jint, jint, jint) {
    (void)env; (void)thiz;
}

JNIEXPORT jfloat JNICALL
Java_aoeck_dwyai_com_bridge_shizuku_ShizukuMicBridge_nativeGetLatencyMs(
    JNIEnv* env, jobject thiz, jlong) {
    (void)env; (void)thiz;
    return 0.0f;
}

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_bridge_accessibility_AccessibilityMicBridge_nativeProcess(
    JNIEnv* env, jobject thiz, jlong, jbyteArray, jbyteArray, jint) {
    (void)env; (void)thiz;
}

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_bridge_root_RootMicBridge_nativeProcess(
    JNIEnv* env, jobject thiz, jlong, jbyteArray, jbyteArray, jint) {
    (void)env; (void)thiz;
}

JNIEXPORT jint JNICALL
Java_aoeck_dwyai_com_bridge_root_RootMicBridge_nativeWriteToVirtualDevice(
    JNIEnv* env, jobject thiz, jint, jbyteArray, jint) {
    (void)env; (void)thiz;
    return -1;
}

JNIEXPORT jdouble JNICALL
Java_aoeck_dwyai_com_plugins_lua_LuaPluginSandbox_nativeGetEngineParam(
    JNIEnv* env, jobject thiz, jstring) {
    (void)env; (void)thiz;
    return 0.0;
}

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_plugins_lua_LuaPluginSandbox_nativeSetEngineParam(
    JNIEnv* env, jobject thiz, jstring, jfloat) {
    (void)env; (void)thiz;
}

JNIEXPORT jstring JNICALL
Java_aoeck_dwyai_com_plugins_lua_LuaPluginSandbox_nativeLoadPreset(
    JNIEnv* env, jobject thiz, jstring, jstring) {
    (void)env; (void)thiz;
    return NULL;
}

#ifdef __cplusplus
}
#endif
