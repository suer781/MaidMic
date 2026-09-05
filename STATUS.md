# MaidMic 开发状态 📋

> 最后更新：2026-09-05

## 当前状态：DSP 引擎 v3 大修完成（变声质量重做）

本轮针对"变声效果差"做了一次引擎级大修：变声核心从
"磁带式变调 + Shelving 伪共振峰" 整体重做为
**TD-PSOLA 变调 + 抽取域 LPC 极点旋转共振峰偏移**，
并按市面主流变声器（Voicemod / MagicMic / MorphVOX）的功能基线
补齐了效果模块与预设参数。

---

## 🔥 本轮大修内容（引擎 v3）

### 1. 变声核心重做：VoiceTransform v3（id 15）
- **变调 = TD-PSOLA**（`src/voice/psola.c`，全新）：
  基音同步重叠相加，窗长恒为 2*max(Ta,Ts)（不随比率缩放）→
  **共振峰完整保留**，±12 半音内无"小黄人"音色偏移；
  清音段自动恒等重构（Hann COLA 精确直通），无伪周期嗡声；
  读标记 Σ-Δ 逼近保证输入恰消耗一遍、无漂移。
- **共振峰偏移 = 真极点旋转**（重写 `voice_transform.c`）：
  信号 ×D 抽取到 ~16kHz（48k 用 67-tap 线性相位 FIR，D=3）→
  25ms 帧 / 75% 重叠 LPC（阶数 16 在 16k 域分辨率足够）→
  Durbin-Kerner 求全部极点 → 角度统一缩放 θ' = θ·c →
  Schur 稳定化（反射系数钳位，杜绝 float 量化导致的 IIR 爆炸）→
  重建全极点滤波器 → "逆滤波 + 移包络再合成" OLA；
  高频带（擦音/齿音）经多相内插校正叠加，原样保留。
  **这是真正的共振峰搬移**（性别变声的关键），不是旧版的倾斜滤波。
- 每帧激励能量归一（g1）+ 输出响度归一（g2 快起慢落），消除泵动。
- 主机数值验证：+7st 变调误差 0.4%；+4st 共振峰偏移误差 <5%。

### 2. 默认链接入（Kotlin DEFAULT_CHAIN 与 C++ 预置链同步）
- 新链：Gain → Compressor → Bass → Treble → Reverb → **VoiceTransform**
  → Distortion → Echo → **Bitcrusher(bypass)**
- `nativeSetEqParams` 的 pitch/formant 现在写入 VoiceTransform
  （模块被编辑器移除时自动回退旧 Pitch+Formant 兼容模式）。

### 3. 新效果模块（对齐市面变声器功能清单）
| 模块 | ID | 说明 |
|------|----|------|
| **Reverb v2** | 5 | Freeverb 式（8 组合器 + 4 全通 + 阻尼），替代单延迟线金属回声 |
| **Vibrato** | 19 | 延时线音高颤音（rate/depth 半音） |
| **Chorus** | 6 | 三路调制合唱（相位 0/120/240°） |
| **Bitcrusher** | 20 | 降比特 + 采样率保持（机器人音色核心） |

### 4. 预设重调（依据人声变换研究基线）
研究结论（Praat / 语音转换文献）：男→女需 **F0 ×1.5~1.7（+7~+9 半音）
且共振峰 ×1.1~1.2（+2~+3 半音）同时偏移**；旧预设（+4/+2）远不到位。
- 萝莉 +7st / +3st　·　御姐 +3st / +1.5st（新增）
- 大叔 −5st / −3st　·　机器人 = 失真 + Bitcrusher（新增联动）
- 原声 / 自定义

### 5. JNI / Kotlin 同步
- 新增 JNI 入口：`nativeSetVibrato` / `nativeSetChorus` / `nativeSetBitcrusher`
  （模块不在链中时自动挂载，mix=0 自动旁路）。
- `PipelineController.DEFAULT_CHAIN` 同步更新（仍 9 模块，
  `DEFAULT_PIPELINE_PREPOPULATED_MODULES` 不变）。
- 模块链编辑器调色板加入全部新模块 + 参数定义。

### 6. 端到端真实性验证（App 真实路径集成测试，防自嗨）
新增 `test/app_path_check.c`，**完全复刻 App 录音路径**（S16 单声道 48kHz、
1024 样本块、9 模块默认链、预设参数），实测发现并修复了两个只有
真实路径才暴露的问题：
- **录音收尾截断**：变声 v3 有约 82ms 流级延迟（输出流滞后输入流），
  录音停止时不排空的话，语音包结尾约 0.1s（最后一个字的尾音）会留在
  管线缓冲里丢失（实测尾部能量只剩 53%）。修复：录音循环结束后送
  8 块全零排空延迟线（约 170ms，同时把混响尾音带出）。
- **快照模块 ID 过时**：`captureChainSnapshot` 记录的还是旧
  Pitch(4)/Formant(13)，已改为 VoiceTransform(15)/Bitcrusher(20)。
- 真实路径变调验证：120Hz 输入 → 179.7Hz 输出（+7 半音，期望 179.8Hz）。
- 顺带修复预设索引升级迁移（旧用户存储的 0~4 索引映射到新列表）。

> 诚实记录：集成测试第一版自身有越界 bug（最后不满块按整块处理），
> 造成的堆破坏一度被误判为引擎问题；逐模块二分排查后确认引擎无辜，
> 测试已按 App 的"按实际字节数处理"语义修正。

---

## 🎈 悬浮球交互 v2 重构（2026-09-05）

