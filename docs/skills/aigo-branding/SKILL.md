---
name: aigo-branding
description: AiGo 的品牌改名方案与逐处清单：applicationId、应用名、图标、仓库链接、路径常量的具体位置与改法，以及刻意不改的源码包名边界与 GPL-3.0 义务。需要改名、核对品牌一致性、或处理上游新引入的 com.nox 硬编码时使用。
---

# AiGo 品牌层改名

## 一、核心决策：只改应用层，不改源码包名

| 层 | 处理 | 理由 |
| --- | --- | --- |
| `applicationId` | 改成 `com.aigo` | 系统与用户看到的「包名」，决定安装标识、共存、私有目录 |
| 源码包 `com.nox` | **保留不动** | 内部命名空间，用户不可见；改了会让**全部源码文件**偏离上游（2026-11 实测 753 个文件、4413 处，数量随上游增长），同步成本极高 |
| 应用显示名 | 改成 `AiGo` | 桌面图标下的名字 |
| 图标资源 | 换成自己的 | 避免沿用上游视觉标识 |
| 仓库链接 | 指向自己 | 检查更新与关于页 |
| 对外可见路径常量 | 指向自己的包名 | 否则功能失效 |

包名全小写：Java/Android 包名规范要求全小写，且 macOS/Windows 文件系统大小写不敏感，混用大写会出现目录覆盖问题。

## 二、改动清单（共 8 处代码/资源 + NOTICE）

### 1. applicationId

`app/build.gradle.kts:105`

```kotlin
applicationId = "com.nox"   →   applicationId = "com.aigo"
```

**不要动**同文件 `:89` 的 `namespace = "com.nox"`，它对应源码包与 `R` 类的包名，改了要连带改全部 import。

### 2. 应用内安装校验的包名

`app/src/main/java/com/nox/feature/settings/presentation/SettingsViewModel.kt:415`

```kotlin
const val RELEASE_APPLICATION_ID = "com.nox"   →   "com.aigo"
```

作用：只有正式包（无 `.debug` / `.beta` 后缀）才允许应用内覆盖安装。不改会导致更新下载后安装校验失败。

### 3. 检查更新的 API 地址

`app/src/main/java/com/nox/feature/settings/data/remote/UpdateCheckService.kt:165`

```kotlin
const val GITHUB_RELEASES_API = "https://api.github.com/repos/xzyy03/Nox/releases"
→ "https://api.github.com/repos/steveneng2020/AiGo/releases"
```

不改会拉到上游的 APK，且签名不同无法覆盖安装。

### 4. 检查更新的跳转地址

`app/src/main/java/com/nox/feature/settings/presentation/component/UpdateCheckDialog.kt:186`

```kotlin
private const val GITHUB_RELEASES_URL = "https://github.com/xzyy03/Nox/releases"
→ "https://github.com/steveneng2020/AiGo/releases"
```

### 5. 关于页的仓库与许可证链接

`app/src/main/java/com/nox/feature/settings/presentation/component/AboutSection.kt:550-551`

```kotlin
private const val PROJECT_REPO_URL = "https://github.com/xzyy03/Nox"
private const val LICENSE_URL = "https://github.com/xzyy03/Nox/blob/main/LICENSE"
→ .../steveneng2020/AiGo
```

### 6. Root 策略里的自身数据目录

`app/src/main/java/com/nox/feature/agent/domain/root/RootPolicyEngine.kt:51`

```kotlin
"/storage/emulated/0/Android/data/com.nox"
→ "/storage/emulated/0/Android/data/com.aigo"
```

作用：Root 操作的黑/白名单路径判断。不改会导致 Root 工具对自己数据目录的判定错位。

### 7. 应用名

`app/src/main/res/values/strings.xml:3`（中文）与 `app/src/main/res/values-en/strings.xml:3`（英文）

```xml
<string name="app_name">Nox</string>   →   <string name="app_name">AiGo</string>
```

两份都要改，缺一份会在对应语言下显示 Nox。

### 8. 图标

替换 `app/src/main/res/mipmap-*` 下的启动图标与 `ic_launcher` 相关资源。注意上游的「图标设置」功能支持多套图标（`MainActivity` / `MainActivityDefault` / `MainActivityGlasses` / `MainActivityWrench` 等别名），若保留该功能，每套图标都要替换。

### 9. NOTICE 修改声明（GPL-3.0 义务）

`NOTICE` 顶部已有上游基于 AiCode 的修改声明。在原有声明后追加自己的：

```
Modification notice (GPL-3.0 section 5a):
    Changes made on 2026-xx-xx by <你的名字/项目> include rebranding to AiGo
    (applicationId com.aigo), a new application name and icon set, and
    features described in README. Source package names remain com.nox to
    keep upstream merge compatibility. All modifications are released
    under GPL-3.0 as well.
```

GPL-3.0 不要求改名，但**分发时必须保留原有版权声明并标注修改**。同时对外提供源码（你的仓库即是）。

## 三、刻意保持不变的地方

以下出现 `com.nox` 但是**不要改**，改了会破坏构建或增大同步成本：

| 位置 | 说明 |
| --- | --- |
| `namespace = "com.nox"` | 源码包与 R 类包名 |
| 所有 `package com.nox.*` / `import com.nox.*` | 源码包声明 |
| `app/src/main/aidl/com/nox/...` | AIDL 包路径 |
| `app/schemas/com.nox...AgentDatabase/` | Room 导出 schema 目录名，由 namespace 决定 |
| `app/proguard-rules.pro` 的 `com.nox.**` 规则 | 与源码包对应 |
| `app/src/main/assets/nox/` 与 `~/.nox` 运行时目录 | 容器内工具链路径约定，改名会牵动 provision 脚本与凭据 helper |
| 通知/Action 字符串常量（如 `com.nox.action.START_SESSION`） | 仅进程内自用，不对外暴露，改名无收益 |

## 四、改完怎么验证

```bash
cd /root/.aigo-build

# 应只剩源码包相关的 com.nox
rg -n 'com\.nox"' app/build.gradle.kts app/src/main/java --glob '!*.md'
# 期望：只出现在 package/import、内部 action 常量、非品牌路径

# 不应再有上游仓库地址
rg -n 'xzyy03/Nox' app/src/main/java

# 编译
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/opt/android-sdk \
       ANDROID_SDK_ROOT=/opt/android-sdk GRADLE_USER_HOME=/root/.gradle-home \
       ANDROID_USER_HOME=/root/.android-home
sh gradlew :app:compileArmsoloDebugKotlin
```

装包后核对：

```bash
adb shell pm list packages | grep aigo     # 应为 com.aigo
adb shell dumpsys package com.aigo | head  # 确认 versionName / versionCode
```

## 五、上游合并后的回归点

上游新增代码时，最常见的两类回退：

1. 新文件里硬编码 `"com.nox"` 字符串（尤其 `applicationId` 相关判断、路径拼接）。
2. 新页面直接引用上游仓库 URL。

合并后跑第二节的检查命令即可发现。发现后按同位置同样方式改成 `com.aigo` / 自己的仓库地址，并在提交信息里注明 `chore(branding):`。
