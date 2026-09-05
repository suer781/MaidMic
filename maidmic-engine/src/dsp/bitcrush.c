// maidmic-engine/src/dsp/bitcrush.c
// Echio 引擎低保真（降比特/降采样率）模块
// Echio Engine Bitcrusher Module
//
// 经典 Lo-Fi 效果，也是"机器人音色"的核心成分：
//   - 位深量化：q = round(x·L)/L，L = 2^(bits−1)
//   - 采样率保持（sample & hold）：每 down 个样本保持一次
//   - 干湿混合
// 两个维度都开满（16bit / down=1）时模块自动直通。
//
// 参数：
//   bitcrush_bits — 有效位深 (1 ~ 16，默认 16)
//   bitcrush_down — 采样率降低倍数 (1 ~ 32，默认 1)
//   bitcrush_mix  — 干湿混合 (0 ~ 1，默认 0)

#include "maidmic/module.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

// ============================================================
// 模块实例数据
// ============================================================

#define BC_MAX_CHANNELS 2u

typedef struct {
    float bits;              // 目标位深
    float down;              // 目标降采样倍数
    float mix;               // 目标干湿
    maidmic_ramp_t mix_ramp;
    uint32_t sample_rate;
    uint16_t channels;
    uint32_t hold_cnt[BC_MAX_CHANNELS];  // 保持计数
    float hold_val[BC_MAX_CHANNELS];     // 保持值
} bitcrush_data_t;

// ============================================================
// 参数定义
// ============================================================

static const maidmic_param_t bitcrush_params[] = {
    {
        .key = "bitcrush_bits",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 16.0f,
        .min = 1.0f,
        .max = 16.0f,
        .unit = "bit",
    },
    {
        .key = "bitcrush_down",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 1.0f,
        .min = 1.0f,
        .max = 32.0f,
        .unit = "x",
    },
    {
        .key = "bitcrush_mix",
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

static void* bitcrush_create(void) {
    bitcrush_data_t* b = (bitcrush_data_t*)calloc(1, sizeof(bitcrush_data_t));
    if (!b) return NULL;
    b->bits = 16.0f;
    b->down = 1.0f;
    b->mix = 0.0f;
    maidmic_ramp_init(&b->mix_ramp, b->mix);
    return b;
}

static void bitcrush_destroy(void* userdata) {
    free(userdata);
}

static bool bitcrush_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    bitcrush_data_t* b = (bitcrush_data_t*)userdata;
    if (sample_rate == 0 || channels == 0 || channels > BC_MAX_CHANNELS) return false;
    b->sample_rate = sample_rate;
    b->channels = channels;
    for (uint32_t c = 0; c < BC_MAX_CHANNELS; c++) {
        b->hold_cnt[c] = 0;
        b->hold_val[c] = 0.0f;
    }
    return true;
}

static uint32_t bitcrush_get_param_count(void* userdata) {
    (void)userdata;
    return 3;
}

static const maidmic_param_t* bitcrush_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 3) return &bitcrush_params[index];
    return NULL;
}

static bool bitcrush_set_param(void* userdata, const char* key, maidmic_param_t value) {
    bitcrush_data_t* b = (bitcrush_data_t*)userdata;
    if (strcmp(key, "bitcrush_bits") == 0 &&
        (value.type == MAIDMIC_PARAM_FLOAT || value.type == MAIDMIC_PARAM_INT)) {
        float v = (value.type == MAIDMIC_PARAM_FLOAT) ? value.value.as_float : (float)value.value.as_int;
        if (v < 1.0f) v = 1.0f;
        if (v > 16.0f) v = 16.0f;
        b->bits = v;
        return true;
    }
    if (strcmp(key, "bitcrush_down") == 0 &&
        (value.type == MAIDMIC_PARAM_FLOAT || value.type == MAIDMIC_PARAM_INT)) {
        float v = (value.type == MAIDMIC_PARAM_FLOAT) ? value.value.as_float : (float)value.value.as_int;
        if (v < 1.0f) v = 1.0f;
        if (v > 32.0f) v = 32.0f;
        b->down = v;
        return true;
    }
    if (strcmp(key, "bitcrush_mix") == 0 &&
        (value.type == MAIDMIC_PARAM_FLOAT || value.type == MAIDMIC_PARAM_INT)) {
        float v = (value.type == MAIDMIC_PARAM_FLOAT) ? value.value.as_float : (float)value.value.as_int;
        if (v < 0.0f) v = 0.0f;
        if (v > 1.0f) v = 1.0f;
        b->mix = v;
        maidmic_ramp_set_target(&b->mix_ramp, v);
        return true;
    }
    return false;
}

