// maidmic-engine/src/voice/voice_transform.c
// MaidMic 引擎 LPC 源-滤波器变声模块
// MaidMic Engine LPC Source-Filter Voice Transformation Module
//
// 基于 LPC（线性预测编码）源-滤波器模型的人声变换：
//   pitch_semitones — 激励变调：对分析窗残差按平滑比率线性插值重采样，
//                     改变激励基频 → 整体音高搬移（-12~+12 半音）
//   formant_shift   — 共振峰偏移：对 LPC 系数做谱包络频率伸缩（-12~+12 半音）
//   lpc_order       — LPC 分析阶数（10~20）
//   bypass          — 旁路开关
//
// 处理流程（非实时，允许模块内缓冲）：
//   输入滑窗累积 → LPC 分析（汉明窗 + 自相关 + Levinson-Durbin）→ 逆滤波得残差激励
//   → 激励按平滑比率线性插值变调（读指针每声道独立、跨块持续）
//   → 共振峰偏移系数 → 全极点合成（state 跨块保持，块间连续）
//   无语音帧（能量/过零率判定）跳过激励变调，直接用原残差合成。
// 热路径（逐样本循环）零堆分配：所有缓冲在 setup 一次性分配并复用。

#include "maidmic/module.h"
#include "maidmic/lpc.h"
#include "maidmic/pitch_detector.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

// ============================================================
// 常量
// Constants
// ============================================================

#define VT_MAX_CHANNELS 2u       // 声道状态上限（立体声）
#define VT_MAX_BLOCK    4096u    // 单声道最大块长（超出则直通，保证滑窗有界、热路径零分配）
#define VT_WIN_MIN      32u      // 分析窗长下限（须大于 LPC 阶数上限 20）
#define VT_WIN_MAX      65536u   // 分析窗长上限（与 lpc.c 单帧上限一致）
#define VT_RATIO_MIN    0.25f    // 变调比率钳位下限（ratio = 2^(semitones/12)）
#define VT_RATIO_MAX    4.0f     // 变调比率钳位上限
#define VT_ORDER_MIN    10       // lpc_order 参数下限
#define VT_ORDER_MAX    20       // lpc_order 参数上限（= MAIDMIC_LPC_MAX_ORDER）
#define VT_S16_SCALE_IN  (1.0f / 32768.0f)  // S16 → float 归一化系数
#define VT_S16_SCALE_OUT 32767.0f           // float → S16 归一化系数

// ============================================================
// 模块实例数据
// Module instance data
// ============================================================

typedef struct {
    // ---- 每声道滑窗 / 激励 / 合成状态（按 setup 的 channels 使用，上限 2）----
    float* in_win;       // 输入滑窗（线性缓冲，容量 = window_len + VT_MAX_BLOCK）
    uint32_t in_count;   // 滑窗内有效样本数（恒 < window_len）
    float* residual;     // 分析窗残差（激励源，环，容量 = window_len）
    float* stretch;      // 变速激励滑窗（环，容量 = window_len + VT_MAX_BLOCK）
    uint32_t sread;      // 变速滑窗读位置
    uint32_t s_avail;    // 变速滑窗可读样本数（未消费部分保留到下一块）
    float pos;           // 残差插值读指针（样本索引，按声道独立、跨块持续）
    float state[MAIDMIC_LPC_MAX_ORDER];          // 合成 state（跨块保持，容量 20）
    float a[MAIDMIC_LPC_MAX_ORDER + 1u];         // 本声道 LPC 系数 a[0..order]
    float a_shifted[MAIDMIC_LPC_MAX_ORDER + 1u]; // 共振峰偏移后的系数
    bool a_ready;        // 已完成至少一次成功分析（否则本块直通）
    maidmic_ramp_t ramp; // 变调比率平滑器（逐样本逼近目标比率，消除 zipper 噪声）
} vt_channel_t;

