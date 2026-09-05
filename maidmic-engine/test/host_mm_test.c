// maidmic-engine/test/host_mm_test.c
// MaidMic 引擎主机（非 Android）CLI 测试程序
//
// 用途：在 PC 上不依赖 Android/JNI 环境，直接验证 maidmic_engine 静态库的
//       管线 API（create/add_module/set_param/process/get_stats/reset/destroy）
//       与全部 DSP 模块的正确性（无 Android 依赖，纯 C）。
//
// 用法：
//   host_mm_test [--in <file.pcm>] [--pitch <半音>] [--formant <半音>]
//     --in      可选：S16LE 单声道 raw PCM 文件路径
//     --pitch   可选：VoiceTransform 变调量（半音，默认 0）
//     --formant 可选：VoiceTransform 共振峰偏移量（半音，默认 0）
//   未指定 --in 时，内部合成 3 秒测试信号（44.1kHz）：
//     220Hz 正弦 + 440Hz 二次谐波（幅度 1/2）+ 少量噪声
//
// 退出码：0 = 全部断言通过；1 = 断言失败；2 = 用法/IO 错误
//
// 构建（主机，任意 C 编译器 gcc/clang）：
//   cmake -B build-host && cmake --build build-host --target host_mm_test

#include "maidmic/pipeline.h"
#include "maidmic/pitch_detector.h"

#include <inttypes.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// ============================================================
// 模块描述符 extern 声明（定义在 src/dsp/*.c 与 src/voice/*.c，
// 与 src/api/maidmic_jni.cpp 中的声明保持一致）
// ============================================================
extern const maidmic_module_t maidmic_module_gain;
extern const maidmic_module_t maidmic_module_noisegate;
extern const maidmic_module_t maidmic_module_compressor;
extern const maidmic_module_t maidmic_module_bass;
extern const maidmic_module_t maidmic_module_treble;
extern const maidmic_module_t maidmic_module_reverb;
extern const maidmic_module_t maidmic_module_pitch;
extern const maidmic_module_t maidmic_module_formant;
extern const maidmic_module_t maidmic_module_voice_transform;
extern const maidmic_module_t maidmic_module_distortion;
extern const maidmic_module_t maidmic_module_echo;
extern const maidmic_module_t maidmic_module_limiter;
extern const maidmic_module_t maidmic_module_presence;
extern const maidmic_module_t maidmic_module_autotune;
extern const maidmic_module_t maidmic_module_voiceprint_mask;
extern const maidmic_module_t maidmic_module_vibrato;
extern const maidmic_module_t maidmic_module_chorus;
extern const maidmic_module_t maidmic_module_bitcrush;

// ============================================================
// 测试常量
// ============================================================
#define TEST_SAMPLE_RATE   44100u   // 采样率 Hz
#define TEST_CHANNELS      1u       // 单声道
#define TEST_BLOCK_SIZE    1024u    // 处理块长（帧）
#define TEST_DURATION_SEC  3u       // 合成信号时长（秒）
#define TEST_S16_SCALE     32768.0f // S16 → [-1,1] 归一化除数
#define MODULE_COUNT_EXPECTED 13u   // 10 个生效 + 3 个 bypass 演示

// 输出峰值断言容差（限制器阈值 -1dB ≈ 0.891，留出数值计算余量）
#define PEAK_TOLERANCE     1e-3f

// ============================================================
// 断言统计
// ============================================================
static int g_assert_total = 0;
static int g_assert_fail = 0;

static void check_assert(bool ok, const char* msg) {
    g_assert_total++;
    if (ok) {
        printf("  [PASS] %s\n", msg);
    } else {
        printf("  [FAIL] %s\n", msg);
        g_assert_fail++;
    }
}

// ============================================================
// 确定性伪随机（xorshift32）：保证跨平台/重复运行结果一致
// ============================================================
static uint32_t g_rng_state = 0x12345678u;

static float next_rand(void) {
    uint32_t x = g_rng_state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    g_rng_state = x;
    return ((float)(x & 0xFFFFu) / 32767.5f) - 1.0f;
}

// ============================================================
// 合成测试信号
// ============================================================

// 第 frame 帧合成样本：220Hz 基波 + 440Hz 谐波（幅度 1/2）+ 少量噪声
// 理论峰值约 0.75 + 0.01 < 0.8，不会触发过载
static float synth_sample(uint64_t frame) {
    double t = (double)frame / (double)TEST_SAMPLE_RATE;
    double s = sin(2.0 * M_PI * 220.0 * t) + 0.5 * sin(2.0 * M_PI * 440.0 * t);
    return (float)(0.5 * s) + 0.01f * next_rand();
}

// ============================================================
// S16LE 单声道 PCM 文件加载
// ============================================================

// 成功返回 malloc 的样本数组（调用方 free），失败返回 NULL
static int16_t* load_pcm_file(const char* path, uint32_t* out_count) {
    FILE* f = fopen(path, "rb");
    if (!f) return NULL;

    if (fseek(f, 0, SEEK_END) != 0) {
        fclose(f);
        return NULL;
    }
    long size = ftell(f);
    if (size < 0 || (size % 2) != 0 || size == 0) {
        fclose(f);
        return NULL;
    }
    if (fseek(f, 0, SEEK_SET) != 0) {
        fclose(f);
        return NULL;
    }

    size_t samples = (size_t)size / 2;
    int16_t* data = (int16_t*)malloc(samples * sizeof(int16_t));
    if (!data) {
        fclose(f);
        return NULL;
    }
    if (fread(data, sizeof(int16_t), samples, f) != samples) {
        free(data);
        fclose(f);
        return NULL;
    }
    fclose(f);

    *out_count = (uint32_t)samples;
    return data;
}

// ============================================================
// 管线构建
// ============================================================

// 设置 float 参数；失败时打印警告并计数（不影响继续运行）
static int g_param_warn = 0;

static void set_float_checked(maidmic_pipeline_t* p, uint32_t node_id,
                              const char* key, float value) {
    maidmic_param_t param;
    memset(&param, 0, sizeof(param));
    param.key = key;
    param.type = MAIDMIC_PARAM_FLOAT;
    param.value.as_float = value;
    if (!maidmic_pipeline_set_param(p, node_id, key, param)) {
        printf("  警告：设置参数 %s 失败（node_id=%u）\n", key, node_id);
        g_param_warn++;
    }
}

