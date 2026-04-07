"""
野狐棋谱工具集 - GoAnalyzer 配套脚本
使用野狐公开 API，无需登录

API 端点:
- 棋谱数据: https://h5.foxwq.com/yehuDiamond/chessbook_local/YHWQFetchChess?chessid=<ID>
- 棋谱列表: https://h5.foxwq.com/yehuDiamond/chessbook_local/YHWQFetchChessList?dstuid=<UID>&type=1&lastcode=0
- 用户查询: https://newframe.foxwq.com/cgi/QueryUserInfoPanel?srcuid=0&username=<昵称>
"""

import urllib.request
import urllib.parse
import json
import sys
import os
import re
from pathlib import Path

BASE_DIR = Path(__file__).parent.parent / "data" / "foxwq"
BASE_DIR.mkdir(parents=True, exist_ok=True)


def api_get(url: str) -> dict:
    headers = {
        "User-Agent": "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Referer": "https://h5.foxwq.com/",
        "Accept": "application/json, text/plain, */*",
    }
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8", errors="ignore"))


def get_chess_sgf(chessid: str) -> dict:
    """从棋谱ID获取完整SGF数据"""
    url = f"https://h5.foxwq.com/yehuDiamond/chessbook_local/YHWQFetchChess?chessid={chessid}"
    return api_get(url)


def get_chess_list(uid: str, lastcode: int = 0) -> dict:
    """获取用户的棋谱列表"""
    url = f"https://h5.foxwq.com/yehuDiamond/chessbook_local/YHWQFetchChessList?dstuid={uid}&type=1&lastcode={lastcode}"
    return api_get(url)


def query_user(username: str) -> dict:
    """通过昵称查询用户UID"""
    encoded = urllib.parse.quote(username)
    url = f"https://newframe.foxwq.com/cgi/QueryUserInfoPanel?srcuid=0&username={encoded}"
    return api_get(url)


def save_sgf(sgf: str, chessid: str, output_dir: Path = None) -> Path:
    """保存SGF到文件"""
    if output_dir is None:
        output_dir = BASE_DIR / "games"
    output_dir.mkdir(parents=True, exist_ok=True)

    # 从SGF提取对局信息作为文件名
    black = re.search(r'PB\[(.*?)\]', sgf)
    white = re.search(r'PW\[(.*?)\]', sgf)
    result = re.search(r'RE\[(.*?)\]', sgf)
    date = re.search(r'DT\[(.*?)\]', sgf)

    name_parts = []
    if black: name_parts.append(black.group(1).replace("/", "-"))
    if white: name_parts.append(white.group(1).replace("/", "-"))
    if result: name_parts.append(result.group(1).replace(" ", "").replace("+", ""))

    if name_parts:
        filename = "_".join(name_parts) + f"_{chessid[-8:]}.sgf"
    else:
        filename = f"game_{chessid[-8:]}.sgf"

    filepath = output_dir / filename
    filepath.write_text(sgf, encoding="utf-8")
    return filepath


def print_game_info(game: dict):
    """打印对局简要信息"""
    winner_map = {"1": "黑", "2": "白", "0": "和", "": "未知"}
    winner = winner_map.get(str(game.get("winner", "")), "未知")
    print(f"  [{game['chessid']}] {game.get('blacknick','?')} vs {game.get('whitenick','?')}")
    print(f"    结果: {winner}胜  手数: {game.get('movenum','?')}  时间: {game.get('starttime','?')}")


if __name__ == "__main__":
    print(__doc__)
    print("\n用法:")
    print("  python foxwq_api.py <命令> [参数]")
    print()
    print("命令:")
    print("  share <chessid>              - 从棋谱ID下载SGF")
    print("  list <username>             - 查看用户棋谱列表")
    print("  list-uid <uid>              - 查看用户棋谱列表（通过UID）")
    print("  user <username>             - 查询用户信息")
    print()
    print("示例:")
    print("  python foxwq_api.py share 1774571026030011045")
    print("  python foxwq_api.py list-uid 533155414")
    print("  python foxwq_api.py user 柯洁")