typedef struct {
    // 用户参数
    float pitch_st;      // 变调半音 -12~+12
    float formant_st;    // 共振峰偏移半音 -12~+12
    int32_t lpc_order;   // LPC 分析阶数 10~20
    bool bypass;         // 旁路开关

    uint32_t sample_rate;
    uint16_t channels;
    uint32_t window_len;   // 分析窗长 = round(sample_rate * 0.02)
    uint32_t in_win_cap;   // 输入滑窗容量 = window_len + VT_MAX_BLOCK
    uint32_t stretch_cap;  // 变速滑窗容量 = window_len + VT_MAX_BLOCK

    // 每声道状态（上限 2）
    vt_channel_t ch[VT_MAX_CHANNELS];

    // 共享块级临时缓冲（逐声道顺序复用；热路径零堆分配）
    float scratch_in[VT_MAX_BLOCK];   // 本声道 float 块（输入提取 / 输出写回，原地）
    float scratch_exc[VT_MAX_BLOCK];  // 本块变速激励
} vt_data_t;

// ============================================================
// 参数定义（供 UI 使用）
// Parameter definitions (for UI use)
// ============================================================

static const maidmic_param_t vt_params[] = {
    {
        .key = "pitch_semitones",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 0.0f,
        .min = -12.0f,
        .max = 12.0f,
        .unit = "semitone",
    },
    {
        .key = "formant_shift",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 0.0f,
        .min = -12.0f,
        .max = 12.0f,
        .unit = "semitone",
    },
    {
        .key = "lpc_order",
        .type = MAIDMIC_PARAM_INT,
        .value.as_int = 16,
        .min = 10.0f,
        .max = 20.0f,
        .unit = "",
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
// 辅助函数
// Helpers
// ============================================================

// 直通复制（支持原地：input == output 时跳过自拷贝），并同步元数据
static void vt_copy(const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    if (output->data != input->data && input->data != NULL && output->data != NULL) {
        memcpy(output->data, input->data, input->data_bytes);
    }
    output->meta = input->meta;
}

// 复位单个声道的全部内部状态（滑窗清空、读指针归零、合成 state 清零）
static void vt_reset_channel(vt_data_t* v, uint32_t ch) {
    vt_channel_t* c = &v->ch[ch];
    c->in_count = 0;
    c->pos = 0.0f;
    c->sread = 0;
    c->s_avail = 0;
    c->a_ready = false;
    memset(c->state, 0, sizeof(c->state));
    maidmic_ramp_reset(&c->ramp);  // 平滑器直接到位（复位不在音频热路径）
}

// ============================================================
// vtable 实现
// vtable implementation
// ============================================================

static void* vt_create(void) {
    vt_data_t* v = (vt_data_t*)calloc(1, sizeof(vt_data_t));
    if (!v) return NULL;
    v->pitch_st = 0.0f;
    v->formant_st = 0.0f;
    v->lpc_order = 16;
    v->bypass = false;
    for (uint32_t ch = 0; ch < VT_MAX_CHANNELS; ch++) {
        maidmic_ramp_init(&v->ch[ch].ramp, 1.0f);
    }
    return v;
}

static void vt_destroy(void* userdata) {
    vt_data_t* v = (vt_data_t*)userdata;
    if (!v) return;
    for (uint32_t ch = 0; ch < VT_MAX_CHANNELS; ch++) {
        free(v->ch[ch].in_win);
        free(v->ch[ch].residual);
        free(v->ch[ch].stretch);
    }
    free(v);
}

static bool vt_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    vt_data_t* v = (vt_data_t*)userdata;
    if (sample_rate == 0 || channels == 0 || channels > VT_MAX_CHANNELS) return false;

    // 释放旧缓冲（采样率/声道变化时重建）
    for (uint32_t ch = 0; ch < VT_MAX_CHANNELS; ch++) {
        free(v->ch[ch].in_win);   v->ch[ch].in_win = NULL;
        free(v->ch[ch].residual); v->ch[ch].residual = NULL;
        free(v->ch[ch].stretch);  v->ch[ch].stretch = NULL;
    }

    // 分析窗长 = 20ms（sample_rate*0.02），并钳制在合理范围（须 > LPC 阶数上限）
    uint32_t wlen = (uint32_t)((float)sample_rate * 0.02f + 0.5f);
    if (wlen < VT_WIN_MIN) wlen = VT_WIN_MIN;
    if (wlen > VT_WIN_MAX) wlen = VT_WIN_MAX;

    v->sample_rate = sample_rate;
    v->channels = channels;
    v->window_len = wlen;
    v->in_win_cap = wlen + VT_MAX_BLOCK;
    v->stretch_cap = wlen + VT_MAX_BLOCK;

    // 为每个声道分配缓冲（上限 2）；任一失败则整体回滚并返回 false
    for (uint32_t ch = 0; ch < VT_MAX_CHANNELS; ch++) {
        v->ch[ch].in_win = (float*)malloc((size_t)v->in_win_cap * sizeof(float));
        v->ch[ch].residual = (float*)malloc((size_t)wlen * sizeof(float));
        v->ch[ch].stretch = (float*)malloc((size_t)v->stretch_cap * sizeof(float));
        if (v->ch[ch].in_win == NULL || v->ch[ch].residual == NULL || v->ch[ch].stretch == NULL) {
            for (uint32_t j = 0; j <= ch; j++) {
                free(v->ch[j].in_win);   v->ch[j].in_win = NULL;
                free(v->ch[j].residual); v->ch[j].residual = NULL;
                free(v->ch[j].stretch);  v->ch[j].stretch = NULL;
            }
            return false;
        }
    }

    // 复位全部状态
    for (uint32_t ch = 0; ch < VT_MAX_CHANNELS; ch++) {
        vt_reset_channel(v, ch);
    }
    return true;
}

static uint32_t vt_get_param_count(void* userdata) {
    (void)userdata;
    return 4;  // pitch_semitones + formant_shift + lpc_order + bypass
}

static const maidmic_param_t* vt_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 4) return &vt_params[index];
    return NULL;
}

