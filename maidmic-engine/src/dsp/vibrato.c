// maidmic-engine/src/dsp/vibrato.c
// Echio 引擎颤音模块
// Echio Engine Vibrato Module
//
// 经典延时线颤音：y(t) = x(t − d(t))，d(t) = D0 + Dm·sin(2π·rate·t)。
// 读延迟的周期性调制等价于瞬时音高偏移（峰值为 ±depth 半音）：
//   depth 样本 Dm 对应峰值音高偏移 ≈ 2π·rate·Dm（线性区），
//   故 Dm = (2^(depth_st/12) − 1)/(2π·rate)·sr。
// 纯调制（无干湿混合），负延迟方向由 D0 = Dm + 4 样本保护余量兜住。
//
// 参数：
//   vibrato_rate  — 调制频率 (0.1 ~ 10 Hz，默认 5)
//   vibrato_depth — 峰值音高偏移 (0 ~ 2 半音，默认 0.3)

#include "maidmic/module.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

// ============================================================
// 模块实例数据
// ============================================================

#define VB_MAX_CHANNELS 2u
#define VB_BUF_CAP      32768u  // 延迟线容量（样本/声道，2 的幂）
#define VB_BUF_MASK     (VB_BUF_CAP - 1u)
#define VB_GUARD        8.0f    // 零延迟保护余量（样本）
#define VB_TWO_PI       6.283185307179586f

typedef struct {
    float rate;              // 目标调制频率 Hz
    float depth_st;          // 目标峰值偏移（半音）
    maidmic_ramp_t rate_ramp;
    maidmic_ramp_t depth_ramp;
    float phase;             // LFO 相位 [0, 2π)
    uint32_t sample_rate;
    uint16_t channels;
    float* buf[VB_MAX_CHANNELS];
    uint64_t wpos[VB_MAX_CHANNELS];
} vibrato_data_t;

// ============================================================
// 参数定义
// ============================================================

static const maidmic_param_t vibrato_params[] = {
    {
        .key = "vibrato_rate",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 5.0f,
        .min = 0.1f,
        .max = 10.0f,
        .unit = "Hz",
    },
    {
        .key = "vibrato_depth",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 0.3f,
        .min = 0.0f,
        .max = 2.0f,
        .unit = "semitone",
    },
    { .key = NULL },
};

// ============================================================
// vtable 实现
// ============================================================

static void* vibrato_create(void) {
    vibrato_data_t* v = (vibrato_data_t*)calloc(1, sizeof(vibrato_data_t));
    if (!v) return NULL;
    v->rate = 5.0f;
    v->depth_st = 0.3f;
    maidmic_ramp_init(&v->rate_ramp, v->rate);
    maidmic_ramp_init(&v->depth_ramp, v->depth_st);
    return v;
}

static void vibrato_destroy(void* userdata) {
    vibrato_data_t* v = (vibrato_data_t*)userdata;
    if (!v) return;
    for (uint32_t c = 0; c < VB_MAX_CHANNELS; c++) {
        free(v->buf[c]);
    }
    free(v);
}

static bool vibrato_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    vibrato_data_t* v = (vibrato_data_t*)userdata;
    if (sample_rate == 0 || channels == 0 || channels > VB_MAX_CHANNELS) return false;
    v->sample_rate = sample_rate;
    v->channels = channels;
    for (uint32_t c = 0; c < VB_MAX_CHANNELS; c++) {
        if (!v->buf[c]) {
            v->buf[c] = (float*)calloc(VB_BUF_CAP, sizeof(float));
            if (!v->buf[c]) return false;
        }
    }
    v->phase = 0.0f;
    for (uint32_t c = 0; c < VB_MAX_CHANNELS; c++) {
        v->wpos[c] = 0;
    }
    return true;
}

static uint32_t vibrato_get_param_count(void* userdata) {
    (void)userdata;
    return 2;
}

static const maidmic_param_t* vibrato_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 2) return &vibrato_params[index];
    return NULL;
}

static bool vibrato_set_param(void* userdata, const char* key, maidmic_param_t value) {
    vibrato_data_t* v = (vibrato_data_t*)userdata;
    if (strcmp(key, "vibrato_rate") == 0 &&
        (value.type == MAIDMIC_PARAM_FLOAT || value.type == MAIDMIC_PARAM_INT)) {
        float r = (value.type == MAIDMIC_PARAM_FLOAT) ? value.value.as_float : (float)value.value.as_int;
        if (r < 0.1f) r = 0.1f;
        if (r > 10.0f) r = 10.0f;
        v->rate = r;
        maidmic_ramp_set_target(&v->rate_ramp, r);
        return true;
    }
    if (strcmp(key, "vibrato_depth") == 0 &&
        (value.type == MAIDMIC_PARAM_FLOAT || value.type == MAIDMIC_PARAM_INT)) {
        float d = (value.type == MAIDMIC_PARAM_FLOAT) ? value.value.as_float : (float)value.value.as_int;
        if (d < 0.0f) d = 0.0f;
        if (d > 2.0f) d = 2.0f;
        v->depth_st = d;
        maidmic_ramp_set_target(&v->depth_ramp, d);
        return true;
    }
    return false;
}

static maidmic_param_t vibrato_get_param(void* userdata, const char* key) {
    vibrato_data_t* v = (vibrato_data_t*)userdata;
    maidmic_param_t param = {0};
    if (strcmp(key, "vibrato_rate") == 0) {
        param.key = "vibrato_rate";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = v->rate;
        param.min = 0.1f;
        param.max = 10.0f;
        param.unit = "Hz";
    } else if (strcmp(key, "vibrato_depth") == 0) {
        param.key = "vibrato_depth";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = v->depth_st;
        param.min = 0.0f;
        param.max = 2.0f;
        param.unit = "semitone";
    }
    return param;
}

