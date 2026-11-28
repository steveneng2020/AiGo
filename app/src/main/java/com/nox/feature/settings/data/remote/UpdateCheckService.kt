package com.nox.feature.settings.data.remote

import android.content.Context
import android.os.Build
import com.nox.R
import com.nox.feature.settings.data.repository.UpdateChannel
import com.nox.feature.settings.presentation.component.compareVersions
import com.nox.feature.settings.presentation.component.parseVersionTag
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Release 里的一个更新包资产。 */
data class UpdateAsset(
    val name: String,
    val url: String,
    val size: Long,
    /**
     * GitHub API 的 `digest` 字段（形如 `sha256:...`）。较早的 release 可能没有该字段，
     * 缺失时不阻断下载，只跳过摘要校验（签名校验才是安全锚，见 [ApkVerifier]）。
     */
    val sha256: String?
)

/** 更新日记里的一条：分类 + 标题 + 具体说明。 */
data class ChangelogItem(
    val title: String,
    val detail: String,
    /**
     * 分类：added / improved / fixed / synced，空串表示未分类。
     *
     * v1.0.5 及更早的 CHANGELOG 没有 ### 分类小标题，解析结果为空串，
     * UI 遇到空串时直接平铺、不加标签——不能因为格式演进把老版本显示弄乱。
     */
    val kind: String = ""
)

/** 单个版本的更新日志与更新包。 */
data class VersionUpdate(
    val tag: String,
    val changelog: String,
    val asset: UpdateAsset? = null,
    /** Release 发布时间（ISO-8601），关于页的更新日志列表用它显示日期；旧接口无此字段时为空串。 */
    val publishedAt: String = "",
    /** 一句话概括这一版，来自 CHANGELOG.md 版本标题下的首段。 */
    val summary: String = "",
    /** 结构化条目；来自内置 changelog.json 时有值，来自 GitHub Release 时为空。 */
    val items: List<ChangelogItem> = emptyList()
)

/** 拉取到的更新信息：最新版本号 + 从当前版本到最新的更新日志。 */
data class UpdateInfo(
    val latestTag: String,
    val changelog: String,
    val updates: List<VersionUpdate>
) {
    /** 最新版本的更新包（应用内下载用）；该 Release 没挂 APK 资产时为 null。 */
    val latestAsset: UpdateAsset? get() = updates.firstOrNull()?.asset
}

/** 检查更新结果。 */
sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class NewVersion(val info: UpdateInfo) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * 从 GitHub Releases 拉取版本信息并生成更新日志。
 *
 * 稳定版通道过滤预发布（RC），最新版通道包含预发布；只统计严格高于当前版本的 release，
 * 更新日志按版本从高到低拼接（版本号 + 正文，轻量清理 markdown 标题/粗体）。
 * 结果不落盘，由调用方按需展示。
 */