static bool vt_set_param(void* userdata, const char* key, maidmic_param_t value) {
    vt_data_t* v = (vt_data_t*)userdata;

    if (strcmp(key, "pitch_semitones") == 0) {
        // 同时接受 FLOAT 与 INT
        float st;
        if (value.type == MAIDMIC_PARAM_INT) {
            st = (float)value.value.as_int;
        } else if (value.type == MAIDMIC_PARAM_FLOAT) {
            st = value.value.as_float;
        } else {
            return false;
        }
        if (st < -12.0f) st = -12.0f;
        if (st > 12.0f) st = 12.0f;
        v->pitch_st = st;
        // 只更新变调比率目标值，process 中逐样本平滑逼近（消除 zipper 噪声）
        float ratio = powf(2.0f, st / 12.0f);
        if (ratio < VT_RATIO_MIN) ratio = VT_RATIO_MIN;
        if (ratio > VT_RATIO_MAX) ratio = VT_RATIO_MAX;
        for (uint32_t ch = 0; ch < VT_MAX_CHANNELS; ch++) {
            maidmic_ramp_set_target(&v->ch[ch].ramp, ratio);
        }
        return true;
    }

    if (strcmp(key, "formant_shift") == 0) {
        // 同时接受 FLOAT 与 INT
        float st;
        if (value.type == MAIDMIC_PARAM_INT) {
            st = (float)value.value.as_int;
        } else if (value.type == MAIDMIC_PARAM_FLOAT) {
            st = value.value.as_float;
        } else {
            return false;
        }
        if (st < -12.0f) st = -12.0f;
        if (st > 12.0f) st = 12.0f;
        v->formant_st = st;
        return true;
    }

    if (strcmp(key, "lpc_order") == 0 && value.type == MAIDMIC_PARAM_INT) {
        int32_t order = value.value.as_int;
        if (order < VT_ORDER_MIN) order = VT_ORDER_MIN;
        if (order > VT_ORDER_MAX) order = VT_ORDER_MAX;
        if (order != v->lpc_order) {
            v->lpc_order = order;
            // 阶数影响合成 state 的滞后语义：清零全部声道 state，避免读入未初始化历史
            for (uint32_t ch = 0; ch < VT_MAX_CHANNELS; ch++) {
                memset(v->ch[ch].state, 0, sizeof(v->ch[ch].state));
            }
        }
        return true;
    }

    if (strcmp(key, "bypass") == 0 && value.type == MAIDMIC_PARAM_BOOL) {
        v->bypass = value.value.as_bool;
        return true;
    }

    return false;
}

