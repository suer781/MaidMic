// maidmic-engine/src/dsp/limiter.c
// Echio 引擎限制器模块
// Echio Engine Limiter Module
//
// 限制器：Look-ahead 峰值检测 + 增益衰减，把信号峰值限制在阈值以内，
// 不产生削波失真，释放过程平滑。
// 算法：
//   1. 峰值包络（一阶平滑：快 attack 0.5ms、慢 release 由参数控制）跟踪输入峰值；
//   2. 峰值包络超过阈值时目标增益 = threshold / env，否则目标增益 = 1.0；
//   3. 增益再做一阶平滑（快 attack 慢 release）；
//   4. 输出 = 延迟线（64 样本 lookahead）读出的信号 × 增益。
// 延迟线的意义：峰值检测用"当前"输入，而输出信号滞后 64 样本，
// 使增益衰减在峰值到达输出端之前就已生效，避免过冲。
//
// 参数：
//   limiter_threshold — 阈值 dB (-12 ~ 0，默认 -3)
//   limiter_release   — 释放时间 ms (0 ~ 1000，默认 100)
//   bypass            — 旁路开关
// （attack 为内部固定常量 0.5ms，不暴露参数）

#include "maidmic/module.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

// ============================================================
// 常量
// ============================================================

#define LIM_LOOKAHEAD_SAMPLES 64     // lookahead 延迟线长度（每声道样本数）
#define LIM_ATTACK_TAU_MS     0.5f   // 固定快 attack 时间常数 0.5ms

// ============================================================
// 辅助函数
// ============================================================

static inline float clampf(float v, float min, float max) {
    return v < min ? min : (v > max ? max : v);
}

static inline float db_to_linear(float db) {
    return powf(10.0f, db / 20.0f);
}

// 毫秒时间常数 → 一阶平滑系数 alpha = 1 - exp(-1/(tau*s))，0ms 表示立即到位
static inline float ms_to_alpha(float ms, float sample_rate) {
    if (ms <= 0.0f) return 1.0f;
    float tau = ms * 0.001f;
    return 1.0f - expf(-1.0f / (tau * (float)sample_rate));
}

// ============================================================
// 模块实例数据
// ============================================================

typedef struct {
    float threshold_db;   // 阈值 dB（目标值）
    float release_ms;     // 释放时间 ms（目标值）
    bool bypass;          // 旁路开关
    // 参数平滑器：平滑"计算中间量"（线性阈值、释放 alpha 系数），
    // 在 set_param 时算好目标值，process 热路径中逐样本逼近，消除 zipper 噪声
    maidmic_ramp_t threshold_ramp;  // 线性阈值平滑器
    maidmic_ramp_t release_ramp;    // 释放系数 alpha 平滑器
    float alpha_attack;             // 固定快 attack 系数（setup 时按采样率计算）
    float* peak_env;                // 峰值包络，按声道隔离（每声道一个）
    float* gain;                    // 增益，按声道隔离（每声道一个）
    float* delay_line;              // lookahead 延迟线（交错样本，长度 = 64 × 声道数）
    uint32_t delay_len;             // 延迟线长度（交错样本数）
    uint32_t write_pos;             // 延迟线环形写/读位置（先读后写）
    uint32_t sample_rate;
    uint16_t channels;
} limiter_data_t;

// ============================================================
// 参数定义
// ============================================================

static const maidmic_param_t limiter_params[] = {
    {
        .key = "limiter_threshold",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = -3.0f,
        .min = -12.0f,
        .max = 0.0f,
        .unit = "dB",
    },
    {
        .key = "limiter_release",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 100.0f,
        .min = 0.0f,
        .max = 1000.0f,
        .unit = "ms",
    },
    {
        .key = "bypass",
        .type = MAIDMIC_PARAM_BOOL,
        .value.as_bool = false,
        .min = 0.0f,
        .max = 1.0f,
        .unit = "",
    },
    { .key = NULL },  // 终止标记
};

// ============================================================
// vtable 实现
// ============================================================

static void* limiter_create(void) {
    limiter_data_t* l = (limiter_data_t*)calloc(1, sizeof(limiter_data_t));
    if (!l) return NULL;
    l->threshold_db = -3.0f;
    l->release_ms = 100.0f;
    l->bypass = false;
    l->peak_env = NULL;
    l->gain = NULL;
    l->delay_line = NULL;  // setup 时按声道数分配
    l->delay_len = 0;
    l->write_pos = 0;
    l->alpha_attack = 0.0f;  // setup 时按实际采样率计算
    // 阈值换算不需要采样率，create 时即可初始化
    maidmic_ramp_init(&l->threshold_ramp, db_to_linear(l->threshold_db));
    // 释放 alpha 依赖采样率，先占位，setup 时刷新目标值
    maidmic_ramp_init(&l->release_ramp, 1.0f);
    return l;
}

static void limiter_destroy(void* userdata) {
    limiter_data_t* l = (limiter_data_t*)userdata;
    free(l->peak_env);
    free(l->gain);
    free(l->delay_line);
    free(l);
}

