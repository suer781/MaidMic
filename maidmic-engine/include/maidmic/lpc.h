// maidmic-engine/include/maidmic/lpc.h
// MaidMic 引擎 LPC 分析与合成算法库接口
// MaidMic Engine LPC Analysis & Synthesis API
//
// 自研 LPC 源-滤波器变声引擎的纯算法层：
//   分析         — 汉明窗 + 自相关 + Levinson-Durbin → 全极点模型系数 a[0..order]
//   逆滤波       — 语音 → 激励残差（源信号）
//   全极点合成   — 激励残差 → 语音（含跨块状态衔接）
//   共振峰偏移   — 对谱包络做频率伸缩近似，搬移共振峰整体位置
// 本层为纯函数算法库：不含模块 vtable、不含缓冲处理，
// 由上层 voice_transform 模块（以及未来的变声处理链）调用。
// 全部使用 float 精度，可重入（无全局状态）。

#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// ============================================================
// LPC 分析 / 合成
// LPC analysis / synthesis
// ============================================================

// 分析阶数上限：lpc.c 内部按此固定大小使用栈数组，
// 调用方传入的 order 不应超过该值（超限时按失败处理）。
#define MAIDMIC_LPC_MAX_ORDER 20u

// LPC 分析：加窗（汉明窗）自相关 + Levinson-Durbin，输出预测系数 a[0..order]。
// 输入 x 为 n 个样本（建议 n 远大于 order，如 256~4096）；
// 输出 a 长度为 order+1，其中 a[0] = 1.0f。
// 递推公式：
//   w[i] = 0.54 - 0.46*cos(2π*i/(n-1))                        （汉明窗）
//   xw[i] = x[i] * w[i]                                       （加窗）
//   R[k] = Σ_{i=k}^{n-1} xw[i] * xw[i-k]                      （自相关，k=0..order）
//   Levinson-Durbin：
//     E_0 = R[0]
//     k_i = ( R[i] - Σ_{j=1}^{i-1} a_j^(i-1) * R[i-j] ) / E_{i-1}
//     a_i^(i) = k_i，a_j^(i) = a_j^(i-1) - k_i * a_{i-j}^(i-1)（j=1..i-1）
//     E_i = E_{i-1} * (1 - k_i^2)
// 稳定性处理：|k_i| >= 1 时截断为 ±0.99 并提前终止递推（高阶系数补 0）。
// 信号能量过小（R[0] < 1e-6）或病态（能量塌缩、参数非法）时返回 false，
// 且 a 置为直通系数 [1, 0, 0, ...]（长度 order+1）。
bool maidmic_lpc_analyze(const float* x, uint32_t n, uint32_t order, float* a);

// 逆滤波：激励残差 e[i] = x[i] - sum_{k=1..order} a[k]*x[i-k]
// （不足 order 的历史样本按 0 处理，即块首无跨块状态、直接从 0 历史开始）。
// e 与 x 可指向同一缓冲区（原地处理安全）。
void maidmic_lpc_residual(const float* x, uint32_t n, const float* a, uint32_t order, float* e);

// 全极点合成：y[i] = e[i] + sum_{k=1..order} a[k]*y[i-k]。
// state 为长度 order 的 float 数组，保存上一块末尾的历史输出：
//   state[0] 为最近的一个输出，state[order-1] 为最远的一个；
//   首次调用前须清零（或调用方保证全零），跨块调用时原样传递以保证连续。
// 本函数会在返回前将本块末尾 order 个输出滚动写入 state。
void maidmic_lpc_synthesize(const float* e, uint32_t n, const float* a, uint32_t order, float* y, float* state);

// 共振峰偏移：对 LPC 系数做频率搬移，formant_shift 单位为半音（-12~+12）。
// 实现方案（谱包络整体频率伸缩近似，详见 lpc.c 中公式注释）：
//   c = 2^(formant_shift/12)，a_shifted[k] = a[k] * c^k（k=0..order），
//   归一化保证 a_shifted[0] = 1.0。等价于对 z 平面做尺度变换 z → z/c，
//   使幅度响应的峰值随 c 沿频率轴平移（近似方法）。
// formant_shift 接近 0（|shift| < 0.01）时直接复制 a 到 a_shifted 并返回 true。
// 返回 false 仅当系数病态（指针为空、order 非法或系数非有限值）。
bool maidmic_lpc_find_formant_shifts(const float* a, uint32_t order, float formant_shift, uint32_t sample_rate, float* a_shifted);

// NEON SIMD 是否可用（编译期检测；非 ARM 平台恒为 false）。
// 供 JNI 层输出 "NEON enabled" 日志。实现位于 lpc.c。
// Whether this build has ARM NEON SIMD enabled.
bool maidmic_neon_enabled(void);

#ifdef __cplusplus
}
#endif
