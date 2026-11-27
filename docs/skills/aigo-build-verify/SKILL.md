---
name: aigo-build-verify
description: AiGo（Android 应用，Nox 的 fork，applicationId com.aigo）的编译与验证流程：为什么必须在 /tmp 主副本里编译、实测耗时基线、Gradle 参数取舍、常见报错对照。改动 .kt/.kts/Manifest 后做冒烟编译时使用。
---

# AiGo 编译与验证

## 一、核心结论：源码在 FUSE，编译在 /root/.aigo-build

`~/workspace` 是 App 通过 **FUSE** 暴露给容器的持久化目录。它的痛点是**元数据操作**，不是带宽：

| 操作 | workspace（FUSE） | 本地磁盘 | 倍数 |
| --- | --- | --- | --- |
| 单次 `stat` | ~99 ms | ~4 ms | 24x |
| 遍历 2436 个文件（当次测量样本） | 55.7 s | 0.81 s | 70x |
| `git status` | 30.0 s | 1.48 s | 20x |
| 200 个小文件写入 | 0.88 s | 0.05 s | 18x |
| 复制整个仓库到本地磁盘 | — | 1 min 01 s | — |

Kotlin 编译要遍历并产出 5000+ 个 class，在 workspace 里跑 `:app:compileUniversalDebugKotlin` **30 分钟都跑不完**（表现为 daemon 几乎不占 CPU、`utime` 十几分钟只涨几秒）。同一任务在主副本里 1 分 30 秒完成。

**所以：日常开发与编译都在 `/root/.aigo-build`，workspace 只作为「宿主可见的归档副本」。**

## 二、两个位置

| 用途 | 路径 | 宿主可见路径 |
| --- | --- | --- |
| 主副本（日常开发、编译） | `/root/.aigo-build` | 不可见，但持久化在 /data 分区 |
| 源仓库（持久化、备份、分享） | `~/workspace/AiGo-Android源码仓库` | `/storage/emulated/0/Download/AiGo/AiGo开发/AiGo-Android源码仓库` |

两处**都是持久化的**，重启不会丢，区别只在速度与可见性：

- 源仓库在 FUSE 挂载上，`stat` 约 99 ms，编译跑不动，但宿主文件管理器/电脑能看到；
- 主副本在 rootfs 的 `/root` 下，与 `/` 同一磁盘分区，`stat` 约 4 ms，编译快，但宿主看不见。

主副本为什么不用 `/tmp`：实测容器内 `/tmp` 与 `/` **确实是同一磁盘分区**（`df` 同为 `/dev/block/dm-37`，写 300 MB 后磁盘可用空间同步减少 300 MB），技术上也能持久。但 `/tmp` 语义上属于临时目录，宿主侧对它是否有清理策略不受我们控制，故放进 `/root` 下的自有目录，同样快且无被误清理的风险。

`aigo push` 默认**只写回 git 认为有改动的文件**（实测 0.67 秒）。不要用整树写回：tar 打包全量到源仓库要 6 分钟。

仍需要 push 的原因不是「怕丢」，而是**只有源仓库在宿主存储上可见**。

## 三、统一入口：`aigo` 命令

已装好 `/root/bin/aigo`：

```bash
aigo status                        # 主副本分支/提交/改动
aigo build                         # 默认 :app:compileUniversalDebugKotlin
aigo build :app:assembleUniversalDebug
aigo test                          # 单元测试
aigo pull                          # 从源仓库重建主副本（约 1 分钟）
aigo push                          # 把主副本改动写回源仓库（只写改动文件）
aigo where                         # 打印两个位置
```

`aigo push` 默认只同步有改动的文件，当无改动时输出「无改动需要写回」。与 FUSE 打交道的通用技巧：**能少碰文件就少碰，一个文件一次 `cp` 远快于遍历后全量写**。

## 四、实测耗时基线

同一台机器，宿主 6 核持续高负载（`/proc/loadavg` 长期 17+）：

