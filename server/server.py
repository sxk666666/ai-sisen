"""
Go Analyzer Server - 电脑端中间层服务

两种模式：
1. USE_MOCK=True: 模拟分析（不需要 KataGo，用于开发测试）
2. USE_MOCK=False: 调用真实 KataGo Analysis Engine

使用方法：
1. 修改 config.py
2. start.bat 或 python server.py
3. Android 模拟器访问 http://10.0.2.2:8088
   真机访问 http://<电脑IP>:8088
"""

import asyncio
import json
import logging
import os
import random
import re
import sys
import socket
import string
import time
from datetime import datetime
from pathlib import Path
from typing import Optional

import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, UploadFile, File, Form
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from config import HOST, PORT, ANALYSIS_VISITS, MAX_VARIATIONS, USE_MOCK

# ============ 结构化日志 ============
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)-8s | %(name)s | %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("go-analyzer")
logger_katago = logging.getLogger("katago")
logger_api = logging.getLogger("api")

SERVER_START_TIME = time.time()

# ============ SGF 解析 ============

def parse_sgf(sgf_content: str, board_size_hint: int = 19):
    """解析 SGF 文件，返回 (走法列表, 棋盘大小, 贴目, 黑方名, 白方名)"""
    board_size = 19
    komi = 7.5
    black_name = ""
    white_name = ""

    for match in re.finditer(r'([A-Z]+)\[([^\]]*)\]', sgf_content):
        key = match.group(1)
        val = match.group(2)
        if not val:
            continue
        if key == "SZ":
            board_size = int(val) if val.isdigit() else 19
        elif key == "KM":
            try:
                komi = float(val)
                # 校验 komi 范围（KataGo 要求 -150.0 ~ 150.0，且必须是整数或半整数）
                # 有些 SGF 格式用整数表示（如 375 表示 3.75）
                if abs(komi) > 150.0:
                    komi = komi / 100.0
                # 确保是半整数（x.0 或 x.5）
                komi = round(komi * 2) / 2
            except (ValueError, TypeError):
                komi = 7.5
        elif key == "PB":
            black_name = val
        elif key == "PW":
            white_name = val

    # 提取 AB/AW 初始布局（让子棋）
    setup_moves = []
    for match in re.finditer(r'AB((?:\[[^\]]*\])+)', sgf_content):
        positions = re.findall(r'\[([^\]]*)\]', match.group(1))
        for pos in positions:
            if len(pos) >= 2:
                x = ord(pos[0].lower()) - ord('a')
                y = ord(pos[1].lower()) - ord('a')
                setup_moves.append({"color": "B", "x": x, "y": y, "turn": 0, "setup": True})

    for match in re.finditer(r'AW((?:\[[^\]]*\])+)', sgf_content):
        positions = re.findall(r'\[([^\]]*)\]', match.group(1))
        for pos in positions:
            if len(pos) >= 2:
                x = ord(pos[0].lower()) - ord('a')
                y = ord(pos[1].lower()) - ord('a')
                setup_moves.append({"color": "W", "x": x, "y": y, "turn": 0, "setup": True})

    moves = list(setup_moves)
    setup_count = len(setup_moves)

    # 提取正常走法（排除 AB[..]/AW[..] 中的坐标）
    # 先把 AB/AW 块替换为空，避免 B[..] 正则误匹配
    sgf_clean = re.sub(r'AB((?:\[[^\]]*\])+)', '', sgf_content)
    sgf_clean = re.sub(r'AW((?:\[[^\]]*\])+)', '', sgf_clean)

    turn = 0
    for match in re.finditer(r'([BW])\[([a-zA-Z]{0,2})\]', sgf_clean):
        color = match.group(1)
        pos = match.group(2)
        turn += 1
        if pos and not pos.lower() == "tt":
            x = ord(pos[0].lower()) - ord('a')
            y = ord(pos[1].lower()) - ord('a')
            moves.append({"color": color, "x": x, "y": y, "turn": turn})
        else:
            moves.append({"color": color, "x": -1, "y": -1, "turn": turn, "pass": True})

    return moves, board_size, komi, black_name, white_name


def coord_to_str(x: int, y: int, board_size: int = 19) -> str:
    """坐标转字母数字，如 (3,15) -> D16"""
    col = chr(ord('A') + x + (1 if x >= 8 else 0))
    return f"{col}{board_size - y}"


