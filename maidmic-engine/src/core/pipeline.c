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
#include <time.h>  // clock_gettime(CLOCK_MONOTONIC)：处理耗时统计（仅 debug 构建）

// 轻量可移植自旋锁：保护管线状态（模块增删/参数/旁路/模式切换）与
// process 并发访问。UI 线程（add/remove/set_param/set_bypass/set_mode）与
// 录音线程（process）可能同时操作默认管线，add/remove 会 realloc+free 节点数组，
// process 若解引用已释放节点将 use-after-free 崩溃。
// Lightweight portable spinlock guarding pipeline state against concurrent
// mutation (add/remove/set_param/bypass/mode) and audio processing.
#include <stdatomic.h>
#ifdef _WIN32
#include <immintrin.h>
#else
#include <sched.h>
#endif

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
    atomic_flag lock;                // 并发访问自旋锁
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

    // 处理性能统计（SubTask 1.5）
    // Processing performance stats
    uint64_t stats_total_ns;      // 累计处理耗时（纳秒）
    uint64_t stats_total_frames;  // 累计处理帧数（每声道样本数之和）
    uint64_t stats_call_count;    // process 调用次数
    uint64_t stats_last_frame_ns; // 最近一帧处理耗时（纳秒）
};

// ============================================================
// 锁操作
// Lock operations
// ============================================================

static inline void pipeline_lock(maidmic_pipeline_t* pipeline) {
    while (atomic_flag_test_and_set_explicit(&pipeline->lock, memory_order_acquire)) {
#ifdef _WIN32
        _mm_pause();
#else
        sched_yield();
#endif
    }
}

static inline void pipeline_unlock(maidmic_pipeline_t* pipeline) {
    atomic_flag_clear_explicit(&pipeline->lock, memory_order_release);
}

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
            // DAG 边数组（节点销毁时一并释放，避免泄漏）
            free(arr->items[i]->incoming_edge_node_ids);
            free(arr->items[i]->outgoing_edge_node_ids);
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
    if (!in_degree) return false;
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
    if (!queue) {
        free(in_degree);
        return false;
    }
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

    atomic_flag_clear(&pipeline->lock);
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
    pipeline_lock(pipeline);
    node_array_destroy(&pipeline->nodes);
    free(pipeline->work_buffer);
    pipeline_unlock(pipeline);
    free(pipeline);
}

// --------------------------------------------------------
// 模式切换
// Mode switching
// --------------------------------------------------------

bool maidmic_pipeline_set_mode(maidmic_pipeline_t* pipeline, maidmic_pipeline_mode_t mode) {
    if (!pipeline) return false;

    pipeline_lock(pipeline);
    if (pipeline->mode == mode) {
        pipeline_unlock(pipeline);
        return true;
    }

    maidmic_pipeline_mode_t old_mode = pipeline->mode;
    pipeline->mode = mode;

    // 切换模式后重新计算拓扑；拓扑排序失败（存在环）则回滚模式并拒绝切换
    if (!topological_sort(pipeline)) {
        pipeline->mode = old_mode;
        topological_sort(pipeline);  // 恢复旧模式的拓扑
        pipeline_unlock(pipeline);
        return false;
    }
    pipeline_unlock(pipeline);

    // 通知 UI 层
    if (pipeline->callbacks.on_mode_changed) {
        pipeline->callbacks.on_mode_changed(pipeline, mode);
    }

    return true;
}

maidmic_pipeline_mode_t maidmic_pipeline_get_mode(const maidmic_pipeline_t* pipeline) {
    if (!pipeline) return MAIDMIC_PIPELINE_MODE_SIMPLE;
    pipeline_lock((maidmic_pipeline_t*)pipeline);
    maidmic_pipeline_mode_t mode = pipeline->mode;
    pipeline_unlock((maidmic_pipeline_t*)pipeline);
    return mode;
}

// --------------------------------------------------------
// 模块管理
// Module management
// --------------------------------------------------------