@Singleton
class UpdateCheckService @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    suspend fun checkForUpdate(currentVersion: String, channel: UpdateChannel): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val releases = fetchReleases() ?: return@runCatching UpdateCheckResult.UpToDate

                val updates = releases
                    .filter { if (channel == UpdateChannel.STABLE) !it.prerelease else true }
                    .filter { compareVersions(it.version, currentVersion) > 0 }
                    .sortedWith { a, b -> compareVersions(b.version, a.version) }

                if (updates.isEmpty()) {
                    UpdateCheckResult.UpToDate
                } else {
                    val versionUpdates = updates.map { toVersionUpdate(it) }
                    UpdateCheckResult.NewVersion(
                        UpdateInfo(
                            latestTag = versionUpdates.first().tag,
                            changelog = versionUpdates.joinToString("\n\n") { "${it.tag}\n${it.changelog}" },
                            updates = versionUpdates
                        )
                    )
                }
            }.getOrElse { UpdateCheckResult.Error(it.message ?: context.getString(R.string.about_network_error)) }
        }

    /**
     * 拉取全部历史版本（不按当前版本过滤），供「更新内容」卡片与「更多更新日记」使用。
     *
     * [checkForUpdate] 只返回「比当前版本新」的版本，无法充当历史日志；这里并集处理：
     * 历史页面需要从新到旧完整列表，所以单独开一个入口，并带上发布时间供列表展示。
     * 失败时返回空列表由调用方展示空态，不弹错——关于页不应因为网络问题变成错误页。
     */
    /**
     * 更新日记：内置 + 远端合并，两侧各管一半场景。
     *
     * 内置数据由 Gradle 任务 `generateChangelog` 在构建时解析仓库根 `CHANGELOG.md` 生成，
     * 写入 assets/changelog.json，跟着 APK 走。
     *
     * | 场景 | 谁兜住 |
     * |---|---|
     * | 断网 / GitHub API 限流（匿名 60 次/小时）/ 代理不通 | 内置（`remote` 为空 → 直接返回 bundled） |
     * | 装了旧包、仓库已有新版本 | 远端（带 release 资产，更新检查要用） |
     * | 本地包比远端新（debug/预发，还没发 Release） | 内置（按 tag 补齐缺的版本） |
     *
     * 合并策略按 tag 去重且**优先保留远端**：远端那条带 `asset`，
     * 更新检查靠它下载；内置那条只有说明文字。
     * 任一侧为空时直接返回另一侧，所以「网络挂了」只会少掉新版本，
     * 不会变成空白页。
     */
    suspend fun fetchAllReleases(): List<VersionUpdate> = withContext(Dispatchers.IO) {
        val bundled = readBundledChangelog()
        val remote = runCatching {
            fetchReleases()
                ?.sortedWith { a, b -> compareVersions(b.version, a.version) }
                ?.map { toVersionUpdate(it) }
                .orEmpty()
        }.getOrDefault(emptyList())
        // 合并两侧：远端负责「装了旧包也能看到新版本」，内置负责「本地包比远端新」
        // （debug/预发布阶段常见：包里已有 v1.0.3 的说明，但 v1.0.3 还没发 Release）。
        // 按 tag 去重时优先保留远端——它带 release 资产，更新检查要用。
        if (remote.isEmpty()) return@withContext bundled
        if (bundled.isEmpty()) return@withContext remote
        val merged = LinkedHashMap<String, VersionUpdate>()
        remote.forEach { merged[it.tag] = it }
        bundled.forEach { entry ->
            if (!merged.containsKey(entry.tag)) {
                merged[entry.tag] = entry
            }
        }
        // compareVersions 按 '.' 切分后逐段 toIntOrNull()，遇到 'v' 前缀会把它
        // 和后面第一段一起吞掉（"v1.0.3" -> [0, 3]，主版本号丢失），
        // 所以这里先剥掉 v 再比。
        merged.values.sortedWith { a, b ->
            compareVersions(b.tag.removePrefix("v"), a.tag.removePrefix("v"))
        }
    }

    /** 读取 APK 内置的更新日志（assets/changelog.json）。失败返回空列表。 */
    private fun readBundledChangelog(): List<VersionUpdate> = runCatching {
        val raw = context.assets.open("changelog.json").bufferedReader().use { it.readText() }
        val root = JsonParser.parseString(raw).asJsonObject
        val entries = root.getAsJsonArray("entries") ?: return emptyList()
        entries.mapNotNull { el ->
            val obj = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val tag = obj.get("tag")?.asString ?: return@mapNotNull null
            val date = obj.get("date")?.asString.orEmpty()
            val summary = obj.get("summary")?.asString.orEmpty()
            val items = obj.getAsJsonArray("items")?.mapNotNull { itemEl ->
                val item = runCatching { itemEl.asJsonObject }.getOrNull() ?: return@mapNotNull null
                ChangelogItem(
                    title = item.get("title")?.asString.orEmpty(),
                    detail = item.get("detail")?.asString.orEmpty(),
                    kind = item.get("kind")?.asString.orEmpty()
                )
            }.orEmpty()
            VersionUpdate(
                tag = tag,
                // changelog 保留纯文本形态，供不支持分条渲染的场景（如更新弹窗）使用
                changelog = items.joinToString("\n") { "· ${it.title}：${it.detail}" },
                publishedAt = date,
                summary = summary,
                items = items
            )
        }
    }.getOrDefault(emptyList())

    /** 拉取 GitHub Releases 并解析；网络或格式错误返回 null，由调用方决定降级方式。 */
    private fun fetchReleases(): List<ReleaseInfo>? = runCatching {
        val req = okhttp3.Request.Builder()
            .url("$GITHUB_RELEASES_API?per_page=100")
            .header("Accept", "application/vnd.github+json")
            .build()
        SHARED_CLIENT.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string().orEmpty()
            val array = runCatching { JsonParser.parseString(body).asJsonArray }.getOrNull()
                ?: return@use null
            array.mapNotNull { el ->
                val obj = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
                val tag = obj.get("tag_name")?.asString ?: return@mapNotNull null
                val version = parseVersionTag(tag) ?: return@mapNotNull null
                ReleaseInfo(
                    version = version,
                    rawTag = tag,
                    notes = obj.get("body")?.asString.orEmpty(),
                    prerelease = obj.get("prerelease")?.asBoolean ?: false,
                    publishedAt = obj.get("published_at")?.asString.orEmpty(),
                    assets = parseAssets(obj)
                )
            }
        }
    }.getOrNull()

    private fun toVersionUpdate(release: ReleaseInfo): VersionUpdate {
        val notes = release.notes.trim()
        // Release 正文由 CHANGELOG.md 解析生成（见 android-release.yml），结构与内置
        // changelog.json 同源：第一段是概括，之后每行 `- **短语**：说明` 是条目。
        // 这里还原成结构化数据，否则关于页会退化成分不了条的纯文本，
        // 与内置数据渲染出两种样式（用户看得见的格式不一致）。
        val (summary, items) = parseReleaseNotes(notes)
        return VersionUpdate(
            tag = release.rawTag,
            changelog = if (notes.isEmpty()) {
                context.getString(R.string.about_no_changelog)
            } else {
                cleanMarkdown(notes)
            },
            asset = selectUpdateAsset(release.assets, release.rawTag, preferredAbiToken(Build.SUPPORTED_ABIS.toList())),
            publishedAt = release.publishedAt,
            summary = summary,
            items = items
        )
    }

    /**
     * 把 Release 正文解析成「概括 + 条目」。
     *
     * 认得的格式（就是本仓库 CHANGELOG.md 的写法）：
     * ```
     * 一句话概括。
     *
     * - **短语**：说明
     * - **短语**：说明
     * ```
     * 认不出的格式一律退化为空 items，调用方会回落到 changelog 纯文本渲染。
     */
    private fun parseReleaseNotes(notes: String): Pair<String, List<ChangelogItem>> {
        if (notes.isBlank()) return "" to emptyList()
        val itemRegex = Regex("""^-\s+\*\*(.+?)\*\*\s*[：:]\s*(.*)$""")
        var summary = ""
        val items = mutableListOf<ChangelogItem>()
        notes.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("<!--")) return@forEach
            val m = itemRegex.find(line)
            if (m != null) {
                items += ChangelogItem(
                    title = m.groupValues[1].trim(),
                    detail = m.groupValues[2].trim()
                )
                return@forEach
            }
            // 第一条非条目文本当作概括；后续散落文本忽略（不塞进条目，免得格式错乱）
            if (summary.isEmpty() && !line.startsWith("-") && !line.startsWith("*")) {
                summary = line
            }
        }
        return summary to items
    }

    /** 轻量清理 markdown：去掉行首标题符与粗体标记，并删除 GitHub 自动生成的标题/链接行，保留纯文本便于直接阅读。 */
    private fun cleanMarkdown(text: String): String = text
        .lines()
        .map { it.trimEnd().replace("**", "") }
        .map { it.replace(Regex("^#{1,6}\\s+"), "") }
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("What's Changed") ||
                t.startsWith("What's New") ||
                t.startsWith("Full Changelog:")
        }
        .joinToString("\n")

    private data class ReleaseInfo(
        val version: String,
        val rawTag: String,
        val notes: String,
        val prerelease: Boolean,
        val publishedAt: String = "",
        val assets: List<UpdateAsset>
    )

    /** 解析 Release 的资产列表；`digest` 只认 sha256，其它算法（或缺失）一律置 null。 */
    private fun parseAssets(obj: com.google.gson.JsonObject): List<UpdateAsset> =
        obj.getAsJsonArray("assets")?.mapNotNull { el ->
            val asset = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val name = asset.get("name")?.asString ?: return@mapNotNull null
            val url = asset.get("browser_download_url")?.asString ?: return@mapNotNull null
            UpdateAsset(
                name = name,
                url = url,
                size = asset.get("size")?.asLong ?: 0L,
                sha256 = asset.get("digest")?.asString
                    ?.takeIf { it.startsWith(SHA256_PREFIX, ignoreCase = true) }
                    ?.substring(SHA256_PREFIX.length)
                    ?.takeIf { it.isNotBlank() }
            )
        } ?: emptyList()

    private companion object {
        const val GITHUB_RELEASES_API = "https://api.github.com/repos/steveneng2020/AiGo/releases"
        const val SHA256_PREFIX = "sha256:"
        val SHARED_CLIENT by lazy {
            okhttp3.OkHttpClient.Builder()
                .proxyAuthenticator(com.nox.core.net.AppProxy.okHttpAuthenticator)
                .build()
        }
    }
}

