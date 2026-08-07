// maidmic-engine/src/dsp/compressor.c
// Echio 引擎压缩器模块
// Echio Engine Compressor Module
//
// 炸麦效果核心：动态范围压缩。
// 当信号超过阈值时，按压缩比降低增益，再做补偿增益。
// 算法逻辑从原 process_audio_frame 的 Step 1b 迁移而来，保持音质一致。
//
// 参数：
//   comp_threshold — 阈值 dB (-60 ~ 0)
//   comp_ratio     — 压缩比 (1 ~ 20)
//   comp_makeup    — 补偿增益 dB (0 ~ 20)

#include "maidmic/module.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

// ============================================================
// 辅助函数
// Helper functions
// ============================================================

static inline float clampf(float v, float min, float max) {
    return v < min ? min : (v > max ? max : v);
}

static inline float db_to_linear(float db) {
    return powf(10.0f, db / 20.0f);
}

// ============================================================
// 模块实例数据
// Module instance data
// ============================================================

typedef struct {
    float threshold_db;   // 阈值 dB（目标值）
    float ratio;          // 压缩比（目标值）
    float makeup_db;      // 补偿增益 dB（目标值）
    float* env;           // 包络跟随器状态，按声道隔离（每声道一个）
    // 参数平滑器（消除 zipper 噪声）：平滑"计算中间量"（线性阈值/压缩比/线性补偿增益），
    // 避免在每样本循环内做 db_to_linear 的 powf 重计算
    maidmic_ramp_t threshold_ramp;  // 线性阈值平滑器
    maidmic_ramp_t ratio_ramp;      // 压缩比平滑器
    maidmic_ramp_t makeup_ramp;     // 线性补偿增益平滑器
    uint32_t sample_rate;
    uint16_t channels;
} compressor_data_t;

// ============================================================
// 参数定义
// Parameter definitions
// ============================================================

static const maidmic_param_t compressor_params[] = {
    {
        .key = "comp_threshold",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 0.0f,
        .min = -60.0f,
        .max = 0.0f,
        .unit = "dB",
    },
    {
        .key = "comp_ratio",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 1.0f,
        .min = 1.0f,
        .max = 20.0f,
        .unit = ":1",
    },
    {
        .key = "comp_makeup",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 0.0f,
        .min = 0.0f,
        .max = 20.0f,
        .unit = "dB",
    },
    { .key = NULL },  // 终止标记
};

// ============================================================
// vtable 实现
// vtable implementation
// ============================================================

static void* compressor_create(void) {
    compressor_data_t* c = (compressor_data_t*)calloc(1, sizeof(compressor_data_t));
    if (!c) return NULL;
    c->threshold_db = 0.0f;
    c->ratio = 1.0f;
    c->makeup_db = 0.0f;
    c->env = NULL;  // setup 时按声道数分配
    maidmic_ramp_init(&c->threshold_ramp, 1.0f);   // 0dB 阈值 → 线性 1.0
    maidmic_ramp_init(&c->ratio_ramp, 1.0f);
    maidmic_ramp_init(&c->makeup_ramp, 1.0f);      // 0dB 补偿 → 线性 1.0
    return c;
}

static void compressor_destroy(void* userdata) {
    compressor_data_t* c = (compressor_data_t*)userdata;
    free(c->env);
    free(c);
}

static bool compressor_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    compressor_data_t* c = (compressor_data_t*)userdata;
    c->sample_rate = sample_rate;
    // 包络状态必须按声道隔离：声道数变化或首次调用时（重新）分配并清零
    if (!c->env || channels != c->channels) {
        float* new_env = (float*)realloc(c->env, (size_t)channels * sizeof(float));
        if (!new_env) return false;
        c->env = new_env;
        memset(c->env, 0, (size_t)channels * sizeof(float));
    }
    c->channels = channels;
    return true;
}

static uint32_t compressor_get_param_count(void* userdata) {
    (void)userdata;
    return 3;
}

static const maidmic_param_t* compressor_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 3) return &compressor_params[index];
    return NULL;
}

static bool compressor_set_param(void* userdata, const char* key, maidmic_param_t value) {
    compressor_data_t* c = (compressor_data_t*)userdata;

    if (strcmp(key, "comp_threshold") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        c->threshold_db = clampf(value.value.as_float, -60.0f, 0.0f);
        // 只更新线性阈值目标值，process 中逐样本平滑逼近
        maidmic_ramp_set_target(&c->threshold_ramp, db_to_linear(c->threshold_db));
        return true;
    }
    if (strcmp(key, "comp_ratio") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        c->ratio = clampf(value.value.as_float, 1.0f, 20.0f);
        maidmic_ramp_set_target(&c->ratio_ramp, c->ratio);
        return true;
    }
    if (strcmp(key, "comp_makeup") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        c->makeup_db = clampf(value.value.as_float, 0.0f, 20.0f);
        // 只更新线性补偿增益目标值，process 中逐样本平滑逼近
        maidmic_ramp_set_target(&c->makeup_ramp, db_to_linear(c->makeup_db));
        return true;
    }
    return false;
}

static maidmic_param_t compressor_get_param(void* userdata, const char* key) {
    compressor_data_t* c = (compressor_data_t*)userdata;
    maidmic_param_t param = {0};

    if (strcmp(key, "comp_threshold") == 0) {
        param.key = "comp_threshold";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = c->threshold_db;
        param.min = -60.0f;
        param.max = 0.0f;
        param.unit = "dB";
    } else if (strcmp(key, "comp_ratio") == 0) {
        param.key = "comp_ratio";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = c->ratio;
        param.min = 1.0f;
        param.max = 20.0f;
        param.unit = ":1";
    } else if (strcmp(key, "comp_makeup") == 0) {
        param.key = "comp_makeup";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = c->makeup_db;
        param.min = 0.0f;
        param.max = 20.0f;
        param.unit = "dB";
    }
    return param;
}