static maidmic_param_t bitcrush_get_param(void* userdata, const char* key) {
    bitcrush_data_t* b = (bitcrush_data_t*)userdata;
    maidmic_param_t param = {0};
    if (strcmp(key, "bitcrush_bits") == 0) {
        param.key = "bitcrush_bits";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = b->bits;
        param.min = 1.0f;
        param.max = 16.0f;
        param.unit = "bit";
    } else if (strcmp(key, "bitcrush_down") == 0) {
        param.key = "bitcrush_down";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = b->down;
        param.min = 1.0f;
        param.max = 32.0f;
        param.unit = "x";
    } else if (strcmp(key, "bitcrush_mix") == 0) {
        param.key = "bitcrush_mix";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = b->mix;
        param.min = 0.0f;
        param.max = 1.0f;
        param.unit = "";
    }
    return param;
}

// ============================================================
// 核心：音频处理
// ============================================================

static bool bitcrush_is_active(bitcrush_data_t* b) {
    const bool dims_off = (b->bits >= 15.5f) && (b->down <= 1.5f);
    return !(dims_off) &&
           !(b->mix_ramp.current <= 0.003f && b->mix_ramp.target <= 0.003f);
}

static bool bitcrush_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    bitcrush_data_t* b = (bitcrush_data_t*)userdata;

    if (!bitcrush_is_active(b)) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    const uint32_t sample_count = input->meta.frame_count * input->meta.channels;
    const uint16_t chs = input->meta.channels;
    const int bits_i = (int)(b->bits + 0.5f);
    const float levels = (bits_i <= 1) ? 1.0f : (float)(1 << (bits_i - 1));
    const float down = b->down;

    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        float* buffer = (float*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            const float mix = maidmic_ramp_next(&b->mix_ramp);
            const uint32_t ch = (chs == 2u) ? (i & 1u) : 0u;
            const float dry = buffer[i];

            // 采样率保持（sample & hold）
            float wet;
            if (b->hold_cnt[ch] == 0) {
                b->hold_val[ch] = dry;
                wet = dry;
            } else {
                wet = b->hold_val[ch];
            }
            b->hold_cnt[ch]++;
            if ((float)b->hold_cnt[ch] >= down) b->hold_cnt[ch] = 0;

            // 位深量化
            wet = floorf(wet * levels + 0.5f) / levels;

            buffer[i] = dry * (1.0f - mix) + wet * mix;
        }
    } else if (input->meta.format == MAIDMIC_SAMPLE_S16) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        int16_t* buffer = (int16_t*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            const float mix = maidmic_ramp_next(&b->mix_ramp);
            const uint32_t ch = (chs == 2u) ? (i & 1u) : 0u;
            const float dry = (float)buffer[i] / 32768.0f;

            float wet;
            if (b->hold_cnt[ch] == 0) {
                b->hold_val[ch] = dry;
                wet = dry;
            } else {
                wet = b->hold_val[ch];
            }
            b->hold_cnt[ch]++;
            if ((float)b->hold_cnt[ch] >= down) b->hold_cnt[ch] = 0;

            wet = floorf(wet * levels + 0.5f) / levels;

            float o = dry * (1.0f - mix) + wet * mix;
            if (o > 1.0f) o = 1.0f;
            if (o < -1.0f) o = -1.0f;
            buffer[i] = (int16_t)(o * 32767.0f);
        }
    }

    output->meta = input->meta;
    return true;
}

static void bitcrush_reset(void* userdata) {
    bitcrush_data_t* b = (bitcrush_data_t*)userdata;
    for (uint32_t c = 0; c < BC_MAX_CHANNELS; c++) {
        b->hold_cnt[c] = 0;
        b->hold_val[c] = 0.0f;
    }
    maidmic_ramp_reset(&b->mix_ramp);
}

// ============================================================
// 模块描述
// ============================================================

static const maidmic_module_vtable_t bitcrush_vtable = {
    .create = bitcrush_create,
    .destroy = bitcrush_destroy,
    .setup = bitcrush_setup,
    .get_param_count = bitcrush_get_param_count,
    .get_param_info = bitcrush_get_param_info,
    .set_param = bitcrush_set_param,
    .get_param = bitcrush_get_param,
    .process = bitcrush_process,
    .reset = bitcrush_reset,
};

const maidmic_module_t maidmic_module_bitcrush = {
    .id = MAIDMIC_MODULE_ID_BITCRUSH,
    .name = "Bitcrusher",
    .description = "Sample&hold + bit-depth Lo-Fi (降比特，机器人音色)",
    .author = "MaidMic Team",
    .version = 1,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_STEREO | MAIDMIC_CAP_REALTIME,
    .vtable = &bitcrush_vtable,
};