/**
 * 设备主 ABI 对应的资产名架构段：按 [supportedAbis] 的优先顺序取第一个支持的架构
 * （`arm64-v8a` → `arm64`，`x86_64` → `x86_64`）。都不支持时返回 null，调用方退回 universal 包。
 */
internal fun preferredAbiToken(supportedAbis: List<String>): String? =
    supportedAbis.firstNotNullOfOrNull { abi ->
        when (abi.lowercase()) {
            "arm64-v8a" -> "arm64"
            "x86_64" -> "x86_64"
            else -> null
        }
    }

/**
 * Release 资产名里可能出现的品牌前缀。
 *
 * 历史原因保留两种：上游 CI 产 `nox-*`，本仓库 CI 产 `AiGo-*`（含构建日期与更新摘要）。
 * 只认一个前缀会让另一边的包永远匹配不到——实测改名后一度导致应用内更新退化为跳浏览器。
 */
private val UPDATE_ASSET_PREFIXES = listOf("AiGo", "nox")

/**
 * 从 Release 资产里挑出更新包。
 *
 * 资产命名由 CI 决定（`AiGo-<架构>-<版本>-<摘要>.apk`，见 `.github/workflows/android-release.yml`）。
 * 同一 Release 里还混着 `mapping.txt.gz`，所以只认 `.apk` 结尾；精确名没命中时依次退让为
 * 「含架构段」→ `universal`，都不命中返回 null（该版本没有可用安装包，只能去浏览器下载）。
 * 一律按 API 返回的资产名匹配，不自己拼下载 URL——CI 曾因段序拼错导致资产匹配不上。
 */
internal fun selectUpdateAsset(assets: List<UpdateAsset>, rawTag: String, abiToken: String?): UpdateAsset? {
    val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    for (prefix in UPDATE_ASSET_PREFIXES) {
        if (abiToken != null) {
            apks.firstOrNull { it.name.equals("$prefix-$abiToken-$rawTag.apk", ignoreCase = true) }?.let { return it }
            apks.firstOrNull { it.name.startsWith("$prefix-$abiToken-", ignoreCase = true) }?.let { return it }
        }
        apks.firstOrNull { it.name.startsWith("$prefix-universal-", ignoreCase = true) }?.let { return it }
    }
    return null
}
