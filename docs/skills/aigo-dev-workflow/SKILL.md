---
name: aigo-dev-workflow
description: AiGo 完整开发流程：开工准备、需求确认、改码、门禁、出包、真机验收、敏感信息检查、提交推送、发版、上游同步。含每步判定标准、失败处理与本项目踩过的坑。开始任何 AiGo 开发任务前先读这一篇。
---

# AiGo 开发全流程

本项目的硬约束（铁律）写在仓库根 `CLAUDE.md`，本文只讲**怎么把事做成**。

## 零、开工前 30 秒确认

```bash
aigo status                      # 主副本在不在、分支、改动数
cd /root/.aigo-build && git fetch upstream -q
git log --oneline HEAD..upstream/main | head   # 上游有没有新提交
git status --short               # 干不干净（有脏改动先弄清是什么）
```

**第四条：有没有构建在跑。** 有就等它结束，绝不并行启动第二个：

```bash
ps -ef | grep "[j]ava.*gradle"    # 有输出 = 正在构建
```

不要用 `pgrep -f "GradleWrapperMain"` 判断——它会匹配到你自己的轮询 shell
（命令行里含这个字符串），2026-11-26 曾因此误判「构建在跑」并空等 20 分钟。
方括号 `[j]ava` 的写法能让 grep 排除自身。

四条都对再动手。**主副本不存在**就先 `aigo pull`（约 1 分钟）。

判断当前状态最快的三个信号：

| 信号 | 含义 |
| --- | --- |
| `git describe --tags --always` | 当前版本号形态（有 tag / 领先 N 个提交 / 无 tag） |
| `git rev-list --count HEAD` | versionCode 会取的值 |
| `git tag -l \| grep -E '^(manual-\|tmp-\|test-)'` | 有输出说明 versionName 已被污染，先 `aigo release-check` |

**判断版本历史前必须先 `git fetch --tags origin`。** 只看本地 tag 会得出
「某版本从未发布」的错误结论——2026-11-26 曾据此错误断言 v1.0.3 没发过 Release，
用户拿截图指出后才纠正。

## 一、需求确认（不许跳过）

三条硬规则：

1. **先定位真实代码，再动手**。用 `search` 找按钮文案、开关名、类名。不要凭截图猜文件名。
2. **判断是「UI 限制」还是「底层不支持」**。例：附件按钮置灰，实测 `enqueueAgentRequest` 本就接收附件，所以只是 UI 误拦，一行改动即可。
3. **多个方案时列选项让用户定**，不替他拍板。

需求里提到「像截图那样」时，逐项拆成可验证的验收点写下来，避免做完发现理解偏了。

## 二、改码

### 复用什么（硬规则）

| 场景 | 必须用 | 禁止 |
| --- | --- | --- |
| 输入框 | `core/ui/AppTextField.kt` | 裸写 `TextField` |
| 开关 | `core/ui/AppSwitch.kt` | M3 `Switch` |
| 模态底栏 | `core/ui/AdaptiveModalBottomSheet.kt` | `ModalBottomSheet` |
| 分组卡片 | `SettingsGroup` / `SettingsRow` / `SettingsDivider` | 自造卡片 |
| 左滑删除 | `core/ui/SwipeToDeleteRow.kt` | 手写手势 |
| 间距圆角 | `Spacing` / `Radius` / `semanticColors` | 硬编码 dp 与色值 |

### 文案

用户可见文案一律进 `values/strings.xml`（中）+ `values-en/strings.xml`（英），**禁止 `.kt` 里硬编码中文**。

英文撇号必须转义：`don't` → `don\'t`，否则 `aapt2` 报 `Invalid unicode escape sequence`。

### 更新日记（如需修改生成逻辑）

`assets/changelog.json` 由 Gradle 任务 `generateChangelog` 解析仓库根 `CHANGELOG.md` 生成，三条硬规则：

1. **必须内置**，不能只走 GitHub API：匿名请求限流 60 次/小时，断网或限流时关于页会空白。
2. **必须按重要度排序**，不是提交时间倒序：用户看更新日记是想知道「这版改了什么」，不是读提交日志。顺序由**人工在 `CHANGELOG.md` 里按重要性书写**（新功能 → 体验改进 → 问题修复），`scripts/parse-changelog.py` 只做解析不排序；App 与 Release 正文共用这一份解析结果。
3. **任务必须接进构建图**：只 `tasks.register` 不 `dependsOn` 的话任务永不执行，clean 构建后 APK 里没有 `changelog.json`，关于页断网时空白。门禁会检查 `dependsOn(...genChangelog)`。

