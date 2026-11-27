#!/bin/sh
# 检测是否有多个 Gradle 构建在并行跑。
#
# 为什么需要：
#   2026-11-27 连续三次在几秒内启动同参数构建（term-3 / term-4 / term-5），
#   三路互相 `--stop` 打断 daemon，全部失败，白等 11 分钟；
#   更糟的是三路 fd 都指向同一个日志文件，offset 处出现 NUL 空洞，
#   Task 行重复交错，日志彻底不可读。
#
#   根因不是「忘了检查」，而是判断依据错了：
#   我当时看「日志里有没有 BUILD 行」来判断构建是否失败，
#   那行只在构建最后才写，中途看当然是空的 → 误判为失败 → 又启动一个。
#
# 正确判据（本脚本用的）：
#   看进程，不看日志。1 个 wrapper + 1 个 daemon 是正常单构建。
#
# 用法：
#   sh scripts/check_no_parallel_build.sh        # 有人并行时输出提示并退出 1
#   sh scripts/check_no_parallel_build.sh --quiet

set -u
QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1

# [j]ava 的方括号让 grep 排除自身：
# 直接写 "java.*gradle" 会匹配到执行这条命令的 shell（命令行里含该字符串），
# 于是永远报「有构建在跑」。这是另一个踩过的坑。
count=$(ps -ef 2>/dev/null | grep -c "[j]ava.*[G]radleWrapperMain" || true)
daemons=$(ps -ef 2>/dev/null | grep -c "[j]ava.*[G]radleDaemon" || true)

# 正常单构建：1 个 wrapper。daemon 可能复用旧的，不作为判据。
if [ "${count:-0}" -le 1 ]; then
  [ "$QUIET" = "1" ] || echo "无并行构建（wrapper ${count:-0} / daemon ${daemons:-0}）"
  exit 0
fi

echo "检测到 $count 个 Gradle 构建在并行跑（正常应为 1）"
echo "并行构建会互相 --stop 打断，且共用日志文件会写坏内容。"
echo
echo "先看是哪些："
ps -ef 2>/dev/null | grep "[j]ava.*[G]radleWrapperMain" | sed 's/^\([^ ]*[ ]*[^ ]*[ ]*[^ ]*\).*/    \1/' 
echo
echo "处理：等最先启的那个跑完（不要 kill 中间那个，会把共用的 daemon 一起带走）。"
echo "若已确认全部要放弃：sh gradlew --stop 后确认 0 残留，再单跑一次、换新日志文件名。"
exit 1