// 添加模块并检查失败（返回 0 表示失败）
static uint32_t add_module_checked(maidmic_pipeline_t* p, const maidmic_module_t* m) {
    uint32_t id = maidmic_pipeline_add_module(p, m);
    if (id == 0) {
        printf("  错误：添加模块 %s 失败\n", m->name);
    }
    return id;
}

// 构建完整处理链并设置参数：
//   Gain → NoiseGate → Compressor → Bass → Treble → Reverb
//   → VoiceTransform → Distortion → Echo → Limiter
// 可选演示：Presence / AutoTune / VoiceprintMask 加入但 bypass（不参与处理）
// 失败返回 NULL
static maidmic_pipeline_t* build_pipeline(float pitch_st, float formant_st) {
    maidmic_pipeline_t* p = maidmic_pipeline_create(MAIDMIC_PIPELINE_MODE_SIMPLE);
    if (!p) {
        printf("  错误：maidmic_pipeline_create 失败\n");
        return NULL;
    }

    uint32_t id;

    // 1. Gain：+1 dB
    id = add_module_checked(p, &maidmic_module_gain);
    if (id == 0) goto fail;
    set_float_checked(p, id, "gain_db", 1.0f);

    // 2. NoiseGate：阈值 -40dB，attack 10ms / release 200ms
    id = add_module_checked(p, &maidmic_module_noisegate);
    if (id == 0) goto fail;
    set_float_checked(p, id, "gate_threshold", -40.0f);
    set_float_checked(p, id, "gate_attack", 10.0f);
    set_float_checked(p, id, "gate_release", 200.0f);

    // 3. Compressor：阈值 -20dB，3:1，makeup +3dB
    id = add_module_checked(p, &maidmic_module_compressor);
    if (id == 0) goto fail;
    set_float_checked(p, id, "comp_threshold", -20.0f);
    set_float_checked(p, id, "comp_ratio", 3.0f);
    set_float_checked(p, id, "comp_makeup", 3.0f);

    // 4. Bass：+2dB 低频增益
    id = add_module_checked(p, &maidmic_module_bass);
    if (id == 0) goto fail;
    set_float_checked(p, id, "bass_db", 2.0f);

    // 5. Treble：+1dB 高频增益
    id = add_module_checked(p, &maidmic_module_treble);
    if (id == 0) goto fail;
    set_float_checked(p, id, "treble_db", 1.0f);

    // 6. Reverb：湿声比例 15%
    id = add_module_checked(p, &maidmic_module_reverb);
    if (id == 0) goto fail;
    set_float_checked(p, id, "reverb_mix", 0.15f);

    // 7. VoiceTransform：命令行 pitch/formant（默认 0/0）
    id = add_module_checked(p, &maidmic_module_voice_transform);
    if (id == 0) goto fail;
    set_float_checked(p, id, "pitch_semitones", pitch_st);
    set_float_checked(p, id, "formant_shift", formant_st);

    // 8. Distortion：轻度失真 5%
    id = add_module_checked(p, &maidmic_module_distortion);
    if (id == 0) goto fail;
    set_float_checked(p, id, "distortion", 0.05f);

    // 9. Echo：250ms 延时，0.3 衰减
    id = add_module_checked(p, &maidmic_module_echo);
    if (id == 0) goto fail;
    set_float_checked(p, id, "echo_delay_ms", 250.0f);
    set_float_checked(p, id, "echo_decay", 0.3f);

    // 10. Limiter：阈值 -1dB，release 100ms（保证输出峰值不超过满刻度）
    id = add_module_checked(p, &maidmic_module_limiter);
    if (id == 0) goto fail;
    set_float_checked(p, id, "limiter_threshold", -1.0f);
    set_float_checked(p, id, "limiter_release", 100.0f);

    // ---- 可选模块演示：加入但 bypass，不参与处理 ----
    // 与 JNI 默认管线行为一致（默认加入但 bypass，由上层按需启用）。
    // 此处仅演示 add_module + set_module_bypass 路径；如需实测可放开注释。
    id = add_module_checked(p, &maidmic_module_presence);
    if (id == 0) goto fail;
    maidmic_pipeline_set_module_bypass(p, id, true);

    id = add_module_checked(p, &maidmic_module_autotune);
    if (id == 0) goto fail;
    maidmic_pipeline_set_module_bypass(p, id, true);

    id = add_module_checked(p, &maidmic_module_voiceprint_mask);
    if (id == 0) goto fail;
    maidmic_pipeline_set_module_bypass(p, id, true);

    return p;

fail:
    maidmic_pipeline_destroy(p);
    return NULL;
}

// ============================================================
// 全零直通一致性检查
// ============================================================

// 处理链是齐次的（零输入 → 零输出）：用于捕获 DC 偏置、未初始化内存、
// 状态污染等缺陷。在送入正式信号前执行（此时模块内部状态均为零）。
static bool zero_passthrough_check(maidmic_pipeline_t* p) {
    float in[TEST_BLOCK_SIZE] = {0};
    float out[TEST_BLOCK_SIZE];
    maidmic_buffer_t in_buf, out_buf;

    memset(&in_buf, 0, sizeof(in_buf));
    memset(&out_buf, 0, sizeof(out_buf));

    in_buf.data = in;
    in_buf.owned = false;
    in_buf.meta.sample_rate = TEST_SAMPLE_RATE;
    in_buf.meta.channels = TEST_CHANNELS;
    in_buf.meta.format = MAIDMIC_SAMPLE_F32;
    in_buf.meta.frame_count = TEST_BLOCK_SIZE;
    in_buf.data_bytes = TEST_BLOCK_SIZE * (uint32_t)sizeof(float);

    out_buf.data = out;
    out_buf.owned = false;

    if (!maidmic_pipeline_process(p, &in_buf, &out_buf)) return false;

    for (uint32_t i = 0; i < TEST_BLOCK_SIZE; i++) {
        if (fabsf(out[i]) > 1e-6f) return false;
    }
    return true;
}

// ============================================================
// Pitch 模块跨块连续性回归测试
// ============================================================
// 背景：旧实现"每块从读位置 0 独立重采样"，块边界波形断裂 → 高频"卡卡"；
// 重写后为跨块连续变调（历史环 + 拼接交叉淡化），块边界保持波形连续。
//
// 方法：以对齐 App 的 256 帧块长（AudioLoopback.bufferFrames）处理 440Hz
// 正弦（幅度 0.5），比较"块内相邻样本差"与"跨块边界相邻样本差"的最大值：
//   - 连续实现：两者量级一致（≈ 0.04，拼接淡化仅使斜率略陡）；
//   - 断裂实现：边界差 ≈ 信号幅度（0.5）>> 内部差。
// 升调（+4 半音）与降调（-4 半音）各测一遍，另做"激活变调下的全零直通"
// 与 NaN 检查。F32 单声道。

