// maidmic-engine/src/voice/voiceprint_mask.c
// MaidMic 引擎声纹脱敏模块
// MaidMic Engine Voiceprint Mask Module
//
// 抗声纹反推的身份特征掩蔽（Voiceprint Masking）：
// 对语音中高频频谱包络做强度化的、缓变的随机化处理——
// 涂抹共振峰相对幅度、漂移共振峰位置、叠加轻微韵律抖动，
// 使基于声纹的特征提取难以稳定匹配说话人身份。
// Voiceprint masking: smear the formant envelope, drift formant
// positions and jitter prosody, so voiceprint-based speaker
// identification cannot reliably match the source identity.
//
// 算法基于经典 DSP（RBJ peaking EQ + 慢 LFO 调制 + 随机游走），
// 确定性、纯 CPU 实现，不依赖任何 AI 硬件。
// Deterministic, pure CPU. No AI hardware required.
//
// 参数：
//   vp_strength — 掩蔽强度 FLOAT 0.0~1.0（0 = 关闭，默认 0）
//   vp_mode     — 掩蔽模式 INT  0 = 标准 / 1 = 激进（默认 0）
//   bypass      — 旁路开关 BOOL

#include "maidmic/module.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

// ============================================================
// 常量
// Constants
// ============================================================

#define VP_MAX_CHANNELS 2              // 状态按声道隔离（立体声上限）
#define VP_TWO_PI 6.283185307179586f

// 三段 peaking EQ 的基准中心频率（覆盖 1~4kHz 与 4~8kHz 两个频段）
#define VP_MID_FREQ_HZ   2200.0f       // 1~4kHz 段代表频率
#define VP_EXTRA_FREQ_HZ 3400.0f       // 1~4kHz 段内第二层（反相起伏）
#define VP_HIGH_FREQ_HZ  5600.0f       // 4~8kHz 段代表频率
#define VP_MID_Q   1.2f
#define VP_EXTRA_Q 1.0f
#define VP_HIGH_Q  1.2f

// 各段最大增益起伏（dB，eff_strength=1 时达到）
#define VP_MID_GAIN_DB_MAX   10.0f
#define VP_EXTRA_GAIN_DB_MAX 7.0f
#define VP_HIGH_GAIN_DB_MAX  8.0f

// 共振峰偏置：中心频率漂移幅度（±比例）
#define VP_FORMANT_DRIFT_MAX 0.08f

// 韵律抖动：输出增益抖动幅度（±比例）
#define VP_JITTER_GAIN_MAX 0.06f

// LFO 频率（共振峰偏置 0.5~2Hz 区间内）
#define VP_ENV_FREQ_HZ 0.20f           // 包络起伏 LFO（缓变）
#define VP_LFO_FREQ_HZ 0.80f           // 共振峰偏置 LFO

// 激进模式：强度放大 / LFO 加速因子
#define VP_MODE_SCALE_AGGRESSIVE 1.5f
#define VP_LFO_RATE_AGGRESSIVE  1.5f
#define VP_ENV_RATE_AGGRESSIVE  1.25f

// 确定性随机源种子（相同参数 → 完全相同的掩蔽图案）
#define VP_RNG_SEED 0x51A7E9Cu

// ============================================================
// Biquad（Transposed Direct Form II）
// ============================================================

typedef struct {
    float b0, b1, b2;  // 归一化分子系数（a0 = 1）
    float a1, a2;      // 归一化分母系数
    float s1, s2;      // 滤波器状态（每声道独立）
} vp_biquad_t;

// ============================================================
// 模块实例数据
// Module instance data
// ============================================================