uint32_t maidmic_pipeline_add_module(maidmic_pipeline_t* pipeline, const maidmic_module_t* module) {
    if (!pipeline || !module) return 0;

    // 创建节点（模块 create/setup 回调在锁外执行，避免持锁调用可能回入管线的回调）
    maidmic_dag_node_t* node = (maidmic_dag_node_t*)calloc(1, sizeof(maidmic_dag_node_t));
    if (!node) return 0;

    node->node_id = pipeline->next_node_id++;
    node->module = module;
    node->bypass = false;

    // 调用模块的 create 回调
    if (module->vtable && module->vtable->create) {
        node->userdata = module->vtable->create();
    }
    if (!node->userdata) {
        // create 失败：不挂载（避免后续 process 解引用 NULL userdata）
        free(node);
        return 0;
    }

    // 调用 setup 设置采样率和声道数；setup 失败（如 OOM）则不挂载该节点
    if (module->vtable && module->vtable->setup) {
        if (!module->vtable->setup(node->userdata, pipeline->sample_rate, pipeline->channels)) {
            if (module->vtable->destroy) module->vtable->destroy(node->userdata);
            free(node);
            return 0;
        }
    }

    // 缓存参数
    if (module->vtable && module->vtable->get_param_count && module->vtable->get_param_info) {
        node->param_count = module->vtable->get_param_count(node->userdata);
        node->params = (maidmic_param_t*)calloc(node->param_count, sizeof(maidmic_param_t));
        if (node->params == NULL && node->param_count > 0) {
            if (module->vtable->destroy) module->vtable->destroy(node->userdata);
            free(node);
            return 0;
        }
        for (uint32_t i = 0; i < node->param_count; i++) {
            node->params[i] = *module->vtable->get_param_info(node->userdata, i);
        }
    }

    pipeline_lock(pipeline);
    node->topo_order = pipeline->nodes.count;
    if (!node_array_add(&pipeline->nodes, node)) {
        pipeline_unlock(pipeline);
        if (module->vtable->destroy) module->vtable->destroy(node->userdata);
        free(node->params);
        free(node);
        return 0;
    }
    topological_sort(pipeline);
    pipeline_unlock(pipeline);

    if (pipeline->callbacks.on_module_added) {
        pipeline->callbacks.on_module_added(pipeline, node->node_id, module->name);
    }

    return node->node_id;
}

bool maidmic_pipeline_remove_module(maidmic_pipeline_t* pipeline, uint32_t node_id) {
    if (!pipeline) return false;

    pipeline_lock(pipeline);
    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        if (pipeline->nodes.items[i]->node_id == node_id) {
            maidmic_dag_node_t* node = pipeline->nodes.items[i];

            // 调用 destroy
            if (node->module->vtable->destroy && node->userdata) {
                node->module->vtable->destroy(node->userdata);
            }
            // DAG 边数组随节点释放
            free(node->incoming_edge_node_ids);
            free(node->outgoing_edge_node_ids);
            free(node->params);
            free(node);

            // 从数组中移除（用最后一个元素覆盖）
            pipeline->nodes.items[i] = pipeline->nodes.items[--pipeline->nodes.count];

            topological_sort(pipeline);
            pipeline_unlock(pipeline);

            if (pipeline->callbacks.on_module_removed) {
                pipeline->callbacks.on_module_removed(pipeline, node_id);
            }

            return true;
        }
    }
    pipeline_unlock(pipeline);
    return false;
}

// --------------------------------------------------------
// 参数操作
// Parameter operations
// --------------------------------------------------------

// 按 node_id 查找节点（调用方须已持有锁）
static maidmic_dag_node_t* find_node_by_id_unlocked(maidmic_pipeline_t* pipeline, uint32_t node_id) {
    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        if (pipeline->nodes.items[i]->node_id == node_id) {
            return pipeline->nodes.items[i];
        }
    }
    return NULL;
}

bool maidmic_pipeline_set_param(maidmic_pipeline_t* pipeline, uint32_t node_id, const char* key, maidmic_param_t value) {
    if (!pipeline) return false;

    pipeline_lock(pipeline);
    maidmic_dag_node_t* node = find_node_by_id_unlocked(pipeline, node_id);
    if (!node || !node->module->vtable->set_param) {
        pipeline_unlock(pipeline);
        return false;
    }

    bool result = node->module->vtable->set_param(node->userdata, key, value);
    pipeline_unlock(pipeline);

    if (result && pipeline->callbacks.on_param_changed) {
        pipeline->callbacks.on_param_changed(pipeline, node_id, key, value);
    }

    return result;
}

