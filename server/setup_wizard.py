"""
Go Analyzer Server 启动辅助脚本
自动查找 KaTrain 安装目录中的 KataGo 路径
"""

import os
import sys
from pathlib import Path


def find_katrain_paths():
    """
    自动搜索 KaTrain 的安装路径，找到 KataGo 可执行文件和模型
    """
    possible_paths = []

    # Windows 常见安装位置
    if sys.platform == "win32":
        drives = ["C:", "D:", "E:", "F:"]
        for drive in drives:
            possible_paths.extend([
                Path(f"{drive}/Program Files/KaTrain"),
                Path(f"{drive}/Program Files (x86)/KaTrain"),
                Path(f"{drive}/KaTrain"),
                Path(f"{drive}/Tools/KaTrain"),
                Path(f"{drive}/Users/{os.getenv('USERNAME')}/Desktop/KaTrain"),
                Path(f"{drive}/Users/{os.getenv('USERNAME')}/Downloads/KaTrain"),
                Path(f"{drive}/Users/{os.getenv('USERNAME')}/AppData/Local/KaTrain"),
            ])
    else:
        # macOS / Linux
        possible_paths.extend([
            Path("/Applications/KaTrain"),
            Path("/opt/KaTrain"),
            Path(os.path.expanduser("~/KaTrain")),
            Path(os.path.expanduser("~/Desktop/KaTrain")),
            Path(os.path.expanduser("~/Downloads/KaTrain")),
        ])

    found = None
    for path in possible_paths:
        if path.exists():
            found = path
            print(f"[✓] 找到 KaTrain 目录: {path}")
            break

    if found is None:
        print("[!] 未自动找到 KaTrain 目录")
        print("[!] 请手动输入 KaTrain 安装路径：")
        custom_path = input("> ").strip()
        if custom_path:
            found = Path(custom_path)
            if not found.exists():
                print(f"[✗] 路径不存在: {found}")
                return None, None, None
        else:
            return None, None, None

    # 搜索 katago 可执行文件
    katago_exe = None
    for name in ["katago.exe", "katago", "KaTrain.exe", "KaTrain"]:
        exe_path = found / name
        if exe_path.exists():
            katago_exe = str(exe_path)
            break

    if katago_exe is None:
        # 搜索子目录
        for sub in ["engine", "katago", "models"]:
            for name in ["katago.exe", "katago"]:
                exe_path = found / sub / name
                if exe_path.exists():
                    katago_exe = str(exe_path)
                    break
            if katago_exe:
                break

    # 搜索模型文件
    model_file = None
    model_dirs = ["models", "networks", "model", "network"]
    for model_dir in model_dirs:
        model_path = found / model_dir
        if model_path.exists():
            for f in model_path.iterdir():
                if f.suffix in [".gz", ".bin"] or f.name.endswith(".bin.gz"):
                    model_file = str(f)
                    print(f"[✓] 找到模型: {f.name}")
                    break
            if model_file:
                break

    if model_file is None:
        # 在根目录搜索
        for f in found.rglob("*.bin.gz"):
            model_file = str(f)
            print(f"[✓] 找到模型: {f.name}")
            break

    # 搜索 GTP 配置文件
    gtp_config = None
    for name in ["gtp.cfg", "default_gtp.cfg", "analysis.cfg", "default_analysis.cfg"]:
        cfg_path = found / name
        if cfg_path.exists():
            gtp_config = str(cfg_path)
            break

    if gtp_config is None:
        for f in found.rglob("*.cfg"):
            if "gtp" in f.name.lower() or "analysis" in f.name.lower():
                gtp_config = str(f)
                break

    if katago_exe:
        print(f"[✓] KataGo: {katago_exe}")
    else:
        print("[!] 未找到 KataGo 可执行文件")

    if gtp_config:
        print(f"[✓] 配置: {gtp_config}")
    else:
        print("[!] 未找到配置文件（将使用默认值）")

    return katago_exe, model_file, gtp_config


def update_config(katago_exe, model_file, gtp_config):
    """更新 config.py"""
    config_path = Path(__file__).parent / "config.py"

    content = f'''"""
配置文件 - 根据你的环境修改
"""

# KataGo 可执行文件路径（KaTrain 自带的 KataGo）
KATAGO_EXE = r"{katago_exe or 'katago'}"

# KataGo 模型文件路径（.bin.gz 或 .bin）
KATA_MODEL = r"{model_file or ''}"

# KataGo GTP 配置文件路径
KATA_GTP_CONFIG = r"{gtp_config or ''}"

# 服务监听配置
HOST = "0.0.0.0"       # 监听所有网卡，局域网可访问
PORT = 8088            # 服务端口

# 分析参数
ANALYSIS_VISITS = 100   # 每手分析搜索次数（越高越准，但越慢）
MAX_VARIATIONS = 5      # 每手返回的变图数量
'''

    config_path.write_text(content, encoding="utf-8")
    print(f"[✓] 配置已保存到: {config_path}")


if __name__ == "__main__":
    print("=" * 50)
    print("  Go Analyzer Server - 首次配置向导")
    print("=" * 50)
    print()

    exe, model, config = find_katrain_paths()

    if exe and model:
        update_config(exe, model, config)
        print()
        print("=" * 50)
        print("  配置完成！运行以下命令启动服务：")
        print("  python server.py")
        print("=" * 50)
    else:
        print()
        print("[!] 自动配置未完成，请手动编辑 config.py")
        print("[!] 需要填写:")
        print("    - KATAGO_EXE: KataGo 可执行文件路径")
        print("    - KATA_MODEL: 神经网络模型路径")
        print("    - KATA_GTP_CONFIG: 配置文件路径（可选）")