typedef struct {
    // 用户参数
    float vp_strength;    // 掩蔽强度 0.0~1.0
    int32_t vp_mode;      // 0 = 标准，1 = 激进
    bool bypass;          // 旁路开关

    uint32_t sample_rate;
    uint16_t channels;

    // 三段 peaking EQ（状态按声道隔离）
    vp_biquad_t band_mid[VP_MAX_CHANNELS];    // 1~4kHz 段
    vp_biquad_t band_extra[VP_MAX_CHANNELS];  // 1~4kHz 段第二层
    vp_biquad_t band_high[VP_MAX_CHANNELS];   // 4~8kHz 段

    // 每声道调制状态
    float env_phase[VP_MAX_CHANNELS];     // 包络起伏相位（增益涂抹）
    float lfo_phase[VP_MAX_CHANNELS];     // 共振峰偏置相位（中心频率漂移）
    float jitter_state[VP_MAX_CHANNELS];  // 韵律抖动随机游走
    uint32_t rng_state[VP_MAX_CHANNELS];  // 确定性随机源
    float jitter_gain[VP_MAX_CHANNELS];   // 当前帧韵律抖动增益

    // setup 时缓存的相位增量（每样本）
    float env_phase_inc;
    float lfo_phase_inc;
} voiceprint_mask_data_t;

// ============================================================
// 参数定义（供 UI 使用）
// Parameter definitions (for UI use)
// ============================================================

static const maidmic_param_t vp_params[] = {
    {
        .key = "vp_strength",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 0.0f,
        .min = 0.0f,
        .max = 1.0f,
        .unit = "",
    },
    {
        .key = "vp_mode",
        .type = MAIDMIC_PARAM_INT,
        .value.as_int = 0,
        .min = 0.0f,
        .max = 1.0f,
        .unit = "",
    },
    {
        .key = "bypass",
        .type = MAIDMIC_PARAM_BOOL,
        .value.as_bool = false,
        .min = 0.0f,
        .max = 1.0f,
        .unit = "",
    },
    { .key = NULL },  // 终止标记 terminator
};

// ============================================================
// 辅助函数
// Helpers
// ============================================================

static inline float vp_clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

// 确定性 LCG 随机数（0~1），固定种子 → 可复现
// Deterministic LCG random in [0,1), fixed seed → reproducible
static inline float vp_rand01(uint32_t* state) {
    *state = *state * 1664525u + 1013904223u;
    return (float)((*state >> 8) & 0x00FFFFFFu) / 16777216.0f;
}

// ============================================================
// Biquad peaking EQ 系数计算（RBJ Audio EQ Cookbook）
// Peaking EQ coefficient calculation
// ============================================================
// 公式（Robert Bristow-Johnson "Audio EQ Cookbook"）：
//   A     = 10^(dBgain / 40)
//   w0    = 2π * f0 / Fs
//   alpha = sin(w0) / (2Q)
//   b0 = 1 + alpha*A       b1 = -2cos(w0)      b2 = 1 - alpha*A
//   a0 = 1 + alpha/A       a1 = -2cos(w0)      a2 = 1 - alpha/A
// 全部除以 a0 归一化（a0 = 1），然后按 Transposed Direct Form II
// 每样本迭代：
//   y  = b0*x + s1
//   s1 = b1*x - a1*y + s2
//   s2 = b2*x - a2*y
static inline void vp_biquad_set_peaking(vp_biquad_t* f, float fs, float freq, float q, float gain_db) {
    float a = powf(10.0f, gain_db / 40.0f);
    float w0 = VP_TWO_PI * freq / fs;
    float cosw = cosf(w0);
    float sinw = sinf(w0);
    float alpha = sinw / (2.0f * q);

    float b0 = 1.0f + alpha * a;
    float b1 = -2.0f * cosw;
    float b2 = 1.0f - alpha * a;
    float a0 = 1.0f + alpha / a;
    float a1 = -2.0f * cosw;
    float a2 = 1.0f - alpha / a;

    // 归一化（a0 = 1）
    float inv_a0 = 1.0f / a0;
    f->b0 = b0 * inv_a0;
    f->b1 = b1 * inv_a0;
    f->b2 = b2 * inv_a0;
    f->a1 = a1 * inv_a0;
    f->a2 = a2 * inv_a0;
    // s1/s2 滤波器状态保持不变（平滑衔接）
}

// 单样本 Biquad 迭代（Transposed DF2）
static inline float vp_biquad_tick(vp_biquad_t* f, float x) {
    float y = f->b0 * x + f->s1;
    f->s1 = f->b1 * x - f->a1 * y + f->s2;
    f->s2 = f->b2 * x - f->a2 * y;
    return y;
}

