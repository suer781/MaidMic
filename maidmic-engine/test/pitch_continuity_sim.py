#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Pitch 模块跨块连续性仿真验证（镜像 src/dsp/pitch.c 的算法逻辑，float32）。

与 src/dsp/pitch.c 逐行对齐的常量：
  - PITCH_BACK_JUMP = 2304（3×WIN，升调回跳量；与 C 代码一致）
  - PITCH_HIST_CAP = 8192 / PITCH_WIN = 768 / PITCH_MARGIN = 64
  - PITCH_MAX_LEAD = 2048 / PITCH_DELAY_INIT = 1536

测试内容：
  1. 块边界连续性：|out[k块首] - out[k-1块尾]| 与块内最大差分对比（断裂检测）。
  2. 拼接点人工痕迹：跟踪拼接（crossfade）位置，测量拼接期间的包络凹陷/跳变。
  3. 与录音链路同口径：块长 1024（PROCESS_BLOCK_SAMPLES）/ 48kHz / 200Hz 谐波信号
     （近似人声基频）/ 升调 +4 / +7 / 降调 -4。
"""
import math
import numpy as np

# ---- 镜像 pitch.c 常量 ----
PITCH_MAX_CHANNELS = 2
PITCH_HIST_CAP = 8192
PITCH_HIST_MASK = PITCH_HIST_CAP - 1
PITCH_WIN = 768
PITCH_BACK_JUMP = 3072        # 与 C 代码一致（4×WIN，升调回跳量）
PITCH_FWD_JUMP = 1536         # 与 C 代码一致（2×WIN，降调前跳量）
PITCH_MARGIN = 64
PITCH_MAX_LEAD = 2560
PITCH_DELAY_INIT = 1536
PITCH_RATIO_MIN = 0.25
PITCH_RATIO_MAX = 4.0
PITCH_ALIGN_WIN = 768         # 与 C 代码一致（对齐窗口 512→768）
PITCH_SEARCH_RANGE = 600
PITCH_RATIO_DEADZONE = 0.02  # 与 C 代码一致（比率≈1 时跳过拼接决策）
RAMP_STEP = np.float32(1.0 / 32.0)

SR = 48000
BLOCK = 1024          # 录音链路块长（PROCESS_BLOCK_SAMPLES）
SECONDS = 4
F0 = 200.0            # 基频（人声范围，main 中按用例覆盖 200/100Hz）
NP = np.float32


class RatioRamp:
    """镜像 maidmic_ramp_t（float32，步长 1/32，到位钳位）。"""

    def __init__(self, value):
        self.current = NP(value)
        self.target = NP(value)
        self.step = RAMP_STEP

    def set_target(self, t):
        self.target = NP(t)

    def reset(self):
        self.current = self.target

    def next(self):
        if self.current < self.target:
            self.current += self.step
            if self.current > self.target:
                self.current = self.target
        elif self.current > self.target:
            self.current -= self.step
            if self.current < self.target:
                self.current = self.target
        return self.current


class PitchState:
    """镜像 pitch_data_t 单声道核心（float32 运算，与 C 一致）。"""

    def __init__(self, semitones):
        self.semitones = semitones
        self.ratio_ramp = RatioRamp(1.0)
        self.ratio_ramp.set_target(2.0 ** (semitones / 12.0))
        self.hist = np.zeros(PITCH_HIST_CAP, dtype=np.float32)
        self.write_pos = np.uint64(PITCH_DELAY_INIT)
        self.read_pos = NP(0.0)
        self.fade_pos_old = NP(0.0)
        self.fade_pos_new = NP(0.0)
        self.fade_total = 0
        self.fade_left = 0
        self.onset_left = PITCH_DELAY_INIT
        self.was_active = False
        self.new_splices = []   # 本块内新发起的拼接（绝对写位置）

    def state_reset(self):
        self.hist[:] = 0.0
        self.write_pos = np.uint64(PITCH_DELAY_INIT)
        self.read_pos = NP(0.0)
        self.fade_pos_old = NP(0.0)
        self.fade_pos_new = NP(0.0)
        self.fade_total = 0
        self.fade_left = 0
        self.onset_left = PITCH_DELAY_INIT
        self.was_active = False
        self.ratio_ramp.reset()

    def interp(self, pos):
        i0 = int(pos)
        frac = NP(pos - float(i0))
        s0 = self.hist[i0 & PITCH_HIST_MASK]
        s1 = self.hist[(i0 + 1) & PITCH_HIST_MASK]
        return NP(s0 + (s1 - s0) * frac)

    def find_align_offset(self, read_pos, wpos, direction, nominal):
        """镜像 pitch_find_align_offset：粗搜整数偏移 + 抛物线细化到子样本。"""
        lo = max(nominal - PITCH_SEARCH_RANGE, 1)
        hi = nominal + PITCH_SEARCH_RANGE
        best = nominal
        best_score = 1e30
        if read_pos + PITCH_ALIGN_WIN > wpos:
            return float(nominal)
        base_old = int(read_pos)
        for off in range(lo, hi + 1):
            new_start = (read_pos + off) if direction > 0 else (read_pos - off)
            if new_start < 0:
                continue
            if new_start + PITCH_ALIGN_WIN > wpos:
                continue
            score = 0.0
            base_new = int(new_start)
            for k in range(PITCH_ALIGN_WIN):
                a = self.hist[(base_old + k) & PITCH_HIST_MASK]
                b = self.hist[(base_new + k) & PITCH_HIST_MASK]
                d = float(a) - float(b)
                score += d * d
            if score < best_score:
                best_score = score
                best = off

        def _score(off2):
            ns = (read_pos + off2) if direction > 0 else (read_pos - off2)
            if ns < 0 or ns + PITCH_ALIGN_WIN > wpos:
                return 1e30
            bn = int(ns)
            s = 0.0
            for k in range(PITCH_ALIGN_WIN):
                a = self.hist[(base_old + k) & PITCH_HIST_MASK]
                b = self.hist[(bn + k) & PITCH_HIST_MASK]
                d = float(a) - float(b)
                s += d * d
            return s

        # 抛物线细化：best-1/best/best+1 插值得子样本最优偏移
        if lo < best < hi:
            sm = _score(best - 1)
            sp = _score(best + 1)
            denom = sm - 2.0 * best_score + sp
            delta = (0.5 * (sm - sp) / denom) if denom != 0 else 0.0
            delta = max(-0.5, min(0.5, delta))
            return float(best) + delta
        return float(best)

    def process_block(self, samples_in):
        fc = len(samples_in)
        hist = self.hist
        wpos = self.write_pos
        wmask = np.uint64(PITCH_HIST_MASK)

        # 1. 本块输入写入历史环（绝对位置折绕）
        for i in range(fc):
            hist[int(wpos + np.uint64(i)) & PITCH_HIST_MASK] = NP(samples_in[i])
        wpos += np.uint64(fc)
        self.write_pos = wpos

        # 2. 拼接决策（方向门控 + 相位对齐 + 死区；与 C 代码一致）
        #    升调（ratio>=1）只回跳、降调（ratio<1）只前跳，避免两方向互相抵消振荡
        self.new_splices = []
        if self.fade_left == 0:
            delay = float(wpos) - float(self.read_pos)
            ratio = max(PITCH_RATIO_MIN, min(PITCH_RATIO_MAX, float(self.ratio_ramp.current)))
            if abs(ratio - 1.0) > PITCH_RATIO_DEADZONE:  # 死区：比率≈1 不拼接
                # 方向门控：升调只回跳、降调只前跳（回跳分支也必须有 ratio>=1 门控，
                # 否则降调前跳后 delay 骤降到回跳阈值以下会立刻反向回跳 → 大断裂）
                if ratio >= 1.0 and delay < PITCH_MARGIN + ratio * PITCH_WIN + PITCH_WIN * 0.5:
                    off = self.find_align_offset(float(self.read_pos), float(wpos), -1, PITCH_BACK_JUMP)
                    new_pos = float(self.read_pos) - float(off)
                    if new_pos < 0.0:
                        new_pos = 0.0
                    self.fade_pos_old = NP(float(self.read_pos))
                    self.fade_pos_new = NP(new_pos)
                    self.fade_total = PITCH_WIN
                    self.fade_left = PITCH_WIN
                    self.new_splices.append(float(wpos))
                elif ratio < 1.0 and delay > PITCH_MAX_LEAD:
                    off = self.find_align_offset(float(self.read_pos), float(wpos), +1, PITCH_FWD_JUMP)
                    new_pos = float(self.read_pos) + float(off)
                    max_pos = float(wpos) - PITCH_MARGIN
                    if new_pos > max_pos:
                        new_pos = max_pos
                    if new_pos > float(self.read_pos):
                        self.fade_pos_old = NP(float(self.read_pos))
                        self.fade_pos_new = NP(new_pos)
                        self.fade_total = PITCH_WIN
                        self.fade_left = PITCH_WIN
                        self.new_splices.append(float(wpos))

        # 3. 读 fc 个输出样本
        out = np.zeros(fc, dtype=np.float32)
        for i in range(fc):
            ratio = NP(max(PITCH_RATIO_MIN, min(PITCH_RATIO_MAX, float(self.ratio_ramp.next()))))
            if self.fade_left > 0:
                s_old = self.interp(self.fade_pos_old)
                self.fade_pos_old += ratio
                s_new = self.interp(self.fade_pos_new)
                self.fade_pos_new += ratio
                t = NP(1.0 - float(self.fade_left) / float(self.fade_total))
                # 等功率交叉淡化（cos²/sin²，与 C 代码一致）：
                # 起止处权重斜率为 0，对齐残差下不引入线性窗端点斜率不连续的高频成分；
                # 相位完全对齐时 cos²+sin²=1 → 恒增益 1.0
                cw = np.cos(t * np.pi * 0.5)
                sw = np.sin(t * np.pi * 0.5)
                s = NP(s_old * cw * cw + s_new * sw * sw)
                self.fade_left -= 1
                if self.fade_left == 0:
                    self.read_pos = self.fade_pos_new
            else:
                s = self.interp(self.read_pos)
                self.read_pos += ratio

            if self.onset_left > 0:
                g = NP(1.0 - float(self.onset_left) / float(PITCH_DELAY_INIT))
                s = NP(s * g)
                self.onset_left -= 1
            out[i] = s
        return out

    def process(self, samples_in):
        r = self.ratio_ramp
        active = not (r.current == r.target and r.current == NP(1.0)) or self.fade_left > 0
        if not active:
            self.was_active = False
            return np.asarray(samples_in, dtype=np.float32).copy()
        if not self.was_active:
            self.state_reset()
        self.was_active = True
        return self.process_block(samples_in)


def voice_like_signal(n, start, f0=F0):
    """谐波（基波+3 次谐波，近似人声），float32。
    start 为绝对样本起点，保证跨块相位连续（与真实录音一致）。
    f0 为基频（Hz），200 近似女声 / 100 近似男声低频。"""
    t = (np.arange(n, dtype=np.float64) + start) / SR
    x = (0.6 * np.sin(2 * np.pi * f0 * t)
         + 0.25 * np.sin(2 * np.pi * f0 * 2 * t)
         + 0.12 * np.sin(2 * np.pi * f0 * 3 * t))
    return x.astype(np.float32)


def realistic_voice(n, start, f0_base=150.0):
    """真实人声近似：基频连续变化（说话韵律 ±10% + 颤音 ±3%@5.3Hz）
    + 1~5 次谐波 + 少量清音噪声（-30dB，固定种子可复现）。
    基频跨块连续变化 → 相邻周期波形不同，考验拼接对齐在非周期波形下的表现。
    start 为绝对样本起点（相位由 cumsum 保证连续）。"""
    t = (np.arange(n, dtype=np.float64) + start) / SR
    f0 = f0_base * (1.0 + 0.10 * np.sin(2 * np.pi * 1.7 * t)
                    + 0.03 * np.sin(2 * np.pi * 5.3 * t))
    phase = 2 * np.pi * np.cumsum(f0) / SR
    x = (0.60 * np.sin(phase)
         + 0.28 * np.sin(2 * phase)
         + 0.14 * np.sin(3 * phase)
         + 0.07 * np.sin(4 * phase)
         + 0.04 * np.sin(5 * phase))
    # 清音/呼吸噪声（固定种子，可复现）；打断完全周期性，更接近真实浊音
    noise = 0.03 * np.random.RandomState(int(start) // SR * 97 + 11).randn(n)
    return (x + noise).astype(np.float32)


def analyze(semitones, label, f0=F0, signal='harmonic'):
    st = PitchState(semitones)
    total = SR * SECONDS
    done = 0
    prev_tail = None
    max_interior = 0.0
    max_boundary = 0.0
    nan_found = False
    # 拼接期间的最大"断点"（相邻样本差分）
    max_splice_jump = 0.0
    splices = 0
    blocks = 0

    # 输出序列（用于包络分析）
    out_all = []
    splice_starts = []   # 新拼接发起时对应的绝对输出样本下标

    while done < total:
        n = min(BLOCK, total - done)
        if signal == 'realistic':
            block_in = realistic_voice(n, done, f0)
        else:
            block_in = voice_like_signal(n, done, f0)
        block_out = st.process(block_in)
        blocks += 1

        out_all.append(block_out)
        for v in block_out:
            if not np.isfinite(v):
                nan_found = True

        for i in range(1, n):
            d = abs(float(block_out[i]) - float(block_out[i - 1]))
            if d > max_interior:
                max_interior = d
        if prev_tail is not None and n > 0:
            d = abs(float(block_out[0]) - float(prev_tail))
            if d > max_boundary:
                max_boundary = d
        prev_tail = float(block_out[-1])

        # 拼接计数：本块内新发起的 crossfade（new_splices 在 process 时记录）
        if len(st.new_splices) > 0:
            splices += len(st.new_splices)
            splice_starts.append(done)
        done += n

    out_all = np.concatenate(out_all)
    print(f"[{label}] 块数={blocks} 拼接(块)={splices} "
          f"内部最大差分={max_interior:.5f} 边界最大差分={max_boundary:.5f} "
          f"NaN={nan_found}")
    print(f"          边界断裂比(边界/内部)={max_boundary / max_interior:.2f}x")

    # ---- 定位最大内部跳变的位置与拼接发起位置的关系 ----
    if len(out_all) > 2:
        diffs = np.abs(np.diff(out_all.astype(np.float64)))
        top_idx = np.argsort(diffs)[-8:]  # 最大的 8 个跳变位置
        top_vals = diffs[top_idx]
        print(f"          最大跳变: " +
              ", ".join(f"idx={i}({i/SR*1000:.0f}ms, d={v:.3f})" for i, v in zip(top_idx, top_vals)))
        if splice_starts:
            near = sum(1 for i in top_idx
                       if any(abs(i - s) < PITCH_WIN for s in splice_starts))
            print(f"          最大跳变中靠近拼接点(±{PITCH_WIN}样本)的: {near}/8")
        # 拼接点局部差分 vs 远离拼接区：拼接点波形是否更"断裂"（听感咔哒的直接度量）
        if splices > 0 and len(out_all) > 2:
            splice_diff = []
            away_diff = []
            for s in splice_starts:
                s = int(s)
                lo, hi = max(0, s - 100), min(len(diffs) - 1, s + 100)
                a0, a1 = max(0, s - 400), max(0, s - 200)
                if hi > lo:
                    splice_diff.append(diffs[lo:hi].max())
                if a1 > a0:
                    away_diff.append(diffs[a0:a1].max())
            if splice_diff and away_diff:
                sd = np.array(splice_diff).mean()
                ad = np.array(away_diff).mean()
                print(f"          拼接点局部最大差分均值={sd:.5f} 远离区={ad:.5f} "
                      f"拼接断裂比={sd / max(ad, 1e-9):.2f}x")

    # 拼接导致的包络凹陷检测：滑动窗口 RMS，找骤降
    win = 256
    rms = np.sqrt(np.convolve(out_all.astype(np.float64) ** 2,
                              np.ones(win) / win, mode='valid'))
    # 全序列平均 RMS 对比最小 1% 分位
    q1 = np.percentile(rms, 1)
    mean_rms = rms.mean()
    print(f"          RMS 均值={mean_rms:.4f} 1%分位={q1:.4f} "
          f"凹陷度={1.0 - q1 / max(mean_rms, 1e-9):.1%}")

    # 拼接点局部 RMS vs 远离拼接点的 RMS（定位凹陷是否由拼接引起）
    if splices > 0:
        splice_local = []
        away = []
        for s in splice_starts:
            lo = max(0, s - win)
            hi = min(len(rms), s + win)
            splice_local.append(rms[lo:hi])
            # 远离拼接的对照区：拼接前 win*3 处
            a0 = max(0, s - 4 * win)
            a1 = max(0, s - 2 * win)
            if a1 > a0:
                away.append(rms[a0:a1])
        sl_mean = np.concatenate(splice_local).mean() if splice_local else 0
        aw_mean = np.concatenate(away).mean() if away else 0
        print(f"          拼接点局部RMS={sl_mean:.4f} 对照区RMS={aw_mean:.4f} "
              f"拼接点凹陷={1.0 - sl_mean / max(aw_mean, 1e-9):.1%}")
    else:
        print(f"          未检测到拼接")

    tol = 8.0 * max_interior + 0.02
    ok = (max_boundary <= tol) and (not nan_found)
    return ok


def main():
    print(f"Pitch 连续性仿真（镜像当前 C 代码，BACK_JUMP={PITCH_BACK_JUMP}, "
          f"ALIGN_WIN={PITCH_ALIGN_WIN}, 块长={BLOCK}, {SECONDS}s）")
    ok1 = analyze(4.0, "升调 +4 半音 (200Hz 女声)")
    ok2 = analyze(7.0, "升调 +7 半音 (200Hz 女声)")
    ok3 = analyze(-4.0, "降调 -4 半音 (200Hz 女声)")
    # 男声低频用例：对齐窗口必须覆盖完整基频周期（100Hz→480 样本 < 512）
    ok4 = analyze(4.0, "升调 +4 半音 (100Hz 男声)", f0=100.0)
    ok5 = analyze(7.0, "升调 +7 半音 (100Hz 男声)", f0=100.0)
    ok6 = analyze(-4.0, "降调 -4 半音 (100Hz 男声)", f0=100.0)
    # 真实人声用例：基频连续变化 + 颤音 + 多谐波 + 噪声（拼接对齐的主要考验）
    ok7 = analyze(4.0, "升调 +4 半音 (150Hz 真实人声)", f0=150.0, signal='realistic')
    ok8 = analyze(7.0, "升调 +7 半音 (150Hz 真实人声)", f0=150.0, signal='realistic')
    ok9 = analyze(-4.0, "降调 -4 半音 (150Hz 真实人声)", f0=150.0, signal='realistic')
    ok = ok1 and ok2 and ok3 and ok4 and ok5 and ok6 and ok7 and ok8 and ok9
    print("结果:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    import sys
    sys.exit(main())
