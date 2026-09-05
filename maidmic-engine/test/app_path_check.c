// app_path_check.c — 完全模拟 App 真实调用路径的集成验证
//
// 复刻 VoicePackRecorder 的精确行为：
//   - S16 单声道 48kHz
//   - 每块 1024 样本（PROCESS_BLOCK_SAMPLES）
//   - 9 模块默认链（Gain→Comp→Bass→Treble→Reverb→VoiceTransform→Dist→Echo→Bitcrush）
//     与 maidmic_jni.cpp ensure_default_pipeline 一致，参数与 Kotlin initDefaultChain 一致
//   - 变声参数 pitch=+7 / formant=+3（萝莉预设）
//   - 录音结束 → 直接 finish（无排空）vs 排空 N 块全零
//
// 验证：
//   1. 变调比例在真实链路中生效（输出 F0 ≈ 220·2^(7/12)）
//   2. 【关键】无排空时输出尾部内容被截断（延迟线吞掉结尾）
//   3. 排空后尾部内容完整保留
#include "maidmic/pipeline.h"
#include "maidmic/pitch_detector.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static FILE* g_out = NULL;
#define P(...) do { if (g_out) { fprintf(g_out, __VA_ARGS__); fflush(g_out); } printf(__VA_ARGS__); fflush(stdout); } while(0)

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

extern const maidmic_module_t maidmic_module_gain;
extern const maidmic_module_t maidmic_module_compressor;
extern const maidmic_module_t maidmic_module_bass;
extern const maidmic_module_t maidmic_module_treble;
extern const maidmic_module_t maidmic_module_reverb;
extern const maidmic_module_t maidmic_module_voice_transform;
extern const maidmic_module_t maidmic_module_distortion;
extern const maidmic_module_t maidmic_module_echo;
extern const maidmic_module_t maidmic_module_bitcrush;

#define SR 48000u
#define BLOCK 1024u          // = VoicePackRecorder.PROCESS_BLOCK_SAMPLES
#define DURATION_SEC 3u
#define TOTAL (SR * DURATION_SEC)

static uint32_t rng = 0x12345678u;
static float noise(void) {
    rng = rng * 1664525u + 1013904223u;
    return ((float)((rng >> 16) & 0xFFu) / 128.0f) - 1.0f;
}

// 语音样式的测试信号：120Hz 谐波堆 + 共振峰整形 + 包络
// 关键：最后 0.5 秒有内容（模拟"结尾的字"），用于验证截断
static float envelope(uint64_t i) {
    // 0~2.5s 常规，2.5~3.0s 仍有内容（会话结尾）
    const float t = (float)i / SR;
    if (t > 2.95f) return 0.0f;  // 最后 50ms 静音（松手）
    return 1.0f;
}

static float gen(uint64_t i) {
    static double ph = 0.0;
    ph += 2.0 * M_PI * 120.0 / SR;
    if (ph > 2.0 * M_PI) ph -= 2.0 * M_PI;
    double s = 0.0;
    for (int k = 1; k <= 40; k++) {
        const float fk = 120.0f * k;
        if (fk > 8000.0f) break;
        const float a1 = 1.0f / (1.0f + (fk - 400.0f) * (fk - 400.0f) / (120.0f * 120.0f));
        const float a2 = 0.8f / (1.0f + (fk - 1600.0f) * (fk - 1600.0f) / (200.0f * 200.0f));
        s += (a1 + a2) * sin(ph * k);
    }
    return (float)(0.3 * s * envelope(i)) + 0.003f * noise() * envelope(i);
}

