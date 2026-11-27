#!/usr/bin/env python3
"""UI 设计系统门禁：拦住会破坏视觉一致性的硬编码。

为什么需要这层检查：界面「简陋、拼凑感」几乎都不是审美问题，而是同一屏里
混了多套尺寸与图标体系——12sp 和 15sp 并排、0.5dp 与 1dp 分隔线共存、
24×24 与 16×16 图标混用。这些在代码审查里看不出来，只有真机上才显得「不对」，
所以交给机器拦。

规则：
  1. 面板/卡片类文件禁止硬编码 fontSize / lineHeight，必须用 PanelTokens。
  2. 面板/卡片类文件禁止使用 FeatherIcons（与卡片的 16×16 图标体系冲突）。
  3. 涉及统计数值的文件必须出现 tnum（等宽数字），否则多行小数无法对齐。

豁免：文件顶部加 `ui-tokens-exempt: <原因>` 可跳过该文件。
存量文件在迁移完成前带此标记，新文件不允许添加。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java"

# 受规则约束的范围：全部 UI 组件文件。
#
# 早期版本只查两个文件，实际等于没有门禁——其余 36 个文件 100+ 处硬编码完全不拦。
# 现在全量检查，存量违规用文件级 ui-tokens-exempt 标记显式豁免，
# 豁免是「被记录的技术债」，不是「看不见的死角」。
SCOPE_DIRS = [
    SRC / "com/nox/core/ui",
    SRC / "com/nox/feature/agent/presentation/component",
    SRC / "com/nox/feature/settings/presentation/component",
    SRC / "com/nox/feature/terminal/presentation/component",
    SRC / "com/nox/feature/git/presentation/component",
    SRC / "com/nox/feature/workspace/presentation/component",
    SRC / "com/nox/feature/browser/presentation",
    SRC / "com/nox/feature/backup/presentation",
]

EXEMPT_MARK = "ui-tokens-exempt"

# 允许出现字面量的文件：设计令牌与图标定义本身就是数值的出处
ALLOWLIST = {
    "PanelTokens.kt",
    "PanelIcons.kt",
    "PanelSection.kt",      # 唯一允许把令牌落到 TextStyle 的地方
    "AnchorPopup.kt",
}

FONT_PATTERN = re.compile(r"\b(fontSize|lineHeight)\s*=\s*[0-9]+(\.[0-9]+)?\.sp\b")

# 豁免说明里声明的「N 处」，用于交叉校验。
# 例：`// ui-tokens-exempt: 存量文件，字号尚未迁移到 PanelTokens（4 处）。`
EXEMPT_COUNT = re.compile(r"ui-tokens-exempt:[^\n]*?（(\d+)\s*处）")
FEATHER_PATTERN = re.compile(r"\bFeatherIcons\b")

# 图标体系规则只对「统计卡片」生效：这类卡片内部的 16×16 图标要和分组标题同一套。
# 其余界面（列表、终端、设置页）本来就用项目通用的 Feather 图标，
# 对它们报错是假阳性，只会让人关掉门禁。
ICON_SCOPE_FILES = {
    "ContextUsagePanel.kt",
    "MessageBubbles.kt",
    "PanelSection.kt",
    "PanelValueRow.kt",
}
TNUM_PATTERN = re.compile(r"fontFeatureSettings\s*=\s*\"tnum\"")

# 数值必须通过 PanelValueRow 渲染：tnum 已在组件内部统一处理，
# 业务文件重复声明 fontFeatureSettings 反而是重复定义。
NUMERIC_HINT = re.compile(r"formatExactTokenCount|formatCompactTokenCount|tok/s")
VALUE_ROW_USAGE = re.compile(r"\bPanelValueRow\s*\(")

# 交互图标（复制/回退/更多/关闭）不属于统计卡片，允许沿用应用通用图标体系。
ICON_ALLOW_LINES = re.compile(
    r"FeatherIcons\.(Check|Copy|RotateCcw|MoreHorizontal|X|ChevronDown|ChevronUp|ChevronRight|ArrowDown|ArrowUp)"
)


def is_panel_scope(path: pathlib.Path) -> bool:
    if path.suffix != ".kt":
        return False
    return any(path.parent == d for d in SCOPE_DIRS)


def main() -> int:
    problems: list[str] = []
    exempt_mismatch: list[str] = []
    checked = 0

    for path in sorted(SRC.rglob("*.kt")):
        if not is_panel_scope(path):
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        rel = path.relative_to(ROOT)

        if EXEMPT_MARK in text:
            # 豁免文件不参与「必须用令牌」的检查，但要核对豁免说明里的数字是否还准。
            #
            # 为什么查这个：豁免注释写着「（4 处）」，但文件后来被人改了
            # （新增两处字号、或删掉一处），数字就成了假的。
            # 假数字比没数字更糟——它会让人以为债务规模没变，实际已经滚大或缩小。
            # 2026-11-27 就有文件因为上游合并多出硬编码，而注释仍写旧数字。
            declared = EXEMPT_COUNT.search(text)
            if declared:
                want = int(declared.group(1))
                got = sum(
                    1 for line in text.splitlines()
                    if not line.lstrip().startswith("//") and FONT_PATTERN.search(line)
                )
                if got != want:
                    exempt_mismatch.append(
                        f"{rel}: 豁免说明写「{want} 处」，实际 {got} 处"
                        f"（{'+' if got > want else ''}{got - want}）"
                    )
            continue
        if path.name in ALLOWLIST:
            continue

        checked += 1
        for lineno, line in enumerate(text.splitlines(), 1):
            if line.lstrip().startswith("//"):
                continue
            if FONT_PATTERN.search(line):
                problems.append(
                    f"{rel}:{lineno} 硬编码字号，改用 PanelTokens\n    {line.strip()}"
                )
            if (
                path.name in ICON_SCOPE_FILES
                and FEATHER_PATTERN.search(line)
                and not ICON_ALLOW_LINES.search(line)
                and not line.lstrip().startswith("import")
            ):
                problems.append(
                    f"{rel}:{lineno} 使用了 FeatherIcons 图标，"
                    f"统计卡片内的分组图标应使用 PanelIcons 的 16×16 体系\n    {line.strip()}"
                )

        # 渲染统计数字却没走统一的行组件：tnum 与对齐都在组件里，绕过就失去保障
        if NUMERIC_HINT.search(text) and not VALUE_ROW_USAGE.search(text):
            problems.append(
                f"{rel}: 渲染统计数值但未使用 PanelValueRow，"
                f"数值将缺少等宽数字与右对齐"
            )

    if problems:
        print(f"UI 设计系统检查未通过（{len(problems)} 项）：")
        for p in problems:
            print(f"  {p}")
        print()
        print("修复方式：尺寸取自 com.nox.core.theme.PanelTokens，")
        print("分组用 PanelSection / PanelValueRow，图标用 PanelIcons。")
        print(f"存量文件可临时加注释 {EXEMPT_MARK}: <原因> 豁免。")
        return 1

    # 豁免数字不符：只警告不拦，因为它不影响界面正确性，
    # 但必须让人看见——数字失真意味着债务规模判断失效。
    if exempt_mismatch:
        print(f"[警告] {len(exempt_mismatch)} 个文件的豁免数字与实际不符：")
        for m in exempt_mismatch:
            print(f"  {m}")
        print()
        print("  豁免注释里的「N 处」应与文件内实际硬编码数量一致。")
        print("  改了这些文件就顺手更新数字，否则债务规模无从判断。")
        print()

    print(f"UI 设计系统检查通过（扫描 {checked} 个面板类文件，"
          f"{len(exempt_mismatch)} 个豁免待更新）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
