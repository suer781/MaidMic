// maidmic-engine/src/voice/autotune.c
// MaidMic 引擎唱歌音高修正模块（AutoTune）
// MaidMic Engine Singing Pitch-Correction Module (AutoTune)
//
// 把演唱音高修正到最近的音阶音（半音阶 / 大调 / 小调）：
//   autotune_enabled — 总开关（默认关，需显式开启）
//   autotune_scale   — 音阶：0=半音阶 / 1=大调 / 2=小调
//   autotune_retune  — 修正强度 0~1：1=完全钉住目标音，<1 保留部分原始偏差（自然感）
//   autotune_speed   — 修正平滑速度 0.01~1：越大收敛越快（越"机械"）
//   bypass           — 旁路开关
//
// 处理流程（非实时，允许模块内缓冲；热路径零堆分配）：
//   每声道独立输入滑窗（30ms）累积 → 窗满时 maidmic_detect_pitch 检基频 f0
//   → 量化到音阶得目标音 ftarget → 实际修正半音 = 12*log2(ftarget/f0)*retune
//   → 目标比率 = 2^(实际修正/12)，一阶平滑（speed 越大收敛越快）
//   → 跨块连续历史环线性插值重采样（读位置绝对、跨块持续；升调回跳/降调前跳
//     处线性交叉淡化拼接），输出与输入等长，消除旧实现"块尾回绕块首"的断裂。
//   非浊音（未检出基频）不更新目标比率，保持上一比率。
//
// 量化公式：
//   半音阶： s = round(12*log2(f0/440))，ftarget = 440 * 2^(s/12)
//   大调：   s 先取整半音，再把半音位置映射到最近音级 {0,2,4,5,7,9,11}
//   小调：   同上，音级 {0,2,3,5,7,8,10}
//   实际修正半音 = 12*log2(ftarget/f0) * retune
//   目标比率     = 2^(实际修正/12) = (ftarget/f0)^retune
//   自检：445Hz 输入、retune=1 → 半音阶 s=round(12*log2(445/440))=0，
//         ftarget=440，比率=(440/445)^1≈0.9888 → 输出基频≈440Hz ✓

#include "maidmic/module.h"
#include "maidmic/pitch_detector.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

// ============================================================
// 常量
// Constants
// ============================================================

#define AT_MAX_CHANNELS 2u      // 声道状态上限（立体声）
#define AT_MAX_BLOCK    4096u   // 单声道最大块长（超出则直通，保证滑窗有界、热路径零分配）
#define AT_WIN_MIN      64u     // 检测窗长下限（pitch_detector 只需 n>=2；64 足够稳健）
#define AT_WIN_MAX      8192u   // 检测窗长上限（= pitch_detector.c 的 PD_MAX_WINDOW）
#define AT_RATIO_MIN    0.5f    // 变调比率钳位下限（对应 -12 半音）
#define AT_RATIO_MAX    2.0f    // 变调比率钳位上限（对应 +12 半音）
#define AT_S16_SCALE_IN  (1.0f / 32768.0f)  // S16 → float 归一化系数
#define AT_S16_SCALE_OUT 32767.0f           // float → S16 归一化系数

// ---- 跨块连续重采样（历史环 + 拼接交叉淡化）----
// 旧实现"块内线性插值 + 块尾回绕块首"：读指针按块循环，块边界波形断裂（"卡卡"）。
// 新实现参考 src/dsp/pitch.c 的 hist/read_pos 方案：每声道维护历史环，读位置绝对
// 跨块持续；升调读头逼近写头时回跳、降调读头落后过多时前跳，拼接处用线性交叉
// 淡化过渡，消除块边界与拼接点的硬断裂。
#define AT_HIST_CAP    16384u   // 历史环容量（2 的幂）
#define AT_HIST_MASK   (AT_HIST_CAP - 1u)
#define AT_DELAY_INIT  2048u    // 初始读延迟（样本，≈43ms @48k；为插值提供前瞻）
#define AT_MARGIN      512u     // 读头与写头最小安全间距（样本）
#define AT_MAX_LEAD    8192u    // 读头落后写头超过此值 → 前跳拼接（降调）
#define AT_BACK_JUMP   4096u    // 升调回跳量（样本）
#define AT_FWD_JUMP    4096u    // 降调前跳量（样本）
#define AT_FADE        256u    // 拼接交叉淡化窗口（样本，≈5ms @48k）

