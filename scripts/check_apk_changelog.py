#!/usr/bin/env python3
"""校验 APK 里内置的更新日记与仓库 CHANGELOG.md 是否一致。

为什么要有这一层：

  2026-11-28 出 vc171 时踩过：CHANGELOG.md 在 08:41 改成 8 条，
  而 APK 是 08:17 构建的，包里 assets/changelog.json 还是旧的 4 条。
  用户装完看到「日记没更新」，而所有既有门禁都放行了。

  根因是既有门禁全部只校验源文件：
    - check_changelog_ready.py 只查 CHANGELOG.md 格式与日期
    - gen-readme-highlights.py 只查 README 是否落后于 CHANGELOG
  没有一条去查验「真正进包的那份 JSON」。

  Gradle 的 generateChangelog 任务把 CHANGELOG.md 声明为 inputs，
  但 CHANGELOG.md 比源码旧时它判定「没变」就不重跑，静默沿用旧输出——
  这就是旧日记进包的机制。

  所以校验点必须放在产物上：读 APK 里的 assets/changelog.json，
  与当场解析 CHANGELOG.md 的结果逐条比对。

一致性判据（全部满足才算通过）：
  1. 版本号与日期序列完全一致
  2. 每个版本的条目数一致
  3. 每条标题与正文完全一致（正文做规范化后比对，避免行尾差异误报）

用法：
  python3 scripts/check_apk_changelog.py <apk路径>
  python3 scripts/check_apk_changelog.py <apk路径> --quiet   # 只在失败时输出

退出码：
  0 = 一致
  1 = 不一致或有错误
"""

import json
import re
import subprocess
import sys
import unicodedata
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHANGELOG = ROOT / "CHANGELOG.md"
PARSER = ROOT / "scripts" / "parse-changelog.py"


def die(msg):
    print(f"[FAIL] {msg}", file=sys.stderr)
    sys.exit(1)


