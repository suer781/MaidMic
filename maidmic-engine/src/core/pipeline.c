// maidmic-engine/src/core/pipeline.c
// Echio 引擎管线实现
// Echio Engine Pipeline Implementation
//
// 管理 DSP 模块的拓扑排序、音频帧路由、参数传递。
// 支持 SIMPLE（线性）和 DAG（有向无环图并联分流）两种模式。
// Manages DSP module topological ordering, audio frame routing, parameter passing.

#include "maidmic/pipeline.h"
#include <stdlib.h>
#include <string.h>

// ============================================================
// 内部结构
// Internal structures
// ============================================================

// 动态数组（用于存储节点列表）
typedef struct {
    maidmic_dag_node_t** items;
    uint32_t count;
    uint32_t capacity;
} node_array_t;

// 管线主结构
struct maidmic_pipeline_t {
    maidmic_pipeline_mode_t mode;            // 当前模式 SIMPLE / DAG
    node_array_t nodes;                      // 所有模块节点
    uint32_t next_node_id;                   // 下一个可用节点 ID
    
    // 音频配置
    uint32_t sample_rate;
    uint16_t channels;
    size_t frame_size;
    
    // 回调
    maidmic_pipeline_callbacks_t callbacks;
    void* callback_userdata;
    
    // 延迟统计
    float estimated_latency_ms;
    
    // 工作缓冲区（DAG 模式：存储中间流数据）
    // Work buffer (DAG mode: stores intermediate stream data)
    char* work_buffer;
    uint32_t work_buffer_size;
};

// ============================================================
// 节点数组操作
// Node array operations
// ============================================================

static bool node_array_init(node_array_t* arr) {
    arr->count = 0;
    arr->capacity = 16;  // 初始容量 16 个模块，不够会自动扩展
    arr->items = (maidmic_dag_node_t**)calloc(arr->capacity, sizeof(maidmic_dag_node_t*));
    return arr->items != NULL;
}

static void node_array_destroy(node_array_t* arr) {
    for (uint32_t i = 0; i < arr->count; i++) {
        if (arr->items[i]) {
            // 调用模块的 destroy 回调
            if (arr->items[i]->module && arr->items[i]->module->vtable && arr->items[i]->module->vtable->destroy) {
                arr->items[i]->module->vtable->destroy(arr->items[i]->userdata);
            }
            free(arr->items[i]->params);
            free(arr->items[i]);
        }
    }
    free(arr->items);
}

static bool node_array_add(node_array_t* arr, maidmic_dag_node_t* node) {
    if (arr->count >= arr->capacity) {
        arr->capacity *= 2;
        maidmic_dag_node_t** new_items = (maidmic_dag_node_t**)realloc(arr->items, arr->capacity * sizeof(maidmic_dag_node_t*));
        if (!new_items) return false;
        arr->items = new_items;
    }
    arr->items[arr->count++] = node;
    return true;
}

// ============================================================
// DAG 拓扑排序（Kahn 算法）
// DAG topological sort (Kahn's algorithm)
// ============================================================
// 检测环并生成处理顺序。如果检测到环，返回 false。
// Detects cycles and generates processing order. Returns false if cycle detected.

