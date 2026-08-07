// maidmic-engine/src/accel/accel.c
// MaidMic 加速器抽象层（AAL）实现
// MaidMic Accelerator Abstraction Layer (AAL) implementation
//
// 纯 C 实现：负责加速器状态维护、任务路由与耗时统计。
// 不直接调用 NNAPI（Android 的 NNAPI DeviceManager 是 Java/Kotlin
// API），探测结果由 JNI 层经 setter 写入。
// Pure C: state keeping, task routing and statistics only.
// No direct NNAPI calls — probe results come in via setters.
//
// CPU 兜底原则：无论 NPU 是否存在，引擎功能完全等价；
// 探测失败或任何异常都不会阻塞处理链。

#include "maidmic/accel.h"
#include <string.h>
#include <stdint.h>

// ============================================================
// 初始化
// ============================================================

void maidmic_accel_init(maidmic_accel_info_t* info) {
    if (!info) return;
    memset(info, 0, sizeof(*info));
    info->state = MAIDMIC_ACCEL_UNKNOWN;
}

// ============================================================
// 探测结果写入（由 JNI 层调用）
// ============================================================

void maidmic_accel_set_npu(maidmic_accel_info_t* info, const char* vendor, const char* name) {
    if (!info) return;

    info->state = MAIDMIC_ACCEL_NPU_AVAILABLE;

    // 厂商：截断拷贝，保证 '\0' 结尾（缓冲区大小 - 1）
    info->npu_vendor[0] = '\0';
    if (vendor) {
        strncpy(info->npu_vendor, vendor, MAIDMIC_ACCEL_NPU_VENDOR_MAX - 1);
        info->npu_vendor[MAIDMIC_ACCEL_NPU_VENDOR_MAX - 1] = '\0';
    }

    // 名称：同上
    info->npu_name[0] = '\0';
    if (name) {
        strncpy(info->npu_name, name, MAIDMIC_ACCEL_NPU_NAME_MAX - 1);
        info->npu_name[MAIDMIC_ACCEL_NPU_NAME_MAX - 1] = '\0';
    }
}

void maidmic_accel_mark_cpu_only(maidmic_accel_info_t* info) {
    if (!info) return;
    info->state = MAIDMIC_ACCEL_CPU_ONLY;
}

// ============================================================
// 查询
// ============================================================

bool maidmic_accel_is_npu_available(const maidmic_accel_info_t* info) {
    return info != NULL && info->state == MAIDMIC_ACCEL_NPU_AVAILABLE;
}

// ============================================================
// 任务路由
// ============================================================
// 默认/无 NPU 时一律返回 CPU（CPU 兜底）。
// NPU 可用时，仅当任务类型登记在"可加速列表"中才返回 NPU；
// 本次可加速列表为空（仅预留注释），故当前恒返回 CPU。
// CPU is always the fallback; the accelerate list is empty for
// now (reserved), so this always returns CPU today.

maidmic_accel_device_t maidmic_accel_route(const maidmic_accel_info_t* info, maidmic_accel_task_t task) {
    (void)task;  // 可加速列表为空，暂不依据任务类型决策

    // 未探测 / 无 NPU → CPU 兜底
    if (!info || info->state != MAIDMIC_ACCEL_NPU_AVAILABLE) {
        return MAIDMIC_ACCEL_DEV_CPU;
    }

    // ---- 可加速任务列表（预留）----
    // 未来接入 AI 模型后，在此登记可安全路由到 NPU 的任务类型，
    // 例如：
    //   case MAIDMIC_ACCEL_TASK_VOICE_CONVERSION:
    //       return MAIDMIC_ACCEL_DEV_NPU;
    // 在完成接入与回退验证前，一律保持 CPU。
    // Reserved accelerate list: register tasks here once models
    // are integrated and CPU-fallback verified. Keep CPU until then.
    switch (task) {
        case MAIDMIC_ACCEL_TASK_VOICE_CONVERSION:
        case MAIDMIC_ACCEL_TASK_VOICEPRINT_SCORE:
        default:
            break;
    }
    return MAIDMIC_ACCEL_DEV_CPU;
}

// ============================================================
// 耗时统计
// ============================================================

void maidmic_accel_record_time(maidmic_accel_info_t* info, bool used_npu, uint64_t elapsed_ns) {
    if (!info) return;

    if (used_npu) {
        info->npu_total_ns += elapsed_ns;
        info->npu_call_count++;
    } else {
        info->cpu_total_ns += elapsed_ns;
        info->cpu_call_count++;
    }
}