// ============================================================
// 模块实例数据
// Module instance data
// ============================================================

typedef struct {
    // ---- 每声道滑窗 / 变速输出状态（按 setup 的 channels 使用，上限 2）----
    float* in_win;       // 输入滑窗（线性缓冲，容量 = window_len + AT_MAX_BLOCK）
    uint32_t in_count;   // 滑窗内有效样本数（恒 < window_len）
    float* stretch;      // 变速输出滑窗（环，容量 = window_len + AT_MAX_BLOCK）
    uint32_t sread;      // 变速滑窗读位置
    uint32_t s_avail;    // 变速滑窗可读样本数（未消费部分保留到下一块）
    float pos;           // （保留字段，兼容旧结构；新实现不再使用）
    float ratio;         // 当前（平滑后）变调比率
    float ratio_target;  // 目标比率（量化 + retune 后，仅浊音帧更新）

    // ---- 跨块连续重采样状态（历史环）----
    float* hist;         // 输入历史环 [AT_HIST_CAP]
    uint64_t hist_wpos;  // 已写入样本数（绝对）
    float rpos;          // 连续读位置（绝对，跨块持续）
    float fade_old;      // 淡化期间旧轨迹读位置
    float fade_new;      // 淡化期间新轨迹读位置
    uint32_t fade_left;  // 淡化剩余样本数（0 = 未在淡化）
} at_channel_t;

typedef struct {
    // 用户参数
    bool enabled;        // autotune_enabled 总开关
    int32_t scale;       // 音阶：0 半音 / 1 大调 / 2 小调
    float retune;        // 修正强度 0~1
    float speed;         // 一阶平滑速度 0.01~1
    bool bypass;         // 旁路开关

    uint32_t sample_rate;
    uint16_t channels;
    uint32_t window_len;   // 检测窗长 = round(sample_rate * 0.03)（30ms）
    uint32_t in_win_cap;   // 输入滑窗容量 = window_len + AT_MAX_BLOCK
    uint32_t stretch_cap;  // 变速滑窗容量 = window_len + AT_MAX_BLOCK

    // 每声道状态（上限 2）
    at_channel_t ch[AT_MAX_CHANNELS];

    // 共享块级临时缓冲（逐声道顺序复用；热路径零堆分配）
    float scratch[AT_MAX_BLOCK];  // 本声道 float 块（输入提取 / 输出写回，原地）
} at_data_t;

// ============================================================
// 参数定义（供 UI 使用）
// Parameter definitions (for UI use)
// ============================================================

