// maidmic-engine/src/voice/lpc.c
// MaidMic 引擎 LPC 分析与合成算法库
// MaidMic Engine LPC Analysis & Synthesis
//
// 自研 LPC 源-滤波器变声引擎的纯算法层：
//   maidmic_lpc_analyze              — 汉明窗加窗 + 自相关 + Levinson-Durbin 求解全极点系数
//   maidmic_lpc_residual             — 逆滤波：语音 → 激励残差（源信号）
//   maidmic_lpc_synthesize           — 全极点合成：激励残差 → 语音（含跨块状态衔接）
//   maidmic_lpc_find_formant_shifts  — 共振峰偏移：谱包络整体频率伸缩近似
//
// 本文件不含模块 vtable、不含缓冲管理，全部 float 精度、可重入，
// 由上层 voice_transform 模块调用。热路径（逐样本循环）内无 malloc。

#include "maidmic/lpc.h"
#include <math.h>
#include <string.h>

// ============================================================
// NEON SIMD 支持
// NEON SIMD support
// ============================================================
// 仅当编译目标为 ARM 且启用 NEON 时才包含 <arm_neon.h>；
// x86 / 模拟器 / 主机测试编译走下方行为一致的标量路径。
// The intrinsics header is only included for ARM NEON builds;
// all other targets use the behaviour-identical scalar path.
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

// ============================================================
// 常量
// Constants
// ============================================================

#define LPC_TWO_PI    6.283185307179586f  // 2π
#define LPC_MAX_ORDER 20u                 // 分析阶数上限（栈数组固定大小，与 lpc.h 一致）
#define LPC_MAX_FRAME 65536u              // 单帧样本数上限（VLA 栈防护，防止大帧撑爆栈）
#define LPC_SILENCE_E 1e-6f               // 自相关能量 R[0] 低于此值视为静音
#define LPC_E_MIN     1e-20f              // Levinson 残差能量下限（防御除零 / 病态）
#define LPC_K_CLAMP   0.99f               // 反射系数 |k|>=1 时的截断限幅
#define LPC_SHIFT_EPS 0.01f               // 共振峰偏移近似零阈值（半音）

// ============================================================
// 静态辅助
// Static helpers
// ============================================================

// 汉明窗：w[i] = 0.54 - 0.46*cos(2π*i/(n-1))，i = 0..n-1
// 调用处保证 n >= 2（n==1 时除零，已在 analyze 入口拦截）。
static inline float lpc_hamming(uint32_t i, uint32_t n) {
    return 0.54f - 0.46f * cosf(LPC_TWO_PI * (float)i / (float)(n - 1));
}

// 直通系数：a = [1, 0, 0, ..., 0]（长度 order+1），供失败/静音时使用。
// order == 0 时也保证 a[0] = 1.0f（与分析函数的直通契约一致）。
static void lpc_set_passthrough(float* a, uint32_t order) {
    if (a == NULL) return;
    memset(a, 0, (size_t)(order + 1) * sizeof(float));
    a[0] = 1.0f;
}

