// maidmic-engine/src/dsp/pitch.c
// Echio 引擎变调模块
// Echio Engine Pitch Shift Module
//
// 连续变调（历史环 + 拼接交叉淡化）。
// 旧实现"每块从读位置 0 独立重采样"，块边界处输出不连续：
//   升调时块尾填充最后样本 → 每块边界断裂 → 高频"卡卡"；
//   降调时每块循环重读本块内容 → 相邻块重复错位 → 顿挫感。
//
// 本实现改为跨块连续的磁带式变调：
//   - 每声道维护一个历史环（写入新输入），读位置绝对、跨块持续；
//   - 读速率 = 目标比率（2^(semitones/12)），逐样本用平滑器逼近；
//   - 升调（ratio>1）读头逼近写头时"回跳"拼接，降调（ratio<1）读头
//     落后过多时"前跳"拼接，拼接处用 PITCH_WIN 窗口交叉淡化过渡，
//     消除块边界与拼接点的可闻断裂；
//   - 输出始终等于输入块长，块与块之间保持波形连续。
//
// 参数：
//   pitch_semitones — 变调半音 (-12 ~ 12)
//
// 多声道支持：读/写/淡化状态按声道独立维护，交错缓冲下各声道互不干扰。
// 双格式支持：MAIDMIC_SAMPLE_S16 与 MAIDMIC_SAMPLE_F32（内部统一转 float 工作域）。
// 参数平滑：平滑"变调比率"（而非半音本身），每输出样本推进一次。
// 零堆分配：所有缓冲在 setup 一次性分配并复用，process 热路径零分配。

#include "maidmic/module.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

// ============================================================
// 辅助函数
// ============================================================

static inline float clampf(float v, float min, float max) {
    return v < min ? min : (v > max ? max : v);
}

// ============================================================
// 模块实例数据
// ============================================================

#define PITCH_MAX_CHANNELS 2u

// 工作缓冲区预分配的最大帧数（每声道）；超出时 process 中 realloc 并保留
#define PITCH_MAX_FRAMES 8192

// ---- 连续变调参数 ----
#define PITCH_HIST_CAP      8192u    // 历史环容量（样本/声道）；2^13，用位掩码取环
#define PITCH_HIST_MASK     (PITCH_HIST_CAP - 1u)  // 环容量掩码（2^13 → 低 13 位索引）
#define PITCH_WIN           768u     // 拼接交叉淡化窗口（样本）≈ 16ms @48kHz
#define PITCH_MARGIN        64u      // 读头与写头的最小安全间距（样本）
#define PITCH_MAX_LEAD      2560u    // 读头落后写头超过此值 → 前跳拼接（降调）
#define PITCH_BACK_JUMP     3072u    // 升调回跳量（=4×PITCH_WIN ≈ 64ms）
                                      // 回跳量必须显著大于 PITCH_WIN：
                                      // 拼接后读头以 ratio 速率推进，淡化结束的净延迟恢复
                                      // = PITCH_BACK_JUMP - (ratio-1)*PITCH_WIN。
                                      // 若回跳仅 1×WIN（ratio→2 时净恢复≈0），
                                      // 读头会反复逼近并越过写头，读到未写入区 → 高变调"卡卡的"。
                                      // 相比旧值 3×WIN：拼接周期 ≈ 回跳量/(ratio-1)，
                                      // 回跳量 +33% 使拼接频率下降约 30~40%（+7 半音 12.5→8.9 次/秒），
                                      // 拼接点总数减少，听感更干净。
#define PITCH_FWD_JUMP      1536u    // 降调前跳量（=2×PITCH_WIN）。降调拼接周期 ≈ 前跳量/(1-ratio)，
                                      // 前跳量 768→1536 使降调拼接频率减半（-4 半音 12.9→6.4 次/秒）。
