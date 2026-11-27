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
