# MaidMic 插件开发指南

MaidMic 采用**三层插件架构**（万物皆插件）：

| 层级 | 形式 | 能力 | 典型用途 |
|------|------|------|----------|
| **Tier 1 · PARAM** | Lua 脚本（沙箱） | 组合引擎参数 | 电话音、花栗鼠等效果预设 |
| **Tier 2 · DSP** | dex/apk（UGC 门控） | **自定义实时音频处理**，逐块 Float 域 | 环形调制、自定义滤波、第三方算法 |
| **Tier 3 · MODEL** | dex/apk（UGC 门控） | **自定义模型推理**，离线整段转换 | RVC / so-vits / 统计模型变声 |

宿主只认统一契约（PCM 进出、能力接口），不感知插件内部实现——
Tier 2 可以是任何算法，Tier 3 可以是任何推理后端（ONNX Runtime、
ncnn、自研统计模型），引擎与模型完全解耦。

---

# Tier 1 · 参数插件（Lua）

MaidMic 支持用 **Lua 脚本**编写参数型效果插件：脚本通过引擎 API 组合
变调、共振峰、EQ、混响、失真等参数，实现自定义音色（电话音、花栗鼠、
低沉大叔……），激活/停用即时生效、可自动恢复。

> 模型说明：插件为**参数型**（不做逐样本音频处理）。LuaJ 解释器对
> 48kHz 逐样本循环的性能不可行，需要音频级处理请写 Tier 2 DSP 插件。

---

## 快速开始

1. 把 `.lua` 插件文件放入设备目录：
   `Android/data/aoeck.dwyai.com/files/maidmic_plugins/`
2. 打开 App → 设置 → 插件 → 「重新扫描插件」
3. 打开插件开关激活，录音/试听即应用效果；关闭开关停用并自动恢复参数

App 内置了三个示例（首次启动自动释放到插件目录，可直接修改学习）：
`radio_telephone.lua`（电话音）、`chipmunk.lua`（花栗鼠）、
`deep_uncle.lua`（低沉大叔）。

---

## 脚本结构

```lua
-- 元数据（设置页展示）
plugin_info = {
    name = "电话音",                  -- 显示名
    author = "你的名字",
    version = 1,
    description = "老式电话音色"        -- 一句话描述
}

-- 激活时调用（必需）
function activate()
    maidmic.set_param("bass_db", -10.0)
    maidmic.set_param("treble_db", 6.0)
    maidmic.set_param("distortion", 0.15)
end

-- 停用时调用（可选，用于恢复参数）
function deactivate()
    maidmic.set_param("bass_db", 0.0)
    maidmic.set_param("treble_db", 0.0)
    maidmic.set_param("distortion", 0.0)
end
```

---

## API

| 函数 | 说明 |
|------|------|
| `maidmic.set_param(key, value)` | 设置引擎参数（数值） |
| `maidmic.get_param(key)` | 读取引擎参数当前值（未找到返回 0） |
| `maidmic.load_preset(name)` | 读取本插件 `presets/<name>.json`（上限 64KB） |
| `maidmic.log(msg)` | 写 logcat（tag: `LuaPlugin[插件名]`） |

### 可用参数 key（默认管线）

| key | 模块 | 范围 |
|-----|------|------|
| `gain_db` | 增益 | -24 ~ +24 dB |
| `comp_threshold` / `comp_ratio` / `comp_makeup` | 压缩器 | -60~0 dB / 1~20 / 0~20 dB |
| `bass_db` / `treble_db` | 低音/高音 | -12 ~ +12 dB |
| `reverb_mix` | 混响 | 0 ~ 1 |
| `pitch_semitones` | 变调（PSOLA，共振峰保持） | -12 ~ +12 半音 |
| `formant_shift` | 共振峰偏移（极点旋转） | -12 ~ +12 半音 |
| `distortion` | 失真 | 0 ~ 1 |
| `echo_delay_ms` / `echo_decay` | 回声 | 0~2000 ms / 0~0.9 |
| `bitcrush_bits` / `bitcrush_down` / `bitcrush_mix` | 降比特 | 1~16 / 1~32 / 0~1 |

> 注意：Vibrato/Chorus/AutoTune/NoiseGate/Limiter/Presence 不在默认链上，
> 参数 key 对它们无效（需经设置页的模块链编辑器挂载后另议）。

---

## 生命周期与最佳实践

- **激活顺序**：激活新插件前，管理器会先停用当前激活插件（调用其
  `deactivate()`），同一时间只有一个插件生效。
- **参数恢复**：建议在 `activate()` 里用 `maidmic.get_param()` 记录原值，
  在 `deactivate()` 里恢复（见 `deep_uncle.lua` 示例）。
- **激活在后台线程执行**：`activate()` 里的耗时操作不会卡 UI，但也拿不到
  UI 线程 API；单次调用超过 50ms 会在日志中告警。
- **状态提示**：插件出错时设置页会显示「出错」与错误信息，引擎参数保持
  上一次状态。

---

## 安全模型（Tier 1）

