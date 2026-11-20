package com.nox.feature.plugin.data.index

import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * GitHub 加速节点选择。
 *
 * 内置一批公共代理前缀，下载前先并发探测、用实测最快的那个——节点存活率与速度波动很大，
 * 固定用某一个经常踩到超时节点。
 */
@Singleton
class GitHubMirrorSelector @Inject constructor() {

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    private var cachedPrefix: String? = null

    @Volatile
    private var cachedAt: Long = 0L

    /**
     * 取当前最快的镜像前缀；全部不可用时返回 null（调用方回退直连）。
     * [manualMirror] 非空时直接用它，尊重用户在插件设置里的手动指定。
     */
    suspend fun pickFastest(url: String, manualMirror: String): String? {
        val manual = manualMirror.trim()
        if (manual.isNotEmpty()) return manual.trimEnd('/') + "/"

        val now = System.currentTimeMillis()
        cachedPrefix?.let { if (now - cachedAt < CACHE_TTL_MS) return it }

        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return null
        if (host !in MIRRORED_HOSTS) return null

        val best = measure(url)
        if (best != null) {
            cachedPrefix = best
            cachedAt = now
        }
        return best
    }

    /**
     * 按实测速度排出候选前缀，末尾追加 null（直连）兜底。
     *
     * 下载要能容错：单个节点可能超时、限速或返回错误页，只挑一个用会让整次安装失败。
     */
    suspend fun ranked(url: String, manualMirror: String, limit: Int = 4): List<String?> {
        val manual = manualMirror.trim()
        if (manual.isNotEmpty()) return listOf(manual.trimEnd('/') + "/", null)

        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
        if (host == null || host !in MIRRORED_HOSTS) return listOf(null)

        val measured = measureAll(url)
        if (measured.isEmpty()) return listOf(null)
        return measured.take(limit) + null
    }

    /**
     * 顺序下载候选：直连 → jsDelivr CDN → 各镜像前缀。
     *
     * 插件包只有几 KB，逐个试比先并发测速更快，也不会因为测速全失败而直接放弃。
     */
    fun downloadCandidates(url: String, manualMirror: String): List<String> {
        val result = linkedSetOf<String>()
        val manual = manualMirror.trim()
        if (manual.isNotEmpty()) result += manual.trimEnd('/') + "/" + url
        result += url
        // jsDelivr 是 CDN 而非 GitHub 镜像代理，支持任意文件类型；公共镜像大多只透传
        // Release 里的 apk，插件包这类 zip 走不通，所以插件下载只用直连与 CDN。
        jsDelivrUrl(url)?.let { result += it }
        return result.toList()
    }

    /** raw.githubusercontent.com 地址换成 jsDelivr 的 gh 通道地址。 */
    private fun jsDelivrUrl(url: String): String? {
        val marker = "raw.githubusercontent.com/"
        val index = url.indexOf(marker)
        if (index < 0) return null
        val rest = url.substring(index + marker.length)
        val segments = rest.split('/', limit = 4)
        if (segments.size < 4) return null
        val (owner, repo, branch, path) = segments
        return "https://fastly.jsdelivr.net/gh/$owner/$repo@$branch/$path"
    }

    /** 给 GitHub 原始地址套上前缀。 */
    fun accelerate(prefix: String?, url: String): String {
        if (prefix.isNullOrBlank()) return url
        return prefix.trimEnd('/') + "/" + url
    }

    private suspend fun measure(url: String): String? =
        measureAll(url).firstOrNull()

    /** 并发探测全部节点，按速率从高到低返回可用前缀。 */
    private suspend fun measureAll(url: String): List<String> = withContext(Dispatchers.IO) {
        val results = coroutineScope {
            MIRRORS.map { prefix ->
                async { prefix to probe(prefix.trimEnd('/') + "/" + url) }
            }.awaitAll()
        }
        results
            .filter { it.second.ok }
            .sortedByDescending { it.second.bytesPerSecond }
            .map { it.first }
    }

    /**
     * 探测单个节点。
     *
     * 用普通 GET 只读前 8KB 并校验 Content-Type：代理最常见的失效方式是返回一张 HTML
     * 错误页，状态码仍是 200，只有内容类型能区分出来；漏掉这步会把网页当成插件包。
     */
    private fun probe(target: String): ProbeResult {
        val request = Request.Builder()
            .url(target)
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Encoding", "identity")
            .build()
        val startedAt = System.nanoTime()
        return try {
            probeClient.newCall(request).execute().use { response ->
                if (response.code != 200) return ProbeResult(false, 0.0)
                val type = response.header("Content-Type").orEmpty().lowercase()
                if (type.contains("text/html")) return ProbeResult(false, 0.0)
                val encoding = response.header("Content-Encoding").orEmpty().trim()
                if (encoding.isNotEmpty() && !encoding.equals("identity", ignoreCase = true)) {
                    return ProbeResult(false, 0.0)
                }
                val source = response.body?.source() ?: return ProbeResult(false, 0.0)
                val sample = source.readByteArray(PROBE_BYTES) ?: ByteArray(0)
                val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
                if (sample.isEmpty() || elapsedSeconds <= 0.0) return ProbeResult(false, 0.0)
                ProbeResult(true, sample.size / elapsedSeconds)
            }
        } catch (_: Exception) {
            ProbeResult(false, 0.0)
        }
    }

    private data class ProbeResult(val ok: Boolean, val bytesPerSecond: Double)

    companion object {
        /**
         * 内置镜像前缀。顺序仅作同速时的兜底优先级，实际选择以测速结果为准。
         */
        val MIRRORS = listOf(
            // 实测可用、响应较快的节点排前面；插件包只有几 KB，直接顺序试比测速更快。
            "https://ghproxy.cxkpro.top/",
            "https://ghfile.geekertao.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.vip/",
            "https://gh.xxooo.cf/",
            "https://git.yylx.win/",
            "https://cdn.crashmc.com/",
            "https://cors.isteed.cc/",
            "https://ghproxy.1888866.xyz/",
            "https://gh.ddlc.top/",
            "https://dl.ghpig.top/",
            "https://slink.ltd/",
            "https://ghproxy.it/",
            "https://gh.monlor.com/",
            "https://gh.jasonzeng.dev/",
            "https://mirror.houlang.cloud/",
            "https://gh.h233.eu.org/",
            "https://gh.api.99988866.xyz/",
            "https://dockerproxy.link/",
            "https://dockerproxy.net/"
        )

        /** 只对这些主机的地址套镜像。 */
        private val MIRRORED_HOSTS = setOf(
            "github.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
            "codeload.github.com"
        )

        /** 探测只读前 8KB：足够判断状态码、内容类型与速率，几乎不耗流量。 */
        private const val PROBE_BYTES = 8 * 1024L

        /** 测速结果复用时间：一次下载会取多个资产，不必每个都重测。 */
        private const val CACHE_TTL_MS = 5 * 60 * 1000L

        private const val PROBE_TIMEOUT_SECONDS = 8L

        /** 部分节点会校验 UA，用常见桌面浏览器标识避免被当成爬虫。 */
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
