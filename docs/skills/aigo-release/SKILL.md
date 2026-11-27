---
name: aigo-release
description: AiGo 发版 SOP：keystore 生成、GitHub Secrets 配置、workflow 启用/禁用、tag 与 versionCode 规则、Release 产物与检查更新链路。需要出正式包、修 CI、或排查发版失败时使用。
---

# AiGo 发版

## 一、版本号的唯一来源：Git

不要手改版本号，`app/build.gradle.kts` 从 git 推导：

| 字段 | 来源 | 规则 |
| --- | --- | --- |
| `versionName` | `git describe --tags` | 当前 commit 有 tag → 去 `v` 前缀（`v1.2.3` → `1.2.3`）；领先 tag N 个提交 → `1.2.3-dev.N+g<hash>`；无 tag → `1.0.0-dev+<hash>` |
| `versionCode` | `git rev-list --count HEAD` | 每次提交 +1，单调递增 |

推论：

- **提交数是 versionCode**。任何改写历史（rebase / squash / force push）都会让它变小，导致新包装不上。
- **不要浅克隆**。CI 里 checkout 必须 `fetch-depth: 0`，否则 `rev-list --count` 得到 1。
- 同一 commit 上打 rc 与正式 tag，versionCode 相等，CI 允许（`-lt` 才判失败）。

### 版本节奏

| 场景 | 操作 | 结果 |
| --- | --- | --- |
| 日常提交，不发版 | 只 push main | `1.0.0-dev.N+hash`，versionCode 递增 |
| 正式发版 | `git tag -a v1.2.0 && git push origin v1.2.0` | `1.2.0` |
| 本地反复调试 | 加 `-PversionCodeOverride` / `-PversionNameOverride` | 版本号固定，可覆盖安装 |

### 本地固定版本号（反复构建不回退版本）

```bash
sh gradlew :app:assembleArmsoloDebug \
  -PversionCodeOverride=137 -PversionNameOverride=1.0.4
```

~~`--no-build-cache` 必加~~ —— **2026-11-27 实测推翻**：不加任何 cache 参数时
`-PversionCodeOverride=999` 产出 `versionCode=999`（正确），加 `--no-build-cache`
时 888 也正确。`-P` 参与 cache key 计算，参数变了缓存自然不命中。
旧结论「产出 127 而非 133」已无法复现。

**出包后必须核验 APK 里的 versionCode**，不要相信命令行参数：

```bash
/opt/android-sdk/build-tools/35.0.0/aapt2 dump badging <apk> | head -1
```

作用：同签名 + 同 versionCode 允许直接覆盖安装，**不必先卸载**，也不会让应用内更新误判为「有新版本」。

前提是 `app/build.gradle.kts` 里 `defaultConfig` 读这两个参数（已实现）：

```kotlin
versionCode = localVersionCodeOverride() ?: gitCommitCount()
versionName = localVersionNameOverride() ?: gitVersionName()
```

**唯一要避免的操作**：先装 versionCode 大的包，再回头装 versionCode 小的包（如切回旧分支构建）。系统会报 `INSTALL_FAILED_VERSION_DOWNGRADE`，此时必须先卸载。

### 构建日期：不要放进 versionName

SemVer 里 `-` 之后是预发布段，参与比较。若写成 `1.0.0-20261121`，它**小于** `1.0.0`，正式版发布后更新检测会失效（被判为"已是最新"）。

正确做法二选一：

- **写进 tag**（推荐，零副作用）：`v1.0.0-20261121`
- 写进 `+` 后的构建元数据（`1.0.0-dev.5+g04bc2fa.20261121`）：不参与比较，但每次构建 versionName 都变，会**破坏 Gradle 配置缓存**，在本机让增量编译从 22 秒掉到接近全量

## 二、首次发版的一次性准备

### 1. 生成自己的 keystore

**必须自己生成，不要用上游的**（签名不同，包无法覆盖安装，也是安全底线）。

```bash
cd /root/.aigo-build/app
keytool -genkeypair -v \
  -keystore aigo.jks \
  -alias aigo \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass <你的口令> -keypass <你的口令> \
  -dname "CN=AiGo, OU=AiGo, O=AiGo, L=, ST=, C=CN"
```

`*.jks` 已在 `.gitignore` 里，不会误提交。**务必备份，丢了就无法覆盖升级。**

### 2. 配置 GitHub Secrets

仓库 `Settings → Secrets and variables → Actions`，添加 4 个（名称沿用上游，无需改 workflow）：

| Secret | 值 |
| --- | --- |
| `AICODE_KEYSTORE_BASE64` | `base64 -w 0 app/aigo.jks` 的输出 |
| `AICODE_KEYSTORE_PASSWORD` | keystore 口令 |
| `AICODE_KEY_ALIAS` | `aigo` |
| `AICODE_KEY_PASSWORD` | key 口令 |

生成 base64（容器内）：

```bash
base64 -w 0 /root/.aigo-build/app/aigo.jks > /tmp/ks.b64
# 内容较长，复制到 Secret 时注意不要带换行
```

workflow 会把 base64 还原到 `app/aicode.jks` 并生成 `app/keystore.properties`，与 `build.gradle.kts` 期望一致。