- 脚本运行在沙箱中：`io` / `os` / `debug` / `require` / `dofile` / `loadfile`
  全部被移除，无法访问文件系统、网络或执行命令。
- `maidmic.*` API 只能读写白名单内的**引擎 DSP 参数**（按参数 key 在默认
  管线中查找），无任意内存/文件访问。
- `load_preset` 只能读取本插件目录下 `presets/*.json`，路径参数做了
  穿越校验，文件上限 64KB。
- 网络与命令执行 API（`http_get` / `exec`）仅为占位，当前版本不开放。
- 插件目录位于应用外部存储私有目录，卸载即清除。

---

# Tier 2 · DSP 插件（自定义实时音频处理）

实现 `DspAudioPlugin` 接口，打包为 dex/apk，即可被宿主以 DexClassLoader
加载并挂入**实时处理链**（引擎管线之后串行，逐块 Float 域原地处理）。

## 接口

```kotlin
interface DspAudioPlugin {
    val pluginId: String
    val pluginName: String
    val pluginAuthor: String
    val pluginDescription: String

    fun init(sampleRate: Int, channels: Int)          // 流开始时调用
    fun process(samples: FloatArray, frames: Int, channels: Int)  // 逐块原地处理
    fun release()                                      // 停用时释放
}
```

## 打包格式

`.apk`（或 .zip/.jar）内含：

```
classes.dex     实现 DspAudioPlugin 的类
plugin.json     清单：{ "id", "entry": "实现类全名", "name", "author", "description" }
```

放置目录：`Android/data/aoeck.dwyai.com/files/maidmic_plugins_ext/`
→ 设置 → 插件 → 扩展插件 → 打开开关即挂入实时链。

## 示例工程

仓库 `examples/dsp-plugin/` 是一个完整的环形调制机器人插件：
- `src/.../RingModPlugin.kt` 实现参考（30 行核心处理）
- `build.gradle.kts` 含自动打包任务：`./gradlew assembleRelease`
  → `build/outputs/plugin_ringmod.apk` 直接可用
- 注意：`DspAudioPlugin.kt` 为接口副本（与宿主保持一致），构建期
  compileOnly，运行时由宿主加载

## 性能与安全

- `process()` 在**音频线程**逐块调用（48kHz 下每秒约 47~187 块），
  必须无阻塞、无堆分配、单块耗时 < 1ms；抛异常的插件会被自动停用。
- dex 插件是**任意代码执行**（拥有 App 全部权限），仅在
  「开发者设置 → UGC 插件」开启后加载，来源需可信。

---

# Tier 3 · 模型插件（自定义模型，RVC 接入面）

实现 `ModelVoicePlugin` 接口，即可把任何**离线整段转换**的变声模型
接入宿主——包括真正的 RVC（HuBERT 内容特征 + F0 + 声码器）、
so-vits-svc、GMM 统计模型等。宿主只认 PCM 进出，不感知推理后端。

## 接口

```kotlin
interface ModelVoicePlugin {
    val pluginId: String
    val pluginName: String
    val pluginAuthor: String
    val pluginDescription: String

    fun loadModel(modelFile: File?): Boolean   // 加载模型文件（.onnx/.bin 由插件解释）
    fun convert(input: ShortArray, sampleRate: Int): ShortArray  // 整段转换
    fun release()
}
```

## 运行方式

- **离线转换**：设置 → 插件 → 模型插件 → 点按应用到最近语音包，
  生成新语音包（不覆盖原包）；推理在后台线程，可耗时数秒。
- **内置参考实现**：`SpectralMorphModel`（STFT 谱包络搬移，纯 Kotlin、
  无依赖），演示完整模型插件形态；RVC 插件用同一接口替换其内部为
  ONNX Runtime 推理即可。
- 打包/放置方式与 Tier 2 相同（`maidmic_plugins_ext/` + plugin.json）。

## RVC 插件实现要点（路线图）

1. 依赖 `com.microsoft.onnxruntime:onnxruntime-android`（或 ncnn）
2. `loadModel` 下载/解压模型（内容编码器 + F0 模型 + 声码器）
3. `convert` 内：重采样 → 内容特征 → F0（RMVPE/CREPE）→ 检索/映射 →
   声码器 → 返回 PCM
4. 建议首次运行时把模型下载到 `files/models/` 并缓存；宿主提供
   `load_preset` 式的目录约定（`maidmic_plugins_ext/<id>/models/`）

---

## 安全模型（Tier 2/3）

- dex/apk 插件是**任意代码执行**（拥有 App 全部权限）——对应权限分级
  中的 NATIVE 级，仅在「开发者设置 → UGC 插件」显式开启后加载。
- 请只安装来源可信的插件包；企业分发可另行引入签名校验。

---

## 预设数据（可选，Tier 1）

插件可在目录下放置预设数据供脚本运行时读取：

```
maidmic_plugins/
├── my_plugin.lua
└── my_plugin/
    └── presets/
        └── clean.json
```

脚本中：

```lua
local json = maidmic.load_preset("clean")
if json then
    maidmic.log("载入预设: " .. json)
    -- 自行解析 json（脚本内可实现简易解析，或按 key 逐个 set_param）
end
```
