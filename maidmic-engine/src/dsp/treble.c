// maidmic-engine/src/dsp/treble.c
// Echio 引擎高音 Shelving 滤波器模块
// Echio Engine Treble Shelving Filter Module
//
// 一阶高通 Shelving 滤波器，提升或衰减高频。
// 算法逻辑从原 process_audio_frame 的 Step 3 迁移而来，保持音质一致。
//
// 参数：
//   treble_db — 高音增益 dB (-10 ~ 10)

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
    float treble_db;    // 高音增益 dB（目标值）
    float* state;       // 高通滤波器记忆，按声道隔离（每声道一个）
    maidmic_ramp_t gain_ramp;  // 线性增益平滑器（消除 zipper 噪声）
    uint32_t sample_rate;
    uint16_t channels;
} treble_data_t;

// ============================================================
// 参数定义
// ============================================================

static const maidmic_param_t treble_params[] = {
    {
        .key = "treble_db",
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

static void* treble_create(void) {
    treble_data_t* t = (treble_data_t*)calloc(1, sizeof(treble_data_t));
    if (!t) return NULL;
    t->treble_db = 0.0f;
    t->state = NULL;  // setup 时按声道数分配
    maidmic_ramp_init(&t->gain_ramp, 1.0f);
    return t;
}

static void treble_destroy(void* userdata) {
    treble_data_t* t = (treble_data_t*)userdata;
    free(t->state);
    free(t);
}

static bool treble_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    treble_data_t* t = (treble_data_t*)userdata;
    t->sample_rate = sample_rate;
    // 滤波器状态必须按声道隔离：声道数变化或首次调用时（重新）分配并清零
    if (!t->state || channels != t->channels) {
        float* new_state = (float*)realloc(t->state, (size_t)channels * sizeof(float));
        if (!new_state) return false;
        t->state = new_state;
        memset(t->state, 0, (size_t)channels * sizeof(float));
    }
    t->channels = channels;
    return true;
}

static uint32_t treble_get_param_count(void* userdata) {
    (void)userdata;
    return 1;
}

static const maidmic_param_t* treble_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 1) return &treble_params[index];
    return NULL;
}

static bool treble_set_param(void* userdata, const char* key, maidmic_param_t value) {
    treble_data_t* t = (treble_data_t*)userdata;
    if (strcmp(key, "treble_db") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        t->treble_db = value.value.as_float;
        // 只更新线性增益目标值，process 中逐样本平滑逼近
        maidmic_ramp_set_target(&t->gain_ramp, db_to_linear(t->treble_db));
        return true;
    }
    return false;
}

static maidmic_param_t treble_get_param(void* userdata, const char* key) {
    treble_data_t* t = (treble_data_t*)userdata;
    maidmic_param_t param = {0};
    if (strcmp(key, "treble_db") == 0) {
        param.key = "treble_db";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = t->treble_db;
        param.min = -10.0f;
        param.max = 10.0f;
        param.unit = "dB";
    }
    return param;
}

// ============================================================
// 核心：音频处理
// ============================================================
// 一阶 Shelving：提取低频（一阶低通），高频 = 原信号 - 低频，
// 对高频施加增益后与低频混合。
// alpha = 0.15 对应截止频率约 3kHz。

static bool treble_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    treble_data_t* t = (treble_data_t*)userdata;

    // 平滑到位且增益≈0dB 时直通
    if (t->gain_ramp.current == t->gain_ramp.target && fabsf(t->treble_db) <= 0.5f) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    uint32_t frame_count = input->meta.frame_count;
    uint16_t channels = input->meta.channels;
    float alpha = 0.15f;  // 截止频率约 3kHz

    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        float* buffer = (float*)output->data;
        for (uint32_t i = 0; i < frame_count; i++) {
            for (uint16_t c = 0; c < channels; c++) {
                uint32_t idx = i * (uint32_t)channels + c;
                // 每样本平滑推进增益（共享参数逐样本逼近），消除 zipper 噪声
                float treble_gain = maidmic_ramp_next(&t->gain_ramp);
                // 提取低频（一阶低通，状态按声道隔离）
                float low = t->state[c] + alpha * (buffer[idx] - t->state[c]);
                t->state[c] = low;
                // 高频 = 原信号 - 低频
                float high = buffer[idx] - low;
                // 增益作用于高频，低频原样混合
                buffer[idx] = low + high * treble_gain;
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
                float treble_gain = maidmic_ramp_next(&t->gain_ramp);
                float low = t->state[c] + alpha * ((float)buffer[idx] - t->state[c]);
                t->state[c] = low;
                float high = (float)buffer[idx] - low;
                buffer[idx] = (int16_t)clampf(low + high * treble_gain, -32768.0f, 32767.0f);
            }
        }
    }

    output->meta = input->meta;
    return true;
}

static void treble_reset(void* userdata) {
    treble_data_t* t = (treble_data_t*)userdata;
    // 滤波器记忆按声道清零
    if (t->state) memset(t->state, 0, (size_t)t->channels * sizeof(float));
    maidmic_ramp_reset(&t->gain_ramp);
}

// ============================================================
// 模块描述
// ============================================================

static const maidmic_module_vtable_t treble_vtable = {
    .create = treble_create,
    .destroy = treble_destroy,
    .setup = treble_setup,
    .get_param_count = treble_get_param_count,
    .get_param_info = treble_get_param_info,
    .set_param = treble_set_param,
    .get_param = treble_get_param,
    .process = treble_process,
    .reset = treble_reset,
};

const maidmic_module_t maidmic_module_treble = {
    .id = MAIDMIC_MODULE_ID_TREBLE,
    .name = "Treble",
    .description = "Treble shelving filter (高音增强/衰减)",
    .author = "MaidMic Team",
    .version = 1,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_REALTIME,
    .vtable = &treble_vtable,
};
