// maidmic-engine/src/voice/psola.c
// MaidMic 流式 TD-PSOLA 变调引擎实现
// MaidMic Streaming TD-PSOLA Pitch Shifter Implementation
//
// 时域基音同步重叠相加（TD-PSOLA）的单声道流式实现。
//
// 原理（Moulines & Charpentier 1990）：
//   输出 = Σ_k x[mark_k + n - L/2]·w[n]·g，n ∈ [0, L)，汉明窗 w，窗长 L；
//   输出基音周期 = 合成基音标记间距 Ts，输入基音周期 = 分析基音标记间距 Ta；
//   每个输出基音周期放置一个"以输入基音标记为中心的窗"：
//     - 升调（Ts < Ta）：同一输入周期被相邻多个输出窗复用（k=0 复用）；
//     - 降调（Ts > Ta）：输入周期按 k≥1 跳过；
//     - 窗长 L = 2*max(Ta, Ts)（不随变调比率缩放）→ 每个窗内的谱包络
//       （共振峰）原样保留，只改变谐波间距 → 音高变了、音色不变。
//   重构增益 g = 2*Ts/L：使重叠相加的窗和恒为 1（Hann 均值 0.5、
//   覆盖密度 L/Ts 个窗），对任意 L/Ts 组合保持单位增益。
//
// 流式调度（块大小无关、热路径零分配）：
//   - 历史环暂存输入；输出位置与输入位置共用绝对时间轴（样本序号）；
//   - 输出基音标记中心 grain_out 每粒推进 Ts；读标记网格 prev_mark 以
//     Σ-Δ 方式逼近累计读位置 r（每粒 +Ts）：k = round((r - prev_mark)/Ta)
//     并钳位 [0,4]，保证平均读速率 = Ts（输入恰消耗一遍）且网格始终
//     对齐基音周期（相位锁定，无半周期抖动）；
//   - 每块调度所有中心 < out_base+fc+L/2max 的窗（多调度部分落入块尾
//     环形缓冲，供下一块输出），随后直接从输出环读出本块样本；
//   - 清音/未检出基频：Ts = Ta → k=1 恒等重叠相加，Hann COLA 精确
//     重构为"延迟直通"，不产生伪周期嗡声，且与浊音段无缝衔接。
//
// 算法延迟恒定 = PSOLA_DELAY（窗最大半径 ×2），输出流滞后输入流。

#include "maidmic/psola.h"
#include "maidmic/pitch_detector.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

// ============================================================
// 常量
// Constants
// ============================================================

#define PS_HIST_CAP   16384u   // 输入历史环容量（2 的幂）
#define PS_HIST_MASK  (PS_HIST_CAP - 1u)
#define PS_OUT_CAP    16384u   // 输出叠加环容量（2 的幂）
#define PS_OUT_MASK   (PS_OUT_CAP - 1u)

// 算法延迟：窗最大半径 = L_MAX/2 = 1024，需满足
// "读窗上界 rc+L/2 ≤ 写头 wpos"（对最晚调度的 grain 成立），
// 并满足"本块输出可全部发出"（对 center ≤ out_end+L/2max 的调度成立），
// 取 L_MAX（2048）留足余量。
#define PS_DELAY      2048u

#define PS_L_MIN      64u      // 窗长下限（样本）
#define PS_L_MAX      2048u    // 窗长上限（Ta=60Hz@48k → L=1600 < 2048）
#define PS_K_MAX      4        // 单粒读标记最大跳过周期数（防网格断裂）
#define PS_TA_MIN_F   60.0f    // 基频下限 Hz（与 pitch_detector 搜索范围一致）
#define PS_TA_MAX_F   500.0f   // 基频上限 Hz

// ============================================================
// 实例结构
// Instance
// ============================================================

struct mm_psola_s {
    uint32_t sample_rate;

    // 参数（目标变调比率，内部逐粒平滑）
    float ratio_target;
    float ratio_cur;      // 当前平滑比率（每粒一阶逼近）

    // 基音状态
    float Ta;             // 平滑分析基音周期（样本）
    float Ts;             // 当前合成基音周期（样本，= Ta/ratio 或 Ta 清音）
    bool unvoiced;        // 清音模式（Ts = Ta，恒等重构）

    // 历史环（输入）
    float* hist;
    uint64_t wpos;        // 已写入样本总数（绝对）

    // 调度状态（绝对样本位置）
    uint64_t grain_out;   // 下一输出窗中心
    uint64_t prev_mark;   // 上一读基音标记（绝对）
    float r;              // 累计读位置（每粒 +Ts）

    // 输出叠加环
    float* oring;

