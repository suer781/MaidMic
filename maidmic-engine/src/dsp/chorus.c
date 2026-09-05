// maidmic-engine/src/dsp/chorus.c
// Echio 引擎合唱模块
// Echio Engine Chorus Module
//
// 3 路调制延迟声（正弦 LFO，相位 0°/120°/240°，基延迟 12/21/30ms，
// 深度可调）与干声等功率混合。每路用分数延迟（线性插值）读取。
//
// 参数：
//   chorus_mix   — 干湿混合 (0 ~ 1，默认 0.3)
//   chorus_rate  — LFO 频率 (0.1 ~ 5 Hz，默认 1.2)
//   chorus_depth — 调制深度 (0 ~ 10 ms，默认 2.5)

#include "maidmic/module.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

// ============================================================
// 模块实例数据
// ============================================================

#define CH_MAX_CHANNELS 2u
#define CH_VOICES       3u
#define CH_BUF_CAP      65536u  // 延迟线容量（样本/声道，2 的幂）
#define CH_BUF_MASK     (CH_BUF_CAP - 1u)
#define CH_TWO_PI       6.283185307179586f

static const float ch_base_ms[CH_VOICES] = {12.0f, 21.0f, 30.0f};
static const float ch_phase_ofs[CH_VOICES] = {0.0f, 2.0943951f, 4.1887902f};  // 0/120/240°

typedef struct {
    float mix;
    float rate;               // 目标 LFO 频率 Hz
    float depth_ms;           // 目标调制深度 ms
    maidmic_ramp_t mix_ramp;
    maidmic_ramp_t rate_ramp;
    maidmic_ramp_t depth_ramp;
    float phase;
    uint32_t sample_rate;
    uint16_t channels;
    float* buf[CH_MAX_CHANNELS];
    uint64_t wpos[CH_MAX_CHANNELS];
} chorus_data_t;

// ============================================================
// 参数定义
// ============================================================

static const maidmic_param_t chorus_params[] = {
    {
        .key = "chorus_mix",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 0.3f,
        .min = 0.0f,
        .max = 1.0f,
        .unit = "",
    },
    {
        .key = "chorus_rate",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 1.2f,
        .min = 0.1f,
        .max = 5.0f,
        .unit = "Hz",
    },
    {
        .key = "chorus_depth",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 2.5f,
        .min = 0.0f,
        .max = 10.0f,
        .unit = "ms",
    },
    { .key = NULL },
};

// ============================================================
// vtable 实现
// ============================================================

static void* chorus_create(void) {
    chorus_data_t* c = (chorus_data_t*)calloc(1, sizeof(chorus_data_t));
    if (!c) return NULL;
    c->mix = 0.3f;
    c->rate = 1.2f;
    c->depth_ms = 2.5f;
    maidmic_ramp_init(&c->mix_ramp, c->mix);
    maidmic_ramp_init(&c->rate_ramp, c->rate);
    maidmic_ramp_init(&c->depth_ramp, c->depth_ms);
    return c;
}

static void chorus_destroy(void* userdata) {
    chorus_data_t* c = (chorus_data_t*)userdata;
    if (!c) return;
    for (uint32_t ch = 0; ch < CH_MAX_CHANNELS; ch++) {
        free(c->buf[ch]);
    }
    free(c);
}

static bool chorus_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    chorus_data_t* c = (chorus_data_t*)userdata;
    if (sample_rate == 0 || channels == 0 || channels > CH_MAX_CHANNELS) return false;
    c->sample_rate = sample_rate;
    c->channels = channels;
    for (uint32_t ch = 0; ch < CH_MAX_CHANNELS; ch++) {
        if (!c->buf[ch]) {
            c->buf[ch] = (float*)calloc(CH_BUF_CAP, sizeof(float));
            if (!c->buf[ch]) return false;
        }
    }
    c->phase = 0.0f;
    for (uint32_t ch = 0; ch < CH_MAX_CHANNELS; ch++) {
        c->wpos[ch] = 0;
    }
    return true;
}

static uint32_t chorus_get_param_count(void* userdata) {
    (void)userdata;
    return 3;
}

static const maidmic_param_t* chorus_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 3) return &chorus_params[index];
    return NULL;
}

