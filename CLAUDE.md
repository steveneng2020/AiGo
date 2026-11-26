# CLAUDE.md

本仓库的 AI 协作规则（Claude Code、AiCode 等 AI 编程助手均读取本文件），优先级高于默认通用规则。

## 铁律（违反即返工，不得以"效率"为由跳过）

### 0. 最高优先级：绝不破坏与上游 Nox 的 fork 关系

**这是本项目的存续前提，优先级高于本文件其余所有条款。**

禁止的操作（无一例外）：

- 在 GitHub 仓库设置里点 **"Leave fork network"** 脱离 fork
- 删除仓库后重新创建（fork 关系会丢失）
- 把仓库转移给他人或其他组织
- 将 `origin` 改为非 fork 来源，或把上游历史整体替换
- 任何 `git push --force` / 历史重写，导致与上游的 merge-base 不可追溯

#### 判据：动手前先问「这会不会断开 fork 关系」

以下任一情形出现，**先停下来确认，不要执行**：

| 可能出现的场景 | 为什么危险 |
| --- | --- |
| 想 `git filter-branch` / `filter-repo` 清理历史 | 重写历史 → merge-base 丢失 |
| 想 `git push --force` 或 `--force-with-lease` 到 main | 同上，且会冲掉别人的提交 |
| 想删除 `.git` 重新 `git init` | 直接断根，fork 关系永久丢失 |
| 想把上游当普通 remote 直接 merge 进无共同祖先的历史 | 产生双根历史，`git describe` / `rev-list --count` 失效 |
| 想为了「干净」重建仓库 | fork 关系随仓库删除而丢失 |
| 想改成 `git remote set-url origin <别的仓库>` | 推送目标一旦偏离，fork 网络里就对不上了 |

**遇到 GitHub 对 fork 的限制时，只能找绕过办法，不能解散 fork。** 已知限制与对策：

| fork 限制 | 正确对策 |
| --- | --- |
| fork 仓库的 push / tag push 不触发 workflow | 改用 `workflow_dispatch` 手动触发，由 CI 自己创建 tag 与 Release |
| fork 的 workflow 默认禁用 | 在 Actions 页面点一次授权，或用 API `enable` |
| fork 的 PR 不带 Secrets | 在 Actions 设置里单独放行，或改为在 fork 内直接构建 |
| 历史里混入了不该有的提交 | **不重写历史**；新提交回退内容，保留完整历史 |
| 提交信息写错 | `git commit --amend` 只对**未推送**的提交安全；已推送的不改 |

**禁止把 AiCode 加为 remote。** 它（`jieapi/aicode`）与 Nox 没有共同祖先，
merge 进来会产生双根历史，versionCode 体系与 fork 关系同时失效。
只能等 Nox 合入 AiCode 的改动，再从 Nox 同步过来。

### 1. 遇到问题必须找到根因，禁止猜测与退缩

- **禁止**在未定位根因前就改代码"试试看"。先用证据确认：读日志、查 API 返回值、对比实际文件、验证假设。
- **禁止**因为难就降低目标或绕过问题。例如「tag 不触发」不能以"fork 就是这样"收场，必须查清是配置、语法还是平台限制，并给出可行方案。
- **禁止**在同一个问题上反复试错超过两轮。第二轮失败就停下来重新做假设，从最基础的证据开始验证。
- 定位到根因后，要**把根因和验证方式写进注释与文档**，避免同类问题再次发生。
- 汇报时必须区分「已证实」与「推测」。推测要明说，不能当成结论。

### 2. 交付前必须自己验证，不许把验收推给用户

**编译通过 ≠ 完成。** 用户不该成为试错员或挑错员——找 bug、找漏改、验界面是 AI 的活。

本机具备完整的自助验收能力（已实测）：

```bash
# 装包（pm install 读不了 FUSE 路径，必须先拷到 /data/local/tmp）
cp ~/workspace/build-output/AiGo-*.apk /data/local/tmp/aigo.apk
pm install -r /data/local/tmp/aigo.apk

# 启动（activity 在 com.nox 包名下，applicationId 是 com.aigo）
am start -n com.aigo/com.nox.MainActivity

# 截图 → 用 viewImage 自己看
screencap -p /data/local/tmp/s.png && cp /data/local/tmp/s.png ~/workspace/s.png
```

交付前必做三件事：

1. **点进改动的那个界面**，截图，自己看图核对内容
2. **看一眼相邻页面**，确认没有副作用
3. **查崩溃**：`logcat -d | grep -E "AndroidRuntime|FATAL"` 应为空

交付说明必须写「我已验证：<页面> 显示 <内容>」，**不许写「请装上看看」**。

例外（只有这三种可以让用户看）：主观审美偏好、需要真机硬件（相机/NFC/蓝牙）、长期运行稳定性。

### 3. 输出物必须分类存放在指定目录

**唯一允许的项目输出根目录**：

```
/storage/emulated/0/Download/AiGo/AiGo开发/        （容器内等价：~/workspace/）
├── AiGo-Android源码仓库/     完整 git 仓库
├── build-output/             所有 APK / 构建产物
├── docs/                     文档、截图、说明
└── keystore/                 签名材料（含口令，绝不入库）
```

- **禁止**把任何产物、文档、临时文件写到 `/storage/emulated/0/Download` 根目录或其它位置。
- 新类别输出先在 `AiGo开发/` 下建子目录再放，不要平铺到根。
- 该目录在容器内即 `~/workspace`（PRoot 绑定挂载，同一份文件），两边路径等价。

### 4. 构建必须离开 FUSE 目录

`~/workspace` 是 FUSE 挂载，单次 `stat` 约 99 ms。在该目录内直接编译 Kotlin，30 分钟跑不完。

- 日常开发与编译一律在 **`/root/.aigo-build`**（本地磁盘主副本）。
- 只有需要宿主可见或归档时才写回 `~/workspace/AiGo-Android源码仓库`。
- 统一用 `aigo` 命令，不要自己拼 Gradle 命令。

### 5. 版本号只由 Git 决定，禁止手改

- `versionCode` = `git rev-list --count HEAD`；`versionName` 由 tag 推导并追加构建日期。
- **禁止**在 `build.gradle.kts` 里写死版本号。
- **禁止**对 `main` 做 `rebase` / `squash` / `push --force`——提交数变小会导致 versionCode 回退、新包装不上，CI 会直接拦下发版。

### 6. 品牌层改名的边界

三层链：`jieapi/aicode → xzyy03/Nox → steveneng2020/AiGo`。

- remote 只保留 `origin` 与 `upstream`（Nox）。**禁止把 AiCode 加为 remote 或直接合并**——实测两者无共同祖先，合并会产生双根历史，versionCode 体系失效。
- 源码包名保留 `com.nox`，只改应用层品牌（`applicationId`、应用名、图标、链接）。**禁止全局替换 `com.nox`**。
- 上游同步只用 `merge`。

### 7. 签名与密钥绝不入库

- `*.jks`、`*.keystore`、`keystore.properties`、`local.properties` 均已被 `.gitignore` 忽略，**不要取消忽略**。
- 推送前必须扫描：`git ls-files | grep -E '\.jks|keystore\.properties|local\.properties'` 应为空。
- 口令、token、PAT 不写进任何被跟踪的文件，不贴进提交信息。

### 8. 提交前必须过门禁

改完编译型代码（`.kt` / `.kts` / `AndroidManifest.xml` / `res/`）后，**一条命令跑完整门禁**：

```bash
aigo gate          # 8 步：敏感信息 → 迁移对账 → 品牌一致性 → 冒烟编译
                   #        （含 4b APK 内容 / 4c 出包文件名 / 4d Release 归档）
                   #        → 单元测试
aigo gate --fast   # 跳过单元测试（只改 UI/资源、或赶时间时用）
```