### 3. 处理无用的 workflow

上游自带 5 个 workflow，其中 4 个对自己无意义。**不要删文件**（会与上游冲突），在 Actions 页面逐个 **Disable**：

| workflow | 处理 | 原因 |
| --- | --- | --- |
| `ci.yml` | 保留 | push 到 main 时编译 + 单测门禁 |
| `beta.yml` | 保留 | 每次 push 产出可测的 beta APK |
| `android-release.yml` | 保留 | 打 tag 出正式包 |
| `docs-deploy.yml` | 禁用 | 部署 VitePress 文档站到 Pages，自己不需要 |
| `sync-gitcode.yml` | 禁用 | 同步到 GitCode 镜像 |
| `sync-container-images.yml` | 禁用 | 每日抓容器镜像清单，写 `data` 分支 |
| `sync-models.yml` | 禁用 | 每日同步模型清单 |

禁用的副作用：`app/src/main/assets/container-images.json` 不再自动刷新。发版前若要更新镜像清单，手动跑：

```bash
cd /root/.aigo-build
python3 scripts/sync-container-images.py --out app/src/main/assets/container-images.json
```

## 三、日常发版流程

### 最重要的一条：不要在本地打 tag 再 push

**本仓库是 fork。GitHub 对 fork 仓库的 push（含 tag）事件不启动 workflow**，
这是平台行为，无法用仓库设置绕过（除非脱离 fork 网络，那会破坏与上游的关系）。

所以发版主路径是 **`workflow_dispatch` 手动触发，由 CI 自己创建 tag 与 Release**。

```bash
cd /root/.aigo-build

# 1. 确认干净、与远端一致
git status --short
git fetch origin && git rev-list --left-right --count origin/main...HEAD

# 2. 拉取最新 tag（否则判断版本历史会出错）
git fetch --tags origin

# 3. 检查有没有会污染 versionName 的临时 tag
aigo release-check

# 4. 本地验证
aigo test --all                # 全量单测（143 个类，8~9 分钟），必须过
# 或直接 sh gradlew :app:testUniversalDebugUnitTest
python3 scripts/check_migrations.py
python3 scripts/check_changelog_date.py

# 5. 推送代码
git push origin main
```

### 触发发版（容器内无 gh CLI，用 API）

```bash
TOKEN=$(printf 'protocol=https\nhost=github.com\n\n' \
  | /root/.nox/git-credential-nox get | sed -n 's/^password=//p')

curl -s -o /dev/null -w "HTTP %{http_code}\n" -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  https://api.github.com/repos/steveneng2020/AiGo/actions/workflows/android-release.yml/dispatches \
  -d '{"ref":"main","inputs":{"release_tag":"v1.0.5","prerelease":"false","flavors":"all"}}'
# 期望 HTTP 204
```

| 输入 | 值 | 说明 |
| --- | --- | --- |
| `release_tag` | `v1.0.5` | 须与 versionName 对应；CI 会覆盖 versionName |
| `prerelease` | `false` | 正式版；RC 用 `true` |
| `flavors` | `all` | 三包全出；只出真机包用 `arm64` |

### CI 流水线结构

```
verify ──► build (matrix × 3) ──► release
```

| job | 内容 |
| --- | --- |
| `verify` | versionCode 单调校验、迁移对账、**单元测试（1379 个）** |
| `build` | matrix 并行三个 flavor：`armsolo` / `x86solo` / `universal` |
| `release` | 汇总三个 artifact，建 tag 与 Release，正文从 `CHANGELOG.md` 读取 |

**`verify` 里的单元测试是硬门槛。** 改行为漏改测试断言会在这里挂掉，
三个 APK 都建好了却出不了 Release（v1.0.4 首次发版即如此：5 个命中率断言
未跟上两位小数修改）。排查失败先看 job 名：`Verify` 失败多半是测试或迁移。

构建约 8~10 分钟（matrix 并行后从原来的 17 分钟降下来）。

### 产物

| 产物 | 内容 | 适用 |
| --- | --- | --- |
| `AiGo-arm64-<ver>-<date>.apk` | 仅 arm64-v8a | 绝大多数真机 |
| `AiGo-x86_64-<ver>-<date>.apk` | 仅 x86_64 | 模拟器 / Chromebook |
| `AiGo-universal-<ver>-<date>.apk` | 两套镜像 | 不确定架构时用，体积最大 |
| `AiGo-universal-<ver>-mapping.txt.gz` | R8 混淆映射 | 排查崩溃栈用 |

### 发版成功后必须归档（门禁 4d 会检查）

1. 下载 arm64 包到 `~/workspace/build-output/`
2. 重命名为 `AiGo-arm64-v8a-<版本>-release-<摘要>-<日期>.apk`
3. **删除同版本的 debug 包**（`AiGo-*-debug-*.apk` 不得残留）
4. 校验 `aapt2 dump badging` 与 `apksigner verify --print-certs`（应为 `CN=AiGo`）
5. **按 Release 真实发布时间回填 mtime**，否则文件管理器按时间排序会乱：
   ```bash
   touch -d "2026-11-26 11:01:51" "AiGo-arm64-v8a-1.0.4-release-上下文用量面板-20261126.apk"
   ```

