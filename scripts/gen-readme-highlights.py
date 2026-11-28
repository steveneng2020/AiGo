#!/usr/bin/env python3
"""从 CHANGELOG.md 生成 README 的「新增与改进」章节。

为什么要有这层：
  README 里那段「新增与改进」原来靠手写维护，而 CHANGELOG 是发版必写的。
  两处各自维护必然漂移——2026-11-26 发现 README 只列了 5 条，
  而 CHANGELOG 里 v1.0.2~v1.0.4 实际有 23 条，整块「上下文用量面板」都没提。

做法：
  用 `<!-- 新增与改进:START -->` 与 `<!-- 新增与改进:END -->` 夹住那段，
  脚本只替换两个标记之间的内容，其余部分（简介、功能特性、快速开始…）不动。
  这样 README 的其它章节仍可手工精修，只有「最近做了什么」部分是自动的。

用法：
  python3 scripts/gen-readme-highlights.py            # 直接改写 README.md
  python3 scripts/gen-readme-highlights.py --check    # 只检查是否最新，不改（门禁用）
  python3 scripts/gen-readme-highlights.py --dry-run  # 打印生成结果

推荐接入点（已加进 aigo-gate）：
  发版前跑 --check，不一致就拦。这样 README 永远不会落后于 CHANGELOG。
"""

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHANGELOG = ROOT / "CHANGELOG.md"
README = ROOT / "README.md"
PARSER = ROOT / "scripts" / "parse-changelog.py"

START = "<!-- 新增与改进:START -->"
END = "<!-- 新增与改进:END -->"

# 取几个版本：README 要回答「这个项目做过什么」，所以覆盖全部版本。
# v1.0.0 是最早的正式版，里面全是本项目的核心原创功能（运行中加附件、
# 插件分组、通知栏图标跟随…），用「最近 N 版」窗口会把这些直接丢掉。
RECENT_VERSIONS = 999

# 每个版本最多列几条：不再截断，全列。
#
# 早先设成 3，理由是「怕 README 撑成发布日志」。2026-11-28 发现这个取舍错了：
# v1.0.6 在 CHANGELOG 里有 8 条，README 只显示前 3 条 + 同步上游 = 4 条，
# 于是 GitHub 首页的版本说明与 App 里「关于页」的更新日记对不上。
# 用户从 GitHub 看是「这版只改了 4 件事」，装完 App 看到 8 件——
# 同一份 CHANGELOG 的两处呈现不一致，比 README 长一点糟得多。
#
# 何况 MAX_LEN=100 已经限制了单条长度，一版 8 条也只有 8 行。
# 完整列表才符合「新增与改进」这个标题的承诺。
MAX_ITEMS_PER_VERSION = None

# 一个版本至少要有几条重点才值得写进 README。
# 只有 1~2 条的版本通常是补丁版，列出来反而显得项目没什么进展。
MIN_ITEMS_PER_VERSION = 2

# 每条截断长度：README 是概览，细节在 CHANGELOG 与 App 里的更新日记。
MAX_LEN = 100

# 主题去重：同一件事在多版里反复出现时只保留最新那版。
# 典型例子是图标——v1.0.1 写「新增鲲六套图标」，v1.0.4 又写「默认图标改为金黄鲲」，
# 两条并列读者会以为是两件事。按标题里的关键词识别主题，新版本优先。
# 注意粒度：「截图直接粘贴」与「运行中也能加附件」是两件不同的事，
# 都用 "附件"/"截图" 当关键词会把后者误杀。只有真正同一件事才归一类。
TOPIC_KEYWORDS = {
    "icon": ("应用图标", "图标改为", "鲲六套"),
    "usage_panel": ("用量面板", "用量明细"),
}

# 「够分量」的判定：说明太短说明只是小改动，不构成「这个项目做了什么」。
# 例如「防误触解锁更快」这类只有一句体感优化，放进 README 会稀释重点。
# 阈值取 40 字：真正的新功能说明通常都在 60 字以上。
MIN_DETAIL_LEN = 40

# 不进的条目特征：这些是修复与细节调整，写进 README 会稀释重点。
# 判断依据是标题里的关键词——新功能的标题是名词短语（「上下文用量面板」），
# 修复类往往是动词开头（「修复…」「优化…」）。
SKIP_PREFIXES = (
    "修复", "优化", "调整", "移除", "删除", "改进", "统一", "补齐",
    # 修饰性小改进：对老用户是「更顺了」，对新访客不构成「这个项目有什么」
    "更省电", "关于页", "数字格式", "明细卡上下",
)

# 「同步上游」不占重点项目名额：它的内容来自上游 Nox/AiCode，不是本项目做的。
# 但完全丢掉也不对——用户升级后确实会多出这些功能，不提等于瞒着。
# 折中：永远排在每版最后，且不计入 MAX_ITEMS_PER_VERSION 的额度。
UPSTREAM_MARKER = "同步上游"


def load_entries():
    """复用 parse-changelog.py，保证 README 与 App/Release 三处内容同源。"""
    out = subprocess.run(
        [sys.executable, str(PARSER)],
        cwd=str(ROOT), capture_output=True, text=True
    )
    if out.returncode != 0:
        sys.stderr.write(f"parse-changelog.py 失败：{out.stderr}\n")
        sys.exit(1)
    return json.loads(out.stdout)["entries"]