// ============================================================
// vtable 实现
// vtable implementation
// ============================================================

static void* vp_create(void) {
    voiceprint_mask_data_t* v = (voiceprint_mask_data_t*)calloc(1, sizeof(*v));
    if (!v) return NULL;
    v->vp_strength = 0.0f;
    v->vp_mode = 0;
    v->bypass = false;
    // 固定种子（确定性）：每声道种子错开，避免左右声道图案完全一致
    for (int ch = 0; ch < VP_MAX_CHANNELS; ch++) {
        v->rng_state[ch] = VP_RNG_SEED + (uint32_t)ch * 0x9E3779B1u;
    }
    return v;
}

static void vp_destroy(void* userdata) {
    free(userdata);
}

static bool vp_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    voiceprint_mask_data_t* v = (voiceprint_mask_data_t*)userdata;
    if (sample_rate == 0) return false;
    v->sample_rate = sample_rate;
    v->channels = channels;
    // 缓存每样本相位增量（帧内按 frame_count 倍推进）
    v->env_phase_inc = VP_TWO_PI * VP_ENV_FREQ_HZ / (float)sample_rate;
    v->lfo_phase_inc = VP_TWO_PI * VP_LFO_FREQ_HZ / (float)sample_rate;
    return true;
}

static uint32_t vp_get_param_count(void* userdata) {
    (void)userdata;
    return 3;  // vp_strength + vp_mode + bypass
}

static const maidmic_param_t* vp_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 3) return &vp_params[index];
    return NULL;
}

static bool vp_set_param(void* userdata, const char* key, maidmic_param_t value) {
    voiceprint_mask_data_t* v = (voiceprint_mask_data_t*)userdata;

    if (strcmp(key, "vp_strength") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        v->vp_strength = vp_clampf(value.value.as_float, 0.0f, 1.0f);
        return true;
    }

    if (strcmp(key, "vp_mode") == 0 && value.type == MAIDMIC_PARAM_INT) {
        v->vp_mode = (value.value.as_int == 1) ? 1 : 0;
        return true;
    }

    if (strcmp(key, "bypass") == 0 && value.type == MAIDMIC_PARAM_BOOL) {
        v->bypass = value.value.as_bool;
        return true;
    }

    return false;
}

static maidmic_param_t vp_get_param(void* userdata, const char* key) {
    voiceprint_mask_data_t* v = (voiceprint_mask_data_t*)userdata;
    maidmic_param_t param = {0};

    if (strcmp(key, "vp_strength") == 0) {
        param.key = "vp_strength";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = v->vp_strength;
        param.min = 0.0f;
        param.max = 1.0f;
        param.unit = "";
    } else if (strcmp(key, "vp_mode") == 0) {
        param.key = "vp_mode";
        param.type = MAIDMIC_PARAM_INT;
        param.value.as_int = v->vp_mode;
        param.min = 0.0f;
        param.max = 1.0f;
        param.unit = "";
    } else if (strcmp(key, "bypass") == 0) {
        param.key = "bypass";
        param.type = MAIDMIC_PARAM_BOOL;
        param.value.as_bool = v->bypass;
        param.min = 0.0f;
        param.max = 1.0f;
        param.unit = "";
    }

    return param;
}

// ============================================================
// 核心：每帧调制更新
// Core: per-frame modulation update
// ============================================================
// 所有调制源都是缓变的（LFO 低频正弦 + 极低频随机游走），
// 每帧更新一次系数与增益，帧内恒定、帧间平滑过渡。
// 因此热路径（逐样本循环）内没有 powf/cosf 等重计算，也无 malloc。
// All modulation sources are slow-moving; coefficients are
// updated once per frame, so the sample loop stays cheap.