static bool chorus_set_param(void* userdata, const char* key, maidmic_param_t value) {
    chorus_data_t* c = (chorus_data_t*)userdata;
    if (strcmp(key, "chorus_mix") == 0 &&
        (value.type == MAIDMIC_PARAM_FLOAT || value.type == MAIDMIC_PARAM_INT)) {
        float m = (value.type == MAIDMIC_PARAM_FLOAT) ? value.value.as_float : (float)value.value.as_int;
        if (m < 0.0f) m = 0.0f;
        if (m > 1.0f) m = 1.0f;
        c->mix = m;
        maidmic_ramp_set_target(&c->mix_ramp, m);
        return true;
    }
    if (strcmp(key, "chorus_rate") == 0 &&
        (value.type == MAIDMIC_PARAM_FLOAT || value.type == MAIDMIC_PARAM_INT)) {
        float r = (value.type == MAIDMIC_PARAM_FLOAT) ? value.value.as_float : (float)value.value.as_int;
        if (r < 0.1f) r = 0.1f;
        if (r > 5.0f) r = 5.0f;
        c->rate = r;
        maidmic_ramp_set_target(&c->rate_ramp, r);
        return true;
    }
    if (strcmp(key, "chorus_depth") == 0 &&
        (value.type == MAIDMIC_PARAM_FLOAT || value.type == MAIDMIC_PARAM_INT)) {
        float d = (value.type == MAIDMIC_PARAM_FLOAT) ? value.value.as_float : (float)value.value.as_int;
        if (d < 0.0f) d = 0.0f;
        if (d > 10.0f) d = 10.0f;
        c->depth_ms = d;
        maidmic_ramp_set_target(&c->depth_ramp, d);
        return true;
    }
    return false;
}

static maidmic_param_t chorus_get_param(void* userdata, const char* key) {
    chorus_data_t* c = (chorus_data_t*)userdata;
    maidmic_param_t param = {0};
    if (strcmp(key, "chorus_mix") == 0) {
        param.key = "chorus_mix";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = c->mix;
        param.min = 0.0f;
        param.max = 1.0f;
        param.unit = "";
    } else if (strcmp(key, "chorus_rate") == 0) {
        param.key = "chorus_rate";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = c->rate;
        param.min = 0.1f;
        param.max = 5.0f;
        param.unit = "Hz";
    } else if (strcmp(key, "chorus_depth") == 0) {
        param.key = "chorus_depth";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = c->depth_ms;
        param.min = 0.0f;
        param.max = 10.0f;
        param.unit = "ms";
    }
    return param;
}

// ============================================================
// 核心：音频处理
// ============================================================

