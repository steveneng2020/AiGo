import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")

    kotlin("plugin.compose")
    kotlin("plugin.serialization") version "2.2.21"
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// 从本地 keystore.properties 读取 release 签名密钥（已 gitignore，不入库）。
// 若文件不存在（如 CI 环境）则跳过，release 产出 unsigned 包。
val keystorePropertiesFile = file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// gitVersionName 从 git tag 动态解析，彻底解决“发版时手改 build.gradle.kts 与 tag 双向不同步”问题。
// 规则：
//   1. 若当前 commit 刚好有 tag（如 v1.0.0 或 v1.0.0-rc1），直接提取为 "1.0.0" 或 "1.0.0-rc1"；
//   2. 若当前 commit 比上个 tag 多了 N 个提交（如 v1.0.0-2-g04bc2fa），提取为 "1.0.0-dev.2+g04bc2fa"；
//   3. 若无 git 环境或报错，fallback 到默认版本号 "1.0.0-dev"。
// providers.exec 而非 Runtime.exec：配置缓存要求配置阶段的外部进程调用登记为 provider 输入，
// 直接 fork 会让缓存条目被丢弃（external process started）。
val gitDescribeOutput = providers.exec {
    workingDir = rootProject.projectDir
    commandLine("git", "describe", "--tags", "--always", "--dirty")
    isIgnoreExitValue = true
}.standardOutput.asText

// 构建日期（yyyyMMdd）。用于 versionName 的元数据段，格式如 1.0.0-dev.5+g04bc2fa.20261121。
//
// 为什么放在 '+' 之后：SemVer 规定 '+' 之后是构建元数据，不参与版本比较。
// splitVersion() 里是 v.substringBefore('+')，所以带日期不会影响「是否有新版本」的判定。
// 若放在 '-' 之后（如 1.0.0-20261121），按 SemVer 会小于 1.0.0，正式版发布后更新检测会失效。
//
// 取 git 提交日期而非机器当前时间：同一提交在任何机器、任何时间构建都得到相同值，
// 这样 CI 与本地产物一致，也避免每次构建都让 BuildConfig 变化而打掉增量缓存。
val buildDateOutput = providers.exec {
    workingDir = rootProject.projectDir
    commandLine("git", "log", "-1", "--format=%cd", "--date=format:%Y%m%d")
    isIgnoreExitValue = true
}.standardOutput.asText

fun buildDate(): String = try {
    buildDateOutput.get().trim().ifEmpty { "" }
} catch (e: Exception) {
    ""
}

fun gitVersionName(): String = try {
    val raw = gitDescribeOutput.get().trim()
    if (raw.startsWith("v")) {
        val version = raw.substring(1) // 去掉开头的 'v'
        // 如 "1.0.0"、"1.0.0-rc1" 或 "1.0.0-2-g04bc2fa"
        // 将 git describe 格式 "1.0.0-2-g04bc2fa" 转为规范语义化版本 "1.0.0-dev.2+g04bc2fa"
        val devRegex = Regex("""^(\d+\.\d+\.\d+(?:-[a-zA-Z0-9]+)?)-(\d+)-g([0-9a-f]+)(.*)$""")
        val match = devRegex.matchEntire(version)
        if (match != null) {
            val (base, count, hash, dirty) = match.destructured
            "$base-dev.$count+$hash$dirty.${buildDate()}"
        } else {
            // 当前 commit 正好有 tag：版本号本身已确定（如 1.0.0 或 1.0.0-20261121）。
            // tag 里若已含 8 位日期就不再追加，避免出现 1.0.0-20261121.20261121。
            if (version.contains(Regex("""\d{8}"""))) version else "$version+${buildDate()}"
        }
    } else if (raw.isNotEmpty()) {
        "1.0.0-dev+$raw.${buildDate()}"
    } else {
        "1.0.0-dev+${buildDate()}"
    }
} catch (e: Exception) {
    "1.0.0-dev"
}

