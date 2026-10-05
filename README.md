# MaidMic 🎤✨

**变声录音 · 自研 DSP 引擎 · 开源**

MaidMic 是一个开源的 Android 变声录音工具，搭载自研 **Echio 变声引擎**（C/C++），
录音时实时 DSP 处理并保存为语音包：增益、压缩、均衡器、混响、变调、共振峰偏移、失真、回声、颤音、合唱、降比特。

## 特性

### 🎛 双引擎切换
| 引擎 | 说明 |
|------|------|
| **直通模式** | 音频不经处理直接透传，零延迟 |
| **Echio 均衡** | 完整 DSP 链：增益→压缩→低音→高音→混响→变声→失真→回声→降比特 |

### 🎭 变声核心（Echio v3）
- **TD-PSOLA 变调** — 基音同步重叠相加，±12 半音，共振峰完整保持（无"小黄人"音色偏移）
- **真·共振峰偏移** — 抽取域 LPC 极点旋转：音高与音色（声道共振峰）独立控制，
  支持可信的性别变声（男↔女、童声）
- 每帧能量归一，无泵动、无 zipper 噪声

### 🎵 实时 DSP 效果
- **增益 / 压缩** — 音量与动态控制
- **均衡器** — 低音/高音 shelving 均衡
- **混响** — Freeverb 式（8 组合器 + 4 全通 + 高频阻尼），人声调优
- **失真** — 软削波 waveshaping，机器人/恶魔声
- **回声** — 反馈延迟线，最长 2000ms，衰减可调
- **颤音** — 延时线音高调制（0.1~10Hz，0~2 半音）
- **合唱** — 三路调制延迟（相位 0°/120°/240°）
- **降比特** — 位深量化 + 采样率保持（Lo-Fi / 机器人）

### 🔌 插件系统（三层架构，万物皆插件，全部可用）
- **Tier 1 参数插件**（Lua 沙箱）：`maidmic.set_param("pitch_semitones", 7)`
  组合引擎参数；内置电话音 / 花栗鼠 / 低沉大叔示例（首启自动释放到插件目录）
- **Tier 2 DSP 插件**（dex，UGC 门控）：实现 `DspAudioPlugin` 接口的
  **自定义实时音频处理**，DexClassLoader 加载挂入实时链
  （示例工程 `examples/dsp-plugin/` 直接构建插件 apk）
- **Tier 3 模型插件**（dex，UGC 门控）：实现 `ModelVoicePlugin` 接口的
  **自定义模型离线转换**——RVC 等推理模型实现同一接口即可接入，
  宿主只认 PCM 进出；内置 STFT 谱包络变换参考模型
- **UGC 开关**：设置 → 开发者设置 → 确认免责声明后开启 UGC；
  开启后可扫描 / 启用 / 卸载扩展插件（dex/apk）
- **模型插件**：设置 → 插件 → 模型插件，「应用到最近语音包」
  生成新语音包（不覆盖原包）；内置参考模型同样支持
- **DSP 插件状态持久化**：启用集合写入 SharedPreferences，
  App 重启后自动恢复上次启用的 DSP 插件链
- 沙箱与权限分级：Lua 全沙箱；dex 插件为 NATIVE 级（任意代码执行），
  需开发者设置开启 UGC 后加载
- 开发指南见 [PLUGIN_API.md](PLUGIN_API.md)

### 🎯 变声预设
萝莉 · 御姐 · 大叔 · 机器人 · 原声 · 自定义
（参数基于人声变换研究基线：音高与共振峰按性别转换典型比例联合偏移）

### 📱 主界面（四个 Tab）
- **变声** — 预设选择 + 变调/共振峰/失真调节 + 录音存包
- **EQ** — 引擎切换 + 增益/低音/高音/混响/回声等完整参数
- **音效库** — 导入音频音效、语音包（录音→变声→存包→外放）
- **设置** — 变声节拍开关、关于（GitHub / 爱发电）

### 🎈 悬浮球（交互 v2）
- **单击零延迟**：紫球开/关面板；**绿球（录完未听）单击直接播放**最近语音包
- **长按 PTT 录音**：默认 600ms（可调 500~5000ms），按住时球上绘制**进度弧**，
  转满触发（震动×2 反馈）；绿色态长按 = 覆盖重录；松手后再录 0.5s 自动停止
- **拖动 + 边缘吸附**：松手弹性吸附到最近的左右边缘，位置持久化（重启恢复）；
  IDLE 态贴边后自动半透明，触摸即恢复