其中 **4d** 检查发版归档：每个已发布版本都要在 `build-output` 有对应的 `release` 包，且不得残留 `debug` 测试包。发版成功后未归档会被它拦下。

任一步 FAIL 就停下修，不要带着失败往下走。门禁脚本是 `/root/bin/aigo-gate`，检查项会随踩过的坑持续增加；新增检查时同步更新本节。

只改 `.md` / 纯文案时可以只跑 `aigo gate --fast`。

### 9. 界面文案必须中英双语

- 用户可见文案一律进 `values/strings.xml`（中文）与 `values-en/strings.xml`（英文），**禁止在 `.kt` 里硬编码中文 UI 文案**。
- 英文含撇号（`don't`）必须写成 `don\'t`，否则 `aapt2` 报 `Invalid unicode escape sequence`。

### 10. 所有变体共用同一包名，禁止 applicationIdSuffix

- `debug` / `beta` / `release` 一律是 `com.aigo`，**禁止**加 `.debug` / `.beta` 后缀。
- 理由：本项目自建自用，覆盖安装是期望行为。加后缀会带来三个实际问题——同机出现多个应用、私有目录不同导致容器 rootfs 与工作区"消失"、debug 包触发 Android 15+ 的 16 KB 兼容性弹窗。
- debug 变体必须与 release 使用**同一签名**（`signingConfig = release`），否则包名相同而签名不同会被拒绝覆盖安装（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。
- 上游若把后缀改回来，合并后必须立即恢复本约定，并跑 `aigo gate` 确认。

### 11. 改 UI 必须同步文档

新增页面、改交互、改文案 → 更新 `docs-site/docs/`；新增文档页还要加进 `docs-site/.vitepress/config.ts` 侧栏与 `guide/overview.md` 索引。

### 8a. 出包必须等构建结束再改动源码

> 2026-11-25 的教训：出包命令启动后又继续改源码，Gradle 用的是命令启动时刻的代码。
> 结果 APK 构建于 19:13、源码改到 19:14，归档并装进设备的包不含最后那批改动，
> 装机验收时看到的全是旧行为，还误以为是改动没生效。

**铁律**：

- 出包命令发出后，**在 `BUILD SUCCESSFUL` 之前不要改任何 `app/src` 下的文件**。
- 要改代码 → 先停掉/等完当前构建 → 改 → 编译验证 → 再出包。
- 出包完成先看两个时间：`APK mtime` 必须晚于 `app/src` 下所有文件的 mtime。
  门禁 `pre-commit` 的「APK 包含最新源码」一项会自动拦。
- 归档到 `build-output` 后，装机前再核对一次 `versionCode` 与时间戳。

### 8a-2. 绝不并行跑两个 Gradle 构建

> 2026-11-26 的教训：为赶进度同时起了两个构建（一个编译验证、一个出包），
> 两个进程互相 `--stop` 打断 daemon，连续失败 5 次以上。典型报错：
>
> - `kspUniversalDebugKotlin` 报 `generated/ksp/universalDebug/java/dagger`
> - `mergeExtDexArmsoloDebug` 报 `failed to make parent directories`
> - Kotlin daemon 持锁导致 `Unable to delete directory .../kotlin-classes`
> - `mergeArmsoloDebugResources` 报 `NoSuchFileException`
>
> 更糟的是还会连带杀掉对方 daemon（`Gradle build daemon has been stopped:
> stop command received`），两个构建一起废掉。

**铁律**：

- 启动构建前先查：`ps -ef | grep "[j]ava.*gradle"`。**有输出就等它结束。**
- **不要用 `pgrep -f "GradleWrapperMain"` 判断**：它会匹配到自己的轮询 shell
  （命令行里含这个字符串），导致误判「有构建在跑」并空等。方括号 `[j]ava`
  的写法能让 grep 排除自身。
- 构建状态损坏时不要反复重试，直接清干净重建：
  `sh gradlew --stop` → `rm -rf app/build build` → 单构建全量重建。
  注意删了 `app/build` 后**不能**只跑 `packageArmsoloDebug`（会缺 dex）。

### 8a-3. 判断版本历史前必须先 fetch tag

> 2026-11-26 的教训：用户问「v1.0.3 发过 Release 吗」，只查了本地 tag 就断言
> 「从未发布过」，实际早已发布。根因是本地 tag 列表是旧的，没先 `git fetch --tags`。
> 用户拿截图指出后才纠正。

**铁律**：任何「某版本是否存在/是否发布」的判断，先执行

```bash
git fetch --tags origin
```

再查。只信本地 ref 会得出错误结论，而且这类错误会直接误导后续决策
（比如据此决定跳过某个版本号）。

### 8a-4. 看构建日志必须找 `What went wrong`

> 2026-11-26 的教训：构建失败后用 `tail -6` 看日志，只看到
> `BUILD FAILED` 和几句 Gradle 版本警告，误以为「没报错」，
> 白跑了两轮。实际错误在 `What went wrong` 段，被 `tail` 截掉了。

**铁律**：

- 判断构建成败看日志里的 `What went wrong` 段，或者 `grep -E "BUILD |What went wrong"`。
- 不要用 `tail -N`（N 太小）下结论。
- 日志统一写到 `/tmp/vcN.log` 之类固定路径，便于失败后复查。

### 8b. 门禁必须真的会拦，不是写在文档里

> 2026-11-25 的教训：`aigo-gate.sh` 写了八步检查，但 `commit-msg` 钩子没有执行位、
> 我连续三次提交都没跑过门禁、UI 检查只覆盖 2 个文件（其余 36 个文件的 100+ 处硬编码
> 完全不检查）。结果就是「门禁存在但从不执行」——比没有门禁更危险，因为它给了虚假的安全感。

**现在的强制机制**（`core.hooksPath=.githooks`，提交时自动执行）：

| 检查 | 拦住什么 |
| --- | --- |
| UI 设计系统 | 统计卡片类文件的硬编码字号、图标体系混用、绕过统一行组件 |
| 出包版本号 | 命令行传了 -PversionCodeOverride 但 APK 里 versionCode 未变（见 8f） |
| 更新日记日期 | 最新版本日期晚于今天、或早于最近一次提交（见 8e） |
| UI 量化验收 | 改了面板/布局却没新验收截图，或截图里数值列右边缘不齐（见 8d） |
| 敏感信息 | 暂存内容里的 token / 私钥 |
| Kotlin 括号 | 括号不配对（编译要等几分钟才报错，这里提前拦） |
| 对标表述 | 文档与注释里的「参照 xx / 对标 xx」 |
| 工作区根目录 | 根目录出现 apk / png / html 等临时文件 |
| 出包文件名 | 不符合 `AiGo-<abi>-<版本>-<摘要>-<日期>.apk` |
| 未推送提交 | 有本地提交没 push（做完整项工作必须推送） |

**规则**：

- 提交前钩子自动跑，不通过就提交不了。`--no-verify` 只允许紧急情况，事后必须补跑 `aigo-gate`。
- 新增检查项时**同时验证它能拦住反例**：注入一个违规样本确认失败，不能只看「通过」。
  只验证通过等于没验证——检查逻辑写错时会永远显示 PASS。
- 存量违规用文件级 `ui-tokens-exempt: <原因>` 显式豁免，豁免是记录下来的技术债；
  不允许为了「让门禁变绿」把规则改宽或删掉检查项。

### 8b-2. 钩子丢失执行位 = 门禁静默失效

