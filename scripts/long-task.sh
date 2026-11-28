#!/bin/sh
# 长任务分段盯进度：把「命令跑完才返回」拆成「每 N 秒输出一次状态」。
#
# 为什么需要它：
#   铁律要求「超过 1 分钟的任务要盯进度并立即汇报」。原手段写的是「派子代理」，
#   但那条路有结构性缺陷——子代理发来的消息只能在主会话的两次工具调用之间
#   被注入，我在生成回复或等用户时收不到。等报告到达时任务往往已经结束，
#   所谓「盯进度」名存实亡。
#
#   2026-11-28 实测：一次 5m49s 的构建，我启动后只发了一条消息就沉默了，
#   中间 5 分钟用户完全不知道进展。
#
#   ​根因不是「没派子代理」，而是「我结束了回合」。只要长任务在同一个工具调用
#   内部等待并按段输出，就不存在失联窗口——这是结构保证，不依赖记性。
#
# 用法：
#   sh scripts/long-task.sh "<命令>" [每段秒数] [日志路径]
#
#   sh scripts/long-task.sh "sh gradlew :app:assembleArmsoloDebug" 100 /tmp/build.log
#
# 输出（每段一行，可直接作为汇报内容）：
#   [09:23:15] 段 1 结束 | 日志 5.2KB | 进程在跑
#   [09:25:08] 段 2 结束 | 日志 12.6KB | 进程在跑
#   [09:26:30] 已结束（耗时 3m15s）| 退出码 0 | BUILD SUCCESSFUL
#
# 退出码：透传被包装命令的退出码（超时除外，见下）。

set -u

CMD="${1:-}"
SEG="${2:-100}"
LOG="${3:-/tmp/long-task.log}"

if [ -z "$CMD" ]; then
  echo "用法：sh scripts/long-task.sh \"<命令>\" [每段秒数] [日志路径]" >&2
  exit 2
fi

# 单段上限 110 秒：宿主工具单次调用通常允许 120s+，
# 留 10 秒余量给启动与收尾，避免执行环境在段中途被切断。
if [ "$SEG" -gt 110 ] 2>/dev/null; then
  SEG=110
fi

start=$(date +%s)
: > "$LOG"

# 把命令丢到后台，日志落盘。用 sh -c 而不是直接 eval，
# 以免命令里的引号被本脚本二次解释。
sh -c "$CMD" > "$LOG" 2>&1 &
pid=$!

elapsed() { echo $(( $(date +%s) - start )); }
size_kb() { echo $(( $(wc -c < "$LOG" 2>/dev/null || echo 0) / 1024 )); }
fmt_ms() {
  t=$1
  if [ "$t" -ge 60 ]; then
    printf '%dm%02ds' $((t / 60)) $((t % 60))
  else
    printf '%ds' "$t"
  fi
}

# 轮询粒度 1 秒，只在「段边界」输出。
#
# 早先是 `sleep $SEG` 一整段，结果命令 1 秒就结束、脚本却白等满 110 秒
# （实测 sleep 1 被拖成 1m50s）。短命令被强行拉长成「长任务」，
# 比不盯进度更糟：既浪费墙钟时间，又让人误以为任务很重。
# 现在每秒查一次存活，命令一结束立刻返回；段边界到了才打印。
seg=0
next_report=$SEG
while kill -0 "$pid" 2>/dev/null; do
  sleep 1
  cur=$(elapsed)
  if kill -0 "$pid" 2>/dev/null && [ "$cur" -ge "$next_report" ]; then
    seg=$((seg + 1))
    printf '[%s] 段 %d 结束 | 日志 %sKB | 进程在跑（已 %s）\n' \
      "$(date '+%H:%M:%S')" "$seg" "$(size_kb)" "$(fmt_ms "$cur")"
    next_report=$((cur + SEG))
  fi
done

# wait 取退出码。注意：不用 `wait $pid`（部分 shell 在被信号打断后返回 127），
# 而是再 kill -0 判一次存活后取 wait 的结果。
wait "$pid"
rc=$?

printf '[%s] 已结束（耗时 %s）| 退出码 %d | %s\n' \
  "$(date '+%H:%M:%S')" "$(fmt_ms "$(elapsed)")" "$rc" "$(size_kb)KB 日志"

# 退出码非 0 时，把日志尾部带出来——这是最容易被忽略、也最该立刻看到的部分。
# 只取末尾 12 行：完整的在日志文件里，汇报里要的是「错在哪」。
if [ "$rc" -ne 0 ]; then
  echo "---- 日志尾部 ----"
  tail -12 "$LOG" 2>/dev/null
  echo "------------------"
  echo "完整日志：$LOG"
fi

exit "$rc"
