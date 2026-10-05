# CLAUDE.md

MaidMic 项目协作约定（面向 AI 助手 / 协作者）。
本文档描述本仓库的模块 ID、构建命令、插件契约、已知未完成项与 Git 工作流。
与代码现状不一致的旧文档以本文为准；发现文档滞后时先更新本文，再改 README/STATUS。

---

## 1. 项目结构

```
maidmic-app/       Android App（Kotlin + Jetpack Compose），内含 JNI 加载与插件系统
maidmic-engine/    Echio DSP 引擎（C/C++），CMake 构建，经 JNI 桥接
examples/dsp-plugin/  Tier 2 DSP 插件示例工程（独立 Gradle 工程）
maidmic-p2p/       预留（未在本文描述范围内）
```

引擎源码分布：
- `src/core/`：pipeline.c（管线/拓扑/锁）、ring_buffer.c
- `src/dsp/`：gain/compressor/bass/treble/reverb/pitch/formant/distortion/echo/
  noisegate/limiter/presence/vibrato/chorus/bitcrush
- `src/voice/`：lpc/pitch_detector/psola/voice_transform/autotune/voiceprint_mask
- `src/accel/`：accel.c（NEON SIMD 加速层）
- `src/api/`：maidmic_api.c（C API 导出）、maidmic_jni.cpp（JNI 桥）

---

## 2. 模块 ID 表

定义在 `maidmic-engine/include/maidmic/module.h`；JNI 侧 `maidmic_jni.cpp`
对未定义宏做了 `#ifndef` 补齐，两处必须保持一致。

| ID | 宏 | 模块 |
|----|----|------|
| 1 | `MAIDMIC_MODULE_ID_GAIN` | Gain 增益 |
| 2 | `MAIDMIC_MODULE_ID_EQ` | EQ（预留） |
| 3 | `MAIDMIC_MODULE_ID_COMPRESSOR` | Compressor 压缩器 |
| 4 | `MAIDMIC_MODULE_ID_PITCH` | Pitch 变调（旧版，兼容） |
| 5 | `MAIDMIC_MODULE_ID_REVERB` | Reverb 混响 |
| 6 | `MAIDMIC_MODULE_ID_CHORUS` | Chorus 合唱 |
| 7 | `MAIDMIC_MODULE_ID_DISTORTION` | Distortion 失真 |
| 8 | `MAIDMIC_MODULE_ID_DELAY` | Delay 延迟（预留） |
| 9 | `MAIDMIC_MODULE_ID_NOISEGATE` | NoiseGate 噪声门 |
| 10 | `MAIDMIC_MODULE_ID_LIMITER` | Limiter 限制器 |
| 11 | `MAIDMIC_MODULE_ID_BASS` | Bass 低音 shelving |
| 12 | `MAIDMIC_MODULE_ID_TREBLE` | Treble 高音 shelving |
| 13 | `MAIDMIC_MODULE_ID_FORMANT` | Formant 共振峰（旧版，兼容） |
| 14 | `MAIDMIC_MODULE_ID_ECHO` | Echo 回声/延迟 |
| 15 | `MAIDMIC_MODULE_ID_VOICE_TRANSFORM` | VoiceTransform 变声核心 v3 |
| 16 | `MAIDMIC_MODULE_ID_VOICEPRINT_MASK` | VoiceprintMask 声纹脱敏 |
| 17 | `MAIDMIC_MODULE_ID_PRESENCE` | Presence 人声存在感 |
| 18 | `MAIDMIC_MODULE_ID_AUTOTUNE` | AutoTune 自动修音 |
| 19 | `MAIDMIC_MODULE_ID_VIBRATO` | Vibrato 颤音 |
| 20 | `MAIDMIC_MODULE_ID_BITCRUSH` | Bitcrush 降比特 |
| 999 | `MAIDMIC_MODULE_ID_LUA` | Lua 插件通用代理（预留） |

注意：默认管线只预置 Gain→Compressor→Bass→Treble→Reverb→VoiceTransform→
Distortion→Echo→Bitcrusher(bypass)。Vibrato/Chorus/NoiseGate/Limiter 等
经模块链编辑器挂载或 JNI `ensure_module_mounted` 动态挂载。

---

## 3. 构建命令

### 主机测试（非 Android，用 ziglang 的 cc）

```bash
python -m ziglang cc -O2 -std=c11 -Iinclude test/<name>.c \
  src/core/*.c src/dsp/*.c src/voice/*.c src/accel/*.c -lm -o <out>
```

常用测试：`test/host_mm_test.c`（模块级）、`test/app_path_check.c`
（复刻 App 录音路径）、`test/vt_check.c`（变声验证）、`test/diag_s16.c`。
Windows 下可用 `ziglang` 提供的 cc 替代 MSVC/gcc。

### NDK 交叉编译

App 通过 Gradle 的 `externalNativeBuild` 调 CMake（`maidmic-engine/CMakeLists.txt`，
CMake 3.22.1，`abiFilters = arm64-v8a, armeabi-v7a`，`ANDROID_STL=c++_shared`）：

