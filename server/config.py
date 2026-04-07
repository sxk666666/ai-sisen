"""
配置文件 - 根据你的环境修改
"""

# ============ KataGo 配置 ============

# 设置为 True 时使用模拟数据（不需要 KataGo 也能跑）
# 设置为 False 时需要正确配置下面的 KataGo 路径
USE_MOCK = False

# KataGo 可执行文件路径（KaTrain 自带 v1.16.0, OpenCL GPU 版本）
KATAGO_EXE = r"C:\Users\xk\.katrain\katago-v1.16.0-opencl-windows-x64.exe"

# KataGo 模型文件路径（KaTrain 自带 b28c512nbt）
KATA_MODEL = r"C:\Users\xk\.katrain\kata1-b28c512nbt-s12674021632-d5782420041.bin.gz"

# KataGo Analysis 配置文件路径
KATA_GTP_CONFIG = r"C:\Users\xk\.katrain\analysis_config.cfg"

# ============ 服务配置 ============

HOST = "0.0.0.0"       # 监听所有网卡，局域网可访问
PORT = 8088            # 服务端口

# ============ 分析参数 ============

ANALYSIS_VISITS = 100   # 每手分析搜索次数（GPU RTX 4060 可设 100-200，越高推荐越准）
MAX_VARIATIONS = 5      # 每手返回的变图数量
