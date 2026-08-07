// maidmic-engine/src/dsp/echo.c
// Echio 引擎回声/延迟模块
// Echio Engine Echo/Delay Module
//
// 延迟线回声：干湿混合 + 反馈。
// 算法逻辑从原 process_audio_frame 的 Step 8 迁移而来，保持音质一致。
//
// 参数：
//   echo_delay_ms — 回声延迟 (毫秒)
//   echo_decay    — 回声衰减 0~0.9

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

#define ECHO_BUF_SIZE 96000  // 默认容量 2秒 @48kHz（单声道样本数）

typedef struct {
    float delay_ms;            // 延迟毫秒（目标值）
    float decay;               // 衰减比 0~0.9（目标值）
    float* buf;                // 延迟线（动态分配，按样本数计，容量 >= 声道相关需求）
    int buf_size;              // 延迟线容量（样本数）
    int pos;                   // 延迟线写指针（全局样本索引，交错数据天然支持）
    int delay_samples;         // 延迟样本数（帧，每声道）
    maidmic_ramp_t decay_ramp; // 衰减比平滑器（消除 zipper 噪声）
    uint32_t sample_rate;
    uint16_t channels;
} echo_data_t;

// ============================================================
// 参数定义
// ============================================================

static const maidmic_param_t echo_params[] = {
    {
        .key = "echo_delay_ms",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 0.0f,
        .min = 0.0f,
        .max = 2000.0f,
        .unit = "ms",
    },
    {
        .key = "echo_decay",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 0.0f,
        .min = 0.0f,
        .max = 0.9f,
        .unit = "",
    },
    { .key = NULL },
};

// ============================================================
// vtable 实现
// ============================================================

static void* echo_create(void) {
    echo_data_t* e = (echo_data_t*)calloc(1, sizeof(echo_data_t));
    if (!e) return NULL;
    e->delay_ms = 0.0f;
    e->decay = 0.0f;
    e->pos = 0;
    e->delay_samples = 0;
    e->buf_size = ECHO_BUF_SIZE;
    e->buf = (float*)calloc((size_t)ECHO_BUF_SIZE, sizeof(float));
    if (!e->buf) {
        free(e);
        return NULL;
    }
    maidmic_ramp_init(&e->decay_ramp, e->decay);
    return e;
}

static void echo_destroy(void* userdata) {
    echo_data_t* e = (echo_data_t*)userdata;
    free(e->buf);
    free(e);
}

static bool echo_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    echo_data_t* e = (echo_data_t*)userdata;
    e->sample_rate = sample_rate;
    e->channels = channels;
    // 延迟线按"样本"计：最大延迟 2000ms = 2*sample_rate 帧，再乘声道数。
    // 缓冲容量必须 >= 声道相关需求（2 声道 @48kHz 需 192000 样本）。
    int need = (int)(2 * sample_rate) * (int)channels;
    if (need < ECHO_BUF_SIZE) need = ECHO_BUF_SIZE;
    if (need != e->buf_size) {
        float* new_buf = (float*)realloc(e->buf, (size_t)need * sizeof(float));
        if (!new_buf) return false;
        e->buf = new_buf;
        memset(e->buf, 0, (size_t)need * sizeof(float));  // 延迟线长度变化，整体清零
        e->buf_size = need;
    }
    // 重新计算延迟帧数（每声道）
    e->delay_samples = (int)(e->delay_ms * (float)sample_rate / 1000.0f);
    if (e->delay_samples > (int)(2 * sample_rate)) e->delay_samples = (int)(2 * sample_rate);
    if (e->pos >= e->buf_size) e->pos = 0;
    return true;
}

static uint32_t echo_get_param_count(void* userdata) {
    (void)userdata;
    return 2;
}

static const maidmic_param_t* echo_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 2) return &echo_params[index];
    return NULL;
}

static bool echo_set_param(void* userdata, const char* key, maidmic_param_t value) {
    echo_data_t* e = (echo_data_t*)userdata;

    if (strcmp(key, "echo_delay_ms") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        e->delay_ms = clampf(value.value.as_float, 0.0f, 2000.0f);
        // 更新延迟帧数（每声道），按"块边界生效"，不做逐样本平滑。
        // 理由：延迟长度改变会使读指针在环形缓冲上跳变，跳变本身即产生咔哒声；
        // 且延迟线内容与长度强耦合，逐样本改写长度在环形缓冲上并不成立。
        // （对应任务允许的例外：对平滑会导致正确性复杂的参数，仅在块边界生效）
        e->delay_samples = (int)(e->delay_ms * (float)e->sample_rate / 1000.0f);
        if (e->sample_rate > 0 && e->delay_samples > (int)(2 * e->sample_rate)) {
            e->delay_samples = (int)(2 * e->sample_rate);
        }
        return true;
    }
    if (strcmp(key, "echo_decay") == 0 && value.type == MAIDMIC_PARAM_FLOAT) {
        e->decay = clampf(value.value.as_float, 0.0f, 0.9f);
        // 只更新目标值，process 中逐样本平滑逼近
        maidmic_ramp_set_target(&e->decay_ramp, e->decay);
        return true;
    }
    return false;
}