#define PITCH_DELAY_INIT    1536u    // 启动预置延迟（样本）≈ 32ms @48kHz（前导静音渐变）
#define PITCH_RATIO_MIN     0.25f    // 变调比率钳位下限
#define PITCH_RATIO_MAX     4.0f     // 变调比率钳位上限
// ---- 相位对齐拼接参数 ----
// 拼接的本质：读头跳变到 read_pos±offset，并在 PITCH_WIN 内交叉淡化两条轨迹。
// 若 offset 不是源信号基频周期的整数倍，两条轨迹相位错开，淡化期间破坏性干涉
// → 周期性幅度凹陷（RMS 凹陷 15~30%，听感"卡卡"）。因此在名义跳变量附近搜索
// 使两条轨迹逐样本对齐的 offset（最小化比较窗口内平方差），对齐到基频周期整数倍，
// 再用抛物线插值细化到子样本精度（消除 1 样本量化相位误差，高频谐波对齐更准）。
//
// PITCH_ALIGN_WIN 需覆盖至少一个完整基频周期，否则低频男声（F0 80~150Hz →
// 周期 320~600 样本）的对齐判别力不足：窗口只看到部分周期，平方差最小值
// 区分度低，选出的 offset 相位误差大 → 拼接点仍残留幅度凹陷。768 样本
// ≈ 16ms @48kHz，覆盖低至 ~63Hz 的完整周期（200Hz 覆盖 3 周期）。
// 代价：每次拼接搜索 1201×768 ≈ 92 万 MAC，按最高拼接率 ~23 次/秒计
// ≈ 2100 万 MAC/秒，对现代 ARM 可忽略（远低于 DSP 预算）。
#define PITCH_ALIGN_WIN     768u     // 相位对齐比较窗口（样本）≈ 16ms @48kHz
#define PITCH_SEARCH_RANGE  600u     // 跳变偏移搜索范围（±样本）；覆盖低至 ~80Hz 基频
                                      // 的周期整数倍命中（80Hz→600 样本，半周期 300 < 600）
// 拼接死区：变调比率≈1.0（|ratio-1| ≤ 此值）时不发起拼接。
// 原因：比率≈1 时读头与写头几乎同速，延迟几乎不变；此时发起回跳只会
// 无意义地重复一段音频（伪影）。死区仅影响比率瞬态过渡期（滑块归零等），
// 稳态整数半音（最小 +1 = 1.06）远离死区，不受影响。
#define PITCH_RATIO_DEADZONE 0.02f   // 比率死区（±0.02 ≈ ±0.34 半音）

typedef struct {
    float semitones;       // 变调半音（目标值，float 保留小数便于平滑）
    uint32_t sample_rate;
    uint16_t channels;

    // 参数平滑器：平滑"变调比率"（target = 2^(semitones/12)），
    // 使变调比率连续过渡，消除 zipper 噪声，且避免每样本 powf
    maidmic_ramp_t ratio_ramp;

    // 缓存的工作缓冲区（避免每块音频 malloc/free，按需 realloc，用完保留）
    float* temp_buf;   // 输入副本（float 工作域，交错样本）
    int    temp_cap;
    float* out_buf;    // 独立输出缓冲区（float 工作域，交错样本）
    int    out_cap;

    // ---- 连续变调跨块状态（每声道独立）----
    float* hist[PITCH_MAX_CHANNELS];    // 历史环（容量 PITCH_HIST_CAP 样本/声道）
    uint64_t write_pos[PITCH_MAX_CHANNELS]; // 已写入样本总数（绝对位置）
    float read_pos[PITCH_MAX_CHANNELS];     // 连续读位置（绝对，跨块持续）
    float fade_pos_old[PITCH_MAX_CHANNELS]; // 淡化期间：旧轨迹读位置
    float fade_pos_new[PITCH_MAX_CHANNELS]; // 淡化期间：新轨迹读位置
    uint32_t fade_total[PITCH_MAX_CHANNELS];// 淡化总长度（PITCH_WIN）
    uint32_t fade_left[PITCH_MAX_CHANNELS]; // 淡化剩余样本数（0 = 未在淡化）
    uint32_t onset_left[PITCH_MAX_CHANNELS];// 激活淡入剩余样本数（消除前导静音→信号阶跃）
    bool was_active;                        // 上一块是否处于变调处理（直通↔变调状态重置）
} pitch_data_t;