## 四、发版前必查

| 项 | 检查方式 |
| --- | --- |
| versionCode 是否递增 | `git rev-list --count HEAD`，与上个 tag 的提交数比较 |
| applicationId 仍是 `com.aigo` | `rg -n 'applicationId' app/build.gradle.kts` |
| 迁移编号连续 | `python3 scripts/check_migrations.py` |
| 中英 strings 都有新文案 | 新增 `R.string.xxx` 时搜两份 `strings.xml` |
| 检查更新地址指向自己 | `rg -n 'xzyy03/Nox' app/src/main/java` 应为空 |
| NOTICE 修改声明已更新 | 新增显著改动时补一条 |

### 发版链条上的构建纪律

| 规则 | 原因 |
| --- | --- |
| **不并行跑两个 Gradle 构建** | 互相 `--stop` 打断 daemon，报 `generated/ksp/.../dagger`、`failed to make parent directories`、`Unable to delete directory kotlin-classes` |
| **判断构建是否在跑用 `ps -ef \| grep "[j]ava.*gradle"`** | `pgrep -f "GradleWrapperMain"` 会匹配到轮询 shell 自身，误判后空等 |
| **看日志找 `What went wrong`** | `tail -6` 会把错误段截掉，看起来像没报错 |
| **钩子必须有执行位** | 丢失时 git 只打印 `hook was ignored` 然后放行，`pre-push` 的单测检查等于不存在。`chmod 755 .githooks/*` |

## 五、常见失败

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `versionCode 回退` | 历史被改写（rebase/squash） | 不要改写历史；必要时在旧 tag 之上重新线性推进 |
| release 变 unsigned | 4 个 Secret 缺任一个 | 补齐 Secrets |
| `Keystore was tampered with` | base64 带换行或口令错 | 重新生成 base64（`-w 0`），核对口令 |
| 构建 OOM | runner 内存不足 | `gradle.properties` 已按 16 GB runner 调优，别调低 |
| `Permission denied` 打包 | PRoot 下容器出包 | 容器只做校验，正式包走 CI |
| **`Verify` job 失败，`Run unit tests` 红** | 改行为漏改测试断言 | 本地先跑 `aigo test`；改断言而非删测试 |
| **`versionName` 变成 `1.0.0-dev+manual-2-2-g<hash>`** | 本地残留 `manual-`/`tmp-`/`test-` 前缀临时 tag，`git describe` 选中了它 | `aigo release-check` 清理；门禁也有此项检查 |
| **`Release` job 失败：tag 或 Release 已存在** | 重发时忘了先删 | 见下方「改 Release 正文」 |
| **`versionCode` 与 `-PversionCodeOverride` 不符** | 多半是并发构建抢 `app/build`（2026-11-27 实测：不开关缓存，`-P` 也能生效） | 查 `ps -ef \| grep "[j]ava.*Gradle"` 应只有 1 个 |
| Actions 未触发 | workflow 被禁用 | Actions 页面确认 `Android Release` 是启用状态 |

## 六、发版后想改 Release 正文怎么办

**不能只改 `CHANGELOG.md`** —— 那只改仓库文件，已发布的 Release 页面不会自动更新。

| 方案 | 做法 | 结果 |
| --- | --- | --- |
| **删掉重发**（用过） | 删 Release → 删 tag（远端+本地）→ 重新 `workflow_dispatch` | 同版本号重发，页面立即正确 |
| 攒到下个版本 | 只改 CHANGELOG，下次发版带走 | 当前版本页面一直是错的 |

### 删 tag + Release 不影响 fork 关系

fork 是仓库层面的链接（GitHub 维护 `fork` / `parent` 元数据），
tag 只是指向提交的指针，不参与历史血缘。删完核实：

```bash
git remote -v                      # upstream 仍指向 xzyy03/Nox
git merge-base HEAD upstream/main  # 血缘不断
```

### 操作顺序

```bash
cd /root/.aigo-build
TOKEN=$(printf 'protocol=https\nhost=github.com\n\n' \
  | /root/.nox/git-credential-nox get | sed -n 's/^password=//p')

# 1. 先推代码（改动必须先上去，否则重发的包还是旧的）
git push origin main

# 2. 删 Release
RID=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/steveneng2020/AiGo/releases/tags/v1.0.4" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
curl -s -o /dev/null -w "DELETE %{http_code}\n" -X DELETE \
  -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/steveneng2020/AiGo/releases/$RID"
# 期望 204

# 3. 删 tag
git push origin --delete v1.0.4
git tag -d v1.0.4

# 4. 重新触发（第三节）
```

**删之前确认**：代码已推送、门禁通过、单测过了。中途失败会留下
「tag 存在但无 Release」的半成品，需要再删一次重来。

## 七、签名长期管理

- keystore 与口令离线备份至少一份（丢了只能换包名或让用户卸载重装）。
- 不要提交 keystore 到任何仓库，也不要贴进 AI 对话。
- 换机器后，把 `app/aigo.jks` 放回原位即可本地出正式包。
