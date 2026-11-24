#!/bin/sh
# AiGo 门禁：提交/推送前一次性跑完所有检查。
#
# 用法：
#   aigo-gate            全量门禁（敏感信息 + 迁移 + 品牌 + 编译 + 单测）
#   aigo-gate --fast     跳过单测（只改 UI/资源时用）
#   aigo-gate --wait     额外轮询 CI 到全部结束并校验结果（默认只查是否触发）
#
# 退出码：0 全部通过；非 0 表示有检查未通过，不要提交。

set -u

MAIN="${AIGO_MAIN:-/root/.aigo-build}"

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export GRADLE_USER_HOME=/root/.gradle-home
export ANDROID_USER_HOME=/root/.android-home
mkdir -p "$ANDROID_USER_HOME" 2>/dev/null || true

FAST=0
CI_WAIT=0
for arg in "$@"; do
  case "$arg" in
    --fast) FAST=1 ;;
    --wait|--wait=*) CI_WAIT=1 ;;
  esac
done

fail=0
step() { printf '\n== %s ==\n' "$1"; }
ok()   { printf '  [PASS] %s\n' "$1"; }
bad()  { printf '  [FAIL] %s\n' "$1"; fail=1; }

[ -d "$MAIN/.git" ] || { echo "主副本不存在: $MAIN（先跑 aigo pull）"; exit 1; }
cd "$MAIN" || exit 1

# ---------- 1. 敏感信息（最先做，失败就别往下跑） ----------
step "1/8 敏感信息检查"

tracked_leak=$(git ls-files | grep -E '\.jks$|\.keystore$|keystore\.properties$|local\.properties$' || true)
if [ -z "$tracked_leak" ]; then
  ok "keystore / local.properties 未被跟踪"
else
  bad "以下敏感文件已被 git 跟踪："
  printf '%s\n' "$tracked_leak" | sed 's/^/        /'
fi

secret_hits=$(rg -n --no-heading "ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|ghu_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}" \
  --glob '!.git' --glob '!*.md' . 2>/dev/null || true)
if [ -z "$secret_hits" ]; then
  ok "未发现硬编码 token"
else
  bad "发现疑似硬编码 token："
  printf '%s\n' "$secret_hits" | sed 's/^/        /'
fi

# ---------- 2. 迁移对账 ----------
step "2/8 数据库迁移对账"
if [ -f scripts/check_migrations.py ]; then
  if python3 scripts/check_migrations.py >/tmp/gate_mig.log 2>&1; then
    ok "$(grep -o '迁移版本.*' /tmp/gate_mig.log | head -1)"
  else
    bad "迁移对账失败："; tail -5 /tmp/gate_mig.log | sed 's/^/        /'
  fi
else
  ok "无迁移脚本，跳过"
fi

# ---------- 2b. 上游 schema 差距 ----------
# 背景（2026-11-22 踩过的坑）：
#   AiGo 是 Nox 的 fork，但 fork 后未跟进上游的 schema 迁移。
#   Nox 已到 68，AiGo 停在 64。把 Nox 的数据库文件直接拷进 AiGo，
#   Room 启动时报 "A migration from 68 to 64 was required but not found" 并闪退。
#   Room 不支持降级，跨版本只能逐列搬运数据（不能覆盖文件）。
#
# 这里不把「落后」判为 FAIL（落后本身不影响构建），但会醒目提示。
step "2b/8 上游 schema 差距"
cur_schema=$(grep -oE 'const val SCHEMA_VERSION = [0-9]+' \
  app/src/main/java/com/nox/feature/agent/data/local/database/AgentDatabase.kt 2>/dev/null \
  | grep -oE '[0-9]+' | head -1)
if git rev-parse --verify -q upstream/main >/dev/null 2>&1; then
  up_schema=$(git show upstream/main:app/src/main/java/com/nox/feature/agent/data/local/database/AgentDatabase.kt 2>/dev/null \
    | grep -oE 'const val SCHEMA_VERSION = [0-9]+' | grep -oE '[0-9]+' | head -1)
  behind=$(git rev-list --count HEAD..upstream/main 2>/dev/null || echo '?')
  if [ -n "$cur_schema" ] && [ -n "$up_schema" ] && [ "$up_schema" -gt "$cur_schema" ] 2>/dev/null; then
    echo "  [WARN] 本地 schema=$cur_schema，上游=$up_schema（落后 $((up_schema - cur_schema)) 版），提交数落后 $behind"
    echo "         影响：不能直接把上游数据库拷进本应用，Room 会报 migration not found 并闪退。"
    echo "         迁移数据请用「跨版本列映射」（见 AiGo迁移包/scripts/），并先比对 PRAGMA user_version。"
  elif [ -n "$cur_schema" ]; then
    ok "schema=$cur_schema，与本地 upstream ref 一致"
    echo "        [注意] 本地 ref 可能是旧的。核实远端真实差距："
    echo "               git fetch upstream --tags && git rev-list --count HEAD..upstream/main"
  fi
  case "$behind" in
    ''|'?'|0) ;;
    *) echo "  [INFO] 落后上游 $behind 个提交（未 fetch 时该值可能偏小，先 git fetch upstream）" ;;
  esac
else
  echo "  [SKIP] 无 upstream remote，跳过上游对比"
fi