def random_move_str(moves_so_far: set, board_size: int = 19) -> str:
    """生成一个随机不重复的落子坐标字符串"""
    while True:
        x = random.randint(0, board_size - 1)
        y = random.randint(0, board_size - 1)
        coord = coord_to_str(x, y, board_size)
        if coord not in moves_so_far:
            return coord


def convert_pv_with_colors(pv: list, start_color: str, board_size: int = 19) -> list:
    """将 KataGo 的 ["D4", "Q16"] PV 转为 ["B[dd]", "W[qp]"] 带颜色前缀格式"""
    result = []
    color = start_color
    for move in pv:
        if not move or move.lower() == "pass":
            result.append(f"{color}[tt]")
        elif len(move) >= 2 and move[0].isalpha():
            sgf = coord_to_sgf(move[1:], board_size)
            result.append(f"{color}[{sgf}]")
        else:
            result.append(f"{color}[tt]")
        color = "W" if color == "B" else "B"
    return result


def coord_to_sgf(coord_str: str, board_size: int = 19) -> str:
    """将 D4 格式的坐标转为 SGF 的 aa 格式"""
    if not coord_str or len(coord_str) < 2:
        return "tt"
    col_char = coord_str[0]
    try:
        row_num = int(coord_str[1:])
    except ValueError:
        return "tt"
    sgf_col = chr(ord('a') + ord(col_char.upper()) - ord('A') - (1 if ord(col_char.upper()) > ord('H') else 0))
    sgf_row = chr(ord('a') + (board_size - row_num))
    return f"{sgf_col}{sgf_row}"


# ============ 模拟分析（不需要 KataGo） ============