static const maidmic_param_t at_params[] = {
    {
        .key = "autotune_enabled",
        .type = MAIDMIC_PARAM_BOOL,
        .value.as_bool = false,
        .min = 0.0f,
        .max = 1.0f,
        .unit = "",
    },
    {
        .key = "autotune_scale",
        .type = MAIDMIC_PARAM_INT,
        .value.as_int = 0,
        .min = 0.0f,  // 0=半音阶
        .max = 2.0f,  // 1=大调 2=小调
        .unit = "",
    },
    {
        .key = "autotune_retune",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 0.8f,
        .min = 0.0f,
        .max = 1.0f,
        .unit = "",
    },
    {
        .key = "autotune_speed",
        .type = MAIDMIC_PARAM_FLOAT,
        .value.as_float = 0.2f,
        .min = 0.01f,
        .max = 1.0f,
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
static void at_copy(const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    if (output->data != input->data && input->data != NULL && output->data != NULL) {
        memcpy(output->data, input->data, input->data_bytes);
    }
    output->meta = input->meta;
}

// 计算目标变调比率：f0 → 量化到音阶 → ftarget → 实际修正半音 → 比率
// 公式（详见文件头注释）：
//   半音阶  s = round(12*log2(f0/440))，ftarget = 440*2^(s/12)
//   大/小调 s 先取整半音，再映射到最近音级（大 {0,2,4,5,7,9,11} / 小 {0,2,3,5,7,8,10}）
//   实际修正半音 = 12*log2(ftarget/f0) * retune
//   目标比率     = 2^(实际修正/12) = (ftarget/f0)^retune
static float at_compute_target_ratio(float f0, int32_t scale, float retune) {
    if (!(f0 > 0.0f)) return 1.0f;

    // 1) 取整到最近半音（相对 A4=440Hz 的音分数）
    float s = roundf(12.0f * log2f(f0 / 440.0f));

    // 2) 大调 / 小调：把半音位置映射到最近音级（音级表覆盖一个八度内 12 个半音）
    if (scale == 1 || scale == 2) {
        static const int8_t major_deg[7] = { 0, 2, 4, 5, 7, 9, 11 };
        static const int8_t minor_deg[7] = { 0, 2, 3, 5, 7, 8, 10 };
        const int8_t* deg = (scale == 1) ? major_deg : minor_deg;

        int32_t si = (int32_t)s;
        int32_t oct = si / 12;             // 八度（C 整除向零截断）
        int32_t nn = si - oct * 12;        // 半音在八度内的位置（可能为负）
        if (nn < 0) { nn += 12; oct -= 1; } // 归一化到 [0,12)

        // 找距离最近的本调音级（并列取先出现的较低音级）
        int best_k = 0;
        int best_d = 12;
        for (int k = 0; k < 7; k++) {
            int d = (int)deg[k] - nn;
            if (d < 0) d = -d;
            if (d < best_d) { best_d = d; best_k = k; }
        }
        s = (float)(oct * 12 + (int)deg[best_k]);
    }

    // 3) 目标音 → 实际修正半音（retune 削弱修正量）→ 目标比率
    const float ftarget = 440.0f * powf(2.0f, s / 12.0f);
    const float corr_st = 12.0f * log2f(ftarget / f0) * retune;
    return powf(2.0f, corr_st / 12.0f);
}

// 复位单个声道的全部内部状态（滑窗清空、读指针归零、比率回 1.0）
static void at_reset_channel(at_data_t* a, uint32_t ch) {
    at_channel_t* c = &a->ch[ch];
    c->in_count = 0;
    c->sread = 0;
    c->s_avail = 0;
    c->pos = 0.0f;
    c->ratio = 1.0f;        // 比率 1.0 → 重采样退化为直通（身份映射）
    c->ratio_target = 1.0f;
    // 历史环复位：预置 AT_DELAY_INIT 样本零（前导静音），写头从延迟位置开始
    if (c->hist) memset(c->hist, 0, AT_HIST_CAP * sizeof(float));
    c->hist_wpos = AT_DELAY_INIT;
    c->rpos = 0.0f;
    c->fade_old = 0.0f;
    c->fade_new = 0.0f;
    c->fade_left = 0;
}

// ============================================================
// vtable 实现
// vtable implementation
// ============================================================

static void* at_create(void) {
    at_data_t* a = (at_data_t*)calloc(1, sizeof(at_data_t));
    if (!a) return NULL;
    a->enabled = false;
    a->scale = 0;
    a->retune = 0.8f;
    a->speed = 0.2f;
    a->bypass = false;
    for (uint32_t ch = 0; ch < AT_MAX_CHANNELS; ch++) {
        a->ch[ch].ratio = 1.0f;
        a->ch[ch].ratio_target = 1.0f;
    }
    return a;
}

static void at_destroy(void* userdata) {
    at_data_t* a = (at_data_t*)userdata;
    if (!a) return;
    for (uint32_t ch = 0; ch < AT_MAX_CHANNELS; ch++) {
        free(a->ch[ch].in_win);
        free(a->ch[ch].stretch);
        free(a->ch[ch].hist);
    }
    free(a);
}

static bool at_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    at_data_t* a = (at_data_t*)userdata;
    if (sample_rate == 0 || channels == 0 || channels > AT_MAX_CHANNELS) return false;

    // 释放旧缓冲（采样率/声道变化时重建）
    for (uint32_t ch = 0; ch < AT_MAX_CHANNELS; ch++) {
        free(a->ch[ch].in_win);   a->ch[ch].in_win = NULL;
        free(a->ch[ch].stretch);  a->ch[ch].stretch = NULL;
    }

    // 检测窗长 = 30ms（sample_rate*0.03），钳制在 pitch_detector 支持范围内
    uint32_t wlen = (uint32_t)((float)sample_rate * 0.03f + 0.5f);
    if (wlen < AT_WIN_MIN) wlen = AT_WIN_MIN;
    if (wlen > AT_WIN_MAX) wlen = AT_WIN_MAX;

    a->sample_rate = sample_rate;
    a->channels = channels;
    a->window_len = wlen;
    a->in_win_cap = wlen + AT_MAX_BLOCK;    // 容量 = 窗长 + 当前块长上限
    a->stretch_cap = wlen + AT_MAX_BLOCK;

    // 为每个声道分配缓冲（上限 2）；任一失败则整体回滚并返回 false
    for (uint32_t ch = 0; ch < AT_MAX_CHANNELS; ch++) {
        a->ch[ch].in_win = (float*)malloc((size_t)a->in_win_cap * sizeof(float));
        a->ch[ch].stretch = (float*)malloc((size_t)a->stretch_cap * sizeof(float));
        a->ch[ch].hist = (float*)calloc(AT_HIST_CAP, sizeof(float));
        if (a->ch[ch].in_win == NULL || a->ch[ch].stretch == NULL || a->ch[ch].hist == NULL) {
            for (uint32_t j = 0; j <= ch; j++) {
                free(a->ch[j].in_win);   a->ch[j].in_win = NULL;
                free(a->ch[j].stretch);  a->ch[j].stretch = NULL;
                free(a->ch[j].hist);     a->ch[j].hist = NULL;
            }
            return false;
        }
    }

    // 复位全部状态
    for (uint32_t ch = 0; ch < AT_MAX_CHANNELS; ch++) {
        at_reset_channel(a, ch);
    }
    return true;
}

static uint32_t at_get_param_count(void* userdata) {
    (void)userdata;
    return 5;  // autotune_enabled + autotune_scale + autotune_retune + autotune_speed + bypass
}

static const maidmic_param_t* at_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 5) return &at_params[index];
    return NULL;
}

