# MaidMic 🎤✨

**虚拟麦克风 · 变声 · 插件生态 · 开源**

> "数字时代声卡，女仆般温柔驾驭你的声音"

MaidMic 是一个开源的 Android 虚拟麦克风应用，支持三路实现（root AudioFlinger / Shizuku AAudio / 无障碍转发），
搭载自研 Echio 变声引擎，用户可按 DAG 或线性排列方式任意组合 DSP 模块、调整任何参数无上限。
支持 Lua 插件系统，插件权限分级管理。

---

## 架构概览

```
┌─────────────────────────────────────────────────┐
│                  maidmic-app                     │
│  (Kotlin/Jetpack Compose)                       │
│  ┌──────────┐ ┌──────────┐ ┌─────────────────┐  │
│  │  UI 层    │ │ 服务层    │ │ 插件管理器       │  │
│  │ - 主界面   │ │ - A: AF  │ │ - Lua 沙箱      │  │
│  │ - 模块链   │ │ - B: AA  │ │ - 市场          │  │
│  │   编辑器   │ │ - C: 无   │ │ - 权限系统      │  │
│  │ - 设置     │ │   障碍    │ │                 │  │
│  └──────────┘ └──────────┘ └─────────────────┘  │
├─────────── JNI 桥 ───────────────────────────────┤
│  maidmic-engine  (C/C++)                        │
│  ┌──────────────────────────────────────────────┐│
│  │  Echio 引擎核心                               ││
│  │  RingBuffer → Pipeline → DSP 链              ││
│  └──────────────────────────────────────────────┘│
└──────────────────────────────────────────────────┘
```

## 子项目

| 目录 | 语言 | 说明 |
|------|------|------|
| `maidmic-app` | Kotlin | Android App (Jetpack Compose UI) |
| `maidmic-engine` | C/C++ | Echio 变声引擎（RingBuffer, Pipeline, DSP 模块） |
| `maidmic-lua` | Lua | 插件系统运行时 |
| `maidmic-p2p` | Go | 插件市场 GitHub 索引同步（P2P/DHT 已移除） |

## 构建

CI 自动构建 APK，推送到 `main` 分支即可触发。
可在 [Actions](https://github.com/suer781/MaidMic/actions) 页面下载 `.apk` 文件。

## 许可

AGPL-3.0
