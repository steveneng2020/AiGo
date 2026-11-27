#!/bin/sh
# Gradle 构建互斥锁：同一时刻只允许一个构建/测试/推送在跑。
#
# 为什么用锁而不是"记住规则"：
#   2026-11-27 一天之内并发 4 次，每次都造成实际损失：
#     · 上午 3 个出包并发 → 互相 --stop，全败，日志写出 NUL 空洞与重复 Task 行
#     · 下午 2 个子代理 + 1 次主会话 push → 三个 pre-push 单测抢 app/build，
#       两个 daemon 互踢，推送被拦
#     · 子代理甚至把主会话的推送误判成"第三方来源"
#   这些规则当时都已经写在 CLAUDE.md 里（1.5、2.3、5.1），
#   但写下规则和遵守规则是两回事——人会忘，脚本不会。
#
#   所以把约束做成技术强制：拿不到锁就不许跑，并在拒绝时告诉你是谁在占着。
#
# 用法（在 aigo 的 build / test / apk / push 里调用）：
#   sh scripts/build-lock.sh acquire "<描述>"   # 拿锁，失败则退出码 1
#   sh scripts/build-lock.sh release            # 放锁
#   sh scripts/build-lock.sh status             # 查看谁在占
#
# 锁文件内容：pid|启动时间|描述
# 自动失效：持锁进程已不存在（崩溃/被杀），或锁超过 STALE_SEC 秒。

set -u

LOCK="${AIGO_BUILD_LOCK:-/tmp/aigo-build.lock}"
# 锁的最长存活时间。构建本身 6~12 分钟，单测 9 分钟，推送含单测可达 15 分钟，
# 取 30 分钟：正常的都在这之内结束，超过基本可以判定是僵尸锁。
STALE_SEC=1800

now=$(date +%s)

# 锁是否有效：文件存在 + pid 活着 + 未超时
lock_alive() {
  [ -f "$LOCK" ] || return 1
  IFS='|' read -r pid started desc < "$LOCK" 2>/dev/null || return 1
  [ -n "${pid:-}" ] || return 1
  # pid 还活着？
  if kill -0 "$pid" 2>/dev/null; then
    return 0
  fi
  # pid 不在 = 僵尸锁，一律作废。
  #
  # 曾加过「10 秒宽限」想防 pid 复用（进程刚退出、pid 立刻被别的进程占用），
  # 但这个宽限反而制造了更实际的问题：进程崩溃后 10 秒内无法启动新构建，
  # 而且它保护的是一个几乎不会发生的场景（pid 复用需要内核恰好分配同一个号）。
  # 权衡后去掉：宁可偶尔误判 pid 复用（重启一次即可），也不要让崩溃后的锁卡住人。
  return 1
}

case "${1:-}" in
  acquire)
    desc="${2:-未描述}"
    if lock_alive; then
      IFS='|' read -r pid started olddesc < "$LOCK" 2>/dev/null || true
      age=$((now - ${started:-$now}))
      echo "已有构建在跑，拒绝启动第二个：" >&2
      echo "  pid ${pid}  已运行 ${age}s  ${olddesc}" >&2
      echo >&2
      echo "并发构建会互相 --stop 打断，且共用 app/build 导致产物不可信。" >&2
      echo "处理：等它结束；确认要放弃则先 sh gradlew --stop 再删 $LOCK" >&2
      echo "查看详情：sh scripts/build-lock.sh status" >&2
      exit 1
    fi
    # 清掉僵尸锁
    rm -f "$LOCK" 2>/dev/null || true
    # 记录调用方的 pid（PPID），不是本脚本自己的。
    #
    # 关键：本脚本执行完就退出，若记 $$，下一毫秒 kill -0 就失败，
    # 锁立刻变成"僵尸"，等于没有互斥——这是初版的实际缺陷，
    # acquire 之后马上 status 会显示「无构建在跑」。
    # 正确做法是记调用方（aigo 进程），它会一直活到构建结束。
    owner=${PPID:-$$}
    printf '%s|%s|%s\n' "$owner" "$now" "$desc" > "$LOCK"
    echo "已获取构建锁（持有者 pid ${owner}，${desc}）"
    ;;

  release)
    if [ -f "$LOCK" ]; then
      IFS='|' read -r pid _ _ < "$LOCK" 2>/dev/null || true
      # 只释放自己持有的锁，避免误删别人的
      if [ "${pid:-}" = "${PPID:-$$}" ] || [ "${2:-}" = "--force" ]; then
        rm -f "$LOCK"
      fi
    fi
    ;;

  status)
    if lock_alive; then
      IFS='|' read -r pid started desc < "$LOCK" 2>/dev/null || true
      echo "持锁中：pid ${pid}  已运行 $((now - ${started:-$now}))s  ${desc}"
      echo
      echo "进程详情："
      ps -ef 2>/dev/null | grep -E "^[^ ]+ +${pid} " | head -3
      # 同时报告实际 gradle 进程数，帮判断是否有锁外并发
      n=$(ps -ef 2>/dev/null | grep -c "[j]ava.*[G]radleWrapperMain" || true)
      echo
      echo "当前 Gradle wrapper 进程数：${n:-0}（正常应为 0 或 1）"
      [ "${n:-0}" -gt 1 ] && echo "⚠ 进程数大于 1：存在锁外并发，先 sh gradlew --stop"
      exit 0
    fi
    echo "无构建在跑"
    n=$(ps -ef 2>/dev/null | grep -c "[j]ava.*[G]radleWrapperMain" || true)
    if [ "${n:-0}" -gt 0 ]; then
      echo "⚠ 但检测到 ${n} 个 Gradle 进程（无锁，可能来自绕过 aigo 的命令）"
      ps -ef 2>/dev/null | grep "[j]ava.*[G]radleWrapperMain" | awk '{print "  pid " $2}' | head -5
    fi
    ;;

  *)
    echo "用法: sh scripts/build-lock.sh {acquire <描述>|release [--force]|status}" >&2
    exit 2
    ;;
esac