def normalize(text):
    """规范化正文，避免不可见差异误报。

    只做 Unicode NFC 与空白折叠：中文全角空格、行尾多余空格、连续空白
    都不影响语义，不该让检查失败；但任何字符增删都必须被发现。
    """
    if text is None:
        return ""
    text = unicodedata.normalize("NFC", str(text))
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def expected_from_repo():
    """用与 Gradle 相同的解析脚本，得到「应该是怎样」。"""
    if not CHANGELOG.exists():
        die(f"CHANGELOG.md 不存在：{CHANGELOG}")
    if not PARSER.exists():
        die(f"解析脚本不存在：{PARSER}")

    proc = subprocess.run(
        [sys.executable, str(PARSER)],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        die(f"parse-changelog.py 执行失败：{proc.stderr.strip()[:400]}")
    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        die(f"parse-changelog.py 输出不是合法 JSON：{exc}")
    if "entries" not in data:
        die("parse-changelog.py 输出缺少 entries 字段")
    return data["entries"]


def actual_from_apk(apk_path):
    """读 APK 内 assets/changelog.json。"""
    apk = Path(apk_path)
    if not apk.exists():
        die(f"APK 不存在：{apk}")

    try:
        with zipfile.ZipFile(apk) as zf:
            names = zf.namelist()
            if "assets/changelog.json" not in names:
                die(
                    "APK 内没有 assets/changelog.json，"
                    "关于页将拿不到任何更新日记（检查 generateChangelog 任务是否执行）"
                )
            raw = zf.read("assets/changelog.json").decode("utf-8")
    except zipfile.BadZipFile:
        die(f"不是合法 APK/ZIP：{apk}")

    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        die(f"APK 内 changelog.json 不是合法 JSON：{exc}")
    if "entries" not in data:
        die("APK 内 changelog.json 缺少 entries 字段（解析脚本版本不一致？）")
    return data["entries"]


def compare(expected, actual, quiet=False):
    """逐条比对，返回错误列表。"""
    errors = []

    if len(expected) != len(actual):
        errors.append(
            f"版本数不一致：仓库 {len(expected)} 个，APK 里 {len(actual)} 个"
        )

    # 按顺序比，不按 tag 匹配：顺序错位本身就是要发现的问题
    for idx in range(min(len(expected), len(actual))):
        exp = expected[idx]
        act = actual[idx]
        pos = f"第 {idx + 1} 个版本"

        exp_tag = exp.get("tag", "")
        act_tag = act.get("tag", "")
        if exp_tag != act_tag:
            errors.append(f"{pos}版本号不一致：仓库 {exp_tag!r}，APK {act_tag!r}")
            # 版本号都不同，后面不用比了
            continue

        exp_date = exp.get("date", "")
        act_date = act.get("date", "")
        if exp_date != act_date:
            errors.append(
                f"{exp_tag} 日期不一致：仓库 {exp_date!r}，APK {act_date!r}"
            )

        exp_items = exp.get("items", [])
        act_items = act.get("items", [])
        if len(exp_items) != len(act_items):
            errors.append(
                f"{exp_tag} 条目数不一致：仓库 {len(exp_items)} 条，"
                f"APK {len(act_items)} 条"
                + (
                    "（APK 是旧的，重出包）"
                    if len(act_items) < len(exp_items)
                    else ""
                )
            )

        for j in range(min(len(exp_items), len(act_items))):
            e = exp_items[j]
            a = act_items[j]
            et = normalize(e.get("title"))
            at = normalize(a.get("title"))
            if et != at:
                errors.append(
                    f"{exp_tag} 第 {j + 1} 条标题不一致：仓库 {et!r}，APK {at!r}"
                )
            ed = normalize(e.get("detail"))
            ad = normalize(a.get("detail"))
            if ed != ad:
                # 正文很长，只报前缀与长度，避免刷屏
                hint = "（长度 {} vs {}）".format(len(ed), len(ad))
                errors.append(
                    f"{exp_tag} 第 {j + 1} 条正文不一致{hint}："
                    f"仓库 {ed[:40]!r}…，APK {ad[:40]!r}…"
                )

    if not errors and not quiet:
        latest = expected[0] if expected else {}
        print(
            "[OK] APK 内置更新日记与 CHANGELOG.md 一致："
            f"{latest.get('tag', '?')}（{latest.get('date', '?')}），"
            f"共 {len(expected)} 个版本、{len(expected[0].get('items', [])) if expected else 0} 条要点"
        )
    return errors


def check_archived_dir(build_output, quiet=False):
    """校验 build-output 里最新的 debug APK。

    存在意义：装包是最后一道关。即使出包时校验过，
    归档环节仍可能拿错文件（比如手工 cp 了旧包、或出包失败后沿用了上一次的包）。
    装之前再验一次，成本几秒，能挡住「装完才发现日记是旧的」。
    """
    out = Path(build_output)
    if not out.is_dir():
        return []  # 没有归档目录不算错误，调用方可能只想验单个包

    apks = sorted(
        out.glob("*.apk"),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    if not apks:
        return []

    newest = apks[0]
    expected = expected_from_repo()
    actual = actual_from_apk(newest)
    errors = compare(expected, actual, quiet=quiet)
    if errors and not quiet:
        print(f"       归档包：{newest.name}", file=sys.stderr)
    return errors


def main():
    quiet = "--quiet" in sys.argv
    args = [a for a in sys.argv[1:] if not a.startswith("--")]

    # --dir 模式：校验指定目录里最新的 APK（装包前用）
    dir_mode = "--dir" in sys.argv
    if dir_mode:
        target = args[0] if args else str(ROOT / "build-output")
        errors = check_archived_dir(target, quiet=quiet)
        if errors:
            print("[FAIL] 归档里最新的包与 CHANGELOG.md 不一致：", file=sys.stderr)
            for err in errors[:8]:
                print(f"       - {err}", file=sys.stderr)
            sys.exit(1)
        if not quiet:
            print("[OK] 归档里最新的包与 CHANGELOG.md 一致")
        sys.exit(0)

    if not args:
        die("用法：check_apk_changelog.py <apk路径> [--quiet]\n"
            "      check_apk_changelog.py --dir [目录]  # 校验目录里最新的包")

    expected = expected_from_repo()
    actual = actual_from_apk(args[0])
    errors = compare(expected, actual, quiet=quiet)

    if errors:
        print("[FAIL] APK 内置更新日记与 CHANGELOG.md 不一致：", file=sys.stderr)
        for err in errors[:12]:
            print(f"       - {err}", file=sys.stderr)
        if len(errors) > 12:
            print(f"       …还有 {len(errors) - 12} 处差异", file=sys.stderr)
        print(
            "\n处置：CHANGELOG.md 改过之后必须重出包，"
            "并确认 generateChangelog 任务真的执行了（不是 UP-TO-DATE）。",
            file=sys.stderr,
        )
        sys.exit(1)

    sys.exit(0)


if __name__ == "__main__":
    main()
