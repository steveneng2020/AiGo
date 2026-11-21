#!/usr/bin/env python3
"""把 CHANGELOG.md 解析成结构化 JSON。

为什么需要这层：更新日记是**人工撰写**的面向用户说明，不是 git log 的机械罗列。
App 内关于页与 GitHub Release 页都从同一份 CHANGELOG.md 读取，
保证两处内容一致，且每条都带具体说明（而不是只有一个标题）。

用法：
  python3 scripts/parse-changelog.py                 # 输出全部版本
  python3 scripts/parse-changelog.py --latest        # 只输出最新一个版本
  python3 scripts/parse-changelog.py --markdown      # 输出 Release 用的 Markdown

输出 JSON 结构：
  {
    "version": 1,
    "entries": [
      {
        "tag": "v1.0.0",
        "date": "2026-11-21",
        "summary": "一句话概括",
        "sections": [
          { "title": "新增", "items": [{"title": "...", "detail": "..."}] }
        ]
      }
    ]
  }
"""

import json
import re
import sys

VERSION_HEADING = re.compile(r"^##\s+(v[\d.]+(?:-[\w.]+)?)\s*[—–-]\s*(\d{4}-\d{2}-\d{2})\s*$")
SECTION_HEADING = re.compile(r"^###\s+(.+?)\s*$")
# 条目形如：「- **标题**：说明」或「- **标题**: 说明」
ITEM = re.compile(r"^-\s+\*\*(.+?)\*\*[：:]\s*(.+)$")


def parse(md: str):
    """解析 CHANGELOG.md。

    结构（参考上游 Nox 的写法，但每条带实质说明）：
        ## v1.0.0 — 2026-11-21
        <一句话概括>
        - **短语**：具体说明。
    """
    entries = []
    cur = None
    in_comment = False
    in_fence = False

    for raw in md.splitlines():
        line = raw.rstrip()

        if "<!--" in line:
            in_comment = True
        if in_comment:
            if "-->" in line:
                in_comment = False
            continue

        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue

        m = VERSION_HEADING.match(line)
        if m:
            cur = {"tag": m.group(1), "date": m.group(2), "summary": "", "items": []}
            entries.append(cur)
            continue

        if cur is None:
            continue

        # 忽略 ### 小标题（旧格式兼容），条目一律平铺
        if SECTION_HEADING.match(line):
            continue

        mi = ITEM.match(line)
        if mi:
            cur["items"].append({"title": mi.group(1), "detail": mi.group(2)})
            continue

        if line.strip() and not line.startswith("#"):
            if not cur["summary"]:
                cur["summary"] = line.strip()
            else:
                cur["summary"] += " " + line.strip()

    return {"version": 1, "entries": entries}


def to_markdown(entry) -> str:
    out = []
    if entry["summary"]:
        out.append(entry["summary"])
        out.append("")
    for it in entry["items"]:
        out.append(f"- **{it['title']}**：{it['detail']}")
    return "\n".join(out).rstrip() + "\n"


def main() -> int:
    args = sys.argv[1:]
    try:
        md = open("CHANGELOG.md", encoding="utf-8").read()
    except FileNotFoundError:
        sys.stderr.write("CHANGELOG.md 不存在\n")
        return 1

    data = parse(md)
    if not data["entries"]:
        sys.stderr.write("CHANGELOG.md 里没有解析到任何版本（检查标题格式 ## vX.Y.Z — YYYY-MM-DD）\n")
        return 1

    if "--markdown-for" in args:
        # 取指定 tag 的版本段落（Release 页用）
        idx = args.index("--markdown-for")
        want = args[idx + 1] if len(args) > idx + 1 else ""
        entry = next((e for e in data["entries"] if e["tag"] == want), None)
        if entry is None:
            sys.exit(2)
        sys.stdout.write(to_markdown(entry))
    elif "--markdown" in args:
        entry = data["entries"][0]
        sys.stdout.write(to_markdown(entry))
    elif "--latest" in args:
        json.dump({"version": 1, "entries": data["entries"][:1]},
                  sys.stdout, ensure_ascii=False, indent=2)
    else:
        json.dump(data, sys.stdout, ensure_ascii=False, indent=2)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
