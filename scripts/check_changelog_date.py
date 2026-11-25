#!/usr/bin/env python3
"""校验 CHANGELOG 最新版本条目的日期是否真的对应当前工作。

为什么需要这个检查：更新日记的日期是手写的，极易忘改。实测出现过错版：
代码已改到 11-26、发版也在 11-26，但关于页里显示的仍是「2026.11.25」，
用户看到的是过期日期，还以为自己装的是旧版。

判定规则
--------
1. 必须存在 `## vX.Y.Z — YYYY-MM-DD` 格式的最新版本标题。
2. 最新版本的日期不得晚于今天（未来日期一定是错的）。
3. 最新版本的日期不得早于最近一次提交的日期：
   若代码已经改过、提交过了，日记日期还停在更早的某天，就是漏更新。
4. 允许日期等于今天或昨天：跨零点提交是正常情况。

用法:
    python3 scripts/check_changelog_date.py
    python3 scripts/check_changelog_date.py --quiet
"""
import re
import subprocess
import sys
from datetime import date, datetime, timedelta

CHANGELOG = "CHANGELOG.md"
HEADER_RE = re.compile(r"^## (v[\d.]+) — (\d{4})-(\d{2})-(\d{2})\s*$", re.MULTILINE)


def latest_version_header(text: str):
    """返回 (版本号, 日期对象, 匹配片段)；找不到返回 None。"""
    m = HEADER_RE.search(text)
    if not m:
        return None
    version, y, mo, d = m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4))
    try:
        return version, date(y, mo, d), m.group(0)
    except ValueError:
        return None


def last_commit_date():
    """最近一次提交的本地日期；仓库不可用时返回 None。"""
    try:
        out = subprocess.run(
            ["git", "log", "-1", "--format=%cd", "--date=format:%Y-%m-%d"],
            capture_output=True, text=True, timeout=10, check=False,
        )
        value = out.stdout.strip()
        if not value:
            return None
        return date.fromisoformat(value)
    except Exception:
        return None


def main() -> int:
    quiet = "--quiet" in sys.argv
    try:
        text = open(CHANGELOG, encoding="utf-8").read()
    except FileNotFoundError:
        print(f"[FAIL] 找不到 {CHANGELOG}")
        return 1

    header = latest_version_header(text)
    if header is None:
        print(f"[FAIL] {CHANGELOG} 里没有 `## vX.Y.Z — YYYY-MM-DD` 格式的版本标题")
        return 1

    version, ver_date, raw = header
    today = date.today()
    yesterday = today - timedelta(days=1)

    problems = []

    # 1) 未来日期
    if ver_date > today:
        problems.append(
            f"版本日期 {ver_date} 晚于今天 {today}（未来日期一定是错的）"
        )

    # 2) 早于最近提交
    commit_date = last_commit_date()
    if commit_date is not None and ver_date < commit_date:
        problems.append(
            f"版本日期 {ver_date} 早于最近一次提交 {commit_date}"
            f"（代码已改，日记日期没跟着更新）"
        )

    if problems:
        print(f"[FAIL] 更新日记日期不对：{raw}")
        for p in problems:
            print(f"       {p}")
        print(f"       请把 {version} 的日期改为 {today} 或 {yesterday}")
        return 1

    if not quiet:
        print(f"[PASS] 更新日记 {version} 日期 {ver_date}（今天 {today}，最近提交 {commit_date}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
