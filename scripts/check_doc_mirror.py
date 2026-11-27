#!/usr/bin/env python3
"""文档副本一致性检查：宿主备份必须与仓库内的源完全相同。

为什么需要：
  /root/workspace/docs/ 下有一份 CLAUDE.md 与技能文件的副本。
  它的价值是「容器损坏时的异地备份」——容器根 `/` 与宿主
  `/storage/emulated`（`/dev/fuse`）是两套独立存储。

  但副本会漂移。2026-11-27 实测：docs/铁律/CLAUDE.md 落后主副本 145 行，
  如果有人（包括 AI）打开那份照做，会拿到过期规则。

  现在 sync-rules-doc.sh 在 aigo push 时自动同步，但如果改了 CLAUDE.md
  却没 push，副本就是旧的——所以需要这个检查把窗口期暴露出来。

检查对象（三份副本，同一批源）：
  · <docs>/铁律/CLAUDE.md           ← CLAUDE.md
  · <docs>/技能/<skill>/SKILL.md    ← ~/.nox/skills/<skill>/SKILL.md
  · <docs>/开发规范/<doc>.md         ← ~/.nox/skills/<skill>/SKILL.md（中文名）
  · <docs>/开发规范/<script>         ← scripts/<script>
  · docs/skills/<skill>/SKILL.md    ← ~/.nox/skills/<skill>/SKILL.md（进 git）

方向始终是「本地真实文件 → 副本」。本地那份（~/.nox/skills/、CLAUDE.md、scripts/）
永远不动，因此断网、容器重启都不会影响使用。

只比对「两边都存在」的文件：
  宿主目录可能整体不存在（换机器、首次 clone），那不算错误。

退出码：
  0 = 一致（或副本不存在，跳过）
  1 = 有不一致
"""
import hashlib
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCS = pathlib.Path("/root/workspace/docs")
REPO_SKILLS = ROOT / "docs" / "skills"
SKILLS = pathlib.Path.home() / ".nox" / "skills"

SKILL_NAMES = [
    "aigo-dev-workflow",
    "aigo-build-verify",
    "aigo-release",
    "aigo-upstream-sync",
    "aigo-branding",
]

# 技能 → 宿主中文文档名（与 sync-rules-doc.sh 保持一致）
SKILL_TO_DOC = {
    "aigo-dev-workflow": "AiGo开发流程",
    "aigo-build-verify": "构建与验证",
    "aigo-release": "发版流程",
    "aigo-upstream-sync": "上游同步",
    "aigo-branding": "品牌改名",
}

# 需要同步的脚本（与 sync-rules-doc.sh 保持一致）
SCRIPTS = [
    "aigo", "aigo-gate.sh", "build-lock.sh", "watch.sh",
    "check_ui_tokens.py", "check_changelog_ready.py", "check_changelog_date.py",
    "check_no_parallel_build.sh", "check_room_nullable_default.py",
    "select_tests.py", "gen-readme-highlights.py",
    "verify_panel_alignment.py", "check_migrations.py", "check_active_session.py",
    "check_doc_xref.py", "check_doc_mirror.py", "check_doc_consistency.py",
    "aigo-verify",
]


def digest(p: pathlib.Path) -> str:
    h = hashlib.sha256()
    h.update(p.read_bytes())
    return h.hexdigest()


def main() -> int:
    if not DOCS.is_dir():
        print(f"宿主副本目录不存在，跳过：{DOCS}")
        return 0

    pairs: list[tuple[pathlib.Path, pathlib.Path, str]] = []

    # 铁律
    pairs.append((ROOT / "CLAUDE.md", DOCS / "铁律" / "CLAUDE.md", "铁律"))

    # 技能（两份：英文目录名 + 中文文档名）
    for name in SKILL_NAMES:
        src = SKILLS / name / "SKILL.md"
        pairs.append((src, DOCS / "技能" / name / "SKILL.md", f"技能/{name}"))
        cn = SKILL_TO_DOC.get(name)
        if cn:
            pairs.append((src, DOCS / "开发规范" / f"{cn}.md", f"开发规范/{cn}.md"))
        # 仓库内副本（进 git，防容器与宿主同时损坏）
        pairs.append((src, REPO_SKILLS / name / "SKILL.md", f"docs/skills/{name}"))

    # 脚本
    for s in SCRIPTS:
        pairs.append((ROOT / "scripts" / s, DOCS / "开发规范" / s, f"开发规范/{s}"))

    missing_src = []
    stale = []
    checked = 0

    for src, dst, label in pairs:
        if not src.is_file():
            # 源不存在：可能是脚本被删了，提醒但不当作副本漂移
            if dst.is_file():
                missing_src.append((label, src, dst))
            continue
        if not dst.is_file():
            # 副本不存在：允许（首次 clone 或换机器）
            continue
        checked += 1
        if digest(src) != digest(dst):
            stale.append((label, src, dst))

    fail = False

    if missing_src:
        print(f"[提示] {len(missing_src)} 项源文件不存在但副本还在（可能已废弃）：")
        for label, src, dst in missing_src:
            print(f"  {label}: 缺 {src.name}，副本 {dst.name} 仍在")
        print()

    if stale:
        fail = True
        print(f"文档副本已漂移：{len(stale)} 处与源不一致")
        for label, src, dst in stale:
            s_lines = len(src.read_text(encoding="utf-8", errors="ignore").splitlines())
            d_lines = len(dst.read_text(encoding="utf-8", errors="ignore").splitlines())
            print(f"  {label}: 源 {s_lines} 行 / 副本 {d_lines} 行")
        print()
        print("  修复：sh scripts/sync-rules-doc.sh")
        print("  这条检查的意义：副本是容器损坏时的唯一退路，漂移了就等于假备份。")

    if not fail:
        if checked == 0:
            print("没有可比对的副本（可能尚未同步过）")
        else:
            print(f"文档副本一致（比对 {checked} 个文件）")
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())