> 2026-11-26 的教训：刚给 `pre-push` 加了「改 .kt 就跑单测」的检查，
> 推送时 git 只打印一行提示就照常放行：
>
> ```
> hint: The '.githooks/pre-push' hook was ignored because it's not set as executable.
> ```
>
> 检查根本没跑，改动推上去了。索引里记的是 100755、磁盘上却是 600——
> 检出时权限位没保住。这是同一类问题第二次出现（`pre-commit` 也丢过一次）。

**铁律**：

- 钩子必须有执行位：`chmod 755 .githooks/*`。
- `pre-commit` 第 8 项会自检三个钩子的执行位，缺失即拦；新增钩子时记得
  把它加进自检列表，并确认索引里是 `100755`（`git ls-files -s .githooks/`）。
- 看到 `hook was ignored` 这类 hint 一律当错误处理，不要忽略——
  **门禁不跑比没有门禁更危险**，因为人会以为检查过了。

### 8b-3. 改了行为必须同步改测试断言

> 2026-11-26 的教训：把缓存命中率从整数百分比改成两位小数（`78.69%`），
> 只改了实现没改单测断言。CI 的 `verify` 阶段 1379 个测试里 5 个失败：
>
> ```
> CacheHitRateTest > rounds_to_whole_percent FAILED
> PanelValueFormatTest > 命中率向下取整而不是四舍五入 FAILED
> 1379 tests completed, 5 failed
> ```
>
> 三个 Release APK 全都构建成功，却卡在测试这一关，Release job 被跳过，
> tag 和 Release 都没创建。

**铁律**：

- 改任何「用户可见的数值/格式/文案」后，`rg` 一遍对应测试文件，同步更新断言。
- 测试是**规格**，不是障碍：断言反映旧行为时改断言，不要删测试。
- 推送前本地跑一次 `aigo test`（`pre-push` 也会跑，但别依赖它兜底）。
- 判断「改的是否只有文案」不够——命中率、耗时、小数位这类都算行为变化。

### 8c. 长时间构建必须派子代理盯守并即时汇报

> 2026-11-25 的教训：出包要跑 8~12 分钟，我把它挂在后台后用 `sleep 240` 干等，
> 用户等了十分钟不知道进展，只能主动来问。等待本身不是问题，
> **不告知**才是问题。

**铁律**：

- 任何预计超过 3 分钟的构建/编译，启动后**立即派一个子代理盯守**（`task(action="create")`）。
- 子代理的职责：每 60 秒检查一次产物 mtime 与 gradle 进程，一旦出结果就回报；
  轮询上限 15 分钟，超时也要回报「超时未完成」而不是静默。
- 盯守子代理**不得启动新的 gradle 构建**（禁止并行）、不得改 `app/src`、不得 kill 进程。
- 同时在主会话里向用户报告一次「已启动构建，预计 X 分钟」，不让用户凭空等。

**为什么交给子代理而不是自己 sleep**：主会话的 sleep 是阻塞的，睡多久就瞎多久；
子代理在独立上下文里轮询，主会话可以继续做其它事（写验收脚本、改下一处代码），
结果出来自动通知。

### 8f. 出包必须关闭构建缓存，否则 -P 版本号覆盖会失效

> 2026-11-26 的教训：出包命令带了 `-PversionCodeOverride=133`，
> 但产出的 APK 里 `versionCode` 仍是 127（恰好等于 git 提交数）。
> 原因：`gradle.properties` 里 `org.gradle.caching=true`，
> 即使加了 `--rerun-tasks`，`versionCode` 的计算结果仍会从构建缓存里取，
> `-P` 参数被旧缓存覆盖——`--rerun-tasks` 只强制重跑 task，不清缓存条目。

**出包命令固定写法**（三个参数缺一不可）：

```bash
sh gradlew :app:assembleArmsoloDebug \
  --no-configuration-cache --no-build-cache \
  -PversionCodeOverride=N -PversionNameOverride=1.0.4 --rerun-tasks
```

**出包后必须核验 APK 里的 versionCode**，而不是相信命令行参数：

```bash
aapt2 dump badging <apk> | head -1   # 看 versionCode= 是否等于 N
```

**同时核验改动真的进了包**：光看 mtime 不可靠（`--rerun-tasks` 在改动前就启动时，
产物 mtime 反而比源码新）。要从 APK 里反查特征字符串，例如改过的文案：

```python
import zipfile
data = zipfile.ZipFile(apk).read('resources.arsc')
print(data.count('新文案'.encode('utf-8')), data.count('旧文案'.encode('utf-8')))
```

### 8e. 更新日记日期必须跟着代码走

> 2026-11-26 的教训：代码已经改到 11-26、发版也在 11-26，
> 但 CHANGELOG 里最新版本仍写着 `2026-11-25`。关于页读的是内置 CHANGELOG，
> 用户点开「关于」看到过期日期，会以为自己装的是旧版本。

**铁律**：

- CHANGELOG 最新版本条目的日期，**必须等于或晚于最近一次提交日期**，且**不得晚于今天**。
- 每次改完代码准备提交前，先确认 CHANGELOG 里这一版的日期就是今天。
- 已发布过的版本条目**不许回头改日期**（那是发布历史，不是当前工作）。
- `pre-commit` 的「更新日记日期」一项会自动拦（`scripts/check_changelog_date.py`）。

**两条规则冲突时怎么办（2026-11-27 踩过）**：

v1.0.4 已在 11-26 发布上线，之后合并上游、提交日期变成 11-27，
门禁报「最新版本日期早于最近提交」。此时**两个都改不了**：

| 想法 | 为什么不行 |
| --- | --- |
| 把 v1.0.4 日期改成 11-27 | 已发布版本，改了与 GitHub Release 对不上，App 内更新日记也错 |
| 不改，硬推 | 门禁拦下，且确实该更新日记 |

**唯一正确的做法是新增版本小节**：在旧版本之前插入 `## vX.Y.(Z+1) — <今天>`，
把本次改动写进去。旧版本一字不动。追加后记得跑
`python3 scripts/gen-readme-highlights.py` 同步 README。

判断「是否已发布」：`curl -H "Authorization: Bearer $TOKEN"
https://api.github.com/repos/steveneng2020/AiGo/releases` 看有没有同名 tag。
本地 `git tag` 不算数——tag 可能是本地打的、或 Release 被删过。

**同时要求**：CHANGELOG 里**不许留有已删除功能的条目**。
本轮就出现过「首 token 耗时可拆解」整条描述的功能已被删除（两行拆解因数据源
测不到真实响应头而移除），但条目还留在日记里，等于对用户说谎。
每次删功能后必须同步删除对应条目。

### 8d. UI 对齐必须量化验收，不得目测

> 2026-11-25 的教训：面板数值行的单位与数字被 `SpaceBetween` 拆到两端，
> 实测右边缘跨度 **107px**（约 32dp），我靠目测整屏截图却判定「数值全部右对齐，通过」。
> 更严重的是验收清单里写着「`tok` 独立成列」——**把自己的 bug 写成了验收标准**。

**铁律**：

- 像素级属性（对齐、间距、尺寸）一律用 `scripts/verify_panel_alignment.py` 量，不靠看。
- 验收标准的来源必须是**用户的原话要求**，不能是自己实现的输出。
  写验收清单前先回读用户那句要求，逐字对应，不得用实现细节改写标准。
- 参考实现打开过就必须把几何、间距、对齐逐项量出来对比，不能只看配色与字号。
- `pre-commit` 的「UI 改动的量化验收」一项会检查：改了面板文件就必须有新的验收截图，
  且该截图必须通过对齐检测。

### 11b. UI 开发：先取确定值，再用设计令牌

> 界面「简陋、拼凑感」几乎都不是审美问题，而是同一屏里混了多套尺寸：12sp 与 15sp
> 并排、0.5dp 与 1dp 分隔线共存、24×24 与 16×16 图标混用。这类问题代码审查看不出来，
> 只有真机上才显得「不对」，所以写成规则并交给门禁拦。