// ============================================================
// 参数定义
// ============================================================

static const maidmic_param_t pitch_params[] = {
    {
        .key = "pitch_semitones",
        .type = MAIDMIC_PARAM_INT,
        .value.as_int = 0,
        .min = -12.0f,
        .max = 12.0f,
        .unit = "semitone",
    },
    { .key = NULL },
};

// ============================================================
// 状态管理
// ============================================================

// 复位全部跨块状态：清历史环（含前导预填充静音）、读头回 0、
// 写头回到预置延迟（输出延迟 = PITCH_DELAY_INIT 样本 ≈ 32ms），
// 并启动激活淡入（前导静音段内输出增益 0→1，避免静音→信号阶跃爆音）。
static void pitch_state_reset(pitch_data_t* p) {
    for (uint32_t c = 0; c < PITCH_MAX_CHANNELS; c++) {
        if (p->hist[c]) {
            memset(p->hist[c], 0, PITCH_HIST_CAP * sizeof(float));
        }
        p->write_pos[c] = PITCH_DELAY_INIT;
        p->read_pos[c] = 0.0f;
        p->fade_pos_old[c] = 0.0f;
        p->fade_pos_new[c] = 0.0f;
        p->fade_total[c] = 0;
        p->fade_left[c] = 0;
        p->onset_left[c] = PITCH_DELAY_INIT;
    }
    p->was_active = false;
    maidmic_ramp_reset(&p->ratio_ramp);
}

// ============================================================
// vtable 实现
// ============================================================

static void* pitch_create(void) {
    pitch_data_t* p = (pitch_data_t*)calloc(1, sizeof(pitch_data_t));
    if (!p) return NULL;
    p->semitones = 0;
    maidmic_ramp_init(&p->ratio_ramp, 1.0f);
    return p;
}

static void pitch_destroy(void* userdata) {
    pitch_data_t* p = (pitch_data_t*)userdata;
    if (!p) return;
    free(p->temp_buf);
    free(p->out_buf);
    for (uint32_t c = 0; c < PITCH_MAX_CHANNELS; c++) {
        free(p->hist[c]);
    }
    free(p);
}

static bool pitch_setup(void* userdata, uint32_t sample_rate, uint16_t channels) {
    pitch_data_t* p = (pitch_data_t*)userdata;
    p->sample_rate = sample_rate;

    // 预分配最大需求，process 热路径零分配；更大帧数在 process 中 realloc 并保留
    int temp_need = PITCH_MAX_FRAMES * (int)channels;          // 输入副本
    if (p->temp_cap < temp_need) {
        float* new_temp = (float*)realloc(p->temp_buf, (size_t)temp_need * sizeof(float));
        if (!new_temp) return false;
        p->temp_buf = new_temp;
        p->temp_cap = temp_need;
    }
    int out_need = PITCH_MAX_FRAMES * (int)channels;           // 输出缓冲
    if (p->out_cap < out_need) {
        float* new_out = (float*)realloc(p->out_buf, (size_t)out_need * sizeof(float));
        if (!new_out) return false;
        p->out_buf = new_out;
        p->out_cap = out_need;
    }

    // 历史环：每声道 PITCH_HIST_CAP，首次分配时清零（calloc 含前导静音）
    for (uint32_t c = 0; c < PITCH_MAX_CHANNELS; c++) {
        if (!p->hist[c]) {
            p->hist[c] = (float*)calloc(PITCH_HIST_CAP, sizeof(float));
            if (!p->hist[c]) return false;
        }
    }

    p->channels = channels;
    // 采样率/声道数变化：复位跨块状态
    pitch_state_reset(p);
    return true;
}

static uint32_t pitch_get_param_count(void* userdata) {
    (void)userdata;
    return 1;
}