// ============================================================
// 核心：音频处理
// ============================================================

static bool vibrato_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    vibrato_data_t* v = (vibrato_data_t*)userdata;

    // 深度≈0：直通
    if (v->depth_ramp.current <= 0.001f && v->depth_ramp.target <= 0.001f) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    const uint32_t sample_count = input->meta.frame_count * input->meta.channels;
    const uint16_t chs = input->meta.channels;
    const float sr = (float)v->sample_rate;
    const float phase_inc = VB_TWO_PI * v->rate_ramp.current / sr;

    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        float* buffer = (float*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            const float depth_st = maidmic_ramp_next(&v->depth_ramp);
            (void)maidmic_ramp_next(&v->rate_ramp);  // 速率逐样本平滑（相位增量按块取当前值）
            const uint32_t ch = (chs == 2u) ? (i & 1u) : 0u;
            float* buf = v->buf[ch];
            const uint64_t w = v->wpos[ch];

            // 当前深度对应的延迟幅度（样本）：Dm = (2^(st/12)−1)/(2π·rate)·sr
            const float ratio = powf(2.0f, depth_st / 12.0f);
            float dm = (ratio - 1.0f) * sr / (VB_TWO_PI * v->rate_ramp.current + 1e-6f);
            const float dm_cap = (float)(VB_BUF_CAP / 2u);
            if (dm > dm_cap) dm = dm_cap;

            // 延迟线写入
            buf[(size_t)(w & VB_BUF_MASK)] = buffer[i];

            // 调制读：d(t) = D0 + Dm·sin(phase)，D0 = Dm + GUARD
            const float delay = dm + VB_GUARD + dm * sinf(v->phase);
            const float rp = (float)w - delay;
            // 历史环读（rp ≥ w − (dm·2+GUARD) ≥ 0 保证不越界）
            float s = 0.0f;
            if (rp >= 0.0f) {
                const uint64_t i0 = (uint64_t)rp;
                const float frac = rp - (float)i0;
                const float s0 = buf[(size_t)(i0 & VB_BUF_MASK)];
                const float s1 = buf[(size_t)((i0 + 1u) & VB_BUF_MASK)];
                s = s0 + (s1 - s0) * frac;
            }
            buffer[i] = s;

            v->wpos[ch] = w + 1u;
            v->phase += phase_inc;
            if (v->phase >= VB_TWO_PI) v->phase -= VB_TWO_PI;
        }
    } else if (input->meta.format == MAIDMIC_SAMPLE_S16) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        int16_t* buffer = (int16_t*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            const float depth_st = maidmic_ramp_next(&v->depth_ramp);
            const uint32_t ch = (chs == 2u) ? (i & 1u) : 0u;
            float* buf = v->buf[ch];
            const uint64_t w = v->wpos[ch];

            const float ratio = powf(2.0f, depth_st / 12.0f);
            float dm = (ratio - 1.0f) * sr / (VB_TWO_PI * v->rate_ramp.current + 1e-6f);
            const float dm_cap = (float)(VB_BUF_CAP / 2u);
            if (dm > dm_cap) dm = dm_cap;

            const float x = (float)buffer[i] / 32768.0f;
            buf[(size_t)(w & VB_BUF_MASK)] = x;

            const float delay = dm + VB_GUARD + dm * sinf(v->phase);
            const float rp = (float)w - delay;
            float s = 0.0f;
            if (rp >= 0.0f) {
                const uint64_t i0 = (uint64_t)rp;
                const float frac = rp - (float)i0;
                const float s0 = buf[(size_t)(i0 & VB_BUF_MASK)];
                const float s1 = buf[(size_t)((i0 + 1u) & VB_BUF_MASK)];
                s = s0 + (s1 - s0) * frac;
            }
            float o = s * 32767.0f;
            if (o > 32767.0f) o = 32767.0f;
            if (o < -32768.0f) o = -32768.0f;
            buffer[i] = (int16_t)o;

            v->wpos[ch] = w + 1u;
            v->phase += phase_inc;
            if (v->phase >= VB_TWO_PI) v->phase -= VB_TWO_PI;
        }
    }

    output->meta = input->meta;
    return true;
}

static void vibrato_reset(void* userdata) {
    vibrato_data_t* v = (vibrato_data_t*)userdata;
    for (uint32_t c = 0; c < VB_MAX_CHANNELS; c++) {
        if (v->buf[c]) memset(v->buf[c], 0, VB_BUF_CAP * sizeof(float));
        v->wpos[c] = 0;
    }
    v->phase = 0.0f;
    maidmic_ramp_reset(&v->rate_ramp);
    maidmic_ramp_reset(&v->depth_ramp);
}

// ============================================================
// 模块描述
// ============================================================

static const maidmic_module_vtable_t vibrato_vtable = {
    .create = vibrato_create,
    .destroy = vibrato_destroy,
    .setup = vibrato_setup,
    .get_param_count = vibrato_get_param_count,
    .get_param_info = vibrato_get_param_info,
    .set_param = vibrato_set_param,
    .get_param = vibrato_get_param,
    .process = vibrato_process,
    .reset = vibrato_reset,
};

const maidmic_module_t maidmic_module_vibrato = {
    .id = MAIDMIC_MODULE_ID_VIBRATO,
    .name = "Vibrato",
    .description = "Delay-line pitch vibrato (颤音)",
    .author = "MaidMic Team",
    .version = 1,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_STEREO | MAIDMIC_CAP_REALTIME,
    .vtable = &vibrato_vtable,
};
