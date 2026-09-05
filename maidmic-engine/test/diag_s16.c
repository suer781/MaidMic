// diag_s16.c — 逐模块 S16/1024块 路径诊断：找出堆破坏的元凶
// 每个模块单独：挂载 → 设默认参数 → 处理 141 块 S16 → 销毁 → 报告
#include "maidmic/pipeline.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

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
#define BLOCK 1024u
#define NBLOCKS 141u

typedef struct {
    const maidmic_module_t* mod;
    const char* name;
    void (*setup)(maidmic_pipeline_t* p, uint32_t node);
} entry_t;

static void sp(maidmic_pipeline_t* p, uint32_t n, const char* k, float v) {
    maidmic_param_t pm;
    memset(&pm, 0, sizeof(pm));
    pm.type = MAIDMIC_PARAM_FLOAT;
    pm.value.as_float = v;
    maidmic_pipeline_set_param(p, n, k, pm);
}

static void s_gain(maidmic_pipeline_t* p, uint32_t n) { sp(p, n, "gain_db", 0.0f); }
static void s_comp(maidmic_pipeline_t* p, uint32_t n) {
    sp(p, n, "comp_threshold", -20.0f);
    sp(p, n, "comp_ratio", 2.0f);
    sp(p, n, "comp_makeup", 0.0f);
}
static void s_bass(maidmic_pipeline_t* p, uint32_t n) { sp(p, n, "bass_db", 0.0f); }
static void s_treble(maidmic_pipeline_t* p, uint32_t n) { sp(p, n, "treble_db", 0.0f); }
static void s_reverb(maidmic_pipeline_t* p, uint32_t n) { sp(p, n, "reverb_mix", 0.0f); }
static void s_vt(maidmic_pipeline_t* p, uint32_t n) {
    sp(p, n, "pitch_semitones", 7.0f);
    sp(p, n, "formant_shift", 3.0f);
}
static void s_dist(maidmic_pipeline_t* p, uint32_t n) { sp(p, n, "distortion", 0.0f); }
static void s_echo(maidmic_pipeline_t* p, uint32_t n) {
    sp(p, n, "echo_delay_ms", 0.0f);
    sp(p, n, "echo_decay", 0.0f);
}
static void s_bit(maidmic_pipeline_t* p, uint32_t n) {
    sp(p, n, "bitcrush_bits", 16.0f);
    sp(p, n, "bitcrush_down", 1.0f);
    sp(p, n, "bitcrush_mix", 0.0f);
}

static const entry_t ENTRIES[] = {
    { &maidmic_module_gain, "Gain", s_gain },
    { &maidmic_module_compressor, "Compressor", s_comp },
    { &maidmic_module_bass, "Bass", s_bass },
    { &maidmic_module_treble, "Treble", s_treble },
    { &maidmic_module_reverb, "Reverb", s_reverb },
    { &maidmic_module_voice_transform, "VoiceTransform", s_vt },
    { &maidmic_module_distortion, "Distortion", s_dist },
    { &maidmic_module_echo, "Echo", s_echo },
    { &maidmic_module_bitcrush, "Bitcrush", s_bit },
};