**动手顺序（缺一步就返工）：**

1. **有可参考的实现时，先读源码取确定值**
   图标几何、字号、行高、间距、圆角、颜色透明度，逐项从源码取，不靠目测。
   取不到的再自绘，但画布与线宽必须与既有图标体系一致。

2. **输出对照表给用户确认**
   列出「元素 / 当前值 / 目标值 / 来源」，用户确认后再动手。
   一次性改完同一区块的所有差异，禁止「改字号→等编译→改间距→等编译」的分批循环。

3. **用设计令牌，不写数值**
   - 尺寸与字体：`com.nox.core.theme.PanelTokens`
   - 卡片外观、悬浮与翻转定位：`com.nox.core.ui.AnchorPopup` / `PanelCard`
   - 分组四件套（图标 + 标题 + 分隔线 + 内容）：`com.nox.core.ui.PanelSection`
   - 键值行：`com.nox.core.ui.PanelValueRow`（内含 tnum 等宽数字与右对齐）

4. **一套界面只用一套图标体系**
   紧凑卡片内用 `PanelIcons` 的 16×16 / 1.35dp；不要把 24×24 / 2dp 的图标混进同一张卡。

5. **间距走阶梯**
   只用 4 / 6 / 8 / 10 / 12 / 14 / 16 / 24 dp。分组内 6dp，分组之间 14dp。

6. **交付前自己并排比对**
   出包 → 自己装上 → 截图 → 与参考实现或设计稿并排看，确认比例、留白、视觉重心都协调后再交付。
   只看代码或只看单个元素不算验证。

**文档与 CHANGELOG 里禁止出现「参照 xx」「对标 xx」这类表述**，功能说明只写「做了什么、对使用者有什么影响」。

门禁 `scripts/check_ui_tokens.py` 会在提交前检查统计卡片类文件：
硬编码字号、混用图标体系、绕过 `PanelValueRow` 渲染数值都会直接拦下。
存量文件可临时加 `ui-tokens-exempt: <原因>` 豁免，新文件不允许添加。

### 12. 构建环境失控时，产出的 APK 一律不可信

> 2026-11-22 与 2026-11-23 两次「装上就闪退」的直接原因都是这一条。

**触发信号**（出现任一即视为环境失控）：

- 同一任务短时间内失败两次以上（`processXxxResources FAILED`、`transformXxxClassesWithAsm FAILED`、KSP `NoSuchFileException`、`Detected multiple Kotlin daemon sessions`）
- 两个 Gradle 构建同时运行（并行构建会互相踩缓存，出现「BUILD FAILED 但 tail 仍显示产物」）
- 手动删除过 `app/build` 的子目录（如 `tmp/kapt3`、`generated/hilt`、`intermediates/javac`）

**正确处置**：

```bash
cd /root/.aigo-build
sh gradlew --stop              # 先停所有 daemon，不要用 pkill -f（会误杀 PRoot 主进程）
rm -rf app/build build .gradle # 要么不动，要么全清；部分删除是最坏的做法
sh /root/bin/aigo apk          # 重新构建
```

**为什么部分删除反而更危险**：R8 的增量裁剪状态记在 `app/build` 里，删一部分会让它基于残缺信息裁剪，产出「Manifest 声明了但 dex 里没有」的包。这种包**编译阶段完全不报错**，只有真机冷启动 `installProvider` 时才崩。

**同一时间只允许一个 Gradle 任务。** 需要并行等待时，用 `terminal` 开单标签串行跑。

### 13. 出包后必须验证 dex 类定义，未通过不得交付

`BUILD SUCCESSFUL` 不代表包可用。**每次 `aigo apk` 之后、发给用户之前，必须跑一次类定义检查**：

```python
import zipfile, struct

def dex_classes(data):
    sid_size, sid_off = struct.unpack_from('<II', data, 0x38)
    tid_size, tid_off = struct.unpack_from('<II', data, 0x40)
    cd_size, cd_off = struct.unpack_from('<II', data, 0x60)
    def gs(i):
        o = struct.unpack_from('<I', data, sid_off + i*4)[0]
        p = o; ln = 0; sh = 0
        while True:
            b = data[p]; p += 1; ln |= (b & 0x7f) << sh
            if not (b & 0x80): break
            sh += 7
        return data[p:p+ln].decode('utf-8', 'replace')
    return [gs(struct.unpack_from('<I', data, tid_off +
            struct.unpack_from('<I', data, cd_off + i*32)[0]*4)[0])
            for i in range(cd_size)]

z = zipfile.ZipFile(apk)
allcls = set()
for d in [n for n in z.namelist() if n.endswith('.dex')]:
    allcls.update(dex_classes(z.read(d)))

for t in ['Lcom/nox/AIEditorApp;',
          'Lcom/nox/feature/agent/domain/container/ContainerInstaller;',
          'Lcom/nox/feature/workspace/data/provider/WorkspaceDocumentsProvider;']:
    print(('OK  ' if t in allcls else 'MISS ') + t)
```

**清单里的类必须全部 OK。** 重点盯 `WorkspaceDocumentsProvider`：它在 `AndroidManifest.xml` 里以 `<provider>` 声明，缺失会导致冷启动直接抛 `RuntimeException: Unable to get provider`，App 完全打不开。

**禁止用字节搜索代替**：`b'XxxClass' in dex_bytes` 会被字符串常量（日志文案、异常消息）误命中，不能证明类定义存在。

**release 包例外**：经 R8 混淆后类名会变，此方法只适用于 debug 包；release 包用 `aapt2 dump badging` 核对包名/版本，并跑 `scripts/verify-apk.py`。

### 13b. 装包前必须确认没有正在跑的会话

> 2026-11-27 的教训：为验证新包，直接执行 `am force-stop com.aigo` 再 `pm install -r`。
> 用户正在排队的会话（含未提交输入、进行中的工具调用）**全部丢失**。
> 更糟的是此前为「停住反复重启」把 `accessibility_enabled` 设成 0，
> 让 App 更难被系统维持。

**铁律**：

- 装包前先问一句「**现在有正在跑的任务吗？装包会重启应用**」。
  用户说可以，再动手。
- **`pm install -r` 自己就会重启进程，不需要先 `force-stop`。**
  多一次 force-stop 只会多一次丢失窗口。
- **不要为了停住 App 反复重启去关无障碍服务**（`accessibility_enabled 0`）。
  那是治标：正确做法是单次 `am force-stop`，装完再恢复服务。
- `pre-commit` 的「装包前有进行中会话的提醒」会查最近 10 分钟的未完成记录
  （`scripts/check_active_session.py`），有则黄字提示。它**不阻断提交**——
  是否重启是用户的决定，不是门禁能替他做的。

**这条为什么不能只靠技术手段解决**：容器读设备数据库要先停 App，
而停 App 本身就会杀掉会话。所以唯一可靠的做法是**装包前问用户**。

### 14. 装包前必须比对 schema，并先备份数据库

门禁跑在容器内，**看不到宿主数据库**（`/data/user/0/com.aigo/` 容器无权访问），所以这三件事必须手动做：

**① 查数据库版本**（容器内可直接读宿主文件的唯一路径是 `~/workspace`，它 bind mount 到宿主 `Download/AiGo/AiGo开发`）：

```bash
# Root 通道：拷到 workspace（容器可见）
cp /data/user/0/com.aigo/databases/nox_agent_db \
   /storage/emulated/0/Download/AiGo/AiGo开发/chk.db
# 容器内读
python3 -c "import sqlite3; print(sqlite3.connect('/root/workspace/chk.db').execute('PRAGMA user_version').fetchone()[0])"
```

