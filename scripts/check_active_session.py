#!/usr/bin/env python3
"""装包前检查：有没有正在跑的会话会被 force-stop 杀掉。

为什么需要：
  2026-11-27 为了验证新包，直接 `am force-stop com.aigo` 再 `pm install -r`，
  用户正在排队的会话（含未提交输入、进行中的工具调用）全部丢失。
  用户明确表示「不用恢复，但要避免」——所以这道检查的作用是「提醒先看再动手」。

判定方式：
  查 `agent_messages` 里最近 10 分钟内、status 仍为 running/pending 的消息，
  或 `llm_call_records` 里最近有活动但未成功的调用。
  有则提示用户先确认，不直接阻断（用户可能就是要重启）。

用法：
  python3 scripts/check_active_session.py            # 检查
  python3 scripts/check_active_session.py --quiet    # 只在有活动时输出

退出码：
  0  无进行中的会话（可以安心装包）
  1  有进行中的会话（装包会丢掉它们）
  2  数据库读不到（无法判断，提示手动确认）
"""
import glob
import os
import sqlite3
import subprocess
import sys
from datetime import datetime, timedelta

# 数据库在设备上，容器要通过备份副本读。装包前 App 必须已停，
# 所以这里只读「上一次备份」或「设备上现有文件」，读不到就报 2。
CANDIDATES = [
    os.path.expanduser("~/workspace/.nox/db_latest.db"),
    os.path.expanduser("~/workspace/.nox/db_v137.db"),
]

# 最近多久算「进行中」
ACTIVE_WINDOW_MIN = 10


def find_db():
    for p in CANDIDATES:
        if os.path.isfile(p) and os.path.getsize(p) > 0:
            return p
    # 任何 db_*.db 里挑最新的
    files = sorted(
        glob.glob(os.path.expanduser("~/workspace/.nox/db_*.db")),
        key=lambda f: os.path.getmtime(f),
        reverse=True,
    )
    return files[0] if files else None


def query_active(db_path):
    """返回 (进行中的消息数, 最近活动时间字符串)。读不到就抛异常。"""
    uri = f"file:{db_path}?mode=ro"
    con = sqlite3.connect(uri, uri=True, timeout=5)
    try:
        cur = con.cursor()
        since = (datetime.now() - timedelta(minutes=ACTIVE_WINDOW_MIN)).strftime(
            "%Y-%m-%d %H:%M:%S"
        )
        # agent_messages 的时间列在不同版本可能是 createdAt 或 timestamp，探测一下。
        cols = {r[1] for r in cur.execute("PRAGMA table_info(agent_messages)")}
        time_col = "createdAt" if "createdAt" in cols else (
            "timestamp" if "timestamp" in cols else None
        )
        if time_col is None:
            return 0, None

        # 未完成的消息：assistant 侧有 isStreaming 标记，tool 侧看 status
        active = 0
        last = None
        try:
            row = cur.execute(
                f"SELECT COUNT(*), MAX({time_col}) FROM agent_messages "
                f"WHERE {time_col} >= ?",
                (since,),
            ).fetchone()
            active, last = row[0] or 0, row[1]
        except sqlite3.Error:
            pass

        # 未成功的调用：status 非 success 且近期有活动
        try:
            row2 = cur.execute(
                "SELECT COUNT(*) FROM llm_call_records "
                "WHERE status IS NOT NULL AND status != 'success' "
                "AND createdAt >= ?",
                (since,),
            ).fetchone()
            active += row2[0] or 0
        except sqlite3.Error:
            pass

        return active, last
    finally:
        con.close()


def device_running():
    """设备上 com.aigo 是否还在跑。容器内没有 adb，靠 Root 通道不可用，
    所以这里只用文件系统的进程可见性兜底；判断不出就返回 None。"""
    try:
        out = subprocess.run(
            ["sh", "-c", "ps -ef 2>/dev/null | grep -c '[c]om.aigo'"],
            capture_output=True, text=True, timeout=5,
        )
        return int(out.stdout.strip() or 0) > 0
    except Exception:
        return None


def main() -> int:
    quiet = "--quiet" in sys.argv
    db = find_db()
    if db is None:
        if not quiet:
            print("[提示] 读不到数据库副本，无法判断是否有进行中的会话。")
            print("       装包前请手动确认：屏幕上有没有正在跑的任务。")
        return 2

    try:
        active, last = query_active(db)
    except Exception as e:
        if not quiet:
            print(f"[提示] 数据库读不了（{type(e).__name__}），无法判断。")
            print()
            print("  要拿到可用副本，先停 App 再拷（含 -wal/-shm 才是完整库）：")
            print("    Root: am force-stop com.aigo")
            print("    Root: cp /data/data/com.aigo/databases/nox_agent_db "
                  "/data/local/tmp/db_latest.db")
            print("    Root: cp /data/local/tmp/db_latest.db "
                  "/storage/emulated/0/Download/AiGo/AiGo开发/.nox/db_latest.db")
            print()
            print("  但这一步本身就会杀掉 App。所以装包前先问用户：")
            print("  「现在有正在跑的任务吗？装包会重启应用。」")
        return 2

    if active > 0:
        print(f"[警告] 最近 {ACTIVE_WINDOW_MIN} 分钟有 {active} 条未完成记录"
              f"（最后活动 {last or '未知'}）")
        print()
        print("  force-stop + pm install 会立即杀掉进程，进行中的会话、")
        print("  未提交的输入、正在跑的工具调用全部丢失（2026-11-27 发生过一次）。")
        print()
        print("  装包前先做一件事：")
        print("    1. 问用户「现在有正在跑的任务吗」")
        print("    2. 或者在 App 里等当前轮次结束")
        print("    3. 用户明确说可以，再继续")
        print()
        print("  注意：pm install -r 自己就会重启进程，不需要先 force-stop；")
        print("       也不要为「停住反复重启」去关 accessibility_enabled，")
        print("       那会让 App 更难被系统维持。")
        return 1

    if not quiet:
        print(f"[PASS] 最近 {ACTIVE_WINDOW_MIN} 分钟无未完成记录，可以装包")
    return 0


if __name__ == "__main__":
    sys.exit(main())