static bool at_set_param(void* userdata, const char* key, maidmic_param_t value) {
    at_data_t* a = (at_data_t*)userdata;

    if (strcmp(key, "autotune_enabled") == 0 && value.type == MAIDMIC_PARAM_BOOL) {
        a->enabled = value.value.as_bool;
        return true;
    }

    if (strcmp(key, "autotune_scale") == 0 && value.type == MAIDMIC_PARAM_INT) {
        int32_t sc = value.value.as_int;
        if (sc < 0) sc = 0;
        if (sc > 2) sc = 2;
        a->scale = sc;
        return true;
    }

    if (strcmp(key, "autotune_retune") == 0) {
        // 同时接受 FLOAT 与 INT
        float r;
        if (value.type == MAIDMIC_PARAM_FLOAT) {
            r = value.value.as_float;
        } else if (value.type == MAIDMIC_PARAM_INT) {
            r = (float)value.value.as_int;
        } else {
            return false;
        }
        if (r < 0.0f) r = 0.0f;
        if (r > 1.0f) r = 1.0f;
        a->retune = r;
        return true;
    }

    if (strcmp(key, "autotune_speed") == 0) {
        // 同时接受 FLOAT 与 INT
        float sp;
        if (value.type == MAIDMIC_PARAM_FLOAT) {
            sp = value.value.as_float;
        } else if (value.type == MAIDMIC_PARAM_INT) {
            sp = (float)value.value.as_int;
        } else {
            return false;
        }
        if (sp < 0.01f) sp = 0.01f;
        if (sp > 1.0f) sp = 1.0f;
        a->speed = sp;
        return true;
    }

    if (strcmp(key, "bypass") == 0 && value.type == MAIDMIC_PARAM_BOOL) {
        a->bypass = value.value.as_bool;
        return true;
    }

    return false;
}

