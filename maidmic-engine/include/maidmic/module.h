// maidmic-engine/include/maidmic/module.h
// Echio 引擎 DSP 模块接口
// Echio Engine DSP Module Interface
//
// 所有 DSP 处理模块（增益、变调、混响等）都实现这个接口。
// 模块是无状态的（状态由 Pipeline 管理），可重入。
// All DSP modules (Gain, Pitch, Reverb, etc.) implement this interface.
// Modules are stateless (state managed by Pipeline), reentrant safe.

#pragma once

#include "types.h"

#ifdef __cplusplus
extern "C" {
#endif

// ============================================================
// DSP 模块 ID — 每个内置模块有固定 ID，插件用 UUID
// DSP Module IDs — built-in modules have fixed IDs, plugins use UUIDs
// ============================================================
// 1000 以下保留给内置模块，1000+ 为 Lua 插件
// IDs < 1000 reserved for built-in, 1000+ for Lua plugins
#define MAIDMIC_MODULE_ID_GAIN       1    // 增益
#define MAIDMIC_MODULE_ID_EQ         2    // 均衡器
#define MAIDMIC_MODULE_ID_COMPRESSOR 3    // 压缩器
#define MAIDMIC_MODULE_ID_PITCH      4    // 变调 (PSOLA)
#define MAIDMIC_MODULE_ID_REVERB     5    // 混响
#define MAIDMIC_MODULE_ID_CHORUS     6    // 合唱
#define MAIDMIC_MODULE_ID_DISTORTION 7    // 失真
#define MAIDMIC_MODULE_ID_DELAY      8    // 延迟
#define MAIDMIC_MODULE_ID_NOISEGATE  9    // 噪声门
#define MAIDMIC_MODULE_ID_LIMITER    10   // 限制器
#define MAIDMIC_MODULE_ID_BASS       11   // 低音 Shelving 滤波器
#define MAIDMIC_MODULE_ID_TREBLE     12   // 高音 Shelving 滤波器
#define MAIDMIC_MODULE_ID_FORMANT    13   // 共振峰偏移
#define MAIDMIC_MODULE_ID_ECHO       14   // 回声/延迟
#define MAIDMIC_MODULE_ID_VOICE_TRANSFORM 15   // LPC 源-滤波器变声引擎
#define MAIDMIC_MODULE_ID_VOICEPRINT_MASK 16   // 声纹脱敏
#define MAIDMIC_MODULE_ID_PRESENCE   17   // 人声存在感
#define MAIDMIC_MODULE_ID_AUTOTUNE   18   // 自动修音
#define MAIDMIC_MODULE_ID_LUA        999  // Lua 插件模块 (通用代理)

// ============================================================
// 模块能力标志
// Module capability flags
// ============================================================
// 声明模块支持的操作，用于 Pipeline 优化和 UI 显示
#define MAIDMIC_CAP_PROCESS_AUDIO    (1 << 0)  // 能处理音频帧
#define MAIDMIC_CAP_HAS_PARAMS       (1 << 1)  // 有可调参数
#define MAIDMIC_CAP_BYPASS           (1 << 2)  // 支持旁路
#define MAIDMIC_CAP_STEREO           (1 << 3)  // 支持立体声
#define MAIDMIC_CAP_REALTIME         (1 << 4)  // 实时安全（低延迟）
#define MAIDMIC_CAP_NON_REALTIME     (1 << 5)  // 非实时（可能高延迟）

// ============================================================
// DSP 模块接口
// DSP Module Interface
// ============================================================
// 每个模块需要实现以下函数指针。
// 引擎通过 vtable 调用，不关心具体实现。
// Each module implements these function pointers.
// Engine calls through vtable, doesn't care about implementation.

typedef struct maidmic_module_t maidmic_module_t;

// 模块 vtable：所有回调函数
// Module vtable: all callback functions
typedef struct {
    // --------------------------------------------------------
    // 初始化 / 销毁
    // Init / Destroy
    // --------------------------------------------------------
    
    // 创建模块实例，返回用户数据指针
    // Create module instance, returns user data pointer
    void* (*create)(void);
    
    // 销毁模块实例
    // Destroy module instance
    void (*destroy)(void* userdata);
    
    // --------------------------------------------------------
    // 配置
    // Configuration
    // --------------------------------------------------------
    
    // 设置采样率和声道数（音频流开始时调用）
    // Set sample rate and channel count (called when audio stream starts)
    bool (*setup)(void* userdata, uint32_t sample_rate, uint16_t channels);
    
    // 获取模块参数列表（用于 UI 显示）
    // Get parameter list (for UI display)
    // 返回参数数组，以 key=NULL 结束
    // Returns param array, terminated by key=NULL
    uint32_t (*get_param_count)(void* userdata);
    const maidmic_param_t* (*get_param_info)(void* userdata, uint32_t index);
    
    // 设置参数值（用户调整滑块时调用）
    // Set parameter value (called when user adjusts slider)
    bool (*set_param)(void* userdata, const char* key, maidmic_param_t value);
    
    // 获取参数当前值
    // Get current parameter value
    maidmic_param_t (*get_param)(void* userdata, const char* key);
    
    // --------------------------------------------------------
    // 音频处理
    // Audio processing
    // --------------------------------------------------------
    
    // 处理一帧音频（核心函数）
    // Process one audio frame (core function)
    // input 和 output 可以指向同一缓冲区（原地处理）
    // input and output may point to the same buffer (in-place processing)
    bool (*process)(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output);
    
    // 复位内部状态（例如 Seek 或模式切换时）
    // Reset internal state (e.g., on seek or mode switch)
    void (*reset)(void* userdata);
    
} maidmic_module_vtable_t;

// ============================================================
// 模块描述
// Module descriptor
// ============================================================
// 注册到引擎时使用这个结构
// Used when registering a module with the engine

struct maidmic_module_t {
    uint32_t id;                            // 模块 ID
    const char* name;                        // 展示名称，如 "Gain", "Pitch Shift"
    const char* description;                 // 描述，如 "Adjust input volume level"
    const char* author;                      // 作者（内置模块为 "MaidMic Team"）
    uint32_t version;                        // 版本号
    uint32_t capabilities;                   // 能力标志 (MAIDMIC_CAP_*)
    const maidmic_module_vtable_t* vtable;   // 函数表
};

// ============================================================
// SIMD 能力检测
// SIMD capability detection
// ============================================================
// 编译期检测当前构建是否启用 ARM NEON SIMD（非 ARM 平台恒为 false）。
// 供 JNI 层输出 "NEON enabled" 日志。实现位于 lpc.c。
// Compile-time check whether this build uses ARM NEON SIMD.
bool maidmic_neon_enabled(void);

// ============================================================
// 参数平滑辅助（消除 zipper 噪声）
// Parameter smoothing helper (anti-zipper)
// ============================================================
// 对连续可调参数做一阶线性逼近：set_param 只更新 target（目标值），
// process 热路径中每样本调用 next() 逼近目标值，避免参数跳变产生爆音。
// 步长 step 默认 1/32：以 48kHz 计约 0.7ms 完成一次全量逼近，足够平滑。
// 平滑对象通常是"计算中间量"（如线性增益、变调比率），而非 UI 显示值，
// 这样无需在每样本循环内做 powf 等重计算。
typedef struct {
    float current;   // 当前（正在逼近的）值
    float target;    // 目标值（set_target 设置）
    float step;      // 每样本步进量
} maidmic_ramp_t;

static inline void maidmic_ramp_init(maidmic_ramp_t* r, float value) {
    r->current = r->target = value;
    r->step = 1.0f / 32.0f;
}

static inline void maidmic_ramp_set_target(maidmic_ramp_t* r, float target) {
    r->target = target;
}

// 复位：直接到位（复位时机不在音频热路径，无需平滑过渡）
static inline void maidmic_ramp_reset(maidmic_ramp_t* r) {
    r->current = r->target;
}

// 每样本推进一次，返回逼近后的当前值
static inline float maidmic_ramp_next(maidmic_ramp_t* r) {
    if (r->current < r->target) {
        r->current += r->step;
        if (r->current > r->target) r->current = r->target;
    } else if (r->current > r->target) {
        r->current -= r->step;
        if (r->current < r->target) r->current = r->target;
    }
    return r->current;
}

#ifdef __cplusplus
}
#endif
