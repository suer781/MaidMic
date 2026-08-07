// maidmic-engine/src/dsp/formant.c
// Echio 引擎共振峰偏移模块
// Echio Engine Formant Shift Module
//
// 用 Shelving filter 模拟共振峰偏移补偿。
// 变调升高时补偿低频，变调降低时补偿高频，保持声音自然。
// 算法逻辑从原 process_audio_frame 的 Step 6 迁移而来，保持音质一致。
//
// 参数：
//   formant_shift — 共振峰偏移 (-12 ~ +12 半音等效)

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
    float shift;        // 共振峰偏移量（目标值）
    float* state;       // 滤波器记忆，按声道隔离（每声道一个）
    maidmic_ramp_t gain_ramp;  // 线性增益平滑器（消除 zipper 噪声）
    uint32_t sample_rate;
    uint16_t channels;
} formant_data_t;

// ============================================================
// 参数定义
// ============================================================

static const maidmic_param_t formant_params[] = {
    {
        .key = "formant_shift",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 0.0f,
        .min = -12.0f,
        .max = 12.0f,
        .unit = "semitone",
    },
    { .key = NULL },
};

// ============================================================
// vtable 实现
// ============================================================

static void* formant_create(void) {
    formant_data_t* f = (formant_data_t*)calloc(1, sizeof(formant_data_t));
    if (!f) return NULL;
    f->shift = 0.0f;
    f->state = NULL;  // setup 时按声道数分配
    maidmic_ramp_init(&f->gain_ramp, 1.0f);
    return f;
}

static void formant_destroy(void* userdata) {
    formant_data_t* f = (formant_data_t*)userdata;
    free(f->state);
    free(f);
}

static bool formant_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    formant_data_t* f = (formant_data_t*)userdata;
    f->sample_rate = sample_rate;
    // 滤波器状态必须按声道隔离：声道数变化或首次调用时（重新）分配并清零
    if (!f->state || channels != f->channels) {
        float* new_state = (float*)realloc(f->state, (size_t)channels * sizeof(float));
        if (!new_state) return false;
        f->state = new_state;
        memset(f->state, 0, (size_t)channels * sizeof(float));
    }
    f->channels = channels;
    return true;
}

static uint32_t formant_get_param_count(void* userdata) {
    (void)userdata;
    return 1;
}

static const maidmic_param_t* formant_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 1) return &formant_params[index];
    return NULL;
}

static bool formant_set_param(void* userdata, const char* key, maidmic_param_t value) {
    formant_data_t* f = (formant_data_t*)userdata;
    if (strcmp(key, "formant_shift") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        f->shift = clampf(value.value.as_float, -12.0f, 12.0f);
        // 只更新线性增益目标值（gain = db_to_linear(shift * 0.5)），process 中逐样本平滑逼近
        maidmic_ramp_set_target(&f->gain_ramp, db_to_linear(f->shift * 0.5f));
        return true;
    }
    return false;
}

static maidmic_param_t formant_get_param(void* userdata, const char* key) {
    formant_data_t* f = (formant_data_t*)userdata;
    maidmic_param_t param = {0};
    if (strcmp(key, "formant_shift") == 0) {
        param.key = "formant_shift";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = f->shift;
        param.min = -12.0f;
        param.max = 12.0f;
        param.unit = "semitone";
    }
    return param;
}

// ============================================================
// 核心：音频处理
// ============================================================
// 正偏移切低频（alpha=0.12），负偏移增强低频（alpha=0.08）
// gain = db_to_linear(shift * 0.5)
// 输出 = 低频 * (1/gain) + 高频 * gain

static bool formant_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    formant_data_t* f = (formant_data_t*)userdata;

    // 平滑到位且偏移≈0 时直通
    if (f->gain_ramp.current == f->gain_ramp.target && fabsf(f->shift) <= 0.5f) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    uint32_t frame_count = input->meta.frame_count;
    uint16_t channels = input->meta.channels;
    float fs = f->shift;
    float alpha = (fs > 0) ? 0.12f : 0.08f;  // 正偏移切低频，负偏移增强低频

    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        float* buffer = (float*)output->data;
        for (uint32_t i = 0; i < frame_count; i++) {
            for (uint16_t c = 0; c < channels; c++) {
                uint32_t idx = i * (uint32_t)channels + c;
                // 每样本平滑推进增益（共享参数逐样本逼近），消除 zipper 噪声
                float gain = maidmic_ramp_next(&f->gain_ramp);
                float inv_gain = 1.0f / gain;
                float lp = f->state[c] + alpha * (buffer[idx] - f->state[c]);
                f->state[c] = lp;
                float hp = buffer[idx] - lp;
                buffer[idx] = lp * inv_gain + hp * gain;
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
                float gain = maidmic_ramp_next(&f->gain_ramp);
                float inv_gain = 1.0f / gain;
                float lp = f->state[c] + alpha * ((float)buffer[idx] - f->state[c]);
                f->state[c] = lp;
                float hp = (float)buffer[idx] - lp;
                buffer[idx] = (int16_t)clampf(lp * inv_gain + hp * gain, -32768.0f, 32767.0f);
            }
        }
    }

    output->meta = input->meta;
    return true;
}

static void formant_reset(void* userdata) {
    formant_data_t* f = (formant_data_t*)userdata;
    // 滤波器记忆按声道清零
    if (f->state) memset(f->state, 0, (size_t)f->channels * sizeof(float));
    maidmic_ramp_reset(&f->gain_ramp);
}

// ============================================================
// 模块描述
// ============================================================

static const maidmic_module_vtable_t formant_vtable = {
    .create = formant_create,
    .destroy = formant_destroy,
    .setup = formant_setup,
    .get_param_count = formant_get_param_count,
    .get_param_info = formant_get_param_info,
    .set_param = formant_set_param,
    .get_param = formant_get_param,
    .process = formant_process,
    .reset = formant_reset,
};

const maidmic_module_t maidmic_module_formant = {
    .id = MAIDMIC_MODULE_ID_FORMANT,
    .name = "Formant Shift",
    .description = "Formant shift compensation (共振峰偏移)",
    .author = "MaidMic Team",
    .version = 1,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_REALTIME,
    .vtable = &formant_vtable,
};