**② 比对 APK 的 schema**：

```bash
python3 -c "
import zipfile, re
z = zipfile.ZipFile(apk)
migs = [n for n in z.namelist() if n.startswith('assets/migrations/') and n.endswith('.sql')]
vers = sorted(int(re.match(r'(\d+)_', n.split('/')[-1]).group(1)) for n in migs)
print(f'{vers[0]}..{vers[-1]}，共 {len(vers)} 个')
print('缺失:', [v for v in range(vers[0], vers[-1]+1) if v not in vers] or '无')
"
```

| 数据库 | APK | 结果 |
| --- | --- | --- |
| 64 | 64 | 可用 |
| 64 | 68 | 可用（Room 自动升级）|
| 68 | 64 | **闪退**（Room 不支持降级）|

**③ 装前备份，禁止 `cp` 拷 WAL 库**：

```python
import sqlite3
c = sqlite3.connect(src)
c.execute("VACUUM INTO '%s'" % out)   # 合并 WAL，产出独立单文件
c.close()
# 验证：PRAGMA journal_mode 应为 delete，PRAGMA integrity_check 应为 ok
```

WAL 模式下未 checkpoint 的写入全在 `-wal` 里，只拷主库 = 得到损坏库 = Room 读到时**直接删库重建**（2026-11-22 数据清空就是这么来的）。

### 14b. 异步落库的数据，刷新事件必须覆盖所有终态

> 2026-11-27 的教训：气泡统计行的「首 token」与「输出速度」时不时不显示，
> 而同样的消息展开后又能看到。根因是刷新只挂在 `LlmCallFinished` 上：
> 那个时刻 workflow 还在 finally 里算 reasoning plan，记录尚未落库，
> 补查 720ms 也可能等不到。而整轮结束的 `AgentEvent.Completed` **没有刷新**，
> 于是错过了最后一次补正机会，右半边就一直空着。

**判断标准**：如果一个 UI 值来自「另一次写库」，问三个问题：

1. **谁触发刷新**：是写入方回调，还是读取方轮询？两者时序无保证。
2. **有没有兜底终态**：轮次结束 / 页面恢复前台 / 切换会话，这三个时刻
   都是补刷的天然时机。**至少挂一个**，否则错过窗口就永远不更新。
3. **补查有上限吗**：退避重试要能停（本例最多 720ms），
   但「停了之后还要有一次终态兜底」，否则失败路径上永远不刷新。

反例（本例）：只在 `LlmCallFinished` 补查 720ms，`Completed` 不刷 →
写入慢于 720ms 就永久缺失。
正解：`Completed` 也调一次 `refreshSessionStats`（不带 expectedCalls，
直接读当前库），这样整轮结束时无论写没写进去都会再读一遍。

### 15. 查宿主路径先看 mountinfo，不要瞎试

容器是 PRoot，宿主路径大多不可见。已知绑定：

```
/root/workspace  →  /storage/emulated/0/Download/AiGo/AiGo开发
/root/.nox       →  /data/user/0/com.aigo/files/nox
```

不确定时一条命令查清：

```bash
cat /proc/self/mountinfo | grep -E "bind"
```

**不要依次试** `/storage/emulated/0`、`/mnt/...`、`/data/local/tmp`：它们在容器内都不存在，只会浪费时间。

## 排查经验（血泪教训，动手前先读）

这一节记录的每一条都是实际踩过、且造成过多轮无效返工的坑。**遇到同类问题时先对照，不要重新摸索。**

### 1. 真机问题先取证据，不要读自己的代码猜

**反面案例**：插件「分享到微信」失败，连续 5 轮都在改自己的 Intent（ClipData、MIME、EXTRA_STREAM 形态、URI 归属、FileProvider vs MediaStore），每次都说「找到根因」，每次都被打回。

**真正的证据链**：
- 微信侧 logcat → `ShareImgUI.onCreate` 后 **104ms** `onDetachedFromWindow`，说明界面起来了立刻销毁
- `dumpsys package com.tencent.mm` → 微信对 `application/*` 只注册 `ShareImgUI`（图片/视频界面）
- **对照实验**：用系统「文件管理」把同一个 zip 分享给微信 → **同样失败**

结论：微信根本不接收通过系统分享（`ACTION_SEND`）传来的 zip，改我方代码完全无效。最终方案是移除分享、改为导出到下载目录 + 提示用微信聊天窗口的 `+ → 文件` 发送。

**沉淀成规则**：
- 涉及第三方 App 行为的问题，**先做对照实验**（用别的应用 / 系统自带功能做同样的事）。能排除「我方代码问题」，避免长时间白费。
- 只在真的看不到时才去猜，且猜之前先想办法**把不可见变成可见**（加落盘日志、查系统状态）。

### 2. logcat 会丢日志，关键诊断必须落盘

**坑**：反复 `logcat -s PluginShare` 抓不到任何输出，误以为代码没执行。实际是 **logcat 环形缓冲区只有 256KB**，被 MIUI 系统日志几秒内冲掉。

**做法**：需要事后排查的诊断日志一律双写——`Log.w` 给人看，`FileLogger.w` 落盘可持久查看。日志位置：

```
/storage/emulated/0/Android/data/<包名>/files/logs/log-<日期>.txt
```

用 Root 复制到 workspace 再 `grep`，比反复 `logcat` 可靠得多。

### 3. 错误被静默吞掉 = 「点了没反应」，这是最难查的一类 bug

**反面案例**：已安装页点「更新」毫无反应。真因是 `install()` 失败时把错误写进了 `storeState.error`，但**只有商店详情页渲染这个错误**，已安装页没有——错误产生了却没人显示。

**做法**：
- 任何用户触发的异步操作，失败路径必须有**可见反馈**（Toast / 行内文案 / 日志），不能只 `runCatching {}.getOrNull()` 就完事
- `runCatching` 吞异常的地方，至少 `onFailure { FileLogger.w(...) }`
- 同一个状态（如 error）被多个页面消费时，确认每个消费方都渲染了它

### 4. 「找到了根因」这句话必须有证据支撑

**规则**：说「根因是 X」之前，自查三点：
1. X 有**直接观测证据**吗（日志原文、错误码、返回值、对照实验）？
2. 修掉 X 之后，**现象消失**了吗？
3. 有没有可能是「X 确实有问题，但不是主因」？

三条都不满足时说「推测」，不要包装成结论。反复宣称「重大突破」然后被现实打回，比承认不知道伤害大得多。

### 5. 第三方 SDK/App 的组件注册表是重要的排查入口

排查 intent 类问题时，先看对方到底注册了什么：

```bash
dumpsys package <对方包名> | grep -A 8 "Activity Resolver Table"
```

能看到它声明了哪些 action / MIME / 组件。本案就是靠这个发现微信对 `application/*` 只有一个入口，从而确认换 MIME 不可能改变行为。

### 6. 增量构建缓存损坏时，清目录比反复重试快

**坑**：改了代码后编译报 `error: cannot find symbol`，指向 KSP 生成的 Dagger 工厂类引用不存在的源码类（`TouchLockManager`、`AgentMessageDao` 等）。

**处理**：这是 KSP/Kotlin 增量缓存与源码不同步，不是代码问题。按顺序清：

```bash
rm -rf app/build/generated app/build/kspCaches app/build/kotlin
```

只清 `generated/` 不够，`kspCaches/` 里还留着增量状态，会继续报同样的错。

### 7. 清单解析器这类「严格校验」要区分「真不支持」与「编辑器辅助字段」

**坑**：第三方插件包 `plugin.json` 顶层带 `"$schema"`（JSON Schema 标准声明，只给编辑器提示用，运行时无语义），被白名单判为未知字段，**整包装不上**，表现为「安装包校验失败」。

