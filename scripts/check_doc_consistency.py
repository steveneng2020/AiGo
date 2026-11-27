#!/usr/bin/env python3
"""文档口径一致性检查：同一术语的强制性表述不能互相矛盾。

为什么需要：
  2026-11-27 发现 `--no-build-cache` 在三处文档里说法不同：
    CLAUDE.md 2.1        写「三个参数缺一不可」（含 --no-build-cache）
    技能 build-verify 五章 说「--configuration-cache 必须开」
    技能 dev-workflow     说「--no-build-cache 必加」
  其中两处与第三处直接相反。靠人工比对发现的，没有脚本能抓。

  更糟的是实测证明「必加」这个结论本身是错的（不加也生效）。
  一条错误的口径，抄进三份文档后就成了「共识」，没人会去验证。

检查方式：
  对每个受关注的术语，收集全文里「术语附近的强制性词」：
    必加 / 必须 / 缺一不可 / 禁止 / 不要 / 不可以   → 强制
    可选 / 可选加 / 不必 / 无需 / 不需要            → 非强制
  同一术语同时出现「强制」与「非强制」两种口径 → 报冲突。

  允许用删除线标注「已推翻」：
    含 ~~ 包裹的行视为历史记录，不参与冲突判断。

不检查什么：
  · 术语的「正确性」——脚本不知道哪个对，只负责指出「说法不一致」
  · 泛指性的句子（如「必须验证」）——只关注配对的强制词与术语
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SKILLS = pathlib.Path.home() / ".nox" / "skills"

# 受关注的术语：出现过自相矛盾、或容易写错的。
# 加新术语前先确认它在至少两份文档里出现，否则没有比对价值。
TERMS = [
    "--no-build-cache",
    "--no-configuration-cache",
    "push --force",
    "rebase",
    "applicationIdSuffix",
    "no-daemon",
    "rerun-tasks",
    "context_window",
]

# 强制口径
MUST = re.compile(r"必加|必须|缺一不可|禁止|不要|不可以|务必|一律")
# 非强制口径
MAY = re.compile(r"可选|不必|无需|不需要|可以不加|不用加|非必需")

# 「必须写明禁止事项（禁止 force push）」这类是元描述，不是对术语本身的口径
FORCE_OK = re.compile(r"必须(写明|包含|做到|验证)|禁止事项|禁令")


def docs() -> list[pathlib.Path]:
    out = [ROOT / "CLAUDE.md"]
    for name in ["aigo-dev-workflow", "aigo-build-verify", "aigo-release",
                 "aigo-upstream-sync", "aigo-branding"]:
        p = SKILLS / name / "SKILL.md"
        if p.is_file():
            out.append(p)
    return [p for p in out if p.is_file()]


def scan(text: str, term: str) -> list[tuple[int, str, str]]:
    """返回 [(行号, 口径 must|may, 原文行)]，跳过删除线与历史说明。"""
    hits = []
    for lineno, line in enumerate(text.splitlines(), 1):
        if term not in line:
            continue
        # 删除线 = 已推翻的历史记录
        if "~~" in line:
            continue
        # 「为什么」段落里的复述不改口径，但无法可靠识别，这里不排除，
        # 宁可多报也别漏报——漏报的代价是矛盾留在文档里。
        if FORCE_OK.search(line):
            continue
        if MAY.search(line):
            hits.append((lineno, "may", line.strip()))
        elif MUST.search(line):
            hits.append((lineno, "must", line.strip()))
    return hits


def main() -> int:
    files = docs()
    if not files:
        print("找不到可检查的文档")
        return 1

    conflicts = []
    for term in TERMS:
        found = {"must": [], "may": []}
        for p in files:
            text = p.read_text(encoding="utf-8", errors="ignore")
            for lineno, kind, line in scan(text, term):
                found[kind].append((p, lineno, line))

        if found["must"] and found["may"]:
            conflicts.append((term, found))

    if conflicts:
        print(f"口径冲突：{len(conflicts)} 个术语在不同文档里说法相反")
        for term, found in conflicts:
            print(f"\n  术语：{term}")
            for kind, label in [("must", "强制"), ("may", "非强制")]:
                for p, lineno, line in found[kind][:3]:
                    name = p.name if p.parent.name != "skills" else p.parent.name
                    short = line[:90] + ("…" if len(line) > 90 else "")
                    print(f"    [{label}] {name}:{lineno}  {short}")
        print()
        print("  处理方式（三选一）：")
        print("    1. 用实测确认哪个对，改掉错的那处，并在提交信息里写明依据")
        print("    2. 保留一种口径，其余处改为引用（「见 CLAUDE.md N.M」）")
        print("    3. 若已推翻，用 ~~删除线~~ 标注并注明推翻日期")
        return 1

    total = sum(len(scan(p.read_text(encoding="utf-8", errors="ignore"), t))
                for p in files for t in TERMS)
    print(f"口径检查通过（{len(TERMS)} 个术语，{len(files)} 份文档，{total} 处相关表述无冲突）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