def shorten(text: str, limit: int = MAX_LEN) -> str:
    """按字符截断，不要按字节——中文按字节截会切出乱码。"""
    text = re.sub(r"\s+", " ", text).strip()
    if len(text) <= limit:
        return text
    return text[: limit - 1].rstrip("，。、；： ") + "…"


def render(entries) -> str:
    lines = []
    used = entries[:RECENT_VERSIONS]

    printed_any = False
    printed_tags = []
    seen_topics = set()   # 已经出现过的话题（新版本优先，倒序填）

    for entry in used:
        tag = entry["tag"]
        date = entry["date"]
        items = entry.get("items") or []

        # 挑重点：只留本项目做的新功能，修复/微调/上游同步都不进 README。
        # 这些在 CHANGELOG 里都有，README 是概览。
        picked = []
        upstream_item = None
        for it in items:
            title = it.get("title", "").strip()
            if not title:
                continue
            if title.startswith(UPSTREAM_MARKER):
                upstream_item = it
                continue
            if title.startswith(SKIP_PREFIXES):
                continue
            # 说明太短 = 小改动，不算重点项目
            if len(it.get("detail", "").strip()) < MIN_DETAIL_LEN:
                continue
            picked.append(it)

        # 主题去重：先标记本版占用的主题
        for it in picked:
            it["_topic"] = next(
                (k for k, kws in TOPIC_KEYWORDS.items()
                 if any(kw in it.get("title", "") for kw in kws)),
                None
            )
        picked = [it for it in picked
                  if not (it["_topic"] and it["_topic"] in seen_topics)]
        for it in picked:
            if it["_topic"]:
                seen_topics.add(it["_topic"])

        # MAX_ITEMS_PER_VERSION 为 None 表示不截断（全列）。
        # 保留这个变量是为了需要再收紧时有单一开关可改。
        shown = picked if MAX_ITEMS_PER_VERSION is None else picked[:MAX_ITEMS_PER_VERSION]
        # 够分量的改动不足 MIN_ITEMS_PER_VERSION 条 → 整个版本不出现在 README。
        # 只留一两条会显得这一版没什么可说的，反而拉低整段的可信度；
        # 这些小版本的内容在 CHANGELOG 与 App 内更新日记里都能看到。
        if len(shown) < MIN_ITEMS_PER_VERSION:
            continue

        # 第一个有内容的版本不写小标题（章节标题已是「新增与改进」）；
        # 之后每个版本用小标题分隔。
        if printed_any:
            lines.append("")
        lines.append(f"**{tag}（{date}）**")
        lines.append("")
        printed_any = True
        printed_tags.append(tag)

        for it in shown:
            title = it.get("title", "").strip()
            detail = shorten(it.get("detail", ""))
            # 与 CHANGELOG 同样的行内格式：粗体短语 + 说明
            lines.append(f"- **{title}** — {detail}" if detail else f"- **{title}**")

        # 上游功能固定排在最后，不计入上面 3 条的名额
        if upstream_item is not None:
            title = upstream_item.get("title", "").strip()
            detail = shorten(upstream_item.get("detail", ""))
            lines.append(f"- **{title}** — {detail}" if detail else f"- **{title}**")

    # 「更早的版本」按实际没列出来的算，不要假设「前 N 个都列了」——
    # 某个版本可能因为没有够分量的改动而被跳过。
    unlisted = [e["tag"] for e in entries if e["tag"] not in printed_tags]
    if unlisted:
        oldest = unlisted[-1]
        span = unlisted[0] if len(unlisted) == 1 else f"{unlisted[0]} ~ {oldest}"
        lines.append("")
        lines.append(
            f"更早的 {len(unlisted)} 个版本（{span}）见 "
            f"[CHANGELOG.md](CHANGELOG.md)，App 内「关于 → 更新内容」也能离线查看。"
        )

    return "\n".join(lines)


def splice(readme: str, block: str) -> str:
    """只替换两个标记之间的内容；标记缺失时报错，不静默追加。"""
    if START not in readme or END not in readme:
        sys.stderr.write(
            f"README.md 缺少标记。请在「新增与改进」章节内加入：\n"
            f"  {START}\n  {END}\n"
        )
        sys.exit(1)

    pattern = re.compile(
        re.escape(START) + r".*?" + re.escape(END), re.S
    )
    return pattern.sub(f"{START}\n\n{block}\n\n{END}", readme, count=1)


def main() -> int:
    args = sys.argv[1:]
    mode_check = "--check" in args
    mode_dry = "--dry-run" in args

    entries = load_entries()
    if not entries:
        sys.stderr.write("CHANGELOG.md 里没有解析到版本\n")
        return 1

    block = render(entries)
    current = README.read_text(encoding="utf-8")
    updated = splice(current, block)

    if mode_dry:
        print(block)
        return 0

    if mode_check:
        if current == updated:
            print("[PASS] README「新增与改进」与 CHANGELOG 一致")
            return 0
        print("[FAIL] README「新增与改进」已落后于 CHANGELOG，请运行：")
        print("       python3 scripts/gen-readme-highlights.py")
        return 1

    if current == updated:
        print("README 已是最新，无需改动")
        return 0

    README.write_text(updated, encoding="utf-8")
    n = sum(len(e.get("items") or []) for e in entries[:RECENT_VERSIONS])
    print(f"已更新 README「新增与改进」：最近 {min(RECENT_VERSIONS, len(entries))} 个版本，共 {n} 条")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
