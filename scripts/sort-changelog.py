#!/usr/bin/env python3
"""把提交标题按「对用户的重要程度」排序。

更新日记是给用户看的，不是提交日志。纯时间倒序会让
`fix(ci): 修 YAML 语法` 压在 `feat: 附件可用性` 前面，
用户翻半天找不到这个版本到底改了什么。

用法：
  # 只排标题
  git log --no-merges --format=%s <range> | python3 scripts/sort-changelog.py

  # 标题 + 短哈希（制表符分隔），发布页用这个
  paste <(git log --no-merges --format=%s <range>) \
        <(git log --no-merges --format=%h <range>) \
    | python3 scripts/sort-changelog.py --with-sha

输入：每行一个提交（标题，或 短哈希<TAB>标题）
输出：按重要度分层、同层内保持输入顺序（即时间倒序）

分层（数字越小越靠前）：
  0  feat，且 scope 不是工程类      用户最关心的新功能
  1  fix，且 scope 不是工程类       用户能感知的修复
  2  feat(ci/build/deps)           对开发者有用，对用户无感
  3  perf
  4  refactor / style
  5  fix(ci/build/deps)、chore、test、ci、build
  6  docs
  9  无 conventional 前缀的杂项

为什么 scope 是 ci/build/deps 时要下沉：这些改动只影响构建与发布流程，
用户装上后感受不到。若不区分，`feat(ci): 发版支持选择架构` 会排在
`feat: 附件可用性` 前面，让人误以为这版只改了 CI。
"""

import re
import sys

TOOLING_SCOPES = {"ci", "build", "deps", "gradle", "release"}

_PREFIX = re.compile(r"^(\w+)(?:\(([^)]*)\))?")


def importance(subject: str) -> int:
    m = _PREFIX.match(subject)
    if not m:
        return 9
    type_ = m.group(1).lower()
    scope = (m.group(2) or "").lower()
    is_tooling = scope in TOOLING_SCOPES

    if type_ == "feat":
        return 2 if is_tooling else 0
    if type_ == "fix":
        return 5 if is_tooling else 1
    if type_ == "perf":
        return 3
    if type_ in ("refactor", "style"):
        return 4
    if type_ in ("chore", "test", "ci", "build"):
        return 5
    if type_ == "docs":
        return 6
    return 9


def main() -> int:
    with_sha = "--with-sha" in sys.argv[1:]
    lines = [ln.rstrip("\n") for ln in sys.stdin if ln.strip()]

    if with_sha:
        # 输入形如 "<sha>\t<subject>"，按 subject 排序、原样输出
        pairs = []
        for ln in lines:
            parts = ln.split("\t", 1)
            pairs.append((parts[0], parts[1] if len(parts) > 1 else ""))
        # 稳定排序：同层内保持输入顺序（git log 的时间倒序）
        ordered = sorted(pairs, key=lambda p: importance(p[1]))
        out = ["%s\t%s" % (sha, subj) for sha, subj in ordered]
    else:
        ordered = sorted(lines, key=importance)
        out = ordered

    sys.stdout.write("\n".join(out) + ("\n" if out else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
