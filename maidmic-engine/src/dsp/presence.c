// maidmic-engine/src/dsp/presence.c
// Echio 引擎人声存在感模块
// Echio Engine Presence Module
//
// 人声存在感：对 2~5kHz 中高频段（人声清晰度/临场感关键频段）做 peaking 均衡提升
// 或衰减，中心频率 3.2kHz、Q≈0.7，增益 = presence_db。
//
// 滤波器系数采用 RBJ Audio EQ Cookbook 的 peaking EQ 公式：
//   A     = 10^(dBgain/40)
//   w0    = 2*pi*f0/fs
//   alpha = sin(w0) / (2*Q)
//   b0 = 1 + alpha*A      b1 = -2*cos(w0)      b2 = 1 - alpha*A
//   a0 = 1 + alpha/A      a1 = -2*cos(w0)      a2 = 1 - alpha/A
// 归一化（除以 a0）后按 Direct Form 1 递推：
//   y = (b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2) / a0
//
// 增益平滑：对增益因子 A 本身做 maidmic_ramp 逐样本逼近（A 即"线性目标增益的
// 平方根"，set_param 时用 powf 算一次），每样本用当前 A 重算系数。
// 相比逐样本 powf，仅需几次乘除；且 A 变化缓慢（step=1/32），系数连续缓变，
// 滤波器始终稳定（A∈[0.562,1.778] 时极点在单位圆内），无 zipper 噪声。
// 当 presence_db = 0 → A = 1 → b0=a0、b1=a1、b2=a2，传递函数恒等，输出等于输入。
//
// 参数：
//   presence_db — 存在感增益 dB (-10 ~ +10，默认 0)
//   bypass      — 旁路开关

#include "maidmic/module.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

// ============================================================
// 常量
// ============================================================

#define PRES_F0_HZ  3200.0f  // 中心频率 3.2kHz（2~5kHz 人声存在感频段）
#define PRES_Q      0.7f     // 品质因数 Q

// ============================================================
// 辅助函数
// ============================================================

static inline float clampf(float v, float min, float max) {
    return v < min ? min : (v > max ? max : v);
}

// ============================================================
// 模块实例数据
// ============================================================

// 每声道独立的 Biquad 滤波器状态（Direct Form 1 记忆）
typedef struct {
    float x1, x2;  // 输入历史
    float y1, y2;  // 输出历史
} presence_biquad_state_t;

typedef struct {
    float presence_db;    // 存在感增益 dB（目标值）
    bool bypass;          // 旁路开关
    maidmic_ramp_t gain_ramp;  // 增益因子 A = 10^(dB/40) 平滑器（逐样本逼近）
    presence_biquad_state_t* state;  // 每声道滤波器状态（setup 时按声道数分配）
    float alpha;          // sin(w0)/(2Q)，setup 时按采样率计算
    float neg2cos;        // -2*cos(w0)，即 b1/a1 系数，setup 时按采样率计算
    uint32_t sample_rate;
    uint16_t channels;
} presence_data_t;

// ============================================================
// 参数定义
// ============================================================