static bool topological_sort(maidmic_pipeline_t* pipeline) {
    if (pipeline->mode == MAIDMIC_PIPELINE_MODE_SIMPLE) {
        // 线性模式：顺序就是处理顺序，无需排序
        // Simple mode: insertion order is processing order
        for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
            pipeline->nodes.items[i]->topo_order = i;
        }
        return true;
    }
    
    // DAG 模式：Kahn 算法
    uint32_t n = pipeline->nodes.count;
    
    // 计算入度
    uint32_t* in_degree = (uint32_t*)calloc(n, sizeof(uint32_t));
    for (uint32_t i = 0; i < n; i++) {
        maidmic_dag_node_t* node = pipeline->nodes.items[i];
        for (uint32_t j = 0; j < node->incoming_edge_count; j++) {
            // 找到前驱节点的索引
            for (uint32_t k = 0; k < n; k++) {
                if (pipeline->nodes.items[k]->node_id == node->incoming_edge_node_ids[j]) {
                    in_degree[i]++;
                    break;
                }
            }
        }
    }
    
    // Kahn 算法队列
    uint32_t* queue = (uint32_t*)malloc(n * sizeof(uint32_t));
    uint32_t q_head = 0, q_tail = 0;
    
    for (uint32_t i = 0; i < n; i++) {
        if (in_degree[i] == 0) {
            queue[q_tail++] = i;
        }
    }
    
    uint32_t processed = 0;
    while (q_head < q_tail) {
        uint32_t idx = queue[q_head++];
        pipeline->nodes.items[idx]->topo_order = processed++;
        
        // 减少后继节点的入度
        maidmic_dag_node_t* node = pipeline->nodes.items[idx];
        for (uint32_t i = 0; i < node->outgoing_edge_count; i++) {
            for (uint32_t j = 0; j < n; j++) {
                if (pipeline->nodes.items[j]->node_id == node->outgoing_edge_node_ids[i]) {
                    if (--in_degree[j] == 0) {
                        queue[q_tail++] = j;
                    }
                    break;
                }
            }
        }
    }
    
    free(in_degree);
    free(queue);
    
    // 如果处理的节点数不等于总节点数，说明有环
    if (processed != n) {
        return false;  // DAG 中有环
    }
    
    return true;
}

// ============================================================
// 管线 API 实现
// Pipeline API implementation
// ============================================================

maidmic_pipeline_t* maidmic_pipeline_create(maidmic_pipeline_mode_t initial_mode) {
    maidmic_pipeline_t* pipeline = (maidmic_pipeline_t*)calloc(1, sizeof(maidmic_pipeline_t));
    if (!pipeline) return NULL;
    
    pipeline->mode = initial_mode;
    pipeline->next_node_id = 1;
    pipeline->sample_rate = 48000;  // 默认 48kHz
    pipeline->channels = 1;         // 默认单声道
    pipeline->frame_size = sizeof(int16_t); // 默认 16-bit
    pipeline->estimated_latency_ms = 0.0f;
    
    if (!node_array_init(&pipeline->nodes)) {
        free(pipeline);
        return NULL;
    }
    
    // 默认工作缓冲区 4096 帧，DAG 模式时扩展
    // Default work buffer 4096 frames, expands in DAG mode
    pipeline->work_buffer_size = 4096 * sizeof(float) * 2; // stereo float
    pipeline->work_buffer = (char*)malloc(pipeline->work_buffer_size);
    
    return pipeline;
}

void maidmic_pipeline_destroy(maidmic_pipeline_t* pipeline) {
    if (!pipeline) return;
    node_array_destroy(&pipeline->nodes);
    free(pipeline->work_buffer);
    free(pipeline);
}

// --------------------------------------------------------
// 模式切换
// Mode switching
// --------------------------------------------------------

bool maidmic_pipeline_set_mode(maidmic_pipeline_t* pipeline, maidmic_pipeline_mode_t mode) {
    if (pipeline->mode == mode) return true;
    
    pipeline->mode = mode;
    
    // 切换模式后重新计算拓扑
    // Recalculate topology after mode switch
    topological_sort(pipeline);
    
    // 通知 UI 层
    if (pipeline->callbacks.on_mode_changed) {
        pipeline->callbacks.on_mode_changed(pipeline, mode);
    }
    
    return true;
}

maidmic_pipeline_mode_t maidmic_pipeline_get_mode(const maidmic_pipeline_t* pipeline) {
    return pipeline->mode;
}

// --------------------------------------------------------
// 模块管理
// Module management
// --------------------------------------------------------