**做法**：未知字段校验要判断字段性质——纯元数据/编辑器辅助字段应容忍，真正不支持的字段才报错。一个无害字段不应放大成整包失败。

### 8. 一次只改一个变量，否则无法归因

多个修复一起上，成功了不知道哪个起作用，失败了也不知道哪个有害。分享问题之所以拖了 5 轮，很大原因是每轮同时改 2~3 处（MIME + ClipData + flags），结果永远无法判断哪处有效。

### 9. 自己的轮询命令会污染进程判断

> 2026-11-26 的教训：用 `pgrep -f "GradleWrapperMain"` 判断构建是否在跑，
> 结果匹配到了自己写的等待循环（它的命令行里含这个字符串），
> 误判「构建已跑 20 分钟还没结束」，白等了 20 分钟。

**做法**：

- 判断进程用 `ps -ef | grep "[j]ava.*gradle"`，方括号让 grep 排除自身。
- 任何 `pgrep -f <字符串>` / `ps | grep <字符串>` 都可能匹配到发起查询的那个 shell，
  尤其是字符串出现在自己的命令行里时。
- 更可靠的是查具体指标：产物文件 mtime 是否在变、`/proc/<pid>/stat` 的 utime 是否增长。

## 基本约定

- **永远使用中文回复。**
- 文件读写用已有的文件工具，不用 `cat` / `sed` / `echo >` 代替。
- Android 应用：Kotlin + Compose + Hilt + Coroutines/Flow，模块 `:app` / `:terminal-emulator` / `:terminal-view`。
- **优先使用项目自定义组件（硬规则）**：
  - **输入框**：优先使用 `core/ui/AppTextField.kt`（及其配套配色 `appTextFieldColors()`、弹窗专用 `dialogTextFieldColors()`），禁止在业务页面无故裸写带有长下划线的原生 `TextField`，禁止在各模块私自重复包装 OutlinedTextField。
  - **开关**：一律使用 `core/ui/AppSwitch.kt`，禁止使用原生 M3 `Switch`。
  - **模态底栏**：一律使用 `core/ui/AdaptiveModalBottomSheet.kt`（已集成平板自适应与 fling fix），禁止直接调用原生 `ModalBottomSheet`；长表单容器外层注意追加 `.imePadding()` 避让软键盘。
  - **分组卡片与列表行**：设置与表单页面优先复用 `feature/settings/presentation/component/SettingsGroupComponents.kt` 中的 `SettingsGroup`、`SettingsRow`、`SettingsDivider`、`SettingsGroupHeader`，保持统一卡片与层级质感。
  - **左滑删除**：一律使用 `core/ui/SwipeToDeleteRow.kt`。
  - **主题与设计规范**：严格遵循 `core/theme/AIEditorTheme.kt` 的 `Spacing`、`Radius`（含 `Radius.mdLarge = 12.dp`）与 `MaterialTheme.semanticColors`，禁止随意硬编码非标魔数与色彩。

## 构建与验证

**改完编译型代码（`.kt` / `.gradle.kts` / `AndroidManifest.xml`）→ 提交前必跑冒烟编译；`git push` 前必跑单元测试，并跑 `check_migrations.py` 迁移对账。** 只改文档 / 资源文案 / 纯 `.md` 时这些都跳过。

统一入口是 `aigo` 命令（`/root/bin/aigo`），它在 `/root/.aigo-build` 主副本里执行并自动注入环境变量：

| 用途 | 命令 |
| --- | --- |
| 冒烟编译（日常默认，缓存命中约 20~50 秒） | `aigo build` |
| 推送前单测 | `aigo test` |
| 出可安装的 debug 包（最快，无需 R8） | `aigo apk` |
| 出 release 包但跳过 R8（仅自测） | `aigo fast` |
| 迁移对账 | `python3 scripts/check_migrations.py` |
| 状态 / 同步 | `aigo status` / `aigo push` |

手工执行 Gradle 时必须先 export 这五个变量，否则会失败：

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export GRADLE_USER_HOME=/root/.gradle-home      # 默认 ~/.gradle 不可写
export ANDROID_USER_HOME=/root/.android-home    # 默认 ~/.android 不可写，debug 签名需要
```

且一律用 `sh gradlew`（FUSE 上 chmod 不生效，`./gradlew` 会 Permission denied）。

**不要加 `--no-daemon`**：实测 daemon 复用时无改动校验 22 秒，加 `--no-daemon` 后同操作 4 分 52 秒。

- **别用聚合任务做日常验证**：`assembleDebug` / `assembleRelease` / `test` / `build` 都会跨三个 flavor 全跑，耗时极长。
- 产物：`app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk`、`.../bundle/<flavor>/release/app-<flavor>-release.aab`。
- flavor 按 ABI 拆分：`universal`（arm64-v8a + x86_64）、`armsolo`（仅 arm64-v8a）、`x86solo`（仅 x86_64）。
- release 签名凭据读 `app/keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）；本地通常不存放签名文件，CI 从 GitHub secret 还原到 `app/aicode.jks`。

**出包后、交付前，必须做三件事**（详细脚本见铁律 13/14）：

1. 解析 dex 类定义，确认关键类都在（尤其 `WorkspaceDocumentsProvider`）
2. 比对 APK 的 schema 与设备数据库 `user_version`
3. 备份设备数据库（`VACUUM INTO`，禁止直接 `cp` WAL 库）
## 开发流程（按此执行，不要自创）

完整流程见技能 `aigo-dev-workflow`。速查：

```
aigo status → 拉上游 → 定位代码 → 改
  → aigo gate（敏感信息/迁移/品牌/编译/单测）
  → aigo apk → 真机验收 → 用户确认
  → commit → push → 打 tag → CI 出包
```

**关键判定**：编译通过 ≠ 完成。UI 改动必须出包给用户真机验证。

## 架构地图

feature-based 分层 + DDD。入口 `AIEditorApp` 初始化 `FileLogger`、`TerminalKeepaliveService`、`McpManager`。

- **`core/`**：跨 feature 基础设施 —— `db/`（含 `MigrationLoader.kt`）、`net/`、`theme/`、`ui/`、`util/`（含 `FileLogger`）。
- **`feature/`**（`app/src/main/java/com/nox/feature/`）：
  - `agent`：AI agent 核心 —— 提示词、工具注册与权限、MCP、provider 适配（`data/remote/` 下 `anthropic` / `openai` / `gemini`）。
  - `terminal`：终端与会话。本地模式 Termux 组件 + PRoot（`LinuxContainerEngine`）；远程模式 sshj。
  - `workspace`：工作区与 DocumentsProvider，远程走 `RemoteSftpFileAccess`。
  - `editor`：sora-editor 编辑器。`git`：Git 操作。`settings`：provider、日志、保活等设置。
  - `backup`：备份恢复与加密。`credentials`：凭据管理与注入容器。
- **远程 SSH 链路**：`RemoteSshConnection`（共享 sshj client）+ `RemoteSshEngine`（执行命令）+ `RemoteSftpFileAccess`（文件）+ `RemoteTerminalSessionManager`（终端）。
- **工具系统**：`feature/agent/domain/tool/` 下各工具经 `ToolRegistry` 注册，执行权限由 `ToolPermissionManager` 与 `ToolPermissionPolicyEngine` 管控。
- **MCP**：`feature/agent/domain/mcp/`，连接远端 server 并动态注册其工具。**DI**：Hilt，各 feature 自带 DI 模块。

### 数据库与迁移

Room（`feature/agent/data/local/database/AgentDatabase.kt` + 各 DAO），迁移分两套，**一个版本只能二选一**：