// versionCode 从 git 提交数自动生成：随每次提交单调递增，无需手动维护，
// 杜绝"升 versionName 忘升 versionCode"导致升级判定失效。
// 工作目录用 rootProject.projectDir（仓库根），无 git 环境（如下载 zip 构建）时 fallback 到 1。
// CI 额外校验 versionCode 单调（见 .github/workflows/android-release.yml），防 rebase/squash 改写历史导致回退。
val gitCommitCountOutput = providers.exec {
    workingDir = rootProject.projectDir
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText

fun gitCommitCount(): Int = try {
    gitCommitCountOutput.get().trim().toIntOrNull() ?: 1
} catch (e: Exception) {
    1
}

/** 本地调试用：`-PversionCodeOverride=14` 覆盖 git 提交数，便于反复覆盖安装。 */
fun localVersionCodeOverride(): Int? =
    (project.findProperty("versionCodeOverride") as String?)?.trim()?.toIntOrNull()

/** 本地调试用：`-PversionNameOverride=1.0.0-dev` 覆盖 tag 推导结果。 */
fun localVersionNameOverride(): String? =
    (project.findProperty("versionNameOverride") as String?)?.trim()?.takeIf { it.isNotEmpty() }

// AI 内置文档（~/.nox/docs）的源在仓库根的 docs-site/docs：同一份 Markdown 既构建 VitePress
// 文档站，也在这里复制进 assets/docs 打进 APK，保证「站上看到的」与「AI 读到的」永远一致。
// 用纯 Gradle Copy 实现，APK 构建不依赖 Node —— CI 与 F-Droid 只装 JDK+Gradle 即可。
val aiDocsGeneratedDir = layout.buildDirectory.dir("generated/aiDocs")
val syncAiDocs = tasks.register<Sync>("syncAiDocs") {
    description = "把 docs-site/docs 下的用户文档复制进 assets/docs，供 AI 在容器内查阅"
    from(rootProject.layout.projectDirectory.dir("docs-site/docs")) {
        include("**/*.md")
        // index.md 是文档站首页（hero 布局的 frontmatter），对 AI 没有意义
        exclude("index.md")
    }
    into(layout.buildDirectory.dir("generated/aiDocs/docs"))
}

// 内置更新日志：把仓库根的 CHANGELOG.md 解析成 assets/changelog.json 打进 APK。
//
// 为什么不再从 git log 生成：git log 只能给标题，而且是给开发者看的。
// 用户需要的是「这版改了什么、对我有什么影响」，这必须人工撰写；
// CI 配置、构建脚本这类用户感受不到的改动也不该出现在更新日记里。
//
// CHANGELOG.md 是唯一事实源，App 内关于页与 GitHub Release 页都从它读取，
// 保证两处内容一致（此前两处各自从 git log 生成，顺序与内容都对不齐）。
val changelogGeneratedDir = layout.buildDirectory.dir("generated/changelog")
val genChangelog = tasks.register("generateChangelog") {
    description = "把 CHANGELOG.md 解析成内置更新日志 assets/changelog.json"
    val outDir = changelogGeneratedDir
    val rootDir = rootProject.projectDir
    val src = rootProject.file("CHANGELOG.md")
    val parser = rootProject.file("scripts/parse-changelog.py")
    inputs.file(src)
    // 解析脚本本身也是输入：此前只声明 CHANGELOG.md 是输入，
    // 改了脚本（输出结构从 sections 改为 items）任务被判为 UP-TO-DATE 不重跑，
    // 导致包里的 changelog.json 一直是旧结构，App 只剩摘要、条目全丢。
    inputs.file(parser)
    outputs.dir(outDir)

    doLast {
        require(src.exists()) { "CHANGELOG.md 不存在，关于页的更新日记会为空" }
        val dir = outDir.get().asFile
        dir.mkdirs()
        // 复用与 CI 相同的解析脚本，避免两处格式理解不一致
        val proc = ProcessBuilder("python3", "scripts/parse-changelog.py")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) {
            throw GradleException("parse-changelog.py 失败：$out")
        }
        File(dir, "changelog.json").writeText(out)
    }
}

