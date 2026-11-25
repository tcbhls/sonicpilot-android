# 音枢 AI 音乐（SonicPilot Android）

> 一款基于 Kotlin + Jetpack Compose + Media3 构建的原生 Android 高音质 AI 音乐播放器、MV 影院、粤语/英文学歌室与实时声乐教练。

[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4)](https://developer.android.com/jetpack/compose)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

## 核心特性

### 1. 多源无损聚合播放 & 动态黑胶舞台
- **多音源热插拔聚合**：内置多组公开音频解析源，支持 `FLAC / 320K / 192K / 128K` 音质按需切换、播放前自动检测并在直链过期时无感重解析续播。
- **动态封面极光舞台**：实时提取专辑封面主色（`ArtworkColorExtractor`），渲染柔和极光背景与带旋转唱臂、唱片纹路、左右滑切歌的实体黑胶舞台。
- **Media3 后台与蓝牙/锁屏全控**：自定义 `ForwardingPlayer` 完整接管锁屏通知栏、耳机线控、车载蓝牙的上一首 / 下一首与进度拖拽。
- **高性能本地曲库**：SQLite 开启 **WAL 并发读写模式** 与四重索引，支持 3 并发高速无损批量下载、智能语种/流派双维度筛选与关键词秒搜。

### 2. 一键歌单迁移（QQ 音乐 / 网易云音乐）
- 粘贴 **QQ 音乐** 或 **网易云音乐** 歌单分享链接，内置 **4 路并发原唱优选引擎** 自动过滤翻唱/伴奏/DJ 版，匹配原唱并完整保留官方语种与流派标签。

### 3. 粤语逐字注音 · 英文双语 · 沉浸学歌室
- **100% 离线粤语注音引擎**：内置 21,574 字简繁粤拼词库，支持「**标准声调粤拼**（如 `pang4`）」与「**零门槛普通话谐音**」双模式一键切换，逐字精对齐。
- **英文歌双语自由切换**：支持「双语对照 / 仅英文原词 / 仅中文翻译」即时切换。
- **学歌室 & 实时声乐评测**：支持 A-B 单句循环、`0.5x–1.5x` 变速不变调，以及纯离线 **YIN DSP 音高检测** 实时跟唱打分与音准曲线分析。

### 4. 高清全屏 MV 影院
- 支持多渠道自动搜索与匹配官方高清 MV，单实例 `PlayerView` 零闪烁横屏全屏切换，清晰度（`1080P / 720P / 480P`）失败自动降级兜底。

### 5. 25 项 App 全控 AI 音乐管家（云端 + 本地离线双模）
- **开箱即用**：不填任何 API Key 也能直接使用内置 **极速端侧规则引擎**，支持听歌、切歌、策展、定时关机、播 MV、切粤拼/双语等日常指令。
- **支持自由接入任意云端大模型（OpenAI 兼容接口）**：在 App「设置 → AI 引擎」中即可一键切换并填入你自己的免费/个人 API Key：
  - 智谱清言（`GLM-4.7` / `GLM-4-Flash`，[官网注册即可领取免费额度](https://open.bigmodel.cn/)）
  - 硅基流动 SiliconFlow（`Qwen2.5-7B-Instruct` 等）
  - DeepSeek / OpenAI / Google Gemini / 任意自定义 OpenAI 兼容网关
- **真·离线端侧大模型（`llama.cpp`）**：支持直接下载或导入 `.gguf` 模型（如 `Qwen3-8B` / `Qwen3-4B` / `Qwen2.5-1.5B`）至手机 `Download/LocalAI` 共享目录，断网也能深度对话与控歌。

---

## 快速上手（普通用户）

1. 前往右侧 **[Releases](https://github.com/tcbhls/sonicpilot-android/releases/latest)** 下载最新版 `SonicPilot-v*-opensource-arm64.apk` 安装到 Android 8.0+ 手机。
2. 打开 App 即可直接搜歌、听歌、看 MV、体验粤语注音与极速端侧 AI。
3. **（可选）迁移歌单**：进入底部「曲库」→ 点右上角云朵图标，粘贴 QQ 音乐 / 网易云歌单链接一键导入。
4. **（可选）解锁最强云端 AI**：进入右上角「设置 → AI 引擎」，点击「前往官网获取免费 API Key」，填入后即可开启 25 项全权限自然语言策展与音乐赏析。

---

## 本地编译（开发者）

### 环境要求
- JDK 17 或 JDK 21
- Android SDK（`compileSdk = 36`, `minSdk = 26`）
- Gradle 8.14+

### 编译步骤
```bash
git clone https://github.com/tcbhls/sonicpilot-android.git
cd sonicpilot-android

# 1. 复制本地配置模板并填入你的 Android SDK 路径
cp local.properties.example local.properties

# 2. 编译开源版 Release APK（强制不包含任何本地密钥；或直接用 Android Studio 打开）
gradle clean testDebugUnitTest assembleRelease -POPEN_SOURCE_BUILD=true --no-daemon
```

Windows PowerShell 也可直接使用内置脚本：
```powershell
.\build_apk.ps1 -OpenSource
```

编译产物位于 `app/build/outputs/apk/release/app-release.apk`。

---

## 自定义音源渠道规范

支持在「设置 → 音乐渠道」通过 HTTP JSON 订阅链接或单渠道表单热插拔扩展音源（详见 [`sample_channels_subscription.json`](sample_channels_subscription.json)）：

```json
{
  "name": "社区扩展渠道示例订阅",
  "version": "1.0",
  "channels": [
    {
      "id": "meting_custom_example",
      "name": "自建 Meting 无损节点",
      "type": "METING",
      "baseUrl": "https://api.example.com/meting",
      "sourceKey": "netease",
      "description": "可替换为自建 Docker / Cloudflare Worker 地址"
    }
  ]
}
```

---

## 开源协议与免责声明

- 本项目基于 **[MIT License](LICENSE)** 开源，零遥测、零追踪、零内置私人密钥或个人数据。
- 本软件仅作为技术学习、本地音频管理与开放式接口聚合播放器框架使用，不存储、不分发任何受版权保护的音频或视频文件。所有第三方网络音源均由公开接口动态解析，请遵守当地法律法规并尊重音乐版权，支持正版音乐。