static maidmic_param_t at_get_param(void* userdata, const char* key) {
    at_data_t* a = (at_data_t*)userdata;
    maidmic_param_t param = {0};

    if (strcmp(key, "autotune_enabled") == 0) {
        param.key = "autotune_enabled";
        param.type = MAIDMIC_PARAM_BOOL;
        param.value.as_bool = a->enabled;
        param.min = 0.0f;
        param.max = 1.0f;
    } else if (strcmp(key, "autotune_scale") == 0) {
        param.key = "autotune_scale";
        param.type = MAIDMIC_PARAM_INT;
        param.value.as_int = a->scale;
        param.min = 0.0f;
        param.max = 2.0f;
    } else if (strcmp(key, "autotune_retune") == 0) {
        param.key = "autotune_retune";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = a->retune;
        param.min = 0.0f;
        param.max = 1.0f;
    } else if (strcmp(key, "autotune_speed") == 0) {
        param.key = "autotune_speed";
        param.type = MAIDMIC_PARAM_FLOAT;
        param.value.as_float = a->speed;
        param.min = 0.01f;
        param.max = 1.0f;
    } else if (strcmp(key, "bypass") == 0) {
        param.key = "bypass";
        param.type = MAIDMIC_PARAM_BOOL;
        param.value.as_bool = a->bypass;
        param.min = 0.0f;
        param.max = 1.0f;
    }

    return param;
}

// ============================================================
// 核心：单声道处理
// Core: per-channel processing
// ============================================================
// 原地处理（输入输出共用 scratch）：输入先复制进滑窗并追加到历史环，之后只在
// 变速生成阶段读取历史环（不再读本块），生成完毕才把变速输出写回 scratch，故写
// 回安全。重采样采用跨块连续历史环（读位置绝对、跨块持续），升调回跳/降调前跳
// 处用线性交叉淡化拼接，消除旧实现"块尾回绕块首"的块边界波形断裂。
// 顺序：追加滑窗 → 窗满检基频并量化 → 一阶平滑比率 → 历史环连续重采样等长输出。
// 热路径零堆分配。

// 历史环线性插值读取（pos 为绝对位置，自动折绕环容量）
static inline float at_hist_interp(const float* hist, float pos) {
    uint64_t i0 = (uint64_t)pos;
    float frac = pos - (float)i0;
    float s0 = hist[i0 & AT_HIST_MASK];
    float s1 = hist[(i0 + 1) & AT_HIST_MASK];
    return s0 + (s1 - s0) * frac;
}

