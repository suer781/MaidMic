// maidmic-engine/src/dsp/reverb.c
// Echio 引擎混响模块 v2（Freeverb 式）
// Echio Engine Reverb Module v2 (Schroeder-Moorer / Freeverb style)
//
// 旧版为单延迟线反馈（金属罐头声）。v2 改为经典 Freeverb 拓扑：
//   8 个并联低阻尼组合器（comb，长度互质防梳状叠加）
//   → 4 个串联全通滤波器（allpass，反馈 0.5）增加模态密度。
// 立体声第二声道延迟整体 +23 样本展开声场。
// 阻尼（高频吸收）与反馈（房间大小）为内部定值，针对人声调优。
//
// 参数：
//   reverb_mix   — 干湿混合比 (0 ~ 1)（与旧版同名，UI 零改动）
//   reverb_decay — 尾音衰减系数 (0.1 ~ 0.95，默认 0.84，可选微调)

#include "maidmic/module.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

// ============================================================
// 常量（Freeverb 调音表 @44.1kHz，setup 时按采样率缩放）
// ============================================================

#define RV_NUM_COMB 8
#define RV_NUM_AP   4
#define RV_STEREO_SPREAD 23   // 立体声第二声道附加延迟（样本）

static const int rv_comb_tuning[RV_NUM_COMB] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
static const int rv_ap_tuning[RV_NUM_AP]     = {556, 441, 341, 225};

// ============================================================
// 模块实例数据
// ============================================================

typedef struct {
    float* buf;        // 延迟线
    int size;          // 容量
    int pos;           // 写指针
    float filterstore; // 阻尼一阶低通状态
} rv_comb_t;

typedef struct {
    float* buf;
    int size;
    int pos;
} rv_ap_t;

typedef struct {
    float mix;
    float decay;               // 尾音衰减（目标值）
    maidmic_ramp_t mix_ramp;
    maidmic_ramp_t decay_ramp;
    uint32_t sample_rate;
    uint16_t channels;
    rv_comb_t comb[2][RV_NUM_COMB];
    rv_ap_t ap[2][RV_NUM_AP];
} reverb_data_t;

// ============================================================
// 参数定义
// ============================================================

static const maidmic_param_t reverb_params[] = {
    {
        .key = "reverb_mix",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 0.0f,
        .min = 0.0f,
        .max = 1.0f,
        .unit = "",
    },
    {
        .key = "reverb_decay",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 0.84f,
        .min = 0.1f,
        .max = 0.95f,
        .unit = "",
    },
    { .key = NULL },
};

// ============================================================
// 状态管理
// ============================================================

static void rv_free_all(reverb_data_t* r) {
    for (uint32_t c = 0; c < 2u; c++) {
        for (int i = 0; i < RV_NUM_COMB; i++) {
            free(r->comb[c][i].buf);
            r->comb[c][i].buf = NULL;
        }
        for (int i = 0; i < RV_NUM_AP; i++) {
            free(r->ap[c][i].buf);
            r->ap[c][i].buf = NULL;
        }
    }
}

static void rv_init_buffers(reverb_data_t* r) {
    // 调音表按 44.1kHz 标定；低于 44.1k 的采样率沿用原值（延迟偏长无碍）
    const float scale = (float)r->sample_rate / 44100.0f;
    const int spread = (int)RV_STEREO_SPREAD;

    for (uint32_t c = 0; c < 2u; c++) {
        const int offset = (c == 1u) ? spread : 0;
        for (int i = 0; i < RV_NUM_COMB; i++) {
            rv_comb_t* cb = &r->comb[c][i];
            int sz = (int)((float)rv_comb_tuning[i] * scale) + offset;
            if (sz < 2) sz = 2;
            free(cb->buf);
            cb->buf = (float*)calloc((size_t)sz, sizeof(float));
            cb->size = cb->buf ? sz : 0;
            cb->pos = 0;
            cb->filterstore = 0.0f;
        }
        for (int i = 0; i < RV_NUM_AP; i++) {
            rv_ap_t* ap = &r->ap[c][i];
            int sz = (int)((float)rv_ap_tuning[i] * scale) + offset;
            if (sz < 2) sz = 2;
            free(ap->buf);
            ap->buf = (float*)calloc((size_t)sz, sizeof(float));
            ap->size = ap->buf ? sz : 0;
            ap->pos = 0;
        }
    }
}

