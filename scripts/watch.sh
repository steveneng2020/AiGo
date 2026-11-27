#!/bin/sh
# 子代理进度看板：子代理写、主会话读，替代「靠主会话记得去 task read」。
#
# 为什么需要（2026-11-27 两次违规）：
#   铁律 8c-0 写了「有进展立即汇报」，但我派完子代理就忘了去读。
#   出包 8 分 36 秒全程零汇报，用户直接质问「子代理去哪里了」。
#   根因不是记性差，而是机制错了：
#     · 子代理在独立上下文里，不会主动向主会话推消息；
#     · 主会话只能主动 task(action="read")，而「主动」这件事全靠自觉；
#     · 我这边的等待往往是阻塞的（sleep）或忙于别的，很容易漏。
#
#   改成「共享文件」就绕开了记性问题：
#     子代理每完成一步 → 写盘 → 主会话随时 cat 一眼就知道进展。
#   读文件比调用 API 便宜得多，也更容易养成习惯。
#
# 用法：
#   主会话创建子代理时，在 prompt 里要求它使用下面两种方式之一：
#
#   1) 写一条进度（子代理侧）：
#      sh scripts/watch.sh post <任务名> <状态> <消息>
#      例：sh scripts/watch.sh post "vc150 出包" running "编译中，第 3 分钟"
#
#   2) 主会话查看（可随时调用）：
#      sh scripts/watch.sh show [任务名]     # 看某任务或全部
#      sh scripts/watch.sh tail [任务名]     # 只看最近 5 条
#
#   3) 清理（任务结束后）：
#      sh scripts/watch.sh clear <任务名>
#
# 状态值约定：running / done / failed

set -u

DIR="${AIGO_WATCH_DIR:-/tmp/aigo-watch}"
mkdir -p "$DIR" 2>/dev/null || true

now() { date '+%Y-%m-%d %H:%M:%S'; }

# 文件名安全化。
# 只替换会破坏路径的字符（斜杠、空格），保留中文——
# 否则「vc150 出包」会变成「vc150_______」，看板一眼看不出是哪个任务。
slug() {
  printf '%s' "$1" | sed 's|/|_|g; s| |_|g' | cut -c1-60
}

case "${1:-}" in
  post)
    task="${2:?需要任务名}"
    state="${3:?需要状态：running/done/failed}"
    msg="${4:-}"
    f="$DIR/$(slug "$task").jsonl"
    # 去重：与上一条「状态 + 消息」完全相同时跳过。
    #
    # 2026-11-27 实测发现每条都被写了两遍（间隔 2~3 秒）——
    # 子代理会把同一条命令执行两次（可能是重试或重复规划）。
    # 看板上重复行会让「有没有新进展」变得难判断，所以在写入侧就去掉。
    # 只比 state+msg，不比时间：真复现同一状态时（如连续两次 running 同一句话）
    # 本来就该算同一件事，合并掉是对的。
    newline="{\"state\":\"$state\",\"msg\":\"$msg\"}"
    if [ -f "$f" ]; then
      lastline=$(tail -1 "$f" 2>/dev/null)
      last_sig=$(printf '%s' "$lastline" \
        | sed -n 's/.*"state":"\([^"]*\)","msg":"\([^"]*\)".*/\1|\(\2\)/p')
      if [ "$last_sig" = "$state|($msg)" ]; then
        echo "已记录：$task → $state（与上一条相同，跳过重复）"
        exit 0
      fi
    fi
    printf '{"time":"%s","state":"%s","msg":"%s"}\n' \
      "$(now)" "$state" "$(printf '%s' "$msg" | sed 's/"/\\"/g')" >> "$f"
    echo "已记录：$task → $state"
    ;;

  show)
    task="${2:-}"
    if [ -n "$task" ]; then
      f="$DIR/$(slug "$task").jsonl"
      [ -f "$f" ] || { echo "无记录：$task"; exit 0; }
      echo "=== $task ==="
      cat "$f"
    else
      found=0
      for f in "$DIR"/*.jsonl; do
        [ -f "$f" ] || continue
        found=1
        echo "=== $(basename "$f" .jsonl) ==="
        tail -3 "$f"
        echo
      done
      [ "$found" = "1" ] || echo "暂无任何子代理进度记录"
    fi
    ;;

  tail)
    task="${2:-}"
    if [ -n "$task" ]; then
      f="$DIR/$(slug "$task").jsonl"
      [ -f "$f" ] && tail -5 "$f" || echo "无记录：$task"
    else
      for f in "$DIR"/*.jsonl; do
        [ -f "$f" ] || continue
        echo "=== $(basename "$f" .jsonl) ==="
        tail -2 "$f"
      done
    fi
    ;;

  clear)
    task="${2:?需要任务名}"
    rm -f "$DIR/$(slug "$task").jsonl"
    echo "已清理：$task"
    ;;

  clear-all)
    rm -f "$DIR"/*.jsonl 2>/dev/null || true
    echo "已清理全部进度记录"
    ;;

  *)
    echo "用法: sh scripts/watch.sh {post <任务> <状态> <消息>|show [任务]|tail [任务]|clear <任务>|clear-all}" >&2
    exit 2
    ;;
esac