| 场景 | 耗时 |
| --- | --- |
| 无改动校验（daemon 预热 + 配置缓存命中） | **22 ~ 24 s** |
| 无改动校验（daemon 未预热） | 38 ~ 47 s |
| 改 1 个文件后的增量编译 | 47 s ~ 5 min 29 s（随宿主负载波动） |
| 全量编译（build 目录已存在） | 1 min 30 s |
| 在 workspace 里跑全量 | > 30 min（跑不完） |
| 写回单个改动文件到 source | **0.67 s** |
| 写回整棵树（tar 打包全量） | 6 min（不要用） |
| 从零建立主副本（`aigo pull`） | 1 min 01 s |

增量编译波动大是**宿主 CPU 过载**导致的，不是配置问题：`loadavg 17.6 / 6 核` 意味着每个核都在排队。连续跑三次无改动校验得到 38s → 24s → 22s，说明 daemon 预热后能稳定在 20 秒出头。

## 五、Gradle 参数：不要凭直觉加

逐项实测，结论与直觉相反：

| 参数 | 实测结果 | 结论 |
| --- | --- | --- |
| `--configuration-cache` | 无改动校验 **12 s** | **必须开**（关掉后同样操作 41 s） |
| `--no-daemon` | 增量编译 4 min 52 s | **禁用**（daemon 复用时同操作 47 s） |
| `--no-parallel` | 无明显收益 | 不必加 |
| `--offline` | 依赖已缓存时可用 | 首次构建或加新依赖时去掉 |
| `--no-configuration-cache` | 41 s | 见过别人这么建议，但本环境是负优化 |

全局配置在 `/root/.gradle-home/gradle.properties`：

```properties
org.gradle.jvmargs=-Xmx3072m -XX:MaxMetaspaceSize=768m
org.gradle.workers.max=2
android.aapt2FromMavenOverride=/opt/android-sdk/build-tools/35.0.0/aapt2
org.gradle.configuration-cache=true
org.gradle.configuration-cache.problems=warn
org.gradle.caching=true
org.gradle.parallel=true
org.gradle.daemon=true
```

`org.gradle.workers.max=2`：宿主已过载，开太多 worker 只会互相抢 CPU。

### 出包：不需要关缓存（2026-11-27 实测更正）

~~出包必须 `--no-build-cache`~~ —— 该结论已推翻。

> 旧记录：2026-11-26 出包带 `-PversionCodeOverride=133`，产出仍是 127，
> 归因为 `org.gradle.caching=true` 让 versionCode 计算命中缓存。

**实测复验（2026-11-27）**：

| 实测 | 参数 | 产出 versionCode | 耗时 |
| --- | --- | --- | --- |
| A | 不加任何 cache 参数 | **999（正确）** | 9m54s |
| B | `--no-build-cache` | **888（正确）** | 6m03s |

两次都生效。原因：`-P` 参与 Gradle 的 cache key 计算，参数变了缓存自然不命中。

```bash
sh gradlew :app:assembleArmsoloDebug \
  -PversionCodeOverride=N -PversionNameOverride=1.0.x
```

缓存可以一直开着（日常编译 22s，关掉后 41s）。

**versionCode 不符时先查什么**：不是先怪缓存，而是先按 CLAUDE.md 2.3 查
是否有并发构建（`ps -ef | grep "[j]ava.*Gradle"` 应只有 1 个）。
并发构建才是 2026-11-26 那次故障最可能的原因——两个进程抢 `app/build`，
产物可能是另一路用旧参数产出的。

## 六、环境变量

