---
name: aigo-upstream-sync
description: AiGo 与上游 Nox 保持同步的完整 SOP：remote 布局、为什么禁用 rebase、merge 流程、冲突预演、rerere、上游改动与我方改动的重叠分析。上游发新版或需要跟进上游修复时使用。
---

# AiGo ↔ 上游 Nox 同步

## 零、三层二改链（先理解这个，再动手）

```
jieapi/aicode  ──(Nox 二次开发)──▶  xzyy03/Nox  ──(AiGo 二次开发)──▶  steveneng2020/AiGo
   原始项目                          直接上游（fork 源）                  本项目
```

**硬规则：任何时候、做任何开发都不能破坏 AiGo 与 Nox 的 fork 关系。**

这决定了「上游的上游（AiCode）有更新怎么办」的答案：

- ✅ **正规路径**：等 Nox 合入 AiCode 的改动，再从 Nox 合并到 AiGo。这条链完整、可追溯、不破坏 fork。
- ❌ **禁止做法**：直接把 AiCode 作为 remote 合并进 AiGo。实测 `git merge-base HEAD AiCode/main` **返回空**——Nox 的历史是从 `e275f7a chore: 品牌独立为 Nox 1.0.0` 重新开始的，与 AiCode **没有共同祖先**。把无共同祖先的历史 merge 进来，会产生一个双根历史，`git describe --tags` 与 `rev-list --count` 的结果失去意义，versionCode 体系直接崩掉，与 Nox 的 fork 关系也随之名义化（GitHub 上的 fork 标记虽还在，但历史已不可对齐）。

之所以不能用 cherry-pick 绕过：AiCode 的提交与 Nox 的代码结构已显著分叉（品牌、包名、UI 层都有改动），逐提交摘取会产生大量冲突且难以验证，成本远高于等 Nox 合入。

## 一、仓库拓扑

| remote | 地址 | 用途 |
| --- | --- | --- |
| `origin` | `https://github.com/steveneng2020/AiGo.git` | 自己的仓库，推送目标 |
| `upstream` | `https://github.com/xzyy03/Nox.git` | 只读，拉取上游更新 |

**不要添加 AiCode 作为 remote。** 只保留上面两个，这是维持 fork 关系的基础。

首次配置（新克隆时需要）：

```bash
cd /root/.aigo-build
git remote add upstream https://github.com/xzyy03/Nox.git
git config rerere.enabled true
git config core.hooksPath .githooks
git config core.createObject rename   # workspace 是 FUSE 层，不支持 hardlink
```

## 二、绝对不能 rebase

本项目 `versionCode` 由 `git rev-list --count HEAD` 计算（`app/build.gradle.kts` 的 `gitCommitCount()`），`versionName` 由 `git describe --tags` 计算。

rebase / squash 会重写历史、改变提交数，导致：

- versionCode 回退 → 新包无法覆盖安装旧包；
- `.github/workflows/android-release.yml` 的 `Verify versionCode monotonic` 步骤直接失败，发不出版；
- 所有已发布的 tag 与提交的对应关系错乱。

**同步一律用 merge。** 同理，也不要对 `main` 做 `git push --force`。

## 三、标准同步流程

```bash
cd /root/.aigo-build

# 1. 确保本地干净
git status --short

# 2. 拉上游
git fetch upstream

# 3. 看上游带来什么
git log --oneline HEAD..upstream/main
git diff --stat HEAD...upstream/main

# 4. 先预演冲突（不落地，安全）
git merge-tree --write-tree --name-only HEAD upstream/main
#   只输出一个 tree hash      → 可干净合并
#   额外列出文件名            → 这些文件会冲突

# 5. 正式合并
git merge upstream/main -m "chore: 同步上游 Nox <上游短hash>"

# 6. 验证（见 aigo-build-verify 技能）
sh gradlew :app:compileUniversalDebugKotlin

# 7. 推送
git push origin main
```

合并信息建议写成 `chore: 同步上游 Nox 289c434`，便于日后追溯同步到哪个上游版本。

## 四、冲突处理原则

冲突几乎只会出现在「我方改过的文件」上。按改名方案，我方改动集中在约 10 个品牌层文件（见 `aigo-branding` 技能），其余 700+ 文件与上游完全一致，理论上不会冲突。

处理冲突时的判断顺序：

1. **上游的改动是功能/修复** → 接受上游内容，然后把自己的品牌值重新套回去。
2. **上游只是重命名或重构了该文件** → 接受上游，在新位置重新套品牌值。
3. **上游新增了硬编码 `com.nox` 或仓库地址** → 这是改名方案唯一的长期维护点。合并后跑一次检查：

```bash
cd /root/.aigo-build
rg -n 'com\.nox"' app/build.gradle.kts app/src/main/java --glob '!*.md' | head -20
rg -n 'xzyy03/Nox' app/src/main/java | head -20
```

预期只剩源码包 `com.nox` 的 `package` / `import` 语句（这是刻意保留的内部命名空间），不应出现 `applicationId = "com.nox"` 或上游仓库链接。

4. **同一段代码双方都改** → 保留我方语义（品牌、包名），吸收上游逻辑。

`rerere` 已开启，同类冲突第二次出现会自动套用上次的解法，但**套用后仍要人工核对一遍**。

## 五、上游同步后必须复查的点

上游每次合并后按此清单过一遍：

| 检查项 | 原因 |
| --- | --- |
| **`git fetch --tags` 后再判断版本历史** | 只查本地 tag 会误判「某版本从未发布」。2026-11-26 曾因此错误断言 v1.0.3 没发过 Release，实际早发了 |
| `git rev-list --count HEAD` 是否递增 | versionCode 单调 |
| `applicationId` 仍是 `com.aigo` | 上游可能改动 defaultConfig |
| `UpdateCheckService.kt` 的 `GITHUB_RELEASES_API` 指向本仓库 | 否则检查更新会拉上游的包 |
| `AboutSection.kt` 的仓库/License 链接 | 品牌一致性 |
| `SettingsViewModel.kt` 的 `RELEASE_APPLICATION_ID` | 应用内安装校验依赖它 |
| 新增的 `values/strings.xml` 文案是否要跟着改应用名 | 上游会持续加文案 |
| `NOTICE` 的修改声明是否仍准确 | GPL-3.0 义务 |

## 六、不要为了「干净」而做的事

- **不要删除上游的 workflow 文件**。`docs-deploy.yml`、`sync-gitcode.yml`、`sync-container-images.yml`、`sync-models.yml` 对自己无用，但删掉会让上游合并必然冲突。正确做法：在 GitHub 仓库的 Actions 页面把对应 workflow 点 **Disable**，代码零改动。
- **不要在源码里全局替换 `com.nox` → `com.aigo`**。原因是这会让**全部源码文件**（2026-11 实测 753 个 `com.nox` 引用文件，数量随上游增长）全部偏离上游，每次同步都得重放改名脚本并逐个解决冲突，且 Room schema 目录名、AIDL 包路径都要跟着动。品牌层改名已经能满足「装出来是 AiGo」的目标。
- **不要改 `versionCode` 的生成方式**。CI 有单调性校验，改了要同步改 workflow。

## 七、跟进节奏建议

上游提交很频繁（实测一天多次）。建议：

- 平时每 1~2 周同步一次，避免一次积压大量冲突；
- 上游发 `v*` tag 时同步一次，跟上大版本；
- 自己正在做大改动时先不同步，等功能落地并验证后再合，减少冲突面。
