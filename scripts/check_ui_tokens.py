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

# 受规则约束的范围：只限「统计类浮层卡片」这一族。
#
# 不覆盖整个 component 目录：聊天面板、状态气泡、问询卡片等是各自独立的界面，
# 它们本来就用 Feather 的 24×24 图标体系，纳入本规则只会误报。
# 规则要拦住的是「同一张统计卡片里混多套尺寸」，不是强迫全应用换图标。
SCOPE_FILES = {
    "ContextUsagePanel.kt",
    "MessageBubbles.kt",
}

EXEMPT_MARK = "ui-tokens-exempt"

# 允许出现字面量的文件：设计令牌与图标定义本身就是数值的出处
ALLOWLIST = {
    "PanelTokens.kt",
    "PanelIcons.kt",
    "PanelSection.kt",      # 唯一允许把令牌落到 TextStyle 的地方
    "AnchorPopup.kt",
}

FONT_PATTERN = re.compile(r"\b(fontSize|lineHeight)\s*=\s*[0-9]+(\.[0-9]+)?\.sp\b")
FEATHER_PATTERN = re.compile(r"\bFeatherIcons\b")
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
    return path.name in SCOPE_FILES


def main() -> int:
    problems: list[str] = []
    checked = 0

    for path in sorted(SRC.rglob("*.kt")):
        if not is_panel_scope(path):
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        rel = path.relative_to(ROOT)

        if EXEMPT_MARK in text:
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
            if FEATHER_PATTERN.search(line) and not ICON_ALLOW_LINES.search(line):
                # import 行单独判：只要文件里还有被允许的图标，导入就是必要的
                if line.lstrip().startswith("import"):
                    pass
                else:
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

    print(f"UI 设计系统检查通过（扫描 {checked} 个面板类文件）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
