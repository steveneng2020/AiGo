#!/usr/bin/env python3
"""检查 Room 实体里「可空类型 + defaultValue」的组合，这类错误编译期查不出、装包才崩。

为什么需要：
  2026-11-27 给 AgentMessageEntity 加两个可空时间列时写成：

      @ColumnInfo(defaultValue = "0")
      val ttfbMillis: Long? = null

  编译、单测、出包全部通过，**装到手机上一启动就崩**：

      IllegalStateException: Migration didn't properly handle: agent_messages
      Expected: ... defaultValue='0' ...
      Found:    ... defaultValue='null' ...

  Room 会把 defaultValue 写进它生成的 schema 期望，再拿它跟实际表结构比对。
  可空列的迁移通常写 `ADD COLUMN x INTEGER DEFAULT NULL`，
  两边对不上就直接抛异常，且是在数据库首次打开时——用户第一眼就看到崩溃。

判定规则
  实体文件里，`@ColumnInfo(defaultValue = ...)` 修饰的字段如果是可空类型（`Xxx?`），
  判为违规。要么去掉 defaultValue，要么把类型改成非空。

  唯一例外：defaultValue = "NULL"（显式写 NULL）与可空类型是自洽的，放行。

用法
    python3 scripts/check_room_nullable_default.py
    python3 scripts/check_room_nullable_default.py --quiet

退出码：0 通过；1 有违规。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# 实体都在 data/local/entity 下；广度扫一遍 main 也无妨，但限定范围更快。
SCAN_DIRS = [ROOT / "app/src/main/java"]

# @ColumnInfo(...) 后面紧跟着的 val 声明
# 允许中间有注释与注解，但实现上按「最近一个 @ColumnInfo 到下一个 val」判断。
COLUMN_INFO = re.compile(r"@ColumnInfo\s*\(([^)]*)\)", re.S)
DEFAULT_VAL = re.compile(r'defaultValue\s*=\s*"([^"]*)"')
FIELD = re.compile(r"\bval\s+(\w+)\s*:\s*([\w.<>,\s\?]+?)\s*=")


def scan_file(path: Path):
    """返回 [(行号, 字段名, 类型, defaultValue)]。"""
    try:
        text = path.read_text(encoding="utf-8")
    except Exception:
        return []
    if "@ColumnInfo" not in text:
        return []

    hits = []
    for m in COLUMN_INFO.finditer(text):
        args = m.group(1)
        dv = DEFAULT_VAL.search(args)
        if not dv:
            continue
        default_value = dv.group(1)
        # 找这个注解之后最近的 val 声明
        f = FIELD.search(text, m.end())
        if not f:
            continue
        # 字段必须紧邻（中间不能跨过另一个属性太多行）——
        # 用行号距离粗判，避免把下一个类的字段算进来。
        between = text[m.end():f.start()]
        if between.count("\n") > 6:
            continue
        field_name, field_type = f.group(1), f.group(2).strip()
        if not field_type.endswith("?"):
            continue
        # 显式 NULL 与可空类型自洽
        if default_value.upper() == "NULL":
            continue
        line_no = text[: m.start()].count("\n") + 1
        hits.append((line_no, field_name, field_type, default_value))
    return hits


def main() -> int:
    quiet = "--quiet" in sys.argv
    problems = []

    for base in SCAN_DIRS:
        for path in base.rglob("*.kt"):
            for line_no, name, ftype, dv in scan_file(path):
                problems.append((path, line_no, name, ftype, dv))

    if problems:
        print("[FAIL] 可空字段带了非 NULL 的 defaultValue（装包启动会崩）：")
        for path, line_no, name, ftype, dv in problems:
            rel = path.relative_to(ROOT)
            print(f"       {rel}:{line_no}")
            print(f"          val {name}: {ftype}   defaultValue = \"{dv}\"")
        print()
        print("  两种改法，选一种：")
        print("    ① 去掉 @ColumnInfo(defaultValue = ...)，可空列不需要默认值")
        print("       迁移里写 ADD COLUMN x INTEGER DEFAULT NULL（或不写 DEFAULT）")
        print("    ② 把类型改成非空（去掉 ?），迁移写 DEFAULT 0 NOT NULL")
        print()
        print("  为什么必须拦：编译、单测、出包全都会通过，")
        print("  只有装到设备上首次打开数据库时才抛 IllegalStateException。")
        return 1

    if not quiet:
        n = sum(1 for base in SCAN_DIRS for _ in base.rglob("*.kt"))
        print(f"[PASS] 未发现「可空字段 + 非 NULL 默认值」组合（扫描 {n} 个 Kotlin 文件）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
