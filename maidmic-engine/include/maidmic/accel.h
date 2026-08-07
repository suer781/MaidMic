// maidmic-engine/include/maidmic/accel.h
// MaidMic 加速器抽象层（AAL）
// MaidMic Accelerator Abstraction Layer (AAL)
//
// 统一的加速器探测/路由/统计接口，让引擎在"有 NPU"与"无 NPU"
// 两种环境下行为完全一致（CPU 兜底原则）。
// Unified accelerator probe/route/statistics interface.
// CPU is always the fallback: with or without an NPU the
// functional behavior must be identical.
//
// 本层不直接调用 NNAPI：Android 的 NNAPI DeviceManager 是
// Java/Kotlin API，探测结果由 JNI 层经 maidmic_accel_set_npu()
// 或 maidmic_accel_mark_cpu_only() 写入，这里只做状态维护、
// 任务路由与耗时统计。
// This layer never calls NNAPI directly: the JNI layer feeds
// probe results in via the setters; we only keep state,
// route tasks and collect statistics.

#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// ============================================================
// 加速器状态
// Accelerator state
// ============================================================

typedef enum {
    MAIDMIC_ACCEL_UNKNOWN = 0,      // 尚未探测（初始态）
    MAIDMIC_ACCEL_CPU_ONLY,         // 探测完成：无可用 NPU
    MAIDMIC_ACCEL_NPU_AVAILABLE,    // 探测完成：NPU 可用
} maidmic_accel_state_t;

// 任务可运行的设备（路由结果）
// Device a task may run on (routing result)
typedef enum {
    MAIDMIC_ACCEL_DEV_CPU = 0,      // 纯 CPU
    MAIDMIC_ACCEL_DEV_NPU,          // NPU 加速
} maidmic_accel_device_t;

// ============================================================
// 任务类型（用于路由决策）
// Task types (for routing decisions)
// ============================================================

typedef enum {
    MAIDMIC_ACCEL_TASK_VOICE_CONVERSION = 0,  // 变声/语音转换
    MAIDMIC_ACCEL_TASK_VOICEPRINT_SCORE,      // 声纹相似度打分
} maidmic_accel_task_t;

// ============================================================
// 加速器信息结构
// Accelerator info structure
// ============================================================
// 建议由调用方（如 JNI 层）持有一个全局实例并传入各 API。
// The caller (e.g. JNI layer) is expected to own one global
// instance and pass it into every API.

#define MAIDMIC_ACCEL_NPU_NAME_MAX   64   // NPU 名称缓冲
#define MAIDMIC_ACCEL_NPU_VENDOR_MAX 32   // 厂商标识缓冲，如 "qcom"

typedef struct {
    maidmic_accel_state_t state;      // 加速器状态
    char npu_name[MAIDMIC_ACCEL_NPU_NAME_MAX];    // 如 "Snapdragon Hexagon NPU"
    char npu_vendor[MAIDMIC_ACCEL_NPU_VENDOR_MAX];// 如 "qcom"（空串 = 未知）
    uint64_t cpu_total_ns;            // CPU 处理总耗时（ns）
    uint64_t npu_total_ns;            // NPU 处理总耗时（ns）
    uint64_t cpu_call_count;          // CPU 处理调用次数
    uint64_t npu_call_count;          // NPU 处理调用次数
} maidmic_accel_info_t;

// ============================================================
// API
// ============================================================

// 初始化：置 UNKNOWN 状态，清空名称与统计（各字段清零）
// Initialize: state = UNKNOWN, clear names and counters
void maidmic_accel_init(maidmic_accel_info_t* info);

// 由 JNI 层在 Kotlin 探测到 NPU 后调用，记录厂商与名称并置 NPU 可用。
// 之后 maidmic_accel_route() 可能把可加速任务路由到 NPU。
// Called by JNI after Kotlin detects an NPU; stores vendor/name.
void maidmic_accel_set_npu(maidmic_accel_info_t* info, const char* vendor, const char* name);

// 探测完成但无 NPU：置 CPU_ONLY 状态。
// Called when probing finished without any NPU.
void maidmic_accel_mark_cpu_only(maidmic_accel_info_t* info);

// NPU 是否可用（state == MAIDMIC_ACCEL_NPU_AVAILABLE）
// Whether the NPU is available
bool maidmic_accel_is_npu_available(const maidmic_accel_info_t* info);

// 任务路由：返回该任务应运行的设备（CPU 或 NPU）。
// 默认/无 NPU 时一律返回 CPU（CPU 兜底）。
// NPU 可用且任务类型在"可加速列表"中时才返回 NPU；
// 本次可加速列表为空（仅预留），故当前恒返回 CPU。
// Route a task to CPU or NPU. CPU is always the fallback.
// NPU is returned only when available AND the task is in the
// (currently empty) accelerate list — so it always returns CPU today.
maidmic_accel_device_t maidmic_accel_route(const maidmic_accel_info_t* info, maidmic_accel_task_t task);

// 记录一次处理耗时与调用次数（按 used_npu 归入 CPU 或 NPU 统计）。
// Record one processing duration and bump the matching counter.
void maidmic_accel_record_time(maidmic_accel_info_t* info, bool used_npu, uint64_t elapsed_ns);

#ifdef __cplusplus
}
#endif