static maidmic_param_t vt_get_param(void* userdata, const char* key) {
    vt_data_t* v = (vt_data_t*)userdata;
    maidmic_param_t param = {0};

    if (strcmp(key, "pitch_semitones") == 0) {
        param.key = "pitch_semitones";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = v->pitch_st;
        param.min = -12.0f;
        param.max = 12.0f;
        param.unit = "semitone";
    } else if (strcmp(key, "formant_shift") == 0) {
        param.key = "formant_shift";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = v->formant_st;
        param.min = -12.0f;
        param.max = 12.0f;
        param.unit = "semitone";
    } else if (strcmp(key, "lpc_order") == 0) {
        param.key = "lpc_order";
        param.type = MAIDMIC_PARAM_INT;
        param.value.as_int = v->lpc_order;
        param.min = 10.0f;
        param.max = 20.0f;
        param.unit = "";
    } else if (strcmp(key, "bypass") == 0) {
        param.key = "bypass";
        param.type = MAIDMIC_PARAM_BOOL;
        param.value.as_bool = v->bypass;
        param.min = 0.0f;
        param.max = 1.0f;
        param.unit = "";
    }

    return param;
}

// ============================================================
// 核心：单声道处理
// Core: per-channel processing
// ============================================================
// 原地处理（输入输出共用 scratch_in）：输入先复制进滑窗，之后不再读输入。
// 顺序：追加滑窗 → 窗满分析（残差激励）→ 清浊音判定 → 激励变调/原残差
//       → 共振峰偏移系数 → 全极点合成。热路径零堆分配。

