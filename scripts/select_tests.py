#!/usr/bin/env python3
"""按本次改动的文件，筛出受影响的测试类，用于本地快速验证。

为什么需要：
  pre-push 跑全量 :app:testUniversalDebugUnitTest 要 8~9 分钟（143 个测试类）。
  只改一行文案也要等这么久，一天推五次就浪费大半小时。

  但**不能因此降低安全性**：CI 的 verify job 仍是全量跑，本地筛的只是
  「推送前先自己发现明显问题」，漏跑由 CI 兜底。

策略：宁可多跑，不可漏跑。
  1. 改动文件 → 测试目录的同名类（Xxx.kt → XxxTest.kt）；
  2. 同目录下所有测试类（改 AgentViewModel 时，同包的聚合逻辑测试也要跑）；
  3. 核心层（core/**）改动 → 全部测试都算受影响（被到处依赖）；
  4. 拿不准就退回全量（比如改了 Gradle 配置、改了测试本身）。

用法：
  python3 scripts/select_tests.py                 # 打印要跑的测试类
  python3 scripts/select_tests.py --gradle-args   # 输出 --tests 参数，可直接拼进 gradlew
  python3 scripts/select_tests.py --base origin/main   # 指定比较基准（默认 HEAD~1）

退出码：0 正常（可能输出空=不需要跑）；1 参数或 git 出错。
"""
import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEST_ROOT = ROOT / "app/src/test/java"
MAIN_ROOT = ROOT / "app/src/main/java"

# 改了这些就直接全量：它们被到处依赖，或会影响测试本身的运行方式。
FULL_RUN_TRIGGERS = (
    "app/build.gradle.kts",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "app/src/test/",          # 改了测试代码本身
    "app/src/androidTest/",
)


def changed_files(base: str) -> list[str]:
    """本次改动涉及的文件（相对仓库根）。取已暂存 + 未提交 + 与 base 的差异。"""
    out = subprocess.run(
        ["git", "diff", "--name-only", base, "HEAD"],
        cwd=str(ROOT), capture_output=True, text=True,
    )
    files = set(filter(None, out.stdout.splitlines()))

    # 工作区未提交的改动也算（本地验证时它们才是重点）
    for args in (["git", "diff", "--name-only"],
                 ["git", "diff", "--name-only", "--cached"],
                 ["git", "ls-files", "--others", "--exclude-standard"]):
        out = subprocess.run(args, cwd=str(ROOT), capture_output=True, text=True)
        files.update(filter(None, out.stdout.splitlines()))
    return sorted(files)


def all_test_classes() -> list[str]:
    """所有测试类的全限定名。"""
    names = []
    for p in TEST_ROOT.rglob("*Test.kt"):
        rel = p.relative_to(TEST_ROOT).with_suffix("")
        names.append(".".join(rel.parts))
    return sorted(names)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="HEAD~1", help="比较基准，默认 HEAD~1")
    ap.add_argument("--gradle-args", action="store_true", help="输出 --tests 参数")
    args = ap.parse_args()

    if not TEST_ROOT.is_dir():
        print(f"[FAIL] 找不到测试目录 {TEST_ROOT}")
        return 1

    files = changed_files(args.base)
    if not files:
        print("无改动")
        return 0

    every = all_test_classes()
    every_set = set(every)

    # 触发全量的改动
    for f in files:
        if any(f.startswith(t) for t in FULL_RUN_TRIGGERS):
            return emit(every, f"改了 {f}，跑全量", args.gradle_args)

    # 只关心 main 下的 Kotlin 源码
    srcs = [f for f in files if f.startswith("app/src/main/java/") and f.endswith(".kt")]
    if not srcs:
        print("本次改动不涉及 Kotlin 源码，无需跑单测")
        return 0

    # core/** 与 domain/** 被到处依赖，改动即全量。
    # 宁可多跑：这两个目录下一个小改动就可能影响几十个测试类。
    for f in srcs:
        if "/core/" in f or "/domain/" in f:
            return emit(every, f"改了公共层 {f.split('/java/')[-1]}，跑全量", args.gradle_args)

    picked: set[str] = set()
    for f in srcs:
        rel = Path(f.split("/java/")[-1])       # com/nox/feature/agent/.../Xxx.kt
        stem = rel.stem                          # Xxx
        pkg = ".".join(rel.with_suffix("").parts[:-1])   # com.nox.feature.agent...
        pkg_path = rel.parent                    # com/nox/feature/agent/...

        # 1) 同名测试类
        for cand in (f"{pkg}.{stem}Test", f"{pkg}.{stem}Tests"):
            if cand in every_set:
                picked.add(cand)

        # 2) 同包下的所有测试类（同目录 src/test 对应位置）
        test_dir = TEST_ROOT / pkg_path
        if test_dir.is_dir():
            for p in test_dir.glob("*Test.kt"):
                picked.add(".".join(p.relative_to(TEST_ROOT).with_suffix("").parts))

    if not picked:
        # 找不到对应测试，可能是纯 UI 文件。不跑比盲跑全量更省，
        # 但要说清楚，避免误以为「已验证」。
        print(f"改了 {len(srcs)} 个 Kotlin 文件，但没找到对应测试类：")
        for f in srcs:
            print(f"  {f.split('/java/')[-1]}")
        print()
        print("可能是纯 UI/Composable 文件（测试覆盖在别处或暂无）。")
        print("需要的话显式跑全量：sh gradlew :app:testUniversalDebugUnitTest")
        return 0

    return emit(sorted(picked), f"改了 {len(srcs)} 个 Kotlin 文件", args.gradle_args,
                total=len(every))


def emit(classes: list[str], reason: str, gradle_args: bool, total: int | None = None) -> int:
    if gradle_args:
        # Gradle 的 --tests 可重复；类名需全限定。
        print(" ".join(f'--tests "{c}"' for c in classes))
        return 0

    print(f"{reason}，选中 {len(classes)} 个测试类"
          + (f"（共 {total} 个）" if total else ""))
    for c in classes:
        print(f"  {c}")
    if total:
        print(f"\n省下约 {total - len(classes)} 个测试类的时间（CI 仍跑全量兜底）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