def mock_analyze(sgf_content: str) -> dict:
    """生成模拟分析数据，用于开发和测试"""
    moves, board_size, komi, black_name, white_name = parse_sgf(sgf_content)

    if not moves:
        return {
            "boardSize": board_size,
            "rules": "chinese",
            "komi": komi,
            "moves": []
        }

    # 分离 setup stones 和正常走法
    setup_count = sum(1 for m in moves if m.get("setup"))
    normal_moves = [m for m in moves if not m.get("setup")]

    result_moves = []
    black_winrate = 0.50  # 起始胜率 50%

    # 收集已落子位置，避免推荐重复
    occupied = set()
    for m in moves:
        if not m.get("pass"):
            occupied.add(coord_to_str(m["x"], m["y"], board_size))

    for i, move in enumerate(normal_moves):
        is_black = move["color"] == "B"

        # 模拟胜率波动
        drift = random.gauss(0, 0.03)
        if is_black:
            black_winrate += drift
        else:
            black_winrate -= drift

        black_winrate = max(0.05, min(0.95, black_winrate))

        # 当前手坐标
        move_color = move["color"]
        move_str = ""
        if not move.get("pass"):
            move_str = coord_to_str(move["x"], move["y"], board_size)

        # 生成候选手（分析完当前手后轮到对面下）
        next_color = "W" if move_color == "B" else "B"
        candidate_coords = set(occupied)
        candidates = []
        # 第一个候选用真实落子
        if move_str:
            candidates.append({
                "move": move_str,
                "winrate": round(black_winrate, 4),
                "visits": ANALYSIS_VISITS,
                "pv": [move_str],
                "order": 0,
                "lead": round((black_winrate - 0.5) * 30, 2),
            })

        # 再生成几个随机候选
        for rank in range(1, min(5, 5)):
            cm = random_move_str(candidate_coords, board_size)
            candidate_coords.add(cm)
            c_wr = black_winrate + random.gauss(0, 0.02)
            c_wr = max(0.05, min(0.95, c_wr))
            candidates.append({
                "move": cm,
                "winrate": round(c_wr, 4),
                "visits": random.randint(10, ANALYSIS_VISITS // 2),
                "pv": [cm],
                "order": rank,
                "lead": round((c_wr - 0.5) * 30, 2),
            })

        # 按胜率排序候选
        if is_black:
            candidates.sort(key=lambda c: c["winrate"], reverse=True)
        else:
            candidates.sort(key=lambda c: c["winrate"])
        candidates = candidates[:5]
        # 重新编号
        for idx, c in enumerate(candidates):
            c["order"] = idx

        # 如果实际落子不在候选第一，标注为失误
        if move_str and len(candidates) > 0 and candidates[0]["move"] != move_str:
            blunder = True
        else:
            blunder = False

        best_pv = candidates[0]["pv"] if candidates else []
        # 给 PV 加几步后续
        for _ in range(random.randint(2, 6)):
            pm = random_move_str(candidate_coords, board_size)
            candidate_coords.add(pm)
            best_pv.append(pm)

        lead = (black_winrate - 0.5) * 30

        result_moves.append({
            "turnNumber": move.get("turn", i + 1),
            "move": move_str,
            "color": move_color,
            "winrate": round(black_winrate, 4),
            "lead": round(lead, 2),
            "scoreMean": round(lead + komi if is_black else -lead + komi, 2),
            "scoreStdev": round(random.uniform(10, 30), 2),
            "visits": ANALYSIS_VISITS,
            "pv": best_pv,
            "prior": round(random.uniform(0.01, 0.3), 4),
            "order": candidates[0]["order"] if candidates else 0,
            "moveInfos": candidates,
            "isBlunder": blunder,
        })

        # 占用落子位置
        if move_str and move_str not in occupied:
            occupied.add(move_str)

    return {
        "boardSize": board_size,
        "rules": "chinese",
        "komi": komi,
        "blackName": black_name,
        "whiteName": white_name,
        "totalMoves": len(moves),
        "moves": result_moves,
    }


# ============ KataGo 分析（真实模式） ============

import subprocess
import threading
import time
import queue

katago_process: Optional[subprocess.Popen] = None
katago_lock = threading.Lock()
katago_stderr_thread: Optional[threading.Thread] = None
katago_stdout_queue: Optional[queue.Queue] = None  # 后台线程持续读取 stdout 并放入队列

try:
    from config import KATAGO_EXE, KATA_MODEL, KATA_GTP_CONFIG
except ImportError:
    KATAGO_EXE = ""
    KATA_MODEL = ""
    KATA_GTP_CONFIG = ""


from contextlib import asynccontextmanager


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    print("=" * 55)
    print("  Go Analyzer Server")
    print("=" * 55)
    mode_str = "Mock (no KataGo needed)" if USE_MOCK else "KataGo Real Analysis"
    print(f"  URL:   http://{HOST}:{PORT}")
    print(f"  Mode:  {mode_str}")
    if not USE_MOCK:
        print(f"  Engine: {KATAGO_EXE}")
        print(f"  Model:  {KATA_MODEL}")
    print()
    print("  Android Emulator: http://10.0.2.2:8088")
    print("  Real Device:      http://<PC_IP>:8088")
    print("=" * 55)

    if not USE_MOCK:
        try:
            loop = asyncio.get_event_loop()
            await loop.run_in_executor(None, start_katago)
            logger.info("KataGo ready")
        except RuntimeError as e:
            logger.error(f"KataGo startup failed: {e}")
    yield
    # Shutdown
    if not USE_MOCK:
        await stop_katago()
    logger.info("Server stopped")


app = FastAPI(title="Go Analyzer Server", version="1.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


def start_katago():
    """启动 KataGo 进程并建立 stdout 后台读取线程"""
    global katago_process, katago_stderr_thread, katago_stdout_queue
    if not KATAGO_EXE or not KATA_MODEL:
        raise RuntimeError("Please configure KATAGO_EXE and KATA_MODEL in config.py")

    config_file = KATA_GTP_CONFIG or "default_gtp"
    cmd = [KATAGO_EXE, "analysis", "-config", config_file, "-model", KATA_MODEL]
    katago_process = subprocess.Popen(
        cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=False,
        bufsize=0,
    )
    logger_katago.info(f"Process started (PID={katago_process.pid})")

    # stdout 后台读取线程 → queue（消费者-生产者模式）
    katago_stdout_queue = queue.Queue()

    def read_stdout():
        try:
            while True:
                line = katago_process.stdout.readline()
                if not line:
                    katago_stdout_queue.put(None)  # EOF 信号
                    break
                decoded = line.decode("utf-8", errors="replace").strip()
                if decoded:
                    katago_stdout_queue.put(decoded)
        except Exception as e:
            katago_stdout_queue.put(None)
            logger_katago.error(f"stdout reader error: {e}")

    threading.Thread(target=read_stdout, daemon=True).start()

    # stderr 后台读取线程：监控就绪信号 + GPU autotuning
    katago_ready = threading.Event()

    def drain_stderr():
        try:
            while True:
                chunk = katago_process.stderr.read(4096)
                if not chunk:
                    break
                text = chunk.decode("utf-8", errors="replace").strip()
                if text:
                    logger_katago.info(f"stderr: {text[:300]}")
                    if "ready to begin handling requests" in text:
                        katago_ready.set()
        except Exception:
            pass

    katago_stderr_thread = threading.Thread(target=drain_stderr, daemon=True)
    katago_stderr_thread.start()

    # 等待 KataGo 就绪：最多 180s（首次启动 GPU autotuning 约 2 分钟）
    logger_katago.info("Waiting for KataGo to be ready (up to 180s, GPU autotuning may take ~2min)...")
    if not katago_ready.wait(timeout=180):
        if katago_process.poll() is not None:
            raise RuntimeError(f"KataGo crashed during startup (returncode={katago_process.returncode})")
        # 超时但进程还活着，继续（可能是 ready 信号被截断）
        logger_katago.warning("KataGo ready signal not detected, but process is alive. Continuing...")

    if katago_process.poll() is not None:
        raise RuntimeError(f"KataGo crashed during startup (returncode={katago_process.returncode})")

    logger_katago.info(f"KataGo ready (PID={katago_process.pid})")


async def stop_katago():
    global katago_process, katago_stdout_queue
    if katago_process:
        katago_process.terminate()
        try:
            katago_process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            katago_process.kill()
        katago_process = None
    katago_stdout_queue = None


def _collect_responses(query_id: str, expected_count: int, timeout: float = 120) -> dict:
    """从 stdout queue 中收集指定 query_id 的所有响应（同步，需在锁内调用）"""
    responses = {}
    deadline = time.time() + timeout

    while time.time() < deadline:
        if katago_process.poll() is not None:
            logger_katago.error("Process died while reading response")
            break

        remaining = deadline - time.time()
        if remaining <= 0:
            break

        try:
            line = katago_stdout_queue.get(timeout=min(remaining, 5))
        except queue.Empty:
            continue

        if line is None:  # EOF
            logger_katago.warning("stdout EOF")
            break

        try:
            data = json.loads(line)
        except json.JSONDecodeError as e:
            logger_katago.warning(f"JSON parse error: {e}, line: {line[:200]}")
            continue

        resp_id = data.get("id", "")
        if resp_id != query_id:
            continue

        if "error" in data:
            logger_katago.error(f"Error response: {data}")
            return {"_error": data["error"], "_field": data.get("field", "")}

        turn = data.get("turnNumber", -1)
        is_during = data.get("isDuringSearch", False)
        logger_katago.debug(f"Response: turn={turn}, isDuringSearch={is_during}")

        if not is_during:
            responses[turn] = data

        if len(responses) >= expected_count:
            logger_katago.info(f"All {expected_count} turns received")
            break

    if not responses:
        logger_katago.warning("No responses received within timeout")
    return responses


async def katago_analyze(sgf_content: str) -> dict:
    """调用真实 KataGo Analysis Engine（符合官方 Analysis Engine API）"""
    results = []
    async for update in katago_analyze_stream(sgf_content):
        if update.get("type") == "complete":
            return update.get("data", {})
        # progress / error updates are ignored in non-streaming mode
    return {}


async def katago_analyze_stream(sgf_content: str):
    """
    流式 KataGo 分析 — 每收到一手结果就 yield 一条进度消息，最后 yield complete。
    yield 格式：
      {"type": "progress", "current": N, "total": T, "data": <partial_result_move>}
      {"type": "complete", "data": <full_result>}
      {"type": "error",    "message": "..."}
    """
    global katago_process

    if katago_process is None or katago_process.poll() is not None:
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, start_katago)

    moves, board_size, komi, black_name, white_name = parse_sgf(sgf_content)
    normal_moves = [m for m in moves if not m.get("setup")]

    # ---- 构建 KataGo 标准请求 ----
    initial_stones = []
    for m in moves:
        if not m.get("setup"):
            continue
        if m.get("pass") or m["x"] < 0:
            continue
        gtp_coord = coord_to_str(m["x"], m["y"], board_size)
        initial_stones.append([m["color"].upper(), gtp_coord])

    katago_moves = []
    for m in normal_moves:
        color = m["color"].upper()
        if m.get("pass") or m["x"] < 0:
            katago_moves.append([color, "pass"])
        else:
            gtp_coord = coord_to_str(m["x"], m["y"], board_size)
            katago_moves.append([color, gtp_coord])

    # ---- 带重试的分析循环：自动去掉非法着手 ----
    removed_indices = set()
    max_retries = 10

    for attempt in range(max_retries + 1):
        total_turns = len(katago_moves)
        if total_turns == 0:
            break

        if total_turns > 100:
            step = total_turns // 100
            analyze_turns = list(range(1, total_turns + 1, step))
        else:
            analyze_turns = list(range(1, total_turns + 1))

        query_id = f"ga-{int(time.time() * 1000)}"

        request = {
            "id": query_id,
            "boardXSize": board_size,
            "boardYSize": board_size,
            "initialStones": initial_stones,
            "moves": katago_moves,
            "rules": "chinese",
            "komi": komi,
            "analyzeTurns": analyze_turns,
            "maxVisits": ANALYSIS_VISITS,
            "includePolicy": False,
            "includeOwnership": False,
        }

        logger_katago.info(f"Sending stream query {query_id}: boardSize={board_size}, moves={len(katago_moves)}, analyzeTurns={len(analyze_turns)}")

        loop = asyncio.get_event_loop()
        progress_queue = asyncio.Queue()

        def _send_and_stream():
            """在锁内发送请求，然后从 stdout queue 读取响应并逐条放入 progress_queue"""
            with katago_lock:
                line_bytes = (json.dumps(request) + "\n").encode("utf-8")
                katago_process.stdin.write(line_bytes)
                katago_process.stdin.flush()
                logger_katago.debug(f"Stream sent {len(line_bytes)} bytes")

            responses = {}
            deadline = time.time() + 300  # 5分钟超时

            while time.time() < deadline:
                if katago_process.poll() is not None:
                    loop.call_soon_threadsafe(progress_queue.put_nowait, {"_eof": True, "reason": "process_died"})
                    break

                remaining = deadline - time.time()
                try:
                    line = katago_stdout_queue.get(timeout=min(remaining, 5))
                except queue.Empty:
                    continue

                if line is None:
                    loop.call_soon_threadsafe(progress_queue.put_nowait, {"_eof": True, "reason": "stdout_eof"})
                    break

                try:
                    data = json.loads(line)
                except json.JSONDecodeError:
                    continue

                resp_id = data.get("id", "")
                if resp_id != query_id:
                    continue

                if "error" in data:
                    loop.call_soon_threadsafe(progress_queue.put_nowait, {"_error": data})
                    return

                turn = data.get("turnNumber", -1)
                is_during = data.get("isDuringSearch", False)

                if not is_during:
                    responses[turn] = data
                    # 每收到一手就立刻推送进度
                    loop.call_soon_threadsafe(progress_queue.put_nowait, {
                        "_progress": True,
                        "received": len(responses),
                        "total": len(analyze_turns),
                        "turn": turn,
                    })

                if len(responses) >= len(analyze_turns):
                    loop.call_soon_threadsafe(progress_queue.put_nowait, {"_done": True, "responses": responses})
                    return

            if time.time() >= deadline:
                loop.call_soon_threadsafe(progress_queue.put_nowait, {"_done": True, "responses": responses})

        thread = threading.Thread(target=_send_and_stream, daemon=True)
        thread.start()

        # 消费进度队列，逐步 yield
        responses = {}
        retry_needed = False
        while True:
            msg = await progress_queue.get()

            if "_progress" in msg:
                yield {
                    "type": "progress",
                    "current": msg["received"],
                    "total": len(analyze_turns),
                    "message": f"已分析 {msg['received']}/{len(analyze_turns)} 手..."
                }

            elif "_error" in msg:
                err_data = msg["_error"]
                error_msg = err_data.get("error", "")
                error_field = err_data.get("field", "")
                if error_field == "moves" and ("Illegal move" in error_msg or "illegal" in error_msg.lower()):
                    match = re.search(r'Illegal move (\d+)', error_msg)
                    if match:
                        bad_idx = int(match.group(1)) - 1
                        if 0 <= bad_idx < len(katago_moves):
                            logger_katago.warning(f"Stream: Illegal move at index {bad_idx} ({katago_moves[bad_idx]}), retrying")
                            katago_moves.pop(bad_idx)
                            removed_indices.add(bad_idx)
                            retry_needed = True
                            break
                yield {"type": "error", "message": f"KataGo error: {error_msg}"}
                return

            elif "_eof" in msg:
                responses = {}  # EOF, 退出
                break

            elif "_done" in msg:
                responses = msg["responses"]
                break

        if retry_needed:
            continue
        break

    # ---- 构建最终结果 ----
    result_moves = []
    kg_idx = 0

    for orig_idx, m in enumerate(normal_moves):
        if orig_idx in removed_indices:
            move_color = m.get("color", "B")
            if m.get("pass") or m["x"] < 0:
                shell_move = "pass"
            else:
                shell_move = coord_to_str(m["x"], m["y"], board_size)
            result_moves.append({
                "turnNumber": m.get("turn", orig_idx + 1),
                "move": shell_move,
                "color": move_color,
                "winrate": 0.5, "lead": 0.0, "scoreMean": 0.0, "scoreStdev": 0.0,
                "visits": 0, "pv": [], "prior": 0.0, "order": 0, "moveInfos": [],
                "illegal": True,
            })
            continue

        turn_num = kg_idx + 1
        kg_idx += 1
        resp = responses.get(turn_num)
        if resp is None:
            move_color = m.get("color", "B")
            shell_move = "pass" if (m.get("pass") or m["x"] < 0) else coord_to_str(m["x"], m["y"], board_size)
            result_moves.append({
                "turnNumber": m.get("turn", orig_idx + 1),
                "move": shell_move, "color": move_color,
                "winrate": 0.5, "lead": 0.0, "scoreMean": 0.0, "scoreStdev": 0.0,
                "visits": 0, "pv": [], "prior": 0.0, "order": 0, "moveInfos": [],
            })
            continue

        root = resp.get("rootInfo", {})
        move_infos = resp.get("moveInfos", [])
        root_wr = root.get("winrate", 0.5)
        black_winrate = root_wr
        score_lead = root.get("scoreLead", 0.0)
        visits = root.get("visits", 0)

        move_color = m.get("color", "B")
        if m.get("pass") or m["x"] < 0:
            actual_move = "pass"
        else:
            actual_move = coord_to_str(m["x"], m["y"], board_size)

        candidates = []
        actual_in_candidates = False
        actual_move_index = -1
        for ci, cm in enumerate(move_infos[:MAX_VARIATIONS]):
            cm_move = cm.get("move", "pass")
            # 规范化比较：转换为大写（KataGo 返回如 "D16"）
            if cm_move.upper() == actual_move.upper():
                actual_in_candidates = True
                actual_move_index = ci
            candidates.append({
                "move": cm_move,
                "winrate": round(cm.get("winrate", 0.5), 4),
                "visits": cm.get("visits", 0),
                "pv": cm.get("pv", []),
                "order": ci,
                "lead": round(cm.get("scoreLead", 0.0), 2),
            })

        # 如果实际落子不在 KataGo 候选项中，按胜率排序插入到合适位置
        # 这样可以正确计算胜率损失
        if not actual_in_candidates and actual_move != "pass":
            # 找到最佳候选项的胜率作为参考
            best_wr = candidates[0].get("winrate", 0.5) if candidates else 0.5
            # 实际着法的胜率设为略低于最佳（表示不是最优但也不太差）
            actual_wr = best_wr - 0.05  # 默认损失 5%
            actual_wr = max(0.01, min(0.99, actual_wr))  # 限制范围
            
            # 创建实际着法的候选项
            actual_candidate = {
                "move": actual_move,
                "winrate": round(actual_wr, 4),
                "visits": visits,
                "pv": [actual_move],
                "order": -1,  # 标记为实际着法
                "lead": round((actual_wr - 0.5) * 30, 2),
            }
            
            # 按胜率从高到低插入到合适位置
            insert_pos = 0
            for i, c in enumerate(candidates):
                if actual_wr >= c.get("winrate", 0):
                    insert_pos = i
                    break
                insert_pos = i + 1
            candidates.insert(insert_pos, actual_candidate)
        
        # 记录实际着法在候选项中的索引（用于客户端判断是否是最佳着法）
        is_best_move = actual_move_index == 0

        result_pv = move_infos[0].get("pv", []) if move_infos else []

        result_moves.append({
            "turnNumber": m.get("turn", orig_idx + 1),
            "move": actual_move, "color": move_color,
            "winrate": round(black_winrate, 4), "lead": round(score_lead, 2),
            "scoreMean": round(root.get("scoreSelfplay", score_lead), 2),
            "scoreStdev": round(root.get("scoreStdev", 0.0), 2),
            "visits": visits, "pv": result_pv,
            "prior": round(move_infos[0].get("prior", 0.0), 4) if move_infos else 0.0,
            "order": 0, "moveInfos": candidates,
            "isBestMove": is_best_move,  # 标记实际着法是否是 KataGo 首选
        })

    final_result = {
        "boardSize": board_size, "rules": "chinese", "komi": komi,
        "blackName": black_name, "whiteName": white_name,
        "totalMoves": len(normal_moves), "moves": result_moves,
    }
    yield {"type": "complete", "data": final_result}


# ============ 全局异常处理 ============

@app.exception_handler(Exception)
async def global_exception_handler(request, exc):
    import traceback
    logger.error(f"{request.method} {request.url}: {type(exc).__name__}: {exc}")
    traceback.print_exc()
    return JSONResponse(
        status_code=500,
        content={"error": f"{type(exc).__name__}: {str(exc)}"},
    )


# ============ HTTP API ============

@app.get("/")
async def root():
    katago_alive = katago_process is not None and katago_process.poll() is None
    return {
        "service": "Go Analyzer Server",
        "status": "running",
        "mode": "mock" if USE_MOCK else "katago",
        "katago_connected": katago_alive,
        "uptime_seconds": round(time.time() - SERVER_START_TIME, 1),
    }


@app.get("/health")
async def health():
    katago_alive = USE_MOCK or (katago_process is not None and katago_process.poll() is None)
    return {
        "status": "ok" if katago_alive else "degraded",
        "mode": "mock" if USE_MOCK else "katago",
        "katago_pid": katago_process.pid if (katago_process and katago_process.poll() is None) else None,
        "uptime_seconds": round(time.time() - SERVER_START_TIME, 1),
        "timestamp": datetime.now().isoformat(),
    }


@app.post("/api/analyze")
async def analyze_sgf(file: UploadFile = File(...)):
    """上传 SGF 文件进行分析"""
    content = await file.read()
    try:
        sgf_text = content.decode("utf-8")
    except UnicodeDecodeError:
        sgf_text = content.decode("gbk", errors="replace")

    logger_api.info(f"analyze (file={file.filename}, size={len(content)})")
    if USE_MOCK:
        result = mock_analyze(sgf_text)
    else:
        result = await katago_analyze(sgf_text)

    logger_api.info(f"analyze done: moves={len(result.get('moves', []))}")
    return JSONResponse(content=result)


@app.post("/api/analyze-text")
async def analyze_sgf_text(sgf_content: str = Form(...)):
    """发送 SGF 文本进行分析"""
    logger_api.info(f"analyze-text: SGF length={len(sgf_content)}")
    logger_api.debug(f"SGF content (first 200 chars): {sgf_content[:200]}")
    try:
        if USE_MOCK:
            result = mock_analyze(sgf_content)
        else:
            result = await katago_analyze(sgf_content)
        logger_api.info(f"analyze-text done: moves={len(result.get('moves', []))}")
        return JSONResponse(content=result)
    except Exception as e:
        import traceback
        logger_api.error(f"analyze-text FAILED: {type(e).__name__}: {e}")
        traceback.print_exc()
        raise


@app.get("/api/discover")
async def discover_server():
    hostname = socket.gethostname()
    try:
        local_ip = socket.gethostbyname(hostname)
    except Exception:
        local_ip = "127.0.0.1"
    return {"name": hostname, "ip": local_ip, "port": PORT, "version": "1.0.0"}


@app.get("/api/test-sgf")
async def test_sgf():
    """返回一个测试用的 SGF 棋谱"""
    # 简单合法的 20 手棋谱（对角星位开局，沿边扩展）
    test_sgf = "(;GM[1]FF[4]CA[UTF-8]SZ[19]KM[7.5]PB[TestB]PW[TestW];B[pd];W[dp];B[pp];W[dd];B[nc];W[nq];B[qo];W[oc];B[cp];W[fo];B[eq];W[ei];B[ke];W[ge];B[qk];W[cf];B[dm];W[do];B[mp];W[cq])"
    return {"sgf": test_sgf}


# ============ WebSocket ============

@app.websocket("/ws/analyze")
async def ws_analyze(websocket: WebSocket):
    await websocket.accept()
    try:
        while True:
            data = await websocket.receive_text()
            try:
                req = json.loads(data)
                sgf = req.get("sgf", "")
                if not sgf:
                    await websocket.send_json({"error": "empty sgf"})
                    continue

                await websocket.send_json({"status": "analyzing", "message": "正在解析棋谱..."})

                if USE_MOCK:
                    # 模拟模式：逐步推送进度
                    moves, board_size, komi, black_name, white_name = parse_sgf(sgf)
                    total_moves = len([m for m in moves if not m.get("setup")])

                    if total_moves == 0:
                        await websocket.send_json({
                            "status": "complete",
                            "data": {"boardSize": board_size, "rules": "chinese", "komi": komi, "moves": []}
                        })
                        continue

                    await websocket.send_json({
                        "status": "progress",
                        "current": 0,
                        "total": total_moves,
                        "message": f"共 {total_moves} 手，准备分析..."
                    })

                    result = mock_analyze(sgf)
                    analyzed_moves = result.get("moves", [])

                    # 推送关键节点进度
                    milestones = [0.25, 0.5, 0.75]
                    sent_milestones = set()
                    for i, _ in enumerate(analyzed_moves):
                        progress = (i + 1) / len(analyzed_moves)
                        milestone_check = round(progress, 2)
                        for ms in milestones:
                            if ms not in sent_milestones and progress >= ms:
                                sent_milestones.add(ms)
                                await websocket.send_json({
                                    "status": "progress",
                                    "current": i + 1,
                                    "total": len(analyzed_moves),
                                    "message": f"分析中... {i + 1}/{len(analyzed_moves)}"
                                })

                    # 统计失误数
                    blunder_count = sum(1 for m in analyzed_moves if m.get("isBlunder", False))

                    await websocket.send_json({
                        "status": "complete",
                        "data": result,
                        "summary": {
                            "totalMoves": len(analyzed_moves),
                            "blunderCount": blunder_count,
                            "blackName": black_name,
                            "whiteName": white_name
                        }
                    })
                else:
                    # KataGo 真实模式：使用流式分析推送真实进度
                    moves, board_size, komi, black_name, white_name = parse_sgf(sgf)
                    total_moves = len([m for m in moves if not m.get("setup")])

                    await websocket.send_json({
                        "status": "progress",
                        "current": 0,
                        "total": total_moves,
                        "message": f"共 {total_moves} 手，KataGo 分析中..."
                    })

                    result = None
                    async for update in katago_analyze_stream(sgf):
                        if update.get("type") == "progress":
                            await websocket.send_json({
                                "status": "progress",
                                "current": update.get("current", 0),
                                "total": update.get("total", total_moves),
                                "message": update.get("message", "分析中...")
                            })
                        elif update.get("type") == "complete":
                            result = update.get("data", {})
                        elif update.get("type") == "error":
                            raise RuntimeError(update.get("message", "KataGo error"))

                    if result is None:
                        raise RuntimeError("KataGo no response")

                    analyzed_moves = result.get("moves", [])
                    # 统计失误数（服务端简单统计）
                    blunder_count = 0
                    for i in range(1, len(analyzed_moves)):
                        m = analyzed_moves[i]
                        prev = analyzed_moves[i - 1]
                        if m.get("move", "") and prev.get("winrate", 0) > 0:
                            change = m.get("winrate", 0) - prev.get("winrate", 0)
                            if change < -0.02:
                                blunder_count += 1

                    await websocket.send_json({
                        "status": "complete",
                        "data": result,
                        "summary": {
                            "totalMoves": len(analyzed_moves),
                            "blunderCount": blunder_count,
                            "blackName": black_name,
                            "whiteName": white_name
                        }
                    })
            except Exception as e:
                await websocket.send_json({"status": "error", "message": str(e)})
    except WebSocketDisconnect:
        logger_api.info("WebSocket disconnected")


# ============ WebSocket ============


if __name__ == "__main__":
    uvicorn.run("server:app", host=HOST, port=PORT, log_level="info", reload=False)
