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
    s = raw.strip()
    if not s:
        return ""
    # conventional commit 前缀：type 或 type(scope) 后跟冒号
    s = re.sub(r"^[a-z]+(\([^)]*\))?:\s*", "", s)
    # 非法字符与中文标点
    s = re.sub(r'[\\/:|*?"<>，,。.、；;：！!？?（）()【】\[\]]', "-", s)
    # 空白与连字符归一
    s = re.sub(r"\s+", "-", s)
    s = re.sub(r"-+", "-", s).strip("-")
    # 按字符截断（Python 的 str 切片以字符为单位，不会切断中文）
    return s[:limit]


def main() -> int:
    data = sys.stdin.read()
    print(to_filename_fragment(data))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