static const maidmic_param_t* pitch_get_param_info(void* userdata, uint32_t index) {
    (void)userdata;
    if (index < 1) return &pitch_params[index];
    return NULL;
}

static bool pitch_set_param(void* userdata, const char* key, maidmic_param_t value) {
    pitch_data_t* p = (pitch_data_t*)userdata;
    if (strcmp(key, "pitch_semitones") == 0) {
        float st;
        if (value.type == MAIDMIC_PARAM_INT) {
            st = (float)value.value.as_int;
        } else if (value.type == MAIDMIC_PARAM_FLOAT) {
            st = value.value.as_float;
        } else {
            return false;
        }
        p->semitones = st;
        // 只更新变调比率目标值，process 中逐样本平滑比率（保证连续过渡）
        maidmic_ramp_set_target(&p->ratio_ramp, powf(2.0f, p->semitones / 12.0f));
        return true;
    }
    return false;
}

static maidmic_param_t pitch_get_param(void* userdata, const char* key) {
    pitch_data_t* p = (pitch_data_t*)userdata;
    maidmic_param_t param = {0};
    if (strcmp(key, "pitch_semitones") == 0) {
        param.key = "pitch_semitones";
        param.type = MAIDMIC_PARAM_INT;
        param.value.as_int = (int)p->semitones;
        param.min = -12.0f;
        param.max = 12.0f;
        param.unit = "semitone";
    }
    return param;
}

// ============================================================
// 核心：连续变调
// ============================================================

// 历史环线性插值读取（pos 为绝对位置，自动折绕环容量）
static inline float pitch_interp(const float* hist, float pos) {
    uint64_t i0 = (uint64_t)pos;
    float frac = pos - (float)i0;
    float s0 = hist[i0 & PITCH_HIST_MASK];
    float s1 = hist[(i0 + 1) & PITCH_HIST_MASK];
    return s0 + (s1 - s0) * frac;
}

// 指定新轨迹起点的对齐平方差（粗搜与抛物线细化共用）
static float pitch_align_score(const float* hist, uint64_t base_old, float new_start) {
    float score = 0.0f;
    const uint64_t base_new = (uint64_t)new_start;
    for (uint32_t k = 0; k < PITCH_ALIGN_WIN; k++) {
        float a = hist[(base_old + k) & PITCH_HIST_MASK];
        float b = hist[(base_new + k) & PITCH_HIST_MASK];
        float d = a - b;
        score += d * d;
    }
    return score;
}

// 相位对齐拼接：在名义跳变量附近搜索使新旧轨迹波形逐样本一致的偏移。
//   dir  = -1 回跳（new = read_pos - off） / +1 前跳（new = read_pos + off）
//   hist/wpos/read_pos 保证比较窗口（ALIGN_WIN）两端都落在已写入历史内。
// 返回子样本最优偏移（float）：先整数粗搜，再对最佳偏移 ±1 做抛物线插值细化，
// 消除 1 样本量化相位误差（高频谐波对齐更准，拼接点更干净）。
// 平方差比较窗口选 ALIGN_WIN ≈ 多个基频周期：信号周期 P 时，
// offset = k*P 得 0 差（相位完全对齐），offset = P/2 附近差最大，
// 故最小化平方差即把跳变对齐到基频周期整数倍，淡化时两轨迹不再抵消。
static float pitch_find_align_offset(const float* hist, float read_pos, float wpos,
                                     int dir, uint32_t nominal) {
    uint32_t lo = (nominal > PITCH_SEARCH_RANGE) ? (nominal - PITCH_SEARCH_RANGE) : 1u;
    uint32_t hi = nominal + PITCH_SEARCH_RANGE;
    uint32_t best = nominal;
    float best_score = 1e30f;

    // 旧轨迹比较窗口越界（理论上不会发生：拼接触发时 delay > 1200 > ALIGN_WIN）
    if (read_pos + (float)PITCH_ALIGN_WIN > wpos) return (float)nominal;

    const uint64_t base_old = (uint64_t)read_pos;
    for (uint32_t off = lo; off <= hi; off++) {
        float new_start = (dir > 0) ? (read_pos + (float)off) : (read_pos - (float)off);
        if (new_start < 0.0f) continue;                       // 新轨迹越界（环外）
        if (new_start + (float)PITCH_ALIGN_WIN > wpos) continue; // 新轨迹窗口越过写头

        float score = pitch_align_score(hist, base_old, new_start);
        if (score < best_score) {
            best_score = score;
            best = off;
        }
    }

    // 抛物线细化：对 best-1/best/best+1 的平方差插值，得子样本最优偏移
    if (best > lo && best < hi) {
        float sm = pitch_align_score(hist, base_old,
                     (dir > 0) ? (read_pos + (float)(best - 1)) : (read_pos - (float)(best - 1)));
        float sp = pitch_align_score(hist, base_old,
                     (dir > 0) ? (read_pos + (float)(best + 1)) : (read_pos - (float)(best + 1)));
        float denom = sm - 2.0f * best_score + sp;
        float delta = (denom != 0.0f) ? 0.5f * (sm - sp) / denom : 0.0f;
        if (delta > 0.5f) delta = 0.5f;
        else if (delta < -0.5f) delta = -0.5f;
        return (float)best + delta;
    }
    return (float)best;
}

