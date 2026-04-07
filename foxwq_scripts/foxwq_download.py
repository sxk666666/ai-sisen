#!/usr/bin/env python3
"""
野狐棋谱下载 → 自动分析脚本
用法:
  python foxwq_download.py <棋手昵称> [--limit 10]
  python foxwq_download.py --chessid <chessid> [--analyze]
"""
import urllib.request
import urllib.parse
import json
import sys
import io
import re
from pathlib import Path
from datetime import datetime

# 修复 Windows 控制台
if sys.stdout.encoding != "utf-8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
if sys.stderr.encoding != "utf-8":
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

BASE_DIR = Path(__file__).parent.parent / "data" / "foxwq"
ANALYSIS_CACHE = BASE_DIR / "analysis_cache"


def api_get(url: str) -> dict:
    headers = {
        "User-Agent": "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Referer": "https://h5.foxwq.com/",
        "Accept": "application/json, text/plain, */*",
    }
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8", errors="ignore"))


def query_user(username: str) -> dict:
    encoded = urllib.parse.quote(username)
    url = f"https://newframe.foxwq.com/cgi/QueryUserInfoPanel?srcuid=0&username={encoded}"
    return api_get(url)


def get_chess_list(uid: str, lastcode: int = 0) -> dict:
    url = f"https://h5.foxwq.com/yehuDiamond/chessbook_local/YHWQFetchChessList?dstuid={uid}&type=1&lastcode={lastcode}"
    return api_get(url)


def get_chess_sgf(chessid: str) -> dict:
    url = f"https://h5.foxwq.com/yehuDiamond/chessbook_local/YHWQFetchChess?chessid={chessid}"
    return api_get(url)


def save_sgf(sgf: str, chessid: str) -> Path:
    output_dir = BASE_DIR / "games"
    output_dir.mkdir(parents=True, exist_ok=True)

    black = re.search(r'PB\[(.*?)\]', sgf)
    white = re.search(r'PW\[(.*?)\]', sgf)
    result = re.search(r'RE\[(.*?)\]', sgf)

    name_parts = []
    if black: name_parts.append(black.group(1).replace("/", "-"))
    if white: name_parts.append(white.group(1).replace("/", "-"))
    if result: name_parts.append(result.group(1).replace(" ", "").replace("+", ""))

    filename = "_".join(name_parts) + f"_{chessid[-8:]}.sgf" if name_parts else f"game_{chessid[-8:]}.sgf"
    filepath = output_dir / filename
    filepath.write_text(sgf, encoding="utf-8")
    return filepath


def send_to_analyzer(sgf_text: str) -> dict:
    """发送到本地 GoAnalyzer 服务端分析"""
    url = 'http://127.0.0.1:8088/api/analyze-text'
    data = urllib.parse.urlencode({'sgf_content': sgf_text}).encode()
    req = urllib.request.Request(url, data=data, headers={
        'User-Agent': 'Mozilla/5.0',
        'Accept': 'application/json',
    })
    with urllib.request.urlopen(req, timeout=600) as resp:
        return json.loads(resp.read().decode('utf-8'))


def main():
    args = sys.argv[1:]

    # 解析参数
    chessid = None
    username = None
    limit = 5
    do_analyze = True

    i = 0
    while i < len(args):
        if args[i] == "--chessid" and i + 1 < len(args):
            chessid = args[i + 1]
            i += 2
        elif args[i] == "--limit" and i + 1 < len(args):
            limit = int(args[i + 1])
            i += 2
        elif args[i] == "--no-analyze":
            do_analyze = False
            i += 1
        elif not args[i].startswith("-"):
            username = args[i]
            i += 1
        else:
            i += 1

    # 模式1: 直接从 chessid 下载
    if chessid:
        print(f"正在获取棋谱 {chessid} ...")
        data = get_chess_sgf(chessid)
        if data.get("result") != 0:
            print(f"获取失败: result={data.get('result')}")
            return 1

        sgf = data.get("chess", "")
        if not sgf:
            print("SGF 数据为空")
            return 1

        filepath = save_sgf(sgf, chessid)
        print(f"[OK] SGF 已保存: {filepath}")
        print(f"    对局: {re.search(r'PB\[(.*?)\]', sgf).group(1) if re.search(r'PB\[(.*?)\]', sgf) else '?'} "
              f"vs {re.search(r'PW\[(.*?)\]', sgf).group(1) if re.search(r'PW\[(.*?)\]', sgf) else '?'} "
              f"({re.search(r'RE\[(.*?)\]', sgf).group(1) if re.search(r'RE\[(.*?)\]', sgf) else '?'})")

        if do_analyze:
            print("正在发送到 KataGo 分析...")
            result = send_to_analyzer(sgf)
            moves = result.get('moves', [])
            print(f"[OK] 分析完成: {len(moves)} 手")
            # 保存分析结果
            cache_file = ANALYSIS_CACHE / f"{chessid}.json"
            ANALYSIS_CACHE.mkdir(parents=True, exist_ok=True)
            cache_file.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
            print(f"    分析缓存: {cache_file}")
        return 0

    # 模式2: 按用户名下载棋谱列表
    if username:
        print(f"正在查询用户 '{username}' ...")
        user_data = query_user(username)
        if user_data.get("result") != 0:
            print(f"用户查询失败: result={user_data.get('result')}")
            return 1

        uid = user_data.get("uid")
        print(f"[OK] UID={uid}  胜={user_data.get('totalwin',0)} 负={user_data.get('totallost',0)}")
        print(f"正在获取最近对局...")

        all_games = []
        lastcode = 0
        page = 1

        while page <= 5 and len(all_games) < limit * 2:
            data = get_chess_list(uid, lastcode)
            if data.get("result") != 0 or not data.get("chesslist"):
                break

            games = data["chesslist"]
            for g in games:
                all_games.append(g)
                if len(all_games) >= limit:
                    break
            lastcode = games[-1].get("chessid", 0)
            page += 1
            if len(games) < 10:
                break

        print(f"\n=== {username} 最近 {len(all_games)} 局 ===")
        for i, g in enumerate(all_games[:limit]):
            winner_map = {"1": "黑", "2": "白", "0": "和"}
            winner = winner_map.get(str(g.get("winner", "")), "?")
            print(f"  {i+1}. {g.get('blacknick','?')} vs {g.get('whitenick','?')} "
                  f"[{winner}胜, {g.get('movenum','?')}手, {g.get('starttime','?')}]")

        print(f"\n要下载哪几局？(输入编号，如 1,3,5 或 all 全部)")
        return 0

    print(__doc__)
    print("用法:")
    print("  python foxwq_download.py <棋手昵称> [--limit 10]")
    print("  python foxwq_download.py --chessid <ID>")
    print("  python foxwq_download.py --chessid <ID> --no-analyze  # 只下载不分析")
    return 0


if __name__ == "__main__":
    sys.exit(main())