static const maidmic_param_t presence_params[] = {
    {
        .key = "presence_db",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 0.0f,
        .min = -10.0f,
        .max = 10.0f,
        .unit = "dB",
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

static void* presence_create(void) {
    presence_data_t* p = (presence_data_t*)calloc(1, sizeof(presence_data_t));
    if (!p) return NULL;
    p->presence_db = 0.0f;
    p->bypass = false;
    p->state = NULL;  // setup 时按声道数分配
    // A 换算不需要采样率，create 时即可初始化（0dB → A = 1）
    maidmic_ramp_init(&p->gain_ramp, 1.0f);
    return p;
}

static void presence_destroy(void* userdata) {
    presence_data_t* p = (presence_data_t*)userdata;
    free(p->state);
    free(p);
}

static bool presence_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    presence_data_t* p = (presence_data_t*)userdata;
    p->sample_rate = sample_rate;
    // RBJ Cookbook 系数基础量（与增益无关，仅依赖 f0/Q/fs）
    float w0 = 2.0f * 3.14159265f * PRES_F0_HZ / (float)sample_rate;
    p->alpha = sinf(w0) / (2.0f * PRES_Q);
    p->neg2cos = -2.0f * cosf(w0);
    // 滤波器状态必须按声道隔离：声道数变化或首次调用时（重新）分配并清零
    if (!p->state || channels != p->channels) {
        presence_biquad_state_t* new_state =
            (presence_biquad_state_t*)realloc(p->state, (size_t)channels * sizeof(presence_biquad_state_t));
        if (!new_state) return false;
        p->state = new_state;
        memset(p->state, 0, (size_t)channels * sizeof(presence_biquad_state_t));
    }
    p->channels = channels;
    return true;
}

static uint32_t presence_get_param_count(void* userdata) {
    (void)userdata;
    return 2;  // presence_db + bypass
}

static const maidmic_param_t* presence_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 2) return &presence_params[index];
    return NULL;
}

static bool presence_set_param(void* userdata, const char* key, maidmic_param_t value) {
    presence_data_t* p = (presence_data_t*)userdata;

    if (strcmp(key, "presence_db") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        p->presence_db = clampf(value.value.as_float, -10.0f, 10.0f);
        // 只更新增益因子 A = 10^(dB/40) 目标值，process 中逐样本平滑逼近
        maidmic_ramp_set_target(&p->gain_ramp, powf(10.0f, p->presence_db / 40.0f));
        return true;
    }
    if (strcmp(key, "bypass") == 0 && value.type == MAIDMIC_PARAM_BOOL) {
        p->bypass = value.value.as_bool;
        return true;
    }

    return false;
}

static maidmic_param_t presence_get_param(void* userdata, const char* key) {
    presence_data_t* p = (presence_data_t*)userdata;
    maidmic_param_t param = {0};

    if (strcmp(key, "presence_db") == 0) {
        param.key = "presence_db";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = p->presence_db;
        param.min = -10.0f;
        param.max = 10.0f;
        param.unit = "dB";
    } else if (strcmp(key, "bypass") == 0) {
        param.key = "bypass";
        param.type = MAIDMIC_PARAM_BOOL;
        param.value.as_bool = p->bypass;
    }

    return param;
}

// ============================================================
// 核心：音频处理
// ============================================================
// 每样本从增益平滑器取当前 A，按 RBJ peaking 公式重算系数并做 Direct Form 1 递推。
// 滤波器状态按声道隔离。
// 支持 F32 / S16 两种格式，原地处理（input == output）安全。
//
// 性能注记（NEON）：本模块保留标量实现。Direct Form 1 的 y[i] 依赖
// y[i-1]、y[i-2]（逐样本 IIR 反馈链），且每样本增益因子 A 经平滑器逐样本
// 变化（b0/b2/a0/a2 系数随之逐样本重算），无法稳定对齐到 4 路向量边界；
// 按声道 x4 的 M 路并行（M=4）需维护 4 组独立 Biquad 状态，其输出与串行
// 滤波不同，违反"标量/NEON 两条路径输出一致"约束，故不做 NEON 向量化。