// 单声道连续变调处理（原地安全：in 读自 temp，out 写独立缓冲）
static void pitch_process_channel(pitch_data_t* p, uint16_t c,
                                  const float* in, uint32_t fc, uint16_t channels,
                                  float* out) {
    float* hist = p->hist[c];
    uint64_t wpos = p->write_pos[c];

    // 1. 本块输入写入历史环（绝对位置，折绕取环下标）
    for (uint32_t i = 0; i < fc; i++) {
        hist[(wpos + i) & PITCH_HIST_MASK] = in[i * channels + c];
    }
    wpos += fc;
    p->write_pos[c] = wpos;

    // 2. 拼接决策（淡化期间不发起新拼接，避免复合淡化）
    if (p->fade_left[c] == 0) {
        float delay = (float)wpos - p->read_pos[c];
        float ratio = p->ratio_ramp.current;
        if (ratio < PITCH_RATIO_MIN) ratio = PITCH_RATIO_MIN;
        if (ratio > PITCH_RATIO_MAX) ratio = PITCH_RATIO_MAX;

        // 拼接死区：比率≈1 时不拼接（读头与写头同速、延迟不变，
        // 拼接只会无意义重复音频）。避免瞬态过渡期的拼接伪影。
        if (fabsf(ratio - 1.0f) > PITCH_RATIO_DEADZONE) {
            // 方向门控：升调（ratio>=1）只回跳、降调（ratio<1）只前跳。
            // 回跳分支同样必须加 ratio>=1.0 门控：降调前跳后延迟骤降
            // （MAX_LEAD - FWD_JUMP，可能低于回跳阈值），若无门控会立刻触发
            // 反向回跳 → 读头大幅回退 → 波形大断裂（仿真边界断裂比 14x）。
            // 升调回跳把延迟推到 MAX_LEAD 之上后也依赖 ratio 门控避免反向前跳，
            // 两个方向互相抵消、每 1~2 块就拼接一次 → 频繁相位断裂即"卡卡"。
            if (ratio >= 1.0f && delay < (float)PITCH_MARGIN + ratio * (float)PITCH_WIN + (float)PITCH_WIN * 0.5f) {
                // 升调：读头逼近写头 → 回跳拼接（重读更旧历史）。
                // 提前触发留出淡化余量：旧轨迹在淡化期推进 ratio*WIN，
                // 同期写头仅推进 WIN，保证旧轨迹始终落在已写入区间内。
                // 跳变偏移经相位对齐（对齐基频周期整数倍 + 子样本细化），
                // 避免淡化期间两条轨迹相位错开互相抵消（周期性幅度凹陷）。
                float off = pitch_find_align_offset(hist, p->read_pos[c], (float)wpos, -1,
                                                    PITCH_BACK_JUMP);
                float new_pos = p->read_pos[c] - off;
                if (new_pos < 0.0f) new_pos = 0.0f;
                p->fade_pos_old[c] = p->read_pos[c];
                p->fade_pos_new[c] = new_pos;
                p->fade_total[c] = PITCH_WIN;
                p->fade_left[c] = PITCH_WIN;
            } else if (ratio < 1.0f && delay > (float)PITCH_MAX_LEAD) {
                // 降调：读头落后过多 → 前跳拼接（跳读更新历史），偏移同样相位对齐
                float off = pitch_find_align_offset(hist, p->read_pos[c], (float)wpos, +1,
                                                    PITCH_FWD_JUMP);
                float new_pos = p->read_pos[c] + off;
                float max_pos = (float)wpos - (float)PITCH_MARGIN;
                if (new_pos > max_pos) new_pos = max_pos;
                if (new_pos > p->read_pos[c]) {
                    p->fade_pos_old[c] = p->read_pos[c];
                    p->fade_pos_new[c] = new_pos;
                    p->fade_total[c] = PITCH_WIN;
                    p->fade_left[c] = PITCH_WIN;
                }
            }
        }  // end 死区
    }

    // 3. 读 fc 个输出样本（逐样本平滑比率；淡化期间混叠旧/新轨迹；
    //    激活初期施加淡入包络，平滑"前导静音 → 信号"过渡）
    for (uint32_t i = 0; i < fc; i++) {
        float ratio = maidmic_ramp_next(&p->ratio_ramp);
        if (ratio < PITCH_RATIO_MIN) ratio = PITCH_RATIO_MIN;
        if (ratio > PITCH_RATIO_MAX) ratio = PITCH_RATIO_MAX;

        float s;
        if (p->fade_left[c] > 0) {
            float s_old = pitch_interp(hist, p->fade_pos_old[c]);
            p->fade_pos_old[c] += ratio;
            float s_new = pitch_interp(hist, p->fade_pos_new[c]);
            p->fade_pos_new[c] += ratio;
            float t = 1.0f - (float)p->fade_left[c] / (float)p->fade_total[c];
            // 等功率交叉淡化（cos²/sin²）：相比线性窗，淡化起止处权重斜率为 0，
            // 对齐残差下不产生线性窗端点斜率不连续引入的高频"咔哒"成分；
            // 相位完全对齐时 cos²+sin²=1 → 输出恒增益 1.0（与线性窗一致）。
            float cw = cosf(t * (float)M_PI * 0.5f);
            float sw = sinf(t * (float)M_PI * 0.5f);
            s = s_old * cw * cw + s_new * sw * sw;
            p->fade_left[c]--;
            if (p->fade_left[c] == 0) {
                p->read_pos[c] = p->fade_pos_new[c];
            }
        } else {
            s = pitch_interp(hist, p->read_pos[c]);
            p->read_pos[c] += ratio;
        }

        // 激活淡入：仅覆盖前导静音段（激活后 PITCH_DELAY_INIT 个输出样本），
        // 增益由 0 线性升至 1；此后不再介入（onset_left 递减到 0 后无开销）
        if (p->onset_left[c] > 0) {
            float g = 1.0f - (float)p->onset_left[c] / (float)PITCH_DELAY_INIT;
            s *= g;
            p->onset_left[c]--;
        }

        out[i * channels + c] = s;
    }
}