- **文件式（默认）**：`core/db/MigrationLoader.kt` 读 `app/src/main/assets/migrations/{VERSION}_description.sql`，由 `SqlScriptSplitter` 按语句切分（识别注释与字符串字面量，**字符串里可放心写 `;`**，不再需要 `char(59)` 绕行）；整段迁移包事务、任一条失败整体回滚，成功记入 `migration_history` 表。
- **AutoMigration**：纯 schema 变更（加列/建表/索引）可用 `@AutoMigration(from = N-1, to = N)` 编译期自动生成，改 entity 忘写迁移会直接编译失败；含数据清理/重命名/改约束的版本必须走文件式。

改 schema 三步：

1. 递增 `AgentDatabase.kt` 的 `SCHEMA_VERSION`（当前 72）。
2. 文件式：在 `app/src/main/assets/migrations/` 新建 `{VERSION}_description.sql`（如 `46_add_provider_multi_key.sql`），**编号必须连续**；AutoMigration：加注解，保证 `to == SCHEMA_VERSION` 且 `from` 衔接文件式最大版本。
3. 写入 DDL/SQL，启动时自动执行并记入 `migration_history` 表。

**跨分支冲突硬规则**（RC/hotfix 与 `main` 并行推进时）

- **发布即冻结**：已打 `v*` tag 的迁移文件内容与编号不可再改，补丁只能靠新增迁移修正。
- **合流后移**：RC/hotfix 若带数据库迁移，其迁移号一旦随 tag 发布即占用；合回 `main` 时，`main` 上所有编号 ≤ 已发布最大版本号的未发布迁移必须整体重编号到该上限之后（内容一字不改、编号连续），并同步递增 `SCHEMA_VERSION`。已发布号一律不可复用。
- **对账脚本**：`python3 scripts/check_migrations.py`（或 `./gradlew checkMigrations`）校验编号连续、SCHEMA_VERSION 一致、已发布迁移未被篡改/复用，**推送前必跑**；CI（ci.yml 与 android-release.yml）已在构建前挂载，合流后未后移会被直接拦下。

## 资产同步（硬规则）

`app/src/main/assets/prompts/`（AI 提示词）与 `docs-site/docs/`（用户文档）是 AI Agent 的知识来源，必须与代码同步：

- **AI 工作流改动 → 同步 `prompts/`**：工具增删改名、参数签名变化、agent 行为或提示词逻辑调整，都要更新 `prompts/` 下对应文件（自行查找，不存在则新建）。
- **功能或工具变化 → 检查 `docs-site/docs/`** 是否有使用文档要更新。
- **UI 变化（新增页面、改交互、调布局、改文案）→ 必须更新 `docs-site/docs/`**；新增文档页同步加进 `docs-site/.vitepress/config.ts` 侧栏与 `docs-site/docs/guide/overview.md` 索引。
- **用户可见中文文案 → 必须进双语 strings.xml**：写入 `values/strings.xml`（中文）与 `values-en/strings.xml`（英文），代码用 `stringResource(R.string.xxx)` 引用。**禁止在 `.kt` 中硬编码中文 UI 文案。** 命名用语义化英文小写下划线，跨页面复用的加 `common_` 前缀。
- **提供商预设只在仓库数据文件维护**：新增或调整内置 AI 供应商，**只改 `data/providers.json`**（App 启动时从仓库动态拉取的源）；**不要**同步改 `app/src/main/assets/providers.json`（内置兜底快照，随发版固化）与 `docs-site/` 文档。
- **容器镜像目录由脚本生成，禁止手改**：`data/container-images.json` 由 CI（`sync-container-images.yml`，每日）跑 `scripts/sync-container-images.py` 抓上游生成后发布到 `data` 分支；`app/src/main/assets/container-images.json` 是内置兜底快照，**发版前用同一脚本刷新**（`python3 scripts/sync-container-images.py --out app/src/main/assets/container-images.json`）——它承载离线可用的版本号与 SHA-256 校验和，与 providers 快照「不维护」的策略刻意不同。要增删发行版或改镜像源，改脚本里的配置或 `sources` 表，不要改生成结果。

- **更新日记必须内置，不能只靠网络**：`assets/changelog.json` 由 Gradle 任务 `generateChangelog` 在构建时解析仓库根 `CHANGELOG.md` 生成（人工撰写、面向用户；不从 git log 生成）。关于页先读内置（离线可读），联网成功再覆盖。GitHub API 匿名限流 60 次/小时，只走网络会导致断网/限流时关于页显示「暂无发布记录」。改动关于页更新日记逻辑时，必须保证内置路径仍然有效。

**文档目录约定**：`docs-site/docs/` 是文档唯一事实源，`guide/` 放功能说明、`advanced/` 放环境搭建与进阶教程。构建时由 `syncAiDocs` task 复制到 `assets/docs/`，AI 在容器内看到的是 `~/.nox/docs/{guide,advanced}/*.md`。**面向用户书写**：讲清怎么做、会看到什么、出错怎么办；变量名、错误码、内部实现路径属于 `prompts/`，别写进用户文档。

## Git 提交规范

Conventional Commits（`.githooks/commit-msg` 本地校验），格式 `<type>(<scope>): <subject>`，正文空行隔开。

- **type** ∈ `feat | fix | refactor | docs | style | chore | ci | build | perf | test`
- **scope** 可选，用功能模块：`agent | settings | terminal | workspace | git | ui | mcp | db | core | docs | build | deps`
- **subject** 一行简述，中英文均可，句末不加句号。例：`fix(settings): 修复 provider 保存时校验失败`
- 仅紧急情况可用 `git commit --no-verify` 跳过校验。

## 分支工作流

Tag 驱动发版，平时 `main` 上的提交不影响发布包。

- **新功能 / 复杂多文件改动 / 架构重构** → 开 `feat/xxx` 或 `refactor/xxx`，动手前先定好分支名，验证通过后合回 `main`。
- **日常 bug 修复 / 补单测 / CI 与构建配置 / 纯文档 / 资源文案** → 直接提交 `main`。
- **预览版热修复** → 从该 RC Tag 拉 `hotfix/xxx`，见〈发版〉。
- **合并后清理分支**：`git branch --merged main` 确认后 `git branch -d <branch>`；推送过的同步 `git push origin --delete <branch>`。

## PR 合并流程

**在本地验证完再推送，不用 `gh pr merge` 在远端直接合**（绕过本地编译验证，且与本地在途提交分叉）。

1. **摸状态**：`git fetch origin` → `gh pr view <N> --json state,mergeable,mergeStateStatus,headRefName,changedFiles` + `gh pr checks <N>` → `git rev-list --left-right --count origin/main...main`。fork 来的 PR 的 Vercel 必报 `Authorization required to deploy`（`mergeStateStatus` 随之 `UNSTABLE`），属正常，只看 `Build & Test` 是否 pass。
2. **拉到本地**：`git fetch origin pull/<N>/head:pr-<N>`。勿用 `gh pr checkout`，它会改当前分支的跟踪关系。
3. **审查**：`gh pr diff <N> > /tmp/pr<N>.diff` 通读。**PR 描述与 CodeRabbit 结论都不算证据**，逐项落地核实：
   - 新引入的库在 `app/build.gradle.kts` 里是否已有
   - 新用的 `R.string.xxx` 在中英两份 `strings.xml` 里是否都存在
   - 调用的既有组件签名是否匹配；被改了签名的组件用 `rg` 找出全部调用点确认都改到
   - 新交互是否复用既有实现（例：模型拖拽排序对照 `ProvidersAndLogSection.kt` 的手柄写法）
   - 安全面：可疑网络请求、凭据外发、命令拼接
   - 〈资产同步〉各项；含数据库迁移的 PR 必须跑 `python3 scripts/check_migrations.py` 对账（编号连续、与已发布 tag 无冲突）
   - 超出 PR 标题范围的改动记下来交用户定夺
