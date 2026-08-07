// maidmic-engine/src/dsp/noisegate.c
// Echio 引擎噪声门模块
// Echio Engine Noise Gate Module
//
// 噪声门：用 RMS 包络检测输入电平，与阈值比较后经迟滞开关控制增益包络。
// 信号低于阈值（静音/背景噪声）时把增益慢速衰减到 -60dB 地板，接近静音；
// 信号超过阈值（说话/出声）时快速放开增益到 1.0，无爆音。
// 迟滞（hysteresis）：开启阈值 = threshold，关闭阈值 = threshold - 6dB，
// 防止电平在阈值附近抖动导致门反复开关。
//
// 参数：
//   gate_threshold — 门限 dB (-80 ~ -20，默认 -50)
//   gate_attack    — 开启时间 ms (0 ~ 200，默认 10)
//   gate_release   — 关闭时间 ms (0 ~ 1000，默认 200)
//   bypass         — 旁路开关

#include "maidmic/module.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

// ============================================================
// 常量
// ============================================================

#define NG_DETECT_TAU_MS  10.0f   // RMS 检测包络时间常数 10ms
#define NG_HYSTERESIS_POWER 0.2512f  // 6dB 迟滞的功率域系数：(10^(-6/20))^2 ≈ 0.2512
#define NG_GATE_FLOOR     0.001f  // 关门时增益下限 0.001（-60dB floor）

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

// 每声道独立状态：RMS 功率包络、输出增益包络、迟滞开关
typedef struct {
    float rms_power;  // 检测用 RMS 功率包络（样本平方的一阶均值）
    float gate_gain;  // 输出增益包络（范围 NG_GATE_FLOOR ~ 1.0）
    bool gate_open;   // 迟滞开关状态（true=开门放行，false=关门衰减）
} noisegate_channel_t;

typedef struct {
    float threshold_db;   // 门限 dB（目标值）
    float attack_ms;      // 开启时间 ms（目标值）
    float release_ms;     // 关闭时间 ms（目标值）
    bool bypass;          // 旁路开关
    // 参数平滑器：平滑"计算中间量"（线性阈值、攻击/释放 alpha 系数），
    // 在 set_param 时算好目标值，process 热路径中逐样本逼近，消除 zipper 噪声
    maidmic_ramp_t threshold_ramp;  // 线性阈值平滑器
    maidmic_ramp_t attack_ramp;     // 攻击系数 alpha 平滑器
    maidmic_ramp_t release_ramp;    // 释放系数 alpha 平滑器
    noisegate_channel_t* ch;        // 每声道状态（setup 时按声道数分配）
    float alpha_detect;             // 检测包络系数（固定 10ms，setup 时计算）
    uint32_t sample_rate;
    uint16_t channels;
} noisegate_data_t;

// ============================================================
// 参数定义
// ============================================================

static const maidmic_param_t noisegate_params[] = {
    {
        .key = "gate_threshold",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = -50.0f,
        .min = -80.0f,
        .max = -20.0f,
        .unit = "dB",
    },
    {
        .key = "gate_attack",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 10.0f,
        .min = 0.0f,
        .max = 200.0f,
        .unit = "ms",
    },
    {
        .key = "gate_release",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 200.0f,
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

static void* noisegate_create(void) {
    noisegate_data_t* ng = (noisegate_data_t*)calloc(1, sizeof(noisegate_data_t));
    if (!ng) return NULL;
    ng->threshold_db = -50.0f;
    ng->attack_ms = 10.0f;
    ng->release_ms = 200.0f;
    ng->bypass = false;
    ng->ch = NULL;        // setup 时按声道数分配
    ng->alpha_detect = 0.0f;  // setup 时按实际采样率计算
    // 阈值换算不需要采样率，create 时即可初始化
    maidmic_ramp_init(&ng->threshold_ramp, db_to_linear(ng->threshold_db));
    // 攻击/释放 alpha 依赖采样率，先占位，setup 时刷新目标值
    maidmic_ramp_init(&ng->attack_ramp, 1.0f);
    maidmic_ramp_init(&ng->release_ramp, 1.0f);
    return ng;
}

static void noisegate_destroy(void* userdata) {
    noisegate_data_t* ng = (noisegate_data_t*)userdata;
    free(ng->ch);
    free(ng);
}

static bool noisegate_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    noisegate_data_t* ng = (noisegate_data_t*)userdata;
    ng->sample_rate = sample_rate;
    ng->alpha_detect = ms_to_alpha(NG_DETECT_TAU_MS, sample_rate);
    // 用真实采样率刷新攻击/释放 alpha 目标（set_param 可能早于 setup 被调用）
    maidmic_ramp_set_target(&ng->attack_ramp, ms_to_alpha(ng->attack_ms, sample_rate));
    maidmic_ramp_set_target(&ng->release_ramp, ms_to_alpha(ng->release_ms, sample_rate));
    // 声道状态按声道数隔离：声道数变化或首次调用时（重新）分配并清零
    if (!ng->ch || channels != ng->channels) {
        noisegate_channel_t* new_ch =
            (noisegate_channel_t*)realloc(ng->ch, (size_t)channels * sizeof(noisegate_channel_t));
        if (!new_ch) return false;
        ng->ch = new_ch;
        memset(ng->ch, 0, (size_t)channels * sizeof(noisegate_channel_t));
    }
    ng->channels = channels;
    return true;
}

static uint32_t noisegate_get_param_count(void* userdata) {
    (void)userdata;
    return 4;  // gate_threshold + gate_attack + gate_release + bypass
}

static const maidmic_param_t* noisegate_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 4) return &noisegate_params[index];
    return NULL;
}

