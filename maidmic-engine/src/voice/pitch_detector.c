// maidmic-engine/src/voice/pitch_detector.c
// MaidMic 基频检测算法库
// MaidMic Pitch Detection Algorithm Library
//
// 实时基频检测纯算法库（无模块 vtable、无缓冲处理），供 voice_transform
// 与 autotune 模块调用。算法为经典自相关法（ACF, Autocorrelation）：
//   1. 对输入帧去均值（消除直流分量，避免污染自相关与能量估计）；
//   2. 计算归一化自相关 norm[tau] = R[tau] / R[0]，
//      R[tau] = Σ_{i=0}^{n-1-tau} x[i] * x[i+tau]；
//   3. 在 tau ∈ [tau_min, tau_max] 内搜索最大峰值（tau_min >= 1，排除 tau=0），
//      峰值处做抛物线插值提升频率精度；
//   4. 归一化峰值 > 阈值 且 能量 R[0] > 阈值 → 判定浊音并输出基频，
//      否则返回 0（未检出）。
//
// 搜索范围 60~500Hz（tau 与频率互为倒数：tau = fs / f）：
//   最高频 500Hz → 最短周期 tau_min = ceil(sample_rate / 500)
//   最低频 60Hz  → 最长周期 tau_max = floor(sample_rate / 60)
//   帧长不足时 tau_max 截断到 n/2：tau 越接近 n，自相关求和样本越少，
//   归一化估计方差越大，n/2 是常用经验上限。
//
// 全部 float 运算；栈上定长工作数组（最大窗口 8192 样本 = 32KB），
// 无堆分配、无内部状态 → 线程安全、可重入、确定性（纯 CPU）。
// Pure CPU, deterministic, reentrant. No heap, no internal state.

#include "maidmic/pitch_detector.h"

#include <stddef.h> /* NULL（宿主 gcc/clang/zig 不传递引入，需显式包含） */

// ============================================================
// 常量
// Constants
// ============================================================

#define PD_MAX_WINDOW 8192            // 最大分析窗口（样本数），超出只取前段
#define PD_VOICED_PEAK_THRESH 0.3f    // 归一化自相关峰值阈值（浊音判定）
#define PD_ENERGY_THRESH     1e-4f    // 帧能量 R[0] 阈值（静音/噪声剔除）
#define PD_ISV_ENERGY_THRESH 1e-4f    // maidmic_is_voiced_frame 能量阈值
#define PD_ISV_ZCR_THRESH    0.3f     // maidmic_is_voiced_frame 过零率阈值

// ============================================================
// 辅助函数
// Helpers
// ============================================================

// 单点自相关和：R[tau] = Σ_{i=0}^{n-1-tau} w[i] * w[i+tau]
// 调用前提：w 为去均值后的数据，tau < n（调用方保证）。
// Sum of lagged products: R[tau] over the valid overlap [0, n-1-tau].
static float pd_acf_sum(const float* w, uint32_t n, uint32_t tau) {
    float sum = 0.0f;
    const uint32_t limit = n - tau;
    for (uint32_t i = 0; i < limit; i++) {
        sum += w[i] * w[i + tau];
    }
    return sum;
}

// ============================================================
// maidmic_detect_pitch：自相关法基频检测
// ============================================================