static bool limiter_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    limiter_data_t* l = (limiter_data_t*)userdata;
    l->sample_rate = sample_rate;
    l->alpha_attack = ms_to_alpha(LIM_ATTACK_TAU_MS, sample_rate);
    // 用真实采样率刷新释放 alpha 目标（set_param 可能早于 setup 被调用）
    maidmic_ramp_set_target(&l->release_ramp, ms_to_alpha(l->release_ms, sample_rate));
    // 声道状态按声道数隔离：声道数变化或首次调用时（重新）分配并清零
    if (!l->peak_env || channels != l->channels) {
        float* new_peak = (float*)realloc(l->peak_env, (size_t)channels * sizeof(float));
        if (!new_peak) return false;
        l->peak_env = new_peak;
        memset(l->peak_env, 0, (size_t)channels * sizeof(float));
        float* new_gain = (float*)realloc(l->gain, (size_t)channels * sizeof(float));
        if (!new_gain) return false;
        l->gain = new_gain;
        memset(l->gain, 0, (size_t)channels * sizeof(float));
        // 延迟线长度 = lookahead 样本数 × 声道数（交错布局），扩容时整体清零
        uint32_t new_len = LIM_LOOKAHEAD_SAMPLES * (uint32_t)channels;
        float* new_delay = (float*)realloc(l->delay_line, (size_t)new_len * sizeof(float));
        if (!new_delay) return false;
        l->delay_line = new_delay;
        memset(l->delay_line, 0, (size_t)new_len * sizeof(float));
        l->delay_len = new_len;
        l->write_pos = 0;
    }
    l->channels = channels;
    return true;
}

static uint32_t limiter_get_param_count(void* userdata) {
    (void)userdata;
    return 3;  // limiter_threshold + limiter_release + bypass
}

static const maidmic_param_t* limiter_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 3) return &limiter_params[index];
    return NULL;
}

static bool limiter_set_param(void* userdata, const char* key, maidmic_param_t value) {
    limiter_data_t* l = (limiter_data_t*)userdata;

    if (strcmp(key, "limiter_threshold") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        l->threshold_db = clampf(value.value.as_float, -12.0f, 0.0f);
        // 只更新线性阈值目标值，process 中逐样本平滑逼近
        maidmic_ramp_set_target(&l->threshold_ramp, db_to_linear(l->threshold_db));
        return true;
    }
    if (strcmp(key, "limiter_release") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        l->release_ms = clampf(value.value.as_float, 0.0f, 1000.0f);
        // alpha 换算需要采样率；setup 尚未调用时由 setup/process 补算目标
        if (l->sample_rate > 0) {
            maidmic_ramp_set_target(&l->release_ramp, ms_to_alpha(l->release_ms, l->sample_rate));
        }
        return true;
    }
    if (strcmp(key, "bypass") == 0 && value.type == MAIDMIC_PARAM_BOOL) {
        l->bypass = value.value.as_bool;
        return true;
    }

    return false;
}

static maidmic_param_t limiter_get_param(void* userdata, const char* key) {
    limiter_data_t* l = (limiter_data_t*)userdata;
    maidmic_param_t param = {0};

    if (strcmp(key, "limiter_threshold") == 0) {
        param.key = "limiter_threshold";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = l->threshold_db;
        param.min = -12.0f;
        param.max = 0.0f;
        param.unit = "dB";
    } else if (strcmp(key, "limiter_release") == 0) {
        param.key = "limiter_release";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = l->release_ms;
        param.min = 0.0f;
        param.max = 1000.0f;
        param.unit = "ms";
    } else if (strcmp(key, "bypass") == 0) {
        param.key = "bypass";
        param.type = MAIDMIC_PARAM_BOOL;
        param.value.as_bool = l->bypass;
    }

    return param;
}

// ============================================================
// 核心：音频处理
// ============================================================
// 每声道独立处理链（峰值包络、增益均按声道隔离，参数平滑器共享）：
//   1. 峰值包络（快 attack 0.5ms / 慢 release）跟踪 |x|；
//   2. env 超过阈值时目标增益 = threshold/env，否则 1.0；
//   3. 增益一阶平滑（快 attack 慢 release）；
//   4. 先读后写环形延迟线，输出 = 64 样本前的信号 × 增益。
// 支持 F32 / S16 两种格式，原地处理（input == output）安全。

