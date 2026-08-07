// maidmic-engine/src/dsp/reverb.c
// Echio 引擎混响模块
// Echio Engine Reverb Module
//
// 简单延迟线混响：干湿混合 + 反馈。
// 算法逻辑从原 process_audio_frame 的 Step 4 迁移而来，保持音质一致。
//
// 参数：
//   reverb_mix — 混响混合比 (0 ~ 1)

#include "maidmic/module.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

// ============================================================
// 辅助函数
// ============================================================

static inline float clampf(float v, float min, float max) {
    return v < min ? min : (v > max ? max : v);
}

// ============================================================
// 模块实例数据
// ============================================================

#define REVERB_BUF_SIZE 48000  // 默认容量 1秒 @48kHz（单声道样本数）

typedef struct {
    float mix;            // 混响混合比 0~1（目标值）
    float* buf;           // 延迟线（动态分配，按样本数计，容量 >= 声道相关需求）
    int buf_size;         // 延迟线容量（样本数）
    int pos;              // 延迟线写指针（全局样本索引，交错数据天然支持）
    maidmic_ramp_t mix_ramp;  // 混合比平滑器（消除 zipper 噪声）
    uint32_t sample_rate;
    uint16_t channels;
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
    { .key = NULL },
};

// ============================================================
// vtable 实现
// ============================================================

static void* reverb_create(void) {
    reverb_data_t* r = (reverb_data_t*)calloc(1, sizeof(reverb_data_t));
    if (!r) return NULL;
    r->mix = 0.0f;
    r->pos = 0;
    r->buf_size = REVERB_BUF_SIZE;
    r->buf = (float*)calloc((size_t)REVERB_BUF_SIZE, sizeof(float));
    if (!r->buf) {
        free(r);
        return NULL;
    }
    maidmic_ramp_init(&r->mix_ramp, r->mix);
    return r;
}

static void reverb_destroy(void* userdata) {
    reverb_data_t* r = (reverb_data_t*)userdata;
    free(r->buf);
    free(r);
}

static bool reverb_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    reverb_data_t* r = (reverb_data_t*)userdata;
    r->sample_rate = sample_rate;
    r->channels = channels;
    // 延迟线长度 = sample_rate/4 帧（250ms），按"样本"计需乘以声道数。
    // 缓冲容量（=延迟线长度）必须 >= 该需求（例如 192kHz 双声道需 96000 样本）。
    int need = (int)(sample_rate / 4) * (int)channels;
    if (need < 1) need = 1;
    if (need != r->buf_size) {
        float* new_buf = (float*)realloc(r->buf, (size_t)need * sizeof(float));
        if (!new_buf) return false;
        r->buf = new_buf;
        memset(r->buf, 0, (size_t)need * sizeof(float));  // 延迟线长度变化，整体清零
        r->buf_size = need;
    }
    if (r->pos >= r->buf_size) r->pos = 0;
    return true;
}

static uint32_t reverb_get_param_count(void* userdata) {
    (void)userdata;
    return 1;
}

static const maidmic_param_t* reverb_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 1) return &reverb_params[index];
    return NULL;
}

static bool reverb_set_param(void* userdata, const char* key, maidmic_param_t value) {
    reverb_data_t* r = (reverb_data_t*)userdata;
    if (strcmp(key, "reverb_mix") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        r->mix = clampf(value.value.as_float, 0.0f, 1.0f);
        // 只更新目标值，process 中逐样本平滑逼近
        maidmic_ramp_set_target(&r->mix_ramp, r->mix);
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
    }
    return param;
}

// ============================================================
// 核心：音频处理
// ============================================================
// 延迟线长度 = sample_rate / 4 (250ms)
// 读延迟线 → 湿信号 * 0.6
// 写延迟线 = 干信号 * 0.4 + 湿信号 * 0.6 (反馈)
// 输出 = 干信号 * (1-mix) + 湿信号 * mix

static bool reverb_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    reverb_data_t* r = (reverb_data_t*)userdata;

    // 平滑到位且混合比极低：直通
    if (r->mix_ramp.current <= 0.01f && r->mix_ramp.target <= 0.01f) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    // 多声道：按全局样本索引读写延迟线（交错数据天然支持），延迟长度按样本计
    uint32_t sample_count = input->meta.frame_count * input->meta.channels;
    int buf_size = r->buf_size;
    if (buf_size <= 0) buf_size = REVERB_BUF_SIZE;

    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        float* buffer = (float*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            // 每样本平滑推进混合比，消除 zipper 噪声
            float mix = maidmic_ramp_next(&r->mix_ramp);
            // 从延迟线读取（环形：读当前位置的最旧值）
            float wet = r->buf[r->pos] * 0.6f;
            // 写入延迟线（输入 + 反馈）
            r->buf[r->pos] = buffer[i] * 0.4f + wet * 0.6f;
            // 混合干湿
            buffer[i] = buffer[i] * (1.0f - mix) + wet * mix;
            if (++r->pos >= buf_size) r->pos = 0;
        }
    } else if (input->meta.format == MAIDMIC_SAMPLE_S16) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        int16_t* buffer = (int16_t*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            float mix = maidmic_ramp_next(&r->mix_ramp);
            float wet = r->buf[r->pos] * 0.6f;
            r->buf[r->pos] = (float)buffer[i] * 0.4f + wet * 0.6f;
            buffer[i] = (int16_t)clampf(
                (float)buffer[i] * (1.0f - mix) + wet * mix,
                -32768.0f, 32767.0f);
            if (++r->pos >= buf_size) r->pos = 0;
        }
    }

    output->meta = input->meta;
    return true;
}

static void reverb_reset(void* userdata) {
    reverb_data_t* r = (reverb_data_t*)userdata;
    if (r->buf) memset(r->buf, 0, (size_t)r->buf_size * sizeof(float));
    r->pos = 0;
    maidmic_ramp_reset(&r->mix_ramp);
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
    .description = "Simple delay-line reverb (混响)",
    .author = "MaidMic Team",
    .version = 1,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_REALTIME,
    .vtable = &reverb_vtable,
};
