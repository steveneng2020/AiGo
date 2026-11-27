#!/usr/bin/env python3
"""出包前置检查：确认 CHANGELOG 已就绪，再允许构建。

为什么放在出包前而不是出包后：
  更新日记是「构建时打进 APK」的（generateChangelog 任务把 CHANGELOG.md
  解析成 assets/changelog.json）。所以顺序必须是：

      改 CHANGELOG.md  →  出包（读进去）  →  装机验收

  2026-11-27 做成了「出包 → 装机发现文案不对 → 才改 CHANGELOG」，
  包里的日记因此是旧的，关于页显示的是上一版描述。事后补改文件没用，
  必须重新出包才会生效。这道检查就是为了在构建开始前拦住这种顺序错误。

检查项
------
1. CHANGELOG 存在，且最新版本日期是今天或昨天（跨零点提交是正常的）。
2. 最新版本至少有一条 `- **标题**：说明` 格式的条目（App 端靠这个正则加粗）。
3. 若工作区里 CHANGELOG.md 有未提交改动，提示「确认这是你要打进包的版本」。
   —— 允许提交，只是提醒：很多人改完忘了它还没进 commit，
      出包时读到的是工作区版本，容易和「仓库里的内容」混淆。

用法
----
    python3 scripts/check_changelog_ready.py          # 检查
    python3 scripts/check_changelog_ready.py --quiet  # 只在失败时输出

退出码：0 可出包；1 需先修 CHANGELOG。
"""
import re
import subprocess
import sys
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHANGELOG = ROOT / "CHANGELOG.md"

HEADER_RE = re.compile(r"^## (v[\d.]+) — (\d{4})-(\d{2})-(\d{2})\s*$", re.MULTILINE)
# App 端 UpdateCheckService.parseReleaseNotes 用的就是这个形状；
# 写成缩进子条目不匹配，关于页那一条会退化成纯文本（不加粗）。
ITEM_RE = re.compile(r"^- \*\*(.+?)\*\*\s*[：:]\s*(.+)$", re.MULTILINE)


def main() -> int:
    quiet = "--quiet" in sys.argv
    problems = []

    if not CHANGELOG.exists():
        print("[FAIL] 找不到 CHANGELOG.md，关于页的更新日记会是空的")
        return 1

    text = CHANGELOG.read_text(encoding="utf-8")
    headers = list(HEADER_RE.finditer(text))
    if not headers:
        print("[FAIL] CHANGELOG.md 里没有 `## vX.Y.Z — YYYY-MM-DD` 格式的版本标题")
        return 1

    latest = headers[0]
    version = latest.group(1)
    ver_date = date(int(latest.group(2)), int(latest.group(3)), int(latest.group(4)))
    today = date.today()
    yesterday = today - timedelta(days=1)

    if ver_date > today:
        problems.append(f"{version} 的日期 {ver_date} 晚于今天 {today}（未来日期一定是错的）")
    if ver_date < yesterday:
        problems.append(
            f"{version} 的日期 {ver_date} 早于昨天 {yesterday}："
            f"这版多半已经在打包含内，本次改动要么补进这一版，要么新增一个版本小节"
        )

    # 该版本的条目
    start = latest.end()
    nxt = HEADER_RE.search(text, start)
    body = text[start: nxt.start() if nxt else len(text)]
    items = ITEM_RE.findall(body)
    if not items:
        problems.append(
            f"{version} 没有任何 `- **标题**：说明` 格式的条目。"
            f"App 端按这个正则加粗显示，缩进子条目或纯段落都会被当成普通文字"
        )

    # 未提交改动：只提醒，不拦。
    # 出包读的是工作区文件，未提交的改动也会被打进去；
    # 提醒是为了避免「以为打的是仓库版本、实际是本地草稿」。
    dirty = False
    try:
        out = subprocess.run(
            ["git", "status", "--porcelain", "CHANGELOG.md"],
            cwd=str(ROOT), capture_output=True, text=True, timeout=10,
        )
        dirty = bool(out.stdout.strip())
    except Exception:
        pass

    if problems:
        print(f"[FAIL] 出包前检查未通过（当前版本 {version}）：")
        for p in problems:
            print(f"       {p}")
        print()
        print("  更新日记是在构建时打进 APK 的，所以要先改好再出包。")
        print("  改完再跑一次本脚本，通过后重新构建。")
        return 1

    if not quiet:
        print(f"[PASS] CHANGELOG 就绪：{version}（{ver_date}），{len(items)} 条要点")
        if dirty:
            print("       ⚠ CHANGELOG.md 有未提交改动——出包读的是工作区版本，")
            print("         确认这就是你要打进包里的内容。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