针对"操作不顺手"的完整手势层重构（不改录音/面板业务逻辑）：

| 痛点 | 旧版 | 新版 |
|------|------|------|
| 单击开面板迟钝 | 等 300ms 判定双击 | **移除双击手势，单击 0ms 响应** |
| 长按 3 秒才录音 | 默认 3000ms | **600ms**（微信级响应，可调 500~5000） |
| 长按盲等 | 触发前无反馈 | **按住绘制进度弧** + 轻微放大，转满触发 |
| 录完试听两步 | 开面板→点播放 | **绿球单击=直接播放**（贴合"绿色=待播放"语义） |
| 拖完停哪算哪 | 无吸附无记忆 | **弹性吸附左右边缘 + 位置持久化**（重启恢复） |
| 贴边挡视线 | 永远全亮 | IDLE 态贴边**自动半透明 0.55**，触摸恢复 |
| 面板生硬弹出 | 直接出现 | **淡入 + 缩放 + 朝球方向滑入**动画 |

交互闭环：录音（长按）→ 球变绿 → 单击试听 → 球回紫 → 单击开面板调整效果 → 长按覆盖重录。
播放开始即回紫（防止播放中单击误触发重播）。拖动过程 y/x 均钳制在屏幕内。

涉及文件：FloatingBallView（手势状态机 + 渲染）、FloatingBallService（回调接线）、
FloatingPanel（入场动画）、SettingsPage（默认时长/文案）、BallInteractionCallback（文档）。

---

## 🔌 插件系统落地（2026-09-05）

原状：LuaJ 依赖与 `LuaPluginSandbox` 骨架早已存在，但无任何调用方、
JNI 三个桥（get/set_param、load_preset）是空壳、设置页入口被注释隐藏。
本轮把"参数型效果插件"做成了完整闭环：

- **JNI 补齐**：`nativeGetEngineParam/nativeSetEngineParam` 按参数 key 在
  默认管线全链查找模块并读写（gain_db/pitch_semitones/... 白名单）；
  `nativeLoadPreset` 读取插件目录预设 JSON（路径穿越校验 + 64KB 上限）；
  新增 `nativeSetPluginDir` 注入数据目录
- **PluginManager（新）**：assets 内置插件首启释放 + 外部目录扫描、
  plugin_info 元数据解析、单激活模型（激活新插件先停旧插件）、
  activate/deactivate 后台线程执行、状态持久化（重启自动恢复）
- **沙箱整理**：移除不可行的 processFrame 逐样本处理模型（LuaJ 性能），
  补 metadata()/callActivate()/callDeactivate()，external 声明改 public
  防 JNI 符号修饰
- **UI**：设置页新增「插件」区块（列表/开关/重新扫描/错误提示），
  **恢复模块链编辑器入口**（原注释：与 UGC 配套后恢复）
- **内置示例**：电话音 / 花栗鼠 / 低沉大叔（assets 首启释放，可改可学）
- **文档**：新增 `PLUGIN_API.md` 插件开发指南（API 表 / 生命周期 / 安全模型）

设计取舍：**参数型插件而非逐样本音频处理**——LuaJ 解释器对 48kHz
逐样本循环性能不可行（≈100× 慢 + JNI 双向拷贝），参数型是性能、
安全与表达力的平衡点；原生 .so 模块加载（NATIVE 权限级）仍留作未来方向。

---

## 瓶颈：实时变声拦截需要 Root（未变）

**这仍是整个项目最大的平台级卡点**（与 DSP 质量无关）：
非 root 下 Android 无法拦截系统音频流注入处理结果。
当前产品形态为**录音后处理重放**（录音 → DSP → 语音包 → 外放），
本轮所有 DSP 改进都在该链路上生效。

---

## ✅ 已完成的里程碑

- [x] 双引擎架构（直通 / Echio 均衡）
- [x] Echio 引擎 v2：9 模块默认链 + 多声道 + 参数平滑 + 零分配
- [x] **DSP 引擎 v3 大修（本轮）**：
  - [x] TD-PSOLA 流式变调引擎（psola.c，共振峰保持）
  - [x] 抽取域 LPC 极点旋转共振峰偏移（voice_transform.c v3）
  - [x] Freeverb 式混响 v2
  - [x] Vibrato / Chorus / Bitcrusher 三个新模块
  - [x] VoiceTransform 接入默认链 + JNI 参数映射 + 兼容回退
  - [x] 预设按研究基线重调 + 机器人预设联动 Bitcrusher
  - [x] 主机测试 13/13 通过（含变调比例 / 共振峰偏移 / 混响稳定性 /
        颤音深度 / 量化台阶断言），NDK arm64 交叉编译零警告
- [x] 四 Tab 主界面 + 悬浮球
- [x] 音效库 + 语音包（录音→变声→存包→外放）
- [x] Material 3 暗色主题 + 前台保活 + 状态持久化

## 📅 未来可能实现

1. **真机冒烟**：录音→处理→重放链路听感验收（本轮 DSP 需人耳确认）
2. **共振峰级自适应阶数**：按 sr_low 自动选择 14~20 阶
3. **Auto-Tune UI 接入** — 引擎 + JNI 已就绪，缺 App 端入口
4. **PC 协同（非实时方向）** — 已有 streaming 雏形
5. **AI 音色转换（RVC 式）** — AAL 加速层已预留接口

---

*本项目由 [我是真的会谢](https://github.com/suer781) 独立开发*
*B站：https://b23.tv/JvcdN4I | 抖音：https://v.douyin.com/cT8XUPBO*
