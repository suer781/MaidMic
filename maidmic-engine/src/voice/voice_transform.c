// maidmic-engine/src/voice/voice_transform.c
// MaidMic 引擎 LPC 源-滤波器变声模块 v3
// MaidMic Engine LPC Source-Filter Voice Transformation Module (v3)
//
// 两级变声核心：
//
//   第一级 · 变调（TD-PSOLA，见 voice/psola.c）
//     基音同步重叠相加：窗长恒为 2*max(Ta,Ts)（不随变调比率缩放），
//     谐波间距改变而谱包络（共振峰）完整保留 → 音高变化、音色不变。
//     清音段自动切换恒等重构（Hann COLA 精确直通），无伪周期嗡声。
//
//   第二级 · 共振峰偏移（抽取域 OLA-LPC + 极点旋转 + 多相内插校正）
//     共振峰集中在 0~5kHz，直接在 48kHz 用 16 阶 LPC 无法分辨窄共振峰
//     （阶数需 ≥ fs/1000 量级）。故将信号 ×D 抽取到 ~16kHz（D = round(sr/16k)，
//     线性相位 FIR 抗混叠）再做 LPC 分析/极点旋转/再合成（低速率 25ms 帧、
//     75% 重叠、Hann² COLA），等效分辨率提高 D 倍。
//     处理结果以"校正信号"形式叠加回原信号：
//       y[m] = x[m−δ] + interp_D(OLA_low − x_low)[m]
//     即 低频带（0~0.47·sr_low）被移包络版本替换、高频带（擦音/齿音）
//     原样保留。interp 为与抽取 FIR 同系数的多相内插器（线性相位，
//     延迟精确已知，保证校正与原信号时间对齐）。
//     每帧激励能量归一（g1）+ 输出响度归一（g2，快起慢落）消除泵动。
//
// 参数（与旧版同名，JNI/Kotlin 调用方零改动）：
//   pitch_semitones — 变调半音 -12~+12（PSOLA，共振峰保持）
//   formant_shift   — 共振峰偏移半音 -12~+12（极点旋转，独立于音高）
//   lpc_order       — LPC 阶数 10~20（在抽取域生效，默认 16）
//   bypass          — 旁路开关
//
// 热路径零堆分配；F32/S16、1~2 声道、任意块长（内部按 ≤4096 分块）。
// 两级均为流级延迟（本引擎面向录音后处理，延迟不影响工作流）。

#include "maidmic/module.h"
#include "maidmic/lpc.h"
#include "maidmic/psola.h"
#include "maidmic/pitch_detector.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

// ============================================================
// 常量
// Constants
// ============================================================

#define VT_MAX_CHANNELS 2u       // 声道状态上限（立体声）
#define VT_MAX_BLOCK    4096u    // 单次内部处理的最大块长（更大自动分块）
#define VT_RING_CAP     16384u   // 高速率环容量（2 的幂）
#define VT_RING_MASK    (VT_RING_CAP - 1u)
#define VT_LOW_CAP      8192u    // 低速率环容量（2 的幂）
#define VT_LOW_MASK     (VT_LOW_CAP - 1u)
#define VT_DET_WIN      1920u    // 基频检测窗（样本，≈40ms @48k）
#define VT_FIR_TAPS     67u      // 抽取/内插 FIR 长度（线性相位）
#define VT_FIR_DELAY    (VT_FIR_TAPS / 2u)
#define VT_S16_SCALE_IN  (1.0f / 32768.0f)
#define VT_S16_SCALE_OUT 32767.0f

// 低速率帧参数：帧长 ≈ 25ms，跳距 = W/4（75% 重叠），Hann² COLA 归一 = 2/3
#define VT_FT_MS_NUM    25
#define VT_FT_MS_DEN    1000
#define VT_G1_MAX       32.0f    // 激励归一增益上限
#define VT_G2_MAX       8.0f     // 输出响度归一增益上限
#define VT_K_STAB       0.9995f  // 反射系数钳位（稳定化）
#define VT_POLE_R_MAX   0.999    // 旋转后极点半径钳位
#define VT_ANGLE_MIN    0.01     // 极点角度钳位（rad）

// ============================================================
// 模块实例数据
// Module instance data
// ============================================================

