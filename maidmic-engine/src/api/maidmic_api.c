// maidmic-engine/src/api/maidmic_api.c
// MaidMic C API exports — missing pipeline.h implementations
// MaidMic C API 导出 — pipeline.h 中缺失的函数实现
//
// pipeline.c 实现了大部分管线 API，
// 这个文件补全剩下声明但未实现的函数。

#include "maidmic/pipeline.h"
#include <stdlib.h>
#include <string.h>

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

    // 先添加到末尾，然后通过拓扑排序重新排序
    uint32_t node_id = maidmic_pipeline_add_module(pipeline, module);

    // 在 SIMPLE 模式下，把新模块移到指定位置之前
    if (pipeline->mode == MAIDMIC_PIPELINE_MODE_SIMPLE) {
        // 找到 before_node_id 的位置
        int insert_idx = -1;
        int new_idx = -1;
        for (uint32_t i = 0; i < pipeline->nodes.count; i++) {
            if (pipeline->nodes.items[i]->node_id == before_node_id) {
                insert_idx = (int)i;
            }
            if (pipeline->nodes.items[i]->node_id == node_id) {
                new_idx = (int)i;
            }
        }

        if (insert_idx >= 0 && new_idx >= 0 && new_idx != insert_idx) {
            // 交换拓扑序，使新模块在 insert_idx 的位置
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

    // 交换拓扑序
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

    // 检查是否已经连接
    for (uint32_t i = 0; i < from_node->outgoing_edge_count; i++) {
        if (from_node->outgoing_edge_node_ids[i] == to_node_id) return true; // 已连接
    }

    // 添加边
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

    // 从 from_node 的出边中移除
    for (uint32_t i = 0; i < from_node->outgoing_edge_count; i++) {
        if (from_node->outgoing_edge_node_ids[i] == to_node_id) {
            from_node->outgoing_edge_node_ids[i] = from_node->outgoing_edge_node_ids[--from_node->outgoing_edge_count];
            break;
        }
    }

    // 从 to_node 的入边中移除
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
