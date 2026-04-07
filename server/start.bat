@echo off
chcp 65001 >nul
echo ==========================================
echo   Go Analyzer Server 启动
echo ==========================================
echo.

REM 检查 Python
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未找到 Python，请先安装 Python 3.9+
    pause
    exit /b 1
)

REM 首次运行 - 检查是否已配置
if not exist "config.py.bak" (
    echo [首次运行] 启动配置向导...
    echo.
    python setup_wizard.py
    echo.
)

REM 检查依赖
echo [检查] Python 依赖...
pip install -q -r requirements.txt 2>nul

echo.
echo [启动] Go Analyzer Server...
echo [提示] 确保手机和电脑在同一局域网
echo.
python server.py
pause