分层规则（scope 为 `ci`/`build`/`deps` 时一律下沉，因为用户感受不到）：

| 层 | 内容 |
| --- | --- |
| 0 | `feat`（非工程 scope） |
| 1 | `fix`（非工程 scope） |
| 2 | `feat(ci/build/deps)` |
| 3 | `perf` |
| 4 | `refactor` / `style` |
| 5 | `fix(ci)`、`chore`、`ci`、`build` |
| 6 | `docs` |

更新日记**不从 git log 生成**，而是来自手写的 `CHANGELOG.md`。这直接消除了「上游边界」问题：Nox 的提交不会混进来，也不会出现「品牌独立为 Nox 1.0.0」这类用户看不懂的上游条目。改动更新日记时只改 `CHANGELOG.md`，不要去找 git log 生成逻辑（那套方案已废弃）。

### 更新日记日期冲突时：新增版本，不改历史

> 2026-11-27：v1.0.4 已在 11-26 发布，之后合并上游使提交日期变成 11-27。
> 门禁报「最新版本日期早于最近提交」，而提示写的是「改为 2026-11-27 或 2026-11-26」
> ——11-26 正是当时的错误值，照着改等于没改；改 11-27 又与已发布的 Release 不符。

**规则冲突时唯一正确的做法**：

| 想改的 | 后果 |
| --- | --- |
| 改已发布版本的日期 | 与 GitHub Release 对不上，App 内更新日记也错 |
| 不改硬推 | 门禁拦下，且确实该记 |

**做法**：在旧版本之前插入 `## vX.Y.(Z+1) — <今天>`，写本次改动，旧版本一字不动。
追加后跑 `python3 scripts/gen-readme-highlights.py` 同步 README。

判断「是否已发布」要查 GitHub，不是 `git tag`：

```bash
curl -sS -H "Authorization: Bearer $TOKEN" \
  https://api.github.com/repos/steveneng2020/AiGo/releases \
  | python3 -c "import json,sys; [print(r['tag_name']) for r in json.load(sys.stdin)]"
```

本地 tag 可能是本地打的、或 Release 被删过，不能作为「已发布」的依据。

### 改 UI 必须同步文档

`docs-site/docs/` 是文档唯一事实源。新增页面 / 改交互 / 改文案都要更新；新增文档页还要加进 `.vitepress/config.ts` 侧栏与 `guide/overview.md` 索引。

### 改数据库

1. 递增 `AgentDatabase.kt` 的 `SCHEMA_VERSION`
   （实际值现查：`rg -o "SCHEMA_VERSION = \d+" app/src/main/java/com/nox/feature/agent/data/local/database/AgentDatabase.kt`）
2. 文件式：`app/src/main/assets/migrations/{VERSION}_description.sql`，编号必须连续
3. AutoMigration：加注解，`to == SCHEMA_VERSION`，`from` 衔接文件式最大版本

**一个版本只能二选一**，不能既有文件又有 AutoMigration。

### 三个高频陷阱

| 陷阱 | 表现 | 正确做法 |
| --- | --- | --- |
| 批量替换中文用 `\b` 词边界 | `~/.nox` 被改成 `~/.aigo`，容器启动失败 | 替换后 `rg` 复查路径类内容；真实路径/环境变量前缀先占位保护 |
| 变体资源覆盖 | 改了 `main` 的 `app_name`，装上还是旧名 | 查 `app/src/debug/res/` 与 `app/src/beta/res/`，它们独立覆盖 |
| 图标改了但不变 | 自适应图标的 webp 与 XML 引用同时被换，两次抵消 | 只换一处；改完解包 APK 用 `aapt2 dump resources` 核对实际引用 |

## 三、门禁（提交前，一条命令）

```bash
aigo gate          # 7 组共 24 项检查
aigo gate --fast   # 跳过单元测试
```

检查项会随踩过的坑持续增加，当前覆盖：

