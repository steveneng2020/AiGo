#!/bin/sh
# 把「AI 协作规则」同步到宿主可见的 docs 下。
#
# 为什么需要：
#   CLAUDE.md 与技能文件都住在容器内（/root/.aigo-build/、~/.nox/skills/），
#   手机上用文件管理器看不到，换设备/交接时容易丢。
#   这里把它们拷一份到 Download/AiGo/AiGo开发/docs/，随仓库副本人眼可查。
#
# 放哪：
#   docs/铁律/CLAUDE.md          ← 仓库根的 CLAUDE.md（AiCode 读的那份）
#   docs/技能/<技能名>/SKILL.md   ← ~/.nox/skills/aigo-*/ 五个技能
#
# 怎么触发：
#   aigo push 推送成功后自动调用（见 cmd_push → cmd_sync_rules）
#   也可手动：aigo sync-rules
#
# 为什么不做双向同步：
#   权威版本在容器内。docs 下这份是只读副本，改它不会被任何工具读取，
#   改了也白改——容易让人误以为生效了。单向覆盖可以避免这种误解。

set -u

MAIN="${AIGO_MAIN:-/root/.aigo-build}"
SKILLS_SRC="${HOME}/.nox/skills"
DOCS_DST="${AIGO_DOCS:-/root/workspace/docs}"

# 只同步 AiGo 自己的技能，不要拷插件开发、设计类等无关技能。
AIGO_SKILLS="aigo-dev-workflow aigo-build-verify aigo-release aigo-upstream-sync aigo-branding"

n=0

# ---- 1. CLAUDE.md ----
if [ -f "$MAIN/CLAUDE.md" ]; then
  mkdir -p "$DOCS_DST/铁律" 2>/dev/null
  if cp -p "$MAIN/CLAUDE.md" "$DOCS_DST/铁律/CLAUDE.md" 2>/dev/null; then
    n=$((n+1))
  else
    echo "警告：CLAUDE.md 拷贝失败" >&2
  fi
fi

# ---- 2. 技能文件 ----
for name in $AIGO_SKILLS; do
  src="$SKILLS_SRC/$name/SKILL.md"
  [ -f "$src" ] || continue
  mkdir -p "$DOCS_DST/技能/$name" 2>/dev/null
  if cp -p "$src" "$DOCS_DST/技能/$name/SKILL.md" 2>/dev/null; then
    n=$((n+1))
  else
    echo "警告：$name 拷贝失败" >&2
  fi
done

echo "已同步 $n 份规则到 $DOCS_DST（铁律 + 技能）"
