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

#ifdef __cplusplus
}
#endif
