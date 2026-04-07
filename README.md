# Go Analyzer - 围棋 AI 分析 App

通过手机上传棋谱（SGF），调用电脑端 KataGo 进行 AI 分析。

## 架构

```
[Android App (Kotlin + Jetpack Compose)]
    ↕ HTTP REST API (局域网 / 内网穿透)
[电脑端 Python 服务 (FastAPI)]
    ↕ stdin/stdout JSON
[KataGo Analysis Engine]
```

## 快速开始

### 电脑端

1. **安装依赖**
   ```bash
   cd server
   pip install -r requirements.txt
   ```

2. **配置路径**
   ```bash
   python setup_wizard.py
   ```
   向导会自动搜索 KaTrain 安装目录，找到 KataGo 可执行文件和模型。
   也可手动编辑 `config.py`。

3. **启动服务**
   ```bash
   python server.py
   # 或者直接双击 start.bat
   ```
   服务启动后监听 `0.0.0.0:8088`

### Android 端

1. 用 Android Studio 打开 `android/` 目录
2. Build & Run 到手机
3. 进入设置页面，输入电脑 IP 地址
4. 选择 SGF 棋谱文件，点击分析

## 功能

- ✅ SGF 棋谱解析与显示
- ✅ AI 全局分析（胜率、推荐手、失误点）
- ✅ 胜率曲线图
- ✅ 单步分析详情（候选手、变化图）
- ✅ 棋盘上标记失误点
- ✅ 棋谱前后导航
- ✅ 内网穿透支持（Tailscale / frp / ngrok）

## API 接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/` | GET | 健康检查 |
| `/api/analyze` | POST (multipart) | 上传 SGF 文件分析 |
| `/api/analyze-text` | POST (form) | 发送 SGF 文本分析 |
| `/api/discover` | GET | 服务发现 |
| `/ws/analyze` | WebSocket | 实时分析进度 |

## 目录结构

```
go-analyzer/
├── server/                    # 电脑端 Python 服务
│   ├── server.py              # FastAPI 主服务
│   ├── config.py              # 配置文件
│   ├── setup_wizard.py        # 配置向导
│   ├── requirements.txt       # Python 依赖
│   └── start.bat              # Windows 启动脚本
│
└── android/                   # Android App
    ├── app/src/main/java/com/goanalyzer/
    │   ├── data/              # 数据层（API、SGF解析、模型）
    │   ├── ui/
    │   │   ├── board/         # 棋盘组件
    │   │   ├── chart/         # 胜率曲线
    │   │   ├── analysis/      # 分析详情面板
    │   │   ├── screen/        # 页面（主页面、设置）
    │   │   └── theme/         # 主题
    │   └── GoAnalyzerApp.kt   # Application
    └── build.gradle.kts
```