static bool presence_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    presence_data_t* p = (presence_data_t*)userdata;

    if (p->bypass) {
        // 旁路时直接复制（原地调用时跳过自拷）
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    // 增益为 0dB 且平滑已到位：Biquad 恒等（输出等于输入）→ 直通快速路径
    if (p->presence_db == 0.0f && p->gain_ramp.current == p->gain_ramp.target) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    uint32_t frame_count = input->meta.frame_count;
    uint16_t channels = input->meta.channels;

    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        const float* src = (const float*)input->data;
        float* dst = (float*)output->data;
        for (uint32_t i = 0; i < frame_count; i++) {
            for (uint16_t ch = 0; ch < channels; ch++) {
                uint32_t idx = i * (uint32_t)channels + ch;
                // 每样本平滑推进增益因子 A，消除 zipper 噪声
                float A = maidmic_ramp_next(&p->gain_ramp);
                // RBJ peaking 系数（b1 = a1 = neg2cos，无需重复计算）
                float b0 = 1.0f + p->alpha * A;
                float b2 = 1.0f - p->alpha * A;
                float a0 = 1.0f + p->alpha / A;
                float a2 = 1.0f - p->alpha / A;
                float a0_inv = 1.0f / a0;
                float sample = src[idx];
                presence_biquad_state_t* st = &p->state[ch];
                // Direct Form 1：b1*x1 - a1*y1 = neg2cos*(x1 - y1)（合并一次乘法）
                float y = (b0 * sample + p->neg2cos * (st->x1 - st->y1)
                           + b2 * st->x2 - a2 * st->y2) * a0_inv;
                // 滚动状态
                st->x2 = st->x1;
                st->x1 = sample;
                st->y2 = st->y1;
                st->y1 = y;
                dst[idx] = y;
            }
        }
    } else if (input->meta.format == MAIDMIC_SAMPLE_S16) {
        // 16-bit 整数：先归一化到 -1~1 域处理，再转回并钳位
        const int16_t* src = (const int16_t*)input->data;
        int16_t* dst = (int16_t*)output->data;
        for (uint32_t i = 0; i < frame_count; i++) {
            for (uint16_t ch = 0; ch < channels; ch++) {
                uint32_t idx = i * (uint32_t)channels + ch;
                float A = maidmic_ramp_next(&p->gain_ramp);
                float b0 = 1.0f + p->alpha * A;
                float b2 = 1.0f - p->alpha * A;
                float a0 = 1.0f + p->alpha / A;
                float a2 = 1.0f - p->alpha / A;
                float a0_inv = 1.0f / a0;
                float sample = (float)src[idx] / 32768.0f;
                presence_biquad_state_t* st = &p->state[ch];
                float y = (b0 * sample + p->neg2cos * (st->x1 - st->y1)
                           + b2 * st->x2 - a2 * st->y2) * a0_inv;
                st->x2 = st->x1;
                st->x1 = sample;
                st->y2 = st->y1;
                st->y1 = y;
                // int16 钳位（整型溢出会爆音，钳位至少不崩）
                float out = clampf(y * 32768.0f, -32768.0f, 32767.0f);
                dst[idx] = (int16_t)out;
            }
        }
    }

    output->meta = input->meta;
    return true;
}

static void presence_reset(void* userdata) {
    presence_data_t* p = (presence_data_t*)userdata;
    // 滤波器状态按声道清零
    if (p->state) memset(p->state, 0, (size_t)p->channels * sizeof(presence_biquad_state_t));
    // 参数平滑器直接到位（复位时机不在音频热路径）
    maidmic_ramp_reset(&p->gain_ramp);
}

// ============================================================
// 模块描述
// ============================================================

static const maidmic_module_vtable_t presence_vtable = {
    .create = presence_create,
    .destroy = presence_destroy,
    .setup = presence_setup,
    .get_param_count = presence_get_param_count,
    .get_param_info = presence_get_param_info,
    .set_param = presence_set_param,
    .get_param = presence_get_param,
    .process = presence_process,
    .reset = presence_reset,
};

const maidmic_module_t maidmic_module_presence = {
    .id = 17,  // 存在感模块 ID（MAIDMIC_MODULE_ID 区段 13~999 中预留给后续模块的字面量）
    .name = "Presence",
    .description = "Presence EQ: mid-high boost at 3.2kHz (人声临场感)",
    .author = "MaidMic Team",
    .version = 1,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_STEREO | MAIDMIC_CAP_NON_REALTIME,
    .vtable = &presence_vtable,
};
