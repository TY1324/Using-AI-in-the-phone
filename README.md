# Using-AI-in-the-phone

An app to use AI easily in Android.

## 项目简介

本项目是一个运行在 Android 手机上的 AI 聊天客户端，支持 DeepSeek、Claude、Gemini、OpenAI 等多种模型接口，可在手机本地完成对话、文件解析、图片识别、联网搜索、MCP 工具调用、长文档分片等功能。

适合在华为/HarmonyOS 及其他 Android 设备上使用，无需 PC 即可随时调用云端大模型。

## 编译要求

- Android Studio 或命令行 Gradle
- JDK 17+
- Android SDK 24+ (minSdk) / 34+ (targetSdk)
- 网络连接（用于下载 Gradle 发行版与依赖）

## 构建步骤

```bash
# 克隆仓库
git clone https://github.com/TY1324/Using-AI-in-the-phone.git
cd Using-AI-in-the-phone

# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK（需先配置签名）
./gradlew assembleRelease
```

构建产物位于 `app/build/outputs/apk/`。

## 使用说明

1. 安装 APK 到手机后打开应用。
2. 在「我的」页配置 API Key、模型 Host、模型 ID（支持 OpenAI / ANTHROPIC / GEMINI / DeepSeek 等兼容接口）。
3. 在「对话」页发送消息，支持附件图片、文本文件（TXT/MD/JSON/HTML/CSV/PDF/DOCX/XLSX/PPTX）。
4. 「工具」页可开启联网搜索、视觉理解、MCP 服务器、技能 Skill、子代理等功能。
5. 「备份 / 恢复」可导出全部对话数据为 JSON/Zip。

## 主要功能

- 多模型切换与预设管理
- 流式输出与 Token 用量统计
- 长文档自动分片与相关片段检索
- 图片压缩后 Base64 传入视觉模型
- 加密保存多套 API Key
- 后台保活服务（可选）
- 导出对话为 Markdown/TXT/HTML/JSON

## 注意事项

- 首次构建需联网下载 Gradle 发行版与 Android 插件，建议在网络良好环境下操作。
- 华为手机构建时如遇到 GMS 相关问题，可检查 `build.gradle` 中的 `compileSdk` 与 `targetSdk` 版本。
- API Key 建议仅在个人设备上使用，勿上传至公共仓库。

## 许可证

MIT License