// ============================================================
// 核心：音频处理
// Core: audio processing
// ============================================================
// 算法从原 process_audio_frame 的 Step 1b 原样迁移。
// 包络跟随 (RMS 近似) → 计算增益衰减 → 应用压缩 + 补偿增益。
//
// 性能注记（NEON）：本模块保留标量实现。包络跟随是逐样本的每声道递归
// env += (|x| - env)·α，且每个样本按 |x| 与 env 的大小关系选择 attack/release，
// 属于带数据依赖分支的串行链；代码中不存在"块状平方和累加"结构可供 4 路
// 并行（此处用 |x| 而非 x²），强行 M 路并行会改变包络数值进而改变输出，
// 违反"标量/NEON 两条路径输出一致"约束，故不做 NEON 向量化。

static bool compressor_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    compressor_data_t* c = (compressor_data_t*)userdata;

    // 压缩比平滑到位且仍 <= 1 时直通（无压缩）
    if (c->ratio_ramp.current <= 1.0f && c->ratio_ramp.target <= 1.0f) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    uint32_t frame_count = input->meta.frame_count;
    uint16_t channels = input->meta.channels;
    float attack = 0.1f;    // 快速启动
    float release = 0.01f;  // 慢释放

    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        float* buffer = (float*)output->data;
        for (uint32_t i = 0; i < frame_count; i++) {
            for (uint16_t ch = 0; ch < channels; ch++) {
                uint32_t idx = i * (uint32_t)channels + ch;
                // 每样本平滑推进共享参数，消除 zipper 噪声
                float threshold_linear = maidmic_ramp_next(&c->threshold_ramp);
                float ratio = maidmic_ramp_next(&c->ratio_ramp);
                float makeup_linear = maidmic_ramp_next(&c->makeup_ramp);
                // 数学等价化简：gain_reduction = (env/thr)^(1/ratio - 1)
                float compress_exp = (1.0f / ratio) - 1.0f;
                float sample = buffer[idx];  // F32 输入已在 -1~1 域
                float abs_s = fabsf(sample);
                // 包络跟随 (RMS 近似)，状态按声道隔离
                float env = c->env[ch] + (abs_s - c->env[ch]) * (abs_s > c->env[ch] ? attack : release);
                c->env[ch] = env;
                // 计算增益衰减
                // 性能：powf(x, e) 在 ARM 上约 20-100 cycles，是本引擎最贵单点；
                // 恒等改写为 exp2f(e·log2f(x))（exp2f/log2f 各约 5-10 cycles）。
                float gain_reduction = 1.0f;
                if (env > threshold_linear) {
                    gain_reduction = exp2f(compress_exp * log2f(env / threshold_linear));
                }
                // 应用压缩 + 补偿增益
                sample *= gain_reduction * makeup_linear;
                sample = clampf(sample, -1.0f, 1.0f);
                buffer[idx] = sample;
            }
        }
    } else if (input->meta.format == MAIDMIC_SAMPLE_S16) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        int16_t* buffer = (int16_t*)output->data;
        for (uint32_t i = 0; i < frame_count; i++) {
            for (uint16_t ch = 0; ch < channels; ch++) {
                uint32_t idx = i * (uint32_t)channels + ch;
                float threshold_linear = maidmic_ramp_next(&c->threshold_ramp);
                float ratio = maidmic_ramp_next(&c->ratio_ramp);
                float makeup_linear = maidmic_ramp_next(&c->makeup_ramp);
                float compress_exp = (1.0f / ratio) - 1.0f;
                float sample = (float)buffer[idx] / 32768.0f;  // 归一化到 -1~1
                float abs_s = fabsf(sample);
                // 包络跟随 (RMS 近似)，状态按声道隔离
                float env = c->env[ch] + (abs_s - c->env[ch]) * (abs_s > c->env[ch] ? attack : release);
                c->env[ch] = env;
                // 计算增益衰减（powf → exp2f·log2f 恒等改写，见 F32 分支注记）
                float gain_reduction = 1.0f;
                if (env > threshold_linear) {
                    gain_reduction = exp2f(compress_exp * log2f(env / threshold_linear));
                }
                // 应用压缩 + 补偿增益
                sample *= gain_reduction * makeup_linear;
                sample = clampf(sample, -1.0f, 1.0f);
                buffer[idx] = (int16_t)(sample * 32768.0f);
            }
        }
    }

    output->meta = input->meta;
    return true;
}

static void compressor_reset(void* userdata) {
    compressor_data_t* c = (compressor_data_t*)userdata;
    // 包络状态按声道清零
    if (c->env) memset(c->env, 0, (size_t)c->channels * sizeof(float));
    maidmic_ramp_reset(&c->threshold_ramp);
    maidmic_ramp_reset(&c->ratio_ramp);
    maidmic_ramp_reset(&c->makeup_ramp);
}

// ============================================================
// 模块描述
// Module descriptor
// ============================================================

static const maidmic_module_vtable_t compressor_vtable = {
    .create = compressor_create,
    .destroy = compressor_destroy,
    .setup = compressor_setup,
    .get_param_count = compressor_get_param_count,
    .get_param_info = compressor_get_param_info,
    .set_param = compressor_set_param,
    .get_param = compressor_get_param,
    .process = compressor_process,
    .reset = compressor_reset,
};

const maidmic_module_t maidmic_module_compressor = {
    .id = MAIDMIC_MODULE_ID_COMPRESSOR,
    .name = "Compressor",
    .description = "Dynamic range compressor (炸麦效果核心)",
    .author = "MaidMic Team",
    .version = 1,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_REALTIME,
    .vtable = &compressor_vtable,
};
