// maidmic-engine/src/dsp/bass.c
// Echio 引擎低音 Shelving 滤波器模块
// Echio Engine Bass Shelving Filter Module
//
// 一阶低通 Shelving 滤波器，提升或衰减低频。
// 算法逻辑从原 process_audio_frame 的 Step 2 迁移而来，保持音质一致。
//
// 参数：
//   bass_db — 低音增益 dB (-10 ~ 10)

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

static inline float db_to_linear(float db) {
    return powf(10.0f, db / 20.0f);
}

// ============================================================
// 模块实例数据
// ============================================================

typedef struct {
    float bass_db;       // 低音增益 dB（目标值）
    float* state;        // 低通滤波器记忆，按声道隔离（每声道一个）
    maidmic_ramp_t gain_ramp;  // 线性增益平滑器（消除 zipper 噪声）
    uint32_t sample_rate;
    uint16_t channels;
} bass_data_t;

// ============================================================
// 参数定义
// ============================================================

static const maidmic_param_t bass_params[] = {
    {
        .key = "bass_db",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 0.0f,
        .min = -10.0f,
        .max = 10.0f,
        .unit = "dB",
    },
    { .key = NULL },
};

// ============================================================
// vtable 实现
// ============================================================

static void* bass_create(void) {
    bass_data_t* b = (bass_data_t*)calloc(1, sizeof(bass_data_t));
    if (!b) return NULL;
    b->bass_db = 0.0f;
    b->state = NULL;  // setup 时按声道数分配
    maidmic_ramp_init(&b->gain_ramp, 1.0f);
    return b;
}

static void bass_destroy(void* userdata) {
    bass_data_t* b = (bass_data_t*)userdata;
    free(b->state);
    free(b);
}

static bool bass_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    bass_data_t* b = (bass_data_t*)userdata;
    b->sample_rate = sample_rate;
    // 滤波器状态必须按声道隔离：声道数变化或首次调用时（重新）分配并清零
    if (!b->state || channels != b->channels) {
        float* new_state = (float*)realloc(b->state, (size_t)channels * sizeof(float));
        if (!new_state) return false;
        b->state = new_state;
        memset(b->state, 0, (size_t)channels * sizeof(float));
    }
    b->channels = channels;
    return true;
}

static uint32_t bass_get_param_count(void* userdata) {
    (void)userdata;
    return 1;
}

static const maidmic_param_t* bass_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 1) return &bass_params[index];
    return NULL;
}

static bool bass_set_param(void* userdata, const char* key, maidmic_param_t value) {
    bass_data_t* b = (bass_data_t*)userdata;
    if (strcmp(key, "bass_db") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        b->bass_db = value.value.as_float;
        // 只更新线性增益目标值，process 中逐样本平滑逼近
        maidmic_ramp_set_target(&b->gain_ramp, db_to_linear(b->bass_db));
        return true;
    }
    return false;
}

static maidmic_param_t bass_get_param(void* userdata, const char* key) {
    bass_data_t* b = (bass_data_t*)userdata;
    maidmic_param_t param = {0};
    if (strcmp(key, "bass_db") == 0) {
        param.key = "bass_db";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = b->bass_db;
        param.min = -10.0f;
        param.max = 10.0f;
        param.unit = "dB";
    }
    return param;
}

// ============================================================
// 核心：音频处理
// ============================================================
// 一阶低通 Shelving：提取低频 → 增益 → 与高频混合。
// alpha = 0.3 对应截止频率约 300Hz。

static bool bass_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    bass_data_t* b = (bass_data_t*)userdata;

    // 平滑到位且增益≈0dB 时直通
    if (b->gain_ramp.current == b->gain_ramp.target && fabsf(b->bass_db) <= 0.5f) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    uint32_t frame_count = input->meta.frame_count;
    uint16_t channels = input->meta.channels;
    float alpha = 0.3f;  // 截止频率约 300Hz

    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        float* buffer = (float*)output->data;
        for (uint32_t i = 0; i < frame_count; i++) {
            for (uint16_t c = 0; c < channels; c++) {
                uint32_t idx = i * (uint32_t)channels + c;
                // 每样本平滑推进增益（共享参数逐样本逼近），消除 zipper 噪声
                float bass_gain = maidmic_ramp_next(&b->gain_ramp);
                // 提取低频（状态按声道隔离）
                float lp = b->state[c] + alpha * (buffer[idx] - b->state[c]);
                b->state[c] = lp;
                // 高频 = 原信号 - 低频
                float hp = buffer[idx] - lp;
                buffer[idx] = lp * bass_gain + hp;
            }
        }
    } else if (input->meta.format == MAIDMIC_SAMPLE_S16) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        int16_t* buffer = (int16_t*)output->data;
        for (uint32_t i = 0; i < frame_count; i++) {
            for (uint16_t c = 0; c < channels; c++) {
                uint32_t idx = i * (uint32_t)channels + c;
                float bass_gain = maidmic_ramp_next(&b->gain_ramp);
                float lp = b->state[c] + alpha * ((float)buffer[idx] - b->state[c]);
                b->state[c] = lp;
                float hp = (float)buffer[idx] - lp;
                buffer[idx] = (int16_t)clampf(lp * bass_gain + hp, -32768.0f, 32767.0f);
            }
        }
    }

    output->meta = input->meta;
    return true;
}

static void bass_reset(void* userdata) {
    bass_data_t* b = (bass_data_t*)userdata;
    // 滤波器记忆按声道清零
    if (b->state) memset(b->state, 0, (size_t)b->channels * sizeof(float));
    maidmic_ramp_reset(&b->gain_ramp);
}

// ============================================================
// 模块描述
// ============================================================

static const maidmic_module_vtable_t bass_vtable = {
    .create = bass_create,
    .destroy = bass_destroy,
    .setup = bass_setup,
    .get_param_count = bass_get_param_count,
    .get_param_info = bass_get_param_info,
    .set_param = bass_set_param,
    .get_param = bass_get_param,
    .process = bass_process,
    .reset = bass_reset,
};

const maidmic_module_t maidmic_module_bass = {
    .id = MAIDMIC_MODULE_ID_BASS,
    .name = "Bass",
    .description = "Bass shelving filter (低音增强/衰减)",
    .author = "MaidMic Team",
    .version = 1,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_REALTIME,
    .vtable = &bass_vtable,
};
