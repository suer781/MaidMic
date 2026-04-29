// maidmic-engine/src/api/maidmic_jni.cpp
// MaidMic JNI 桥 — 音频处理核心
// ============================================================
// JNI 函数命名：Java_<package>_<class>_<method>

#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstring>

#define LOG_TAG "MaidMic-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef __cplusplus
extern "C" {
#endif

// ============================================================
// DSP 全局状态（EQ 参数）
// DSP global state
// ============================================================

static struct {
    float gain_db;        // 增益 dB (-10 ~ 10)
    float bass_db;        // 低音 dB (-10 ~ 10)
    float treble_db;      // 高音 dB (-10 ~ 10)
    float reverb_mix;     // 混响混合比 (0 ~ 1)
    int pitch_semitones;  // 变调半音 (-12 ~ 12)

    // 滤波器状态（用于 Bass/Treble shelving filters）
    float bass_state;     // 低通滤波器记忆
    float treble_state;   // 高通滤波器记忆

    // 混响延迟线（简单实现）
    float reverb_buf[48000]; // 1秒 @48kHz
    int reverb_pos;
} dsp_state = {0, 0, 0, 0, 0, 0, 0, {0}, 0};

// ============================================================
// Utils
// ============================================================

static inline float db_to_linear(float db) {
    return powf(10.0f, db / 20.0f);
}

static inline float clamp(float v, float min, float max) {
    return v < min ? min : (v > max ? max : v);
}

// ============================================================
// 设置 EQ 参数
// Set EQ parameters
// ============================================================

void set_eq_params(float gain_db, float bass_db, float treble_db,
                   float reverb_mix, int pitch_semitones) {
    dsp_state.gain_db = gain_db;
    dsp_state.bass_db = bass_db;
    dsp_state.treble_db = treble_db;
    dsp_state.reverb_mix = clamp(reverb_mix, 0.0f, 1.0f);
    dsp_state.pitch_semitones = pitch_semitones;
}

// ============================================================
// 处理一帧音频（16-bit PCM）
// Process one audio frame (16-bit PCM)
// ============================================================

void process_audio_frame(int16_t* buffer, int frame_count, int sample_rate) {
    if (!buffer || frame_count <= 0) return;

    // --- Step 1: 增益 ---
    float gain_linear = db_to_linear(dsp_state.gain_db);
    if (fabsf(gain_linear - 1.0f) > 0.001f) {
        for (int i = 0; i < frame_count; i++) {
            float sample = buffer[i] * gain_linear;
            sample = clamp(sample, -32768.0f, 32767.0f);
            buffer[i] = (int16_t)sample;
        }
    }

    // --- Step 2: 低音（一阶低通 shelving）---
    if (fabsf(dsp_state.bass_db) > 0.5f) {
        float bass_gain = db_to_linear(dsp_state.bass_db);
        float alpha = 0.3f; // 截止频率约 300Hz
        for (int i = 0; i < frame_count; i++) {
            // 提取低频
            float lp = dsp_state.bass_state + alpha * (buffer[i] - dsp_state.bass_state);
            dsp_state.bass_state = lp;
            // 低频 * gain + 高频原样
            float hp = buffer[i] - lp;
            buffer[i] = (int16_t)clamp(lp * bass_gain + hp, -32768.0f, 32767.0f);
        }
    }

    // --- Step 3: 高音（一阶高通 shelving）---
    if (fabsf(dsp_state.treble_db) > 0.5f) {
        float treble_gain = db_to_linear(dsp_state.treble_db);
        float alpha = 0.15f; // 截止频率约 3kHz
        for (int i = 0; i < frame_count; i++) {
            float hp = dsp_state.treble_state + alpha * (buffer[i] - dsp_state.treble_state);
            dsp_state.treble_state = hp;
            float lp = buffer[i] - hp;
            buffer[i] = (int16_t)clamp(lp + hp * treble_gain, -32768.0f, 32767.0f);
        }
    }

    // --- Step 4: 混响（简单延迟线）---
    if (dsp_state.reverb_mix > 0.01f) {
        int delay_samples = sample_rate / 4; // 250ms 延迟
        int buf_size = delay_samples < 48000 ? delay_samples : 48000;
        for (int i = 0; i < frame_count; i++) {
            // 从延迟线读取
            int read_pos = dsp_state.reverb_pos;
            float wet = dsp_state.reverb_buf[read_pos] * 0.6f;

            // 写入延迟线（输入 + 反馈）
            dsp_state.reverb_buf[dsp_state.reverb_pos] =
                buffer[i] * 0.4f + wet * 0.6f;

            // 混合干湿
            buffer[i] = (int16_t)clamp(
                buffer[i] * (1.0f - dsp_state.reverb_mix) + wet * dsp_state.reverb_mix,
                -32768.0f, 32767.0f);

            dsp_state.reverb_pos = (dsp_state.reverb_pos + 1) % buf_size;
        }
    }

    // --- Step 5: 变调（线性插值重采样）---
    if (dsp_state.pitch_semitones != 0) {
        // 半音转频率比: 2^(n/12)
        float pitch_ratio = powf(2.0f, dsp_state.pitch_semitones / 12.0f);
        int out_count = (int)(frame_count / pitch_ratio);

        if (out_count > 0 && out_count <= frame_count) {
            int16_t* temp = new int16_t[frame_count];
            memcpy(temp, buffer, frame_count * sizeof(int16_t));

            for (int i = 0; i < out_count; i++) {
                float src_pos = i * pitch_ratio;
                int src_idx = (int)src_pos;
                float frac = src_pos - src_idx;

                if (src_idx + 1 < frame_count) {
                    // 线性插值
                    buffer[i] = (int16_t)clamp(
                        temp[src_idx] * (1.0f - frac) + temp[src_idx + 1] * frac,
                        -32768.0f, 32767.0f);
                } else if (src_idx < frame_count) {
                    buffer[i] = temp[src_idx];
                }
            }
            // 填充剩余
            for (int i = out_count; i < frame_count; i++) {
                buffer[i] = 0;
            }
            delete[] temp;
        }
    }
}

// ============================================================
// 频响曲线引擎（HxCore 风格）
// Frequency Response Curve Engine (HxCore-inspired)
//
// 级联 Biquad Peaking 滤波器实现多段图形均衡器。
// 支持 10 个标准频段：31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz
// 每段独立增益，支持前级预渲染曲线 + 主 EQ 曲线两层叠加。
// ============================================================

#define FC_BAND_COUNT 10
#define FC_BIQUAD_STATE_SIZE 2  // x[n-1], x[n-2], y[n-1], y[n-2] per filter

// 频响曲线状态
static struct {
    // 主 EQ 各频段增益 (dB)
    float bands[FC_BAND_COUNT];
    
    // 前级预渲染曲线增益 (dB) — 叠加在主 EQ 之前
    float pre_render[FC_BAND_COUNT];
    
    // 各频段中心频率 (Hz)
    float freqs[FC_BAND_COUNT];
    
    // Biquad 滤波器状态（每个滤波器 4 个状态变量）
    float biquad_state[FC_BAND_COUNT][4];
    
    // 前级预渲染滤波器状态
    float pre_biquad_state[FC_BAND_COUNT][4];
    
    // 上次计算的 biquad 系数
    float b_coeffs[FC_BAND_COUNT][3];  // b0, b1, b2
    float a_coeffs[FC_BAND_COUNT][3];  // a1, a2 (a0 归一化为 1)
    
    // 是否已初始化系数
    bool coeffs_valid;
    
    // 当前采样率
    int sample_rate;
} fc_state = {
    {0}, {0},
    {31.0f, 62.0f, 125.0f, 250.0f, 500.0f, 1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f},
    {{0}}, {{0}}, {{0}}, {{0}},
    false, 48000
};

// Biquad Peaking Filter 系数计算
// Q = 0.707 (Butterworth), 带宽约 1 倍频程
static void calc_biquad_peaking(float freq, float gain_db, float Q, float sr,
                                 float b[3], float a[3]) {
    if (gain_db == 0.0f || freq <= 0.0f || freq >= sr / 2.0f) {
        // 直通系数
        b[0] = 1.0f; b[1] = 0.0f; b[2] = 0.0f;
        a[0] = 0.0f; a[1] = 0.0f;  // a[2] unused, a0 implied = 1
        return;
    }
    
    float A = powf(10.0f, gain_db / 40.0f);
    float omega = 2.0f * (float)M_PI * freq / sr;
    float sin_omega = sinf(omega);
    float cos_omega = cosf(omega);
    float alpha = sin_omega / (2.0f * Q);
    
    float b0 = 1.0f + alpha * A;
    float b1 = -2.0f * cos_omega;
    float b2 = 1.0f - alpha * A;
    float a0 = 1.0f + alpha / A;
    float a1 = -2.0f * cos_omega;
    float a2 = 1.0f - alpha / A;
    
    // 归一化 (a0 = 1)
    b[0] = b0 / a0;
    b[1] = b1 / a0;
    b[2] = b2 / a0;
    a[0] = a1 / a0;
    a[1] = a2 / a0;
}

// 重新计算所有 Biquad 系数
static void fc_update_coeffs() {
    for (int i = 0; i < FC_BAND_COUNT; i++) {
        calc_biquad_peaking(
            fc_state.freqs[i],
            fc_state.bands[i],
            0.707f,  // Q = 0.707 (Butterworth)
            (float)fc_state.sample_rate,
            fc_state.b_coeffs[i],
            fc_state.a_coeffs[i]
        );
    }
    fc_state.coeffs_valid = true;
}

// 通过 Biquad 滤波器处理单个样本
static inline float biquad_process(float sample, const float b[3], const float a[3],
                                    float state[4]) {
    // state[0] = x[n-1], state[1] = x[n-2]
    // state[2] = y[n-1], state[3] = y[n-2]
    float y = b[0] * sample + state[0] * b[1] + state[1] * b[2]
              - state[2] * a[0] - state[3] * a[1];
    state[1] = state[0]; state[0] = sample;
    state[3] = state[2]; state[2] = y;
    return y;
}

// 通过级联 Biquad 滤波器处理一个样本（主 EQ）
static inline float fc_process_sample(float sample) {
    float y = sample;
    // 前级预渲染（pre-render）
    for (int i = 0; i < FC_BAND_COUNT && fc_state.pre_render[i] != 0.0f; i++) {
        float b[3], a[3];
        calc_biquad_peaking(fc_state.freqs[i], fc_state.pre_render[i],
                            0.707f, (float)fc_state.sample_rate, b, a);
        // 使用独立的状态数组
        y = biquad_process(y, b, a, fc_state.pre_biquad_state[i]);
    }
    // 主 EQ
    for (int i = 0; i < FC_BAND_COUNT && fc_state.bands[i] != 0.0f; i++) {
        if (!fc_state.coeffs_valid) fc_update_coeffs();
        y = biquad_process(y, fc_state.b_coeffs[i], fc_state.a_coeffs[i],
                           fc_state.biquad_state[i]);
    }
    return y;
}

// 设置频响曲线参数
void set_freq_curve_params(const float* bands, const float* pre_render, int sample_rate) {
    for (int i = 0; i < FC_BAND_COUNT; i++) {
        fc_state.bands[i] = bands[i];
        fc_state.pre_render[i] = pre_render ? pre_render[i] : 0.0f;
        // 重置滤波器状态
        fc_state.biquad_state[i][0] = 0;
        fc_state.biquad_state[i][1] = 0;
        fc_state.biquad_state[i][2] = 0;
        fc_state.biquad_state[i][3] = 0;
        fc_state.pre_biquad_state[i][0] = 0;
        fc_state.pre_biquad_state[i][1] = 0;
        fc_state.pre_biquad_state[i][2] = 0;
        fc_state.pre_biquad_state[i][3] = 0;
    }
    fc_state.sample_rate = sample_rate;
    fc_state.coeffs_valid = false;  // 强制重新计算
    fc_update_coeffs();
}

// 频响曲线引擎处理一帧音频（16-bit PCM）
void process_freq_curve_frame(int16_t* buffer, int frame_count) {
    if (!buffer || frame_count <= 0) return;
    
    for (int i = 0; i < frame_count; i++) {
        float sample = (float)buffer[i];
        sample = fc_process_sample(sample);
        sample = clamp(sample, -32768.0f, 32767.0f);
        buffer[i] = (int16_t)sample;
    }
    
    // 混响（复用主 DSP 的混响状态）
    if (dsp_state.reverb_mix > 0.01f) {
        int delay_samples = dsp_state.reverb_pos > 0 ? dsp_state.reverb_pos : 48000;
        int buf_size = delay_samples < 48000 ? delay_samples : 48000;
        if (buf_size <= 0) buf_size = 48000;
        for (int i = 0; i < frame_count; i++) {
            int read_pos = dsp_state.reverb_pos;
            float wet = dsp_state.reverb_buf[read_pos] * 0.6f;
            dsp_state.reverb_buf[dsp_state.reverb_pos] =
                buffer[i] * 0.4f + wet * 0.6f;
            buffer[i] = (int16_t)clamp(
                buffer[i] * (1.0f - dsp_state.reverb_mix) + wet * dsp_state.reverb_mix,
                -32768.0f, 32767.0f);
            dsp_state.reverb_pos = (dsp_state.reverb_pos + 1) % buf_size;
        }
    }
    
    // 变调（复用主 DSP 逻辑）
    if (dsp_state.pitch_semitones != 0) {
        float pitch_ratio = powf(2.0f, dsp_state.pitch_semitones / 12.0f);
        int out_count = (int)(frame_count / pitch_ratio);
        if (out_count > 0 && out_count <= frame_count) {
            int16_t* temp = new int16_t[frame_count];
            memcpy(temp, buffer, frame_count * sizeof(int16_t));
            for (int i = 0; i < out_count; i++) {
                float src_pos = i * pitch_ratio;
                int src_idx = (int)src_pos;
                float frac = src_pos - src_idx;
                if (src_idx + 1 < frame_count) {
                    buffer[i] = (int16_t)clamp(
                        temp[src_idx] * (1.0f - frac) + temp[src_idx + 1] * frac,
                        -32768.0f, 32767.0f);
                } else if (src_idx < frame_count) {
                    buffer[i] = temp[src_idx];
                }
            }
            for (int i = out_count; i < frame_count; i++) buffer[i] = 0;
            delete[] temp;
        }
    }
}

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativeSetEqParams(
    JNIEnv* env, jclass clazz,
    jfloat gain_db, jfloat bass_db, jfloat treble_db,
    jfloat reverb_mix, jint pitch_semitones) {
    (void)env; (void)clazz;
    set_eq_params(gain_db, bass_db, treble_db, reverb_mix, pitch_semitones);
}

// ============================================================
// JNI: 处理音频（被所有 bridge 共享）
// 输入/输出都是 16-bit PCM byte array
// ============================================================

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativeProcessAudio(
    JNIEnv* env, jclass clazz,
    jbyteArray input, jbyteArray output, jint size) {
    (void)clazz;

    int sample_count = size / 2; // 16-bit
    if (sample_count <= 0) return;

    // 拷贝输入数据
    jbyte* in_data = env->GetByteArrayElements(input, NULL);
    jbyte* out_data = env->GetByteArrayElements(output, NULL);

    memcpy(out_data, in_data, size);

    // 处理
    process_audio_frame((int16_t*)out_data, sample_count, 48000);

    env->ReleaseByteArrayElements(input, in_data, JNI_ABORT);
    env->ReleaseByteArrayElements(output, out_data, 0);
}

// ============================================================
// JNI: 设置频响曲线参数
// JNI: Set frequency curve parameters
// ============================================================
// bands: float[10] — 10 频段增益 (dB)
// pre_render: float[10] — 前级预渲染增益 (dB)，可为 NULL
// sample_rate: int

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativeSetFreqCurve(
    JNIEnv* env, jclass clazz,
    jfloatArray bands, jfloatArray pre_render, jint sample_rate) {
    (void)clazz;
    
    float band_values[FC_BAND_COUNT];
    float pre_values[FC_BAND_COUNT] = {0};
    
    jfloat* band_elements = env->GetFloatArrayElements(bands, NULL);
    for (int i = 0; i < FC_BAND_COUNT && i < env->GetArrayLength(bands); i++) {
        band_values[i] = band_elements[i];
    }
    env->ReleaseFloatArrayElements(bands, band_elements, JNI_ABORT);
    
    if (pre_render != NULL) {
        jfloat* pre_elements = env->GetFloatArrayElements(pre_render, NULL);
        for (int i = 0; i < FC_BAND_COUNT && i < env->GetArrayLength(pre_render); i++) {
            pre_values[i] = pre_elements[i];
        }
        env->ReleaseFloatArrayElements(pre_render, pre_elements, JNI_ABORT);
    }
    
    set_freq_curve_params(band_values, pre_values, sample_rate);
}

// ============================================================
// JNI: 频响曲线引擎处理音频
// JNI: Process audio with frequency curve engine
// ============================================================

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_NativeAudioProcessor_nativeProcessFreqCurve(
    JNIEnv* env, jclass clazz,
    jbyteArray input, jbyteArray output, jint size) {
    (void)clazz;
    
    int sample_count = size / 2;
    if (sample_count <= 0) return;
    
    jbyte* in_data = env->GetByteArrayElements(input, NULL);
    jbyte* out_data = env->GetByteArrayElements(output, NULL);
    
    memcpy(out_data, in_data, size);
    process_freq_curve_frame((int16_t*)out_data, sample_count);
    
    env->ReleaseByteArrayElements(input, in_data, JNI_ABORT);
    env->ReleaseByteArrayElements(output, out_data, 0);
}

// ============================================================
// JNI 初始化
// ============================================================

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)vm; (void)reserved;
    LOGI("MaidMic JNI loaded");
    return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM* vm, void* reserved) {
    (void)vm; (void)reserved;
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
    return (jlong)(intptr_t)&dsp_state;
}

JNIEXPORT void JNICALL
Java_aoeck_dwyai_com_bridge_shizuku_ShizukuMicBridge_nativeDestroyEngine(
    JNIEnv* env, jobject thiz, jlong) {
    (void)env; (void)thiz;
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