// ============================================================
// vtable 实现
// ============================================================

static void* reverb_create(void) {
    reverb_data_t* r = (reverb_data_t*)calloc(1, sizeof(reverb_data_t));
    if (!r) return NULL;
    r->mix = 0.0f;
    r->decay = 0.84f;
    maidmic_ramp_init(&r->mix_ramp, r->mix);
    maidmic_ramp_init(&r->decay_ramp, r->decay);
    return r;
}

static void reverb_destroy(void* userdata) {
    reverb_data_t* r = (reverb_data_t*)userdata;
    if (!r) return;
    rv_free_all(r);
    free(r);
}

static bool reverb_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    reverb_data_t* r = (reverb_data_t*)userdata;
    if (sample_rate == 0 || channels == 0 || channels > 2) return false;
    r->sample_rate = sample_rate;
    r->channels = channels;
    rv_free_all(r);
    rv_init_buffers(r);
    // 任一缓冲分配失败
    for (uint32_t c = 0; c < 2u; c++) {
        for (int i = 0; i < RV_NUM_COMB; i++) {
            if (!r->comb[c][i].buf) return false;
        }
        for (int i = 0; i < RV_NUM_AP; i++) {
            if (!r->ap[c][i].buf) return false;
        }
    }
    return true;
}

static uint32_t reverb_get_param_count(void* userdata) {
    (void)userdata;
    return 2;
}

static const maidmic_param_t* reverb_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 2) return &reverb_params[index];
    return NULL;
}

static bool reverb_set_param(void* userdata, const char* key, maidmic_param_t value) {
    reverb_data_t* r = (reverb_data_t*)userdata;
    if (strcmp(key, "reverb_mix") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        float m = value.value.as_float;
        if (m < 0.0f) m = 0.0f;
        if (m > 1.0f) m = 1.0f;
        r->mix = m;
        maidmic_ramp_set_target(&r->mix_ramp, m);
        return true;
    }
    if (strcmp(key, "reverb_decay") == 0 && (value.type == MAIDMIC_PARAM_FLOAT || value.type == MAIDMIC_PARAM_INT)) {
        float d = (value.type == MAIDMIC_PARAM_FLOAT) ? value.value.as_float : (float)value.value.as_int;
        if (d < 0.1f) d = 0.1f;
        if (d > 0.95f) d = 0.95f;
        r->decay = d;
        maidmic_ramp_set_target(&r->decay_ramp, d);
        return true;
    }
    return false;
}

static maidmic_param_t reverb_get_param(void* userdata, const char* key) {
    reverb_data_t* r = (reverb_data_t*)userdata;
    maidmic_param_t param = {0};
    if (strcmp(key, "reverb_mix") == 0) {
        param.key = "reverb_mix";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = r->mix;
        param.min = 0.0f;
        param.max = 1.0f;
        param.unit = "";
    } else if (strcmp(key, "reverb_decay") == 0) {
        param.key = "reverb_decay";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = r->decay;
        param.min = 0.1f;
        param.max = 0.95f;
        param.unit = "";
    }
    return param;
}

// ============================================================
// 核心：单声道采样处理
// ============================================================

// 组合器（并联，带阻尼低通）：经典 Freeverb comb
static inline float rv_comb_process(rv_comb_t* cb, float in, float feedback, float damp1) {
    const float out = cb->buf[cb->pos];
    cb->filterstore = out * (1.0f - damp1) + cb->filterstore * damp1;
    cb->buf[cb->pos] = in + cb->filterstore * feedback;
    if (++cb->pos >= cb->size) cb->pos = 0;
    return out;
}

// 全通（串联）：Freeverb allpass
static inline float rv_ap_process(rv_ap_t* ap, float in) {
    const float bufout = ap->buf[ap->pos];
    const float out = -in + bufout;
    ap->buf[ap->pos] = in + bufout * 0.5f;
    if (++ap->pos >= ap->size) ap->pos = 0;
    return out;
}