maidmic_param_t maidmic_pipeline_get_param(const maidmic_pipeline_t* pipeline, uint32_t node_id, const char* key) {
    maidmic_param_t empty = {0};
    if (!pipeline) return empty;

    pipeline_lock((maidmic_pipeline_t*)pipeline);
    const maidmic_dag_node_t* node = find_node_by_id_unlocked((maidmic_pipeline_t*)pipeline, node_id);
    if (!node || !node->module->vtable->get_param) {
        pipeline_unlock((maidmic_pipeline_t*)pipeline);
        return empty;
    }
    maidmic_param_t result = node->module->vtable->get_param(node->userdata, key);
    pipeline_unlock((maidmic_pipeline_t*)pipeline);
    return result;
}

// --------------------------------------------------------
// 核心：音频处理
// Core: audio processing
// ============================================================
// 遍历管线中所有模块，按拓扑序处理音频帧。
// 策略：先把输入复制到工作缓冲区，然后所有模块原地处理工作缓冲区，
// 最后把工作缓冲区复制到输出。这样：
//   - 所有模块只需支持原地处理（input == output）
//   - bypass 模块自动跳过（工作缓冲区保持上一个模块的输出）
//   - 没有模块时直通复制
//
// 并发：全程持锁遍历节点。内置模块的 process 回调不会回入管线，
// 因此持锁调用是安全的。

bool maidmic_pipeline_process(maidmic_pipeline_t* pipeline, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    if (!pipeline || !input || !output) return false;

    pipeline_lock(pipeline);

#ifdef MAIDMIC_ENABLE_TIMING_STATS
    // 处理耗时统计：块首计时（SubTask 1.5；仅 debug 构建开启，避免音频热路径系统调用）
    struct timespec t_start, t_end;
    clock_gettime(CLOCK_MONOTONIC, &t_start);
#endif

    bool ok = true;

    // 没有模块，直通
    if (pipeline->nodes.count == 0) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        goto stats_done;
    }

    if (pipeline->mode == MAIDMIC_PIPELINE_MODE_SIMPLE) {
        // ============ SIMPLE 模式：零分配零双拷贝快速路径 ============
        // SIMPLE 模式下 nodes.items 数组顺序即处理顺序（insert/swap 直接改数组，
        // 见 maidmic_pipeline_insert_module / maidmic_pipeline_swap_modules）。
        // 直接用输出缓冲区作为模块链工作区（所有模块均支持 output != input，
        // 第一个非 bypass 模块负责从 input 拷贝/处理到 output，后续模块原地处理），
        // 消除 work_buffer 的两次全量 memcpy 与 order 数组的每块 malloc/free。
        maidmic_buffer_t work_buf = *input;
        work_buf.data = output->data;
        work_buf.data_bytes = input->data_bytes;
        work_buf.owned = false;

        bool first = true;          // 是否首个实际处理的模块
        bool any_processed = false; // 是否有模块真正处理过（含拷贝语义）
        for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
            maidmic_dag_node_t* node = pipeline->nodes.items[i];
            if (node->bypass) continue;
            if (!node->module || !node->module->vtable || !node->module->vtable->process) continue;
            if (first) {
                // 第一个非 bypass 模块：从 input 读，输出到 output（模块内部完成拷贝/处理）
                node->module->vtable->process(node->userdata, input, &work_buf);
                first = false;
            } else {
                node->module->vtable->process(node->userdata, &work_buf, &work_buf);
            }
            any_processed = true;
        }

        // 全部模块都 bypass 或没有可处理的模块：手动拷贝一次
        if (!any_processed && output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        goto stats_done;
    }

    // ============ DAG 模式：work_buffer 中间流 + 拓扑排序 ============
    // 确保工作缓冲区足够大
    size_t needed = input->data_bytes;
    if (needed > pipeline->work_buffer_size) {
        char* new_buf = (char*)realloc(pipeline->work_buffer, needed);
        if (!new_buf) { ok = false; goto stats_done; }
        pipeline->work_buffer = new_buf;
        pipeline->work_buffer_size = needed;
    }

    // 把输入复制到工作缓冲区（后续所有模块原地处理）
    memcpy(pipeline->work_buffer, input->data, input->data_bytes);

    // 构建工作缓冲区描述符（input == output == work_buffer，原地处理）
    maidmic_buffer_t work_buf = *input;
    work_buf.data = pipeline->work_buffer;
    work_buf.data_bytes = input->data_bytes;
    work_buf.owned = false;

    // 校验 topo_order 是 [0, count) 的排列：拓扑排序失败（环）或节点增删后
    // 陈旧值会导致 order[topo_order] 越界写，这里在构建 order 前拦截。
    bool topo_valid = true;
    for (uint32_t i = 0; i < pipeline->nodes.count && topo_valid; i++) {
        if (pipeline->nodes.items[i]->topo_order >= pipeline->nodes.count) {
            topo_valid = false;
        }
    }
    if (topo_valid) {
        for (uint32_t i = 0; i < pipeline->nodes.count && topo_valid; i++) {
            for (uint32_t j = i + 1; j < pipeline->nodes.count; j++) {
                if (pipeline->nodes.items[i]->topo_order == pipeline->nodes.items[j]->topo_order) {
                    topo_valid = false;
                    break;
                }
            }
        }
    }
    if (!topo_valid) {
        ok = false;
        goto stats_done;
    }

    // 按拓扑序构建处理顺序索引数组（栈上数组，节点数超 64 才退回堆分配）
    uint32_t order_stack[64];
    uint32_t* order = order_stack;
    uint32_t* heap_order = NULL;
    if (pipeline->nodes.count > 64) {
        heap_order = (uint32_t*)malloc(pipeline->nodes.count * sizeof(uint32_t));
        if (!heap_order) { ok = false; goto stats_done; }
        order = heap_order;
    }
    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        order[pipeline->nodes.items[i]->topo_order] = i;
    }

    // 依次调用每个非 bypass 模块的 process（原地处理）
    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        maidmic_dag_node_t* node = pipeline->nodes.items[order[i]];
        if (node->bypass) continue;
        if (!node->module || !node->module->vtable || !node->module->vtable->process) continue;
        node->module->vtable->process(node->userdata, &work_buf, &work_buf);
    }

    // 把工作缓冲区复制到输出
    if (output->data != pipeline->work_buffer) {
        memcpy(output->data, pipeline->work_buffer, input->data_bytes);
    }
    output->meta = input->meta;

    if (heap_order) free(heap_order);

