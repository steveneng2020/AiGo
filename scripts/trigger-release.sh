#!/bin/sh
# 触发 Android Release workflow（workflow_dispatch）。
#
# 为什么要有这个脚本：
#   本仓库是 Nox 的 fork，GitHub 对 fork 仓库的 tag push 不启动 workflow，
#   所以发版只能手动触发。容器里没有 gh 命令，只能调 REST API。
#
# 凭据来源与 git 完全相同（~/.nox/git-credentials，反转 + base64），
# 但**绝不回显**：token 只在本进程内用于 Authorization 头，
# 不写日志、不落文件、不进输出。
#
# 用法：
#   sh scripts/trigger-release.sh v1.0.6            # 默认 all + 非预发布
#   sh scripts/trigger-release.sh v1.0.6 arm64      # 只出真机包
#   sh scripts/trigger-release.sh v1.0.6 all true   # 标为预发布
#
# 退出码：0=已触发；非 0=失败（含原因）

set -u

TAG="${1:-}"
FLAVORS="${2:-all}"
PRERELEASE="${3:-false}"

if [ -z "$TAG" ]; then
  echo "用法：sh scripts/trigger-release.sh <tag> [all|arm64|x86_64] [true|false]" >&2
  exit 2
fi

REPO="steveneng2020/AiGo"
WORKFLOW="android-release.yml"
CREDS="${HOME}/.nox/git-credentials"

[ -f "$CREDS" ] || { echo "凭据文件不存在：$CREDS" >&2; exit 1; }

# 解码凭据（与 git-credential-nox 同一算法）
raw=$(cat "$CREDS" 2>/dev/null)
[ -n "$raw" ] || { echo "凭据文件为空" >&2; exit 1; }
rev=$(printf '%s' "$raw" | awk '{for(i=length($0);i>0;i--)printf "%s",substr($0,i,1)}')
content=$(printf '%s' "$rev" | base64 -d 2>/dev/null)
[ -n "$content" ] || { echo "凭据解码失败" >&2; exit 1; }

# 取 github.com 那一行的 token 部分
# 行形如 https://user:token@github.com，取结尾恰好是 @github.com 的那行。
# 用 awk 算长度而非 grep，避免 token 里含正则元字符时误判。
host="@github.com"
line=$(printf '%s\n' "$content" | awk -v h="$host" 'substr($0, length($0) - length(h) + 1) == h { print; exit }')
[ -n "$line" ] || { echo "凭据里没有 github.com 条目" >&2; exit 1; }
ut=${line#*://}
ut=${ut%@*}
TOKEN=${ut#*:}

[ -n "$TOKEN" ] || { echo "token 为空" >&2; exit 1; }

BODY=$(cat <<EOF
{"ref":"main","inputs":{"release_tag":"${TAG}","prerelease":${PRERELEASE},"flavors":"${FLAVORS}"}}
EOF
)

# --silent 抑制进度，-o 把响应体丢走（响应里可能含 workflow 元数据，不必看）
# -w 只取 HTTP 状态码，这样既能判断成败又不泄露任何凭据。
code=$(curl -s -o /dev/null -w '%{http_code}' \
  -X POST \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  -H "Content-Type: application/json" \
  -d "$BODY" \
  "https://api.github.com/repos/${REPO}/actions/workflows/${WORKFLOW}/dispatches")

case "$code" in
  204)
    echo "已触发发版：${TAG}（flavors=${FLAVORS}, prerelease=${PRERELEASE}）"
    exit 0
    ;;
  401|403)
    echo "触发失败（HTTP ${code}）：token 无权限或已过期。" >&2
    echo "需要的权限：该仓库的 Actions: write（细粒度 token）或 repo（经典 token）。" >&2
    exit 1
    ;;
  404)
    echo "触发失败（HTTP 404）：workflow 或仓库不存在，或 token 对该仓库无访问权。" >&2
    exit 1
    ;;
  422)
    echo "触发失败（HTTP 422）：输入参数不合法（tag 格式？flavors 取值？）。" >&2
    exit 1
    ;;
  *)
    echo "触发失败（HTTP ${code}）。" >&2
    exit 1
    ;;
esac