#define PITCH_TEST_SR        48000u   // 对齐 App SAMPLE_RATE
#define PITCH_TEST_BLOCK     256u     // 对齐 App bufferFrames（卡顿最敏感块长）
#define PITCH_TEST_SECONDS   2u
#define PITCH_TEST_FREQ      440.0f

static bool pitch_run_continuity(float semitones) {
    maidmic_pipeline_t* p = maidmic_pipeline_create(MAIDMIC_PIPELINE_MODE_SIMPLE);
    if (!p) {
        printf("  错误：pitch 测试管线创建失败\n");
        return false;
    }
    uint32_t node = maidmic_pipeline_add_module(p, &maidmic_module_pitch);
    if (node == 0) {
        maidmic_pipeline_destroy(p);
        printf("  错误：添加 Pitch 模块失败\n");
        return false;
    }
    maidmic_param_t param;
    memset(&param, 0, sizeof(param));
    param.key = "pitch_semitones";
    param.type = MAIDMIC_PARAM_FLOAT;
    param.value.as_float = semitones;
    maidmic_pipeline_set_param(p, node, "pitch_semitones", param);

    // ---- 激活变调下的全零直通（不得引入 DC/数值噪声）----
    {
        float zin[PITCH_TEST_BLOCK] = {0};
        float zout[PITCH_TEST_BLOCK];
        maidmic_buffer_t ib, ob;
        memset(&ib, 0, sizeof(ib));
        memset(&ob, 0, sizeof(ob));
        ib.data = zin;
        ib.data_bytes = (uint32_t)(PITCH_TEST_BLOCK * sizeof(float));
        ib.meta.sample_rate = PITCH_TEST_SR;
        ib.meta.channels = 1;
        ib.meta.format = MAIDMIC_SAMPLE_F32;
        ib.meta.frame_count = PITCH_TEST_BLOCK;
        ob.data = zout;
        ob.data_bytes = ib.data_bytes;
        ob.meta = ib.meta;
        maidmic_pipeline_process(p, &ib, &ob);
        for (uint32_t i = 0; i < PITCH_TEST_BLOCK; i++) {
            if (fabsf(zout[i]) > 1e-6f) {
                printf("  失败：全零输入产生非零输出 %g @ %u\n", zout[i], (unsigned)i);
                maidmic_pipeline_destroy(p);
                return false;
            }
        }
    }

    float* in = (float*)malloc(PITCH_TEST_BLOCK * sizeof(float));
    float* out = (float*)malloc(PITCH_TEST_BLOCK * sizeof(float));
    float* prev_tail = (float*)malloc(PITCH_TEST_BLOCK * sizeof(float));
    if (!in || !out || !prev_tail) {
        printf("  错误：内存分配失败\n");
        free(in); free(out); free(prev_tail);
        maidmic_pipeline_destroy(p);
        return false;
    }

    maidmic_buffer_t in_buf, out_buf;
    memset(&in_buf, 0, sizeof(in_buf));
    memset(&out_buf, 0, sizeof(out_buf));
    in_buf.data = in;
    in_buf.data_bytes = (uint32_t)(PITCH_TEST_BLOCK * sizeof(float));
    in_buf.meta.sample_rate = PITCH_TEST_SR;
    in_buf.meta.channels = 1;
    in_buf.meta.format = MAIDMIC_SAMPLE_F32;
    out_buf.data = out;
    out_buf.data_bytes = in_buf.data_bytes;
    out_buf.meta = in_buf.meta;

    const uint64_t total = (uint64_t)PITCH_TEST_SR * PITCH_TEST_SECONDS;
    uint64_t done = 0;
    uint32_t prev_n = 0;
    bool has_prev = false;
    double max_interior = 0.0, max_boundary = 0.0;
    bool ok = true;

    while (done < total) {
        uint32_t n = PITCH_TEST_BLOCK;
        if ((uint64_t)n > total - done) n = (uint32_t)(total - done);

        for (uint32_t i = 0; i < n; i++) {
            double t = (double)(done + i) / (double)PITCH_TEST_SR;
            in[i] = 0.5f * (float)sin(2.0 * M_PI * (double)PITCH_TEST_FREQ * t);
        }
        in_buf.meta.frame_count = n;
        in_buf.data_bytes = n * (uint32_t)sizeof(float);
        out_buf.meta = in_buf.meta;
        out_buf.data_bytes = in_buf.data_bytes;

        if (!maidmic_pipeline_process(p, &in_buf, &out_buf)) {
            printf("  失败：Pitch 处理返回 false\n");
            ok = false;
            break;
        }

        // NaN / Inf 检查
        for (uint32_t i = 0; i < n; i++) {
            if (!isfinite(out[i])) {
                printf("  失败：输出非有限值 %g @ %u\n", out[i], (unsigned)i);
                ok = false;
            }
        }
        if (!ok) break;

        // 块内相邻样本差
        for (uint32_t i = 1; i < n; i++) {
            double d = fabs((double)out[i] - (double)out[i - 1]);
            if (d > max_interior) max_interior = d;
        }
        // 跨块边界相邻样本差（上一块末尾 vs 本块开头）
        if (has_prev && n > 0) {
            double d = fabs((double)out[0] - (double)prev_tail[prev_n - 1]);
            if (d > max_boundary) max_boundary = d;
        }
        if (n > 0) {
            memcpy(prev_tail, out, n * sizeof(float));
            prev_n = n;
            has_prev = true;
        }
        done += n;
    }

    // 容差：拼接淡化会轻微加大斜率，边界差允许到内部差的数倍；
    // 旧实现边界断裂 ≈ 幅度 0.5，必然超过该容差。
    double tol = 6.0 * max_interior + 0.02;
    printf("  内部最大差分=%.5f  边界最大差分=%.5f  (容差=%.5f)\n",
           max_interior, max_boundary, tol);
    if (max_boundary > tol) {
        printf("  失败：块边界差分超出容差（存在块边界断裂）\n");
        ok = false;
    }

    free(in); free(out); free(prev_tail);
    maidmic_pipeline_destroy(p);
    return ok;
}