stats_done:
#ifdef MAIDMIC_ENABLE_TIMING_STATS
    // 处理耗时统计：块尾计时并累加（无论成功失败均计入一次调用）
    clock_gettime(CLOCK_MONOTONIC, &t_end);
    uint64_t elapsed_ns =
        (uint64_t)(t_end.tv_sec - t_start.tv_sec) * 1000000000ULL +
        (uint64_t)(t_end.tv_nsec - t_start.tv_nsec);
    pipeline->stats_total_ns += elapsed_ns;
    pipeline->stats_last_frame_ns = elapsed_ns;
#endif
    // 累计帧数按"每声道样本数之和"计（与 struct 注释一致）
    pipeline->stats_total_frames += (uint64_t)input->meta.frame_count * input->meta.channels;
    pipeline->stats_call_count++;
    pipeline_unlock(pipeline);
    return ok;
}

// --------------------------------------------------------
// 查询函数
// Query functions
// --------------------------------------------------------

uint32_t maidmic_pipeline_get_module_count(const maidmic_pipeline_t* pipeline) {
    if (!pipeline) return 0;
    pipeline_lock((maidmic_pipeline_t*)pipeline);
    uint32_t count = pipeline->nodes.count;
    pipeline_unlock((maidmic_pipeline_t*)pipeline);
    return count;
}

const maidmic_dag_node_t* maidmic_pipeline_get_module_at(const maidmic_pipeline_t* pipeline, uint32_t index) {
    if (!pipeline) return NULL;
    pipeline_lock((maidmic_pipeline_t*)pipeline);
    const maidmic_dag_node_t* node = (index < pipeline->nodes.count) ? pipeline->nodes.items[index] : NULL;
    pipeline_unlock((maidmic_pipeline_t*)pipeline);
    return node;
}

