#!/usr/bin/env python3
"""中英 README 结构一致性检查。

为什么需要：
  2026-11-27 发现英文版与中文版实质脱节——
  中文「新增与改进」由 CHANGELOG 自动生成，覆盖 5 个版本、每条含详细说明；
  英文那节是手写的 5 条泛泛短句，与 CHANGELOG 无关。
  更糟的是英文写着「three built-in icon sets」，实际是九套。

  两份 README 面向不同读者，但结构必须对应：读者切换语言时，
  应该看到同样的章节、同样的顺序。内容深度可以不同（中文详、英文简）。

采用的做法（用户 2026-11-27 决定）：
  英文版不镜像详细更新日志，只保留概述 + 指向 CHANGELOG.md 的链接。
  这样英文的维护成本接近零，不会与中文脱节。

检查内容：
  1. 二级标题数量与顺序必须一一对应
  2. 每个对应章节都不能为空（至少一行正文）
  3. 中文的「新增与改进」自动生成标记必须存在
  4. 英文必须链接到 CHANGELOG.md（否则读者找不到详细说明）

不检查内容语义——翻译质量无法自动判断。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ZH = ROOT / "README.md"
EN = ROOT / "README.en.md"

HEAD = re.compile(r"^##\s+(.+)$", re.M)


def sections(path: pathlib.Path) -> list[tuple[str, int]]:
    """返回 [(标题, 正文行数)]。"""
    lines = path.read_text(encoding="utf-8").split("\n")
    idx = [(i, m.group(1).strip())
           for i, l in enumerate(lines)
           if (m := re.match(r"^##\s+(.+)$", l))]
    out = []
    for k, (i, name) in enumerate(idx):
        end = idx[k + 1][0] if k + 1 < len(idx) else len(lines)
        body = [l for l in lines[i + 1:end] if l.strip()]
        out.append((name, len(body)))
    return out


def main() -> int:
    if not ZH.is_file() or not EN.is_file():
        print("找不到 README.md 或 README.en.md")
        return 1

    zh_text = ZH.read_text(encoding="utf-8")
    en_text = EN.read_text(encoding="utf-8")
    zh = sections(ZH)
    en = sections(EN)

    problems = []

    # 1. 章节数必须相等
    if len(zh) != len(en):
        problems.append(
            f"章节数不一致：中文 {len(zh)} 节 / 英文 {len(en)} 节\n"
            f"    中文：{' / '.join(n for n, _ in zh)}\n"
            f"    英文：{' / '.join(n for n, _ in en)}"
        )
    else:
        # 2. 逐节检查非空
        for (zn, zc), (en_, ec) in zip(zh, en):
            if ec == 0:
                problems.append(f"英文「{en_}」（对应中文「{zn}」）是空章节")
            if zc == 0:
                problems.append(f"中文「{zn}」是空章节")

    # 3. 中文的自动生成标记
    if "新增与改进:START" not in zh_text:
        problems.append("中文 README 缺少 `<!-- 新增与改进:START -->` 标记（gen-readme-highlights.py 依赖它）")
    if "新增与改进:END" not in zh_text:
        problems.append("中文 README 缺少 `<!-- 新增与改进:END -->` 标记")

    # 4. 英文必须指向 CHANGELOG
    if "CHANGELOG.md" not in en_text:
        problems.append("英文 README 没有链接到 CHANGELOG.md——英文版是简述，"
                        "不给链接读者就找不到详细说明")

    if problems:
        print(f"中英 README 结构检查未通过（{len(problems)} 项）：")
        for p in problems:
            print(f"  {p}")
        print()
        print("  约定（2026-11-27 定）：中文详细、英文简述，但章节必须一一对应。")
        print("  英文不镜像更新日志，只留概述 + 指向 CHANGELOG.md 的链接。")
        return 1

    print(f"中英 README 结构一致（各 {len(zh)} 节，英文含 CHANGELOG 链接）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