| 组 | 检查 |
| --- | --- |
| 敏感信息 | keystore/local.properties 未被跟踪；无硬编码 token |
| 迁移 | 编号连续、SCHEMA_VERSION 一致、已发布未被篡改 |
| 上游 schema | 本地与 `upstream/main` 的 SCHEMA_VERSION 差距（落后时 WARN） |
| 数据库安全 | 跨版本迁移脚本就位；无异常小的空库副本 |
| 品牌 | applicationId、所有变体 app_name、无错误仓库链接、图标资源与引用、更新包前缀、提示词自称 |
| CI 配置 | workflow YAML 语法、任务名带 `:app:`、保留 `workflow_dispatch`、产物走 MANIFEST |
| 更新日记 | 内置 changelog 的三个环节齐全 |
| 构建 | 冒烟编译 + 单元测试 |

**任一步 FAIL 就停下修**，不要带着失败往下走。

### 改了行为必须同步改测试断言

> 2026-11-26：把缓存命中率从整数百分比改成两位小数，只改实现没改断言。
> CI 的 `verify` 阶段 1379 个测试里 5 个失败：

```
CacheHitRateTest > rounds_to_whole_percent FAILED
PanelValueFormatTest > 命中率向下取整而不是四舍五入 FAILED
1379 tests completed, 5 failed
```

> 三个 Release APK 全部构建成功，却卡在测试这关，Release job 被跳过，
> tag 和 Release 都没创建。

**做法**：

- 改任何「用户可见的数值/格式/文案」后，`rg` 一遍对应测试文件，同步更新断言。
- 测试是**规格**，不是障碍：断言反映旧行为时改断言，不要删测试。
- 判断「改的是否只有文案」不够——命中率、耗时、小数位这类都算行为变化。

### 常见误判

| 现象 | 真相 |
| --- | --- |
| `--offline` 报缺依赖 | 是未缓存的依赖，不是代码错，联网重跑 |
| `Unable to initialize metrics` | 无害警告（`~/.android` 不可写），可忽略 |
| 单测全绿但功能坏 | 夹具用了旧数据。例：`selectUpdateAsset` 只认 `nox-` 前缀，测试夹具也用 `nox-`，所以没抓到改名后的失效 |
| **`tail -6` 看日志觉得「没报错」** | 错误在 `What went wrong` 段被截掉了。判断成败用 `grep -E "BUILD \|What went wrong"` |
| **`kspUniversalDebugKotlin` 报 `generated/ksp/.../dagger`** | 被并发构建污染，不是代码错。`rm -rf app/build/generated/ksp app/build/tmp/ksp* app/build/kotlin` 后重跑 |

最后一条最重要：**测试通过不代表功能对，夹具可能和现实脱节**。

## 四、出包

| 场景 | 命令 | 耗时 | 说明 |
| --- | --- | --- | --- |
| 日常验证 | `aigo apk` | 2~6 分钟 | debug 包，无需 R8，可覆盖安装 |
| 要 release 但赶时间 | `aigo fast` | 5~10 分钟 | 跳过 R8，**仅自测** |
| 正式发版 | CI（见第八节） | 约 13 分钟 | 不要本地出正式包 |

### 出包命令固定写法

```bash
cd /root/.aigo-build && sh gradlew :app:assembleArmsoloDebug \
  -PversionCodeOverride=N -PversionNameOverride=1.0.x \
  > /tmp/vcN.log 2>&1
```

两个必守点：

1. **`sh gradlew`**，不能 `./gradlew`（FUSE 上 chmod 不生效）
2. **出包启动后、`BUILD SUCCESSFUL` 之前绝不改 `app/src`**（vc128、vc133 因此作废）

~~`--no-build-cache` 必加~~ —— **2026-11-27 实测推翻**：

| 实测 | 参数 | 产出 versionCode | 耗时 |
| --- | --- | --- | --- |
| A | 不加任何 cache 参数 | 999（正确） | 9m54s |
| B | `--no-build-cache` | 888（正确） | 6m03s |

`-P` 参与 cache key 计算，参数变了缓存自然不命中。旧结论「产出 127 而非 133」
已无法复现，不要再照抄那个参数组合。versionCode 不符时先查并发构建（CLAUDE.md 2.3）。

### 出包后四项核验（缺一不可）

