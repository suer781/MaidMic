// vt_check.c — voice_transform v3 数值验证（主机侧）
// 1) 变调比例：200Hz 谐波信号 +7st → 输出 f0 ≈ 200·2^(7/12) ≈ 299.7Hz
// 2) 恒等重构：清音噪声 + 变调 0 → 输出 ≈ 延迟直通
// 3) 共振峰偏移：双共振峰谐波信号 +4st → 包络峰按 2^(4/12) ≈ 1.26 移动
#include "maidmic/pipeline.h"
#include "maidmic/pitch_detector.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

extern const maidmic_module_t maidmic_module_voice_transform;

#define SR 48000u
#define BLOCK 256u

static float g_phase = 0.0f;

// 谐波堆叠信号（f0 基频，n_harm 个谐波，幅度 1/k）
static float gen_harmonic(float f0, uint32_t nh) {
    g_phase += 2.0f * (float)M_PI * f0 / (float)SR;
    if (g_phase > 2.0f * (float)M_PI) g_phase -= 2.0f * (float)M_PI;
    float s = 0.0f;
    for (uint32_t k = 1; k <= nh; k++) {
        s += sinf(g_phase * (float)k) / (float)k;
    }
    return 0.25f * s;
}

// 包络整形：双共振峰（F1/F2）谐波幅度加权
static float formant_amp(float f, float F1, float F2) {
    const float a1 = 1.0f / (1.0f + (f - F1) * (f - F1) / (120.0f * 120.0f));
    const float a2 = 0.8f / (1.0f + (f - F2) * (f - F2) / (200.0f * 200.0f));
    return a1 + a2;
}

static uint32_t g_rng = 0x12345678u;
static float next_noise(void) {
    g_rng = g_rng * 1664525u + 1013904223u;
    return ((float)((g_rng >> 16) & 0xFFu) / 128.0f) - 1.0f;
}

static float g_phase2 = 0.0f;
static float gen_vowel(float f0, float F1, float F2, uint32_t nh) {
    g_phase2 += 2.0f * (float)M_PI * f0 / (float)SR;
    if (g_phase2 > 2.0f * (float)M_PI) g_phase2 -= 2.0f * (float)M_PI;
    float s = 0.0f;
    for (uint32_t k = 1; k <= nh; k++) {
        const float fk = f0 * (float)k;
        if (fk > 0.45f * (float)SR) break;
        s += formant_amp(fk, F1, F2) * sinf(g_phase2 * (float)k);
    }
    // 微小宽带噪声（模拟真实录音底噪；完美周期信号会使 LPC 退化）
    return 0.3f * s + 0.003f * next_noise();
}

// 处理整段信号，返回输出（malloc）
static float* run_vt(const float* in, uint32_t n, float pitch, float formant) {
    maidmic_pipeline_t* p = maidmic_pipeline_create(MAIDMIC_PIPELINE_MODE_SIMPLE);
    uint32_t node = maidmic_pipeline_add_module(p, &maidmic_module_voice_transform);
    maidmic_param_t param;
    memset(&param, 0, sizeof(param));
    param.type = MAIDMIC_PARAM_FLOAT;
    param.key = "pitch_semitones"; param.value.as_float = pitch;
    maidmic_pipeline_set_param(p, node, "pitch_semitones", param);
    param.key = "formant_shift"; param.value.as_float = formant;
    maidmic_pipeline_set_param(p, node, "formant_shift", param);

    float* out = (float*)malloc(n * sizeof(float));
    float ib[BLOCK], ob[BLOCK];
    maidmic_buffer_t bi, bo;
    memset(&bi, 0, sizeof(bi)); memset(&bo, 0, sizeof(bo));
    bi.data = ib; bi.owned = false;
    bi.meta.sample_rate = SR; bi.meta.channels = 1;
    bi.meta.format = MAIDMIC_SAMPLE_F32;
    bo.data = ob; bo.owned = false; bo.meta = bi.meta;
    for (uint32_t off = 0; off < n; off += BLOCK) {
        uint32_t nn = (n - off < BLOCK) ? n - off : BLOCK;
        memcpy(ib, in + off, nn * sizeof(float));
        bi.meta.frame_count = nn;
        bi.data_bytes = nn * sizeof(float);
        bo.data_bytes = nn * sizeof(float);
        bo.meta = bi.meta;
        maidmic_pipeline_process(p, &bi, &bo);
        memcpy(out + off, ob, nn * sizeof(float));
    }
    maidmic_pipeline_destroy(p);
    return out;
}

// 单频 Goertzel 能量
static double goertzel_energy(const float* x, uint32_t n, float f) {
    const float w = 2.0f * (float)M_PI * f / (float)SR;
    const float coeff = 2.0f * cosf(w);
    float s1 = 0.0f, s2 = 0.0f;
    for (uint32_t i = 0; i < n; i++) {
        const float s0 = x[i] + coeff * s1 - s2;
        s2 = s1; s1 = s0;
    }
    return (double)s1 * s1 + (double)s2 * s2 - (double)coeff * s1 * s2;
}