static void vt_process_channel(vt_data_t* v, uint32_t ch, uint32_t fc) {
    vt_channel_t* c = &v->ch[ch];
    const float* in = v->scratch_in;   // 本块输入（本声道 float 样本）
    float* out = v->scratch_in;        // 本块输出（原地写回）
    float* exc = v->scratch_exc;       // 本块激励临时缓冲
    const uint32_t wlen = v->window_len;
    const uint32_t order = (uint32_t)v->lpc_order;

    // 该声道未配置（setup 声道数不匹配等异常）→ 直通（in == out，原地）
    if (c->in_win == NULL || wlen == 0) return;

    // ---- 1. 当前块追加进输入滑窗 ----
    // 不变量：块间 in_count < wlen，故追加后 in_count < wlen + VT_MAX_BLOCK = 容量
    memcpy(c->in_win + c->in_count, in, (size_t)fc * sizeof(float));
    c->in_count += fc;

    // ---- 2. 滑窗满窗长（sample_rate*0.02 样本）→ 分析一次，分析窗残差做激励 ----
    // 常规帧（fc <= wlen）每块至多分析一次；超大帧自动多次分析，保证滑窗有界
    while (c->in_count >= wlen) {
        const float* x = c->in_win + (c->in_count - wlen);  // 最近 wlen 个样本
        if (maidmic_lpc_analyze(x, wlen, order, c->a)) {
            maidmic_lpc_residual(x, wlen, c->a, order, c->residual);
            c->a_ready = true;
        } else {
            c->a_ready = false;  // 静音/病态 → 无可用激励，本块直通
        }
        // 丢弃已分析的 wlen 个样本，保留尾段（与下一块形成重叠分析窗）
        uint32_t keep = c->in_count - wlen;
        if (keep > 0) memmove(c->in_win, x + wlen, (size_t)keep * sizeof(float));
        c->in_count = keep;
    }

    // ---- 3. 尚无分析结果（启动缓冲期）→ 直通 ----
    if (!c->a_ready) return;  // in == out，无需拷贝

    // ---- 4. 清浊音判定（对当前块 float 样本） ----
    const bool voiced = maidmic_is_voiced_frame(in, fc);

    // ---- 5. 激励变调（仅浊音；无语音帧跳过变调，直接用原残差合成） ----
    if (voiced) {
        // 补充变速激励滑窗至可读满 frame_count：逐样本用平滑后的比率线性插值
        while (c->s_avail < fc) {
            float ratio = maidmic_ramp_next(&c->ramp);
            if (ratio < VT_RATIO_MIN) ratio = VT_RATIO_MIN;
            if (ratio > VT_RATIO_MAX) ratio = VT_RATIO_MAX;
            // 对残差环按 ratio 线性插值取样（读指针 c->pos 每声道独立、跨块持续）
            uint32_t p = (uint32_t)c->pos;
            float frac = c->pos - (float)p;
            uint32_t p1 = (p + 1u >= wlen) ? 0u : p + 1u;  // 环回：残差窗末尾循环填充
            float s0 = c->residual[p];
            float s1 = c->residual[p1];
            c->stretch[(c->sread + c->s_avail) % v->stretch_cap] = s0 + (s1 - s0) * frac;
            c->s_avail++;
            // 推进读指针；越过窗尾时循环回绕
            c->pos += ratio;
            while (c->pos >= (float)wlen) c->pos -= (float)wlen;
        }
        // 从变速激励滑窗读满 frame_count 个样本，作为本块"变速激励"
        for (uint32_t i = 0; i < fc; i++) {
            exc[i] = c->stretch[(c->sread + i) % v->stretch_cap];
        }
        c->sread = (c->sread + fc) % v->stretch_cap;
        c->s_avail -= fc;  // 滑窗未消费部分（此处恒为 0）保留到下一块
    } else {
        // 无语音回退：激励不变调，直接顺序读原残差环合成
        uint32_t p = (uint32_t)c->pos;
        for (uint32_t i = 0; i < fc; i++) {
            exc[i] = c->residual[(p + i) % wlen];
        }
        c->pos += (float)fc;
        while (c->pos >= (float)wlen) c->pos -= (float)wlen;
        // 失效变速滑窗中陈旧的已变调激励（避免混入后续浊音块）
        c->s_avail = 0;
        c->sread = 0;
    }

    // ---- 6. 共振峰偏移（谱包络频率伸缩） ----
    if (!maidmic_lpc_find_formant_shifts(c->a, order, v->formant_st, v->sample_rate, c->a_shifted)) {
        // 病态兜底：使用未偏移系数
        memcpy(c->a_shifted, c->a, (size_t)(order + 1u) * sizeof(float));
    }

    // ---- 7. 全极点合成（state 跨块保持，保证块间输出连续） ----
    maidmic_lpc_synthesize(exc, fc, c->a_shifted, order, out, c->state);
}

// ============================================================
// 核心：音频处理
// Core: audio processing
// ============================================================
// 支持 F32 / S16 两种格式；每声道独立处理（交错索引取声道）。
// 原地（input == output）与异地均安全；热路径内无 malloc。