```bash
APK=app/build/outputs/apk/armsolo/debug/app-armsolo-debug.apk

# 1. versionCode 对不对
/opt/android-sdk/build-tools/35.0.0/aapt2 dump badging $APK | head -1

# 2. 源码有没有比包新（应为 0）
find app/src -name '*.kt' -newer $APK | wc -l

# 3. 构建期间源码是否被改（构建前先快照）
#    前：find app/src -name '*.kt' -o -name '*.xml' | xargs stat -c '%Y %n' | sort > /tmp/snap.txt
#    后：同样命令 > /tmp/snap_now.txt && diff /tmp/snap.txt /tmp/snap_now.txt

# 4. 改动真进包了（mtime 不可靠，反查字符串）
python3 -c "
import zipfile
d = zipfile.ZipFile('$APK').read('resources.arsc')
print('新文案命中:', d.count('你的新文案'.encode()))"
```

产物自动复制到 `~/workspace/build-output/`，命名 `<品牌>-<架构>-<versionName>-<摘要>.apk`。

**注意**：`aigo apk` 出的 debug 包 `debuggable=true`，Android 15+ 会弹 16 KB 页对齐兼容性提示。这个提示可以忽略——`libproot*` / `libtermux.so` 是上游预编译产物，不满足 16 KB 对齐，与我们的改动无关。

## 五、自证（AI 自己做，不许推给用户）

**这是本流程最重要的一节。** 之前把「装包看效果」交给用户，导致用户反复当试错员——这是流程失败，不是用户的责任。

本机有 root + `screencap` + `uiautomator`，**界面验证 AI 能自己完成**。

### 能力链（已实测可用）

```bash
# 1. 装包：pm install 读不了 FUSE 路径，必须先拷到 /data/local/tmp
cp ~/workspace/build-output/AiGo-*.apk /data/local/tmp/aigo.apk   # Root
pm install -r /data/local/tmp/aigo.apk                            # Root

# 2. 启动（activity 在 com.nox 包名下，applicationId 是 com.aigo）
am start -n com.aigo/com.nox.MainActivity                          # Root

# 3. 截图并看图（最可靠，UI 有动画时 uiautomator 会报 could not get idle state）
screencap -p /data/local/tmp/s.png
cp /data/local/tmp/s.png ~/workspace/s.png
# 然后用 viewImage 看这张图，自己核对界面

# 4. 需要文本时可 dump（界面静止时才行）
uiautomator dump /data/local/tmp/ui.xml
```

### 交付前三问（缺一不可）

1. **改动点在哪一屏？** 自己点进去，截图，看图确认。
2. **有没有副作用？** 至少看一眼相邻页面（如改了设置项就看设置首页）。
3. **崩溃了吗？** `logcat -d | grep -E "AndroidRuntime|FATAL"` 应为空。

只有这三问都过，才能把包交给用户。**交付说明里要写「我已验证：<具体页面> 显示 <具体内容>」，而不是「请装上看看」。**

### 用户只做最后一道

用户的价值是**判断审美与体验是否合意**，不是替你找 bug、找漏改。把「图标没变」「字还是旧的」这类可自动检测的问题交给他，是浪费他的时间。

### 一定要让用户看的场景

- 主观体验（动效手感、配色是否喜欢）
- 需要真机硬件（相机、NFC、蓝牙）
- 需要长期运行的稳定性

其余一律自己验完再交。

## 六、敏感信息检查（推送前）

```bash
git ls-files | grep -E '\.jks|\.keystore|keystore\.properties|local\.properties'   # 应为空
rg -n "ghp_|github_pat_|ghu_|AKIA|BEGIN [A-Z ]*PRIVATE KEY" --glob '!.git' .      # 只应命中占位文案
```

发现真实凭据：**立即停止推送**，先删除、改写历史，再考虑推送。已经推到公开仓库的凭据必须吊销重签——爬虫抓取速度是秒级。

## 七、提交

```bash
cd /root/.aigo-build
git add -A && git commit -m "feat(scope): 一句话说明"
```

Conventional Commits：`type` ∈ `feat|fix|refactor|docs|style|chore|ci|build|perf|test`，scope 用模块名，句末不加句号。正文写清「改了什么、为什么」。

### 钩子（提交与推送时的自动检查）