static void vp_update_modulation(voiceprint_mask_data_t* v, uint32_t frame_count, uint16_t channels) {
    float strength = v->vp_strength;
    bool aggressive = (v->vp_mode == 1);

    // 内部强度：激进模式整体放大 ×1.5（上限 1.5，防增益失控）
    float eff_strength = vp_clampf(strength * (aggressive ? VP_MODE_SCALE_AGGRESSIVE : 1.0f),
                                   0.0f, 1.5f);

    float env_inc = v->env_phase_inc * (aggressive ? VP_ENV_RATE_AGGRESSIVE : 1.0f);
    float lfo_inc = v->lfo_phase_inc * (aggressive ? VP_LFO_RATE_AGGRESSIVE : 1.0f);
    float frames = (float)frame_count;
    float fs = (float)v->sample_rate;
    if (fs <= 0.0f) return;  // 防御：setup 未完成

    uint16_t nch = (channels > VP_MAX_CHANNELS) ? VP_MAX_CHANNELS : channels;
    if (nch == 0) nch = 1;

    for (uint16_t ch = 0; ch < nch; ch++) {
        // ---- 相位推进（缓变）----
        v->env_phase[ch] += env_inc * frames;
        v->lfo_phase[ch] += lfo_inc * frames;
        if (v->env_phase[ch] > VP_TWO_PI) v->env_phase[ch] -= VP_TWO_PI;
        if (v->lfo_phase[ch] > VP_TWO_PI) v->lfo_phase[ch] -= VP_TWO_PI;

        // ---- 韵律抖动：极低频随机游走（每帧一步，低通泄漏）----
        float jitter_step = (vp_rand01(&v->rng_state[ch]) - 0.5f) * 0.01f;
        v->jitter_state[ch] = v->jitter_state[ch] * 0.995f + jitter_step;
        v->jitter_state[ch] = vp_clampf(v->jitter_state[ch], -1.0f, 1.0f);
        // 输出增益抖动：±VP_JITTER_GAIN_MAX × 强度（轻微，不破坏听感）
        v->jitter_gain[ch] = 1.0f + VP_JITTER_GAIN_MAX * eff_strength * v->jitter_state[ch];

        // ---- 频谱包络重塑：三段 peaking EQ 增益起伏（相位错开）----
        // 起伏相位相互错开 → 各频段相对幅度持续变化 → 共振峰相对幅度被涂抹
        float env = v->env_phase[ch];
        float g_mid   = VP_MID_GAIN_DB_MAX   * eff_strength * (0.5f + 0.5f * sinf(env));
        float g_extra = VP_EXTRA_GAIN_DB_MAX * eff_strength * (0.5f + 0.5f * sinf(env + VP_TWO_PI * 0.5f)); // 反相
        float g_high  = VP_HIGH_GAIN_DB_MAX  * eff_strength * (0.5f + 0.5f * sinf(env + VP_TWO_PI * 0.25f)); // 正交

        // ---- 共振峰偏置：慢 LFO 正弦叠加在中心频率上（轻微漂移）----
        // 中/高段反向漂移 → 双频段相对移动，共振峰位置持续偏移
        float drift = VP_FORMANT_DRIFT_MAX * eff_strength * sinf(v->lfo_phase[ch]);
        float f_mid   = VP_MID_FREQ_HZ   * (1.0f + drift);
        float f_extra = VP_EXTRA_FREQ_HZ * (1.0f - drift);
        float f_high  = VP_HIGH_FREQ_HZ  * (1.0f - drift);

        // 更新 Biquad 系数（滤波器状态 s1/s2 保持不变，平滑衔接）
        vp_biquad_set_peaking(&v->band_mid[ch],   fs, f_mid,   VP_MID_Q,   g_mid);
        vp_biquad_set_peaking(&v->band_extra[ch], fs, f_extra, VP_EXTRA_Q, g_extra);
        vp_biquad_set_peaking(&v->band_high[ch],  fs, f_high,  VP_HIGH_Q,  g_high);
    }
}

// ============================================================
// 核心：音频处理
// Core: audio processing
// ============================================================
// 逐样本：三段 peaking EQ 串联 + 韵律抖动增益调制。
// 支持 F32 与 S16 两种格式；状态按声道隔离（交错索引取模）。
// 原地处理（input == output）安全；热路径内无 malloc。