static bool vt_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    vt_data_t* v = (vt_data_t*)userdata;
    if (input == NULL || output == NULL) return false;

    // ---- 快路径：旁路或两参数均 0 → 直通复制（支持原地与异地） ----
    if (v->bypass || (v->pitch_st == 0.0f && v->formant_st == 0.0f)) {
        vt_copy(input, output);
        return true;
    }

    // ---- 格式 / 配置防御：不支持的输入一律直通，不阻塞处理链 ----
    if (input->meta.format != MAIDMIC_SAMPLE_F32 && input->meta.format != MAIDMIC_SAMPLE_S16) {
        vt_copy(input, output);
        return true;
    }
    if (input->data == NULL || output->data == NULL) return false;

    const uint32_t fc = input->meta.frame_count;
    const uint16_t chs = input->meta.channels;

    // 空帧 / 声道超限（仅立体声）/ 帧长超出内部缓冲容量 → 直通
    if (fc == 0 || chs == 0 || chs > VT_MAX_CHANNELS || fc > VT_MAX_BLOCK) {
        vt_copy(input, output);
        return true;
    }
    // 尚未 setup（缓冲未分配）→ 直通
    if (v->window_len == 0 || v->ch[0].in_win == NULL) {
        vt_copy(input, output);
        return true;
    }

    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        // 32-bit float（DSP 内部推荐格式）
        const float* src = (const float*)input->data;
        float* dst = (float*)output->data;
        for (uint32_t ch = 0; ch < (uint32_t)chs; ch++) {
            // 交错 → 连续（提取本声道）
            for (uint32_t i = 0; i < fc; i++) v->scratch_in[i] = src[i * chs + ch];
            vt_process_channel(v, ch, fc);
            // 连续 → 交错（写回本声道；各声道只写自己的交错位置，原地安全）
            for (uint32_t i = 0; i < fc; i++) dst[i * chs + ch] = v->scratch_in[i];
        }
    } else {
        // 16-bit 整数（Android 默认格式）：/32768 转 float，处理后 *32767 钳位转回
        const int16_t* src = (const int16_t*)input->data;
        int16_t* dst = (int16_t*)output->data;
        for (uint32_t ch = 0; ch < (uint32_t)chs; ch++) {
            for (uint32_t i = 0; i < fc; i++) {
                v->scratch_in[i] = (float)src[i * chs + ch] * VT_S16_SCALE_IN;
            }
            vt_process_channel(v, ch, fc);
            for (uint32_t i = 0; i < fc; i++) {
                float s = v->scratch_in[i] * VT_S16_SCALE_OUT;
                if (s > 32767.0f) s = 32767.0f;
                if (s < -32768.0f) s = -32768.0f;
                dst[i * chs + ch] = (int16_t)s;
            }
        }
    }

    output->meta = input->meta;
    return true;
}

static void vt_reset(void* userdata) {
    vt_data_t* v = (vt_data_t*)userdata;
    for (uint32_t ch = 0; ch < VT_MAX_CHANNELS; ch++) {
        vt_reset_channel(v, ch);
    }
}

// ============================================================
// 模块描述
// Module descriptor
// ============================================================

static const maidmic_module_vtable_t vt_vtable = {
    .create = vt_create,
    .destroy = vt_destroy,
    .setup = vt_setup,
    .get_param_count = vt_get_param_count,
    .get_param_info = vt_get_param_info,
    .set_param = vt_set_param,
    .get_param = vt_get_param,
    .process = vt_process,
    .reset = vt_reset,
};

// 模块 ID 15：module.h 中尚未定义宏（id 15 字面量，与 ECHO=14 相邻），
// 注册时由后续集成任务统一登记。
// Module ID 15 (literal; no macro in module.h yet; registration handled by a later task)
const maidmic_module_t maidmic_module_voice_transform = {
    .id = 15,
    .name = "Voice Transform",
    .description = "LPC source-filter voice transformation: pitch shift + formant shift (LPC 源-滤波器变声)",
    .author = "MaidMic Team",
    .version = 1,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_STEREO | MAIDMIC_CAP_NON_REALTIME,
    .vtable = &vt_vtable,
};