static bool noisegate_set_param(void* userdata, const char* key, maidmic_param_t value) {
    noisegate_data_t* ng = (noisegate_data_t*)userdata;

    if (strcmp(key, "gate_threshold") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        ng->threshold_db = clampf(value.value.as_float, -80.0f, -20.0f);
        // 只更新线性阈值目标值，process 中逐样本平滑逼近
        maidmic_ramp_set_target(&ng->threshold_ramp, db_to_linear(ng->threshold_db));
        return true;
    }
    if (strcmp(key, "gate_attack") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        ng->attack_ms = clampf(value.value.as_float, 0.0f, 200.0f);
        // alpha 换算需要采样率；setup 尚未调用时由 setup/process 补算目标
        if (ng->sample_rate > 0) {
            maidmic_ramp_set_target(&ng->attack_ramp, ms_to_alpha(ng->attack_ms, ng->sample_rate));
        }
        return true;
    }
    if (strcmp(key, "gate_release") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        ng->release_ms = clampf(value.value.as_float, 0.0f, 1000.0f);
        if (ng->sample_rate > 0) {
            maidmic_ramp_set_target(&ng->release_ramp, ms_to_alpha(ng->release_ms, ng->sample_rate));
        }
        return true;
    }
    if (strcmp(key, "bypass") == 0 && value.type == MAIDMIC_PARAM_BOOL) {
        ng->bypass = value.value.as_bool;
        return true;
    }

    return false;
}

static maidmic_param_t noisegate_get_param(void* userdata, const char* key) {
    noisegate_data_t* ng = (noisegate_data_t*)userdata;
    maidmic_param_t param = {0};

    if (strcmp(key, "gate_threshold") == 0) {
        param.key = "gate_threshold";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = ng->threshold_db;
        param.min = -80.0f;
        param.max = -20.0f;
        param.unit = "dB";
    } else if (strcmp(key, "gate_attack") == 0) {
        param.key = "gate_attack";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = ng->attack_ms;
        param.min = 0.0f;
        param.max = 200.0f;
        param.unit = "ms";
    } else if (strcmp(key, "gate_release") == 0) {
        param.key = "gate_release";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = ng->release_ms;
        param.min = 0.0f;
        param.max = 1000.0f;
        param.unit = "ms";
    } else if (strcmp(key, "bypass") == 0) {
        param.key = "bypass";
        param.type = MAIDMIC_PARAM_BOOL;
        param.value.as_bool = ng->bypass;
    }

    return param;
}

// ============================================================
// 核心：音频处理
// ============================================================
// 每声道独立处理链：
//   1. RMS 功率包络（一阶平滑，10ms）→ 2. 迟滞比较 → 3. 增益包络 → 4. 输出 = 输入 * 增益
// 比较在功率域（x²）进行，避免每样本 sqrt。开启阈值 = threshold，
// 关闭阈值 = threshold - 6dB（功率域系数见 NG_HYSTERESIS_POWER）。
// 支持 F32 / S16 两种格式，原地处理（input == output）安全。
//
// 性能注记（NEON）：本模块保留标量实现。RMS 功率包络是每声道的逐样本
// 一阶递归 rms += (x² - rms)·α_detect，迟滞开关 gate_open 与增益包络
// gate_gain 构成串行状态机（下一状态依赖上一状态），代码中不存在可 4 路
// 并行的"块状平方和累加"；强行 M 路并行会改变包络时序与门控判决，
// 违反"标量/NEON 两条路径输出一致"约束，故不做 NEON 向量化。

