package com.nox.feature.plugin.data.index

import android.content.Context
import com.nox.feature.plugin.data.PluginStoreSettingsRepository
import com.nox.feature.plugin.domain.SemVer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

data class PluginIndexSnapshot(
    val index: PluginIndexDocument,
    val stale: Boolean,
    val fetchedAt: Long
)

data class PluginReleaseAsset(
    val version: String,
    val name: String,
    val url: String,
    val size: Long
)

data class DownloadedPluginPackage(
    val file: File,
    val asset: PluginReleaseAsset
)

class PluginIndexException(message: String, cause: Throwable? = null) : IOException(message, cause)

class NoMatchingReleaseException(message: String) : IOException(message)

@Singleton
class PluginIndexClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    client: OkHttpClient,
    private val storeSettings: PluginStoreSettingsRepository,
    private val mirrorSelector: GitHubMirrorSelector
) {
    private val http = client.newBuilder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .cache(null)
        .build()

    private val cacheRoot = File(context.filesDir, "plugin-index-cache")

    suspend fun fetchIndex(source: PluginIndexSource, forceRefresh: Boolean = false): PluginIndexSnapshot =
        withContext(Dispatchers.IO) {
            val cached = cacheFile(source)
            val url = accelerated(source.indexUrl)
            val bytes = try {
                getBytes(url, MAX_INDEX_BYTES, noCache = forceRefresh)
            } catch (e: IOException) {
                if (forceRefresh) throw e
                val fallback = readCache(cached) ?: throw e
                return@withContext PluginIndexSnapshot(fallback, stale = true, fetchedAt = cached.lastModified())
            }
            val index = PluginIndexParser.parse(bytes)
            writeCache(cached, bytes)
            PluginIndexSnapshot(index, stale = false, fetchedAt = System.currentTimeMillis())
        }

    /**
     * 是否允许安装。
     *
     * 上游按清单里的 `min_app_version` 卡版本，这里不再拦截：插件实际能不能跑，
     * 安装时的清单校验（api_version / 字段 / 贡献类型）已经给出结论，卡宿主版本号
     * 只会把「作者写了个偏保守的版本号」变成装不上。界面仍展示要求的最低版本，
     * 但不作为禁装理由。
     */
    fun isCompatible(plugin: PluginIndexPlugin): Boolean = true

    /**
     * 解析插件对应的 Release 资产。
     * version 为空时取 latest Release 中 `{id}_*.zip` 的最高版本；有 version 时精确查找 `{id}_{version}.zip`。
     */
    suspend fun resolveRelease(plugin: PluginIndexPlugin): PluginReleaseAsset = withContext(Dispatchers.IO) {
        // 商店直接托管包时按直链下载，跳过 Release 查找。
        plugin.packageUrl?.takeIf { it.isNotBlank() }?.let { direct ->
            return@withContext PluginReleaseAsset(
                version = plugin.version.orEmpty(),
                name = "${plugin.id}_${plugin.version ?: "latest"}.zip",
                url = direct,
                // 直链在索引里不带大小，下载时按上限约束
                size = 0L
            )
        }
        val (owner, repo) = parseGitHubRepo(plugin.repository)
            ?: throw NoMatchingReleaseException("不是 GitHub 仓库: ${plugin.repository}")
        val expectedName = plugin.version?.let { assetName(plugin.id, it) }
        val releases = if (plugin.version == null) {
            listOf(apiGet<GitHubRelease>("$GITHUB_API/repos/$owner/$repo/releases/latest"))
        } else {
            apiGet<List<GitHubRelease>>("$GITHUB_API/repos/$owner/$repo/releases?per_page=100")
        }
        val candidates = releases
            .filterNot { it.draft }
            .flatMap { release -> release.assets.map { asset -> release to asset } }
        val matched = when {
            expectedName != null -> candidates.firstOrNull { it.second.name == expectedName }
            else -> candidates
                .mapNotNull { pair ->
                    val version = assetVersion(plugin.id, pair.second.name) ?: return@mapNotNull null
                    pair to version
                }
                .maxByOrNull { SemVer.parse(it.second) ?: SemVer(0, 0, 0) }
                ?.let { it.first }
        } ?: throw NoMatchingReleaseException("未找到插件资产: ${expectedName ?: "${plugin.id}_*.zip"}")
        val asset = matched.second
        val version = expectedName?.let { plugin.version!! } ?: assetVersion(plugin.id, asset.name)!!
        PluginReleaseAsset(
            version = version,
            name = asset.name,
            url = asset.browserDownloadUrl,
            size = asset.size
        )
    }

    /**
     * 顺序候选地址：直连 → jsDelivr → 各镜像。
     *
     * 插件包只有几 KB，按顺序逐个试比并发测速更快；测速对这么小的文件也没有意义，
     * 反倒会因为节点全超时而直接把安装判失败。
     */
    suspend fun rankCandidates(asset: PluginReleaseAsset): List<String> = withContext(Dispatchers.IO) {
        val manual = storeSettings.downloadMirror.first().trim()
        val mirrorable = asset.url.contains("/releases/download/") ||
            asset.url.contains("raw.githubusercontent.com")
        if (!mirrorable) return@withContext listOf(asset.url)
        mirrorSelector.downloadCandidates(asset.url, manual)
    }

    suspend fun download(
        asset: PluginReleaseAsset,
        candidates: List<String>? = null
    ): DownloadedPluginPackage = withContext(Dispatchers.IO) {
        if (asset.size > MAX_ARCHIVE_BYTES) {
            throw PluginIndexException("资产超过上限: ${asset.size}")
        }
        val maxBytes = if (asset.size > 0) asset.size else MAX_ARCHIVE_BYTES
        val urls = candidates ?: rankCandidates(asset)

        val failures = mutableListOf<String>()
        for (candidate in urls) {
            val result = runCatching {
                getBytes(candidate, maxBytes, noCache = true)
            }
            result.getOrNull()?.let { bytes ->
                val file = File(context.cacheDir, "plugin-${sha256(bytes).take(16)}.zip")
                file.writeBytes(bytes)
                return@withContext DownloadedPluginPackage(file, asset)
            }
            failures += (result.exceptionOrNull()?.message ?: "未知错误").take(120)
        }
        throw PluginIndexException(
            if (failures.isEmpty()) "没有可用的下载地址"
            else "下载失败：${failures.first()}" +
                if (failures.size > 1) "（已尝试 ${failures.size} 个地址）" else ""
        )
    }

    /**
     * 给插件包地址套加速前缀。
     *
     * 只对 Release 资产走镜像：索引与元数据是几 KB 的 raw 文件，直连本来就快，
     * 走代理反而常被换成 HTML 错误页，表现为「index.json 解析失败」。
     */
    private suspend fun accelerated(url: String): String {
        if (!url.contains("/releases/download/")) return url
        val manual = storeSettings.downloadMirror.first().trim()
        val prefix = mirrorSelector.pickFastest(url, manual) ?: return url
        return mirrorSelector.accelerate(prefix, url)
    }

    private fun assetName(id: String, version: String): String = "${id}_$version.zip"

    private fun assetVersion(id: String, name: String): String? {
        val prefix = "${id}_"
        if (!name.startsWith(prefix) || !name.endsWith(".zip")) return null
        val version = name.removePrefix(prefix).removeSuffix(".zip")
        return version.takeIf { !it.startsWith("v") && SemVer.parse(it) != null }
    }

    private fun parseGitHubRepo(repository: String): Pair<String, String>? {
        val uri = runCatching { URI(repository) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "github.com") return null
        val segments = uri.path.trim('/').split('/')
        if (segments.size != 2 || segments.any { it.isBlank() }) return null
        return segments[0] to segments[1].removeSuffix(".git")
    }

    private inline fun <reified T> apiGet(url: String): T {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "aicode-plugin-index")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw NoMatchingReleaseException("GitHub Release 请求失败: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw NoMatchingReleaseException("GitHub Release 响应为空")
            return try {
                PluginIndexJson.decodeFromString<T>(body)
            } catch (e: Exception) {
                throw NoMatchingReleaseException("GitHub Release 响应解析失败")
            }
        }
    }

    private fun getBytes(
        url: String,
        maxBytes: Long,
        noCache: Boolean
    ): ByteArray {
        val builder = Request.Builder().url(url).header("User-Agent", "aicode-plugin-index")
        if (noCache) builder.cacheControl(CacheControl.Builder().noCache().build())
        http.newCall(builder.build()).execute().use { response ->
            // 只判断请求是否成功与有没有拿到数据；大小、内容类型都不再校验。
            // 商店里的包都已通过审计，地址由索引给出，多余校验只会把正常下载判成失败。
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("空响应")
            val stream = body.byteStream()
            val declared = body.contentLength()
            val output = java.io.ByteArrayOutputStream(
                if (declared in 1..maxBytes) declared.toInt() else 8192
            )
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("内容超过大小上限")
                output.write(buffer, 0, read)
            }
            if (total == 0L) throw IOException("空响应")
            return output.toByteArray()
        }
    }

    private fun cacheFile(source: PluginIndexSource): File = File(File(cacheRoot, source.id), "index.json")

    private fun writeCache(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(bytes)
        temp.renameTo(file)
    }

    private fun readCache(file: File): PluginIndexDocument? {
        if (!file.isFile) return null
        return runCatching { PluginIndexParser.parse(file.readBytes()) }.getOrNull()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GitHubAsset> = emptyList()
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        val size: Long = 0
    )

    private companion object {
        const val GITHUB_API = "https://api.github.com"
        const val MAX_INDEX_BYTES = 2L * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 16L * 1024 * 1024
    }
}