```bash
cd maidmic-app
./gradlew assembleDebug
```

手动 NDK cmake 亦可：

```bash
cmake -B build-android \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared \
  -S maidmic-engine
cmake --build build-android
```

### 示例插件

```bash
cd examples/dsp-plugin
./gradlew assembleRelease   # 产物 build/outputs/plugin_ringmod.apk
```

需本机 Android SDK（`local.properties` 的 `sdk.dir` 或 `ANDROID_HOME`）。

---

## 4. 插件契约摘要

三层插件架构（详见 PLUGIN_API.md）：

- **Tier 1 · Lua 参数插件**：`.lua` 放
  `Android/data/aoeck.dwyai.com/files/maidmic_plugins/`；
  脚本通过 `maidmic.set_param/get_param/load_preset/log` 与引擎交互；
  沙箱移除 `luajava/load/loadstring/package/require/dofile/loadfile/io/os/debug/coroutine`，
  扫描阶段只纯文本解析 `plugin_info`，不执行顶层代码。
- **Tier 2 · DSP dex 插件**：`.apk/.jar/.dex` 放
  `Android/data/aoeck.dwyai.com/files/maidmic_plugins_ext/`；
  实现 `DspAudioPlugin`（init/process/release），实时链内逐块 Float 域原地处理。
- **Tier 3 · 模型插件**：同目录，实现 `ModelVoicePlugin`
  （loadModel/convert/release），离线整段 PCM 转换，设置页「应用到最近语音包」。

`plugin.json` 格式（apk/jar 内，或裸 dex 的同名 `.json`）：

```json
{
  "id": "example.ringmod",
  "entry": "com.example.maidmic.plugin.RingModPlugin",
  "name": "环形调制机器人（示例）",
  "author": "MaidMic",
  "description": "载波 30Hz 环形调制，机器人音色。"
}
```

仅 `entry` 必填；`id/name/author/description` 缺省时加载器自动补默认值。

JNI 参数 key 白名单（按 key 在当前默认管线全链查找，`find_module_by_param_key`）：
`gain_db`、`comp_threshold/comp_ratio/comp_makeup`、`bass_db`、`treble_db`、
`reverb_mix`、`pitch_semitones`、`formant_shift`、`distortion`、
`echo_delay_ms`（0~2000）/`echo_decay`（0~0.9）、
`bitcrush_bits/bitcrush_down/bitcrush_mix`；Vibrato/Chorus 参数 key
（`vibrato_rate/vibrato_depth/chorus_mix/chorus_rate/chorus_depth`）需模块已挂载。
`maidmic.set_param` 拒绝 NaN/Inf。

UGC 门控：Tier 2/3 是任意代码执行，必须在
设置 → 开发者设置 → 确认免责声明后开启 UGC 才扫描/加载。

---

## 5. 已知未完成项

修改代码或写文档时，不要把这些占位描述成已完成：

1. **DAG 可视化编辑器未实现** — 模块链编辑器仅线性模式（SimpleEditor），
   `DagEditor` 是占位，选中 DAG 模式后以线性模式兜底并提示。
2. **采样率钳制 48kHz** — 设置页 44.1/48k 选项实际都会被钳到 48k
   （录音器、引擎、DSP 插件链统一 48k）。
3. **虚拟麦克风桥为存根** — Shizuku/无障碍/Root 桥的 JNI 是满足链接的存根，
   Root 桥 Kotlin 类不存在。
4. **streaming 无 UI** — `streaming/` 底层存在但 App 内无界面。
5. **后台保活服务默认关闭** — 悬浮球/前台服务需显式开启并授权。
6. **AutoTune/Presence/VoiceprintMask 未接 UI** — 引擎 + JNI 可自动挂载，
   但编辑器调色板与主界面未暴露（NoiseGate/Limiter 可在编辑器手动挂载）。
7. **示例插件构建依赖本机 SDK 路径** — `examples/dsp-plugin` 需
   `local.properties` 的 `sdk.dir` 或 `ANDROID_HOME`。

---

## 6. 文档维护规则

- 五份文档（README.md / STATUS.md / GAP_ANALYSIS.md / PLUGIN_API.md /
  CLAUDE.md）必须与代码真实状态一致，禁止"敬请期待/尚未实现"式虚假宣传。
- 每轮代码改动后，先更新本文档，再同步其余文档。
- 文档中的相对路径必须真实存在（如 `examples/dsp-plugin/build/outputs/plugin_ringmod.apk`
  是构建产物，源工程路径 `examples/dsp-plugin/gradlew` 必须存在）。

---

## 7. Git 工作流提醒

- **push 前必须运行 Mimosa 安全审计**：调用 `mimosa:mimosa-security-scan`
  对仓库做深度扫描，确认无新增 secret / 高危漏洞后再提交。
- 提交信息用中文，简明描述改动（如 `docs: 同步插件系统真实状态`）。
- 不要修改 `examples/` 与 `maidmic-app/` 的源码来"配合文档"——
  文档只描述现状；发现不一致时改文档，源码问题另开任务。