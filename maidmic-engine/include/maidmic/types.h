// maidmic-engine/include/maidmic/types.h
// MaidMic 基础类型定义
// MaidMic basic type definitions
//
// 所有引擎模块共享的基础类型，跨 C 和 C++ 使用
// Shared basic types across engine modules, usable from both C and C++

#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// ============================================================
// 音频格式常量
// Audio format constants
// ============================================================

// 采样格式枚举
// Sample format enum
typedef enum {
    MAIDMIC_SAMPLE_S16,       // 16-bit signed int (最常见，所有 Android 设备都支持)
    MAIDMIC_SAMPLE_S32,       // 32-bit signed int (更高精度)
    MAIDMIC_SAMPLE_F32,       // 32-bit float (DSP 内部推荐格式，不失真)
    MAIDMIC_SAMPLE_S24,       // 24-bit packed in 32-bit (专业设备)
} maidmic_sample_format_t;

// ============================================================
// 音频帧 / 缓冲区结构
// Audio frame / buffer structures
// ============================================================

// 音频帧元数据：描述一帧 PCM 数据的格式和时序
// Audio frame metadata: describes format and timing of one PCM frame
typedef struct {
    uint32_t sample_rate;      // 采样率 Hz, 可用户自定 (8000 ~ 192000)
    uint16_t channels;         // 声道数 (1=mono, 2=stereo)
    maidmic_sample_format_t format; // 采样格式
    uint32_t frame_count;      // 每声道样本数（帧大小）
    uint64_t timestamp_ns;     // 时间戳（纳秒），用于同步多路流
    uint32_t sequence;         // 帧序列号，检测丢帧
} maidmic_frame_meta_t;

// 音频缓冲区：metadata + 数据
// Audio buffer: metadata + data
typedef struct {
    maidmic_frame_meta_t meta; // 帧元数据
    void* data;                // PCM 数据指针
    uint32_t data_bytes;       // 数据大小（字节）
    bool owned;                // 是否拥有 data 所有权（是否需要 free）
} maidmic_buffer_t;

// ============================================================
// 参数系统
// Parameter system
// ============================================================
// 任何 DSP 参数都是一个键值对，支持浮点/整数/字符串/布尔四种类型
// Any DSP parameter is a key-value pair supporting 4 types

typedef enum {
    MAIDMIC_PARAM_FLOAT,       // 浮点参数：增益值、频率、比率等
    MAIDMIC_PARAM_INT,         // 整数参数：声道选择、FFT 大小等
    MAIDMIC_PARAM_STRING,      // 字符串参数：预设名称、文件路径等
    MAIDMIC_PARAM_BOOL,        // 布尔参数：旁路开关、静音开关等
} maidmic_param_type_t;

typedef struct {
    const char* key;           // 参数键名，如 "gain", "pitch_semitones", "reverb_decay"
    maidmic_param_type_t type; // 参数类型
    union {
        float as_float;        // 浮点值 —— 无上限，用户想填多大就多大
        int32_t as_int;        // 整数值
        const char* as_string; // 字符串值
        bool as_bool;          // 布尔值
    } value;
    // 参数元数据（供 UI 层使用，引擎内部不关心）
    float min;                 // 建议最小值（UI 滑块用，引擎不强制）
    float max;                 // 建议最大值（同上，用户可手动超限）
    const char* unit;          // 单位： "dB", "Hz", "ms", "%", "semitone" 等
} maidmic_param_t;

#ifdef __cplusplus
}
#endif