// 单声道单样本混响：返回湿信号
static inline float rv_reverb_sample(reverb_data_t* r, uint32_t ch, float in,
                                     float feedback, float damp1) {
    float out = 0.0f;
    for (int i = 0; i < RV_NUM_COMB; i++) {
        out += rv_comb_process(&r->comb[ch][i], in, feedback, damp1);
    }
    for (int i = 0; i < RV_NUM_AP; i++) {
        out = rv_ap_process(&r->ap[ch][i], out);
    }
    return out;
}

static bool reverb_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    reverb_data_t* r = (reverb_data_t*)userdata;

    // 平滑到位且混合比极低：直通
    if (r->mix_ramp.current <= 0.003f && r->mix_ramp.target <= 0.003f) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    const uint32_t sample_count = input->meta.frame_count * input->meta.channels;
    const uint16_t chs = input->meta.channels;

    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        float* buffer = (float*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            const float mix = maidmic_ramp_next(&r->mix_ramp);
            const float feedback = maidmic_ramp_next(&r->decay_ramp);
            const float damp1 = 0.20f;  // 高频阻尼（针对人声调优）
            const uint32_t ch = (chs == 2u) ? (i & 1u) : 0u;
            const float dry = buffer[i];
            // 湿信号固定增益 0.28：8 组合器并联能量 ≈ √8×单路，补偿到接近干声
            const float wet = rv_reverb_sample(r, ch, dry, feedback, damp1) * 0.28f;
            buffer[i] = dry * (1.0f - mix) + wet * mix;
        }
    } else if (input->meta.format == MAIDMIC_SAMPLE_S16) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        int16_t* buffer = (int16_t*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            const float mix = maidmic_ramp_next(&r->mix_ramp);
            const float feedback = maidmic_ramp_next(&r->decay_ramp);
            const float damp1 = 0.20f;
            const uint32_t ch = (chs == 2u) ? (i & 1u) : 0u;
            const float dry = (float)buffer[i] / 32768.0f;
            const float wet = rv_reverb_sample(r, ch, dry, feedback, damp1) * 0.28f;
            float v = dry * (1.0f - mix) + wet * mix;
            if (v > 1.0f) v = 1.0f;
            if (v < -1.0f) v = -1.0f;
            buffer[i] = (int16_t)(v * 32767.0f);
        }
    }

    output->meta = input->meta;
    return true;
}

static void reverb_reset(void* userdata) {
    reverb_data_t* r = (reverb_data_t*)userdata;
    for (uint32_t c = 0; c < 2u; c++) {
        for (int i = 0; i < RV_NUM_COMB; i++) {
            if (r->comb[c][i].buf) {
                memset(r->comb[c][i].buf, 0, (size_t)r->comb[c][i].size * sizeof(float));
            }
            r->comb[c][i].pos = 0;
            r->comb[c][i].filterstore = 0.0f;
        }
        for (int i = 0; i < RV_NUM_AP; i++) {
            if (r->ap[c][i].buf) {
                memset(r->ap[c][i].buf, 0, (size_t)r->ap[c][i].size * sizeof(float));
            }
            r->ap[c][i].pos = 0;
        }
    }
    maidmic_ramp_reset(&r->mix_ramp);
    maidmic_ramp_reset(&r->decay_ramp);
}

// ============================================================
// 模块描述
// ============================================================

static const maidmic_module_vtable_t reverb_vtable = {
    .create = reverb_create,
    .destroy = reverb_destroy,
    .setup = reverb_setup,
    .get_param_count = reverb_get_param_count,
    .get_param_info = reverb_get_param_info,
    .set_param = reverb_set_param,
    .get_param = reverb_get_param,
    .process = reverb_process,
    .reset = reverb_reset,
};

const maidmic_module_t maidmic_module_reverb = {
    .id = MAIDMIC_MODULE_ID_REVERB,
    .name = "Reverb",
    .description = "Freeverb-style reverb (8 comb + 4 allpass, 人声混响 v2)",
    .author = "MaidMic Team",
    .version = 2,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_STEREO | MAIDMIC_CAP_REALTIME,
    .vtable = &reverb_vtable,
};
