package com.nox.feature.agent.presentation.component

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import com.nox.core.ui.decodeSampledBitmap
import com.nox.core.util.FileLogger
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Markdown 里远端图片（http/https）的下载与解码。
 *
 * 只用于正文内联展示，所以两处收敛体积与流量：
 * - 按 URL 的 SHA-256 落盘到 cacheDir，同一张图只下一次；进程内再加一层内存缓存，
 *   LazyColumn 回收后重新组合不会重新解码。
 * - 下载体积上限 [MAX_BYTES]，配合解码侧采样，避免一张巨图把内存顶爆。
 *
 * 刻意不复用全局 OkHttpClient：那条链路挂着 provider 代理与 http hook，
 * 图片请求不该走业务代理，也不该触发 `agent.http.before` 拦截。
 */
internal object RemoteImageCache {

    private const val TAG = "RemoteImageCache"
    private const val MAX_BYTES = 20L * 1024 * 1024
    private const val MAX_MEMORY_ENTRIES = 32

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** 进程内位图缓存：键含采样参数，不同尺寸各存一份。 */
    private val memory = object : LinkedHashMap<String, ImageBitmap>(MAX_MEMORY_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean =
            size > MAX_MEMORY_ENTRIES
    }

    /** 远端图片是否可交给本类处理。 */
    fun isRemote(url: String): Boolean {
        val u = url.trim()
        return u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)
    }

    suspend fun decode(
        context: Context,
        url: String,
        maxEdge: Int,
        maxPixels: Long
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$url|$maxEdge|$maxPixels"
        synchronized(memory) { memory[key] }?.let { return@withContext it }
        val file = download(context, url) ?: return@withContext null
        val bitmap = decodeSampledBitmap(file.absolutePath, maxEdge, maxPixels)
            ?: return@withContext null
        synchronized(memory) { memory[key] = bitmap }
        bitmap
    }

    private fun download(context: Context, url: String): File? {
        val dir = File(context.cacheDir, "remote-images")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = File(dir, sha256(url) + ".img")
        if (target.isFile && target.length() in 1..MAX_BYTES) return target
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Nox/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    FileLogger.w(TAG, "远端图片下载失败 ${response.code}: $url")
                    return null
                }
                val body = response.body ?: return null
                if (body.contentLength() > MAX_BYTES) {
                    FileLogger.w(TAG, "远端图片超过上限: $url")
                    return null
                }
                val temp = File(dir, target.name + ".part")
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_BYTES) {
                                temp.delete()
                                FileLogger.w(TAG, "远端图片超过上限: $url")
                                return null
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return null
                }
                target
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "远端图片下载异常: $url", e)
            null
        }
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
