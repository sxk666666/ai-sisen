#!/usr/bin/env python3
"""
野狐棋谱工具 CLI 入口
"""
import sys
import os
import io
from pathlib import Path

# 修复 Windows 控制台 UTF-8 输出
if sys.stdout.encoding != "utf-8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
if sys.stderr.encoding != "utf-8":
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

# 添加父目录到路径
sys.path.insert(0, str(Path(__file__).parent))
from foxwq_api import get_chess_sgf, get_chess_list, query_user, save_sgf, print_game_info, BASE_DIR


def cmd_share(args):
    chessid = args[0] if args else None
    if not chessid:
        print("错误: 需要提供 chessid")
        return 1

    print(f"正在获取棋谱 {chessid} ...")
    data = get_chess_sgf(chessid)

    if data.get("result") != 0:
        print(f"获取失败: result={data.get('result')}")
        return 1

    sgf = data.get("chess", "")
    if not sgf:
        print("错误: SGF 数据为空")
        return 1

    filepath = save_sgf(sgf, chessid)
    print(f"✅ SGF 已保存: {filepath}")
    print(f"\n文件预览 (前500字符):\n{sgf[:500]}")
    return 0


def cmd_list(args):
    if not args:
        print("错误: 需要提供用户名")
        return 1

    username = args[0]
    uid = args[1] if len(args) > 1 else None

    if not uid:
        print(f"正在查询用户 '{username}' 的UID...")
        user_data = query_user(username)
        if user_data.get("result") != 0:
            print(f"用户查询失败: result={user_data.get('result')}")
            return 1
        uid = user_data.get("uid")
        dan = user_data.get("dan", "?")
        total = (user_data.get("totalwin", 0) or 0) + (user_data.get("totallost", 0) or 0)
        print(f"✅ 找到用户: {user_data.get('username','?')} (UID={uid}, 段位={dan}, 总对局={total})")
        print()

    if not uid:
        print("错误: 无法获取 UID")
        return 1

    print(f"正在获取用户 {uid} 的棋谱列表...")
    all_games = []
    lastcode = 0
    page = 1

    while page <= 5:  # 最多5页
        data = get_chess_list(uid, lastcode)
        if data.get("result") != 0:
            print(f"获取失败: result={data.get('result')}")
            break

        games = data.get("chesslist", [])
        if not games:
            break

        print(f"\n=== 第 {page} 页 ({len(games)} 局) ===")
        for i, g in enumerate(games):
            print_game_info(g)

        all_games.extend(games)
        lastcode = games[-1].get("chessid", 0)
        page += 1

    print(f"\n共获取 {len(all_games)} 局对局")
    return 0


def cmd_user(args):
    if not args:
        print("错误: 需要提供用户名")
        return 1

    username = args[0]
    print(f"正在查询用户 '{username}' ...")
    data = query_user(username)

    if data.get("result") != 0:
        print(f"查询失败: result={data.get('result')}")
        return 1

    print(f"\n=== 用户信息 ===")
    print(f"  昵称: {data.get('username', '?')}")
    print(f"  UID:  {data.get('uid', '?')}")
    dan = data.get("dan", 0)
    if dan:
        dan_str = ["", "业余初段", "业余二段", "业余三段", "业余四段", "业余五段",
                   "业余六段", "业余七段", "业余八段", "业余九段"]
        if 1 <= dan <= 9:
            dan_str = dan_str[dan]
        elif dan == 24:
            dan_str = "段位 24+ (高段)"
        elif dan == 25:
            dan_str = "段位 25+ (职业?)"
        else:
            dan_str = f"段位{dan}"
        print(f"  段位: {dan_str}")
    print(f"  胜:   {data.get('totalwin', 0)}")
    print(f"  负:   {data.get('totallost', 0)}")
    return 0


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else None
    args = sys.argv[2:]

    if not cmd or cmd in ("-h", "--help"):
        print(__import__("foxwq_api").__doc__)
        return 0

    commands = {
        "share": cmd_share,
        "list": cmd_list,
        "list-uid": cmd_list,
        "user": cmd_user,
    }

    fn = commands.get(cmd)
    if not fn:
        print(f"未知命令: {cmd}")
        print("可用命令: " + ", ".join(commands.keys()))
        return 1

    return fn(args)


if __name__ == "__main__":
    sys.exit(main())