typedef struct {
    // ---- 第一级：PSOLA 变调 ----
    mm_psola_t* ps;          // PSOLA 实例（每声道一个）
    float* det_buf;          // 基频检测历史环
    uint64_t det_wpos;       // 检测环写位置（绝对）

    // ---- 第二级：共振峰偏移 ----
    float* fring;            // 高速率输入流环（延迟读 + 抽取 FIR 输入）
    uint64_t fwpos;          // 高速率写位置（绝对）
    float* fir_hist;         // 抽取 FIR 历史环 [128]
    uint32_t fir_cnt;        // FIR 已输入高速率样本数
    float* xlow;             // 低速率原始信号环
    float* ola;              // 低速率 OLA 环（含移包络重建）
    uint64_t xlw;            // 低速率已抽取样本数（绝对）
    uint64_t anchor_l;       // 低速率下一帧锚点
    float* frame_x;          // 低速率帧 [ft_wl]
    float* frame_e;          // 激励残差 [ft_wl]
    float* frame_s;          // 合成输出 [ft_wl]
    float* a_cur;            // LPC 系数 [order+1]
    float* a_shift;          // 极点旋转后系数 [order+1]
    float g2;                // 输出响度归一增益（快起慢落）
    float c_cur;             // 共振峰伸缩因子（逐帧平滑）
} vt_channel_t;

