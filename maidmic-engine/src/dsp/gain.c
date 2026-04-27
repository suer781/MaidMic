// maidmic-engine/src/dsp/gain.c
// Echio 引擎增益模块
// Echio Engine Gain Module
//
// 最简单的 DSP 模块：调整音量增益。
// 用户可调参数：gain（增益值，无上限）
//
// 这是其他 DSP 模块的模板——照着这个结构写新的模块就行。
// This serves as a template for writing new DSP modules.

#include "maidmic/module.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

// ============================================================
// 模块实例数据
// Module instance data
// ============================================================
// 每个模块实例都有自己的数据。
// 因为 Pipeline 可以做多个 Gain 实例，所以不能用全局变量。
// Each module instance has its own data.
// Pipeline can have multiple Gain instances, so no globals.

typedef struct {
    float gain_linear;         // 线性增益值（用户调的是 dB，内部存线性值）
    float gain_db;             // 用户界面上的 dB 值
    bool bypass;               // 旁路开关
    uint32_t sample_rate;
    uint16_t channels;
} gain_data_t;

// 参数定义（供 UI 使用）
// Parameter definitions (for UI use)
// 参数元数据：UI 层根据这些来画滑块
// Parameter metadata: UI uses this to render sliders

static const maidmic_param_t gain_params[] = {
    {
        .key = "gain_db",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 0.0f,
        .min = -60.0f,      // 建议最小值（-60dB ≈ 静音），但用户可填更小
        .max = 60.0f,       // 建议最大值（+60dB ≈ 夸张增益），用户可填更大
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
    { .key = NULL },  // 终止标记 terminator
};

// ============================================================
// vtable 实现
// vtable implementation
// ============================================================

static void* gain_create(void) {
    gain_data_t* g = (gain_data_t*)calloc(1, sizeof(gain_data_t));
    if (!g) return NULL;
    g->gain_db = 0.0f;
    g->gain_linear = 1.0f;  // 0dB = 无增益
    g->bypass = false;
    return g;
}

static void gain_destroy(void* userdata) {
    free(userdata);
}

static bool gain_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    gain_data_t* g = (gain_data_t*)userdata;
    g->sample_rate = sample_rate;
    g->channels = channels;
    return true;
}

static uint32_t gain_get_param_count(void* userdata) {
    (void)userdata;
    return 2;  // gain_db + bypass
}

static const maidmic_param_t* gain_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 2) return &gain_params[index];
    return NULL;
}

static bool gain_set_param(void* userdata, const char* key, maidmic_param_t value) {
    gain_data_t* g = (gain_data_t*)userdata;
    
    if (strcmp(key, "gain_db") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        g->gain_db = value.value.as_float;
        // dB 转线性：linear = 10^(dB/20)
        // dB to linear conversion
        g->gain_linear = powf(10.0f, g->gain_db / 20.0f);
        return true;
    }
    
    if (strcmp(key, "bypass") == 0 && value.type == MAIDMIC_PARAM_BOOL) {
        g->bypass = value.value.as_bool;
        return true;
    }
    
    return false;
}

static maidmic_param_t gain_get_param(void* userdata, const char* key) {
    gain_data_t* g = (gain_data_t*)userdata;
    maidmic_param_t param = {0};
    
    if (strcmp(key, "gain_db") == 0) {
        param.key = "gain_db";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = g->gain_db;
        param.min = -60.0f;
        param.max = 60.0f;
        param.unit = "dB";
    } else if (strcmp(key, "bypass") == 0) {
        param.key = "bypass";
        param.type = MAIDMIC_PARAM_BOOL;
        param.value.as_bool = g->bypass;
    }
    
    return param;
}

// ============================================================
// 核心：音频处理
// Core: audio processing
// ============================================================
// 简单地将每个样本乘以增益值。
// 支持 16-bit 和 32-bit float 两种格式。
// 原地处理（input == output）是安全的。

static bool gain_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    gain_data_t* g = (gain_data_t*)userdata;
    
    if (g->bypass || g->gain_linear == 1.0f) {
        // 旁路或单位增益时直接复制
        memcpy(output->data, input->data, input->data_bytes);
        output->meta = input->meta;
        return true;
    }
    
    uint32_t sample_count = input->meta.frame_count * input->meta.channels;
    
    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        // 32-bit float 处理（DSP 内部推荐格式）
        const float* src = (const float*)input->data;
        float* dst = (float*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            dst[i] = src[i] * g->gain_linear;
            // 不削波！用户可以把增益拉到爆音，这是他们的选择。
            // No clipping! User can push gain into distortion territory.
        }
    } else if (input->meta.format == MAIDMIC_SAMPLE_S16) {
        // 16-bit 整数处理（Android 默认格式）
        const int16_t* src = (const int16_t*)input->data;
        int16_t* dst = (int16_t*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            float sample = (float)src[i] * g->gain_linear;
            // 简单的 int16 钳位（整型溢出会爆音，钳位至少不崩）
            // Simple int16 clamp
            if (sample > 32767.0f) sample = 32767.0f;
            if (sample < -32768.0f) sample = -32768.0f;
            dst[i] = (int16_t)sample;
        }
    }
    
    output->meta = input->meta;
    return true;
}

static void gain_reset(void* userdata) {
    gain_data_t* g = (gain_data_t*)userdata;
    // Gain 没有内部状态需要复位
    (void)g;
}

// ============================================================
// 模块描述
// Module descriptor
// ============================================================

static const maidmic_module_vtable_t gain_vtable = {
    .create = gain_create,
    .destroy = gain_destroy,
    .setup = gain_setup,
    .get_param_count = gain_get_param_count,
    .get_param_info = gain_get_param_info,
    .set_param = gain_set_param,
    .get_param = gain_get_param,
    .process = gain_process,
    .reset = gain_reset,
};

const maidmic_module_t maidmic_module_gain = {
    .id = MAIDMIC_MODULE_ID_GAIN,
    .name = "Gain",
    .description = "Adjust input volume level. No limits — crank it up!",
    .author = "MaidMic Team",
    .version = 1,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_STEREO | MAIDMIC_CAP_REALTIME,
    .vtable = &gain_vtable,
};