// 构建 App 精确默认链（对齐 maidmic_jni.cpp + Kotlin initDefaultChain）
static maidmic_pipeline_t* build_app_chain(void) {
    maidmic_pipeline_t* p = maidmic_pipeline_create(MAIDMIC_PIPELINE_MODE_SIMPLE);
    if (!p) return NULL;
    maidmic_param_t pm;
    memset(&pm, 0, sizeof(pm));
    pm.type = MAIDMIC_PARAM_FLOAT;

    uint32_t n;
    n = maidmic_pipeline_add_module(p, &maidmic_module_gain);
    pm.value.as_float = 0.0f;  // gain_db = 0（默认）
    maidmic_pipeline_set_param(p, n, "gain_db", pm);

    n = maidmic_pipeline_add_module(p, &maidmic_module_compressor);
    pm.value.as_float = -20.0f; maidmic_pipeline_set_param(p, n, "comp_threshold", pm);
    pm.value.as_float = 2.0f;   maidmic_pipeline_set_param(p, n, "comp_ratio", pm);
    pm.value.as_float = 0.0f;   maidmic_pipeline_set_param(p, n, "comp_makeup", pm);

    n = maidmic_pipeline_add_module(p, &maidmic_module_bass);
    pm.value.as_float = 0.0f; maidmic_pipeline_set_param(p, n, "bass_db", pm);

    n = maidmic_pipeline_add_module(p, &maidmic_module_treble);
    pm.value.as_float = 0.0f; maidmic_pipeline_set_param(p, n, "treble_db", pm);

    n = maidmic_pipeline_add_module(p, &maidmic_module_reverb);
    pm.value.as_float = 0.0f; maidmic_pipeline_set_param(p, n, "reverb_mix", pm);

    n = maidmic_pipeline_add_module(p, &maidmic_module_voice_transform);
    pm.value.as_float = 7.0f; maidmic_pipeline_set_param(p, n, "pitch_semitones", pm);
    pm.value.as_float = 3.0f; maidmic_pipeline_set_param(p, n, "formant_shift", pm);

    n = maidmic_pipeline_add_module(p, &maidmic_module_distortion);
    pm.value.as_float = 0.0f; maidmic_pipeline_set_param(p, n, "distortion", pm);

    n = maidmic_pipeline_add_module(p, &maidmic_module_echo);
    pm.value.as_float = 0.0f; maidmic_pipeline_set_param(p, n, "echo_delay_ms", pm);
    pm.value.as_float = 0.0f; maidmic_pipeline_set_param(p, n, "echo_decay", pm);

    n = maidmic_pipeline_add_module(p, &maidmic_module_bitcrush);
    pm.value.as_float = 16.0f; maidmic_pipeline_set_param(p, n, "bitcrush_bits", pm);
    pm.value.as_float = 1.0f;  maidmic_pipeline_set_param(p, n, "bitcrush_down", pm);
    pm.value.as_float = 0.0f;  maidmic_pipeline_set_param(p, n, "bitcrush_mix", pm);

    return p;
}

// 按 App 方式处理：S16 单声道 1024 块；drain_blocks > 0 时结尾送全零块排空
static int16_t* run_app_path(const int16_t* in, uint32_t total, uint32_t drain_blocks) {
    maidmic_pipeline_t* p = build_app_chain();
    if (!p) return NULL;

    int16_t* out = (int16_t*)calloc((size_t)(total + (size_t)drain_blocks * BLOCK), sizeof(int16_t));
    int16_t ib[BLOCK], ob[BLOCK];
    maidmic_buffer_t bi, bo;
    memset(&bi, 0, sizeof(bi));
    memset(&bo, 0, sizeof(bo));
    bi.data = ib; bi.owned = false;
    bi.meta.sample_rate = SR;
    bi.meta.channels = 1;
    bi.meta.format = MAIDMIC_SAMPLE_S16;
    bo.data = ob; bo.owned = false; bo.meta = bi.meta;

    for (uint32_t off = 0; off < total; off += BLOCK) {
        // 与 App 一致：最后一块可能不满（processAudio 按实际字节数处理）
        const uint32_t n = (total - off < BLOCK) ? (total - off) : BLOCK;
        memcpy(ib, in + off, n * sizeof(int16_t));
        bi.meta.frame_count = n;
        bi.data_bytes = n * 2;
        bo.meta = bi.meta;
        bo.data_bytes = n * 2;
        maidmic_pipeline_process(p, &bi, &bo);
        memcpy(out + off, ob, n * sizeof(int16_t));
    }
    // 排空：送全零块（S16 静音）
    memset(ib, 0, sizeof(ib));
    for (uint32_t d = 0; d < drain_blocks; d++) {
        maidmic_pipeline_process(p, &bi, &bo);
        memcpy(out + total + d * BLOCK, ob, BLOCK * sizeof(int16_t));
    }
    maidmic_pipeline_destroy(p);
    return out;
}

// 段内基频
static float f0_of(const int16_t* x, uint32_t n) {
    float* f = (float*)malloc(n * sizeof(float));
    for (uint32_t i = 0; i < n; i++) f[i] = x[i] / 32768.0f;
    bool voiced = false;
    const float f0 = maidmic_detect_pitch(f, n, SR, &voiced);
    free(f);
    return voiced ? f0 : 0.0f;
}

// 段内 RMS 能量
static double rms_of(const int16_t* x, uint32_t n) {
    double e = 0.0;
    for (uint32_t i = 0; i < n; i++) e += (double)x[i] * x[i];
    return sqrt(e / n);
}

