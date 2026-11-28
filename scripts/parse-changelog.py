#!/usr/bin/env python3
"""把 CHANGELOG.md 解析成结构化 JSON。

为什么需要这层：更新日记是**人工撰写**的面向用户说明，不是 git log 的机械罗列。
App 内关于页与 GitHub Release 页都从同一份 CHANGELOG.md 读取，
保证两处内容一致，且每条都带具体说明（而不是只有一个标题）。

用法：
  python3 scripts/parse-changelog.py                  # 输出全部版本
  python3 scripts/parse-changelog.py --latest         # 只输出最新一个版本
  python3 scripts/parse-changelog.py --markdown       # 输出 Release 用的 Markdown
  python3 scripts/parse-changelog.py --markdown-for v1.0.0   # 指定版本的 Markdown
  python3 scripts/parse-changelog.py --summary-for v1.0.0    # 指定版本的首条要点（APK 文件名用）

输出 JSON 结构（items 平铺，每条带 kind 分类）：
  {
    "version": 1,
    "entries": [
      {
        "tag": "v1.0.0",
        "date": "2026-11-21",
        "summary": "一句话概括",
        "items": [{"kind": "added", "title": "短语", "detail": "具体说明"}]
      }
    ]
  }

分类（kind）来自版本下的 ### 小标题：
  ### 新增      → added
  ### 优化      → improved
  ### 修复      → fixed
  ### 上游同步  → synced
  无小标题（v1.0.0~v1.0.5 的旧写法）→ 空串，表示未分类。

**旧版本必须继续能解析**：v1.0.5 及更早的 CHANGELOG 没有分类小标题，
解析结果的 kind 一律为空串，由 UI 决定如何呈现（当前是直接平铺，不加标签）。
这条兼容性是硬要求——已发布的包若因格式变更而显示异常，用户会以为坏了。
"""

import json
import re
import sys

VERSION_HEADING = re.compile(r"^##\s+(v[\d.]+(?:-[\w.]+)?)\s*[—–-]\s*(\d{4}-\d{2}-\d{2})\s*$")
SECTION_HEADING = re.compile(r"^###\s+(.+?)\s*$")
# 条目形如：「- **标题**：说明」或「- **标题**: 说明」
ITEM = re.compile(r"^-\s+\*\*(.+?)\*\*[：:]\s*(.+)$")
# 子条目：缩进的「  - 说明」，是上一条 item 的补充段落。
# 典型用法是把判据、实现要点这类细节挂在主条目下面，避免主条目过长。
SUBITEM = re.compile(r"^\s{2,}-\s+(.+)$")

# 版本下的小标题 → kind。
#
# 「优化」与「改进」是两类，不能合并：
#   优化（perf）= 同功能跑得更快/更准/更省，例如显示精度修正、请求链路提速
#   改进（polished）= 交互或呈现变好，功能范围不变，例如分组可折叠、布局重排
# 上游 Conventional Commits 只有 perf，没有对应「交互改善」的 type；
# 本项目面向终端用户，这个区分对读者有意义，所以单独列出。
#
# 中英都收：发布时可能有人用英文写。未收录的小标题不改变 kind（沿用上一个），
# 这样「### 细节」这类纯排版小标题不会把已分类的条目打回未分类。
SECTION_KINDS = {
    "新增": "added",
    "新功能": "added",
    "added": "added",
    "改进": "polished",
    "体验改进": "polished",
    "交互改进": "polished",
    "polished": "polished",
    "优化": "improved",
    "性能优化": "improved",
    "体验优化": "improved",
    "improved": "improved",
    "修复": "fixed",
    "问题修复": "fixed",
    "fixed": "fixed",
    "上游同步": "synced",
    "同步上游": "synced",
    "synced": "synced",
}


def parse(md: str):
    """解析 CHANGELOG.md。

    结构（参考上游 Nox 的写法，但每条带实质说明）：
        ## v1.0.0 — 2026-11-21
        <一句话概括>
        - **短语**：具体说明。
    """
    entries = []
    cur = None
    kind = ""  # 当前分类；### 小标题切换，未分类时为空串
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
            kind = ""
            continue

        if cur is None:
            continue

        # ### 小标题：识别为分类，不产生条目。
        # 旧版本（v1.0.5 及更早）没有分类小标题，kind 保持空串。
        msec = SECTION_HEADING.match(line)
        if msec:
            label = msec.group(1).strip()
            mapped = SECTION_KINDS.get(label) or SECTION_KINDS.get(label.lower(), "")
            if mapped:
                kind = mapped
            continue

        mi = ITEM.match(line)
        if mi:
            cur["items"].append({"kind": kind, "title": mi.group(1), "detail": mi.group(2)})
            continue

        # 子条目并入上一条 item 的 detail。
        # 不加这段判断时，子条目会被当成版本概述继续拼进 summary，
        # 结果是「一句话概括」里混进了实现细节，而 App 内只显示 summary
        # 与各条 title，用户看到的概述是一段读不通的长文本。
        ms = SUBITEM.match(line)
        if ms and cur["items"]:
            cur["items"][-1]["detail"] += "\n" + ms.group(1).strip()
            continue

        if line.strip() and not line.startswith("#"):
            if not cur["summary"]:
                cur["summary"] = line.strip()
            elif not cur["items"]:
                # 概述只取版本标题下的第一段；一旦开始出现条目，
                # 后续任何裸文本都不再算概述。
                cur["summary"] += " " + line.strip()

    return {"version": 1, "entries": entries}


KIND_LABELS = {
    "added": "新增",
    "polished": "改进",
    "improved": "优化",
    "fixed": "修复",
    "synced": "上游同步",
}


def to_markdown(entry) -> str:
    out = []
    if entry["summary"]:
        out.append(entry["summary"])
        out.append("")

    # 按 kind 重建小标题：CHANGELOG 里的 ### 分类在解析时变成了 kind 字段，
    # 不重建的话 Release 页/弹窗拿到的是一大块平铺列表，分类信息丢失。
    # 旧版本全部 kind 为空串，会落进同一个「无标签」组，输出与以前一致。
    #
    # 空行处理：summary 后面已经 append 过一个空行，这里只在已有内容后补一行，
    # 否则会出现两个连续空行（Markdown 里会被渲染成多余的垂直间隙）。
    current_kind = None
    for it in entry["items"]:
        k = it.get("kind") or ""
        if k != current_kind:
            if k:
                if out and out[-1] != "":
                    out.append("")
                out.append(f"### {KIND_LABELS.get(k, k)}")
                out.append("")
            current_kind = k
        lines = it["detail"].split("\n")
        out.append(f"- **{it['title']}**：{lines[0]}")
        for extra in lines[1:]:
            if extra.strip():
                out.append(f"  - {extra.strip()}")
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

    if "--summary-for" in args:
        # 取指定版本「最主要的更新」作为文件名片段。
        # 用首条 item 的标题（如「新增鲲六套图标」）而不是版本概述：
        # 概述是一整句话（"插件用起来更顺手，图标多了六种配色…"），
        # 落进文件名会被截断成半句话，反而看不清改了什么。
        idx = args.index("--summary-for")
        want = args[idx + 1] if len(args) > idx + 1 else ""
        entry = next((e for e in data["entries"] if e["tag"] == want), None)
        if entry and entry.get("items"):
            sys.stdout.write(entry["items"][0]["title"])
        return 0
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