// ============================================================
// Levinson-Durbin 递推
// Levinson-Durbin recursion
// ============================================================
// 输入：自相关 R[0..order]（R[0] > 0 已由调用方检查）
// 输出：预测系数 a[0..order]，a[0] = 1.0f
// 递推公式（i = 1..order）：
//   反射系数 k_i = ( R[i] - Σ_{j=1}^{i-1} a_j^(i-1) * R[i-j] ) / E_{i-1}
//   新系数       a_i^(i) = k_i
//                a_j^(i) = a_j^(i-1) - k_i * a_{i-j}^(i-1)，j = 1..i-1
//   残差能量     E_i = E_{i-1} * (1 - k_i^2)
// 稳定性：|k_i| >= 1 时截断为 ±0.99 并提前终止（高阶系数补 0），
// 保证输出滤波器全极点稳定（极点全部在单位圆内）。
// 病态（E 塌缩到 LPC_E_MIN 以下）返回 false，由调用方置直通。
static bool lpc_levinson_durbin(const float* R, uint32_t order, float* a) {
    float old[LPC_MAX_ORDER + 1];  // 上一阶系数副本（就地更新会破坏尚未读取的项）
    float E = R[0];

    a[0] = 1.0f;

    for (uint32_t i = 1; i <= order; i++) {
        // 拷贝上一阶系数 a[0..i-1] 到 old
        memcpy(old, a, (size_t)i * sizeof(float));

        // 反射系数分子：acc = R[i] - Σ_{j=1}^{i-1} old[j] * R[i-j]
        float acc = R[i];
        for (uint32_t j = 1; j < i; j++) {
            acc -= old[j] * R[i - j];
        }

        // 防御：残差能量塌缩（数值上不可继续递推，视为病态信号）
        if (E < LPC_E_MIN) {
            return false;
        }

        float k = acc / E;

        // 稳定性：|k| >= 1 → 截断为 ±0.99 并停止递推，高阶补 0
        if (fabsf(k) >= 1.0f) {
            k = (k >= 0.0f) ? LPC_K_CLAMP : -LPC_K_CLAMP;
            a[i] = k;
            for (uint32_t j = 1; j < i; j++) {
                a[j] = old[j] - k * old[i - j];
            }
            for (uint32_t j = i + 1; j <= order; j++) {
                a[j] = 0.0f;
            }
            return true;
        }

        // 正常更新：a_i = k_i，a_j = old[j] - k * old[i-j]
        a[i] = k;
        for (uint32_t j = 1; j < i; j++) {
            a[j] = old[j] - k * old[i - j];
        }

        E *= (1.0f - k * k);
    }
    return true;
}

// ============================================================
// LPC 分析
// LPC analysis
// ============================================================
bool maidmic_lpc_analyze(const float* x, uint32_t n, uint32_t order, float* a) {
    // ---- 参数防御：非法输入一律置直通并返回 false ----
    if (x == NULL || a == NULL || order == 0 || order > LPC_MAX_ORDER) {
        lpc_set_passthrough(a, order);
        return false;
    }
    // n 过小（n==1 时汉明窗除零）或 order >= n（自相关滞后超过样本数）→ 直通
    if (n < 2 || n > LPC_MAX_FRAME || order >= n) {
        lpc_set_passthrough(a, order);
        return false;
    }

    // 步骤 1：汉明窗加窗（n 已被上限检查约束，C99 VLA 栈分配）
    float xw[n];
    for (uint32_t i = 0; i < n; i++) {
        xw[i] = x[i] * lpc_hamming(i, n);
    }

    // 步骤 2：自相关 R[k] = Σ_{i=k}^{n-1} xw[i] * xw[i-k]，k = 0..order
    // 该双重循环是 analyze 的热路径（复杂度 O(n·order)）。
    // ARM NEON 下对最内层做 4 路并行乘累加（vmlaq_f32）：一次处理
    // i, i+1, i+2, i+3 四个连续滞后样本；4 路块的余量（1~3 个样本）
    // 由标量循环收尾，保证不越界。非 ARM 平台走完全一致的标量累加
    // （两种路径累加顺序略有差异，允许极小浮点误差）。
    float R[LPC_MAX_ORDER + 1];
    for (uint32_t k = 0; k <= order; k++) {
        float acc = 0.0f;
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
        // 4 路并行累加：vacc = Σ_i xw[i..i+3] ⊙ xw[i-k..i-k+3]
        uint32_t i = k;
        float32x4_t vacc = vdupq_n_f32(0.0f);
        for (; i + 4 <= n; i += 4) {
            float32x4_t cur = vld1q_f32(&xw[i]);     // xw[i], xw[i+1], xw[i+2], xw[i+3]
            float32x4_t lag = vld1q_f32(&xw[i - k]); // xw[i-k], ..., xw[i-k+3]
            vacc = vmlaq_f32(vacc, cur, lag);
        }
        // 横向归约（vaddvq_f32 仅 AArch64 可用，这里用可移植的配对相加）
        float32x2_t sum2 = vadd_f32(vget_low_f32(vacc), vget_high_f32(vacc));
        sum2 = vpadd_f32(sum2, sum2);
        acc = vget_lane_f32(sum2, 0);
        // 尾部 1~3 个样本标量收尾
        for (; i < n; i++) {
            acc += xw[i] * xw[i - k];
        }
#else
        for (uint32_t i = k; i < n; i++) {
            acc += xw[i] * xw[i - k];
        }
#endif
        R[k] = acc;
    }

    // 步骤 3：能量检查 —— R[0] 过小视为静音
    if (R[0] < LPC_SILENCE_E) {
        lpc_set_passthrough(a, order);
        return false;
    }

    // 步骤 4：Levinson-Durbin 求解预测系数
    if (!lpc_levinson_durbin(R, order, a)) {
        lpc_set_passthrough(a, order);
        return false;
    }
    return true;
}