static bool pitch_continuity_test(void) {
    bool ok = true;
    printf("  升调 +4 半音:\n");
    if (!pitch_run_continuity(4.0f)) ok = false;
    printf("  降调 -4 半音:\n");
    if (!pitch_run_continuity(-4.0f)) ok = false;
    return ok;
}

// ============================================================
// VoiceTransform v3 变调比例测试（TD-PSOLA）
// ============================================================
// 220Hz 谐波信号 → +7 半音：输出基频应 ≈ 220·2^(7/12) ≈ 329.6Hz（±5%）。
// 同时验证输出有限（无 NaN/Inf、无爆炸）。

#define VT_TEST_SR      48000u
#define VT_TEST_BLOCK   256u
#define VT_TEST_SECONDS 2u

extern const maidmic_module_t maidmic_module_voice_transform;

// 基频检测（复用引擎检测器，作用于指定段）
static bool vt_f0_of(const float* x, uint32_t n, float* out_f0) {
    bool voiced = false;
    const float f0 = maidmic_detect_pitch(x, n, VT_TEST_SR, &voiced);
    *out_f0 = f0;
    return voiced;
}

// 单独管线跑 VoiceTransform：in → out（均为 total 样本，已分配）
static bool vt_run(const float* in, float* out, uint32_t total, float pitch, float formant) {
    maidmic_pipeline_t* p = maidmic_pipeline_create(MAIDMIC_PIPELINE_MODE_SIMPLE);
    if (!p) return false;
    const uint32_t node = maidmic_pipeline_add_module(p, &maidmic_module_voice_transform);
    if (node == 0) {
        maidmic_pipeline_destroy(p);
        return false;
    }
    maidmic_param_t param;
    memset(&param, 0, sizeof(param));
    param.type = MAIDMIC_PARAM_FLOAT;
    param.key = "pitch_semitones";
    param.value.as_float = pitch;
    maidmic_pipeline_set_param(p, node, "pitch_semitones", param);
    param.key = "formant_shift";
    param.value.as_float = formant;
    maidmic_pipeline_set_param(p, node, "formant_shift", param);

    float ib[VT_TEST_BLOCK], ob[VT_TEST_BLOCK];
    maidmic_buffer_t bi, bo;
    memset(&bi, 0, sizeof(bi));
    memset(&bo, 0, sizeof(bo));
    bi.data = ib;
    bi.owned = false;
    bi.meta.sample_rate = VT_TEST_SR;
    bi.meta.channels = 1;
    bi.meta.format = MAIDMIC_SAMPLE_F32;
    bo.data = ob;
    bo.owned = false;
    bo.meta = bi.meta;

    bool ok = true;
    for (uint32_t off = 0; off < total; off += VT_TEST_BLOCK) {
        uint32_t nn = (total - off < VT_TEST_BLOCK) ? total - off : VT_TEST_BLOCK;
        memcpy(ib, in + off, nn * sizeof(float));
        bi.meta.frame_count = nn;
        bi.data_bytes = nn * sizeof(float);
        bo.data_bytes = nn * sizeof(float);
        bo.meta = bi.meta;
        if (!maidmic_pipeline_process(p, &bi, &bo)) {
            ok = false;
            break;
        }
        for (uint32_t i = 0; i < nn; i++) {
            if (!isfinite(ob[i])) {
                printf("  失败：输出非有限值 %g @ %u\n", ob[i], (unsigned)(off + i));
                ok = false;
                break;
            }
        }
        if (!ok) break;
        memcpy(out + off, ob, nn * sizeof(float));
    }
    maidmic_pipeline_destroy(p);
    return ok;
}

// 谐波测试信号（含底噪，模拟真实语音激励）
static uint32_t vt_rng = 0x12345678u;
static float vt_noise(void) {
    vt_rng = vt_rng * 1664525u + 1013904223u;
    return ((float)((vt_rng >> 16) & 0xFFu) / 128.0f) - 1.0f;
}
static float vt_harmonic(uint64_t frame, float f0) {
    const double t = (double)frame / (double)VT_TEST_SR;
    double s = 0.0;
    for (int k = 1; k <= 20; k++) {
        s += sin(2.0 * M_PI * (double)f0 * (double)k * t) / (double)k;
    }
    return 0.25f * (float)s + 0.003f * vt_noise();
}

static bool vt_pitch_ratio_test(void) {
    const uint32_t total = VT_TEST_SR * VT_TEST_SECONDS;
    float* in = (float*)malloc(total * sizeof(float));
    float* out = (float*)malloc(total * sizeof(float));
    if (!in || !out) {
        free(in); free(out);
        return false;
    }

    // +7 半音：220 → 329.6Hz
    for (uint64_t i = 0; i < total; i++) in[i] = vt_harmonic(i, 220.0f);
    bool ok = vt_run(in, out, total, 7.0f, 0.0f);
    float f0_in = 0.0f, f0_out = 0.0f;
    const bool v_in = vt_f0_of(in + VT_TEST_SR, VT_TEST_SR, &f0_in);
    const bool v_out = vt_f0_of(out + VT_TEST_SR, VT_TEST_SR, &f0_out);
    const float expect = 220.0f * powf(2.0f, 7.0f / 12.0f);
    printf("  变调 +7st: in_f0=%.1f(voiced=%d) out_f0=%.1f(voiced=%d) expect=%.1f\n",
           f0_in, v_in, f0_out, v_out, expect);
    if (!ok || !v_out || fabsf(f0_out - expect) > 0.05f * expect) {
        printf("  失败：变调比例偏差过大\n");
        ok = false;
    }

    // -5 半音：220 → 164.8Hz
    for (uint64_t i = 0; i < total; i++) in[i] = vt_harmonic(i, 220.0f);
    ok = vt_run(in, out, total, -5.0f, 0.0f) && ok;
    vt_f0_of(out + VT_TEST_SR, VT_TEST_SR, &f0_out);
    const float expect2 = 220.0f * powf(2.0f, -5.0f / 12.0f);
    printf("  变调 -5st: out_f0=%.1f expect=%.1f\n", f0_out, expect2);
    if (fabsf(f0_out - expect2) > 0.05f * expect2) {
        printf("  失败：变调比例偏差过大\n");
        ok = false;
    }

    free(in);
    free(out);
    return ok;
}

// ============================================================
// VoiceTransform v3 共振峰偏移测试（抽取域极点旋转）
// ============================================================
// 双共振峰谐波信号（F1=400/F2=1600）+4 半音：输出包络峰应按
// 2^(4/12) ≈ 1.26 移动（±20%）。用 Goertzel 能量找谱峰。