int main(void) {
    g_out = fopen("app_path_result.txt", "w");
    int fails = 0;

    // 生成 S16 输入
    int16_t* in = (int16_t*)malloc(TOTAL * sizeof(int16_t));
    for (uint32_t i = 0; i < TOTAL; i++) {
        float v = gen(i) * 32767.0f;
        if (v > 32767.0f) v = 32767.0f;
        if (v < -32768.0f) v = -32768.0f;
        in[i] = (int16_t)v;
    }

    // ---- 1. 变调在真实链路生效 ----
    int16_t* out = run_app_path(in, TOTAL, 0);
    const float f_in = f0_of(in + SR, SR);
    const float f_out = f0_of(out + 2 * SR, SR);  // 跳过启动延迟
    const float expect = 120.0f * powf(2.0f, 7.0f / 12.0f);  // gen() 基频为 120Hz
    P("1) 真实链路变调: in_f0=%.1f out_f0=%.1f expect=%.1f\n", f_in, f_out, expect);
    if (fabsf(f_out - expect) > 0.06f * expect) {
        P("   FAIL: 真实链路变调失效\n"); fails++;
    } else P("   PASS\n");

    // ---- 2. 无排空：结尾截断验证 ----
    // 输入最后 0.4~0.3s（2.6~2.7s 处）有内容；输出流中该内容出现在
    // 相同时间偏移 + 延迟处。无排空时，输出 2.6~2.7s 处应有内容吗？
    // out[m] = f(in[m - D])：in[2.6s..2.7s] 出现在 out[2.6s+D..2.7s+D]，
    // 无排空时 out 只到 3.0s，D≈0.08~0.17s → out[2.68s..3.0s] 缺失。
    // 检查输出最后 300ms 的能量：无排空时应显著低于输入对应段。
    const uint32_t tail_n = (uint32_t)(0.3f * SR);
    const double in_tail_rms = rms_of(in + TOTAL - tail_n - (uint32_t)(0.05f * SR), tail_n);
    const double out_tail_rms = rms_of(out + TOTAL - tail_n, tail_n);
    P("2) 无排空结尾: in_tail_rms=%.0f out_tail_rms=%.0f (比值=%.2f，远小于1=截断)\n",
           in_tail_rms, out_tail_rms, out_tail_rms / (in_tail_rms + 1e-9));
    if (out_tail_rms > 0.7 * in_tail_rms) {
        P("   FAIL: 预期截断但未观察到（测试前提失效）\n"); fails++;
    } else P("   PASS（证实无排空会截断结尾）\n");
    free(out);

    // ---- 3. 排空 8 块（8192 样本 ≈ 170ms）：结尾完整 ----
    const uint32_t DRAIN = 8;
    int16_t* out2 = run_app_path(in, TOTAL, DRAIN);
    // 输入尾部内容出现在输出 TOTAL−tail−0.05s + D 位置附近；
    // 直接验证：out2 的 [TOTAL, TOTAL + DRAIN*BLOCK) 排空段应包含真实内容能量，
    // 且 out2 结尾（最后 50ms + 排空后段）能量完整。
    const uint32_t drain_samples = DRAIN * BLOCK;
    double drain_rms = 0.0;
    for (uint32_t i = 0; i < drain_samples; i++) {
        drain_rms += (double)out2[TOTAL + i] * out2[TOTAL + i];
    }
    drain_rms = sqrt(drain_rms / drain_samples);
    // 排空段应包含被延迟线扣住的尾段内容（RMS 明显非零）
    P("3) 排空段 RMS=%.0f（应 > 0，含尾段内容）\n", drain_rms);
    if (drain_rms < 0.05 * in_tail_rms) {
        P("   FAIL: 排空段近乎静音，尾部内容未冲出\n"); fails++;
    } else P("   PASS（尾段内容已冲出）\n");

    // 完整性：out2 中 in 的内容窗口相关（取 2.0s 处输入窗，在 out2 中
    // 搜索最大相关的延迟偏移，应 ≈ 链路延迟 0.05~0.25s）
    const uint32_t win = (uint32_t)(0.2f * SR);
    const uint32_t probe = (uint32_t)(1.5f * SR);
    double best_corr = -1e30; uint32_t best_lag = 0;
    for (uint32_t lag = 0; lag < (uint32_t)(0.4f * SR); lag += 8) {
        double c = 0.0;
        for (uint32_t i = 0; i < win; i += 4) {
            c += (double)in[probe + i] * out2[probe + lag + i];
        }
        if (c > best_corr) { best_corr = c; best_lag = lag; }
    }
    P("4) 链路延迟: %u 样本 (%.1f ms)（应 2000~12000，相关法受周期性干扰为近似值）\n", best_lag, best_lag * 1000.0f / SR);
    if (best_lag < 1500 || best_lag > 12000) {
        P("   FAIL: 链路延迟超出预期范围\n"); fails++;
    } else P("   PASS\n");
    free(out2);
    free(in);

    P("=== app_path_check: %s ===\n", fails == 0 ? "ALL PASS" : "FAIL");
    return fails;
}
