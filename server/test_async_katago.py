"""测试 queue-based stdout 读取 + FastAPI run_in_executor 是否正常工作"""
import subprocess
import threading
import time
import queue
import json
import asyncio

KATAGO_EXE = r"C:\Users\xk\.katrain\katago-v1.16.0-eigenavx2-windows-x64.exe"
KATA_MODEL = r"C:\Users\xk\.katrain\kata1-b28c512nbt-s12674021632-d5782420041.bin.gz"
KATA_CONFIG = r"C:\Users\xk\.katrain\analysis_config.cfg"

print("[1] Starting KataGo...")
proc = subprocess.Popen(
    [KATAGO_EXE, "analysis", "-config", KATA_CONFIG, "-model", KATA_MODEL],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    text=False, bufsize=0,
)

stdout_queue = queue.Queue()

def read_stdout():
    while True:
        line = proc.stdout.readline()
        if not line:
            stdout_queue.put(None)
            break
        decoded = line.decode("utf-8", errors="replace").strip()
        if decoded:
            stdout_queue.put(decoded)

threading.Thread(target=read_stdout, daemon=True).start()

ready = threading.Event()

def drain_stderr():
    while True:
        chunk = proc.stderr.read(4096)
        if not chunk:
            break
        t = chunk.decode("utf-8", errors="replace")
        if "ready" in t:
            ready.set()

threading.Thread(target=drain_stderr, daemon=True).start()

print("[2] Waiting for ready...")
ready.wait(timeout=30)
print("[3] Ready!")

# Now test with asyncio run_in_executor (same pattern as FastAPI)
async def test_async():
    print("[4] In async function, calling run_in_executor...")
    
    query = json.dumps({
        "id": "test-async",
        "boardXSize": 9,
        "boardYSize": 9,
        "moves": [["B", "E5"], ["W", "C6"]],
        "rules": "chinese",
        "komi": 7.5,
        "analyzeTurns": [0, 1, 2],
        "maxVisits": 10,
    })
    
    def do_query():
        proc.stdin.write((query + "\n").encode())
        proc.stdin.flush()
        print(f"[5] Sent {len(query)} bytes")
        
        responses = {}
        deadline = time.time() + 30
        while time.time() < deadline:
            try:
                line = stdout_queue.get(timeout=3)
            except queue.Empty:
                print(f"[wait] queue empty, {deadline - time.time():.1f}s remaining")
                continue
            if line is None:
                print("[EOF]")
                break
            try:
                data = json.loads(line)
            except json.JSONDecodeError:
                continue
            if data.get("id") == "test-async" and not data.get("isDuringSearch"):
                turn = data.get("turnNumber")
                wr = data.get("rootInfo", {}).get("winrate")
                print(f"  [got] turn={turn} wr={wr:.3f}")
                responses[turn] = data
            if len(responses) >= 3:
                break
        return responses
    
    loop = asyncio.get_event_loop()
    result = await loop.run_in_executor(None, do_query)
    print(f"[6] Done! Got {len(result)} responses")
    return result

result = asyncio.run(test_async())
proc.terminate()
print(f"[DONE] {result is not None and len(result) == 3}")