static bool pitch_process(void* userdata, const maidmic_buffer_t* input, maidmic_buffer_t* output) {
    pitch_data_t* p = (pitch_data_t*)userdata;

    uint32_t frame_count = input->meta.frame_count;
    uint16_t channels = input->meta.channels;
    if (frame_count == 0 || channels == 0 || channels > PITCH_MAX_CHANNELS) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }
    uint32_t sample_count = frame_count * (uint32_t)channels;

    // 变调是否激活：比率平滑到位且为 1.0 且无淡化在进行 → 直通
    bool active =
        !(p->ratio_ramp.current == p->ratio_ramp.target && p->ratio_ramp.current == 1.0f) ||
        p->fade_left[0] > 0 || p->fade_left[1] > 0;

    if (!active) {
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        p->was_active = false;
        return true;
    }

    // 从直通过渡到变调：复位跨块状态，避免使用陈旧历史/残留淡化
    if (!p->was_active) {
        pitch_state_reset(p);
    }
    p->was_active = true;

    // 输入转 float 工作域（支持 S16 与 F32）
    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        if ((int)sample_count > p->temp_cap) {
            float* new_temp = (float*)realloc(p->temp_buf, (size_t)sample_count * sizeof(float));
            if (!new_temp) return false;
            p->temp_buf = new_temp;
            p->temp_cap = (int)sample_count;
        }
        memcpy(p->temp_buf, input->data, (size_t)sample_count * sizeof(float));
    } else if (input->meta.format == MAIDMIC_SAMPLE_S16) {
        if ((int)sample_count > p->temp_cap) {
            float* new_temp = (float*)realloc(p->temp_buf, (size_t)sample_count * sizeof(float));
            if (!new_temp) return false;
            p->temp_buf = new_temp;
            p->temp_cap = (int)sample_count;
        }
        const int16_t* src = (const int16_t*)input->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            p->temp_buf[i] = (float)src[i];
        }
    } else {
        // 其他格式不支持：直通
        if (output->data != input->data) {
            memcpy(output->data, input->data, input->data_bytes);
        }
        output->meta = input->meta;
        return true;
    }

    // 确保输出缓冲
    if ((int)sample_count > p->out_cap) {
        float* new_out = (float*)realloc(p->out_buf, (size_t)sample_count * sizeof(float));
        if (!new_out) return false;
        p->out_buf = new_out;
        p->out_cap = (int)sample_count;
    }

    // 每声道连续变调
    for (uint16_t c = 0; c < channels; c++) {
        pitch_process_channel(p, c, p->temp_buf, frame_count, channels, p->out_buf);
    }

    // 写回输出（S16 钳位转换；F32 直拷）。始终经 out_buf，原地处理安全。
    if (input->meta.format == MAIDMIC_SAMPLE_F32) {
        memcpy(output->data, p->out_buf, (size_t)sample_count * sizeof(float));
    } else {  // S16
        int16_t* dst = (int16_t*)output->data;
        for (uint32_t i = 0; i < sample_count; i++) {
            float v = clampf(p->out_buf[i], -32768.0f, 32767.0f);
            dst[i] = (int16_t)v;
        }
    }

    output->meta = input->meta;
    return true;
}

static void pitch_reset(void* userdata) {
    pitch_data_t* p = (pitch_data_t*)userdata;
    pitch_state_reset(p);
}

// ============================================================
// 模块描述
// ============================================================

static const maidmic_module_vtable_t pitch_vtable = {
    .create = pitch_create,
    .destroy = pitch_destroy,
    .setup = pitch_setup,
    .get_param_count = pitch_get_param_count,
    .get_param_info = pitch_get_param_info,
    .set_param = pitch_set_param,
    .get_param = pitch_get_param,
    .process = pitch_process,
    .reset = pitch_reset,
};

const maidmic_module_t maidmic_module_pitch = {
    .id = MAIDMIC_MODULE_ID_PITCH,
    .name = "Pitch Shift",
    .description = "Continuous pitch shifter with history ring and splice crossfade (变调)",
    .author = "MaidMic Team",
    .version = 2,
    .capabilities = MAIDMIC_CAP_PROCESS_AUDIO | MAIDMIC_CAP_HAS_PARAMS | MAIDMIC_CAP_BYPASS | MAIDMIC_CAP_REALTIME,
    .vtable = &pitch_vtable,
};