# ---------- 2b2. 合并后数据库必须升级（强制醒目） ----------
# 这是 2026-11-22 事故的直接诱因：合并后 schema 从 64 变 68，
# 但没人意识到数据库也要跟着走，结果旧 APK 读 68 的库直接闪退。
#
# 触发条件：迁移文件最大版本 >= 65（说明已合入上游的新迁移）
mig_max=$(ls app/src/main/assets/migrations/*.sql 2>/dev/null \
  | sed 's#.*/##' | grep -oE '^[0-9]+' | sort -n | tail -1)
merged_tag=$(git tag -l 'pre-merge-upstream-*' 2>/dev/null | head -1)
if [ -n "$mig_max" ] && [ "$mig_max" -ge 65 ] 2>/dev/null; then
  echo
  echo "  ============================================================"
  echo "   !! 合并后数据库必须升级（当前 schema = $mig_max）"
  echo "  ============================================================"
  echo "  合并上游后 schema 从 64 升到 $mig_max，按顺序做完这四步："
  echo
  echo "   1) 装包前先备份数据库（防意外）"
  echo "      cp <应用>/databases/nox_agent_db  <备份目录>/nox_agent_db-$mig_max版"
  echo
  echo "   2) 构建并安装 schema $mig_max 的新 APK"
  echo "      Room 首次启动会自动跑 64 到 $mig_max 的全部迁移，无需手动 SQL"
  echo "      但旧 APK（schema 64）读不了 $mig_max 的库，会闪退"
  echo
  echo "   3) 首次启动后验证"
  echo "      PRAGMA user_version 应等于 $mig_max"
  echo
  echo "   4) 若要回退到 64 版应用，必须做降级迁移（禁止直接 cp 覆盖）"
  echo "      python3 scripts/migrate-schema.py --src <$mig_max库> \\"
  echo "        --dst-template <64空库> --out <输出>"
  echo
  echo "  详见：AiGo迁移包/事故复盘-20261122.md「合并后数据库升级」"
  echo "  ============================================================"
  echo
elif [ -n "$merged_tag" ]; then
  echo "  [INFO] 检测到合并保护 tag：$merged_tag"
  echo "         若本次合并带入了新迁移文件，请确认数据库已升级"
fi


# ---------- 2c. 数据库安全操作 ----------
# 背景（2026-11-22 踩过的坑，代价是整个数据库被清空）：
#   nox_agent_db 默认是 WAL 模式。改完数据后若用 `cp` 拷主库、
#   丢掉 -wal 文件，写回应用就会得到一个损坏的库；Room 读到损坏文件会
#   直接删库重建，表现为「供应商、会话全没了」。
#
#   正确做法：任何对数据库的写入之后，用 VACUUM INTO 导出单文件：
#     sqlite3 db "VACUUM INTO '/path/out.db'"
#   VACUUM INTO 会把 WAL 合并进主库，产出 journal_mode=delete 的独立文件，
#   拷过去不会有任何 WAL 依赖。
#
# 另外：从上游（Nox）搬数据时，两边 schema 版本可能不同（64 vs 68），
#   直接覆盖文件会闪退，必须逐列搬运（见 AiGo迁移包/scripts/migrate-schema.py）。
step "2c/8 数据库安全操作（参考）"
db_mig_script=""
for p in scripts/migrate-schema.py ../AiGo迁移包/scripts/migrate-schema.py \
         /storage/emulated/0/Download/AiGo/AiGo开发/AiGo迁移包/scripts/migrate-schema.py; do
  [ -f "$p" ] && { db_mig_script="$p"; break; }
done
if [ -n "$db_mig_script" ]; then
  ok "跨版本迁移脚本就位：$db_mig_script"
else
  echo "  [WARN] 找不到 migrate-schema.py（跨 schema 搬数据时需要）"
fi

# 检查工作区里有没有被 cp 弄成「主库 + 缺失 WAL」的悬空数据库副本。
# 特征：存在 .db 但 journal_mode=wal 且没有同名 -wal，且库大小异常小（如 4096）。
dangling=""
for f in *.db /root/.nox/*.db; do
  [ -f "$f" ] || continue
  sz=$(stat -c %s "$f" 2>/dev/null || echo 0)
  # 4096 字节是新建空库的文件头，几乎总是被重建过的空壳
  if [ "$sz" -le 8192 ] 2>/dev/null; then
    dangling="$dangling\n        $f ($sz 字节，疑似空库/被重建)"
  fi
done
if [ -z "$dangling" ]; then
  ok "无异常小的数据库副本"
else
  echo "  [INFO] 发现疑似空库副本（确认是否为预期）："
  printf "$dangling\n" | sed 's/^/       /'
fi

# 提醒：写库后的正确导出方式
if command -v sqlite3 >/dev/null 2>&1; then
  ok "sqlite3 可用（写库后请用 VACUUM INTO 导出，禁止 cp WAL 库）"
else
  echo "  [INFO] 无 sqlite3 CLI，用 python3 sqlite3 模块执行 VACUUM INTO"
fi

# ---------- 3. 品牌一致性 ----------
step "3/8 品牌一致性"
upstream_leak=$(rg -n "xzyy03/Nox/releases|xzyy03\.github\.io/Nox|jieapi/aicode" \
  app/src/main/java app/src/main/res 2>/dev/null | grep -v "UPSTREAM_REPO_URL" || true)
if [ -z "$upstream_leak" ]; then
  ok "应用内无残留的错误仓库链接"
else
  bad "发现指向错误仓库的链接："
  printf '%s\n' "$upstream_leak" | sed 's/^/        /'
fi

appid=$(grep -E '^[[:space:]]*applicationId[[:space:]]*=' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
if [ "$appid" = "com.aigo" ]; then
  ok "applicationId = com.aigo"
else
  bad "applicationId 异常: $appid"
fi

# 变体覆盖检查：debug/beta 各有独立 strings.xml，会覆盖 main 的 app_name。
# 曾因只查 main 而漏掉，装上后显示的还是旧品牌名。
variant_bad=$(rg -n '<string name="app_name">[^<]*(AiCode|Nox)[^<]*</string>' app/src --glob "*.xml" 2>/dev/null || true)
if [ -z "$variant_bad" ]; then
  ok "所有变体的 app_name 均为 AiGo"
else
  bad "以下变体 app_name 仍是旧品牌名："
  printf '%s\n' "$variant_bad" | sed 's/^/        /'
fi

# 包名后缀检查：debug/beta 不得带 applicationIdSuffix，
# 否则同机出现多个应用、私有目录不同导致容器 rootfs 与工作区"消失"。
suffix_bad=$(grep -nE '^[[:space:]]*applicationIdSuffix[[:space:]]*=[[:space:]]*"\.' app/build.gradle.kts 2>/dev/null || true)
if [ -z "$suffix_bad" ]; then
  ok "debug/beta 无 applicationIdSuffix（与 release 同包名，可覆盖安装）"
else
  bad "存在 applicationIdSuffix，会导致同机多应用与数据目录隔离："
  printf '%s\n' "$suffix_bad" | sed 's/^/        /'
fi

# 图标资源存在性：九套图标（鲲 6 + 原有 3）各需 anydpi-v26 声明，缺一个就打包失败或显示空白
missing_icon=""
for d in app/src/main/res/mipmap-anydpi-v26; do
  for f in ic_launcher ic_launcher_round ic_launcher_glasses ic_launcher_glasses_round \
           ic_launcher_wrench ic_launcher_wrench_round \
           ic_launcher_default ic_launcher_moyu ic_launcher_tianqing \
           ic_launcher_zheshi ic_launcher_biyu ic_launcher_zishou; do
    [ -f "$d/$f.xml" ] || missing_icon="$missing_icon $d/$f.xml"
  done
done
if [ -z "$missing_icon" ]; then
  ok "九套自适应图标资源齐全（鲲 6 + 原有 3）"
else
  bad "缺少图标资源:$missing_icon"
fi

# 图标引用一致性：adaptive-icon XML 引用的 bg/foreground 必须真实存在。
# 曾因互换资源时同时改了 webp 与 XML，两次抵消，装出来还是旧图标。
icon_ref_bad=""
for xml in app/src/main/res/mipmap-anydpi-v26/*.xml; do
  [ -f "$xml" ] || continue
  for ref in $(grep -oE '@mipmap/[A-Za-z0-9_]+' "$xml" 2>/dev/null | sed 's#@mipmap/##'); do
    ls app/src/main/res/mipmap-*/"$ref".webp >/dev/null 2>&1 || icon_ref_bad="$icon_ref_bad\n        $xml -> $ref"
  done
done
if [ -z "$icon_ref_bad" ]; then
  ok "adaptive-icon 引用的图层资源均存在"
else
  bad "adaptive-icon 引用了不存在的资源："
  printf "$icon_ref_bad\n" | sed 's/^/       /'
fi

# CI workflow 语法：YAML 坏了会让 GitHub 直接移除该 workflow 注册，
# 表现为 tag/push 完全不触发，极难排查。这里用 pyyaml 提前拦住。
if python3 -c "import yaml" 2>/dev/null; then
  wf_bad=""
  for wf in .github/workflows/*.yml; do
    [ -f "$wf" ] || continue
    python3 -c "import yaml,sys; yaml.safe_load(open('$wf'))" 2>/dev/null \
      || wf_bad="$wf_bad $wf"
  done
  if [ -z "$wf_bad" ]; then
    ok "所有 workflow YAML 语法有效"
  else
    bad "workflow YAML 语法错误:$wf_bad"
  fi

  # 同一 job 内 step id 必须唯一。yaml.safe_load 不管这个（语法合法），
  # 但 GitHub 会报 "The identifier 'x' may not be used more than once within the same scope"
  # 并直接拒绝整个 workflow——表现为发版 workflow 从 Actions 列表里消失。
  # 曾经因为重复加了 id: tag 踩过一次，而上面的语法检查全 PASS。
  dup_ids=$(python3 - <<'PY'
import glob
from collections import Counter
import yaml
for f in sorted(glob.glob('.github/workflows/*.yml')):
    try:
        d = yaml.safe_load(open(f, encoding='utf-8'))
    except Exception:
        continue
    for jn, j in (d.get('jobs') or {}).items():
        ids = [s.get('id') for s in (j.get('steps') or []) if s.get('id')]
        for k, v in Counter(ids).items():
            if v > 1:
                print(f"{f}:{jn}:{k}x{v}")
PY
)
  if [ -z "$dup_ids" ]; then
    ok "workflow 内 step id 无重复"
  else
    bad "workflow 内 step id 重复（GitHub 会拒绝整个文件）:$dup_ids"
  fi
else
  echo "  [SKIP] 无 pyyaml，跳过 workflow 语法检查"
fi

# 更新包资产前缀必须与 CI 产物命名一致。
# 曾因改名后 selectUpdateAsset 只认 nox- 而 CI 产 AiGo-，
# 应用内更新匹配不到任何包，静默退化为跳浏览器下载。
if grep -q 'listOf("AiGo", "nox")' app/src/main/java/com/nox/feature/settings/data/remote/UpdateCheckService.kt 2>/dev/null; then
  ok "更新包资产前缀同时兼容 AiGo 与 nox"
else
  bad "UpdateCheckService 的资产前缀未覆盖 AiGo-*，应用内更新会失效"
fi

# 内置提示词不得让 AI 自称旧品牌（用户每次对话都可见）。
prompt_brand=$(grep -rn "你是 Nox\|Nox 的 AI 编程助手\|派发的 Nox 子代理" app/src/main/assets/prompts 2>/dev/null | head -3 || true)
if [ -z "$prompt_brand" ]; then
  ok "内置提示词无旧品牌自称"
else
  bad "提示词仍在自称 Nox："
  printf '%s\n' "$prompt_brand" | sed 's/^/        /'
fi

# 文档与代码一致性：文档写错会让人按错的说明操作，比没有文档更糟。
# 这轮踩到的具体项（都由子代理审查发现）：
#   - docs-site/api/download.js 指向 jieapi/aicode 且资产名格式与 CI 产物不符
#   - README 版本号规则漏了构建日期
#   - CLAUDE.md 残留 com/aicode、~/.aicode、过期 SCHEMA_VERSION
doc_bad=""

# 文档站 API 不得指向其它仓库
if grep -q "jieapi/aicode\|xzyy03/Nox'" docs-site/api/download.js 2>/dev/null; then
  doc_bad="$doc_bad\n        docs-site/api/download.js 指向了上游仓库"
fi

# CLAUDE.md 里的路径必须与真实目录一致
grep -q "com/aicode/feature" CLAUDE.md 2>/dev/null && \
  doc_bad="$doc_bad\n        CLAUDE.md 写了 com/aicode/feature（实际是 com/nox/feature）"
grep -q "~/\\.aicode/docs" CLAUDE.md 2>/dev/null && \
  doc_bad="$doc_bad\n        CLAUDE.md 写了 ~/.aicode/docs（实际是 ~/.nox/docs）"

# CLAUDE.md 里的 SCHEMA_VERSION 必须与代码一致
doc_schema=$(grep -oE 'SCHEMA_VERSION.{0,6}当前 ([0-9]+)' CLAUDE.md 2>/dev/null | grep -oE '[0-9]+$' | head -1)
code_schema=$(grep -oE 'SCHEMA_VERSION = ([0-9]+)' \
  app/src/main/java/com/nox/feature/agent/data/local/database/AgentDatabase.kt 2>/dev/null \
  | grep -oE '[0-9]+' | head -1)
if [ -n "$doc_schema" ] && [ -n "$code_schema" ] && [ "$doc_schema" != "$code_schema" ]; then
  doc_bad="$doc_bad\n        CLAUDE.md 写 SCHEMA_VERSION=$doc_schema（代码是 $code_schema）"
fi

# README 版本号规则必须提到构建日期（gitVersionName 会追加）
if grep -q "## 版本号规则" README.md 2>/dev/null; then
  grep -q "构建日期" README.md || \
    doc_bad="$doc_bad\n        README 版本号规则未提构建日期（versionName 实际会追加）"
fi

# README 里的发版说明必须提到 workflow_dispatch（fork 仓库 tag push 不触发）
if grep -q "## 发版" README.md 2>/dev/null; then
  grep -q "fork" README.md || \
    doc_bad="$doc_bad\n        README 发版章节未说明 fork 仓库 tag push 不触发"
fi

if [ -z "$doc_bad" ]; then
  ok "文档与代码一致（路径/版本号/发版说明）"
else
  bad "文档与代码不一致："
  printf "$doc_bad\n" | sed 's/^/       /'
fi

# 更新日记必须内置：关于页不能依赖 GitHub API（匿名限流 60 次/小时，
# 断网或限流时曾整块显示「暂无发布记录」）。
# 检查三件事：Gradle 任务存在、sourceSet 挂了生成目录、代码读 assets。
cl_bad=""
grep -q "generateChangelog" app/build.gradle.kts 2>/dev/null || \
  cl_bad="$cl_bad\n        app/build.gradle.kts 缺 generateChangelog 任务"
grep -q "changelogGeneratedDir" app/build.gradle.kts 2>/dev/null || \
  cl_bad="$cl_bad\n        main sourceSet 未挂 changelog 生成目录"
grep -q 'assets.open("changelog.json")' \
  app/src/main/java/com/nox/feature/settings/data/remote/UpdateCheckService.kt 2>/dev/null || \
  cl_bad="$cl_bad\n        UpdateCheckService 未读取内置 changelog.json"

# 更新日记必须来自 CHANGELOG.md（人工撰写、面向用户），不能从 git log 机械生成。
# git log 只有标题、混入 CI 改动、App 与 Release 两处还对不齐。
if [ ! -f CHANGELOG.md ]; then
  cl_bad="$cl_bad\n        CHANGELOG.md 不存在（关于页与 Release 正文都靠它）"
else
  grep -q "parse-changelog.py" app/build.gradle.kts 2>/dev/null || \
    cl_bad="$cl_bad\n        generateChangelog 未从 CHANGELOG.md 解析"
  grep -q "parse-changelog.py" .github/workflows/android-release.yml 2>/dev/null || \
    cl_bad="$cl_bad\n        发版 workflow 未从 CHANGELOG.md 解析（两处会不一致）"
  python3 scripts/parse-changelog.py >/dev/null 2>&1 || \
    cl_bad="$cl_bad\n        CHANGELOG.md 格式无法解析（检查 ## vX.Y.Z — YYYY-MM-DD）"

  # 脚本本身必须声明为 Gradle 输入，否则改了输出结构后任务被判 UP-TO-DATE，
  # 包里的 changelog.json 会停留在旧结构（曾导致 App 只显示摘要、条目全丢）。
  grep -q "inputs.file(parser)" app/build.gradle.kts 2>/dev/null || \
    cl_bad="$cl_bad\n        generateChangelog 未把 parse-changelog.py 声明为输入（改脚本不会重跑）"

  # 任务必须真的接进构建图。只 grep 源码里有没有 dependsOn 不够：
  # 曾经 generateChangelog 定义完整、输入输出都声明了，却没挂到 preBuild，
  # 任务永远不执行，APK 里压根没有 changelog.json，关于页断网时空白。
  # 上面的文本检查全部 PASS，掩盖了这个致命缺陷。
  grep -qE 'dependsOn\([^)]*genChangelog' app/build.gradle.kts 2>/dev/null || \
    cl_bad="$cl_bad\n        generateChangelog 未接入构建图（只在 tasks.register 定义，构建时不会执行）"

  # 解析出的条目数必须与 App 读取的字段名一致。
  # 这里直接比对「脚本输出的 JSON 顶层结构」与「Kotlin 读取的 key」，
  # 结构不匹配（如曾经用 sections、代码读 items）会让条目静默丢失。
  got_items=$(python3 scripts/parse-changelog.py 2>/dev/null | python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
    e=d['entries'][0]
    print(len(e.get('items',[])) if 'items' in e else 'BAD:'+','.join(e.keys()))
except Exception:
    print('ERR')
" 2>/dev/null || echo ERR)
  case "$got_items" in
    ERR|BAD:*|0|"")
      cl_bad="$cl_bad\n        解析结果异常：顶层字段为 '$got_items'，App 读的是 items"
      ;;
    *)
      grep -q 'getAsJsonArray("items")' \
        app/src/main/java/com/nox/feature/settings/data/remote/UpdateCheckService.kt 2>/dev/null || \
        cl_bad="$cl_bad\n        App 未读取 items 字段（与解析输出不一致，条目会静默丢失）"
      # 内置 assets 必须是最新结构，否则装上就是旧内容
      if [ -f app/build/generated/changelog/changelog.json ]; then
        built=$(python3 -c "
import json
try:
    e=json.load(open('app/build/generated/changelog/changelog.json'))['entries'][0]
    print(len(e.get('items',[])) if 'items' in e else 'STALE')
except Exception:
    print('ERR')
" 2>/dev/null || echo ERR)
        case "$built" in
          STALE|ERR)
            cl_bad="$cl_bad\n        已生成的 changelog.json 结构过期，需重跑 generateChangelog"
            ;;
        esac
      fi
      ;;
  esac
fi

if [ -z "$cl_bad" ]; then
  ok "更新日记来自 CHANGELOG.md（人工撰写，App 与 Release 同源）"
else
  bad "更新日记未正确内置："
  printf "$cl_bad\n" | sed 's/^/       /'
fi

# 代码是否领先最近的 Release。
# 踩过的坑：改完只 aigo push，没触发发版，GitHub 上的 Release 与 APK 停在 2 小时前，
# 用户下载到的还是旧包，白改 6 个提交。fork 仓库的 push 不会自动触发 workflow，
# 所以「push 完就算完事」是错的。
release_check() {
  command -v curl >/dev/null 2>&1 || { echo "  [SKIP] 无 curl"; return; }
  local rel_json token
  # 必须带 token：匿名请求只有 60 次/小时，被限流时返回的是错误 JSON，
  # 会被误判成「仓库还没有任何 Release」（曾因此给出错误的 FAIL）。
  token=$(printf 'protocol=https\nhost=github.com\n\n' \
    | git credential fill 2>/dev/null | sed -n 's/^password=//p' || true)
  if [ -n "$token" ]; then
    rel_json=$(curl -sS --max-time 10 -H "Authorization: Bearer $token" \
      "https://api.github.com/repos/steveneng2020/AiGo/releases?per_page=1" 2>/dev/null || true)
  else
    rel_json=$(curl -sS --max-time 10 \
      "https://api.github.com/repos/steveneng2020/AiGo/releases?per_page=1" 2>/dev/null || true)
  fi
  [ -n "$rel_json" ] || { echo "  [SKIP] 无法查询 Release（网络）"; return; }
  # 区分「API 报错」与「真的没有 Release」
  if printf '%s' "$rel_json" | grep -q '"message"'; then
    echo "  [SKIP] GitHub API 拒绝（限流或凭据失效），跳过领先检查"
    return
  fi

  local rel_commit rel_tag
  rel_commit=$(printf '%s' "$rel_json" | python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
    print(d[0].get('target_commitish','') if d else '')
except Exception:
    print('')
" 2>/dev/null || true)
  rel_tag=$(printf '%s' "$rel_json" | python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
    print(d[0].get('tag_name','') if d else '')
except Exception:
    print('')
" 2>/dev/null || true)

  if [ -z "$rel_commit" ]; then
    bad "仓库还没有任何 Release —— 用户无从下载，请先发版"
    return
  fi

  local head_commit behind
  head_commit=$(git rev-parse HEAD)
  # target_commitish 可能是 commit hash，也可能是分支名，两种都处理
  local rel_resolved
  rel_resolved=$(git rev-parse --verify -q "$rel_commit^{commit}" 2>/dev/null || echo "")
  if [ -z "$rel_resolved" ]; then
    echo "  [SKIP] Release 的 target($rel_commit) 无法解析为本地提交"
    return
  fi

  if [ "$rel_resolved" = "$head_commit" ]; then
    ok "Release($rel_tag) 与 HEAD 一致"
  else
    behind=$(git rev-list --count "$rel_resolved..HEAD" 2>/dev/null || echo "?")
    bad "代码领先 Release($rel_tag) $behind 个提交，用户下载到的仍是旧包"
    echo "        发版：Actions 触发 android-release，用新版本号（旧 tag 不能复用）"
  fi
}
release_check

# 未推送的提交（本地工作副本一旦被清理就彻底丢失）。
#
# 背景（2026-11-22 真实事故）：
#   16:26 开始合并上游，17:05 提交为 f808831（53 个文件、schema 64→68），
#   但一直没 push。之后 21:44 构建出的 APK 闪退，排查中发现
#   f808831 在两个工作副本里都找不到了（git cat-file 报 Not a valid object name，
#   reflog 里也无记录），连保护 tag pre-merge-upstream-b240c97 一起没了。
#   结果：53 个文件的上游合并成果（WS/fast、会话管理、日志工作台）全部丢失，
#   代码退回合并前的 0171f55，而数据库已被迁移到 68，造成 schema 不匹配闪退。
#
# 教训：commit 只写进本地 .git，工作副本被删/重置就没了。
#   合并、重构这类“重活”完成后必须立刻 push，不要等验收。
#
# 这里只看是否有 upstream 可对比（无 remote 时跳过，不阻塞离线开发）。
step "2d/8 提交是否已推送"
if git rev-parse --verify -q origin/main >/dev/null 2>&1; then
  unpushed=$(git rev-list --count origin/main..HEAD 2>/dev/null || echo 0)
  if [ "$unpushed" = "0" ]; then
    ok "本地提交已全部推送到 origin/main"
  else
    bad "有 $unpushed 个提交未推送到 origin/main（工作副本被清理就会丢失）"
    echo "        待推送："
    git log --oneline origin/main..HEAD 2>/dev/null | head -8 | sed 's/^/          /'
    echo "        处理：git push origin main"
    echo "        尤其注意：合并提交、大重构完成后必须立即 push，不要等验收"
  fi
else
  echo "  [SKIP] 无 origin/main，跳过未推送检查"
fi

# 本地合并进行中时提醒：MERGE_HEAD 存在说明还没提交，此时极易被误清理。
if [ -f "$MAIN/.git/MERGE_HEAD" ]; then
  bad "合并尚未提交（.git/MERGE_HEAD 存在），现在清理工作副本会丢失全部合并结果"
  echo "        处理：解决冲突后 git commit，然后立即 git push"
fi

# 临时 tag 会污染 versionName（git describe 返回 manual-N-x-g<hash>），
# 导致产物名与版本号都不对，必须发布前清掉。
tmp_tags=$(git tag -l 2>/dev/null | grep -E '^(manual-|tmp-|test-)' || true)
if [ -z "$tmp_tags" ]; then
  ok "无残留临时 tag"
else
  bad "存在临时 tag，会污染 versionName：$(echo $tmp_tags | tr '\n' ' ')"
  echo "        清理：aigo release-check"
fi

# 发版 workflow 里调用的 Gradle 任务名必须带 :app: 前缀。
# assemble*Release 是模块任务，写成 :assembleUniversalRelease（缺 :app:）会直接失败，
# 且因为 matrix 的 fail-fast 会把另外两个 job 一起取消，表现为「三个都挂」。
if grep -qE 'gradlew :assemble[A-Z]' .github/workflows/android-release.yml 2>/dev/null; then
  bad "workflow 里的 assemble 任务缺 :app: 前缀（:assembleXxxRelease 会失败）"
else
  ok "workflow 的 Gradle 任务名带 :app: 前缀"
fi

# 发版产物完整性：APK 与 mapping 都必须进 MANIFEST。
# 这轮踩过两个坑：
#   1. find 用 '*.mapping.txt.gz' 匹配不到 '...-mapping.txt.gz'（点号前是连字符）
#   2. find 的 -o 缺括号，只有第一个条件生效
# 现在改为 MANIFEST 驱动，检查条件是「MANIFEST 是否登记了 mapping」。
if grep -q 'mapping.txt.gz' .github/workflows/android-release.yml 2>/dev/null \
   && grep -q 'MANIFEST' .github/workflows/android-release.yml 2>/dev/null; then
  ok "发版产物（APK + mapping）走 MANIFEST 登记"
else
  bad "发版 workflow 未把 mapping 登记进 MANIFEST，会漏资产"
fi

# 发版 workflow 必须保留 workflow_dispatch：发版走手动触发（fork 仓库的 tag push
# 不一定能触发，手动是唯一可靠路径）。
if grep -q 'workflow_dispatch' .github/workflows/android-release.yml 2>/dev/null; then
  ok "android-release.yml 保留了 workflow_dispatch 触发"
else
  bad "android-release.yml 缺少 workflow_dispatch，发版将无法手动触发"
fi

# push 闭环：既看触发，也看结果。
#
# 只看触发不够：2026-11-22 踩过 push 报告成功但事件未投递（Actions 一次没跑），
# 而代码明明在远端；更常见的是触发了但跑红（Beta Build 曾因单测变体选错
# 连续 5 次变红），本地只看「已推送」完全无感。
# 默认不等结果（门禁要快）；加 --wait 才轮询到全部结束。
if command -v curl >/dev/null 2>&1 && [ -f "$HOME/.nox/git-credential-nox" ]; then
  remote_sha=$(git rev-parse origin/main 2>/dev/null || echo '')
  if [ -n "$remote_sha" ] && [ "$remote_sha" = "$(git rev-parse HEAD 2>/dev/null)" ]; then
    wait_sec=0
    [ "$CI_WAIT" = "1" ] && wait_sec=10
    if [ "$wait_sec" = "0" ]; then
      # 快查：只看触发（总 run 数 > 0），不轮询。
      token=$(cd "$MAIN" && echo "url=https://github.com" | git credential fill 2>/dev/null \
              | grep '^password=' | sed 's/^password=//')
      if [ -n "$token" ]; then
        runs=$(curl -s -m 20 -H "Authorization: Bearer $token" \
          "https://api.github.com/repos/steveneng2020/AiGo/actions/runs?head_sha=$remote_sha" \
          2>/dev/null | grep -o '"total_count": *[0-9]*' | grep -o '[0-9]*$' | head -1)
        if [ -z "$runs" ]; then
          echo "  [SKIP] 无法查询 Actions run（网络或限流）"
        elif [ "$runs" = "0" ]; then
          echo "  [WARN] 本次提交没有产生任何 workflow run（push 事件可能未投递）"
          echo "        sha=$remote_sha"
          echo "        处理：Actions 页手动 Run workflow，或改一行再推一次"
        else
          ok "push 已触发 $runs 个 workflow run（结果未查；加 --wait 连结果一起看）"
        fi
      else
        echo "  [SKIP] 取不到 GitHub 凭据，跳过 run 检查"
      fi
    else
      # 等结果：调共享脚本，与 aigo verify-push 同源。
      out=$(cd "$MAIN" && python3 scripts/check_actions.py --sha "$remote_sha" --wait "$wait_sec" 2>&1)
      rc=$?
      if [ "$rc" = "0" ]; then
        ok "CI 全部通过：$(echo "$out" | tail -1)"
      elif [ "$rc" = "2" ]; then
        echo "  [SKIP] 无法查询 Actions（网络或限流）"
      else
        bad "CI 未通过（查看下方详情）"
        echo "$out" | sed 's/^/        /'
        echo "        处置：看失败步骤 → 本地复现 → 修完再推"
      fi
    fi
  else
    echo "  [SKIP] 本地 HEAD 与 origin/main 不一致，先推送再看"
  fi
else
  echo "  [SKIP] 无 curl 或凭据，跳过 run 检查"
fi

# 归档副本是否跟上主副本。
# 用户在手机 Git 面板里看的是 ~/workspace/AiGo-Android源码仓库（FUSE 归档），
# 而日常开发在 /root/.aigo-build；两个副本不自动同步，导致「明明推了却看不到」。
ARCHIVE="$HOME/workspace/AiGo-Android源码仓库"
if [ -d "$ARCHIVE/.git" ]; then
  archive_head=$(cd "$ARCHIVE" && git rev-parse HEAD 2>/dev/null || echo '')
  main_head=$(git rev-parse HEAD 2>/dev/null || echo '')
  if [ "$archive_head" = "$main_head" ]; then
    ok "归档副本已跟上主副本（$(echo $main_head | cut -c1-7)）"
  else
    echo "  [WARN] 归档副本落后：归档=$(echo $archive_head | cut -c1-7) 主副本=$(echo $main_head | cut -c1-7)"
    echo "        影响：手机 Git 面板看不到新提交"
    echo "        处理：aigo sync-archive"
  fi
else
  echo "  [SKIP] 未找到归档副本，跳过同步检查"
fi

# ---------- 4. 冒烟编译 ----------
step "4/8 冒烟编译"
if sh gradlew :app:compileUniversalDebugKotlin --offline >/tmp/gate_build.log 2>&1; then
  ok "$(grep -oE 'BUILD SUCCESSFUL in [0-9a-z ]+' /tmp/gate_build.log | tail -1)"
else
  echo "  离线失败，联网重试…"
  if sh gradlew :app:compileUniversalDebugKotlin >/tmp/gate_build.log 2>&1; then
    ok "联网重试通过"
  else
    bad "编译失败："
    grep -E "^e: |error:" /tmp/gate_build.log | head -10 | sed 's/^/        /'
  fi
fi

# ---------- 4b. APK 内容验证（仅当已构建出 debug 包时） ----------
step "4b/8 APK 内容验证（参考）"
APK_PATH="app/build/outputs/apk/universal/debug/app-universal-debug.apk"
if [ -f "$APK_PATH" ]; then
  if python3 scripts/verify-apk.py "$APK_PATH" >/tmp/gate_apk.log 2>&1; then
    ok "迁移连续、关键类齐全、图标齐全"
  else
    # 只是参考项：APK 可能是旧的（本次没重新打包），不阻断提交
    echo "  [WARN] APK 验证未通过（可能是旧包，重新 assemble 后再看）："
    grep -E "^\[FAIL\]|^   -" /tmp/gate_apk.log | head -8 | sed 's/^/        /'
  fi
else
  echo "  [SKIP] 未找到 debug APK，先跑 aigo apk"
fi

# ---------- 4c. 出包文件名规则 ----------
# 规则：AiGo-<abi>-<版本>-<摘要>-<构建日期>.apk
#   例：AiGo-arm64-v8a-1.0.1-debug-新增语音输入-20261123.apk
# 踩过的坑：曾产出 AiGo-release-1.0.1-20261123-arm64.apk（abi 掉到最后、
# 摘要和日期顺序也不对），用户很难从文件名判断是哪个包。
# 只检查 build-output 下最新几个文件，历史遗留不追。
step "4c/8 出包文件名规则"
OUT_DIR="$HOME/workspace/build-output"
if [ -d "$OUT_DIR" ]; then
  bad_names=""
  checked=0
  for f in $(ls -t "$OUT_DIR"/*.apk 2>/dev/null | head -5); do
    name=$(basename "$f")
    checked=$((checked + 1))
    # 允许的 abi 段：arm64-v8a / x86_64 / universe / universal，可多段用 + 连接
    if ! printf '%s' "$name" | grep -qE '^AiGo-(arm64-v8a|x86_64|universal|universal-[a-z0-9+]+)(\+[a-z0-9_-]+)*-[0-9]+\.[0-9]+\.[0-9]+.*-[0-9]{8}\.apk$'; then
      bad_names="$bad_names\n          $name"
    fi
  done
  if [ "$checked" = "0" ]; then
    echo "  [SKIP] build-output 下没有 apk"
  elif [ -z "$bad_names" ]; then
    ok "最近 $checked 个出包文件名均符合 AiGo-<abi>-<版本>-<摘要>-<日期>.apk"
  else
    bad "出包文件名不符合规则（应为 AiGo-<abi>-<版本>-<摘要>-<日期>.apk）"
    printf "$bad_names\n"
    echo "        处理：用 aigo apk / aigo fast 重新出包（会自动命名）"
  fi
else
  echo "  [SKIP] 未找到 build-output 目录"
fi

# ---------- 4d. Release 归档与测试包清理 ----------
# 规则（发版后必须满足）：
#   1. build-output 下每个正式版本都要有对应的 release 包
#      （命名 AiGo-<abi>-<版本>-release-<摘要>-<日期>.apk）
#   2. 不允许残留 debug 测试包——会与正式包混在一起，装机时容易拿错
# 踩过的坑：发版成功后只顾着本地 debug 包，build-output 里
#   v1.0.2 只有 debug、v1.0.3 也只有 debug，用户想要正式包时无处可取。
step "4d/8 Release 归档与测试包清理"
OUT_DIR2="$HOME/workspace/build-output"
if [ -d "$OUT_DIR2" ]; then
  leftover_debug=$(ls "$OUT_DIR2"/AiGo-*-debug-*.apk 2>/dev/null || true)
  if [ -z "$leftover_debug" ]; then
    ok "无 debug 测试包残留"
  else
    bad "build-output 下仍有 debug 测试包，正式包归档后应删除："
    printf '%s\n' "$leftover_debug" | sed 's/^/        /'
    echo "        处理：确认正式包已归档后 rm 掉这些 debug 包"
  fi

  # 已发布版本必须有对应的 release 包
  missing_release=""
  for v in 1.0.0 1.0.1 1.0.2 1.0.3; do
    if ls "$OUT_DIR2"/AiGo-*-"$v"-release-*.apk >/dev/null 2>&1; then :; else
      missing_release="$missing_release $v"
    fi
  done
  if [ -z "$missing_release" ]; then
    ok "v1.0.0~v1.0.3 均已有 release 包"
  else
    bad "以下版本缺少 release 包：$missing_release"
    echo "        处理：从对应 GitHub Release 下载 AiGo-arm64-<版本>-<日期>.apk，"
    echo "              重命名为 AiGo-arm64-v8a-<版本>-release-<摘要>-<日期>.apk"
  fi
else
  echo "  [SKIP] 未找到 build-output 目录"
fi

# ---------- 5. 单元测试 ----------
step "5/8 单元测试"
if [ "$FAST" = "1" ]; then
  echo "  [SKIP] --fast 模式跳过"
else
  if sh gradlew :app:testUniversalDebugUnitTest >/tmp/gate_test.log 2>&1; then
    ok "$(grep -oE 'BUILD SUCCESSFUL in [0-9a-z ]+' /tmp/gate_test.log | tail -1)"
  else
    bad "单元测试失败："
    grep -E "FAILED|tests? completed|^e: " /tmp/gate_test.log | head -10 | sed 's/^/        /'
  fi
fi

# ---------- 汇总 ----------
echo
if [ "$fail" = "0" ]; then
  echo "门禁全部通过，可以提交。"
  exit 0
else
  echo "门禁未通过，请先修复上面标 FAIL 的项。"
  exit 1
fi