static bool limiter_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    limiter_data_t* l = (limiter_data_t*)userdata;

    if (l->bypass) {
        // 旁路时直接复制（原地调用时跳过自拷）
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    // 阈值调到上限 0dB：任何不超过满刻度的信号都不会触发限制 → 直通
    // （同时要求阈值平滑已到位，避免平滑中途跳过硬逼近过程）
    if (l->threshold_db >= 0.0f && l->threshold_ramp.current == l->threshold_ramp.target) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    // 采样率或释放时间变化后刷新 alpha 目标（兜底 set_param 早于 setup 的场景）
    float want_release = ms_to_alpha(l->release_ms, l->sample_rate);
    if (l->release_ramp.target != want_release) maidmic_ramp_set_target(&l->release_ramp, want_release);

    uint32_t frame_count = input->meta.frame_count;
    uint16_t channels = input->meta.channels;

    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        const float* src = (const float*)input->data;
        float* dst = (float*)output->data;
        for (uint32_t i = 0; i < frame_count; i++) {
            for (uint16_t ch = 0; ch < channels; ch++) {
                uint32_t idx = i * (uint32_t)channels + ch;
                // 每样本平滑推进共享参数，消除 zipper 噪声
                float threshold = maidmic_ramp_next(&l->threshold_ramp);
                float alpha_release = maidmic_ramp_next(&l->release_ramp);
                float sample = src[idx];

                // 1) 峰值包络（快 attack 慢 release），状态按声道隔离
                float abs_s = fabsf(sample);
                float alpha_env = (abs_s > l->peak_env[ch]) ? l->alpha_attack : alpha_release;
                l->peak_env[ch] += (abs_s - l->peak_env[ch]) * alpha_env;

                // 2) 目标增益：包络超过阈值时按比例压缩，否则不限制
                float target_gain = 1.0f;
                if (l->peak_env[ch] > threshold) {
                    target_gain = threshold / l->peak_env[ch];
                }

                // 3) 增益平滑（快 attack 慢 release），状态按声道隔离
                float alpha_gain = (target_gain < l->gain[ch]) ? l->alpha_attack : alpha_release;
                l->gain[ch] += (target_gain - l->gain[ch]) * alpha_gain;

                // 4) 延迟线（先读后写）：读出 64 帧前的信号，使衰减提前于峰值生效
                float delayed = l->delay_line[l->write_pos];
                l->delay_line[l->write_pos] = sample;
                l->write_pos++;
                if (l->write_pos >= l->delay_len) l->write_pos = 0;

                // 5) 输出 = 延迟信号 × 增益
                dst[idx] = delayed * l->gain[ch];
            }
        }
    } else if (input->meta.format == MAIDMIC_SAMPLE_S16) {
        // 16-bit 整数：先归一化到 -1~1 域处理，再转回并钳位
        const int16_t* src = (const int16_t*)input->data;
        int16_t* dst = (int16_t*)output->data;
        for (uint32_t i = 0; i < frame_count; i++) {
            for (uint16_t ch = 0; ch < channels; ch++) {
                uint32_t idx = i * (uint32_t)channels + ch;
                float threshold = maidmic_ramp_next(&l->threshold_ramp);
                float alpha_release = maidmic_ramp_next(&l->release_ramp);
                float sample = (float)src[idx] / 32768.0f;

                float abs_s = fabsf(sample);
                float alpha_env = (abs_s > l->peak_env[ch]) ? l->alpha_attack : alpha_release;
                l->peak_env[ch] += (abs_s - l->peak_env[ch]) * alpha_env;

                float target_gain = 1.0f;
                if (l->peak_env[ch] > threshold) {
                    target_gain = threshold / l->peak_env[ch];
                }

                float alpha_gain = (target_gain < l->gain[ch]) ? l->alpha_attack : alpha_release;
                l->gain[ch] += (target_gain - l->gain[ch]) * alpha_gain;

                float delayed = l->delay_line[l->write_pos];
                l->delay_line[l->write_pos] = sample;
                l->write_pos++;
                if (l->write_pos >= l->delay_len) l->write_pos = 0;

                float out = delayed * l->gain[ch];
                // int16 钳位（整型溢出会爆音，钳位至少不崩）
                out = clampf(out * 32768.0f, -32768.0f, 32767.0f);
                dst[idx] = (int16_t)out;
            }
        }
    }

    output->meta = input->meta;
    return true;
}

static void limiter_reset(void* userdata) {
    limiter_data_t* l = (limiter_data_t*)userdata;
    // 峰值包络与增益按声道清零
    if (l->peak_env) memset(l->peak_env, 0, (size_t)l->channels * sizeof(float));
    if (l->gain) memset(l->gain, 0, (size_t)l->channels * sizeof(float));
    // 延迟线清零并回绕写指针
    if (l->delay_line) memset(l->delay_line, 0, (size_t)l->delay_len * sizeof(float));
    l->write_pos = 0;
    // 参数平滑器直接到位（复位时机不在音频热路径）
    maidmic_ramp_reset(&l->threshold_ramp);
    maidmic_ramp_reset(&l->release_ramp);
}

// ============================================================
// 模块描述
// ============================================================

static const maidmic_module_vtable_t limiter_vtable = {
    .create = limiter_create,
    .destroy = limiter_destroy,
    .setup = limiter_setup,
    .get_param_count = limiter_get_param_count,
    .get_param_info = limiter_get_param_info,
    .set_param = limiter_set_param,
    .get_param = limiter_get_param,
    .process = limiter_process,
    .reset = limiter_reset,
};

const maidmic_module_t maidmic_module_limiter = {
    .id = MAIDMIC_MODULE_ID_LIMITER,
    .name = "Limiter",
    .description = "Look-ahead peak limiter (无削波失真，峰值限制)",
    .author = "MaidMic Team",
    .version = 1,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_STEREO | MAIDMIC_CAP_NON_REALTIME,
    .vtable = &limiter_vtable,
};
