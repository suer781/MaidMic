// maidmic-engine/src/core/ring_buffer.c
// Echio 引擎环形缓冲区
// Echio Engine Ring Buffer
//
// 无锁、单生产者单消费者 (SPSC) 环形缓冲区。
// Lock-free, single-producer single-consumer (SPSC) ring buffer.
//
// 这是整个引擎的基石——所有音频 IO 都通过 RingBuffer 流动。
// 系统麦克风写入、DSP 处理读取、虚拟麦克风输出读取，全部走这里。
// This is the foundation of the engine — all audio IO flows through RingBuffer.

#include "maidmic/types.h"
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>

// ============================================================
// RingBuffer 结构
// ============================================================
// 使用内存屏障（atomic）实现无锁 SPSC，避免音频线程被锁阻塞。
// 缓冲区大小必须是 2 的幂，用位运算替代取模。
// Uses memory barriers (atomic) for lock-free SPSC to avoid audio thread blocking.

typedef struct {
    // --- 原子读写指针（无锁核心） ---
    // write_index: 生产者（音频输入线程）写入的位置
    // read_index:  消费者（DSP 处理线程）读取的位置
    // 两者都是原子变量，各自只写自己的，只读对方的
    atomic_uint_fast32_t write_index;   // 写指针（生产者更新）
    atomic_uint_fast32_t read_index;    // 读指针（消费者更新）
    
    // --- 缓冲区参数 ---
    uint32_t capacity;                  // 缓冲区容量（帧数，必须是 2 的幂）
    uint32_t mask;                      // capacity - 1，用于位运算取模
    size_t frame_size;                  // 每帧字节数 = channels * sample_size
	
    // --- 音频格式 ---
    uint32_t sample_rate;
    uint16_t channels;
    uint32_t frame_capacity;            // 最大可存储帧数 = capacity - 1（留一个空位区分满/空）
    
    // --- 统计（用户可读） ---
    atomic_uint_64_t frames_written;    // 总写入帧数
    atomic_uint_64_t frames_read;       // 总读取帧数
    atomic_uint_32_t underrun_count;    // 欠载次数（消费者追上了生产者）
    atomic_uint_32_t overrun_count;     // 过载次数（生产者覆盖了未读数据）
    
    // --- 数据缓冲区 ---
    // 使用柔性数组成员，缓冲区紧跟在结构体后面，一次 malloc
    // Flexible array member, buffer follows struct in single malloc
    char buffer[];                      // 实际数据缓冲区
} ring_buffer_t;

// ============================================================
// 创建 RingBuffer
// ============================================================
// capacity: 缓冲区帧数（必须是 2 的幂！）
// frame_size: 每帧字节数
//
// 注意：实际可用容量是 capacity - 1（留一个槽区分满/空）
// Note: actual usable capacity is capacity - 1

ring_buffer_t* ring_buffer_create(uint32_t capacity, size_t frame_size) {
    // 确保 capacity 是 2 的幂
    // Ensure capacity is a power of 2
    if (capacity < 2 || (capacity & (capacity - 1)) != 0) {
        return NULL;
    }
    
    // 一次分配结构体 + 数据缓冲区（减少内存碎片）
    // Single allocation for struct + data buffer (less fragmentation)
    ring_buffer_t* rb = (ring_buffer_t*)malloc(sizeof(ring_buffer_t) + capacity * frame_size);
    if (!rb) return NULL;
    
    rb->capacity = capacity;
    rb->mask = capacity - 1;
    rb->frame_size = frame_size;
    rb->frame_capacity = capacity - 1;  // 留一个空位
    
    atomic_init(&rb->write_index, 0);
    atomic_init(&rb->read_index, 0);
    atomic_init(&rb->frames_written, 0);
    atomic_init(&rb->frames_read, 0);
    atomic_init(&rb->underrun_count, 0);
    atomic_init(&rb->overrun_count, 0);
    
    memset(rb->buffer, 0, capacity * frame_size);
    
    return rb;
}

// ============================================================
// 销毁 RingBuffer
// ============================================================
void ring_buffer_destroy(ring_buffer_t* rb) {
    free(rb);
}

// ============================================================
// 写入一帧（生产者调用——音频输入线程）
// Write one frame (producer — audio input thread)
// ============================================================
// 返回 true: 写入成功
//      false: 缓冲区满了（overrun），帧被丢弃
// Returns true on success, false on buffer full (overrun, frame dropped)

