// maidmic-engine/src/dsp/distortion.c
// Echio 引擎失真模块
// Echio Engine Distortion Module
//
// Waveshaping 软削波失真。
// 算法逻辑从原 process_audio_frame 的 Step 7 迁移而来，保持音质一致。
//
// 参数：
//   distortion — 失真量 0~1

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

typedef struct {
    float amount;        // 失真量 0~1（目标值）
    maidmic_ramp_t amount_ramp;  // 失真量平滑器（消除 zipper 噪声）
    uint32_t sample_rate;
    uint16_t channels;
} distortion_data_t;

// ============================================================
// 参数定义
// ============================================================

static const maidmic_param_t distortion_params[] = {
    {
        .key = "distortion",
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

static void* distortion_create(void) {
    distortion_data_t* d = (distortion_data_t*)calloc(1, sizeof(distortion_data_t));
    if (!d) return NULL;
    d->amount = 0.0f;
    maidmic_ramp_init(&d->amount_ramp, d->amount);
    return d;
}

static void distortion_destroy(void* userdata) {
    free(userdata);
}

static bool distortion_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    distortion_data_t* d = (distortion_data_t*)userdata;
    d->sample_rate = sample_rate;
    d->channels = channels;
    return true;
}

static uint32_t distortion_get_param_count(void* userdata) {
    (void)userdata;
    return 1;
}

static const maidmic_param_t* distortion_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 1) return &distortion_params[index];
    return NULL;
}

static bool distortion_set_param(void* userdata, const char* key, maidmic_param_t value) {
    distortion_data_t* d = (distortion_data_t*)userdata;
    if (strcmp(key, "distortion") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        d->amount = clampf(value.value.as_float, 0.0f, 1.0f);
        // 只更新目标值，process 中逐样本平滑逼近
        maidmic_ramp_set_target(&d->amount_ramp, d->amount);
        return true;
    }
    return false;
}

static maidmic_param_t distortion_get_param(void* userdata, const char* key) {
    distortion_data_t* d = (distortion_data_t*)userdata;
    maidmic_param_t param = {0};
    if (strcmp(key, "distortion") == 0) {
        param.key = "distortion";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = d->amount;
        param.min = 0.0f;
        param.max = 1.0f;
        param.unit = "";
    }
    return param;
}

// ============================================================
// 核心：音频处理
// ============================================================
// drive = 1 + amount * 10
// threshold = 32768 / drive
// 软削波 (tanh 近似)：超过阈值的部分以 0.3 比例压缩
// 输出 = sample * (1 / (1 + amount * 3))

static bool distortion_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    distortion_data_t* d = (distortion_data_t*)userdata;

    // 平滑到位且失真量极低：直通
    if (d->amount_ramp.current <= 0.01f && d->amount_ramp.target <= 0.01f) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    // 多声道：循环遍历全部样本（frame_count * channels，交错排列天然支持）
    uint32_t sample_count = input->meta.frame_count * input->meta.channels;

    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        float* buffer = (float*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            // 每样本平滑推进失真量，消除 zipper 噪声
            float amount = maidmic_ramp_next(&d->amount_ramp);
            float drive = 1.0f + amount * 10.0f;
            float threshold = 1.0f / drive;                       // -1~1 域阈值
            float out_scale = 1.0f / (1.0f + amount * 3.0f);
            float sample = buffer[i] * drive;
            // 软削波 (tanh 近似)
            float abs_s = fabsf(sample);
            if (abs_s > threshold) {
                sample = (sample > 0)
                    ? threshold + (abs_s - threshold) * 0.3f
                    : -threshold - (abs_s - threshold) * 0.3f;
            }
            sample = clampf(sample * out_scale, -1.0f, 1.0f);
            buffer[i] = sample;
        }
    } else if (input->meta.format == MAIDMIC_SAMPLE_S16) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        int16_t* buffer = (int16_t*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            float amount = maidmic_ramp_next(&d->amount_ramp);
            float drive = 1.0f + amount * 10.0f;
            float threshold = 32768.0f / drive;                   // int16 域阈值
            float out_scale = 1.0f / (1.0f + amount * 3.0f);
            float sample = (float)buffer[i] * drive;
            // 软削波 (tanh 近似)
            float abs_s = fabsf(sample);
            if (abs_s > threshold) {
                sample = (sample > 0)
                    ? threshold + (abs_s - threshold) * 0.3f
                    : -threshold - (abs_s - threshold) * 0.3f;
            }
            sample = clampf(sample * out_scale, -32768.0f, 32767.0f);
            buffer[i] = (int16_t)sample;
        }
    }

    output->meta = input->meta;
    return true;
}

static void distortion_reset(void* userdata) {
    distortion_data_t* d = (distortion_data_t*)userdata;
    // Distortion 无持久状态需要复位；参数平滑器直接到位
    maidmic_ramp_reset(&d->amount_ramp);
}

// ============================================================
// 模块描述
// ============================================================

static const maidmic_module_vtable_t distortion_vtable = {
    .create = distortion_create,
    .destroy = distortion_destroy,
    .setup = distortion_setup,
    .get_param_count = distortion_get_param_count,
    .get_param_info = distortion_get_param_info,
    .set_param = distortion_set_param,
    .get_param = distortion_get_param,
    .process = distortion_process,
    .reset = distortion_reset,
};

const maidmic_module_t maidmic_module_distortion = {
    .id = MAIDMIC_MODULE_ID_DISTORTION,
    .name = "Distortion",
    .description = "Waveshaping soft-clip distortion (失真)",
    .author = "MaidMic Team",
    .version = 1,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_REALTIME,
    .vtable = &distortion_vtable,
};