typedef struct {
    // 用户参数
    float pitch_st;      // 变调半音 -12~+12
    float formant_st;    // 共振峰偏移半音 -12~+12
    int32_t lpc_order;   // LPC 分析阶数 10~20（抽取域）
    bool bypass;         // 旁路开关

    uint32_t sample_rate;
    uint16_t channels;
    uint32_t dec;        // 抽取因子 D = round(sr/16000)，≥1
    uint32_t ft_wl;      // 低速率帧长（≈25ms，4 对齐）
    uint32_t ft_hl;      // 帧跳距 = ft_wl/4
    uint32_t ft_delay;   // 共振峰级流延迟（高速率样本）
    float* ft_window;    // 低速率合成窗（周期 Hann，ft_wl 点）
    float* fir;          // 抽取/内插共用 FIR [VT_FIR_TAPS]

    vt_channel_t ch[VT_MAX_CHANNELS];

    // 共享块级临时缓冲（逐声道顺序复用；热路径零堆分配）
    float scratch_in[VT_MAX_BLOCK];   // 本声道输入/输出（原地）
    float scratch_mid[VT_MAX_BLOCK];  // 第一级输出暂存
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
// 复数运算与多项式求根（极点旋转用）
// Complex arithmetic & polynomial root finding
// ============================================================

typedef struct { double re, im; } vt_cpx;

static inline vt_cpx vt_cadd(vt_cpx a, vt_cpx b) { vt_cpx r = {a.re + b.re, a.im + b.im}; return r; }
static inline vt_cpx vt_csub(vt_cpx a, vt_cpx b) { vt_cpx r = {a.re - b.re, a.im - b.im}; return r; }
static inline vt_cpx vt_cmul(vt_cpx a, vt_cpx b) {
    vt_cpx r = {a.re * b.re - a.im * b.im, a.re * b.im + a.im * b.re};
    return r;
}
static inline vt_cpx vt_cdiv(vt_cpx a, vt_cpx b) {
    double d = b.re * b.re + b.im * b.im;
    if (d < 1e-300) { vt_cpx r = {0.0, 0.0}; return r; }
    vt_cpx r = {(a.re * b.re + a.im * b.im) / d, (a.im * b.re - a.re * b.im) / d};
    return r;
}

// 复多项式求值（Horner）：p[0] + p[1]·z + ... + p[n]·z^n
static vt_cpx vt_poly_eval(const vt_cpx* p, uint32_t n, vt_cpx z) {
    vt_cpx acc = p[n];
    for (int32_t i = (int32_t)n - 1; i >= 0; i--) {
        acc = vt_cadd(vt_cmul(acc, z), p[i]);
    }
    return acc;
}

// Durbin-Kerner（Weierstrass）同时求根，迭代至收敛或次数上限
static bool vt_poly_roots(const vt_cpx* p, uint32_t n, vt_cpx* roots) {
    // 初始根：等比复数分布（经典 Durbin 初始化，避免对称退化）
    const vt_cpx seed = {0.4, 0.9};
    for (uint32_t k = 0; k < n; k++) {
        vt_cpx z = {1.0, 0.0};
        for (uint32_t j = 0; j < k; j++) z = vt_cmul(z, seed);
        roots[k] = z;
    }

    for (uint32_t iter = 0; iter < 64; iter++) {
        double max_delta = 0.0;
        for (uint32_t k = 0; k < n; k++) {
            const vt_cpx pk = vt_poly_eval(p, n, roots[k]);
            vt_cpx denom = {1.0, 0.0};
            for (uint32_t j = 0; j < n; j++) {
                if (j == k) continue;
                denom = vt_cmul(denom, vt_csub(roots[k], roots[j]));
            }
            const vt_cpx delta = vt_cdiv(pk, denom);
            if (!isfinite(delta.re) || !isfinite(delta.im)) return false;
            roots[k] = vt_csub(roots[k], delta);
            const double mag = sqrt(delta.re * delta.re + delta.im * delta.im);
            if (mag > max_delta) max_delta = mag;
        }
        if (max_delta < 1e-10) break;
    }
    return true;
}

// ============================================================
// 系数稳定化（Schur-Cohn 步降 + 反射系数钳位 + 步升重建）
// Coefficient stabilization (step-down / clamp / step-up)
// ============================================================
// 极点旋转 + float 精度系数量化可能把聚簇极点推到单位圆之外，
// 全极点合成会指数爆炸。把系数转为反射系数 k[1..N]，钳位 |k| ≤ VT_K_STAB，
// 再重建系数 —— 数学上保证所有极点严格在单位圆内。
static bool vt_stabilize_coeffs(float* a, uint32_t order) {
    float k[MAIDMIC_LPC_MAX_ORDER + 1u];
    float tmp[MAIDMIC_LPC_MAX_ORDER + 1u];

    // ---- 步降：a[1..order] → k[1..order]（逆 Levinson）----
    for (uint32_t m = order; m >= 1; m--) {
        k[m] = a[m];
        if (fabsf(k[m]) >= 1.0f) {
            k[m] = (k[m] >= 0.0f) ? VT_K_STAB : -VT_K_STAB;
        }
        const float denom = 1.0f - k[m] * k[m];
        if (fabsf(denom) < 1e-9f) return false;
        for (uint32_t j = 1; j < m; j++) {
            tmp[j] = (a[j] + k[m] * a[m - j]) / denom;
        }
        for (uint32_t j = 1; j < m; j++) {
            a[j] = tmp[j];
        }
    }

    // ---- 反射系数钳位 ----
    for (uint32_t m = 1; m <= order; m++) {
        if (fabsf(k[m]) > VT_K_STAB) {
            k[m] = (k[m] >= 0.0f) ? VT_K_STAB : -VT_K_STAB;
        }
        if (!isfinite(k[m])) return false;
    }

    // ---- 步升：k[1..order] → a[1..order]（正 Levinson 更新）----
    for (uint32_t i = 1; i <= order; i++) {
        const float ki = k[i];
        for (uint32_t j = 1; j < i; j++) {
            tmp[j] = a[j] - ki * a[i - j];
        }
        a[i] = ki;
        for (uint32_t j = 1; j < i; j++) {
            a[j] = tmp[j];
        }
    }
    return true;
}

// ============================================================
// 极点旋转（Durbin-Kerner 求根 + 角度缩放 + 多项式重建）
// Pole rotation (Durbin-Kerner root finding + angle scaling)
// ============================================================
// A(z) = 1 - Σ_{k=1..N} a[k]·z^-k 的极点 = 多项式
//   q(z) = z^N - a[1]·z^(N-1) - ... - a[N]（升幂系数 [−aN,…,−a1, 1]）
// 的根（稳定 LPC 全部在单位圆内）。对每个根做 θ' = θ·c（半径不变 →
// 稳定性自动保持），再由新根集重建 q'(z) = Π (z - r_k)，a'[k] = -q'[N-k]。
// 全程 double 精度；调用方必须再做 vt_stabilize_coeffs 消除 float 量化误差。
static bool vt_rotate_formants(const float* a, uint32_t order, float c, float* a_out) {
    const uint32_t N = order;

    vt_cpx poly[MAIDMIC_LPC_MAX_ORDER + 1u];
    for (uint32_t j = 0; j < N; j++) {
        poly[j].re = -(double)a[N - j];
        poly[j].im = 0.0;
    }
    poly[N].re = 1.0;  // 最高次项（首一）
    poly[N].im = 0.0;

    vt_cpx roots[MAIDMIC_LPC_MAX_ORDER];
    if (!vt_poly_roots(poly, N, roots)) return false;

    for (uint32_t k = 0; k < N; k++) {
        double mag = sqrt(roots[k].re * roots[k].re + roots[k].im * roots[k].im);
        if (!isfinite(mag) || mag < 1e-6) continue;
        if (mag > VT_POLE_R_MAX) {
            roots[k].re *= VT_POLE_R_MAX / mag;
            roots[k].im *= VT_POLE_R_MAX / mag;
        }
        double theta = atan2(roots[k].im, roots[k].re);
        double theta2 = theta * (double)c;
        if (theta2 > M_PI - VT_ANGLE_MIN) theta2 = M_PI - VT_ANGLE_MIN;
        if (theta2 < -(M_PI - VT_ANGLE_MIN)) theta2 = -(M_PI - VT_ANGLE_MIN);
        roots[k].re = mag * cos(theta2);
        roots[k].im = mag * sin(theta2);
    }

    // 由根集重建首一多项式（升幂）：out = Π (z - r_k)
    vt_cpx out[MAIDMIC_LPC_MAX_ORDER + 1u];
    memset(out, 0, sizeof(out));
    out[0].re = 1.0;
    uint32_t deg = 0;
    for (uint32_t k = 0; k < N; k++) {
        // 乘 (z - r)：out_new[j] = out_old[j-1] - r·out_old[j]（降序原地更新）
        const vt_cpx rk = roots[k];
        for (uint32_t j = deg + 1u; ; j--) {
            const vt_cpx old_j = out[j];
            const vt_cpx prev = (j > 0u) ? out[j - 1u] : (vt_cpx){0.0, 0.0};
            out[j] = vt_csub(prev, vt_cmul(old_j, rk));
            if (j == 0u) break;
        }
        deg++;
    }

    for (uint32_t k = 1; k <= N; k++) {
        if (!isfinite(out[N - k].re)) return false;
        a_out[k] = (float)(-out[N - k].re);
    }
    return true;
}

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

// 复位单个声道的全部内部状态
static void vt_reset_channel(vt_data_t* v, uint32_t ch) {
    vt_channel_t* c = &v->ch[ch];
    c->det_wpos = 0;
    c->fwpos = 0;
    c->fir_cnt = 0;
    c->xlw = 0;
    c->anchor_l = 0;
    c->g2 = 1.0f;
    c->c_cur = 1.0f;
    if (c->ps) mm_psola_reset(c->ps);
    if (c->fring) memset(c->fring, 0, VT_RING_CAP * sizeof(float));
    if (c->fir_hist) memset(c->fir_hist, 0, 128 * sizeof(float));
    if (c->xlow) memset(c->xlow, 0, VT_LOW_CAP * sizeof(float));
    if (c->ola) memset(c->ola, 0, VT_LOW_CAP * sizeof(float));
    if (c->det_buf) memset(c->det_buf, 0, VT_DET_WIN * sizeof(float));
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
    return v;
}

static void vt_destroy(void* userdata) {
    vt_data_t* v = (vt_data_t*)userdata;
    if (!v) return;
    for (uint32_t ch = 0; ch < VT_MAX_CHANNELS; ch++) {
        vt_channel_t* c = &v->ch[ch];
        mm_psola_destroy(c->ps);
        free(c->det_buf);
        free(c->fring);
        free(c->fir_hist);
        free(c->xlow);
        free(c->ola);
        free(c->frame_x);
        free(c->frame_e);
        free(c->frame_s);
        free(c->a_cur);
        free(c->a_shift);
    }
    free(v->ft_window);
    free(v->fir);
    free(v);
}

static bool vt_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    vt_data_t* v = (vt_data_t*)userdata;
    if (sample_rate == 0 || channels == 0 || channels > VT_MAX_CHANNELS) return false;

    v->sample_rate = sample_rate;
    v->channels = channels;

    // 抽取因子：目标低速率 ~16kHz（≥24kHz 才抽取）
    uint32_t dec = 1u;
    if (sample_rate >= 24000u) {
        dec = (sample_rate + 8000u) / 16000u;
        if (dec < 1u) dec = 1u;
    }
    v->dec = dec;
    const uint32_t srl = sample_rate / dec;

    // 低速率帧长 ≈ 25ms，4 对齐
    uint32_t wl = (uint32_t)((uint64_t)srl * VT_FT_MS_NUM / VT_FT_MS_DEN);
    wl = (wl + 3u) & ~3u;
    if (wl < 160u) wl = 160u;
    if (wl > 512u) wl = 512u;
    v->ft_wl = wl;
    v->ft_hl = wl / 4u;

    // 共振峰级流延迟：OLA 完成所需前瞻（D·Wl + 2·D·Hl）+ FIR 延迟 + 裕量 + 最大块
    v->ft_delay = dec * wl + 2u * dec * v->ft_hl + VT_FIR_DELAY + 64u + VT_MAX_BLOCK;

    // 合成窗（周期 Hann）
    free(v->ft_window);
    v->ft_window = (float*)malloc((size_t)wl * sizeof(float));
    if (!v->ft_window) return false;
    const float two_pi_w = 6.283185307179586f / (float)wl;
    for (uint32_t i = 0; i < wl; i++) {
        v->ft_window[i] = 0.5f - 0.5f * cosf(two_pi_w * (float)i);
    }

    // 抽取/内插共用 FIR：Hamming 窗 sinc，截止 0.468·sr_low（高速率归一化 0.468/D）
    free(v->fir);
    v->fir = (float*)malloc(VT_FIR_TAPS * sizeof(float));
    if (!v->fir) return false;
    const uint32_t ctr = VT_FIR_DELAY;
    const float wc = 6.283185307179586f * 0.468f * (float)srl / (float)sample_rate;
    for (uint32_t i = 0; i < VT_FIR_TAPS; i++) {
        const int32_t d = (int32_t)i - (int32_t)ctr;
        float s;
        if (d == 0) {
            s = wc / 3.14159265358979f;
        } else {
            s = sinf(wc * (float)d) / (3.14159265358979f * (float)d);
        }
        const float ham = 0.54f - 0.46f * cosf(6.283185307179586f * (float)i /
                                               (float)(VT_FIR_TAPS - 1u));
        v->fir[i] = s * ham;
    }

    for (uint32_t ch = 0; ch < VT_MAX_CHANNELS; ch++) {
        vt_channel_t* c = &v->ch[ch];

        mm_psola_destroy(c->ps);
        c->ps = mm_psola_create(sample_rate);

        free(c->det_buf);
        free(c->fring);
        free(c->fir_hist);
        free(c->xlow);
        free(c->ola);
        free(c->frame_x);
        free(c->frame_e);
        free(c->frame_s);
        free(c->a_cur);
        free(c->a_shift);

        c->det_buf  = (float*)calloc(VT_DET_WIN, sizeof(float));
        c->fring    = (float*)calloc(VT_RING_CAP, sizeof(float));
        c->fir_hist = (float*)calloc(128u, sizeof(float));
        c->xlow     = (float*)calloc(VT_LOW_CAP, sizeof(float));
        c->ola      = (float*)calloc(VT_LOW_CAP, sizeof(float));
        c->frame_x  = (float*)malloc((size_t)wl * sizeof(float));
        c->frame_e  = (float*)malloc((size_t)wl * sizeof(float));
        c->frame_s  = (float*)malloc((size_t)wl * sizeof(float));
        c->a_cur    = (float*)calloc(MAIDMIC_LPC_MAX_ORDER + 1u, sizeof(float));
        c->a_shift  = (float*)calloc(MAIDMIC_LPC_MAX_ORDER + 1u, sizeof(float));

        if (!c->ps || !c->det_buf || !c->fring || !c->fir_hist || !c->xlow || !c->ola ||
            !c->frame_x || !c->frame_e || !c->frame_s || !c->a_cur || !c->a_shift) {
            mm_psola_destroy(c->ps); c->ps = NULL;
            free(c->det_buf);  c->det_buf = NULL;
            free(c->fring);    c->fring = NULL;
            free(c->fir_hist); c->fir_hist = NULL;
            free(c->xlow);     c->xlow = NULL;
            free(c->ola);      c->ola = NULL;
            free(c->frame_x);  c->frame_x = NULL;
            free(c->frame_e);  c->frame_e = NULL;
            free(c->frame_s);  c->frame_s = NULL;
            free(c->a_cur);    c->a_cur = NULL;
            free(c->a_shift);  c->a_shift = NULL;
            return false;
        }
    }

    for (uint32_t ch = 0; ch < VT_MAX_CHANNELS; ch++) {
        vt_reset_channel(v, ch);
    }
    return true;
}