bool ring_buffer_write(ring_buffer_t* rb, const void* frame_data) {
    // 读取当前读指针（消费者端，用 relaxed 就够了，我们只需要一个快照）
    uint32_t r = atomic_load_explicit(&rb->read_index, memory_order_relaxed);
    uint32_t w = atomic_load_explicit(&rb->write_index, memory_order_relaxed);
    
    // 计算可用空间
    uint32_t available = rb->frame_capacity - ((w - r) & rb->mask);
    
    if (available == 0) {
        // 缓冲区满了，丢弃这一帧
        atomic_fetch_add(&rb->overrun_count, 1);
        return false;
    }
    
    // 写入数据
    memcpy(rb->buffer + (w & rb->mask) * rb->frame_size, frame_data, rb->frame_size);
    
    // 更新写指针（用 release 语义，确保 memcpy 在写指针更新前完成）
    atomic_store_explicit(&rb->write_index, w + 1, memory_order_release);
    atomic_fetch_add(&rb->frames_written, 1);
    
    return true;
}

// ============================================================
// 读取一帧（消费者调用——DSP 处理线程）
// Read one frame (consumer — DSP processing thread)
// ============================================================
// 返回 true: 读取成功
//      false: 缓冲区空了（underrun）
// Returns true on success, false on buffer empty (underrun)

bool ring_buffer_read(ring_buffer_t* rb, void* frame_data) {
    uint32_t r = atomic_load_explicit(&rb->read_index, memory_order_relaxed);
    // 用 acquire 语义读取写指针，确保能看到生产者写入的最新数据
    uint32_t w = atomic_load_explicit(&rb->write_index, memory_order_acquire);
    
    uint32_t available = (w - r) & rb->mask;
    
    if (available == 0) {
        // 缓冲区空了，返回静音帧（填0，不爆音）
        memset(frame_data, 0, rb->frame_size);
        atomic_fetch_add(&rb->underrun_count, 1);
        return false;
    }
    
    memcpy(frame_data, rb->buffer + (r & rb->mask) * rb->frame_size, rb->frame_size);
    
    // 更新读指针
    atomic_store_explicit(&rb->read_index, r + 1, memory_order_release);
    atomic_fetch_add(&rb->frames_read, 1);
    
    return true;
}

// ============================================================
// 批量写入（性能优化：一次写多帧）
// Bulk write (performance optimization: write multiple frames at once)
// ============================================================
uint32_t ring_buffer_write_bulk(ring_buffer_t* rb, const void* data, uint32_t frame_count) {
    uint32_t written = 0;
    const char* src = (const char*)data;
    
    for (uint32_t i = 0; i < frame_count; i++) {
        if (ring_buffer_write(rb, src)) {
            written++;
        }
        src += rb->frame_size;
    }
    
    return written;
}

// ============================================================
// 批量读取（性能优化：一次读多帧）
// Bulk read (performance optimization: read multiple frames at once)
// ============================================================
uint32_t ring_buffer_read_bulk(ring_buffer_t* rb, void* data, uint32_t frame_count) {
    uint32_t read = 0;
    char* dst = (char*)data;
    
    for (uint32_t i = 0; i < frame_count; i++) {
        if (ring_buffer_read(rb, dst)) {
            read++;
        }
        dst += rb->frame_size;
    }
    
    return read;
}

// ============================================================
// 获取当前填充帧数（用于延迟估算和 UI 显示）
// Get current fill level (for latency estimation and UI display)
// ============================================================
uint32_t ring_buffer_get_fill(ring_buffer_t* rb) {
    uint32_t r = atomic_load_explicit(&rb->read_index, memory_order_acquire);
    uint32_t w = atomic_load_explicit(&rb->write_index, memory_order_acquire);
    return (w - r) & rb->mask;
}

// 获取延迟估计（毫秒）
// Get estimated latency (ms)
float ring_buffer_get_latency_ms(ring_buffer_t* rb) {
    uint32_t fill = ring_buffer_get_fill(rb);
    return (float)fill / rb->sample_rate * 1000.0f;
}

// ============================================================
// 清空缓冲区（复位指针）
// Clear buffer (reset pointers)
// ============================================================
void ring_buffer_clear(ring_buffer_t* rb) {
    atomic_store(&rb->write_index, 0);
    atomic_store(&rb->read_index, 0);
    memset(rb->buffer, 0, rb->capacity * rb->frame_size);
}
