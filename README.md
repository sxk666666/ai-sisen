# AI.SISEN - 围棋 AI 分析应用

基于 KataGo 引擎的围棋棋谱分析工具，支持 Android 客户端。

## 功能特点

- 📋 **棋谱导入**：支持本地 SGF 文件导入、野狐棋院账号导入
- 🤖 **AI 分析**：KataGo 引擎（GPU 加速）胜率分析、最优着法推荐
- 📊 **胜率曲线**：贝塞尔平滑曲线，带移动平均线和失误标记
- 🎯 **复盘功能**：自动标注失误/严重失误，展示 AI 推荐手
- 🧩 **死活题**：自动从棋谱中提取死活题（仅失误/严重失误）
- 🎨 **精美棋盘**：3D 棋子渐变、木纹棋盘、玻璃态 PV 预览

## 架构

```
Android 客户端 (Kotlin/Jetpack Compose)
    ↕ HTTP
Python FastAPI 服务端
    ↕ stdin/stdout JSON
KataGo 分析引擎 (OpenCL GPU)
```

## 快速开始

### 服务端

```bash
cd server
pip install -r requirements.txt
python server.py
```

默认端口：**8088**

### Android 客户端

用 Android Studio 打开 `android/` 目录，构建并运行。

需要在 App 设置页填写服务端地址（如 `http://192.168.x.x:8088`）。

## 环境要求

- Python 3.10+
- KataGo 1.16+ (OpenCL 或 CUDA 版本推荐)
- Android 8.0+ (API 26+)

## License

MIT
