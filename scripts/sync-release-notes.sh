#!/bin/sh
# 把 CHANGELOG.md 的正文同步到已发布 Release（PATCH body）。
#
# 为什么需要：
#   Release 正文是发版时从 CHANGELOG.md 读的快照。改了 CHANGELOG 之后，
#   已经发布的 Release 仍是旧文案，会出现「App 关于页显示新版、网页显示旧版」
#   的矛盾。本脚本用 API 把已发布 Release 的正文改成当前 CHANGELOG 的内容。
#
# 只改 body，不动 tag / 资产 / 标题 / 发布日期——历史事实不变，
# 变的只是「说明写得是否清楚」。GitHub 会记录编辑时间。
#
# 用法：
#   sh scripts/sync-release-notes.sh              # 预览（默认，不写入）
#   sh scripts/sync-release-notes.sh --apply      # 实际写入
#   sh scripts/sync-release-notes.sh --apply v1.0.5   # 只同步指定版本
#
# 退出码：0=全部成功（或预览完成）；非 0=有版本失败

set -u

APPLY=0
ONLY=""
for arg in "$@"; do
  case "$arg" in
    --apply) APPLY=1 ;;
    v*) ONLY="$arg" ;;
  esac
done

REPO="steveneng2020/AiGo"
CREDS="${HOME}/.nox/git-credentials"
API="https://api.github.com/repos/${REPO}"

[ -f "$CREDS" ] || { echo "凭据文件不存在：$CREDS" >&2; exit 1; }

# 解码凭据（与 git-credential-nox 同一算法），token 不回显
raw=$(cat "$CREDS" 2>/dev/null)
rev=$(printf '%s' "$raw" | awk '{for(i=length($0);i>0;i--)printf "%s",substr($0,i,1)}')
content=$(printf '%s' "$rev" | base64 -d 2>/dev/null)
line=$(printf '%s\n' "$content" | awk -v h="@github.com" 'substr($0,length($0)-length(h)+1)==h{print;exit}')
[ -n "$line" ] || { echo "凭据里没有 github.com 条目" >&2; exit 1; }
ut=${line#*://}; ut=${ut%@*}; TOKEN=${ut#*:}
[ -n "$TOKEN" ] || { echo "token 为空" >&2; exit 1; }

# 取所有 Release 的 id 与 tag（最多 100 个，够用）
tmp=$(mktemp)
code=$(curl -s --max-time 30 -H "Authorization: Bearer ${TOKEN}" \
  -H "Accept: application/vnd.github+json" \
  "${API}/releases?per_page=100" -o "$tmp" -w '%{http_code}')
if [ "$code" != "200" ]; then
  echo "拉取 Release 列表失败（HTTP ${code}）" >&2
  rm -f "$tmp"; exit 1
fi

fail=0
# 输出「id tag」两列供循环
python3 - "$tmp" <<'PY' > /tmp/rel_list.txt
import json, sys
d = json.load(open(sys.argv[1]))
for r in d:
    print(r["id"], r["tag_name"])
PY
rm -f "$tmp"

while read -r rid rtag; do
  [ -n "${rtag:-}" ] || continue
  if [ -n "$ONLY" ] && [ "$rtag" != "$ONLY" ]; then
    continue
  fi

  # 用与发版 CI 完全相同的解析脚本生成正文，保证三处同源
  notes=$(python3 scripts/parse-changelog.py --markdown-for "$rtag" 2>/dev/null || true)
  if [ -z "$notes" ]; then
    echo "跳过 ${rtag}：CHANGELOG.md 里没有这个版本"
    continue
  fi

  if [ "$APPLY" = "0" ]; then
    echo "=== ${rtag}（预览，${#notes} 字节）==="
    printf '%s\n' "$notes" | head -6
    echo "  …"
    echo
    continue
  fi

  # 正文含换行与中文标点，必须用 python 组 JSON，不能手拼
  body=$(printf '%s' "$notes" | python3 -c 'import json,sys; print(json.dumps({"body": sys.stdin.read()}))')
  out=$(mktemp)
  code=$(printf '%s' "$body" | curl -s --max-time 30 -X PATCH \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "Content-Type: application/json" \
    --data-binary @- \
    "${API}/releases/${rid}" -o "$out" -w '%{http_code}')
  rm -f "$out"
  if [ "$code" = "200" ]; then
    echo "✓ ${rtag} 正文已更新"
  else
    echo "✗ ${rtag} 失败（HTTP ${code}）" >&2
    fail=1
  fi
done < /tmp/rel_list.txt

[ "$APPLY" = "0" ] && echo "（以上为预览。加 --apply 实际写入）"

exit "$fail"