// 在 [f_lo, f_hi] 内按 Goertzel 能量找谱峰
static float find_peak(const float* x, uint32_t n, float f_lo, float f_hi) {
    float best_e = -1.0f, best_f = 0.0f;
    for (float f = f_lo; f <= f_hi; f += 5.0f) {
        const double e = goertzel_energy(x, n, f);
        if (e > best_e) { best_e = (float)e; best_f = f; }
    }
    return best_f;
}

// 频带能量比：E[num_lo,num_hi] / E[den_lo,den_hi]（5Hz 步进求和）
static double band_ratio(const float* x, uint32_t n,
                         float num_lo, float num_hi,
                         float den_lo, float den_hi) {
    double num = 0.0, den = 0.0;
    for (float f = num_lo; f <= num_hi; f += 5.0f) num += goertzel_energy(x, n, f);
    for (float f = den_lo; f <= den_hi; f += 5.0f) den += goertzel_energy(x, n, f);
    return num / (den + 1e-9);
}

// 输出段基频（自相关检测器）
static float out_f0(const float* x, uint32_t n) {
    bool voiced = false;
    return maidmic_detect_pitch(x, n, SR, &voiced);
}

int main(void) {
    int fails = 0;

    // ---- 1. 变调比例 +7st ----
    {
        const uint32_t n = SR * 2;
        float* in = (float*)malloc(n * sizeof(float));
        g_phase = 0.0f;
        for (uint32_t i = 0; i < n; i++) in[i] = gen_harmonic(200.0f, 20);
        float* out = run_vt(in, n, 7.0f, 0.0f);
        const float f_in = out_f0(in + SR, SR);
        const float f_out = out_f0(out + SR, SR);  // 跳过启动延迟段
        const float expect = 200.0f * powf(2.0f, 7.0f / 12.0f);
        printf("pitch +7st: in_f0=%.1f out_f0=%.1f expect=%.1f ratio=%.3f\n",
               f_in, f_out, expect, f_out / f_in);
        if (fabsf(f_out - expect) > 0.05f * expect) {
            printf("  FAIL: 变调比例偏差 > 5%%\n"); fails++;
        } else printf("  PASS\n");
        free(in); free(out);
    }

    // ---- 2. 变调比例 -5st ----
    {
        const uint32_t n = SR * 2;
        float* in = (float*)malloc(n * sizeof(float));
        g_phase = 0.0f;
        for (uint32_t i = 0; i < n; i++) in[i] = gen_harmonic(220.0f, 20);
        float* out = run_vt(in, n, -5.0f, 0.0f);
        const float f_out = out_f0(out + SR, SR);
        const float expect = 220.0f * powf(2.0f, -5.0f / 12.0f);
        printf("pitch -5st: out_f0=%.1f expect=%.1f\n", f_out, expect);
        if (fabsf(f_out - expect) > 0.05f * expect) {
            printf("  FAIL: 变调比例偏差 > 5%%\n"); fails++;
        } else printf("  PASS\n");
        free(in); free(out);
    }

    // ---- 3. 共振峰偏移 +4st ----
    {
        const uint32_t n = SR * 2;
        float* in = (float*)malloc(n * sizeof(float));
        float* out;
        g_phase2 = 0.0f;
        for (uint32_t i = 0; i < n; i++) in[i] = gen_vowel(120.0f, 400.0f, 1600.0f, 60);
        out = run_vt(in, n, 0.0f, 4.0f);
        // F1：能量应从低频带（原 400Hz 附近）向高频带（偏移后 ~504Hz 附近）转移。
        // 用频带能量比而非最强谐波，避免 f0=120Hz 谐波间距（120Hz）与共振峰带宽
        // 相当导致"最强谐波不跟随包络峰"的误判。
        const double in_r1  = band_ratio(in + SR, SR, 450.0f, 900.0f, 250.0f, 450.0f);
        const double out_r1 = band_ratio(out + SR, SR, 450.0f, 900.0f, 250.0f, 450.0f);
        const float expect1 = 400.0f * powf(2.0f, 4.0f / 12.0f);
        printf("formant +4st F1: in_peak=%.0f out_peak=%.0f expect=%.0f band_ratio %.3f→%.3f\n",
               find_peak(in + SR, SR, 250.0f, 700.0f),
               find_peak(out + SR, SR, 250.0f, 900.0f), expect1, in_r1, out_r1);
        if (!(out_r1 > in_r1 * 1.3)) {
            printf("  FAIL: F1 能量未向高频带转移\n"); fails++;
        } else printf("  PASS\n");
        // F2：同上，原 1600Hz → 偏移后 ~2016Hz
        const double in_r2  = band_ratio(in + SR, SR, 1800.0f, 2600.0f, 1300.0f, 1800.0f);
        const double out_r2 = band_ratio(out + SR, SR, 1800.0f, 2600.0f, 1300.0f, 1800.0f);
        const float expect2 = 1600.0f * powf(2.0f, 4.0f / 12.0f);
        printf("formant +4st F2: in_peak=%.0f out_peak=%.0f expect=%.0f band_ratio %.3f→%.3f\n",
               find_peak(in + SR, SR, 1200.0f, 2200.0f),
               find_peak(out + SR, SR, 1400.0f, 2600.0f), expect2, in_r2, out_r2);
        if (!(out_r2 > in_r2 * 1.3)) {
            printf("  FAIL: F2 能量未向高频带转移\n"); fails++;
        } else printf("  PASS\n");
        free(in); free(out);
    }

    // ---- 4. 共振峰偏移 -3st（降包络）----
    {
        const uint32_t n = SR * 2;
        float* in = (float*)malloc(n * sizeof(float));
        float* out;
        g_phase2 = 0.0f;
        for (uint32_t i = 0; i < n; i++) in[i] = gen_vowel(140.0f, 700.0f, 1900.0f, 60);
        out = run_vt(in, n, 0.0f, -3.0f);
        // F1：能量应从高频带（原 700Hz 附近）向低频带（偏移后 ~589Hz 附近）转移
        const double in_r  = band_ratio(in + SR, SR, 450.0f, 650.0f, 650.0f, 850.0f);
        const double out_r = band_ratio(out + SR, SR, 450.0f, 650.0f, 650.0f, 850.0f);
        const float expect1 = 700.0f * powf(2.0f, -3.0f / 12.0f);
        printf("formant -3st F1: out_peak=%.0f expect=%.0f band_ratio %.3f→%.3f\n",
               find_peak(out + SR, SR, 350.0f, 700.0f), expect1, in_r, out_r);
        if (!(out_r > in_r * 1.3)) {
            printf("  FAIL: F1 能量未向低频带转移\n"); fails++;
        } else printf("  PASS\n");
        free(in); free(out);
    }

    // ---- 5. 音高+共振峰联合 ----
    {
        const uint32_t n = SR * 2;
        float* in = (float*)malloc(n * sizeof(float));
        g_phase2 = 0.0f;
        for (uint32_t i = 0; i < n; i++) in[i] = gen_vowel(120.0f, 450.0f, 1500.0f, 60);
        float* out = run_vt(in, n, 7.0f, 2.5f);
        const float f_out = out_f0(out + SR, SR);
        const float expect_f = 120.0f * powf(2.0f, 7.0f / 12.0f);
        // F1：450Hz → ~520Hz，能量向高频带转移
        const double in_r  = band_ratio(in + SR, SR, 500.0f, 700.0f, 350.0f, 500.0f);
        const double out_r = band_ratio(out + SR, SR, 500.0f, 700.0f, 350.0f, 500.0f);
        const float expect1 = 450.0f * powf(2.0f, 2.5f / 12.0f);
        printf("joint +7st/+2.5st: f0=%.1f (exp %.1f) F1_peak=%.0f (exp %.0f) band_ratio %.3f→%.3f\n",
               f_out, expect_f, find_peak(out + SR, SR, 400.0f, 900.0f), expect1, in_r, out_r);
        bool ok = fabsf(f_out - expect_f) < 0.06f * expect_f && (out_r > in_r * 1.2);
        if (!ok) { printf("  FAIL\n"); fails++; } else printf("  PASS\n");
        free(in); free(out);
    }

    // ---- 6. 长时运行幽灵回声（OLA 环回绕后无 0.5s 前残响）----
    // 输入前 0.4s 为 200Hz 谐波音，之后静音（共 1.5s）。共振峰 OLA 路径激活时，
    // 低速率环 VT_LOW_CAP=8192（@48k 抽取后 ≈0.5s）会回绕。若发射消费过的槽
    // 不清零，旧 OLA 值会叠加到新帧，使输出在 0.5s 后混入"幽灵"残响。
    // 链路延迟 ≈0.12s，故检查 [1.0s, 1.5s] 段输出 RMS 应接近零。
    {
        const uint32_t n = SR * 3 / 2;              // 1.5s
        const uint32_t tone_n = (uint32_t)(0.4f * SR);
        float* in = (float*)calloc(n, sizeof(float));
        float* out;
        g_phase = 0.0f;
        for (uint32_t i = 0; i < tone_n; i++) in[i] = gen_harmonic(200.0f, 20);
        out = run_vt(in, n, 0.0f, 2.0f);            // 激活共振峰 OLA 路径

        const uint32_t late_start = (uint32_t)(1.0f * SR);
        double e = 0.0;
        for (uint32_t i = late_start; i < n; i++) e += (double)out[i] * out[i];
        const double rms = sqrt(e / (double)(n - late_start));
        double e_in = 0.0;
        for (uint32_t i = 0; i < tone_n; i++) e_in += (double)in[i] * in[i];
        const double in_rms = sqrt(e_in / (double)tone_n);
        const double thresh = in_rms * 1e-3;
        printf("ghost echo: late RMS=%.6g in_rms=%.4f thresh=%.4g\n", rms, in_rms, thresh);
        if (rms > thresh) {
            printf("  FAIL: 0.5s 后输出存在幽灵残响\n"); fails++;
        } else printf("  PASS\n");
        free(in); free(out);
    }

    printf("=== vt_check: %s ===\n", fails == 0 ? "ALL PASS" : "FAIL");
    return fails;
}