    // 本块发射基准线：输出环中 < emit_base 的位置属于已发出的历史样本，
    // 调度写入不得触及（调度进度恒领先发射基准线 ≥ 半窗长，见 process）
    uint64_t emit_base;

    // 汉明窗缓存（按当前 L 计算，每粒重算；L ≤ PS_L_MAX）
    float win[PS_L_MAX];
    uint32_t win_len;     // 当前缓存窗长
};

// ============================================================
// 内部辅助
// Internal helpers
// ============================================================

// 历史环读（绝对位置；越界返回 0 —— 启动期前导静音）
static inline float ps_hist_read(const mm_psola_t* ps, uint64_t pos) {
    if (pos >= ps->wpos) return 0.0f;
    return ps->hist[(size_t)(pos & PS_HIST_MASK)];
}

// 计算窗长：L = 2*round(max(Ta,Ts))，偶数，钳位 [PS_L_MIN, PS_L_MAX]
static inline uint32_t ps_window_len(const mm_psola_t* ps) {
    float base = (ps->Ts > ps->Ta) ? ps->Ts : ps->Ta;
    uint32_t half = (uint32_t)(base + 0.5f);
    if (half < PS_L_MIN / 2u) half = PS_L_MIN / 2u;
    if (half > PS_L_MAX / 2u) half = PS_L_MAX / 2u;
    return half * 2u;
}

// 生成周期 Hann 窗：w[n] = 0.5 - 0.5*cos(2πn/L)（均值 0.5，COLA 兼容）
static void ps_make_window(mm_psola_t* ps, uint32_t L) {
    if (ps->win_len == L) return;  // 窗长未变，复用缓存
    const float two_pi_over_L = 6.283185307179586f / (float)L;
    for (uint32_t n = 0; n < L; n++) {
        ps->win[n] = 0.5f - 0.5f * cosf(two_pi_over_L * (float)n);
    }
    ps->win_len = L;
}

// 复位调度状态（不动比率目标）
static void ps_reset_state(mm_psola_t* ps) {
    memset(ps->hist, 0, PS_HIST_CAP * sizeof(float));
    memset(ps->oring, 0, PS_OUT_CAP * sizeof(float));
    ps->wpos = 0;
    ps->grain_out = PS_DELAY;
    ps->prev_mark = 0;
    ps->r = 0.0f;
    ps->emit_base = 0;
    ps->Ta = (float)ps->sample_rate / 200.0f;  // 初始假设 200Hz
    ps->Ts = ps->Ta;
    ps->unvoiced = true;
    ps->ratio_cur = 1.0f;
    ps->win_len = 0;
}

// ============================================================
// 公开 API
// Public API
// ============================================================

mm_psola_t* mm_psola_create(uint32_t sample_rate) {
    if (sample_rate == 0) return NULL;
    mm_psola_t* ps = (mm_psola_t*)calloc(1, sizeof(mm_psola_t));
    if (!ps) return NULL;
    ps->sample_rate = sample_rate;
    ps->hist = (float*)calloc(PS_HIST_CAP, sizeof(float));
    ps->oring = (float*)calloc(PS_OUT_CAP, sizeof(float));
    if (!ps->hist || !ps->oring) {
        mm_psola_destroy(ps);
        return NULL;
    }
    ps->ratio_target = 1.0f;
    ps_reset_state(ps);
    return ps;
}

void mm_psola_destroy(mm_psola_t* ps) {
    if (!ps) return;
    free(ps->hist);
    free(ps->oring);
    free(ps);
}

void mm_psola_reset(mm_psola_t* ps) {
    if (!ps) return;
    ps_reset_state(ps);
}

void mm_psola_set_ratio(mm_psola_t* ps, float target_ratio) {
    if (!ps) return;
    if (!(target_ratio > 0.0f) || !isfinite(target_ratio)) return;
    if (target_ratio < 0.25f) target_ratio = 0.25f;
    if (target_ratio > 4.0f) target_ratio = 4.0f;
    ps->ratio_target = target_ratio;
}

void mm_psola_report_pitch(mm_psola_t* ps, float f0, bool voiced) {
    if (!ps) return;
    if (voiced && f0 > 0.0f && isfinite(f0)) {
        // 周期钳位到 60~500Hz 对应范围
        float Ta_t = (float)ps->sample_rate / f0;
        const float ta_min = (float)ps->sample_rate / PS_TA_MAX_F;
        const float ta_max = (float)ps->sample_rate / PS_TA_MIN_F;
        if (Ta_t < ta_min) Ta_t = ta_min;
        if (Ta_t > ta_max) Ta_t = ta_max;
        // 一阶平滑（块级调用，系数 0.3 ≈ 10ms 时间常数 @256 块）
        ps->Ta += 0.3f * (Ta_t - ps->Ta);
        ps->unvoiced = false;
    } else {
        ps->unvoiced = true;  // 周期保持，进入恒等重构
    }
}