static void at_process_channel(at_data_t* a, uint32_t ch, uint32_t fc) {
    at_channel_t* c = &a->ch[ch];
    const float* in = a->scratch;  // 本块输入（本声道 float 样本）
    float* out = a->scratch;       // 本块输出（原地写回）
    const uint32_t wlen = a->window_len;

    // 该声道未配置（setup 声道数不匹配等异常）→ 直通（in == out，原地）
    if (c->in_win == NULL || wlen == 0 || c->hist == NULL) return;

    // ---- 1. 当前块追加进输入滑窗 ----
    // 不变量：块间 in_count < wlen，故追加后 in_count < wlen + AT_MAX_BLOCK = 容量
    memcpy(c->in_win + c->in_count, in, (size_t)fc * sizeof(float));
    c->in_count += fc;

    // ---- 2. 滑窗满窗长（sample_rate*0.03 样本）→ 检基频并量化 ----
    // 常规帧（fc <= wlen）每块至多分析一次；超大帧自动多次分析，保证滑窗有界
    bool detected = false;
    while (c->in_count >= wlen) {
        const float* x = c->in_win + (c->in_count - wlen);  // 最近 wlen 个样本
        bool voiced = false;
        const float f0 = maidmic_detect_pitch(x, wlen, a->sample_rate, &voiced);
        if (voiced && f0 > 0.0f) {
            // 量化 + retune → 更新目标比率；非浊音保持上一比率（不更新）
            c->ratio_target = at_compute_target_ratio(f0, a->scale, a->retune);
            detected = true;
        }
        // 丢弃已分析的 wlen 个样本，保留尾段（与下一块形成重叠分析窗）
        const uint32_t keep = c->in_count - wlen;
        if (keep > 0) memmove(c->in_win, x + wlen, (size_t)keep * sizeof(float));
        c->in_count = keep;
    }

    // ---- 3. 一阶平滑：ratio += speed * (ratio_target - ratio) ----
    // speed 越大收敛越快；仅在本次检出浊音时推进，非浊音保持上一比率（比率冻结）
    if (detected) {
        c->ratio += a->speed * (c->ratio_target - c->ratio);
    }
    float ratio = c->ratio;
    if (ratio < AT_RATIO_MIN) ratio = AT_RATIO_MIN;
    if (ratio > AT_RATIO_MAX) ratio = AT_RATIO_MAX;

    // ---- 4. 跨块连续重采样：历史环 + 拼接交叉淡化（输出与输入等长）----
    // 4.1 本块输入追加进历史环
    for (uint32_t i = 0; i < fc; i++) {
        c->hist[(size_t)((c->hist_wpos + i) & AT_HIST_MASK)] = in[i];
    }
    c->hist_wpos += fc;

    for (uint32_t i = 0; i < fc; i++) {
        // 拼接决策（淡化期间不发起新拼接）
        if (c->fade_left == 0) {
            const float delay = (float)c->hist_wpos - c->rpos;
            if (ratio >= 1.0f &&
                delay < (float)AT_MARGIN + ratio * (float)AT_FADE + (float)AT_FADE) {
                // 升调：读头逼近写头 → 回跳拼接（重读更旧历史）
                float new_pos = c->rpos - (float)AT_BACK_JUMP;
                if (new_pos < 0.0f) new_pos = 0.0f;
                c->fade_old = c->rpos;
                c->fade_new = new_pos;
                c->fade_left = AT_FADE;
            } else if (ratio < 1.0f && delay > (float)AT_MAX_LEAD) {
                // 降调：读头落后过多 → 前跳拼接（跳读更新历史）
                float new_pos = c->rpos + (float)AT_FWD_JUMP;
                const float max_pos = (float)c->hist_wpos - (float)AT_MARGIN;
                if (new_pos > max_pos) new_pos = max_pos;
                if (new_pos > c->rpos) {
                    c->fade_old = c->rpos;
                    c->fade_new = new_pos;
                    c->fade_left = AT_FADE;
                }
            }
        }

        // 读取输出样本：淡化期间混叠新旧轨迹，否则沿 rpos 连续读取
        float s;
        if (c->fade_left > 0) {
            const float s_old = at_hist_interp(c->hist, c->fade_old);
            c->fade_old += ratio;
            const float s_new = at_hist_interp(c->hist, c->fade_new);
            c->fade_new += ratio;
            const float t = 1.0f - (float)c->fade_left / (float)AT_FADE;
            s = s_old * (1.0f - t) + s_new * t;   // 线性交叉淡化
            c->fade_left--;
            if (c->fade_left == 0) {
                c->rpos = c->fade_new;
            }
        } else {
            s = at_hist_interp(c->hist, c->rpos);
            c->rpos += ratio;
        }

        out[i] = s;
    }
}

// ============================================================
// 核心：音频处理
// Core: audio processing
// ============================================================
// 支持 F32 / S16 两种格式；每声道独立处理（交错索引取声道）。
// 原地（input == output）与异地均安全；热路径内无 malloc。

