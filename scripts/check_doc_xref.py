#!/usr/bin/env python3
"""文档交叉引用检查：确认「见 N.M」指向的条目真的存在。

为什么需要：
  2026-11-27 给 CLAUDE.md 插入新条目后，原 3.2~3.8 顺延为 3.3~3.9，
  但正文里三处「见 3.5 / 见 3.4 / 见 3.3」还指着旧编号。
  当时是手工 `rg` 找出来改的——下次插入条目还会重演。

  引用失效比「写错」更隐蔽：读者看到「见 3.5」会翻过去，
  发现那条讲的是别的事，才意识到编号错了。没人会主动验证每条引用。

检查范围：
  · CLAUDE.md 内部的 `见 N.M` / `见 N.M 与 N.P` 引用
  · 交接程序/ 下各文档对 CLAUDE.md 的引用

不检查：
  · 「见 3.x」这类占位写法
  · 指向文件路径的引用（那是另一个脚本的事）
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CLAUDE = ROOT / "CLAUDE.md"

# 「见 3.5」「见 10.2」「见 3.1 与 3.2」这类引用
REF = re.compile(r"见\s*(\d+\.\d+(?:\s*[、与和]\s*\d+\.\d+)*)")
# 三级标题「### 3.5 标题」
HEAD = re.compile(r"^###\s+(\d+\.\d+)\s", re.M)


def collect_heads(text: str) -> set[str]:
    return set(HEAD.findall(text))


def collect_refs(text: str) -> list[tuple[int, str]]:
    """返回 [(行号, 引用编号)]。"""
    out = []
    for lineno, line in enumerate(text.splitlines(), 1):
        for m in REF.finditer(line):
            # 一段引用里可能有多条：「见 3.1 与 3.2」
            for num in re.findall(r"\d+\.\d+", m.group(1)):
                out.append((lineno, num))
    return out


def main() -> int:
    if not CLAUDE.is_file():
        print("找不到 CLAUDE.md", file=sys.stderr)
        return 1

    text = CLAUDE.read_text(encoding="utf-8")
    heads = collect_heads(text)
    refs = collect_refs(text)

    if not heads:
        print("CLAUDE.md 里没有找到任何 `### N.M` 标题，检查规则可能失效")
        return 1

    bad = [(ln, n) for ln, n in refs if n not in heads]

    # 交接程序/ 下对 CLAUDE.md 的引用也查（这些文档不在仓库内，允许不存在）
    handoff_dir = pathlib.Path("/root/workspace/交接程序")
    ext_bad = []
    if handoff_dir.is_dir():
        for p in sorted(handoff_dir.glob("*.md")):
            t = p.read_text(encoding="utf-8", errors="ignore")
            for ln, n in collect_refs(t):
                if n not in heads:
                    ext_bad.append((p.name, ln, n))

    if bad:
        print(f"CLAUDE.md 的交叉引用有 {len(bad)} 处指向不存在的条目：")
        for ln, n in bad:
            print(f"  CLAUDE.md:{ln}  「见 {n}」但不存在 ### {n}")
        print()
        print("  改法：插入或删除条目后，重排编号并同步所有引用。")
        print("  自动重排片段：")
        print("    idx = [i for i,l in enumerate(lines) if l.startswith('### 3.')]")
        print("    for n,i in enumerate(idx, 1): lines[i] = re.sub(r'^### 3\\.\\d+', f'### 3.{n}', lines[i])")
        return 1

    if ext_bad:
        # 交接文档不在仓库里，只警告不拦
        print(f"[警告] 交接程序/ 下有 {len(ext_bad)} 处引用失效（不阻断提交）：")
        for name, ln, n in ext_bad:
            print(f"  {name}:{ln}  「见 {n}」但 CLAUDE.md 无此条")
        print()

    print(f"交叉引用检查通过（{len(refs)} 处引用，{len(heads)} 个条目）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
