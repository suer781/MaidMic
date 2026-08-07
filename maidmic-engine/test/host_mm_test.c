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
