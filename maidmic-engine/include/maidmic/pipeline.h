// maidmic-engine/include/maidmic/pipeline.h
// Echio 引擎管线框架
// Echio Engine Pipeline Framework
//
// 管线是 DSP 模块的容器，支持两种拓扑模式：
// The pipeline is a container of DSP modules, supporting two topology modes:
//
// 1. SIMPLE（线性模式）: 模块按顺序串联，A→B→C→D
// 2. DAG（有向无环图模式）: 模块可并联/分流/混合，A→(B+C)→D
//
// 用户可以在设置中自由切换两种模式，切换时保留已有模块和参数。
// User can switch between modes freely in settings, preserving modules and params.

#pragma once

#include "types.h"
#include "module.h"

#ifdef __cplusplus
extern "C" {
#endif

// ============================================================
// 管线拓扑模式
// Pipeline topology mode
// ============================================================

typedef enum {
    MAIDMIC_PIPELINE_MODE_SIMPLE = 0,  // 线性模式：模块线性排列，顺序处理
    MAIDMIC_PIPELINE_MODE_DAG   = 1,   // DAG 模式：模块可按 DAG 排列，支持并联分流/混音
} maidmic_pipeline_mode_t;

// ============================================================
// DAG 节点 / 边 结构
// DAG Node / Edge structures (仅 DAG 模式使用)
// ============================================================

// 管线中的音频流通道标签
// Audio stream channel label in pipeline
typedef struct {
    uint32_t id;                 // 通道唯一 ID
    char name[64];               // 通道名，如 "dry", "wet", "pitch_shifted"
    float mix_ratio;             // 混音比例 0.0~1.0（无上限，用户可以拉到 2.0 做增强）
    bool muted;                  // 是否静音
} maidmic_stream_channel_t;

// DAG 节点：一个模块实例在管线中的位置
typedef struct maidmic_dag_node_t {
    uint32_t node_id;                     // 节点 ID
    const maidmic_module_t* module;       // 模块指针
    void* userdata;                       // 模块实例数据
    
    // 输入/输出通道
    uint32_t input_channel_count;
    maidmic_stream_channel_t* input_channels;
    uint32_t output_channel_count;
    maidmic_stream_channel_t* output_channels;
    
    // 模块参数缓存（快速访问）
    uint32_t param_count;
    maidmic_param_t* params;
    
    // 旁路：如果 bypass=true，音频直接通过不处理
    bool bypass;
    
    // DAG 结构指针（仅 DAG 模式使用）
    uint32_t* incoming_edge_node_ids;     // 前驱节点 ID 数组
    uint32_t incoming_edge_count;
    uint32_t* outgoing_edge_node_ids;     // 后继节点 ID 数组
    uint32_t outgoing_edge_count;
    
    // 拓扑排序序号（引擎内部维护）
    uint32_t topo_order;
} maidmic_dag_node_t;

// ============================================================
// 管线主结构
// Pipeline main structure
// ============================================================

// 管线是不透明结构，通过 API 操作
// Pipeline is opaque, accessed through API
typedef struct maidmic_pipeline_t maidmic_pipeline_t;

// ============================================================
// 回调：管线状态变化通知
// Callbacks: pipeline state change notifications
// ============================================================
// UI 层注册这些回调来响应引擎状态变化
// UI layer registers these callbacks to respond to engine state changes

typedef struct {
    // 模块被添加到管线
    void (*on_module_added)(maidmic_pipeline_t* pipeline, uint32_t node_id, const char* name);
    // 模块从管线移除
    void (*on_module_removed)(maidmic_pipeline_t* pipeline, uint32_t node_id);
    // 模块顺序改变
    void (*on_module_reordered)(maidmic_pipeline_t* pipeline);
    // 参数值改变
    void (*on_param_changed)(maidmic_pipeline_t* pipeline, uint32_t node_id, const char* key, maidmic_param_t value);
    // 管线拓扑模式切换
    void (*on_mode_changed)(maidmic_pipeline_t* pipeline, maidmic_pipeline_mode_t new_mode);
    // 音频处理异常（如爆音、延迟过高）
    void (*on_error)(maidmic_pipeline_t* pipeline, int error_code, const char* message);
} maidmic_pipeline_callbacks_t;

// ============================================================
// 管线 API
// Pipeline API
// ============================================================

// 创建管线
// Create a pipeline
maidmic_pipeline_t* maidmic_pipeline_create(maidmic_pipeline_mode_t initial_mode);

// 销毁管线
// Destroy a pipeline
void maidmic_pipeline_destroy(maidmic_pipeline_t* pipeline);

// 注册回调
// Register callbacks
void maidmic_pipeline_set_callbacks(maidmic_pipeline_t* pipeline, const maidmic_pipeline_callbacks_t* callbacks, void* callback_userdata);

// --------------------------------------------------------
// 模式切换
// Mode switching
// --------------------------------------------------------

// 切换拓扑模式（保留模块和参数）
// Switch topology mode (preserves modules and params)
bool maidmic_pipeline_set_mode(maidmic_pipeline_t* pipeline, maidmic_pipeline_mode_t mode);
maidmic_pipeline_mode_t maidmic_pipeline_get_mode(const maidmic_pipeline_t* pipeline);

// --------------------------------------------------------
// 模块管理
// Module management
// --------------------------------------------------------

// 在管线末尾添加模块
// Add a module to the end of pipeline
uint32_t maidmic_pipeline_add_module(maidmic_pipeline_t* pipeline, const maidmic_module_t* module);

// 在指定位置插入模块（SIMPLE 模式：在 index 之前插入；DAG 模式：在 node_id 之前插入）
// Insert a module at a specific position
uint32_t maidmic_pipeline_insert_module(maidmic_pipeline_t* pipeline, const maidmic_module_t* module, uint32_t before_node_id);

// 移除模块
// Remove a module
bool maidmic_pipeline_remove_module(maidmic_pipeline_t* pipeline, uint32_t node_id);

// 重新排序（SIMPLE 模式：交换两个模块位置）
// Reorder modules
bool maidmic_pipeline_swap_modules(maidmic_pipeline_t* pipeline, uint32_t node_id_a, uint32_t node_id_b);

// 旁路/启用模块
// Bypass/enable a module
bool maidmic_pipeline_set_module_bypass(maidmic_pipeline_t* pipeline, uint32_t node_id, bool bypass);

// --------------------------------------------------------
// DAG 特定的连接管理
// DAG-specific connection management
// ============================================================
// 仅在 DAG 模式下可用。SIMPLE 模式下连接是自动的（按顺序串联）。
// Only available in DAG mode. In SIMPLE mode, connections are automatic (sequential).

// 在 DAG 中添加一条边（连接两个节点）
// Add an edge connecting two nodes in DAG
bool maidmic_pipeline_dag_connect(maidmic_pipeline_t* pipeline, uint32_t from_node_id, uint32_t to_node_id);

// 移除一条边
// Remove an edge
bool maidmic_pipeline_dag_disconnect(maidmic_pipeline_t* pipeline, uint32_t from_node_id, uint32_t to_node_id);

// --------------------------------------------------------
// 参数操作
// Parameter operations
// --------------------------------------------------------

// 设置模块参数
// Set module parameter
bool maidmic_pipeline_set_param(maidmic_pipeline_t* pipeline, uint32_t node_id, const char* key, maidmic_param_t value);

// 获取模块参数
// Get module parameter
maidmic_param_t maidmic_pipeline_get_param(const maidmic_pipeline_t* pipeline, uint32_t node_id, const char* key);

// --------------------------------------------------------
// 音频处理入口
// Audio processing entry point
// --------------------------------------------------------

// 处理一帧音频（遍历管线中所有模块，按拓扑序处理）
// Process one audio frame (traverse all modules in topological order)
// 音频从输入进入管线，经过所有非 bypass 模块，从输出离开
// Audio enters pipeline through input, goes through non-bypassed modules, leaves through output
bool maidmic_pipeline_process(maidmic_pipeline_t* pipeline, const maidmic_buffer_t* input, maidmic_buffer_t* output);

// 复位管线中所有模块的状态
// Reset all modules' internal states
void maidmic_pipeline_reset(maidmic_pipeline_t* pipeline);

// --------------------------------------------------------
// 查询
// Query
// --------------------------------------------------------

// 获取管线中的模块数量
// Get module count in pipeline
uint32_t maidmic_pipeline_get_module_count(const maidmic_pipeline_t* pipeline);

// 按索引获取模块信息（SIMPLE 模式）
// Get module info by index (SIMPLE mode)
const maidmic_dag_node_t* maidmic_pipeline_get_module_at(const maidmic_pipeline_t* pipeline, uint32_t index);

// 按 ID 获取模块信息
// Get module info by ID
const maidmic_dag_node_t* maidmic_pipeline_get_module_by_id(const maidmic_pipeline_t* pipeline, uint32_t node_id);

// 获取当前延迟估计（毫秒）
// Get current latency estimate (ms)
float maidmic_pipeline_get_latency_ms(const maidmic_pipeline_t* pipeline);

#ifdef __cplusplus
}
#endif