4. **预演冲突**：`git merge-tree --write-tree --name-only main pr-<N>`。只输出一个 tree hash = 可干净合并；列出文件名 = 有冲突。
5. **临时分支合并**：`git switch -c merge/pr-<N> main` → `git merge --no-ff pr-<N> -m "Merge pull request #<N> from <owner>/<head-branch>" -m "<PR 标题>"`。
6. **冒烟 + 真机验证**：动了 UI 或交互的 PR 必须发 APK 给用户真机验证，**通过才继续**。
7. **单测 + 推送**：`git switch main && git merge --ff-only merge/pr-<N> && git push origin main`。head commit 成为 `main` 祖先后 GitHub 自动标记 Merged，用 `gh pr view <N> --json state,mergeCommit` 核实。
8. **清理**：`git branch -d merge/pr-<N> pr-<N>`。不动 stash 与未跟踪文件。

**必须停下来问用户**：本地 `main` 有未推提交（推 `main` 会把在途提交一并带上去）／预演有冲突／PR 含数据库迁移或构建、CI、签名改动／审查发现超范围改动或质量问题／编译或单测失败。

## Issue 审查

收到待评估的 issue（新功能 / bug 报告 / 改进建议）时，按以下顺序评估并输出审查报告：

1. **通读 issue**：提炼核心诉求、验收标准、所属模块（对照〈架构地图〉）与优先级；关联的讨论、PR、历史 commit 一并核实。**issue 里提出的实现方案只是作者的建议，不代表最优解，不得直接采纳**，优劣须在第 2~4 步结合代码与查证独立判断。
2. **阅读相关代码**：按 issue 提到的模块定位并阅读真实代码（`list` / `search` / `readFile`），确认描述是否属实、影响面多大。**陈述事实以核实过的代码为准，issue 作者的描述不算证据**。做法拿不准或需要业界对照时，用 `websearch` / `webfetch` 查官方文档、同类实现与社区实践。
3. **评估是否合适**：结合项目约束判断该改动该不该做——
   - 是否符合 feature-based 分层 + DDD 现有结构，是否重复造已有轮子；
   - 是否触碰硬约束（`targetSdk` 28、迁移编号连续、双语 strings.xml、`prompts/` / 文档同步等〈资产同步〉各项）；
   - 改动范围与风险：是否触及启动 / 容器 / 签名 / CI 等敏感面（此类改动发版需按〈发版〉先发 RC）。
4. **平替方案**：不受 issue 提案的局限，以核实过的代码与查证资料为依据，给出至少一个替代实现并对比（实现成本、风险、维护性、对既有用户的影响），给出推荐结论；需要用户取舍时用选择题征询，别替用户拍板。
5. **输出报告**：以对话回复形式输出报告，包含：结论（采纳 / 拒绝 / 待定）、理由、相关代码位置（带行号链接）、方案对比、建议的下一步（是否进入 PLAN、是否开分支按〈分支工作流〉）。报告不落盘，除非用户明确要求写文件。

## 发版

**版本号唯一事实源是 Git Tag / Commit，无需手写。** `versionName` 由 `gitVersionName()` 解析（tag `v1.7.0` → `1.7.0`，非 Tag 提交 → `1.7.0-dev.N+<hash>`）；`versionCode` 由 `gitCommitCount()` 递增。

### 是否先发 RC

靠 GitHub Release 分发且无灰度，发出去即终态，RC 是主要兜底：

- **必须先发 RC**：含新功能或行为变化（定档 `x.Y.0`）；构建链路 / 签名 / flavor / CI 改动；容器镜像 / PRoot / ABI 改动。
- **可直接发正式**：仅纯文档 / typo / 资源文案（定档 `x.y.Z`）。
- **看改动面**：仅纯 bug 修复（定档 `x.y.Z`）小改直接正式，触碰启动或容器的仍先发 RC。

### 步骤

0. **更新内置模型数据（仅打 Tag 前执行）**：`python3 scripts/update-models-dev-assets.py` 从 models.dev 刷新 `app/src/main/assets/api.official.json`（只保留内置 12 个官方 provider、不引入新 provider，现有 provider 下可扩充模型与单价）。**该脚本只在打 Tag 发版前手动执行；日常提交 / 推 main / 其它开发流程一律不跑**。**失败时脚本非零退出且不改快照——直接跳过此步发版，不要重试或手改文件**；成功则把快照改动一并提交到即将打 Tag 的分支。
1. **在 GitHub Actions 页面手动触发 `android-release.yml`（workflow_dispatch）**，输入 `release_tag`（如 `v1.0.0`）。**不要本地打 tag 再 push**——本仓库是 fork，GitHub 不为 fork 的 tag push 触发 workflow，打了也不会跑。
2. CI 自己创建 tag 与 Release，并用输入的 tag 覆盖 versionName（避免构建时仓库无 tag 导致版本号落到 dev 形态）。
3. **真机装 RC 包**，至少跑通 AI 对话 + 终端 + 容器启动三条主线。
4. **有问题**：从该 RC Tag 拉 `hotfix/xxx` 修复（**勿从最新 `main` 或功能分支拉**，否则会把已合入的未发版功能带进修复包）→ 升 rc 序号打 Tag 重发 → 修复合回 `main` 并推送 → 删 hotfix 分支。这是允许在非 `main` 分支打 Tag 的唯一例外。**无问题**：直接打正式 Tag（`v1.7.0`）转正。
5. **归档 Release 资产到 build-output（发版成功后必做）**：
   - 从 GitHub Release 下载 arm64 包，存到 `/storage/emulated/0/Download/AiGo/AiGo开发/build-output/`
   - **必须重命名**为 `AiGo-arm64-v8a-<版本>-release-<摘要>-<日期>.apk`（CI 产物名是 `AiGo-arm64-1.0.3-20261124.apk`，不含 ABI 短横线形式、不含 release 标识与摘要，直接存会和本地包混不清）
     - `<摘要>` 取 Release 正文首句里的关键词，与同目录历史包保持风格一致
     - `<日期>` 用 Release 的发布日期，与 CHANGELOG 的版本日期一致
   - **删除同版本的 debug 测试包**：正式包归档后 `AiGo-*-debug-*.apk` 不应留在该目录，否则装机时容易拿错
   - **若历史版本缺 release 包**（只有 debug），一并从对应 Release 下载补齐
   - 校验：`aapt2 dump badging` 确认 versionCode / versionName，`apksigner verify --print-certs` 确认签名证书为 `CN=AiGo`
   - **按 Release 真实发布时间回填 mtime（否则文件管理器顺序会乱）**：
     系统的文件管理器按修改时间倒序排列。批量下载归档时几个包 mtime 几乎相同，
     会出现「v1.0.2 排在 v1.0.3 前面」这类错序。归档后逐个设为该版本的
     `published_at`（GitHub API 返回 UTC，本地时区 +8）：
     ```bash
     touch -d "2026-11-24 09:14:20" "AiGo-arm64-v8a-1.0.3-release-xxx-20261124.apk"
     ```
     设完用 `ls -lt *.apk` 确认顺序为版本号从新到旧。
   - 门禁 `4d` 会检查「每个已发布版本都有 release 包」且「无 debug 残留」
   - 若 hotfix 带数据库迁移：迁移号随 tag 发布即冻结（发布即冻结），合回 `main` 时 `main` 上编号更小的未发布迁移必须后移（见〈数据库与迁移〉合流后移），push 前先跑 `check_migrations.py`，CI 兜底拦截。
