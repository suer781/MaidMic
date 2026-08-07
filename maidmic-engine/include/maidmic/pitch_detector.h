// maidmic-engine/include/maidmic/pitch_detector.h
// MaidMic 基频检测算法库（纯函数，无模块 vtable）
// MaidMic Pitch Detection Algorithm Library (pure functions, no module vtable)
//
// 自相关法（ACF）基频检测 + 能量/过零率清浊音判定，供 voice_transform
// 与 autotune 模块调用。纯算法库：无状态、无缓冲处理、线程安全、可重入。
// ACF-based pitch detection plus energy/ZCR voicing decision. Stateless,
// thread-safe and reentrant; consumed by the voice_transform / autotune
// modules.

#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// 基频检测：返回估计基频（Hz），0 表示未检出。
// 搜索范围 60~500Hz。voiced 输出判定（归一化峰值与能量）。
// Detect fundamental frequency (Hz); returns 0 when unvoiced/undetected.
// Search range 60~500Hz. voiced reflects the normalized-peak + energy test.
float maidmic_detect_pitch(const float* x, uint32_t n, uint32_t sample_rate, bool* voiced);

// 清浊音判定辅助：基于能量与过零率，返回 true 表示"浊音帧"（有周期性）。
// Voicing helper based on short-time energy and zero-crossing rate;
// returns true for a "voiced frame" (periodic content).
bool maidmic_is_voiced_frame(const float* x, uint32_t n);

#ifdef __cplusplus
}
#endif