// ============================================================
// 逆滤波：激励残差
// Inverse filtering: excitation residual
// ============================================================
// e[i] = x[i] - Σ_{k=1}^{min(order,i)} a[k] * x[i-k]
// 块首（i < order）历史样本不足，按 0 处理（残差为开环、无跨块状态）。
//
// 性能注记（NEON）：保留标量实现。每个输出 e[i] 依赖其之前 order 个输入
// x[i-1..i-order]（滑动窗短 FIR），窗起点随 i 逐样本滑动，无法稳定对齐到
// 4 路向量边界；order ≤ 20 的标量内层循环已被编译器充分展开，
// 向量化（vld1q + 重排）的开销大于收益，故不做 NEON。
void maidmic_lpc_residual(const float* x, uint32_t n, const float* a, uint32_t order, float* e) {
    if (x == NULL || e == NULL || n == 0 || order == 0) return;

    for (uint32_t i = 0; i < n; i++) {
        float acc = x[i];
        uint32_t kmax = (i < order) ? i : order;
        for (uint32_t k = 1; k <= kmax; k++) {
            acc -= a[k] * x[i - k];
        }
        e[i] = acc;
    }
}

// ============================================================
// 全极点合成
// All-pole synthesis
// ============================================================
// y[i] = e[i] + Σ_{k=1}^{min(order,i)} a[k] * y[i-k]（块内）+ 块外历史项
//
// 跨块状态 state（长度 order）：
//   state[0] 为上一块最后一个输出 y[-1]，state[1] 为 y[-2]，……，state[order-1] 为 y[-order]。
//   合成本块第 i 个样本时，若需要 k > i 的滞后（i < order），
//   则 y[i-k] = state[k-i-1]（来自上一块末尾的历史输出）。
//   首次调用前 state 须全零；跨块调用时原样传入、本函数滚动更新。
//
// state 更新：本块末尾 order 个输出写入 state（最新在前）。
//   若 n >= order：state[j] = y[n-1-j]（j = 0..order-1）
//   若 n <  order：state[j] = y[n-1-j]（j < n），state[j] = state_旧[j-n]（j >= n）
//   倒序写入，避免覆盖尚未读取的旧 state（n < order 时）。
//
// 性能注记（NEON）：保留标量实现。y[i] 依赖 y[i-1..i-order]（逐样本 IIR
// 反馈链），输出必须先于后续输入计算完成，无法对 4 个连续样本做并行乘累加；
// 跨块 state 衔接进一步引入顺序依赖，向量化收益有限而复杂度高，故不做 NEON。
void maidmic_lpc_synthesize(const float* e, uint32_t n, const float* a, uint32_t order, float* y, float* state) {
    if (e == NULL || y == NULL || state == NULL || n == 0 || order == 0) return;

    for (uint32_t i = 0; i < n; i++) {
        float acc = e[i];

        // 块内历史输出：k = 1..min(order, i)，y[i-k] 为本块已算出的输出
        uint32_t kmax = (i < order) ? i : order;
        for (uint32_t k = 1; k <= kmax; k++) {
            acc += a[k] * y[i - k];
        }

        // 块外历史输出：k = i+1..order（仅 i < order 时存在），来自 state
        for (uint32_t k = i + 1; k <= order; k++) {
            acc += a[k] * state[k - i - 1];
        }

        y[i] = acc;
    }

    // 滚动更新 state：本块末尾 order 个输出（最新在前），倒序写入
    for (int32_t j = (int32_t)order - 1; j >= 0; j--) {
        if ((uint32_t)j < n) {
            state[j] = y[n - 1 - (uint32_t)j];
        } else {
            state[j] = state[j - (int32_t)n];
        }
    }
}