| 钩子 | 检查项 |
| --- | --- |
| `commit-msg` | 提交信息格式 |
| `pre-commit` | UI 设计系统、更新日记日期、**README 与 CHANGELOG 同步**、面板右对齐量化、敏感信息、Kotlin 括号、表述规范、工作区干净、出包文件名、APK 含最新源码、出包已归档、**钩子执行位自检** |
| `pre-push` | 未落后远端、待推送无敏感文件、更新日记日期；**改了 `.kt` 则自动跑单测** |

**钩子丢失执行位 = 门禁静默失效。** git 只会打印一行提示然后照常放行：

```
hint: The '.githooks/pre-push' hook was ignored because it's not set as executable.
```

2026-11-26 就因此让 `pre-push` 的单测检查完全没跑，改动照常推上去。
索引里记的是 100755、磁盘上却是 600——检出时权限位没保住。

- 修复：`chmod 755 .githooks/*`
- `pre-commit` 第 8 项会自检三个钩子的执行位，缺失即拦
- 看到 `hook was ignored` 这类 hint 一律当错误处理，**门禁不跑比没有门禁更危险**
- 应急可用 `AIGO_SKIP_TESTS=1` 跳过 `pre-push` 单测，事后必须补跑 `aigo test`

## 八、推送与发版

### 推送（秒级）

```bash
aigo push          # 主副本直推，约 3 秒
```

**不要**再用「tar 打包 → 解包到 FUSE → 从 FUSE 推」的老路——FUSE 元数据慢，那一步要 5~10 分钟且完全多余。

需要宿主文件管理器能看到源码时才 `aigo sync-fuse`（那才是慢的那一步，约 5~10 分钟）。

### 发版

**改完代码只 `aigo push` 是不够的。** push 只更新仓库源码，GitHub 上的 Release 与 APK 不会自动重建——fork 仓库的 push 不触发 workflow。用户看到的一直是上一次发版的产物。

所以每轮功能改动收尾时必须走完整流程：

```
aigo gate → aigo push → 触发发版（新版本号）→ 等 CI → 核对 Release
```

**版本号冲突**：同一个 tag 只能发一次。`v1.0.0` 发过就要用 `v1.0.1`。CI 没有专门的 tag 占用检查，冲突时会在创建 Release 那一步报 API 错误；更早发现的办法是发版前 `git tag -l` 与 `gh release list` 各看一眼。

**核对 Release 是否真的更新**（不要只看 CI 成功）：

```bash
curl -sS -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/steveneng2020/AiGo/releases" \
  | python3 -c "import json,sys; [print(r['tag_name'], r['target_commitish'], r['published_at']) for r in json.load(sys.stdin)]"
```

`target_commitish` 必须是当前 HEAD 的 hash，否则说明 Release 还是旧的。

**删 tag / Release 不会断开 fork 关系**（已核实）：

| 决定 fork 的因素 | 删 tag 后 |
| --- | --- |
| GitHub 元数据 `fork` / `parent` | 不变 |
| `git merge-base HEAD upstream/main` | 不变（tag 不参与历史） |

会断开 fork 的是：点 Leave fork network、删仓库重建、转移仓库、force push 改写历史。
tag 只是指向提交的指针，删它只是删指针。

**本仓库是 Nox 的 fork，GitHub 不为 fork 仓库的 tag push 触发 workflow。** 所以发版走手动触发，由 CI 自己创建 tag 与 Release：

```bash
# GitHub Actions 页面点 Run workflow，或 API
# 输入：release_tag=v1.0.0，flavors=all|arm64|x86_64
```

流程：

```
verify（校验+单测，约 5 分钟）
  → build × N 并行（每个 flavor 一个 runner，约 6~8 分钟）
    → release（汇总产物、建 tag 与 Release，约 15 秒）
```

**发版前必做**：

- [ ] `aigo release-check` 清掉临时 tag（否则 versionName 变成 `manual-2-6-gxxxx`）
- [ ] 4 个 Secrets 已配，`AICODE_KEYSTORE_BASE64` 无换行（5832 字符）
- [ ] `git rev-list --count HEAD` 比上个 tag 大
- [ ] tag 未被占用（`git tag -l` + `gh release list` 自查；冲突时需先删旧 Release 与 tag）

产物命名 `AiGo-<abi>-<versionName>-<summary>.apk`，**summary 只能是 ASCII**：GitHub 的 Release Assets API 会把中文改写成 `-`。中文要点写在 Release 正文首行。