const maidmic_dag_node_t* maidmic_pipeline_get_module_by_id(const maidmic_pipeline_t* pipeline, uint32_t node_id) {
    if (!pipeline) return NULL;
    pipeline_lock((maidmic_pipeline_t*)pipeline);
    const maidmic_dag_node_t* node = find_node_by_id_unlocked((maidmic_pipeline_t*)pipeline, node_id);
    pipeline_unlock((maidmic_pipeline_t*)pipeline);
    return node;
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

    // 先按 add_module 完整创建节点（create/setup/参数缓存/回调），追加到数组末尾
    uint32_t node_id = maidmic_pipeline_add_module(pipeline, module);
    if (node_id == 0) return 0;

    if (pipeline->mode == MAIDMIC_PIPELINE_MODE_SIMPLE) {
        // SIMPLE 模式：SIMPLE 快速路径按 nodes.items 数组顺序处理（不看 topo_order），
        // 因此必须直接调整数组顺序：把刚追加到末尾的新节点 memmove 平移到
        // before_node_id 所在位置之前，使处理顺序数组本身改变。
        pipeline_lock(pipeline);
        uint32_t count = pipeline->nodes.count;
        int new_idx = -1;       // 新节点当前所在索引
        int insert_idx = -1;    // before_node_id 所在索引
        for (uint32_t i = 0; i < count; i++) {
            if (pipeline->nodes.items[i]->node_id == node_id) new_idx = (int)i;
            if (pipeline->nodes.items[i]->node_id == before_node_id) insert_idx = (int)i;
        }
        if (new_idx >= 0 && insert_idx >= 0 && insert_idx != new_idx) {
            maidmic_dag_node_t* moved = pipeline->nodes.items[new_idx];
            // 把 [insert_idx, new_idx) 区间的指针右移一格，新节点落到 insert_idx
            memmove(&pipeline->nodes.items[insert_idx + 1],
                    &pipeline->nodes.items[insert_idx],
                    (size_t)(new_idx - (uint32_t)insert_idx) * sizeof(maidmic_dag_node_t*));
            pipeline->nodes.items[insert_idx] = moved;
            // 数组顺序已变化，topo_order 在 SIMPLE 模式下无实际用途，但保持同步便于调试
            for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
                pipeline->nodes.items[i]->topo_order = i;
            }
        }
        pipeline_unlock(pipeline);
    }
    // DAG 模式：add_module 已按拓扑序排好，保持原有 topo_order 逻辑不动

    return node_id;
}