static maidmic_param_t echo_get_param(void* userdata, const char* key) {
    echo_data_t* e = (echo_data_t*)userdata;
    maidmic_param_t param = {0};
    if (strcmp(key, "echo_delay_ms") == 0) {
        param.key = "echo_delay_ms";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = e->delay_ms;
        param.min = 0.0f;
        param.max = 2000.0f;
        param.unit = "ms";
    } else if (strcmp(key, "echo_decay") == 0) {
        param.key = "echo_decay";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = e->decay;
        param.min = 0.0f;
        param.max = 0.9f;
        param.unit = "";
    }
    return param;
}

// ============================================================
// 核心：音频处理
// ============================================================
// 延迟线环形缓冲区（setup 时按 2s × 声道数动态扩容，容量以样本计）
// read_pos = pos - delay (环形)
// wet = buf[read_pos] * decay
// 写延迟线 = 干信号 * 0.5 + wet * 0.5
// 输出 = 干信号 * 0.7 + wet * 0.3

static bool echo_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    echo_data_t* e = (echo_data_t*)userdata;

    // 延迟过短（<100 帧）或衰减平滑到位且≈0：直通
    if (e->delay_samples <= 100 ||
        (e->decay_ramp.current <= 0.01f && e->decay_ramp.target <= 0.01f)) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    // 多声道：按全局样本索引读写延迟线（交错数据天然支持）。
    // 延迟量以帧为单位换算成样本时要乘以声道数（用 input 的声道数，与样本循环一致）。
    uint32_t sample_count = input->meta.frame_count * input->meta.channels;
    int buf_size = e->buf_size;
    if (buf_size <= 0) buf_size = ECHO_BUF_SIZE;
    int delay = e->delay_samples * (int)input->meta.channels;
    if (delay > buf_size - 1) delay = buf_size - 1;
    if (delay <= 0) delay = 1;

    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        float* buffer = (float*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            // 每样本平滑推进衰减比，消除 zipper 噪声
            float decay = maidmic_ramp_next(&e->decay_ramp);
            // 从延迟线读取
            int read_pos = e->pos - delay;
            if (read_pos < 0) read_pos += buf_size;
            float wet = e->buf[read_pos] * decay;
            // 写入延迟线（输入 + 反馈）
            e->buf[e->pos] = buffer[i] * 0.5f + wet * 0.5f;
            // 混合干湿
            buffer[i] = buffer[i] * 0.7f + wet * 0.3f;
            if (++e->pos >= buf_size) e->pos = 0;
        }
    } else if (input->meta.format == MAIDMIC_SAMPLE_S16) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        int16_t* buffer = (int16_t*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            float decay = maidmic_ramp_next(&e->decay_ramp);
            int read_pos = e->pos - delay;
            if (read_pos < 0) read_pos += buf_size;
            float wet = e->buf[read_pos] * decay;
            e->buf[e->pos] = (float)buffer[i] * 0.5f + wet * 0.5f;
            buffer[i] = (int16_t)clampf(
                (float)buffer[i] * 0.7f + wet * 0.3f,
                -32768.0f, 32767.0f);
            if (++e->pos >= buf_size) e->pos = 0;
        }
    }

    output->meta = input->meta;
    return true;
}

static void echo_reset(void* userdata) {
    echo_data_t* e = (echo_data_t*)userdata;
    if (e->buf) memset(e->buf, 0, (size_t)e->buf_size * sizeof(float));
    e->pos = 0;
    maidmic_ramp_reset(&e->decay_ramp);
}

// ============================================================
// 模块描述
// ============================================================

static const maidmic_module_vtable_t echo_vtable = {
    .create = echo_create,
    .destroy = echo_destroy,
    .setup = echo_setup,
    .get_param_count = echo_get_param_count,
    .get_param_info = echo_get_param_info,
    .set_param = echo_set_param,
    .get_param = echo_get_param,
    .process = echo_process,
    .reset = echo_reset,
};

const maidmic_module_t maidmic_module_echo = {
    .id = MAIDMIC_MODULE_ID_ECHO,
    .name = "Echo",
    .description = "Delay-line echo (回声/延迟)",
    .author = "MaidMic Team",
    .version = 1,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_REALTIME,
    .vtable = &echo_vtable,
};