- 面板：录音 / EQ与增益 / 变音 / 快捷音效库 四页，点外部收起
- 模块链编辑器：DSP 模块增删、排序、旁路，实时生效（当前为线性模式）

### ⚠️ 当前限制 / 占位

以下为如实标注的未完成或占位项，与代码现状一致：

- **采样率设置实际钳制到 48kHz**：设置页提供 44.1kHz / 48kHz 选项，但录音器、
  引擎与 DSP 插件链统一按 48kHz 运行，非 48k 选择会被钳到 48k。
- **DAG 可视化编辑器未实现**：模块链编辑器当前只有线性模式（增删/排序/旁路/
  参数可用）；开发者设置中的 DAG 模式切换仍为占位，选中后以线性模式兜底展示。
- **后台保活服务默认关闭**：悬浮球/前台保活服务默认不启动，需在设置中显式开启
  并授予悬浮窗权限；引导页的「后台保活」仅引导加入电池优化白名单。
- **Shizuku / 无障碍 / Root 虚拟麦克风桥为存根未实现**：JNI 侧仅有满足链接的
  存根函数，Root 桥的 Kotlin 类尚不存在；实时监听/虚拟麦克风注入不在当前产品形态内。
- **streaming 无 UI**：双设备音频流（Wi-Fi UDP / 蓝牙）仅有底层
  `streaming/` 包与连接管理器，App 内没有可用界面。
- **示例插件工程需要 Android SDK 路径**：`examples/dsp-plugin` 构建时通过
  `local.properties` 的 `sdk.dir` 或 `ANDROID_HOME` 环境变量定位 SDK，
  否则 Gradle 会报错提示。

### 🎤 预录音变声
- 录音时实时应用 DSP 效果，直接存为语音包（WAV），随时外放
- 无需 root / 虚拟麦克风，纯本机录音处理

### 其他
- Material 3 暗色主题（Jetpack Compose）
- 前台保活服务
- 状态持久化（EQ 参数、引擎选择跨重启保留）

## 构建

CI 自动构建 APK，推送到 `main` 分支即可触发。
可在 [Actions](https://github.com/suer781/MaidMic/actions) 页面下载 `.apk` 文件。

### 本地构建

```bash
cd maidmic-app
./gradlew assembleDebug
```

### 构建示例 DSP 插件

仓库已为 `examples/dsp-plugin` 补全 Gradle wrapper，可直接构建：

```bash
cd examples/dsp-plugin
./gradlew assembleRelease
```

产物为 `build/outputs/plugin_ringmod.apk`（内含 `classes.dex` + `plugin.json`），
拷到手机 `Android/data/aoeck.dwyai.com/files/maidmic_plugins_ext/`，
在设置 → 插件 → 扩展插件中启用即可。

> 构建需要本机 Android SDK：在 `examples/dsp-plugin/local.properties` 配置
> `sdk.dir`（如 `sdk.dir=C\:\\AndroidSdk`）或设置 `ANDROID_HOME` 环境变量；
> 插件接口副本 `DspAudioPlugin.kt` 是随插件一起编译进 dex 的普通源文件。

### 依赖

- Android SDK 34
- NDK (CMake 3.22+)
- JDK 17+（编译目标 17）

## 鸣谢

- **Shizuku** by Rikka Apps — 非 root 权限提升
- **LuaJ** — Lua 插件沙箱运行时

## 算法来源与许可证声明

本项目为 **Apache 2.0** 开源项目，**不包含任何闭源或 Copyleft 第三方代码**：

- `maidmic-engine/` 下全部 C/C++ 源码为本项目原创实现，仅采用公开的
  学术/教科书算法：
  - **TD-PSOLA 变调** — Moulines & Charpentier (1990) 发表算法，自行实现流式版本
  - **LPC（自相关 + Levinson-Durbin）** — 经典语音编码算法
  - **Durbin-Kerner 多项式求根 / Schur-Cohn 稳定化** — 数值分析教科书方法
  - **Freeverb 混响拓扑**（8 组合器 + 4 全通）— 公有领域经典结构，参数为本项目调优
- 未使用 SoundTouch（LGPL）、Rubber Band（GPL）、Praat（GPL）等任何
  Copyleft 库的代码或二进制；
- 依赖库：AndroidX、Shizuku（均为 Apache-2.0）、LuaJ（BSD 类许可），
  与本项目许可兼容。

## 许可

Apache 2.0

---

**作者**：[我是真的会谢](https://github.com/suer781)
**B站**：https://b23.tv/JvcdN4I
**抖音**：https://v.douyin.com/cT8XUPBO
