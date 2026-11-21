#!/usr/bin/env python3
"""把提交标题转成安全的 APK 文件名片段。

从 stdin 读一行文本（通常是 git 提交标题或 tag 注释首行），输出可直接
拼进文件名的片段。

为什么单独成脚本：这段逻辑原本内联在 workflow 的 `python3 -c '...'` 里，
多层引号（shell 单引号 + python 双引号 + 正则转义）在 YAML 的 run 块中
极易被误解析，曾导致 android-release.yml 整个被判为无效、从 workflow
列表里消失、tag 推送不触发任何构建。

规则：
  1. 去掉 conventional commit 前缀（feat: / fix(x): 等）
  2. 路径分隔符、Windows 非法字符、中文标点换成 '-'
  3. 空白换成 '-'，合并连续 '-'
  4. 按字符（非字节）截断到 32，避免中文被切断成乱码

示例：
  "fix(ci): 修正 APK 文件名摘要" -> "修正-APK-文件名摘要"
"""

import re
import sys


def to_filename_fragment(raw: str, limit: int = 32) -> str:
    """生成可安全用于 GitHub Release 资产的名称片段。

    注意：GitHub 的 Release Assets API 会把中文等非 ASCII 字符改写成 '-'，
    无论客户端传什么名字（实测 softprops/action-gh-release 上传时日志显示中文，
    但 API 落库后变成 '-.-'）。因此这里只保留 ASCII 单词，
    中文摘要改由 Release 正文承载（见 workflow 的 Generate changelog 步骤）。
    """
    s = raw.strip()
    if not s:
        return ""
    # conventional commit 前缀：type 或 type(scope) 后跟冒号
    s = re.sub(r"^[a-z]+(\([^)]*\))?:\s*", "", s)
    # 只保留 ASCII 字母数字与分隔符；中文/标点全部视为分隔
    s = re.sub(r"[^A-Za-z0-9]+", "-", s)
    s = re.sub(r"-+", "-", s).strip("-")
    return s[:limit].strip("-")


def main() -> int:
    data = sys.stdin.read()
    print(to_filename_fragment(data))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