static bool chorus_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    chorus_data_t* c = (chorus_data_t*)userdata;

    // 混合≈0：直通
    if (c->mix_ramp.current <= 0.003f && c->mix_ramp.target <= 0.003f) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    const uint32_t sample_count = input->meta.frame_count * input->meta.channels;
    const uint16_t chs = input->meta.channels;
    const float sr = (float)c->sample_rate;
    const float phase_inc = CH_TWO_PI * c->rate_ramp.current / sr;

    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        float* buffer = (float*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            const float mix = maidmic_ramp_next(&c->mix_ramp);
            const float depth_ms = maidmic_ramp_next(&c->depth_ramp);
            (void)maidmic_ramp_next(&c->rate_ramp);
            const uint32_t ch = (chs == 2u) ? (i & 1u) : 0u;
            float* buf = c->buf[ch];
            const uint64_t w = c->wpos[ch];
            const float dry = buffer[i];

            buf[(size_t)(w & CH_BUF_MASK)] = dry;

            // 3 路调制延迟平均
            float wet = 0.0f;
            for (uint32_t v = 0; v < CH_VOICES; v++) {
                const float delay_ms = ch_base_ms[v] + depth_ms * sinf(c->phase + ch_phase_ofs[v]);
                float delay = delay_ms * sr * 0.001f;
                if (delay < 1.0f) delay = 1.0f;
                if (delay > (float)(CH_BUF_CAP / 2u)) delay = (float)(CH_BUF_CAP / 2u);
                const float rp = (float)w - delay;
                if (rp >= 0.0f) {
                    const uint64_t i0 = (uint64_t)rp;
                    const float frac = rp - (float)i0;
                    const float s0 = buf[(size_t)(i0 & CH_BUF_MASK)];
                    const float s1 = buf[(size_t)((i0 + 1u) & CH_BUF_MASK)];
                    wet += s0 + (s1 - s0) * frac;
                }
            }
            wet /= (float)CH_VOICES;

            // 等功率混合
            const float cw = cosf(mix * 1.5707963f);
            const float sw = sinf(mix * 1.5707963f);
            buffer[i] = dry * cw + wet * sw;

            c->wpos[ch] = w + 1u;
            c->phase += phase_inc;
            if (c->phase >= CH_TWO_PI) c->phase -= CH_TWO_PI;
        }
    } else if (input->meta.format == MAIDMIC_SAMPLE_S16) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        int16_t* buffer = (int16_t*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            const float mix = maidmic_ramp_next(&c->mix_ramp);
            const float depth_ms = maidmic_ramp_next(&c->depth_ramp);
            (void)maidmic_ramp_next(&c->rate_ramp);
            const uint32_t ch = (chs == 2u) ? (i & 1u) : 0u;
            float* buf = c->buf[ch];
            const uint64_t w = c->wpos[ch];
            const float dry = (float)buffer[i] / 32768.0f;

            buf[(size_t)(w & CH_BUF_MASK)] = dry;

            float wet = 0.0f;
            for (uint32_t v = 0; v < CH_VOICES; v++) {
                const float delay_ms = ch_base_ms[v] + depth_ms * sinf(c->phase + ch_phase_ofs[v]);
                float delay = delay_ms * sr * 0.001f;
                if (delay < 1.0f) delay = 1.0f;
                if (delay > (float)(CH_BUF_CAP / 2u)) delay = (float)(CH_BUF_CAP / 2u);
                const float rp = (float)w - delay;
                if (rp >= 0.0f) {
                    const uint64_t i0 = (uint64_t)rp;
                    const float frac = rp - (float)i0;
                    const float s0 = buf[(size_t)(i0 & CH_BUF_MASK)];
                    const float s1 = buf[(size_t)((i0 + 1u) & CH_BUF_MASK)];
                    wet += s0 + (s1 - s0) * frac;
                }
            }
            wet /= (float)CH_VOICES;

            const float cw = cosf(mix * 1.5707963f);
            const float sw = sinf(mix * 1.5707963f);
            float o = dry * cw + wet * sw;
            if (o > 1.0f) o = 1.0f;
            if (o < -1.0f) o = -1.0f;
            buffer[i] = (int16_t)(o * 32767.0f);

            c->wpos[ch] = w + 1u;
            c->phase += phase_inc;
            if (c->phase >= CH_TWO_PI) c->phase -= CH_TWO_PI;
        }
    }

    output->meta = input->meta;
    return true;
}

static void chorus_reset(void* userdata) {
    chorus_data_t* c = (chorus_data_t*)userdata;
    for (uint32_t ch = 0; ch < CH_MAX_CHANNELS; ch++) {
        if (c->buf[ch]) memset(c->buf[ch], 0, CH_BUF_CAP * sizeof(float));
        c->wpos[ch] = 0;
    }
    c->phase = 0.0f;
    maidmic_ramp_reset(&c->mix_ramp);
    maidmic_ramp_reset(&c->rate_ramp);
    maidmic_ramp_reset(&c->depth_ramp);
}

// ============================================================
// 模块描述
// ============================================================

static const maidmic_module_vtable_t chorus_vtable = {
    .create = chorus_create,
    .destroy = chorus_destroy,
    .setup = chorus_setup,
    .get_param_count = chorus_get_param_count,
    .get_param_info = chorus_get_param_info,
    .set_param = chorus_set_param,
    .get_param = chorus_get_param,
    .process = chorus_process,
    .reset = chorus_reset,
};

const maidmic_module_t maidmic_module_chorus = {
    .id = MAIDMIC_MODULE_ID_CHORUS,
    .name = "Chorus",
    .description = "3-voice modulated chorus (合唱)",
    .author = "MaidMic Team",
    .version = 1,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_STEREO | MAIDMIC_CAP_REALTIME,
    .vtable = &chorus_vtable,
};