static bool at_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    at_data_t* a = (at_data_t*)userdata;
    if (input == NULL || output == NULL) return false;

    // ---- 快路径：旁路或未启用 → 直通复制（支持原地与异地）----
    if (a->bypass || !a->enabled) {
        at_copy(input, output);
        return true;
    }

    // ---- 格式 / 配置防御：不支持的输入一律直通，不阻塞处理链 ----
    if (input->meta.format != MAIDMIC_SAMPLE_F32 && input->meta.format != MAIDMIC_SAMPLE_S16) {
        at_copy(input, output);
        return true;
    }
    if (input->data == NULL || output->data == NULL) return false;

    const uint32_t fc = input->meta.frame_count;
    const uint16_t chs = input->meta.channels;

    // 空帧 / 声道超限（仅立体声）/ 帧长超出内部缓冲容量 → 直通
    if (fc == 0 || chs == 0 || chs > AT_MAX_CHANNELS || fc > AT_MAX_BLOCK) {
        at_copy(input, output);
        return true;
    }
    // 尚未 setup（缓冲未分配）→ 直通
    if (a->window_len == 0 || a->ch[0].in_win == NULL) {
        at_copy(input, output);
        return true;
    }

    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        // 32-bit float（DSP 内部推荐格式）
        const float* src = (const float*)input->data;
        float* dst = (float*)output->data;
        for (uint32_t ch = 0; ch < (uint32_t)chs; ch++) {
            // 交错 → 连续（提取本声道）
            for (uint32_t i = 0; i < fc; i++) a->scratch[i] = src[i * chs + ch];
            at_process_channel(a, ch, fc);
            // 连续 → 交错（写回本声道；各声道只写自己的交错位置，原地安全）
            for (uint32_t i = 0; i < fc; i++) dst[i * chs + ch] = a->scratch[i];
        }
    } else {
        // 16-bit 整数（Android 默认格式）：/32768 转 float，处理后 *32767 钳位转回
        const int16_t* src = (const int16_t*)input->data;
        int16_t* dst = (int16_t*)output->data;
        for (uint32_t ch = 0; ch < (uint32_t)chs; ch++) {
            for (uint32_t i = 0; i < fc; i++) {
                a->scratch[i] = (float)src[i * chs + ch] * AT_S16_SCALE_IN;
            }
            at_process_channel(a, ch, fc);
            for (uint32_t i = 0; i < fc; i++) {
                float s = a->scratch[i] * AT_S16_SCALE_OUT;
                if (s > 32767.0f) s = 32767.0f;
                if (s < -32768.0f) s = -32768.0f;
                dst[i * chs + ch] = (int16_t)s;
            }
        }
    }

    output->meta = input->meta;
    return true;
}

static void at_reset(void* userdata) {
    at_data_t* a = (at_data_t*)userdata;
    for (uint32_t ch = 0; ch < AT_MAX_CHANNELS; ch++) {
        at_reset_channel(a, ch);
    }
}

// ============================================================
// 模块描述
// Module descriptor
// ============================================================

static const maidmic_module_vtable_t at_vtable = {
    .create = at_create,
    .destroy = at_destroy,
    .setup = at_setup,
    .get_param_count = at_get_param_count,
    .get_param_info = at_get_param_info,
    .set_param = at_set_param,
    .get_param = at_get_param,
    .process = at_process,
    .reset = at_reset,
};

// 模块 ID 18：module.h 中尚未定义宏（id 18 字面量，与 PRESENCE=17 相邻），
// 注册时由后续集成任务统一登记。
// Module ID 18 (literal; no macro in module.h yet; registration handled by a later task)
const maidmic_module_t maidmic_module_autotune = {
    .id = 18,
    .name = "AutoTune",
    .description = "Singing pitch correction to scale (唱歌音高修正到音阶)",
    .author = "MaidMic Team",
    .version = 1,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_STEREO | MAIDMIC_CAP_NON_REALTIME,
    .vtable = &at_vtable,
};