// 在 [f_lo, f_hi] 内以 5Hz 步进 Goertzel 找能量峰
static float vt_find_peak(const float* x, uint32_t n, float f_lo, float f_hi) {
    float best_e = -1.0f, best_f = 0.0f;
    for (float f = f_lo; f <= f_hi; f += 5.0f) {
        const float w = 2.0f * (float)M_PI * f / (float)VT_TEST_SR;
        const float coeff = 2.0f * cosf(w);
        float s1 = 0.0f, s2 = 0.0f;
        for (uint32_t i = 0; i < n; i++) {
            const float s0 = x[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        const float e = s1 * s1 + s2 * s2 - coeff * s1 * s2;
        if (e > best_e) {
            best_e = e;
            best_f = f;
        }
    }
    return best_f;
}

static float vt_formant_amp(float f, float F1, float F2) {
    const float a1 = 1.0f / (1.0f + (f - F1) * (f - F1) / (120.0f * 120.0f));
    const float a2 = 0.8f / (1.0f + (f - F2) * (f - F2) / (200.0f * 200.0f));
    return a1 + a2;
}

static float vt_vowel(uint64_t frame, float f0, float F1, float F2) {
    const double t = (double)frame / (double)VT_TEST_SR;
    double s = 0.0;
    for (int k = 1; k <= 60; k++) {
        const float fk = f0 * (float)k;
        if (fk > 0.45f * (float)VT_TEST_SR) break;
        s += (double)vt_formant_amp(fk, F1, F2) * sin(2.0 * M_PI * (double)f0 * (double)k * t);
    }
    return 0.3f * (float)s + 0.003f * vt_noise();
}

static bool vt_formant_shift_test(void) {
    const uint32_t total = VT_TEST_SR * VT_TEST_SECONDS;
    float* in = (float*)malloc(total * sizeof(float));
    float* out = (float*)malloc(total * sizeof(float));
    if (!in || !out) {
        free(in); free(out);
        return false;
    }

    // +4 半音：F1 400 → 504Hz，F2 1600 → 2016Hz
    for (uint64_t i = 0; i < total; i++) in[i] = vt_vowel(i, 120.0f, 400.0f, 1600.0f);
    bool ok = vt_run(in, out, total, 0.0f, 4.0f);
    const float pk_in1 = vt_find_peak(in + VT_TEST_SR, VT_TEST_SR, 250.0f, 700.0f);
    const float pk_out1 = vt_find_peak(out + VT_TEST_SR, VT_TEST_SR, 250.0f, 900.0f);
    const float expect1 = 400.0f * powf(2.0f, 4.0f / 12.0f);
    printf("  共振峰 +4st F1: in=%.0f out=%.0f expect=%.0f\n", pk_in1, pk_out1, expect1);
    if (!ok || fabsf(pk_out1 - expect1) > 0.2f * expect1) {
        printf("  失败：F1 偏移偏差过大\n");
        ok = false;
    }

    // -3 半音：F1 700 → 589Hz
    for (uint64_t i = 0; i < total; i++) in[i] = vt_vowel(i, 140.0f, 700.0f, 1900.0f);
    ok = vt_run(in, out, total, 0.0f, -3.0f) && ok;
    const float pk_out2 = vt_find_peak(out + VT_TEST_SR, VT_TEST_SR, 350.0f, 700.0f);
    const float expect2 = 700.0f * powf(2.0f, -3.0f / 12.0f);
    printf("  共振峰 -3st F1: out=%.0f expect=%.0f\n", pk_out2, expect2);
    if (fabsf(pk_out2 - expect2) > 0.2f * expect2) {
        printf("  失败：F1 偏移偏差过大\n");
        ok = false;
    }

    free(in);
    free(out);
    return ok;
}

// ============================================================
// Reverb v2（Freeverb 式）稳定性测试
// ============================================================
// 单位脉冲 + 白噪声输入 5 秒：输出必须有界、有限，且输入停止后
// 尾音能量单调衰减（前 0.5s 尾音能量 > 2s 后尾音能量）。

static bool reverb_stability_test(void) {
    const uint32_t total = VT_TEST_SR * 5u;
    float* in = (float*)calloc(total, sizeof(float));
    float* out = (float*)calloc(total, sizeof(float));
    if (!in || !out) {
        free(in); free(out);
        return false;
    }

    vt_rng = 0x87654321u;
    in[0] = 0.9f;  // 单位脉冲
    for (uint32_t i = 1; i < VT_TEST_SR * 2u; i++) {
        in[i] = 0.5f * vt_noise();  // 2 秒白噪声激励，之后静音观察尾音
    }

    maidmic_pipeline_t* p = maidmic_pipeline_create(MAIDMIC_PIPELINE_MODE_SIMPLE);
    if (!p) {
        free(in); free(out);
        return false;
    }
    const uint32_t node = maidmic_pipeline_add_module(p, &maidmic_module_reverb);
    if (node == 0) {
        maidmic_pipeline_destroy(p);
        free(in); free(out);
        return false;
    }
    maidmic_param_t param;
    memset(&param, 0, sizeof(param));
    param.type = MAIDMIC_PARAM_FLOAT;
    param.key = "reverb_mix";
    param.value.as_float = 0.4f;
    maidmic_pipeline_set_param(p, node, "reverb_mix", param);

    float ib[VT_TEST_BLOCK], ob[VT_TEST_BLOCK];
    maidmic_buffer_t bi, bo;
    memset(&bi, 0, sizeof(bi));
    memset(&bo, 0, sizeof(bo));
    bi.data = ib; bi.owned = false;
    bi.meta.sample_rate = VT_TEST_SR;
    bi.meta.channels = 1;
    bi.meta.format = MAIDMIC_SAMPLE_F32;
    bo.data = ob; bo.owned = false; bo.meta = bi.meta;

    bool ok = true;
    double e_early = 0.0, e_late = 0.0;
    for (uint32_t off = 0; off < total; off += VT_TEST_BLOCK) {
        uint32_t nn = (total - off < VT_TEST_BLOCK) ? total - off : VT_TEST_BLOCK;
        memcpy(ib, in + off, nn * sizeof(float));
        bi.meta.frame_count = nn;
        bi.data_bytes = nn * sizeof(float);
        bo.data_bytes = nn * sizeof(float);
        bo.meta = bi.meta;
        if (!maidmic_pipeline_process(p, &bi, &bo)) {
            ok = false;
            break;
        }
        for (uint32_t i = 0; i < nn; i++) {
            const float s = ob[i];
            if (!isfinite(s) || fabsf(s) > 4.0f) {
                printf("  失败：混响输出异常 %g @ %u\n", s, (unsigned)(off + i));
                ok = false;
                break;
            }
            // 尾音能量：输入停止（2s）后 0.2~0.7s vs 2.5~3.5s
            const uint32_t abs_i = off + i;
            if (abs_i > 2u * VT_TEST_SR + (uint32_t)(0.2f * VT_TEST_SR) &&
                abs_i < 2u * VT_TEST_SR + (uint32_t)(0.7f * VT_TEST_SR)) {
                e_early += (double)s * s;
            }
            if (abs_i > 2u * VT_TEST_SR + (uint32_t)(2.5f * VT_TEST_SR) &&
                abs_i < 2u * VT_TEST_SR + (uint32_t)(3.5f * VT_TEST_SR)) {
                e_late += (double)s * s;
            }
        }
        if (!ok) break;
        memcpy(out + off, ob, nn * sizeof(float));
    }
    maidmic_pipeline_destroy(p);

    printf("  混响尾音能量: 早期=%.4g 后期=%.4g（应衰减）\n", e_early, e_late);
    if (ok && e_early <= e_late) {
        printf("  失败：尾音未正常衰减\n");
        ok = false;
    }

    free(in);
    free(out);
    return ok;
}

// ============================================================
// Vibrato / Chorus / Bitcrusher 基础行为测试
// ============================================================

// 单模块跑一段输入（mix/params 由调用方设置），返回输出 + 有限性检查
typedef bool (*module_param_setup)(maidmic_pipeline_t* p, uint32_t node);

static bool fx_run_module(const maidmic_module_t* mod, module_param_setup setup,
                          const float* in, float* out, uint32_t total) {
    maidmic_pipeline_t* p = maidmic_pipeline_create(MAIDMIC_PIPELINE_MODE_SIMPLE);
    if (!p) return false;
    const uint32_t node = maidmic_pipeline_add_module(p, mod);
    if (node == 0 || (setup && !setup(p, node))) {
        maidmic_pipeline_destroy(p);
        return false;
    }
    float ib[VT_TEST_BLOCK], ob[VT_TEST_BLOCK];
    maidmic_buffer_t bi, bo;
    memset(&bi, 0, sizeof(bi));
    memset(&bo, 0, sizeof(bo));
    bi.data = ib; bi.owned = false;
    bi.meta.sample_rate = VT_TEST_SR;
    bi.meta.channels = 1;
    bi.meta.format = MAIDMIC_SAMPLE_F32;
    bo.data = ob; bo.owned = false; bo.meta = bi.meta;

    bool ok = true;
    for (uint32_t off = 0; off < total; off += VT_TEST_BLOCK) {
        uint32_t nn = (total - off < VT_TEST_BLOCK) ? total - off : VT_TEST_BLOCK;
        memcpy(ib, in + off, nn * sizeof(float));
        bi.meta.frame_count = nn;
        bi.data_bytes = nn * sizeof(float);
        bo.data_bytes = nn * sizeof(float);
        bo.meta = bi.meta;
        if (!maidmic_pipeline_process(p, &bi, &bo)) {
            ok = false;
            break;
        }
        for (uint32_t i = 0; i < nn; i++) {
            if (!isfinite(ob[i])) {
                ok = false;
                break;
            }
        }
        if (!ok) break;
        memcpy(out + off, ob, nn * sizeof(float));
    }
    maidmic_pipeline_destroy(p);
    return ok;
}

static bool fx_setup_vibrato(maidmic_pipeline_t* p, uint32_t node) {
    maidmic_param_t param;
    memset(&param, 0, sizeof(param));
    param.type = MAIDMIC_PARAM_FLOAT;
    param.key = "vibrato_depth";
    param.value.as_float = 1.0f;  // ±1 半音
    return maidmic_pipeline_set_param(p, node, "vibrato_depth", param);
}

static bool fx_setup_chorus(maidmic_pipeline_t* p, uint32_t node) {
    maidmic_param_t param;
    memset(&param, 0, sizeof(param));
    param.type = MAIDMIC_PARAM_FLOAT;
    param.key = "chorus_mix";
    param.value.as_float = 0.8f;
    return maidmic_pipeline_set_param(p, node, "chorus_mix", param);
}

static bool fx_setup_bitcrush(maidmic_pipeline_t* p, uint32_t node) {
    maidmic_param_t param;
    memset(&param, 0, sizeof(param));
    param.type = MAIDMIC_PARAM_FLOAT;
    param.key = "bitcrush_bits";
    param.value.as_float = 4.0f;   // 4 bit：量化台阶 1/8
    if (!maidmic_pipeline_set_param(p, node, "bitcrush_bits", param)) return false;
    param.key = "bitcrush_mix";
    param.value.as_float = 1.0f;
    return maidmic_pipeline_set_param(p, node, "bitcrush_mix", param);
}

// 颤音：输出基频应随 LFO 波动（相邻窗口基频差 > 2%，验证调制生效）
static bool fx_vibrato_test(void) {
    const uint32_t total = VT_TEST_SR * 2u;
    float* in = (float*)malloc(total * sizeof(float));
    float* out = (float*)malloc(total * sizeof(float));
    if (!in || !out) {
        free(in); free(out);
        return false;
    }
    for (uint64_t i = 0; i < total; i++) in[i] = vt_harmonic(i, 440.0f);
    const bool ok = fx_run_module(&maidmic_module_vibrato, fx_setup_vibrato, in, out, total);
    bool passed = ok;
    if (passed) {
        // 分四窗测基频，要求波动幅度 > 2%
        float fmin = 1e9f, fmax = 0.0f;
        for (uint32_t w = 0; w < 4u; w++) {
            float f0 = 0.0f;
            vt_f0_of(out + total / 2u + w * (VT_TEST_SR / 4u), VT_TEST_SR / 8u, &f0);
            if (f0 > 0.0f) {
                if (f0 < fmin) fmin = f0;
                if (f0 > fmax) fmax = f0;
            }
        }
        printf("  颤音基频波动: %.1f ~ %.1f Hz（±1 半音 ≈ ±26Hz）\n", fmin, fmax);
        if (fmax - fmin < 8.0f) {
            printf("  失败：颤音调制深度不足\n");
            passed = false;
        }
    }
    free(in);
    free(out);
    return passed;
}

// 合唱：输出非零、有界、与输入不同
static bool fx_chorus_test(void) {
    const uint32_t total = VT_TEST_SR;
    float* in = (float*)malloc(total * sizeof(float));
    float* out = (float*)malloc(total * sizeof(float));
    if (!in || !out) {
        free(in); free(out);
        return false;
    }
    for (uint64_t i = 0; i < total; i++) in[i] = vt_harmonic(i, 300.0f);
    const bool ok = fx_run_module(&maidmic_module_chorus, fx_setup_chorus, in, out, total);
    double diff = 0.0;
    for (uint32_t i = 0; i < total; i++) {
        diff += fabs((double)out[i] - (double)in[i]);
        if (fabsf(out[i]) > 2.0f) { diff = -1.0; break; }
    }
    printf("  合唱输出与输入平均差: %.4g\n", diff / total);
    const bool passed = ok && diff > 0.0 && diff / total > 1e-4;
    free(in);
    free(out);
    return passed;
}

// 降比特：4bit 全湿输出应落在量化台阶（1/8 的整数倍附近）
static bool fx_bitcrush_test(void) {
    const uint32_t total = VT_TEST_SR;
    float* in = (float*)malloc(total * sizeof(float));
    float* out = (float*)malloc(total * sizeof(float));
    if (!in || !out) {
        free(in); free(out);
        return false;
    }
    for (uint64_t i = 0; i < total; i++) in[i] = vt_harmonic(i, 300.0f);
    const bool ok = fx_run_module(&maidmic_module_bitcrush, fx_setup_bitcrush, in, out, total);
    // 跳过启动 0.1s，检查量化：out·8 与最近整数偏差 ≤ 0.06（hold + 量化容差）
    bool passed = ok;
    double max_dev = 0.0;
    for (uint32_t i = VT_TEST_SR / 10u; i < total; i++) {
        const float q = out[i] * 8.0f;
        const float dev = fabsf(q - floorf(q + 0.5f));
        if (dev > max_dev) max_dev = dev;
    }
    printf("  降比特量化最大偏差: %.4f（应 ≈ 0）\n", max_dev);
    if (max_dev > 0.06f) passed = false;
    free(in);
    free(out);
    return passed;
}

// ============================================================
// 用法说明
// ============================================================
static void print_usage(const char* argv0) {
    printf("用法: %s [--in <file.pcm>] [--pitch <半音>] [--formant <半音>]\n", argv0);
    printf("  --in <file>     可选：S16LE 单声道 raw PCM 输入文件\n");
    printf("  --pitch <半音>  可选：VoiceTransform 变调量（默认 0）\n");
    printf("  --formant <半音> 可选：VoiceTransform 共振峰偏移（默认 0）\n");
    printf("  未指定 --in 时内部合成 3 秒测试信号（220Hz+440Hz+噪声，44.1kHz）\n");
    printf("  退出码：0=断言全部通过，1=断言失败，2=用法/IO 错误\n");
}

// ============================================================
// 主流程
// ============================================================
int main(int argc, char** argv) {
    const char* in_file = NULL;
    float pitch_st = 0.0f;
    float formant_st = 0.0f;

    // ---- 解析命令行参数 ----
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--in") == 0 && i + 1 < argc) {
            in_file = argv[++i];
        } else if (strcmp(argv[i], "--pitch") == 0 && i + 1 < argc) {
            pitch_st = (float)atof(argv[++i]);
        } else if (strcmp(argv[i], "--formant") == 0 && i + 1 < argc) {
            formant_st = (float)atof(argv[++i]);
        } else {
            print_usage(argv[0]);
            return 2;
        }
    }

    printf("=== MaidMic 引擎主机测试 (host_mm_test) ===\n");
    printf("采样率 %u Hz / %u 声道 / 块长 %u 帧\n",
           (unsigned)TEST_SAMPLE_RATE, (unsigned)TEST_CHANNELS, (unsigned)TEST_BLOCK_SIZE);
    printf("变声参数：pitch %+.1f 半音, formant %+.1f 半音\n", pitch_st, formant_st);
    if (in_file) {
        printf("输入：文件 %s\n", in_file);
    } else {
        printf("输入：内部合成信号（%u 秒，220Hz+440Hz+噪声）\n", (unsigned)TEST_DURATION_SEC);
    }

    // ---- 加载输入文件（可选）----
    int16_t* pcm = NULL;
    uint32_t pcm_count = 0;
    if (in_file) {
        pcm = load_pcm_file(in_file, &pcm_count);
        if (!pcm) {
            printf("错误：无法读取输入文件 %s（需为 S16LE 单声道 raw PCM）\n", in_file);
            return 2;
        }
        printf("文件样本数：%u（%.2f 秒 @ %u Hz）\n",
               (unsigned)pcm_count, (double)pcm_count / (double)TEST_SAMPLE_RATE,
               (unsigned)TEST_SAMPLE_RATE);
    }

    // ---- 构建管线 ----
    maidmic_pipeline_t* pipe = build_pipeline(pitch_st, formant_st);
    if (!pipe) {
        free(pcm);
        return 1;
    }
    check_assert(maidmic_pipeline_get_module_count(pipe) == MODULE_COUNT_EXPECTED,
                 "模块链完整（10 个生效 + 3 个 bypass 演示）");

    // ---- 全零直通一致性检查（在正式信号之前）----
    check_assert(zero_passthrough_check(pipe), "全零输入 → 全零输出（直通一致性）");

    // ---- 分配处理缓冲 ----
    float* in = (float*)malloc(TEST_BLOCK_SIZE * sizeof(float));
    float* out = (float*)malloc(TEST_BLOCK_SIZE * sizeof(float));
    if (!in || !out) {
        printf("错误：内存分配失败\n");
        free(in);
        free(out);
        free(pcm);
        maidmic_pipeline_destroy(pipe);
        return 1;
    }

    maidmic_buffer_t in_buf, out_buf;
    memset(&in_buf, 0, sizeof(in_buf));
    memset(&out_buf, 0, sizeof(out_buf));
    in_buf.data = in;
    in_buf.owned = false;
    in_buf.meta.sample_rate = TEST_SAMPLE_RATE;
    in_buf.meta.channels = TEST_CHANNELS;
    in_buf.meta.format = MAIDMIC_SAMPLE_F32;
    out_buf.data = out;
    out_buf.owned = false;

    // ---- 分块处理 + 累计统计 ----
    const uint64_t total_input = pcm
        ? (uint64_t)pcm_count
        : (uint64_t)TEST_DURATION_SEC * (uint64_t)TEST_SAMPLE_RATE;

    // 基线统计：zero_passthrough_check 也走同一 pipe，会多累计一块（1024 帧），
    // 记录基线并在最后减去，使 stats 只统计下面的主循环（与 frames_processed 口径一致）。
    uint64_t base_ns = 0, base_frames = 0, base_calls = 0;
    maidmic_pipeline_get_stats(pipe, &base_ns, &base_frames, &base_calls);

    uint64_t frames_processed = 0;
    uint64_t seq = 0;
    uint64_t blocks = 0;
    double out_energy = 0.0;
    float out_peak = 0.0f;

    while (frames_processed < total_input) {
        uint32_t n = TEST_BLOCK_SIZE;
        if ((uint64_t)n > total_input - frames_processed) {
            n = (uint32_t)(total_input - frames_processed);
        }

        // 填充输入块（文件 S16 → float，或合成信号）
        for (uint32_t i = 0; i < n; i++) {
            in[i] = pcm ? (float)pcm[frames_processed + i] / TEST_S16_SCALE
                        : synth_sample(frames_processed + i);
        }

        in_buf.meta.frame_count = n;
        in_buf.meta.sequence = (uint32_t)seq;
        in_buf.meta.timestamp_ns = frames_processed * 1000000000ULL / TEST_SAMPLE_RATE;
        in_buf.data_bytes = n * (uint32_t)sizeof(float);
        out_buf.meta = in_buf.meta;
        out_buf.data_bytes = in_buf.data_bytes;

        if (!maidmic_pipeline_process(pipe, &in_buf, &out_buf)) {
            printf("错误：第 %" PRIu64 " 块处理失败（frame_count=%u）\n", seq, n);
            break;
        }

        // 累计输出能量（平方和）与峰值
        for (uint32_t i = 0; i < n; i++) {
            float s = out[i];
            out_energy += (double)s * (double)s;
            float a = fabsf(s);
            if (a > out_peak) out_peak = a;
        }

        frames_processed += n;
        blocks++;
        seq++;
    }

    // ---- 处理性能统计（公共 API 仅暴露管线级累计值）----
    uint64_t stats_ns = 0, stats_frames = 0, stats_calls = 0;
    maidmic_pipeline_get_stats(pipe, &stats_ns, &stats_frames, &stats_calls);
    // 减去主循环前的基线（zero_passthrough_check 累计的一块），口径与 frames_processed 一致
    stats_ns -= base_ns;
    stats_frames -= base_frames;
    stats_calls -= base_calls;

    printf("\n处理统计（maidmic_pipeline_get_stats）:\n");
    printf("  处理块数 : %" PRIu64 "\n", blocks);
    printf("  调用次数 : %" PRIu64 "\n", stats_calls);
    printf("  处理帧数 : %" PRIu64 "（每声道样本数之和，单声道=帧数）\n", stats_frames);
    printf("  总耗时   : %" PRIu64 " ns (%.3f ms)\n", stats_ns, (double)stats_ns / 1e6);
    if (stats_calls > 0) {
        printf("  平均每块 : %.3f ms\n", (double)stats_ns / 1e6 / (double)stats_calls);
    }
    // 注：公共 API 不提供逐模块耗时，以下打印模块链明细作为替代
    printf("模块链明细:\n");
    uint32_t count = maidmic_pipeline_get_module_count(pipe);
    for (uint32_t i = 0; i < count; i++) {
        const maidmic_dag_node_t* node = maidmic_pipeline_get_module_at(pipe, i);
        if (node && node->module) {
            printf("  [%u] %s (node_id=%u, %s)\n", i, node->module->name,
                   node->node_id, node->bypass ? "bypass" : "active");
        }
    }

    // ---- 断言 ----
    printf("\n断言结果:\n");
    check_assert(frames_processed > 0, "处理总帧数 > 0");
    check_assert(out_energy > 0.0, "输出能量 > 0（非全静音）");
    check_assert(out_peak <= 1.0f + PEAK_TOLERANCE, "输出峰值 <= 1.0（限制器生效）");
    check_assert(stats_frames == frames_processed, "管线统计帧数与本地累计一致");
    if (g_param_warn > 0) {
        printf("  注意：%d 个参数设置未生效\n", g_param_warn);
    }

    // ---- Pitch 模块跨块连续性回归（核心"卡卡"修复验证）----
    printf("\nPitch 模块跨块连续性测试（256 帧块，对齐 App 实时块长）:\n");
    check_assert(pitch_continuity_test(), "Pitch 跨块连续（升调/降调，无块边界断裂）");

    // ---- VoiceTransform v3：变调比例（TD-PSOLA）----
    printf("\nVoiceTransform v3 变调比例测试（TD-PSOLA，共振峰保持）:\n");
    check_assert(vt_pitch_ratio_test(), "VoiceTransform 变调比例（+7st/-5st，±5%）");

    // ---- VoiceTransform v3：共振峰偏移（抽取域极点旋转）----
    printf("\nVoiceTransform v3 共振峰偏移测试（抽取域 LPC 极点旋转）:\n");
    check_assert(vt_formant_shift_test(), "VoiceTransform 共振峰偏移（+4st/-3st，±20%）");

    // ---- Reverb v2：Freeverb 式稳定性 ----
    printf("\nReverb v2 稳定性测试（8 组合器 + 4 全通）:\n");
    check_assert(reverb_stability_test(), "Reverb 输出有界且尾音衰减");

    // ---- 新效果模块 ----
    printf("\nVibrato / Chorus / Bitcrusher 行为测试:\n");
    check_assert(fx_vibrato_test(), "Vibrato 音高调制生效");
    check_assert(fx_chorus_test(), "Chorus 输出有效且与输入不同");
    check_assert(fx_bitcrush_test(), "Bitcrusher 4bit 量化台阶正确");

    // ---- 收尾：reset 后销毁，释放内存 ----
    maidmic_pipeline_reset(pipe);
    maidmic_pipeline_destroy(pipe);
    free(in);
    free(out);
    free(pcm);

    printf("\n=== 结果: %s（断言 %d/%d 通过） ===\n",
           g_assert_fail == 0 ? "PASS" : "FAIL",
           g_assert_total - g_assert_fail, g_assert_total);
    return g_assert_fail == 0 ? 0 : 1;
}