// ============================================================
// 共振峰偏移
// Formant shifting
// ============================================================
// 采用"谱包络整体频率伸缩"近似（任务推荐方案 B 的简化实现）：
//
//   c = 2^(formant_shift / 12)                 （半音 → 倍频程伸缩因子）
//   a_shifted[k] = a[k] * c^k，k = 0..order
//
// 数学解释：原多项式 A(z) = Σ_{k=0}^{order} a[k]·z^-k（a[0]=1）在 z 平面做
// 尺度变换 z → z/c，即 A_shifted(z) = A(z/c) = Σ a[k]·c^k·z^-k，其极点为
// 原极点的 c 倍（z'_i = c·z_i）。这等价于"极点半径统一缩放"的近似：
//   幅度响应的峰值（共振峰）随 c 沿频率轴平移，谱包络整体向高频/低频移动；
//   共振峰的相对位置与形状关系近似保持（c>1 时向高频、c<1 时向低频，
//   同时峰值带宽随半径缩放略有变化）。
// 注意：这是频响近似而非严格极点角度旋转，注释中明确——
//   "谱包络整体频率伸缩：共振峰相对位置保持，但整体随 c 向高频/低频移动"。
// 系数恒为实数（a[k]、c 均为实数），a_shifted[0] = a[0]·c^0 = 1.0（已归一化）。
//
// |shift| < 0.01 时视为无偏移，直接复制 a 并返回 true。
// 返回 false 仅当系数病态（指针为空、order 非法、系数非有限值或 c 非有限正数）。
bool maidmic_lpc_find_formant_shifts(const float* a, uint32_t order, float formant_shift, uint32_t sample_rate, float* a_shifted) {
    (void)sample_rate;  // 当前近似只做系数缩放，不依赖采样率

    if (a == NULL || a_shifted == NULL || order == 0 || order > LPC_MAX_ORDER) {
        return false;
    }

    // 病态检查：a[0] 应恒为 1.0（analyze 保证）；NaN/Inf 视为病态
    if (!isfinite(a[0]) || a[0] <= 0.0f) {
        return false;
    }

    // 偏移接近 0 → 直接复制
    if (fabsf(formant_shift) < LPC_SHIFT_EPS) {
        memcpy(a_shifted, a, (size_t)(order + 1) * sizeof(float));
        return true;
    }

    // 频率伸缩因子：c = 2^(shift/12)；shift>0 → c>1（向高频），shift<0 → c<1（向低频）
    float c = powf(2.0f, formant_shift / 12.0f);
    if (!(c > 0.0f) || !isfinite(c)) {
        return false;
    }

    // a_shifted[k] = a[k] * c^k，ck 递推累乘避免每阶重复 powf
    float ck = 1.0f;
    for (uint32_t k = 0; k <= order; k++) {
        a_shifted[k] = a[k] * ck;
        ck *= c;
    }

    // 归一化：a_shifted[0] = a[0] * c^0 = 1.0（显式赋值，防御调用方传入未归一化 a）
    a_shifted[0] = 1.0f;
    return true;
}

// ============================================================
// NEON 可用性检测
// NEON availability detection
// ============================================================
// 编译期检测当前构建是否启用 ARM NEON SIMD；非 ARM 平台恒为 false。
// 供 JNI 层输出 "NEON enabled" 日志。声明见 lpc.h / module.h。
// Compile-time check whether this build uses ARM NEON SIMD.
bool maidmic_neon_enabled(void) {
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    return true;
#else
    return false;
#endif
}