`aigo` 脚本已内置，手动执行时要自己 export：

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export GRADLE_USER_HOME=/root/.gradle-home   # 默认 ~/.gradle 不可写
```

`GRADLE_USER_HOME` 不能省：`~/.gradle` 在本环境 mkdir/touch 都返回 Permission denied。

## 七、执行方式：`sh gradlew`，不要 `./gradlew`

FUSE 挂载**不支持 chmod**（`chmod +x gradlew` 返回 0 但权限位不变），`./gradlew` 会 `Permission denied`。一律 `sh gradlew`。

## 七之二、构建纪律（踩坑总结，务必遵守）

| 规则 | 原因 | 后果 |
| --- | --- | --- |
| **绝不并行跑两个 Gradle 构建** | 两个进程互相 `--stop` 打断 daemon | `ksp` 报 `generated/ksp/.../dagger`；`mergeExtDex` 报 `failed to make parent directories`；Kotlin daemon 持锁致 `Unable to delete directory kotlin-classes`；`mergeResources` 报 `NoSuchFileException`。2026-11-26 因此失败 5 次以上 |
| **出包启动后、`BUILD SUCCESSFUL` 之前不改 `app/src`** | 构建读的是启动时的源码快照 | 包与提交对不上。vc128、vc133 因此作废 |
| **判断构建是否在跑用 `ps -ef \| grep "[j]ava.*gradle"`** | `pgrep -f "GradleWrapperMain"` 会匹配到轮询 shell 自身（命令行含该字符串） | 误判「构建在跑」，空等 20 分钟 |
| **看日志找 `What went wrong`** | `tail -N` 会截掉错误段 | 误以为「没报错」，白跑两轮 |
| **状态损坏时全量重建** | 增量目录被污染 | `sh gradlew --stop` → `rm -rf app/build build` → 单构建全量。删 `app/build` 后**不能**只跑 `packageArmsoloDebug`（缺 dex） |

### 出包后四项核验（缺一不可）

```bash
APK=app/build/outputs/apk/armsolo/debug/app-armsolo-debug.apk

# 1. versionCode 对不对
/opt/android-sdk/build-tools/35.0.0/aapt2 dump badging $APK | head -1

# 2. 源码有没有比包新（应为 0）
find app/src -name '*.kt' -newer $APK | wc -l

# 3. 构建期间源码是否被改（构建前先快照对比）
#    前：find app/src -name '*.kt' -o -name '*.xml' | xargs stat -c '%Y %n' | sort > /tmp/snap.txt
#    后：同样命令 > /tmp/snap_now.txt && diff /tmp/snap.txt /tmp/snap_now.txt

# 4. 改动真进包了（mtime 不可靠，反查字符串）
python3 -c "
import zipfile
d = zipfile.ZipFile('$APK').read('resources.arsc')
print('新文案命中:', d.count('你的新文案'.encode()))"
```

## 八、职责边界：容器只做校验，APK 交给 CI

不要在容器里出正式包：内存不足（release 的 R8 会 OOM）、签名密钥不应进容器、PRoot 下 AGP 的 apkzlib 删临时目录可能失败。正式包走 GitHub Actions（见 `aigo-release`）。

## 九、任务选择

不要用聚合任务。`assembleDebug` / `assembleRelease` / `test` / `build` 会跨 `universal` / `armsolo` / `x86solo` 三个 flavor 全跑。

| 用途 | 命令 |
| --- | --- |
| 日常改 Kotlin 后冒烟 | `:app:compileUniversalDebugKotlin` |
| 需要资源/Manifest 校验 | `:app:assembleUniversalDebug` |
| 单元测试 | `:app:testUniversalDebugUnitTest` |
| 迁移对账 | `python3 scripts/check_migrations.py` |

## 十、Git 凭据只对指定目录生效（易漏）

App 写入的凭据 helper 靠 `includeIf "gitdir:/path/"` 绑定，**只对列出的路径生效**。

注册路径在 `/root/.nox/.gitconfig`，目前包含两条：

```
/root/workspace/          → 源仓库
/root/.aigo-build/        → 主副本
```

新增第三个工作目录时必须同步加一条，否则在那一目录里 `git fetch/push` 会报
`could not read Username for 'https://github.com'`：

```bash
git config --global --add includeif.gitdir:<新路径>/.path /root/.nox/gitconfig.credential
```

排查时注意：**不要在仓库外跑 `git credential fill`**，helper 按目录生效，在外面跑一定失败。

```bash
cd <仓库目录>
printf "protocol=https\nhost=github.com\n\n" | git credential fill