bool maidmic_pipeline_swap_modules(
    maidmic_pipeline_t* pipeline,
    uint32_t node_id_a,
    uint32_t node_id_b)
{
    if (!pipeline || node_id_a == node_id_b) return false;

    pipeline_lock(pipeline);
    int idx_a = -1;
    int idx_b = -1;

    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        if (pipeline->nodes.items[i]->node_id == node_id_a) idx_a = (int)i;
        if (pipeline->nodes.items[i]->node_id == node_id_b) idx_b = (int)i;
    }

    if (idx_a < 0 || idx_b < 0) {
        pipeline_unlock(pipeline);
        return false;
    }

    if (pipeline->mode == MAIDMIC_PIPELINE_MODE_SIMPLE) {
        // SIMPLE 模式：交换 nodes.items 中的指针，使处理顺序数组本身改变
        maidmic_dag_node_t* tmp = pipeline->nodes.items[idx_a];
        pipeline->nodes.items[idx_a] = pipeline->nodes.items[idx_b];
        pipeline->nodes.items[idx_b] = tmp;
        // 数组顺序已变化，topo_order 保持同步便于调试
        for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
            pipeline->nodes.items[i]->topo_order = i;
        }
    } else {
        // DAG 模式：保持原有 topo_order 交换逻辑不动
        uint32_t temp_order = pipeline->nodes.items[idx_a]->topo_order;
        pipeline->nodes.items[idx_a]->topo_order = pipeline->nodes.items[idx_b]->topo_order;
        pipeline->nodes.items[idx_b]->topo_order = temp_order;
    }
    pipeline_unlock(pipeline);

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
    pipeline_lock(pipeline);
    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        if (pipeline->nodes.items[i]->node_id == node_id) {
            pipeline->nodes.items[i]->bypass = bypass;
            pipeline_unlock(pipeline);
            return true;
        }
    }
    pipeline_unlock(pipeline);
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

    pipeline_lock(pipeline);
    maidmic_dag_node_t* from_node = NULL;
    maidmic_dag_node_t* to_node = NULL;

    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        if (pipeline->nodes.items[i]->node_id == from_node_id) from_node = pipeline->nodes.items[i];
        if (pipeline->nodes.items[i]->node_id == to_node_id) to_node = pipeline->nodes.items[i];
    }

    if (!from_node || !to_node) {
        pipeline_unlock(pipeline);
        return false;
    }

    for (uint32_t i = 0; i < from_node->outgoing_edge_count; i++) {
        if (from_node->outgoing_edge_node_ids[i] == to_node_id) {
            pipeline_unlock(pipeline);
            return true;
        }
    }

    uint32_t* new_out = (uint32_t*)realloc(
        from_node->outgoing_edge_node_ids,
        (from_node->outgoing_edge_count + 1) * sizeof(uint32_t));
    if (!new_out) {
        pipeline_unlock(pipeline);
        return false;
    }
    new_out[from_node->outgoing_edge_count++] = to_node_id;
    from_node->outgoing_edge_node_ids = new_out;

    uint32_t* new_in = (uint32_t*)realloc(
        to_node->incoming_edge_node_ids,
        (to_node->incoming_edge_count + 1) * sizeof(uint32_t));
    if (!new_in) {
        pipeline_unlock(pipeline);
        return false;
    }
    new_in[to_node->incoming_edge_count++] = from_node_id;
    to_node->incoming_edge_node_ids = new_in;

    pipeline_unlock(pipeline);
    return true;
}

bool maidmic_pipeline_dag_disconnect(
    maidmic_pipeline_t* pipeline,
    uint32_t from_node_id,
    uint32_t to_node_id)
{
    if (!pipeline || pipeline->mode != MAIDMIC_PIPELINE_MODE_DAG) return false;

    pipeline_lock(pipeline);
    maidmic_dag_node_t* from_node = NULL;
    maidmic_dag_node_t* to_node = NULL;

    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        if (pipeline->nodes.items[i]->node_id == from_node_id) from_node = pipeline->nodes.items[i];
        if (pipeline->nodes.items[i]->node_id == to_node_id) to_node = pipeline->nodes.items[i];
    }

    if (!from_node || !to_node) {
        pipeline_unlock(pipeline);
        return false;
    }

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

    pipeline_unlock(pipeline);
    return true;
}

// --------------------------------------------------------
// 复位与延迟
// Reset and latency
// --------------------------------------------------------

void maidmic_pipeline_reset(maidmic_pipeline_t* pipeline) {
    if (!pipeline) return;
    pipeline_lock(pipeline);
    for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
        maidmic_dag_node_t* node = pipeline->nodes.items[i];
        if (node->module && node->module->vtable && node->module->vtable->reset) {
            node->module->vtable->reset(node->userdata);
        }
    }
    pipeline_unlock(pipeline);
}

float maidmic_pipeline_get_latency_ms(const maidmic_pipeline_t* pipeline) {
    if (!pipeline) return 0.0f;
    pipeline_lock((maidmic_pipeline_t*)pipeline);
    float latency = pipeline->estimated_latency_ms;
    pipeline_unlock((maidmic_pipeline_t*)pipeline);
    return latency;
}

// --------------------------------------------------------
// 处理性能统计
// Processing performance stats
// --------------------------------------------------------

void maidmic_pipeline_get_stats(const maidmic_pipeline_t* pipeline,
                                uint64_t* total_ns,
                                uint64_t* total_frames,
                                uint64_t* call_count) {
    if (!pipeline) return;
    pipeline_lock((maidmic_pipeline_t*)pipeline);
    if (total_ns)     *total_ns     = pipeline->stats_total_ns;
    if (total_frames) *total_frames = pipeline->stats_total_frames;
    if (call_count)   *call_count   = pipeline->stats_call_count;
    pipeline_unlock((maidmic_pipeline_t*)pipeline);
}