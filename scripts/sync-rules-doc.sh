#!/bin/sh
# 把「AI 协作规则与开发规范」同步到宿主可见的 docs 下。
#
# 为什么需要（2026-11-27 重新确认）：
#   容器根 /（/dev/block/dm-10）与宿主共享存储 /storage/emulated（/dev/fuse）
#   是两套独立存储。容器损坏、重置或刷机时，/root 下的东西全部消失；
#   宿主存储里那份还在。所以副本的价值是**异地备份**，不是方便查看。
#
# 曾经犯的错（2026-11-27 同一天）：
#   把这些副本当成「会漂移的冗余」全删了。理由是「权威版本在容器内，副本只会过期」。
#   这个判断只对了一半——漂移是真问题，但解决办法是**自动刷新**，不是删除。
#   删掉之后容器一旦损坏就彻底没了。
#
# 权威版本（唯一真实来源，本脚本只读它们）：
#   /root/.aigo-build/CLAUDE.md              铁律
#   /root/.aigo-build/scripts/aigo           统一入口
#   /root/.aigo-build/scripts/aigo-gate.sh   门禁
#   /root/.aigo-build/scripts/*.py|*.sh      各专项脚本
#   /root/.nox/skills/aigo-*/SKILL.md        5 个技能
#
# 同步到：
#   <宿主>/docs/铁律/CLAUDE.md
#   <宿主>/docs/技能/<技能名>/SKILL.md
#   <宿主>/docs/开发规范/<文档>.md
#   <宿主>/docs/开发规范/<脚本>
#
# 触发：aigo push 成功后自动调用；也可手动 aigo sync-rules
#
# 为什么只单向同步：权威版本在容器内，这里改了不会生效，
# 单向覆盖可以避免「以为改了有用」的误解。

set -u

MAIN="${AIGO_MAIN:-/root/.aigo-build}"
SKILLS_SRC="${HOME}/.nox/skills"
DOCS_DST="${AIGO_DOCS:-/root/workspace/docs}"
# 仓库内副本：进 git，断网/换设备时从远端 clone 即可拿回。
# 注意方向：本地 ~/.nox/skills 是真实来源，这里和 DOCS_DST 都是从它拷出来的产物。
# 绝不能反过来把本地做成软链接指向仓库——那样断网或忘记 clone 就用不了。
REPO_DST="$MAIN/docs/skills"

# 只同步 AiGo 自己的技能，不拷插件开发、设计类等无关技能。
AIGO_SKILLS="aigo-dev-workflow aigo-build-verify aigo-release aigo-upstream-sync aigo-branding"

# 技能名 → 宿主可见的中文文档名（与 scripts/ 下的脚本一起放进 开发规范/）
SKILL_TO_DOC_dev="AiGo开发流程"
SKILL_TO_DOC_build="构建与验证"
SKILL_TO_DOC_release="发版流程"
SKILL_TO_DOC_upstream="上游同步"
SKILL_TO_DOC_branding="品牌改名"

# 需要同步的脚本（权威版本在 scripts/）
SCRIPTS="aigo aigo-gate.sh build-lock.sh watch.sh check_ui_tokens.py \
check_changelog_ready.py check_changelog_date.py check_no_parallel_build.sh \
check_room_nullable_default.py select_tests.py gen-readme-highlights.py \
verify_panel_alignment.py check_migrations.py check_active_session.py"

n=0
failed=0

copy() {
  # $1 源文件  $2 目标路径
  if [ ! -f "$1" ]; then
    return 0
  fi
  mkdir -p "$(dirname "$2")" 2>/dev/null
  if cp -p "$1" "$2" 2>/dev/null; then
    n=$((n + 1))
  else
    echo "警告：拷贝失败 $1 → $2" >&2
    failed=$((failed + 1))
  fi
}

# ---- 1. 铁律 ----
copy "$MAIN/CLAUDE.md" "$DOCS_DST/铁律/CLAUDE.md"

# ---- 2. 技能（同时以中文名落到 开发规范/）----
for name in $AIGO_SKILLS; do
  src="$SKILLS_SRC/$name/SKILL.md"
  [ -f "$src" ] || continue
  copy "$src" "$DOCS_DST/技能/$name/SKILL.md"
  copy "$src" "$REPO_DST/$name/SKILL.md"

  case "$name" in
    aigo-dev-workflow)   doc="$SKILL_TO_DOC_dev" ;;
    aigo-build-verify)   doc="$SKILL_TO_DOC_build" ;;
    aigo-release)        doc="$SKILL_TO_DOC_release" ;;
    aigo-upstream-sync)  doc="$SKILL_TO_DOC_upstream" ;;
    aigo-branding)       doc="$SKILL_TO_DOC_branding" ;;
    *)                   doc="" ;;
  esac
  [ -n "$doc" ] && copy "$src" "$DOCS_DST/开发规范/$doc.md"
done

# ---- 3. 脚本 ----
for s in $SCRIPTS; do
  copy "$MAIN/scripts/$s" "$DOCS_DST/开发规范/$s"
done

# ---- 4. 目录说明 ----
cat > "$DOCS_DST/开发规范/README.md" <<'EOF'
# AiGo 开发规范与工具（宿主备份）

本目录是**异地备份**，权威版本在容器内：

| 这里 | 权威版本 |
|---|---|
| `CLAUDE.md`（上一级 `铁律/`） | `/root/.aigo-build/CLAUDE.md` |
| 5 份流程文档 | `~/.nox/skills/aigo-*/SKILL.md` |
| 各脚本 | `/root/.aigo-build/scripts/` |

**不要改这里** —— 改了不会生效，下次同步会被覆盖。
要改就改容器内那份，然后 `aigo sync-rules` 刷新本目录。

**为什么留这份**：容器根 `/` 与宿主 `/storage/emulated` 是两套独立存储。
容器损坏、重置或刷机时，容器内文件全部消失，这里的副本还在。

**最后同步时间**：见各文件的 mtime。`aigo push` 会自动刷新。
EOF

# ---- 5. 仓库内技能副本的说明 ----
cat > "$REPO_DST/README.md" <<'REPOEOF'
# 技能副本（进 git）

这些文件是 `~/.nox/skills/aigo-*/SKILL.md` 的副本，由 `scripts/sync-rules-doc.sh`
在 `aigo push` 时自动生成。**不要直接改这里** —— 下次同步会覆盖。

## 为什么放仓库

本地 `~/.nox/skills/` 在容器家目录，不在 git 里。容器损坏且宿主备份也丢失时，
这 5 个技能就彻底没了。放进仓库后，远端有一份，随时可以 clone 回来。

## 方向

```
~/.nox/skills/aigo-*/SKILL.md       ← 真实来源，永远不动
        ↓ 拷一份
/root/aigo-build/docs/skills/       ← 本目录，进 git
        ↓ git push
       远端仓库                      ← 断网也能靠本地那份正常工作
```

**绝不能反过来**把 `~/.nox/skills/` 做成指向本目录的软链接——
那样断网、或容器重启后忘了 clone，技能就用不了了。

## 怎么改技能

改 `~/.nox/skills/<name>/SKILL.md`，然后：

```bash
sh scripts/sync-rules-doc.sh    # 同步到本目录与宿主备份
git add docs/skills && git commit && git push
```

或直接 `aigo push`（它会自动调 sync-rules）。
REPOEOF
n=$((n + 1))

if [ "$failed" -gt 0 ]; then
  echo "已同步 $n 份（失败 $failed 份）到 $DOCS_DST" >&2
  exit 1
fi

echo "已同步 $n 份到 $DOCS_DST（铁律 + 技能 + 开发规范）"