static bool noisegate_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    noisegate_data_t* ng = (noisegate_data_t*)userdata;

    if (ng->bypass) {
        // 旁路时直接复制（原地调用时跳过自拷）
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    // 采样率或时间参数变化后刷新 alpha 目标（兜底 set_param 早于 setup 的场景）
    float want_attack = ms_to_alpha(ng->attack_ms, ng->sample_rate);
    float want_release = ms_to_alpha(ng->release_ms, ng->sample_rate);
    if (ng->attack_ramp.target != want_attack) maidmic_ramp_set_target(&ng->attack_ramp, want_attack);
    if (ng->release_ramp.target != want_release) maidmic_ramp_set_target(&ng->release_ramp, want_release);

    uint32_t frame_count = input->meta.frame_count;
    uint16_t channels = input->meta.channels;

    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        const float* src = (const float*)input->data;
        float* dst = (float*)output->data;
        for (uint32_t i = 0; i < frame_count; i++) {
            for (uint16_t ch = 0; ch < channels; ch++) {
                uint32_t idx = i * (uint32_t)channels + ch;
                // 每样本平滑推进共享参数，消除 zipper 噪声
                float threshold = maidmic_ramp_next(&ng->threshold_ramp);
                float alpha_attack = maidmic_ramp_next(&ng->attack_ramp);
                float alpha_release = maidmic_ramp_next(&ng->release_ramp);
                float sample = src[idx];
                noisegate_channel_t* st = &ng->ch[ch];

                // 1) RMS 功率包络（一阶平滑，时间常数 10ms）
                st->rms_power += (sample * sample - st->rms_power) * ng->alpha_detect;

                // 2) 迟滞比较（功率域：x² > thr² 开门，x² < thr²×系数 关门）
                float on_power = threshold * threshold;
                float off_power = on_power * NG_HYSTERESIS_POWER;
                if (st->gate_open) {
                    if (st->rms_power < off_power) st->gate_open = false;
                } else {
                    if (st->rms_power > on_power) st->gate_open = true;
                }

                // 3) 增益包络：开门时快 attack 升至 1.0，关门时慢 release 降至地板
                float target = st->gate_open ? 1.0f : NG_GATE_FLOOR;
                float alpha = (target > st->gate_gain) ? alpha_attack : alpha_release;
                st->gate_gain += (target - st->gate_gain) * alpha;

                // 4) 输出 = 输入 * 增益包络
                dst[idx] = sample * st->gate_gain;
            }
        }
    } else if (input->meta.format == MAIDMIC_SAMPLE_S16) {
        // 16-bit 整数：先归一化到 -1~1 域处理，再转回并钳位
        const int16_t* src = (const int16_t*)input->data;
        int16_t* dst = (int16_t*)output->data;
        for (uint32_t i = 0; i < frame_count; i++) {
            for (uint16_t ch = 0; ch < channels; ch++) {
                uint32_t idx = i * (uint32_t)channels + ch;
                float threshold = maidmic_ramp_next(&ng->threshold_ramp);
                float alpha_attack = maidmic_ramp_next(&ng->attack_ramp);
                float alpha_release = maidmic_ramp_next(&ng->release_ramp);
                float sample = (float)src[idx] / 32768.0f;
                noisegate_channel_t* st = &ng->ch[ch];

                st->rms_power += (sample * sample - st->rms_power) * ng->alpha_detect;

                float on_power = threshold * threshold;
                float off_power = on_power * NG_HYSTERESIS_POWER;
                if (st->gate_open) {
                    if (st->rms_power < off_power) st->gate_open = false;
                } else {
                    if (st->rms_power > on_power) st->gate_open = true;
                }

                float target = st->gate_open ? 1.0f : NG_GATE_FLOOR;
                float alpha = (target > st->gate_gain) ? alpha_attack : alpha_release;
                st->gate_gain += (target - st->gate_gain) * alpha;

                float out = sample * st->gate_gain;
                // int16 钳位（整型溢出会爆音，钳位至少不崩）
                out = clampf(out * 32768.0f, -32768.0f, 32767.0f);
                dst[idx] = (int16_t)out;
            }
        }
    }

    output->meta = input->meta;
    return true;
}

static void noisegate_reset(void* userdata) {
    noisegate_data_t* ng = (noisegate_data_t*)userdata;
    // 每声道状态清零：包络归零、门默认关闭（输出处于衰减地板）
    if (ng->ch) memset(ng->ch, 0, (size_t)ng->channels * sizeof(noisegate_channel_t));
    // 参数平滑器直接到位（复位时机不在音频热路径）
    maidmic_ramp_reset(&ng->threshold_ramp);
    maidmic_ramp_reset(&ng->attack_ramp);
    maidmic_ramp_reset(&ng->release_ramp);
}

// ============================================================
// 模块描述
// ============================================================

static const maidmic_module_vtable_t noisegate_vtable = {
    .create = noisegate_create,
    .destroy = noisegate_destroy,
    .setup = noisegate_setup,
    .get_param_count = noisegate_get_param_count,
    .get_param_info = noisegate_get_param_info,
    .set_param = noisegate_set_param,
    .get_param = noisegate_get_param,
    .process = noisegate_process,
    .reset = noisegate_reset,
};

const maidmic_module_t maidmic_module_noisegate = {
    .id = MAIDMIC_MODULE_ID_NOISEGATE,
    .name = "Noise Gate",
    .description = "Noise gate with RMS envelope and hysteresis (消除背景噪声)",
    .author = "MaidMic Team",
    .version = 1,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_STEREO | MAIDMIC_CAP_NON_REALTIME,
    .vtable = &noisegate_vtable,
};
