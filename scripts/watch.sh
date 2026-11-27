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

# 看板目录。
#
# 2026-11-27 的教训：多个子代理/会话共写同一个 /tmp/aigo-watch，
# 互相 clear-all、互相看到对方的记录，验证时以为「自己的记录消失了」，
# 实际是别人的清理动作。多个子代理各自排查了十几分钟。
#
# 现在按会话隔离：AIGO_WATCH_SESSION 有值时用 /tmp/aigo-watch-<session>，
# 不同主会话/不同批次的子代理天然分开。
# 不设时仍用 /tmp/aigo-watch（单会话场景够用，且兼容已有调用）。
SESSION="${AIGO_WATCH_SESSION:-}"
if [ -n "$SESSION" ]; then
  DIR="${AIGO_WATCH_DIR:-/tmp/aigo-watch-$(printf '%s' "$SESSION" | sed 's|/|_|g; s| |_|g')}"
else
  DIR="${AIGO_WATCH_DIR:-/tmp/aigo-watch}"
fi
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
    # 去重 + 写入必须原子，否则并发时两边都通过检查、各写一行。
    #
    # 2026-11-27 的教训：初版是「先 tail -1 读末行比对，再 >> 追加」，
    # 读取和写入之间有窗口。子代理用两个并发 post 稳定复现了重复行，
    # 并准确指出这是 TOCTOU（time-of-check to time-of-use）竞态。
    #
    # 用 mkdir 做互斥：mkdir 是原子的，目录已存在就失败。
    # 不用 flock——BusyBox 的精简 sh 不一定带。
    #
    # 为什么还要去重：子代理会把同一条命令执行两遍（重试/重复规划），
    # 看板上重复行会让「有没有新进展」难判断。
    # 只比 state+msg 不比时间：连续两次同状态同消息本就该算一件事。
    lockdir="$DIR/.lock-write"
    tries=0
    while ! mkdir "$lockdir" 2>/dev/null; do
      tries=$((tries + 1))
      # 5 秒还没拿到锁，说明有僵尸锁（进程被杀没清理），强制接管
      if [ "$tries" -gt 50 ]; then
        rm -rf "$lockdir" 2>/dev/null || true
        break
      fi
      sleep 0.1
    done

    # 拿到锁后重新读末行——这保证了「读」与「写」之间没有别人插进来
    skip=0
    if [ -f "$f" ]; then
      lastline=$(tail -1 "$f" 2>/dev/null)
      last_sig=$(printf '%s' "$lastline" \
        | sed -n 's/.*"state":"\([^"]*\)","msg":"\([^"]*\)".*/\1|\2/p')
      if [ "$last_sig" = "$state|$msg" ]; then
        skip=1
      fi
    fi

    if [ "$skip" = "1" ]; then
      rmdir "$lockdir" 2>/dev/null || true
      echo "已记录：$task → $state（与上一条相同，跳过重复）"
      exit 0
    fi

    printf '{"time":"%s","state":"%s","msg":"%s"}\n' \
      "$(now)" "$state" "$(printf '%s' "$msg" | sed 's/"/\\"/g')" >> "$f"
    rmdir "$lockdir" 2>/dev/null || true
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
    # 安全护栏：默认只删已结束的任务，避免误伤正在运行中的子代理。
    #
    # 2026-11-27 的教训：清理测试时用 `rm -f /tmp/aigo-watch/*.jsonl`，
    # 把另一个还在跑的验证子代理的 3 条 running 记录一起删了，
    # 它汇报时看到「只写进去 1 条」，花了十几分钟排查是不是自己的问题。
    # 无差别的 clear-all 在多任务并发时必然误伤。
    removed=0
    kept=0
    for f in "$DIR"/*.jsonl; do
      [ -f "$f" ] || continue
      # 最后一条是 done/failed 才认为已结束
      last_state=$(tail -1 "$f" 2>/dev/null \
        | sed -n 's/.*"state":"\([^"]*\)".*/\1/p')
      if [ "$last_state" = "done" ] || [ "$last_state" = "failed" ]; then
        rm -f "$f"
        removed=$((removed + 1))
      else
        kept=$((kept + 1))
      fi
    done
    echo "已清理 ${removed} 个已结束任务"
    if [ "$kept" -gt 0 ]; then
      echo "保留 ${kept} 个仍在进行中的任务（要强制全删用 clear-all --force）"
    fi
    ;;

  clear-all-force)
    rm -f "$DIR"/*.jsonl 2>/dev/null || true
    echo "已强制清理全部进度记录"
    ;;

  *)
    echo "用法: sh scripts/watch.sh {post <任务> <状态> <消息>|show [任务]|tail [任务]|clear <任务>|clear-all}" >&2
    exit 2
    ;;
esac