// 全链（App 顺序）+ 指定格式：定位"组合链"崩溃
static void full_chain(int fmt) {
    printf("[FULL-%s] start\n", fmt == 0 ? "S16" : "F32");
    fflush(stdout);
    maidmic_pipeline_t* p = maidmic_pipeline_create(MAIDMIC_PIPELINE_MODE_SIMPLE);
    uint32_t n;
    n = maidmic_pipeline_add_module(p, &maidmic_module_gain); s_gain(p, n);
    n = maidmic_pipeline_add_module(p, &maidmic_module_compressor); s_comp(p, n);
    n = maidmic_pipeline_add_module(p, &maidmic_module_bass); s_bass(p, n);
    n = maidmic_pipeline_add_module(p, &maidmic_module_treble); s_treble(p, n);
    n = maidmic_pipeline_add_module(p, &maidmic_module_reverb); s_reverb(p, n);
    n = maidmic_pipeline_add_module(p, &maidmic_module_voice_transform); s_vt(p, n);
    n = maidmic_pipeline_add_module(p, &maidmic_module_distortion); s_dist(p, n);
    n = maidmic_pipeline_add_module(p, &maidmic_module_echo); s_echo(p, n);
    n = maidmic_pipeline_add_module(p, &maidmic_module_bitcrush); s_bit(p, n);

    static int16_t in16[NBLOCKS * BLOCK];
    static int16_t out16[NBLOCKS * BLOCK];
    static float inf[NBLOCKS * BLOCK];
    static float outf[NBLOCKS * BLOCK];
    static double phase = 0.0;
    for (uint32_t i = 0; i < NBLOCKS * BLOCK; i++) {
        phase += 2.0 * M_PI * 120.0 / SR;
        if (phase > 2.0 * M_PI) phase -= 2.0 * M_PI;
        double s = 0.0;
        for (int k = 1; k <= 40; k++) {
            const float fk = 120.0f * k;
            if (fk > 8000.0f) break;
            s += sin(phase * k) / k;
        }
        float v = (float)(0.3 * s) + 0.003f * ((float)((i * 2654435761u) >> 16 & 0xFF) / 128.0f - 1.0f);
        if (fmt == 0) {
            v *= 32767.0f;
            if (v > 32767.0f) v = 32767.0f;
            if (v < -32768.0f) v = -32768.0f;
            in16[i] = (int16_t)v;
        } else {
            inf[i] = v;
        }
    }

    if (fmt == 0) {
        int16_t ib[BLOCK], ob[BLOCK];
        maidmic_buffer_t bi, bo;
        memset(&bi, 0, sizeof(bi));
        memset(&bo, 0, sizeof(bo));
        bi.data = ib; bi.meta.sample_rate = SR; bi.meta.channels = 1;
        bi.meta.format = MAIDMIC_SAMPLE_S16; bi.meta.frame_count = BLOCK;
        bi.data_bytes = BLOCK * 2;
        bo.data = ob; bo.meta = bi.meta; bo.data_bytes = BLOCK * 2;
        for (uint32_t b = 0; b < NBLOCKS; b++) {
            memcpy(ib, in16 + b * BLOCK, BLOCK * sizeof(int16_t));
            maidmic_pipeline_process(p, &bi, &bo);
            memcpy(out16 + b * BLOCK, ob, BLOCK * sizeof(int16_t));
            if (b % 32u == 0u) { printf("[FULL-S16] block %u\n", b); fflush(stdout); }
        }
    } else {
        float ib[BLOCK], ob[BLOCK];
        maidmic_buffer_t bi, bo;
        memset(&bi, 0, sizeof(bi));
        memset(&bo, 0, sizeof(bo));
        bi.data = ib; bi.meta.sample_rate = SR; bi.meta.channels = 1;
        bi.meta.format = MAIDMIC_SAMPLE_F32; bi.meta.frame_count = BLOCK;
        bi.data_bytes = BLOCK * 4;
        bo.data = ob; bo.meta = bi.meta; bo.data_bytes = BLOCK * 4;
        for (uint32_t b = 0; b < NBLOCKS; b++) {
            memcpy(ib, inf + b * BLOCK, BLOCK * sizeof(float));
            maidmic_pipeline_process(p, &bi, &bo);
            memcpy(outf + b * BLOCK, ob, BLOCK * sizeof(float));
            if (b % 32u == 0u) { printf("[FULL-F32] block %u\n", b); fflush(stdout); }
        }
    }
    printf("[FULL-%s] processed\n", fmt == 0 ? "S16" : "F32");
    fflush(stdout);
    maidmic_pipeline_destroy(p);
    printf("[FULL-%s] destroyed OK\n", fmt == 0 ? "S16" : "F32");
    fflush(stdout);
}

static uint32_t rng = 0x12345678u;
static float noise(void) {
    rng = rng * 1664525u + 1013904223u;
    return ((float)((rng >> 16) & 0xFFu) / 128.0f) - 1.0f;
}

int main(void) {
    // 合成输入（S16）
    static int16_t in[NBLOCKS * BLOCK];
    static int16_t out[NBLOCKS * BLOCK];
    static double phase = 0.0;
    for (uint32_t i = 0; i < NBLOCKS * BLOCK; i++) {
        phase += 2.0 * M_PI * 120.0 / SR;
        if (phase > 2.0 * M_PI) phase -= 2.0 * M_PI;
        double s = 0.0;
        for (int k = 1; k <= 40; k++) {
            const float fk = 120.0f * k;
            if (fk > 8000.0f) break;
            s += sin(phase * k) / k;
        }
        float v = (float)(0.3 * s) + 0.003f * noise();
        v *= 32767.0f;
        if (v > 32767.0f) v = 32767.0f;
        if (v < -32768.0f) v = -32768.0f;
        in[i] = (int16_t)v;
    }

    for (size_t e = 0; e < sizeof(ENTRIES) / sizeof(ENTRIES[0]); e++) {
        printf("[%s] start\n", ENTRIES[e].name);
        fflush(stdout);
        maidmic_pipeline_t* p = maidmic_pipeline_create(MAIDMIC_PIPELINE_MODE_SIMPLE);
        uint32_t n = maidmic_pipeline_add_module(p, ENTRIES[e].mod);
        ENTRIES[e].setup(p, n);

        int16_t ib[BLOCK], ob[BLOCK];
        maidmic_buffer_t bi, bo;
        memset(&bi, 0, sizeof(bi));
        memset(&bo, 0, sizeof(bo));
        bi.data = ib; bi.owned = false;
        bi.meta.sample_rate = SR;
        bi.meta.channels = 1;
        bi.meta.format = MAIDMIC_SAMPLE_S16;
        bi.meta.frame_count = BLOCK;
        bi.data_bytes = BLOCK * 2;
        bo.data = ob; bo.owned = false; bo.meta = bi.meta; bo.data_bytes = BLOCK * 2;

        for (uint32_t b = 0; b < NBLOCKS; b++) {
            memcpy(ib, in + b * BLOCK, BLOCK * sizeof(int16_t));
            maidmic_pipeline_process(p, &bi, &bo);
            memcpy(out + b * BLOCK, ob, BLOCK * sizeof(int16_t));
        }
        printf("[%s] processed\n", ENTRIES[e].name);
        fflush(stdout);
        maidmic_pipeline_destroy(p);
        printf("[%s] destroyed OK\n", ENTRIES[e].name);
        fflush(stdout);
    }
    printf("=== diag_s16 done ===\n");
    fflush(stdout);

    // ---- 全链组合诊断 ----
    full_chain(0);  // S16（App 真实格式）
    full_chain(1);  // F32 对照
    return 0;
}