## 九、上游同步（定期）

```bash
cd /root/.aigo-build
git fetch upstream
git merge-tree --write-tree --name-only HEAD upstream/main   # 先预演冲突
git merge upstream/main -m "chore: 同步上游 Nox <短hash>"
aigo gate                                                     # 合并后必须重新验证
```

**禁止 rebase**：versionCode 是提交数，改写历史会让它回退。

合并后按 `aigo-branding` 的清单复查品牌层有没有被上游改回去，`aigo gate` 的品牌组会自动查出一部分。

**绝不把 AiCode 加为 remote**——实测 Nox 与 AiCode 无共同祖先，合并会产生双根历史。

## 十、流程速查

```
开工    aigo status → 拉上游 → 定位代码
改码    复用项目组件 → 中英文案 → 同步文档
门禁    aigo gate          （24 项，任一步 FAIL 就停）
出包    aigo apk           （debug，2~6 分钟）
自证    装包 → 截图 → 自己看图核对 → 查 logcat（不许推给用户）
安全    git ls-files 查 keystore → rg 查 token
推送    aigo push          （3 秒）
发版    aigo release-check → Actions 触发 → 填 release_tag + flavors
同步    git fetch upstream → merge → aigo gate
```

## 十一、踩过的坑（按损失排序）

| 教训 | 代价 | 现在的防线 |
| --- | --- | --- |
| 未确认就 `pm clear com.miui.home`，清空用户桌面 | 356 条图标布局全部丢失，靠 SQLite WAL 才救回 | **禁止未确认执行清除用户数据的命令** |
| 内联 `python3 -c` 写进 YAML `run:` 块，多层引号破坏语法 | workflow 被 GitHub 移除注册，tag/push 完全不触发，排查 2 小时 | 门禁校验所有 workflow YAML 语法 |
| 图标双重互换（webp + XML 同时换） | 看起来改了实际没变，返工 3 次 | 门禁校验 adaptive-icon 引用存在性；只换一处 |
| 改名后 `selectUpdateAsset` 只认 `nox-` | 应用内更新静默失效，退化成跳浏览器 | 门禁校验前缀双兼容；补了新命名的单测 |
| 在 FUSE 目录里编译 | 30 分钟跑不完 | `aigo` 自动切到 `/root/.aigo-build` |
| tar 打包同步后再推送 | 每次多花 5~10 分钟 | `aigo push` 主副本直推 |
| 加 `--no-daemon` "省内存" | 22 秒变 4 分 52 秒 | 不加 |
| 为"稳"关掉配置缓存 | 12 秒变 41 秒 | 保持开启 |
| 更新日记只走 GitHub API | 匿名限流 60 次/小时，断网就空白 | 构建时内置 `assets/changelog.json` |
| 内置 changelog 未设上游边界 | 混入 12 条 Nox 提交，用户以为版本含那些改动 | 以 `upstream/main` 为下界 |
| 更新日记纯时间倒序 | CI 修复压在前面，用户找不到本版功能 | 人工在 `CHANGELOG.md` 里按重要度书写 |
| 全局替换 `com.nox` | 753 文件偏离上游，合并必冲突 | 只改应用层品牌 |
| rebase 整理提交 | versionCode 回退，装不上 | 只用 merge |
| 编译通过就宣称完成 | 用户装上一看没效果 | 必须真机验收 |
| 只 push 不发版 | 用户看到的 Release 停在 2 小时前，白改 | 收尾必须触发发版并核对 `target_commitish` |
| 只查 `main` 的 strings.xml | debug/beta 变体仍显示旧名 | 门禁校验所有变体 |
| 提交前不查 keystore | 私钥可能泄露 | 每次推送前扫描 |

## 十二、效率工具

| 场景 | 用 |
| --- | --- |
| 只读扫描、批量查找、一致性检查 | 子代理 `Explore`，`tier=light` |
| 单文件修改、常规修复 | 主会话直接做，或 `tier=standard` |
| 跨模块重构、疑难排查 | `tier=strong` |
| 需要独立上下文的并行验证 | 派多个子代理，但**不要和编译同时跑**（抢 CPU） |

子代理的价值是**独立上下文 + 并行**，不加速 CPU 密集任务（编译、CI）。