uint32_t maidmic_pipeline_add_module(maidmic_pipeline_t* pipeline, const maidmic_module_t* module) {
    if (!pipeline || !module) return 0;
    
    // 创建节点
    maidmic_dag_node_t* node = (maidmic_dag_node_t*)calloc(1, sizeof(maidmic_dag_node_t));
    if (!node) return 0;
    
    node->node_id = pipeline->next_node_id++;
    node->module = module;
    node->bypass = false;
    node->topo_order = pipeline->nodes.count;
    
    // 调用模块的 create 回调
    if (module->vtable && module->vtable->create) {
        node->userdata = module->vtable->create();
    }
    
    // 调用 setup 设置采样率和声道数
    if (module->vtable && module->vtable->setup && node->userdata) {
        module->vtable->setup(node->userdata, pipeline->sample_rate, pipeline->channels);
    }
    
    // 缓存参数
    if (module->vtable && module->vtable->get_param_count && module->vtable->get_param_info) {
        node->param_count = module->vtable->get_param_count(node->userdata);
        node->params = (maidmic_param_t*)calloc(node->param_count, sizeof(maidmic_param_t));
        for (uint32_t i = 0; i < node->param_count; i++) {
            node->params[i] = *module->vtable->get_param_info(node->userdata, i);
        }
    }
    
    node_array_add(&pipeline->nodes, node);
    topological_sort(pipeline);
    
    if (pipeline->callbacks.on_module_added) {
        pipeline->callbacks.on_module_added(pipeline, node->node_id, module->name);
    }
    
    return node->node_id;
}

bool maidmic_pipeline_remove_module(maidmic_pipeline_t* pipeline, uint32_t node_id) {
    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        if (pipeline->nodes.items[i]->node_id == node_id) {
            maidmic_dag_node_t* node = pipeline->nodes.items[i];
            
            // 调用 destroy
            if (node->module->vtable->destroy && node->userdata) {
                node->module->vtable->destroy(node->userdata);
            }
            free(node->params);
            free(node);
            
            // 从数组中移除（用最后一个元素覆盖）
            pipeline->nodes.items[i] = pipeline->nodes.items[--pipeline->nodes.count];
            
            topological_sort(pipeline);
            
            if (pipeline->callbacks.on_module_removed) {
                pipeline->callbacks.on_module_removed(pipeline, node_id);
            }
            
            return true;
        }
    }
    return false;
}

// --------------------------------------------------------
// 参数操作
// Parameter operations
// --------------------------------------------------------

bool maidmic_pipeline_set_param(maidmic_pipeline_t* pipeline, uint32_t node_id, const char* key, maidmic_param_t value) {
    const maidmic_dag_node_t* node = maidmic_pipeline_get_module_by_id(pipeline, node_id);
    if (!node || !node->module->vtable->set_param) return false;
    
    bool result = node->module->vtable->set_param(node->userdata, key, value);
    
    if (result && pipeline->callbacks.on_param_changed) {
        pipeline->callbacks.on_param_changed(pipeline, node_id, key, value);
    }
    
    return result;
}

maidmic_param_t maidmic_pipeline_get_param(const maidmic_pipeline_t* pipeline, uint32_t node_id, const char* key) {
    const maidmic_dag_node_t* node = maidmic_pipeline_get_module_by_id(pipeline, node_id);
    if (!node || !node->module->vtable->get_param) {
        maidmic_param_t empty = {0};
        return empty;
    }
    return node->module->vtable->get_param(node->userdata, key);
}

// --------------------------------------------------------
// 核心：音频处理
// Core: Audio processing
// ============================================================
// 遍历管线中所有模块，按拓扑序处理音频帧。
// 输入帧经过每个非 bypass 模块，最终输出。
// 在 SIMPLE 模式下，模块串联，上一个的输出是下一个的输入。
// 在 DAG 模式下，按照拓扑排序处理，使用工作缓冲区分流。