static bool vp_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    voiceprint_mask_data_t* v = (voiceprint_mask_data_t*)userdata;

    // 旁路 / 零强度 → 直通复制
    if (v->bypass || v->vp_strength <= 0.001f) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
            output->meta = input->meta;
        }
        return true;
    }

    if (input->data == NULL || output->data == NULL) return false;
    if (input->meta.format != MAIDMIC_SAMPLE_F32 &&
        input->meta.format != MAIDMIC_SAMPLE_S16) {
        // 不支持的格式：直通（不阻塞处理链）
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    uint32_t frame_count = input->meta.frame_count;
    uint16_t channels = input->meta.channels;
    if (channels == 0) channels = 1;
    uint32_t sample_count = frame_count * channels;
    if (sample_count == 0) return true;

    // 每帧更新一次调制状态与滤波器系数（缓变；热路径无 malloc）
    vp_update_modulation(v, frame_count, channels);

    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        // 32-bit float 处理（DSP 内部推荐格式）
        const float* src = (const float*)input->data;
        float* dst = (float*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            uint32_t ch = i % channels;                  // 交错索引 → 声道
            if (ch >= VP_MAX_CHANNELS) ch %= VP_MAX_CHANNELS;  // 防御 >2 声道
            float x = src[i];
            x = vp_biquad_tick(&v->band_mid[ch], x);
            x = vp_biquad_tick(&v->band_extra[ch], x);
            x = vp_biquad_tick(&v->band_high[ch], x);
            x *= v->jitter_gain[ch];
            dst[i] = x;
        }
    } else {
        // 16-bit 整数处理（Android 默认格式）：内部转 float 再转回并钳位
        const int16_t* src = (const int16_t*)input->data;
        int16_t* dst = (int16_t*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            uint32_t ch = i % channels;
            if (ch >= VP_MAX_CHANNELS) ch %= VP_MAX_CHANNELS;
            float x = (float)src[i];
            x = vp_biquad_tick(&v->band_mid[ch], x);
            x = vp_biquad_tick(&v->band_extra[ch], x);
            x = vp_biquad_tick(&v->band_high[ch], x);
            x *= v->jitter_gain[ch];
            // 钳位 int16 范围（整型溢出会爆音，钳位至少不崩）
            if (x > 32767.0f) x = 32767.0f;
            if (x < -32768.0f) x = -32768.0f;
            dst[i] = (int16_t)x;
        }
    }

    output->meta = input->meta;
    return true;
}

static void vp_reset(void* userdata) {
    voiceprint_mask_data_t* v = (voiceprint_mask_data_t*)userdata;
    for (int ch = 0; ch < VP_MAX_CHANNELS; ch++) {
        // 清空滤波器状态
        v->band_mid[ch].s1 = 0.0f;   v->band_mid[ch].s2 = 0.0f;
        v->band_extra[ch].s1 = 0.0f; v->band_extra[ch].s2 = 0.0f;
        v->band_high[ch].s1 = 0.0f;  v->band_high[ch].s2 = 0.0f;
        // 复位调制状态（确定性：回到初始相位与种子）
        v->env_phase[ch] = 0.0f;
        v->lfo_phase[ch] = 0.0f;
        v->jitter_state[ch] = 0.0f;
        v->jitter_gain[ch] = 1.0f;
        v->rng_state[ch] = VP_RNG_SEED + (uint32_t)ch * 0x9E3779B1u;
    }
}

// ============================================================
// 模块描述
// Module descriptor
// ============================================================

static const maidmic_module_vtable_t vp_vtable = {
    .create = vp_create,
    .destroy = vp_destroy,
    .setup = vp_setup,
    .get_param_count = vp_get_param_count,
    .get_param_info = vp_get_param_info,
    .set_param = vp_set_param,
    .get_param = vp_get_param,
    .process = vp_process,
    .reset = vp_reset,
};

// 模块 ID 16：module.h 中尚未定义宏，注册时由后续集成任务统一登记
// Module ID 16 (no macro in module.h yet; registration handled by a later task)
const maidmic_module_t maidmic_module_voiceprint_mask = {
    .id = 16,
    .name = "Voiceprint Mask",
    .description = "Voiceprint masking: smear formants, drift peaks, jitter prosody (声纹脱敏)",
    .author = "MaidMic Team",
    .version = 1,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_STEREO | MAIDMIC_CAP_REALTIME,
    .vtable = &vp_vtable,
};