uint32_t mm_psola_get_delay(const mm_psola_t* ps) {
    (void)ps;
    return PS_DELAY;
}

// 发射单个窗：以输入标记 c 为中心、输出中心 o，叠加进输出环。
// 仅写入位置 ≥ emit_base 的样本（更旧位置已发出并清零，不得回写）。
static void ps_emit_grain(mm_psola_t* ps, uint64_t o, uint64_t c, uint32_t L, float g) {
    const int64_t half = (int64_t)(L / 2u);

    for (uint32_t n = 0; n < L; n++) {
        const int64_t out_rel = (int64_t)(o + n) - (int64_t)half;  // 输出绝对位置
        if (out_rel < (int64_t)ps->emit_base) continue;
        const int64_t in_pos = (int64_t)c + (int64_t)n - half;     // 输入绝对位置
        const float s = ps_hist_read(ps, (uint64_t)in_pos) * ps->win[n] * g;
        if (s == 0.0f) continue;
        ps->oring[(size_t)((uint64_t)out_rel & PS_OUT_MASK)] += s;
    }
}

void mm_psola_process(mm_psola_t* ps, const float* x, uint32_t fc, float* y) {
    if (!ps || !x || !y || fc == 0) return;

    const uint64_t out_base = ps->wpos;  // 输出绝对基 = 输入绝对基（等速流）
    ps->emit_base = out_base;            // 本块发射基准线

    // ---- 1. 输入入环 ----
    for (uint32_t i = 0; i < fc; i++) {
        ps->hist[(size_t)((ps->wpos + i) & PS_HIST_MASK)] = x[i];
    }
    ps->wpos += fc;

    // ---- 2. 逐粒调度：中心 < out_end + L/2max 的窗全部叠加 ----
    // 提前调度半个最大窗长，保证本块输出位置的全部贡献都已入环。
    const uint64_t sched_end = out_base + fc + (PS_L_MAX / 2u);
    while (ps->grain_out < sched_end) {
        // 比率逐粒平滑（约 30ms 逼近 @200Hz）
        ps->ratio_cur += 0.06f * (ps->ratio_target - ps->ratio_cur);
        if (fabsf(ps->ratio_cur - ps->ratio_target) < 1e-4f) {
            ps->ratio_cur = ps->ratio_target;
        }

        // 合成周期：清音 = Ta（恒等重构）；浊音 = Ta/ratio
        float Ts_t = ps->unvoiced ? ps->Ta : (ps->Ta / ps->ratio_cur);
        if (Ts_t < 16.0f) Ts_t = 16.0f;              // 防御：周期下限
        if (Ts_t > (float)(PS_L_MAX / 2u)) Ts_t = (float)(PS_L_MAX / 2u);
        ps->Ts = Ts_t;

        // 读标记 Σ-Δ 推进：k = round((r - prev_mark)/Ta) ∈ [0, K_MAX]
        ps->r += ps->Ts;
        float diff = ps->r - (float)ps->prev_mark;
        int k = (int)lroundf(diff / ps->Ta);
        if (k < 0) k = 0;
        if (k > PS_K_MAX) k = PS_K_MAX;
        // 可用性钳位：读窗上界不得超过写头
        const uint32_t L = ps_window_len(ps);
        ps_make_window(ps, L);
        const float g = 2.0f * ps->Ts / (float)L;    // 重构增益（窗和恒 1）
        const uint64_t half = L / 2u;
        while (k > 0 &&
               (ps->prev_mark + (uint64_t)k * (uint64_t)ps->Ta + half) >= ps->wpos) {
            k--;
        }

        uint64_t c = ps->prev_mark + (uint64_t)k * (uint64_t)ps->Ta;
        ps->prev_mark = c;

        // 读标记不得早于历史环可读下界（过旧内容已被覆盖 → 钳到最近可用）
        const uint64_t oldest = (ps->wpos > PS_HIST_CAP) ? (ps->wpos - PS_HIST_CAP + 64u) : 0u;
        if (c < oldest) c = oldest;

        ps_emit_grain(ps, ps->grain_out, c, L, g);

        ps->grain_out += (uint64_t)ps->Ts;
        if (ps->grain_out <= c) {
            // 防御：异常参数下避免死循环（grain_out 必须前进）
            ps->grain_out = c + 1u;
        }
    }

    // ---- 3. 从输出环读出本块样本（读后清零，供后续复用） ----
    for (uint32_t i = 0; i < fc; i++) {
        const size_t idx = (size_t)((out_base + i) & PS_OUT_MASK);
        y[i] = ps->oring[idx];
        ps->oring[idx] = 0.0f;
    }
}
