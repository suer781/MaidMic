# MaidMic 效果插件开发指南（Lua）

MaidMic 支持用 **Lua 脚本**编写参数型效果插件：脚本通过引擎 API 组合
变调、共振峰、EQ、混响、失真等参数，实现自定义音色（电话音、花栗鼠、
低沉大叔……），激活/停用即时生效、可自动恢复。

> 模型说明：插件为**参数型**（不做逐样本音频处理）。LuaJ 解释器对
> 48kHz 逐样本循环的性能不可行，参数型是性能、安全与表达力的平衡点。

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

## 安全模型

- 脚本运行在沙箱中：`io` / `os` / `debug` / `require` / `dofile` / `loadfile`
  全部被移除，无法访问文件系统、网络或执行命令。
- `maidmic.*` API 只能读写白名单内的**引擎 DSP 参数**（按参数 key 在默认
  管线中查找），无任意内存/文件访问。
- `load_preset` 只能读取本插件目录下 `presets/*.json`，路径参数做了
  穿越校验，文件上限 64KB。
- 网络与命令执行 API（`http_get` / `exec`）仅为占位，当前版本不开放。
- 插件目录位于应用外部存储私有目录，卸载即清除。

---

## 预设数据（可选）

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
