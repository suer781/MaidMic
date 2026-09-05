# MaidMic 功能缺口分析
## Gap Analysis vs Industry Voice Changer Standards

日期: 2026-04-29

---

## 现状 vs 行业标准

### MaidMic 已有功能 ✅
| 功能 | 状态 | 备注 |
|------|------|------|
| 引擎选择器 | ✅ | 直通 / Echio 均衡 |
| 音量增益 (Gain) | ✅ | ±10dB |
| 均衡器 (EQ) | ✅ | Echio: 低音+高音 shelving |
| 混响 (Reverb) | ✅ | 简单延迟线 |
| 变调 (Pitch Shift) | ✅ | ±12 半音，相位对齐拼接（PSOLA 类） |
| 预设 (Presets) | ✅ | 5 个变声预设（萝莉/大叔/机器人/原声/自定义） |
| 录音存包 | ✅ | VoicePackRecorder（录音→变声→存包） |
| 语音包外放（音板） | ✅ | VoicePackPlayer |
| 悬浮球快捷面板 | ✅ | EQ/变声开关、长按录音 |

### 行业变声器常见功能盘点（2026-09 DSP v3 大修后）
| 功能 | 状态 | 说明 |
|------|------|------|
| **Gender Voice Conversion** (性别变声) | ✅ 已实现 | VoiceTransform v3：TD-PSOLA 变调 + 抽取域 LPC 极点旋转共振峰，音高/音色独立控制 |
| **Formant Shifting** (共振峰偏移) | ✅ 已实现 | 真极点角度缩放（±12 半音），替代旧 Shelving 近似 |
| **Distortion** (失真) | ✅ 已实现 | 软削波 waveshaping，0~1 驱动量 |
| **Echo/Delay** (回声) | ✅ 已实现 | 反馈延迟线，最长 2000ms |
| **Reverb** (混响) | ✅ 已升级 | Freeverb 式（8 组合器 + 4 全通 + 阻尼），替代单延迟线 |
| **Noise Gate** (噪声门) | ✅ 已实现 | noisegate.c，编辑器可挂载 |
| **Limiter** (限制器) | ✅ 已实现 | Look-ahead 峰值钳制 |
| **Vibrato** (颤音) | ✅ 新增 | 延时线音高调制（0.1~10Hz / 0~2 半音） |
| **Chorus** (合唱) | ✅ 新增 | 三路调制延迟（0/120/240° 相位） |
| **Bitcrushing** (降比特) | ✅ 新增 | 位深量化 + 采样率保持（机器人音色核心） |
| **Audio Recording** (录音) | ✅ 已实现 | VoicePackRecorder（录音→变声→存包）+ WavWriter |
| **Soundboard** (音板) | ✅ 已实现 | VoicePackPlayer 语音包外放 |
| **Auto-Tune** (自动校音) | 🟡 引擎就绪 | autotune.c + nativeSetAutoTune 已实现，App 端无 UI 入口 |
| **Voice Lock** (语音锁定) | ❌ 不适用 | 非实时定位（录音后处理），实时监听不在当前范围 |

### Android 平台特有缺口
| 功能 | 优先级 | 说明 |
|------|--------|------|
| Bluetooth LE Audio | 🟡 中 | LC3 编码支持 |
| AudioFocus 管理 | 🟡 中 | 避免与其他 App 音频冲突 |
| 低延迟模式 (<50ms) | 🔴 高 | 当前简单处理约 5-10ms，但完整 pipeline + 大量 biquad 可能增加延迟 |

---

## 建议下一步开发方向

### 短期 (已实现 ✓)
1. **Formant Shifting** — 配合 Pitch Shift 实现自然变声 ✅
2. **Distortion** — 实现机器人、恶魔等经典效果 ✅
3. **Echo/Delay** — 反馈延迟线，最长 500ms ✅
4. **5 个变声预设** — 萝莉/大叔/机器人/原声/自定义 ✅

### 中期
5. **Noise Gate 接入** — 引擎已实现（noisegate.c），接入默认链 + UI
6. **Auto-Tune UI 接入** — 引擎 + JNI 已就绪，App 端无入口
7. **更低延迟** — 当前 9 模块级联，完整管线延迟待压到 <50ms

### 长期
8. **Chorus** — module.h 已预留 ID 6，需实现模块
9. **Bitcrushing / Vibrato** — Lo-Fi 与颤音效果
10. **Voice Lock** — 监听时实时听到处理效果

---

参考来源:
- Human or Not: Best Voice Changer Apps 2026
- SoundTools.io Voice Changer feature list
- Voicemod, MagicMic, Voice FX feature comparison
- Digital Trends: Best voice changer apps 2025