float maidmic_detect_pitch(const float* x, uint32_t n, uint32_t sample_rate, bool* voiced) {
    // 默认未检出：所有失败路径都必须保证 *voiced = false、返回 0
    if (voiced) *voiced = false;

    // 输入防御：空指针、空帧、非法采样率、帧长 < 2（无法定义周期）
    if (x == NULL || n < 2u || sample_rate == 0u) return 0.0f;

    // 工作窗口上限：n 可达数千，按固定上限截断，避免超大栈帧
    if (n > PD_MAX_WINDOW) n = PD_MAX_WINDOW;

    // ---- 搜索区间：tau 与频率互为倒数，对应 60~500Hz ----
    // tau_min = ceil(fs / 500)：对应最高频 500Hz（最短周期）。
    //   向上取整保证 tau_min >= 1，杜绝 tau=0（R[0]/R[0]=1 恒最大，会误检）。
    // tau_max = floor(fs / 60)：对应最低频 60Hz（最长周期）。
    // 帧长不足时把 tau_max 截断到 n/2（自相关长延时的方差控制）。
    const uint32_t tau_min = (sample_rate + 499u) / 500u;
    uint32_t tau_max = sample_rate / 60u;
    const uint32_t half = n / 2u;
    if (tau_max > half) tau_max = half;

    // 搜索区间为空（帧太短或采样率过低，无法覆盖最小周期）→ 未检出
    if (tau_min > tau_max) return 0.0f;

    // ---- 1) 去均值：直流分量会抬高 R[0] 并扭曲归一化峰值 ----
    // 均值从全部 n 个样本估算：mean = Σx / n
    float w[PD_MAX_WINDOW];
    float mean = 0.0f;
    for (uint32_t i = 0; i < n; i++) mean += x[i];
    mean /= (float)n;

    float r0 = 0.0f;  // R[0] = Σ x'[i]²（去均值后的帧能量）
    for (uint32_t i = 0; i < n; i++) {
        const float v = x[i] - mean;
        w[i] = v;
        r0 += v * v;
    }

    // 能量不足（近乎静音/纯噪声）→ 未检出；同时保证后续 norm = R/r0 无除零
    if (r0 <= PD_ENERGY_THRESH) return 0.0f;

    // ---- 2)~3) 归一化自相关峰值搜索（tau 从 tau_min 起，排除 tau=0）----
    float best_norm = -1.0f;
    uint32_t best_tau = 0u;
    for (uint32_t tau = tau_min; tau <= tau_max; tau++) {
        const float norm = pd_acf_sum(w, n, tau) / r0;
        if (norm > best_norm) {
            best_norm = norm;
            best_tau = tau;
        }
    }

    // ---- 4) 浊音判定：归一化峰值 + 能量双条件 ----
    // 浊音自相关峰值典型 > 0.5；清音/摩擦音近噪声，峰值显著更低（< 0.3）。
    // 阈值 PD_VOICED_PEAK_THRESH = 0.3 为浊/清经验分界，可依需要微调。
    if (best_norm <= PD_VOICED_PEAK_THRESH) return 0.0f;

    // ---- 抛物线插值（精度提升，推荐）----
    // 以 (tau-1, tau, tau+1) 三点拟合抛物线 y = a·τ² + b·τ + c：
    //   y1 = y(-1), y2 = y(0), y3 = y(+1)
    //   a = (y1 - 2·y2 + y3) / 2
    //   b = (y3 - y1) / 2
    // 顶点偏移 delta = -b / (2a) = (y1 - y3) / (2·(y1 - 2·y2 + y3))
    // 仅当最佳 tau 两侧都有邻居（不在搜索区间边界）且分母为负
    // （二次项系数 a < 0，确为极大值；y2 是区间最大，恒成立）时插值。
    float tau_best = (float)best_tau;
    if (best_tau > tau_min && best_tau < tau_max) {
        const float y1 = pd_acf_sum(w, n, best_tau - 1u) / r0;
        const float y3 = pd_acf_sum(w, n, best_tau + 1u) / r0;
        const float denom = y1 - 2.0f * best_norm + y3;
        if (denom < 0.0f) {
            float delta = 0.5f * (y1 - y3) / denom;
            // 防御：顶点偏移限制在半格（±0.5 bin）内
            if (delta > 0.5f) delta = 0.5f;
            else if (delta < -0.5f) delta = -0.5f;
            tau_best += delta;
        }
    }

    // 基频 = 采样率 / 周期（样本数）；tau_best > 0 恒成立（tau_min >= 1）
    const float f0 = (float)sample_rate / tau_best;

    if (voiced) *voiced = true;
    return f0;
}

// ============================================================
// maidmic_is_voiced_frame：能量 + 过零率清浊音判定
// ============================================================

bool maidmic_is_voiced_frame(const float* x, uint32_t n) {
    // 帧长 < 2 无法计算过零率
    if (x == NULL || n < 2u) return false;

    // 短时能量 E = Σx² / n；过零率 ZCR = 过零次数 / (n-1)
    // 过零定义：相邻样本符号不同记为一次过零；以 x >= 0 为"正"判号，
    // 可正确处理样本恰为 0 的情况。
    float energy = 0.0f;
    uint32_t zc = 0u;
    bool prev_pos = (x[0] >= 0.0f);
    for (uint32_t i = 0; i < n; i++) {
        energy += x[i] * x[i];
        if (i > 0u) {
            const bool pos = (x[i] >= 0.0f);
            if (pos != prev_pos) zc++;
            prev_pos = pos;
        }
    }
    energy /= (float)n;
    const float zcr = (float)zc / (float)(n - 1u);

    // 规则：能量高于噪声底 且 过零率低于阈值 → 浊音帧（有周期性）
    // 浊音（元音）能量集中、波形准周期 → ZCR 低（典型 < 0.1）；
    // 清音（辅音）近噪声 → ZCR 高（典型 > 0.4）。阈值可依需要微调：
    //   PD_ISV_ENERGY_THRESH = 1e-4：剔除静音/极弱信号
    //   PD_ISV_ZCR_THRESH    = 0.3 ：浊/清经验分界（上调更激进判浊）
    return (energy > PD_ISV_ENERGY_THRESH) && (zcr < PD_ISV_ZCR_THRESH);
}
