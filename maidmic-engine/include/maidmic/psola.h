// maidmic-engine/include/maidmic/psola.h
// MaidMic 流式 TD-PSOLA 变调引擎接口
// MaidMic Streaming TD-PSOLA Pitch Shifter API
//
// 时域基音同步重叠相加（TD-PSOLA, Moulines & Charpentier 1990）的
// 单声道流式实现，供 voice_transform 模块调用：
//   - 输入基音周期 Ta 由上层逐块报告（maidmic_psola_report_pitch）；
//   - 合成基音周期 Ts = Ta / ratio（ratio 由 set_ratio 给出目标并内部平滑）；
//   - 窗长恒等于 2*max(Ta, Ts)（不随变调比率缩放）→ 共振峰（声道谱包络）
//     完整保留，不会产生重采样式"小黄人"音色偏移；
//   - 浊音段按基音网格对齐（pitch-synchronous），清音/无基频段自动切换
//     Ts = Ta 的恒等重叠相加（Hann COLA 精确重构 = 延迟直通，无伪周期嗡声）。
// 算法延迟恒定：mm_psola_get_delay() 样本（输出流滞后输入流）。

#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// PSOLA 实例（单声道；多声道请为每个声道各建一个实例）
typedef struct mm_psola_s mm_psola_t;

// 创建实例。sample_rate 用于周期范围换算（60~500Hz）。
// 失败返回 NULL。
mm_psola_t* mm_psola_create(uint32_t sample_rate);

// 销毁实例
void mm_psola_destroy(mm_psola_t* ps);

// 复位内部状态（清历史环、基音网格、待叠块），ratio 保持当前目标
void mm_psola_reset(mm_psola_t* ps);

// 设置目标变调比率（ratio = 2^(semitones/12)）。
// 内部按 grain 粒度一阶平滑逼近，不产生参数跳变伪影。
void mm_psola_set_ratio(mm_psola_t* ps, float target_ratio);

// 逐块报告基频检测结果（对最新输入估计）：
//   f0 > 0 且 voiced=true → 浊音，基音周期 Ta = sr/f0；
//   voiced=false → 清音/未检出，进入恒等重构模式（Ts = Ta）。
void mm_psola_report_pitch(mm_psola_t* ps, float f0, bool voiced);

// 处理一块（单声道）：x 为 fc 个输入样本，y 为 fc 个输出样本。
// 输出流相对输入流滞后 mm_psola_get_delay() 个样本（流级延迟，块大小无关）。
void mm_psola_process(mm_psola_t* ps, const float* x, uint32_t fc, float* y);

// 算法延迟（样本数），实例生命周期内恒定
uint32_t mm_psola_get_delay(const mm_psola_t* ps);

#ifdef __cplusplus
}
#endif
