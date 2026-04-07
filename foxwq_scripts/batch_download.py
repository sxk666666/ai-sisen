#!/usr/bin/env python3
"""批量下载并分析指定棋手的最近对局"""
import urllib.request
import urllib.parse
import json
import sys
import io
import re
from pathlib import Path

if sys.stdout.encoding != "utf-8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
if sys.stderr.encoding != "utf-8":
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

BASE_DIR = Path(r"D:\go-analyzer\data\foxwq")
ANALYSIS_CACHE = BASE_DIR / "analysis_cache"
ANALYSIS_CACHE.mkdir(parents=True, exist_ok=True)


def api_get(url):
    headers = {
        "User-Agent": "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Referer": "https://h5.foxwq.com/",
        "Accept": "application/json, text/plain, */*",
    }
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8", errors="ignore"))


def save_sgf(sgf, chessid):
    games_dir = BASE_DIR / "games"
    games_dir.mkdir(parents=True, exist_ok=True)
    black = re.search(r"PB\[(.*?)\]", sgf)
    white = re.search(r"PW\[(.*?)\]", sgf)
    result = re.search(r"RE\[(.*?)\]", sgf)
    name_parts = []
    if black: name_parts.append(black.group(1).replace("/", "-"))
    if white: name_parts.append(white.group(1).replace("/", "-"))
    if result: name_parts.append(result.group(1).replace(" ", "").replace("+", ""))
    filename = "_".join(name_parts) + f"_{chessid[-8:]}.sgf" if name_parts else f"game_{chessid[-8:]}.sgf"
    filepath = games_dir / filename
    filepath.write_text(sgf, encoding="utf-8")
    return filepath


def analyze_sgf(sgf_text):
    url = "http://127.0.0.1:8088/api/analyze-text"
    data = urllib.parse.urlencode({"sgf_content": sgf_text}).encode()
    req = urllib.request.Request(url, data=data, headers={"User-Agent": "Mozilla/5.0", "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as resp:
        return json.loads(resp.read().decode("utf-8"))


def get_games(uid, count=3):
    all_games = []
    lastcode = 0
    page = 1
    while page <= 5 and len(all_games) < count:
        data = api_get(f"https://h5.foxwq.com/yehuDiamond/chessbook_local/YHWQFetchChessList?dstuid={uid}&type=1&lastcode={lastcode}")
        if data.get("result") != 0 or not data.get("chesslist"):
            break
        games = data["chesslist"]
        all_games.extend(games)
        lastcode = games[-1].get("chessid", 0)
        page += 1
        if len(games) < 10:
            break
    return all_games[:count]


def main():
    # 从命令行参数获取棋手昵称
    username = sys.argv[1] if len(sys.argv) > 1 else None
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 3

    if not username:
        print("用法: python batch_download.py <棋手昵称> [下载数量]")
        return 1

    # 查询用户 UID
    print(f"查询用户 '{username}' ...")
    encoded = urllib.parse.quote(username)
    user_data = api_get(f"https://newframe.foxwq.com/cgi/QueryUserInfoPanel?srcuid=0&username={encoded}")
    if user_data.get("result") != 0:
        print(f"用户查询失败: result={user_data.get('result')}")
        return 1
    uid = user_data["uid"]
    total = (user_data.get("totalwin", 0) or 0) + (user_data.get("totallost", 0) or 0)
    print(f"[OK] UID={uid}  总对局={total}  胜={user_data.get('totalwin',0)} 负={user_data.get('totallost',0)}")

    # 获取棋谱列表
    print(f"\n获取最近 {limit} 局...")
    games = get_games(uid, limit)
    winner_map = {"1": "黑", "2": "白", "0": "和"}

    print(f"\n=== {username} 最近 {len(games)} 局 ===")
    for i, g in enumerate(games):
        w = winner_map.get(str(g.get("winner", "")), "?")
        print(f"  {i+1}. {g['blacknick']} vs {g['whitenick']} [{w}胜, {g['movenum']}手, {g['starttime']}]")

    # 下载并分析
    print()
    for i, g in enumerate(games):
        chessid = g["chessid"]
        print(f"--- [{i+1}/{len(games)}] {g['blacknick']} vs {g['whitenick']} ---")

        # 下载 SGF
        data = api_get(f"https://h5.foxwq.com/yehuDiamond/chessbook_local/YHWQFetchChess?chessid={chessid}")
        if data.get("result") != 0:
            print(f"  [FAIL] 获取失败")
            continue
        sgf = data.get("chess", "")
        if not sgf:
            print(f"  [FAIL] SGF为空")
            continue

        # 保存
        sf = save_sgf(sgf, chessid)
        print(f"  [OK] SGF已保存: {sf.name}")

        # 分析
        print(f"  KataGo分析中 ({len(sgf)}字符 SGF)...")
        try:
            result = analyze_sgf(sgf)
            moves = result.get("moves", [])
            # 保存分析缓存
            cache_file = ANALYSIS_CACHE / f"{chessid}.json"
            cache_file.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
            print(f"  [OK] 分析完成: {len(moves)}手")
            # 打印简要统计
            blunder_count = sum(1 for m in moves if m.get("moveInfos") and any(
                info.get("order", 0) > 0 and m.get("winrate", 0) < 0.5 and info.get("winrate", 1) > 0.7
                for info in m["moveInfos"]
            ))
            print(f"       胜率范围: {min((m['winrate'] for m in moves), default=0):.1%} - {max((m['winrate'] for m in moves), default=0):.1%}")
            print(f"       缓存: {cache_file.name}")
        except Exception as e:
            print(f"  [FAIL] 分析失败: {type(e).__name__}: {e}")

    print(f"\n完成！SGF存于: {BASE_DIR / 'games'}")
    print(f"分析缓存: {ANALYSIS_CACHE}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