bool maidmic_pipeline_process(maidmic_pipeline_t* pipeline, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    if (pipeline->nodes.count == 0) {
        // 没有模块，直通
        memcpy(output->data, input->data, input->data_bytes);
        output->meta = input->meta;
        return true;
    }
    
    // 按拓扑序排序的节点索引
    uint32_t* order = (uint32_t*)malloc(pipeline->nodes.count * sizeof(uint32_t));
    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        order[pipeline->nodes.items[i]->topo_order] = i;
    }
    
    // 第一个模块的输入来自 input
    const maidmic_buffer_t* current_input = input;
    maidmic_buffer_t current_output = *input;  // 复制 metadata
    current_output.data = pipeline->work_buffer;
    current_output.data_bytes = pipeline->work_buffer_size;
    current_output.owned = false;
    
    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        maidmic_dag_node_t* node = pipeline->nodes.items[order[i]];
        
        if (node->bypass || !node->module->vtable->process) {
            // 旁路或没有 process 回调，直通
            if (i < pipeline->nodes.count - 1) {
                // 不是最后一个，把输入传给下一个
                // 不需要复制，继续用 current_input
            }
            continue;
        }
        
        // 调用模块处理
        node->module->vtable->process(node->userdata, current_input, &current_output);
        
        // 输出作为下一个模块的输入（原地处理优化）
        // 大多数 DSP 模块支持原地处理（input == output）
        current_input = &current_output;
    }
    
    // 最后一个模块的输出复制到 output
    memcpy(output->data, current_output.data, current_output.data_bytes);
    output->meta = current_output.meta;
    
    free(order);
    return true;
}

// --------------------------------------------------------
// 查询函数
// Query functions
// --------------------------------------------------------

uint32_t maidmic_pipeline_get_module_count(const maidmic_pipeline_t* pipeline) {
    return pipeline->nodes.count;
}

const maidmic_dag_node_t* maidmic_pipeline_get_module_at(const maidmic_pipeline_t* pipeline, uint32_t index) {
    if (index >= pipeline->nodes.count) return NULL;
    return pipeline->nodes.items[index];
}

const maidmic_dag_node_t* maidmic_pipeline_get_module_by_id(const maidmic_pipeline_t* pipeline, uint32_t node_id) {
    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        if (pipeline->nodes.items[i]->node_id == node_id) {
            return pipeline->nodes.items[i];
        }
    }
    return NULL;
}

// --------------------------------------------------------
// 回调注册
// Callback registration
// --------------------------------------------------------

void maidmic_pipeline_set_callbacks(
    maidmic_pipeline_t* pipeline,
    const maidmic_pipeline_callbacks_t* callbacks,
    void* callback_userdata)
{
    if (!pipeline) return;
    if (callbacks) {
        pipeline->callbacks = *callbacks;
    } else {
        memset(&pipeline->callbacks, 0, sizeof(pipeline->callbacks));
    }
    pipeline->callback_userdata = callback_userdata;
}

// --------------------------------------------------------
// 插入/交换模块
// Insert/Swap modules
// --------------------------------------------------------

uint32_t maidmic_pipeline_insert_module(
    maidmic_pipeline_t* pipeline,
    const maidmic_module_t* module,
    uint32_t before_node_id)
{
    if (!pipeline || !module) return 0;

    uint32_t node_id = maidmic_pipeline_add_module(pipeline, module);

    if (pipeline->mode == MAIDMIC_PIPELINE_MODE_SIMPLE) {
        int insert_idx = -1;
        int new_idx = -1;
        for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
            if (pipeline->nodes.items[i]->node_id == before_node_id) insert_idx = (int)i;
            if (pipeline->nodes.items[i]->node_id == node_id) new_idx = (int)i;
        }
        if (insert_idx >= 0 && new_idx >= 0 && new_idx != insert_idx) {
            pipeline->nodes.items[new_idx]->topo_order = pipeline->nodes.items[insert_idx]->topo_order;
            for (uint32_t i = (uint32_t)insert_idx; i < (uint32_t)new_idx; i++) {
                pipeline->nodes.items[i]->topo_order++;
            }
        }
    }

    return node_id;
}

bool maidmic_pipeline_swap_modules(
    maidmic_pipeline_t* pipeline,
    uint32_t node_id_a,
    uint32_t node_id_b)
{
    if (!pipeline || node_id_a == node_id_b) return false;

    maidmic_dag_node_t* node_a = NULL;
    maidmic_dag_node_t* node_b = NULL;

    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        if (pipeline->nodes.items[i]->node_id == node_id_a) node_a = pipeline->nodes.items[i];
        if (pipeline->nodes.items[i]->node_id == node_id_b) node_b = pipeline->nodes.items[i];
    }

    if (!node_a || !node_b) return false;

    uint32_t temp_order = node_a->topo_order;
    node_a->topo_order = node_b->topo_order;
    node_b->topo_order = temp_order;

    return true;
}