static uint32_t vt_get_param_count(void* userdata) {
    (void)userdata;
    return 4;
}

static const maidmic_param_t* vt_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 4) return &vt_params[index];
    return NULL;
}

static bool vt_set_param(void* userdata, const char* key, maidmic_param_t value) {
    vt_data_t* v = (vt_data_t*)userdata;

    if (strcmp(key, "pitch_semitones") == 0) {
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
        float ratio = powf(2.0f, st / 12.0f);
        if (ratio < 0.25f) ratio = 0.25f;
        if (ratio > 4.0f) ratio = 4.0f;
        for (uint32_t ch = 0; ch < VT_MAX_CHANNELS; ch++) {
            if (v->ch[ch].ps) mm_psola_set_ratio(v->ch[ch].ps, ratio);
        }
        return true;
    }

    if (strcmp(key, "formant_shift") == 0) {
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
        if (order < 10) order = 10;
        if (order > (int32_t)MAIDMIC_LPC_MAX_ORDER) order = (int32_t)MAIDMIC_LPC_MAX_ORDER;
        v->lpc_order = order;
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

// 基频检测（检测环内最近 VT_DET_WIN 样本，×2 抽取后送自相关检测器）
static void vt_detect_and_report(vt_channel_t* c, uint32_t sample_rate) {
    uint32_t avail = VT_DET_WIN;
    if (c->det_wpos < VT_DET_WIN) avail = (uint32_t)c->det_wpos;

    if (avail < 256u) {
        mm_psola_report_pitch(c->ps, 0.0f, false);
        return;
    }

    float dec[VT_DET_WIN / 2u];
    const uint32_t nd = avail / 2u;
    const uint32_t start = (uint32_t)(c->det_wpos - avail);
    for (uint32_t i = 0; i < nd; i++) {
        const float a = c->det_buf[(start + 2u * i) % VT_DET_WIN];
        const float b = c->det_buf[(start + 2u * i + 1u) % VT_DET_WIN];
        dec[i] = 0.5f * (a + b);
    }

    bool voiced = false;
    const float f0 = maidmic_detect_pitch(dec, nd, sample_rate / 2u, &voiced);
    mm_psola_report_pitch(c->ps, f0, voiced);
}

// 抽取 FIR：输入高速率样本，返回滤波输出（线性相位，延迟 VT_FIR_DELAY）
static inline float vt_fir_tick(vt_channel_t* c, const float* fir, float x) {
    c->fir_hist[c->fir_cnt & 127u] = x;
    c->fir_cnt++;
    const uint32_t m = c->fir_cnt - 1u;  // 当前样本绝对序号
    float acc = 0.0f;
    for (uint32_t j = 0; j < VT_FIR_TAPS; j++) {
        acc += fir[j] * c->fir_hist[(m - j) & 127u];
    }
    return acc;
}

// 共振峰级：消费 n 个第一级输出样本（高速率），产出 n 个输出样本
static void vt_formant_process(vt_data_t* v, vt_channel_t* c,
                               const float* in, uint32_t n, float* out) {
    const uint32_t D = v->dec;
    const uint32_t W = v->ft_wl;
    const uint32_t H = v->ft_hl;
    const uint32_t order = (uint32_t)v->lpc_order;

    // ---- 1. 输入入高速率环 ----
    for (uint32_t i = 0; i < n; i++) {
        c->fring[(size_t)((c->fwpos + i) & VT_RING_MASK)] = in[i];
    }
    const uint64_t base = c->fwpos;  // 本块高速率起始绝对位置
    c->fwpos += n;

    // ---- 2. 抽取：FIR + 按中心对齐取样（仅 D ≥ 2；D=1 直接透传）----
    if (D >= 2u) {
        for (uint32_t i = 0; i < n; i++) {
            const float y = vt_fir_tick(c, v->fir, in[i]);
            const uint64_t m = base + i;          // 当前高速率绝对序号
            if (m >= VT_FIR_DELAY && ((m - VT_FIR_DELAY) % D) == 0u) {
                const uint64_t nl = (m - VT_FIR_DELAY) / D;  // 对应低速率序号
                c->xlow[(size_t)(nl & VT_LOW_MASK)] = y;
                c->xlw = nl + 1u;
            }
        }
    } else {
        for (uint32_t i = 0; i < n; i++) {
            c->xlow[(size_t)((base + i) & VT_LOW_MASK)] = in[i];
        }
        c->xlw = base + n;
    }

    // ---- 3. 低速率逐帧分析-合成（锚点 +W ≤ 已抽取样本数即处理）----
    while (c->anchor_l + W <= c->xlw) {
        const uint64_t anchor = c->anchor_l;

        for (uint32_t i = 0; i < W; i++) {
            c->frame_x[i] = c->xlow[(size_t)((anchor + i) & VT_LOW_MASK)];
        }

        float e_in = 0.0f;
        for (uint32_t i = 0; i < W; i++) {
            e_in += c->frame_x[i] * c->frame_x[i];
        }

        float g2t = 1.0f;
        bool synthesized = false;

        if (e_in > 1e-9f) {
            if (maidmic_lpc_analyze(c->frame_x, W, order, c->a_cur)) {
                maidmic_lpc_residual(c->frame_x, W, c->a_cur, order, c->frame_e);
                float e_res = 0.0f;
                for (uint32_t i = 0; i < W; i++) {
                    e_res += c->frame_e[i] * c->frame_e[i];
                }
                // 激励能量归一：保留响度、消除逆滤波增益失配
                float g1 = sqrtf(e_in / (e_res > 1e-9f ? e_res : 1e-9f));
                if (g1 > VT_G1_MAX) g1 = VT_G1_MAX;

                // 伸缩因子逐帧平滑 + 极点旋转 + 稳定化
                const float c_t = powf(2.0f, v->formant_st / 12.0f);
                c->c_cur += 0.25f * (c_t - c->c_cur);
                if (fabsf(v->formant_st) < 0.01f || fabsf(c->c_cur - 1.0f) < 1e-4f) {
                    memcpy(c->a_shift, c->a_cur, (size_t)(order + 1u) * sizeof(float));
                } else {
                    if (!vt_rotate_formants(c->a_cur, order, c->c_cur, c->a_shift) ||
                        !vt_stabilize_coeffs(c->a_shift, order)) {
                        memcpy(c->a_shift, c->a_cur, (size_t)(order + 1u) * sizeof(float));
                    }
                }

                // 移包络再合成（帧内零状态，边界由 OLA 弥合）
                float state[MAIDMIC_LPC_MAX_ORDER];
                memset(state, 0, sizeof(state));
                for (uint32_t i = 0; i < W; i++) {
                    c->frame_e[i] *= g1;
                }
                maidmic_lpc_synthesize(c->frame_e, W, c->a_shift, order, c->frame_s, state);

                // 数值安全网：异常输出回退恒等帧
                bool syn_ok = true;
                for (uint32_t i = 0; i < W; i++) {
                    if (!isfinite(c->frame_s[i]) || fabsf(c->frame_s[i]) > 64.0f) {
                        syn_ok = false;
                        break;
                    }
                }
                if (syn_ok) {
                    float e_syn = 0.0f;
                    for (uint32_t i = 0; i < W; i++) {
                        e_syn += c->frame_s[i] * c->frame_s[i];
                    }
                    g2t = sqrtf(e_in / (e_syn > 1e-9f ? e_syn : 1e-9f));
                    if (g2t > VT_G2_MAX) g2t = VT_G2_MAX;
                } else {
                    memcpy(c->frame_s, c->frame_x, (size_t)W * sizeof(float));
                    g2t = 1.0f;
                }
                synthesized = true;
            }
        }

        if (!synthesized) {
            memcpy(c->frame_s, c->frame_x, (size_t)W * sizeof(float));
            g2t = 1.0f;
        }

        // 输出响度归一：快起（0.5）慢落（0.08）
        if (g2t > c->g2) {
            c->g2 += 0.5f * (g2t - c->g2);
        } else {
            c->g2 += 0.08f * (g2t - c->g2);
        }

        // Hann² @ 75% 重叠和 = 1.5 → 归一 2/3
        const float norm = (2.0f / 3.0f) * c->g2;
        for (uint32_t i = 0; i < W; i++) {
            c->ola[(size_t)((anchor + i) & VT_LOW_MASK)] +=
                c->frame_s[i] * v->ft_window[i] * norm;
        }

        c->anchor_l += H;
    }

    // ---- 4. 发射：y[m] = x[m−δ] + interp_D(OLA − xlow)[m] ----
    // 校正发射位置额外回退 EXTRA，保证所需低速率 OLA 样本均已完成。
    const uint64_t extra = (uint64_t)D * W + 2u * (uint64_t)D * H + VT_FIR_DELAY + 64u;
    for (uint32_t i = 0; i < n; i++) {
        const uint64_t m = base + i;                       // 本块输出流位置
        const uint64_t me = (m > extra) ? (m - extra) : 0; // 校正取值位置

        // 4.1 原信号延迟项（延迟 δ = FIR_DELAY + extra 的等效已并入 extra）
        float y = c->fring[(size_t)((me) & VT_RING_MASK)];

        // 4.2 校正信号：c_low[n] = OLA[n] − x_low[n]
        //   D ≥ 2：多相内插 c_fir[me] = D·Σ_{j ≡ me (mod D)} h[j]·c_low[(me−j)/D]
        //   D = 1：OLA 在全速率，直接取 c_low[me]（不过内插低通）
        float corr;
        if (D >= 2u) {
            const uint32_t r = (uint32_t)(me % D);
            float acc = 0.0f;
            for (uint32_t j = r; j < VT_FIR_TAPS; j += D) {
                const uint64_t nl = (me - j) / D;
                if (nl >= c->xlw) continue;  // 低速率样本尚未抽取（启动期）
                const float cl =
                    c->ola[(size_t)(nl & VT_LOW_MASK)] - c->xlow[(size_t)(nl & VT_LOW_MASK)];
                acc += v->fir[j] * cl;
            }
            corr = acc * (float)D;
        } else {
            corr = c->ola[(size_t)(me & VT_LOW_MASK)] - c->xlow[(size_t)(me & VT_LOW_MASK)];
        }
        y += corr;

        // 4.3 输出 = 延迟对齐的原信号 + 校正（写入本块输出偏移 m − base）
        out[i] = y;
    }
}

// 单声道主路径：x[0..n) → out[0..n)（scratch 原地安全）
static void vt_process_channel(vt_data_t* v, uint32_t ch, uint32_t n,
                               const float* x, float* out, float* mid) {
    vt_channel_t* c = &v->ch[ch];
    const bool pitch_on = fabsf(v->pitch_st) > 0.001f;
    const bool formant_on = fabsf(v->formant_st) > 0.001f;

    if (!pitch_on && !formant_on) {
        if (out != x) memcpy(out, x, (size_t)n * sizeof(float));
        return;
    }

    // ---- 第一级：基频检测 + PSOLA 变调 ----
    if (pitch_on) {
        for (uint32_t i = 0; i < n; i++) {
            c->det_buf[(size_t)((c->det_wpos + i) % VT_DET_WIN)] = x[i];
        }
        c->det_wpos += n;
        vt_detect_and_report(c, v->sample_rate);
        mm_psola_process(c->ps, x, n, mid);
    } else {
        memcpy(mid, x, (size_t)n * sizeof(float));
    }

    // ---- 第二级：共振峰偏移 ----
    if (formant_on) {
        vt_formant_process(v, c, mid, n, out);
    } else {
        memcpy(out, mid, (size_t)n * sizeof(float));
    }
}

// ============================================================
// 核心：音频处理
// Core: audio processing
// ============================================================

static bool vt_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    vt_data_t* v = (vt_data_t*)userdata;
    if (input == NULL || output == NULL) return false;

    // ---- 快路径：旁路或两参数均 0 → 直通复制 ----
    if (v->bypass || (fabsf(v->pitch_st) <= 0.001f && fabsf(v->formant_st) <= 0.001f)) {
        vt_copy(input, output);
        return true;
    }

    if (input->meta.format != MAIDMIC_SAMPLE_F32 && input->meta.format != MAIDMIC_SAMPLE_S16) {
        vt_copy(input, output);
        return true;
    }
    if (input->data == NULL || output->data == NULL) return false;

    const uint32_t fc_total = input->meta.frame_count;
    const uint16_t chs = input->meta.channels;
    if (fc_total == 0 || chs == 0 || chs > VT_MAX_CHANNELS) {
        vt_copy(input, output);
        return true;
    }
    if (v->ft_wl == 0 || v->ch[0].fring == NULL) {
        vt_copy(input, output);
        return true;
    }

    // 分块驱动（fc_total 任意；单块 ≤ VT_MAX_BLOCK）
    uint32_t done = 0;
    while (done < fc_total) {
        uint32_t n = fc_total - done;
        if (n > VT_MAX_BLOCK) n = VT_MAX_BLOCK;

        if (input->meta.format == MAIDMIC_SAMPLE_F32) {
            const float* src = (const float*)input->data;
            float* dst = (float*)output->data;
            for (uint32_t ch = 0; ch < (uint32_t)chs; ch++) {
                for (uint32_t i = 0; i < n; i++) v->scratch_in[i] = src[(done + i) * chs + ch];
                vt_process_channel(v, ch, n, v->scratch_in, v->scratch_in, v->scratch_mid);
                for (uint32_t i = 0; i < n; i++) dst[(done + i) * chs + ch] = v->scratch_in[i];
            }
        } else {
            const int16_t* src = (const int16_t*)input->data;
            int16_t* dst = (int16_t*)output->data;
            for (uint32_t ch = 0; ch < (uint32_t)chs; ch++) {
                for (uint32_t i = 0; i < n; i++) {
                    v->scratch_in[i] = (float)src[(done + i) * chs + ch] * VT_S16_SCALE_IN;
                }
                vt_process_channel(v, ch, n, v->scratch_in, v->scratch_in, v->scratch_mid);
                for (uint32_t i = 0; i < n; i++) {
                    float s = v->scratch_in[i] * VT_S16_SCALE_OUT;
                    if (s > 32767.0f) s = 32767.0f;
                    if (s < -32768.0f) s = -32768.0f;
                    dst[(done + i) * chs + ch] = (int16_t)s;
                }
            }
        }
        done += n;
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

const maidmic_module_t maidmic_module_voice_transform = {
    .id = MAIDMIC_MODULE_ID_VOICE_TRANSFORM,
    .name = "Voice Transform",
    .description = "TD-PSOLA pitch shift + decimated-domain pole-rotated formant shift (变声 v3)",
    .author = "MaidMic Team",
    .version = 3,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_STEREO | MAIDMIC_CAP_NON_REALTIME,
    .vtable = &vt_vtable,
};
