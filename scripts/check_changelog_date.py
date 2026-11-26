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

修法只有一种：**新增版本小节，不改历史版本日期**。
已发布的版本（GitHub 上有同名 Release）改了日期就与线上页面不一致；
而门禁看到的是「最新版本早于最近提交」，硬把旧版本日期改成今天同样不对。

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


def self_check() -> int:
    """确认提示文案没退回误导句式。

    2026-11-27 的教训：旧提示是「请把 v1.0.4 的日期改为 2026-11-27 或 2026-11-26」，
    两个选项里 11-26 正是当时的错误值。这类「二选一」句式会把用户往错误方向带，
    因为它默认了「改这个版本的日期」是对的，而正确做法是新增版本。
    """
    # 只检查 print 出来的提示语，不检查注释与文档字符串——
    # 注释里需要引用旧文案来说明教训，一起拦会误伤自己。
    out_lines = [
        ln for ln in open(__file__, encoding="utf-8").read().splitlines()
        if ln.lstrip().startswith("print(")
    ]
    text = "\n".join(out_lines)
    bad = []
    if "的日期改为" in text:
        bad.append("出现「的日期改为」，这是旧版的误导句式")
    if "新增一个版本小节" not in text:
        bad.append("缺少「新增一个版本小节」的正确指引")
    if bad:
        print("[FAIL] check_changelog_date.py 自身自检失败：")
        for b in bad:
            print(f"       {b}")
        return 1
    return 0


def main() -> int:
    quiet = "--quiet" in sys.argv
    if self_check() != 0:
        return 1
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
        # 这里以前写的是「请把 vX 的日期改为今天或昨天」，害过一次：
        # v1.0.4 已在 11-26 发布上线，合并上游后提交日期变成 11-27，
        # 照着提示改 v1.0.4 的日期会与 GitHub 上已发布的 Release 对不上，
        # 而提示里「或 11-26」正是当时的错误值，照着改等于没改。
        # 正确做法是新增一个版本小节，历史版本一律不动。
        print()
        print("       处理方式：新增一个版本小节，不要改历史版本的日期。")
        print(f"          - {version} 若已发布（GitHub 上有同名 Release），日期不能动，")
        print("            否则仓库与已发布页面对不上。")
        print(f"          - 在 {version} 之前插入新的 `## vX.Y.(Z+1) — {today}` 小节，")
        print("            把本次改动写进去，同步跑 python3 scripts/gen-readme-highlights.py")
        return 1

    if not quiet:
        print(f"[PASS] 更新日记 {version} 日期 {ver_date}（今天 {today}，最近提交 {commit_date}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
