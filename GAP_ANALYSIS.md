# MaidMic 功能缺口分析
## Gap Analysis vs Industry Voice Changer Standards

生成日期: 2026-04-29

---

## 现状 vs 行业标准

### MaidMic 已有功能 ✅
| 功能 | 状态 | 备注 |
|------|------|------|
| 引擎选择器 | ✅ | 直通 / Echio 均衡 / 频响曲线 |
| 音量增益 (Gain) | ✅ | ±10dB |
| 均衡器 (EQ) | ✅ | Echio: 低音+高音 shelving / Curves: 10段 peaking |
| 混响 (Reverb) | ✅ | 简单延迟线 |
| 变调 (Pitch Shift) | ✅ | ±12 半音，线性插值 |
| 预设 (Presets) | ✅ | 6个 Echio 预设 + 8个曲线预设 |
| 虚拟麦克风桥接 | ✅ | Shizuku / Root / 无障碍 |
| 前级预渲染 (Pre-render) | ✅ | HxCore 风格双曲线层 |

### 行业变声器常见功能 ❌（MaidMic 缺失）
| 功能 | 优先级 | 说明 |
|------|--------|------|
| **Formant Shifting** (共振峰偏移) | 🔴 高 | 让变调听起来自然，不产生"花栗鼠/巨人"效果 |
| **Distortion** (失真) | 🔴 高 | Overdrive/Fuzz，机器人声、恶魔声 |
| **Chorus** (合唱) | 🟡 中 | 多层叠加，增加声音厚度 |
| **Echo/Delay** (回声) | 🟡 中 | 与现有混响互补 |
| **Noise Gate** (噪声门) | 🟡 中 | 消除背景噪音 |
| **Bitcrushing** (降比特) | 🟢 低 | Lo-Fi 效果 |
| **Vibrato** (颤音) | 🟢 低 | 周期性音高调制 |
| **Voice Lock** (语音锁定) | 🟢 低 | 监听时实时听到处理效果 |
| **Audio Recording** (录音) | 🟢 低 | 保存处理后的音频 |
| **Auto-Tune** (自动校音) | 🔴 高 | 将人声量化到最近的音阶 |
| **Soundboard** (音板) | 🟢 低 | 播放预录制音效 |

### Android 平台特有缺口
| 功能 | 优先级 | 说明 |
|------|--------|------|
| Bluetooth LE Audio | 🟡 中 | LC3 编码支持 |
| AudioFocus 管理 | 🟡 中 | 避免与其他 App 音频冲突 |
| 低延迟模式 (<50ms) | 🔴 高 | 当前简单处理约 5-10ms，但完整 pipeline + 大量 biquad 可能增加延迟 |

---

## 建议下一步开发方向

### 短期 (1-2 次开发)
1. **Formant Shifting** — 最重要的缺失功能，配合现有 Pitch Shift 实现自然变声
2. **Distortion** — 第二重要，能实现机器人、恶魔等经典效果
3. **Suppress build warnings** — ✅ 已完成
4. **Scrollable UI** — ✅ 已完成

### 中期
5. **Chorus + Echo** — 与 Reverb 统一管理
6. **Noise Gate** — 提升音质下限
7. **更低延迟** — 当前简单 DSP 延迟 ok，但 10 段 biquad 级联可能有延迟

### 长期
8. **Auto-Tune** — 需要音高检测算法
9. **Soundboard** — 简单的 WAV 播放器集成
10. **录音功能** — 保存处理后的音频文件

---

参考来源:
- Human or Not: Best Voice Changer Apps 2026
- SoundTools.io Voice Changer feature list
- Voicemod, MagicMic, Voice FX feature comparison
- Digital Trends: Best voice changer apps 2025