android {
    namespace = "com.nox"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.aigo"
        minSdk = 26
        // 仍锁 targetSdk 28，但阻塞项已不是 PRoot：proot 全套改由 jniLibs 装到 nativeLibraryDir
        // （见 sourceSets 与 packaging.jniLibs 注释），W^X 不再挡容器启动。升级前待解决：
        //   1. 外部工作区（EXTERNAL_LOCAL）目前用 File API 直读 /storage/emulated/0，29+ 需改走
        //      MANAGE_EXTERNAL_STORAGE；
        //   2. TerminalKeepaliveService 的 dataSync 前台服务在 35 上有 24 小时内累计 6 小时上限，
        //      保活需换 specialUse。
        // 代价：不能上 Google Play。
        targetSdk = 28
        // 版本号唯一事实源是 git：versionName 来自 tag（无 tag 时为基线+dev 后缀），
        // versionCode 来自提交数。之前这里写死 `6` / "1.0.5"，两个 git 推导函数形同虚设，
        // 导致每次发版 versionCode 不变、应用内更新自检（ApkVerifier 要求更高 versionCode）永远失败。
        // 本地调试开关：-PversionCodeOverride=14 -PversionNameOverride=1.0.0-dev
        // 固定版本号后反复重建可直接覆盖安装，不必先卸载，也不会让应用内更新误判。
        versionCode = localVersionCodeOverride() ?: gitCommitCount()
        versionName = localVersionNameOverride() ?: gitVersionName()

        // 插件上传暂存区令牌：由 local.properties 的 PLUGIN_INBOX_TOKEN 提供。
        // 不写进源码，避免明文凭据随仓库分发；本机没有配置时留空，上传会提示未配置。
        val inboxToken = runCatching {
            Properties().apply {
                rootProject.file("local.properties").inputStream().use { load(it) }
            }.getProperty("PLUGIN_INBOX_TOKEN").orEmpty()
        }.getOrDefault("")
        buildConfigField("String", "PLUGIN_INBOX_TOKEN", "\"$inboxToken\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // 按容器镜像拆包：universal 同时含 arm + x86 两套 Alpine rootfs/proot（兼容所有设备但体积大），
    // armsolo 仅含 arm（对应 arm64-v8a），x86solo 仅含 x86（对应 x86_64）
    // ——单架构包体积约为通用包的一半。assets/container/... 由各 flavor 的 sourceSet 提供，
    // ContainerInstaller.ASSET_DIR 在 universal 下按设备 ABI 选其一，在 solo 包里只剩一套故一定命中。
    //
    // 注意：defaultConfig 不再固定 abiFilters，改由各 flavor 维度决定；universal 默认含全部
    // 依赖的 ABI（arm64-v8a + x86_64），单架构包各自收敛到单一 ABI，避免错架构设备加载错误的镜像。
    flavorDimensions += "container"
    productFlavors {
        create("universal") {
            dimension = "container"
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        create("armsolo") {
            dimension = "container"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("x86solo") {
            dimension = "container"
            ndk { abiFilters += "x86_64" }
        }
    }

    // 容器镜像按 flavor 共享 sourceSet：
    //   _armAssets 仅物理一份 arm 镜像，由 universal + armsolo 共享
    //   _x86Assets 仅物理一份 x86 镜像，由 universal + x86solo 共享
    // 这样单架构包天然只含一套镜像（AGP 资源并集合并：未被引用的目录不参与），
    // universal 含两套；镜像二进制在仓库里也只各一份，无重复。
    // 放弃 ignoreAssetsPattern=dir:x86：实测对 container/x86 整树无效（rc1 已证实）。
    // proot 全套（proot 本体 + 两个 loader + libtalloc/libandroid-shmem）走 jniLibs 而非 assets：
    // 安装器只把 APK 内 lib/<abi>/lib*.so 解压到 nativeLibraryDir，而那是 App 目录里唯一
    // 允许 execve 的位置（SELinux 标签 apk_data_file）。rootfs 仍在 assets→filesDir——
    // 客户机二进制不由内核 execve，而是 proot 的 loader 用 mmap(PROT_EXEC) 映射进内存，
    // 不受 W^X 约束。故命名必须是 lib*.so（libproot-loader32.so 实为 32 位 ELF，同样有效）。
    sourceSets {
        getByName("universal") { assets.srcDir("src/_armAssets") }
        getByName("universal") { assets.srcDir("src/_x86Assets") }
        getByName("armsolo") { assets.srcDir("src/_armAssets") }
        getByName("x86solo") { assets.srcDir("src/_x86Assets") }
        getByName("universal") { jniLibs.srcDirs("src/_armJniLibs", "src/_x86JniLibs") }
        getByName("armsolo") { jniLibs.srcDir("src/_armJniLibs") }
        getByName("x86solo") { jniLibs.srcDir("src/_x86JniLibs") }
        // 文档不放在 app/src/main/assets 下，改由 syncAiDocs 从 docs-site/docs 生成后并入
        getByName("main") { assets.srcDir(aiDocsGeneratedDir) }
        getByName("main") { assets.srcDir(changelogGeneratedDir) }
    }

    buildTypes {
        // debug 不加包名后缀：与 release 同为 com.aigo，可直接覆盖安装，数据与容器 rootfs 全部保留。
        //
        // 上游原本给 debug 加 .debug 后缀，目的是让 IDE 的调试包不覆盖已装的正式版。
        // 但本项目由 root 用户自建自用，覆盖安装本就是想要的行为，加后缀反而带来三个实际问题：
        //   1. 同机出现两个应用（com.aigo 与 com.aigo.debug），桌面看着像装了两份；
        //   2. debug 变体私有目录不同，之前解压的容器 rootfs、工作区项目全部"消失"；
        //   3. debug 包 debuggable=true，Android 15+ 会弹 16 KB 页对齐兼容性警告。
        // 因此统一为同一包名。签名若与已装包不同，安装时会提示签名冲突，需先卸载一次。
        debug {
            // 保持与 release 相同的 applicationId，不做后缀。
            // 显式声明而非留空，是为了让后续维护者一眼看出这是刻意为之。
            applicationIdSuffix = null
            // 签名也必须与 release 一致：包名相同而签名不同时，Android 会拒绝覆盖安装
            // （INSTALL_FAILED_UPDATE_INCOMPATIBLE）。有 keystore 就用正式签名，
            // 没有（如 CI 未配 Secrets 的 PR 构建）才退回默认 debug 签名。
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            // 本地调试可加 -PskipMinify 跳过 R8 与资源压缩：实测能省 3~5 分钟。
            // CI 与正式发版不要带这个参数，产物必须经过混淆与压缩。
            val skipMinify = (project.findProperty("skipMinify") as String?)?.toBoolean() == true
            isMinifyEnabled = !skipMinify
            isShrinkResources = !skipMinify
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 可复现构建：AGP 8.3+ 默认往 APK 写 version-control-info.textproto，
            // 其中 local_root_path 是构建机绝对路径，GitHub CI 与 F-Droid 构建服务器必然不同，
            // 会让两边 APK 字节不一致导致可复现对比失败。关闭之（版本溯源由 git tag/CI 保证）。
            vcsInfo {
                include = false
            }
        }
        // beta 测试版：继承 release 的全部配置（签名/R8/资源压缩/proguard），
        // 同样不加包名后缀——理由见上面 debug 的注释，覆盖安装即可。
        // 由 .github/workflows/beta.yml 在 push main 时构建并上传 Artifacts，供测机验证。
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = null
            // :terminal-emulator / :terminal-view 无 beta 变体，依赖解析回退到它们的 release 变体。
            matchingFallbacks += "release"
        }
        // 本仓库（Nox）固定使用标准版本号；包名即 com.nox，不带任何后缀。
        create("miuix") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // sora-editor 的 language-textmate 在 API 33 以下设备必须依赖核心库脱糖（minSdk 26）。
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // UserService 走 AIDL 跨进程接口（app/src/main/aidl），显式开启以免依赖 AGP 默认值。
        aidl = true
        buildConfig = true
    }



    packaging {
        jniLibs {
            // 必须 true：默认（false）下 .so 不解压、直接从 APK 映射，nativeLibraryDir 会是空目录，
            // execve libproot.so 得到 ENOENT。代价是安装后多占一份解压副本（proot 全套约 300KB）。
            useLegacyPackaging = true
            // proot 全套是外部预编译产物，不得被构建流程后处理：当前 NDK 的 strip 认不出它们而
            // 自行跳过，显式声明避开 AGP/NDK 升版后 strip 真的生效而改动字节。
            keepDebugSymbols += setOf(
                "**/libproot.so",
                "**/libproot-loader.so",
                "**/libproot-loader32.so",
                "**/libtalloc.so",
                "**/libandroid-shmem.so"
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/INDEX.LIST"
            excludes += "/sshj.properties"
            excludes += "/kotlin-tooling-metadata.json"
            excludes += "/DebugProbesKt.bin"
            // bcprov-jdk18on 1.78.1 与 jspecify 都带该 multi-release OSGi 元数据文件，打包路径冲突；
            // 仅是 OSGi MANIFEST，排除即可。
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    // targetSdk 故意锁定 28（原因见 defaultConfig 处注释），代价是不进 Google Play——故关闭该平台的过期 targetSdk 检查。
    // 同时关闭 release 构建的 lint 检查：本仓库只出 GitHub Release 不上 Play，
    // lintVital 在 R8/打包阶段额外吃 CPU 与内存（2 核 7GB runner 易 OOM），且其发现不阻塞发布。
    lint {
        disable += "ExpiredTargetSdkVersion"
        checkReleaseBuilds = false
        abortOnError = false
    }

    // 单测环境不 mock android.jar：让 Log 等调用返回默认值而非抛 "not mocked"，
    // 否则被测代码里偶发的 FileLogger 日志调用会让纯 JVM 单测崩溃。
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric（迁移测试）需要真实 Android resources
        unitTests.isIncludeAndroidResources = true
    }

    // 迁移测试（MigrationTestHelper）在 Robolectric 下从合并后的 assets 读 Room 导出的 schema，
    // 路径为 <databaseClass 全限定名>/<version>.json；AGP 不会把 unit test sourceSet 的 assets
    // 合并进 Robolectric 的 assets（test_config.properties 指向 mergeUniversalDebugAssets），
    // 故挂在 debug 变体上：迁移测试只跑 universalDebug，release 包不会带上 schema。
    sourceSets {
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
}

// 彻底禁用 lintVital<Flavor>Release 任务（三 flavor 各一个），
// 使其不进入 assembleRelease 的任务图——比 lint.checkReleaseBuilds=false 更省构建开销与内存。
// 仅在 release 任务图执行前禁用，避免影响开发期 debug lint。
gradle.projectsEvaluated {
    tasks.matching { it.name.startsWith("lintVital") }.configureEach { enabled = false }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2026.01.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.animation:animation")

    // Vico 图表（Token 统计趋势图）
    implementation("com.patrykandpatrick.vico:compose-m3:2.4.4")

    // Lifecycle + ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // Hilt 依赖注入
    implementation("com.google.dagger:hilt-android:2.56.1")
    ksp("com.google.dagger:hilt-compiler:2.56.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room 数据库
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.4")

    // 网络请求
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // HTML 解析与清洗 (用于 WebFetchTool)
    implementation("org.jsoup:jsoup:1.18.1")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Kotlin 序列化
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // YAML 解析 (用于 Skill Frontmatter)
    implementation("org.yaml:snakeyaml:2.2")

    // 远程同步 (SFTP/FTP) 与内置 FTP 服务端
    implementation("com.hierynomus:sshj:0.38.0")
    // BouncyCastle：sshj 0.38.0 用 X25519 密钥交换，Android 自带裁剪版 BC 不含该算法，
    // 需显式引入完整版并注册替换（见 AIEditorApp.registerBouncyCastle）。
    // 1.75 命中 CVE-2024-30172（Ed25519 验证死循环 DoS），1.78 起修复；取与 sshj 0.39.0 对齐的 1.78.1。
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("commons-net:commons-net:3.10.0")
    implementation("org.apache.ftpserver:ftpserver-core:1.2.0")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // 容器：解压 Alpine rootfs tar.gz（正确处理 symlink/hardlink/权限位）
    implementation("org.apache.commons:commons-compress:1.26.2")
    // xz 解压支持：commons-compress 的 XZCompressorInputStream 依赖此库（解压用户导入的 .tar.xz 镜像）
    implementation("org.tukaani:xz:1.10")

    // Termux 开源终端组件：terminal-emulator 负责 VT100/ANSI 解析与 PTY（自带 native .so），
    // terminal-view 是渲染用的 Android View。经 JitPack 分发（com.github.<user>.<repo> 坐标形式），
    // 避免自行实现终端模拟器。
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))

    // Material Icons
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // Lucide Icons
    implementation("br.com.devsrsouza.compose.icons:feather:1.1.1")

    // 可拖拽排序列表（长按拖拽手势，提供商排序用）
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    // Markdown Renderer
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.41.0")
    // Markdown Renderer — Code Syntax Highlighting
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.41.0")
    // 语法高亮引擎（markdown-renderer-code 传递引入，显式声明以供 diff 视图直接使用）
    implementation("dev.snipme:highlights-jvm:1.1.0")
    // LaTeX 数学公式渲染（纯 JVM，无 .so；jlatexmath-android 内置字体资源）
    implementation("ru.noties:jlatexmath-android:0.2.0")

    // 代码编辑器（独立编辑器页）。只取纯 JVM 模块：language-treesitter 与 oniguruma-native 含 .so，
    // 会与 ABI flavor 拆分和 F-Droid 可复现构建冲突，故不引入。
    implementation(platform("io.github.rosemoe:editor-bom:0.24.6"))
    implementation("io.github.rosemoe:editor")
    implementation("io.github.rosemoe:language-textmate")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // WorkManager — 保活兜底：周期检查 TerminalKeepaliveService 存活并拉起（KeepaliveWorker）
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.hilt:hilt-work:1.2.0")

    // Android WebKit 扩展（提供现代深色模式 WebSettingsCompat / ForceDark 支持）
    implementation("androidx.webkit:webkit:1.12.1")

    // 聊天内联视频播放（1.11.1 在本地 Gradle 缓存中，可离线构建）
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    // Shizuku：以 adb shell（uid 2000）身份执行命令。api 提供 Shizuku 类与 UserService 绑定，
    // provider 注册 ShizukuProvider（见 AndroidManifest）以跨进程获取 binder。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Core Android（1.17.0 起提供 NotificationCompat 的实时通知推广接口 setRequestPromotedOngoing）
    implementation("androidx.core:core:1.17.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    // 迁移测试：MigrationTestHelper + Robolectric（在 JVM 上跑 Room 迁移，需真实 resources）
    testImplementation("androidx.room:room-testing:2.7.1")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.1.5")
    // 协程测试：runTest 虚拟时间（替代 runBlocking）+ Dispatchers.setMain（ViewModel 测试基建）
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // MockK：Kotlin 原生 mock（coEvery/coVerify 协程支持），用于 ViewModel/Repository 依赖打桩
    testImplementation("io.mockk:mockk:1.14.11")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// 迁移版本号对账：本地手动跑 `./gradlew checkMigrations`（CI 在 workflow 里直接跑脚本）。
// 校验迁移编号连续、SCHEMA_VERSION 一致、已发布迁移未被篡改/复用（详见 scripts/check_migrations.py）。
tasks.register<Exec>("checkMigrations") {
    commandLine("python3", "scripts/check_migrations.py")
    workingDir(rootProject.projectDir)
}

// assets 合并前必须先生成文档，否则首次构建（或 clean 后）APK 里会没有 docs/。
tasks.named("preBuild") { dependsOn(syncAiDocs) }