// --------------------------------------------------------
// 旁路
// Bypass
// --------------------------------------------------------

bool maidmic_pipeline_set_module_bypass(
    maidmic_pipeline_t* pipeline,
    uint32_t node_id,
    bool bypass)
{
    if (!pipeline) return false;
    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        if (pipeline->nodes.items[i]->node_id == node_id) {
            pipeline->nodes.items[i]->bypass = bypass;
            return true;
        }
    }
    return false;
}

// --------------------------------------------------------
// DAG 连接管理
// DAG connection management
// --------------------------------------------------------

bool maidmic_pipeline_dag_connect(
    maidmic_pipeline_t* pipeline,
    uint32_t from_node_id,
    uint32_t to_node_id)
{
    if (!pipeline || pipeline->mode != MAIDMIC_PIPELINE_MODE_DAG) return false;
    if (from_node_id == to_node_id) return false;

    maidmic_dag_node_t* from_node = NULL;
    maidmic_dag_node_t* to_node = NULL;

    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        if (pipeline->nodes.items[i]->node_id == from_node_id) from_node = pipeline->nodes.items[i];
        if (pipeline->nodes.items[i]->node_id == to_node_id) to_node = pipeline->nodes.items[i];
    }

    if (!from_node || !to_node) return false;

    for (uint32_t i = 0; i < from_node->outgoing_edge_count; i++) {
        if (from_node->outgoing_edge_node_ids[i] == to_node_id) return true;
    }

    uint32_t* new_out = (uint32_t*)realloc(
        from_node->outgoing_edge_node_ids,
        (from_node->outgoing_edge_count + 1) * sizeof(uint32_t));
    if (!new_out) return false;
    new_out[from_node->outgoing_edge_count++] = to_node_id;
    from_node->outgoing_edge_node_ids = new_out;

    uint32_t* new_in = (uint32_t*)realloc(
        to_node->incoming_edge_node_ids,
        (to_node->incoming_edge_count + 1) * sizeof(uint32_t));
    if (!new_in) return false;
    new_in[to_node->incoming_edge_count++] = from_node_id;
    to_node->incoming_edge_node_ids = new_in;

    return true;
}

bool maidmic_pipeline_dag_disconnect(
    maidmic_pipeline_t* pipeline,
    uint32_t from_node_id,
    uint32_t to_node_id)
{
    if (!pipeline || pipeline->mode != MAIDMIC_PIPELINE_MODE_DAG) return false;

    maidmic_dag_node_t* from_node = NULL;
    maidmic_dag_node_t* to_node = NULL;

    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        if (pipeline->nodes.items[i]->node_id == from_node_id) from_node = pipeline->nodes.items[i];
        if (pipeline->nodes.items[i]->node_id == to_node_id) to_node = pipeline->nodes.items[i];
    }

    if (!from_node || !to_node) return false;

    for (uint32_t i = 0; i < from_node->outgoing_edge_count; i++) {
        if (from_node->outgoing_edge_node_ids[i] == to_node_id) {
            from_node->outgoing_edge_node_ids[i] = from_node->outgoing_edge_node_ids[--from_node->outgoing_edge_count];
            break;
        }
    }

    for (uint32_t i = 0; i < to_node->incoming_edge_count; i++) {
        if (to_node->incoming_edge_node_ids[i] == from_node_id) {
            to_node->incoming_edge_node_ids[i] = to_node->incoming_edge_node_ids[--to_node->incoming_edge_count];
            break;
        }
    }

    return true;
}

// --------------------------------------------------------
// 复位与延迟
// Reset and latency
// --------------------------------------------------------

void maidmic_pipeline_reset(maidmic_pipeline_t* pipeline) {
    if (!pipeline) return;
    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        maidmic_dag_node_t* node = pipeline->nodes.items[i];
        if (node->module && node->module->vtable && node->module->vtable->reset) {
            node->module->vtable->reset(node->userdata);
        }
    }
}

float maidmic_pipeline_get_latency_ms(const maidmic_pipeline_t* pipeline) {
    if (!pipeline) return 0.0f;
    return pipeline->estimated_latency_ms;
}