# 验证凭据本身有没有数据（与目录无关）
printf "protocol=https\nhost=github.com\n\n" | ~/.nox/git-credential-nox get
```

## 十一、常见报错对照

| 现象 | 原因与处理 |
| --- | --- |
| `./gradlew: Permission denied` | FUSE 改不了权限位，用 `sh gradlew`。 |
| 写 `~/.gradle/**` 报 permission denied | 该目录不可写，设 `GRADLE_USER_HOME=/root/.gradle-home`。 |
| `unable to write file .git/objects/xx/...` | FUSE 不支持 hardlink。已全局设 `core.createObject=rename`。 |
| `ln: .git: Permission denied` | FUSE 不支持创建符号链接。 |
| `aapt2: cannot execute` | 用了 x86_64 版。确认 `aapt2FromMavenOverride` 指向 ARM64 版。 |
| 在仓库外 `git credential fill` 报 `could not read Username` | helper 按目录生效，到仓库目录里再执行。 |
| Gradle daemon 长时间不占 CPU | 在 workspace（FUSE）里编译。换到 `/root/.aigo-build`。 |
| `Gradle build daemon disappeared` | daemon 被外部杀掉。重跑即可，缓存还在。 |
| 锁文件冲突 | 删 `/root/.gradle-home/caches/*.lock`。 |
| 增量编译忽快忽慢（47 s vs 5 min） | 宿主 CPU 过载，非配置问题。查 `/proc/loadavg`。 |

## 十二、提交前检查清单

```bash
cd /root/.aigo-build

# 1. 冒烟编译（改过 .kt / .kts / Manifest）
aigo build

# 2. 推送前单测
aigo test

# 3. 迁移对账（改过数据库 schema）
python3 scripts/check_migrations.py

# 4. 同步到宿主可见的源仓库
aigo push
```

只改 `.md`、资源文案时 1~3 可跳过，但第 4 步不能省。

## 十三、出包与真机验收（固定流程，不要每次试）

### 13.1 构建

```bash
cd /root/.aigo-build

# 日常自测：debug 包，固定版本号便于覆盖安装
sh gradlew :app:assembleUniversalDebug \
  -PversionCodeOverride=15 -PversionNameOverride=1.0.1

# 产物
ls -lh app/build/outputs/apk/universal/debug/app-universal-debug.apk
```

`-PversionCodeOverride` 必须**大于已装版本**，否则覆盖安装会被系统拒绝。
版本号只在本地覆盖，不改 git tag。

### 13.2 安装（SELinux 坑，必须按这个顺序）

**直接 `pm install /storage/emulated/0/...` 会失败**：

```
avc: denied { read } for scontext=u:r:system_server:s0
tcontext=u:object_r:fuse:s0
Error: Unable to open file
```

system_server 无权读 FUSE 挂载的用户存储。正确做法是先复制到 `/data/local/tmp`：

```bash
# 用 Root 通道，逐条执行（不支持管道/串联）
cp /storage/emulated/0/Download/AiGo/AiGo开发/aigo-1.0.1-debug.apk /data/local/tmp/a.apk
chmod 644 /data/local/tmp/a.apk
pm install -r /data/local/tmp/a.apk
```

或直接跑脚本：

```bash
sh /root/.aigo-build/scripts/aigo-install.sh /storage/emulated/0/Download/AiGo/AiGo开发/aigo-1.0.1-debug.apk
```

**容器内不能 `cp` 到 `/data/local/tmp`**（PRoot 看不到该路径），必须走 Root 通道。

### 13.3 验收清单

装完逐项过一遍，每项记录「通过/不通过」：

| # | 项目 | 判定标准 |
| --- | --- | --- |
| 1 | 启动不闪退 | 冷启动进入聊天页，无崩溃弹窗 |
| 2 | 数据保留 | 会话数、供应商数、插件数与本轮前一致 |
| 3 | 数据库 schema | 升级后不触发重建（会话不丢） |
| 4 | 本轮改动 | 逐条对照 CHANGELOG 新增条目验证 |
| 5 | 图标切换 | 设置 → 图标设置，逐套切换桌面生效 |
| 6 | 通知栏图标 | 切图标后常驻通知同步换图 |
| 7 | 键盘避让 | 相关输入框聚焦后不被键盘遮挡 |

### 13.4 验收后

- 截图留证（关键界面）
- 不通过项当场定位，不要攒到最后
- 通过后同步源码到宿主仓库：`aigo push`

### 13.5 数据备份（验收前必做）

覆盖安装理论上保数据，但改动涉及数据库或 `applicationId` 时必须先备份：

```bash
# Root 通道
am force-stop com.aigo
cp -r /data/user/0/com.aigo/databases /data/local/tmp/db-backup-$(date +%s)
```

**不要用 `cp` 直接拷 WAL 模式主库**——必须连 `-wal`/`-shm` 一起，或用 `VACUUM INTO` 导出单文件。
