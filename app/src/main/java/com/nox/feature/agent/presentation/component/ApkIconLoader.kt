package com.nox.feature.agent.presentation.component

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import com.nox.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 读取本地 APK 自带的启动图标。
 *
 * 聊天里 sendFile 发来的安装包，用通用文件图标显示等于没有信息量；能读到 APK 自己的
 * launcher icon 时优先用它。解析失败（不是有效 APK、包损坏、读不到文件）返回 null，
 * 调用方退回通用图标。
 *
 * 解析开销不小（要读 zip 里的 AndroidManifest），所以按「路径 + 修改时间 + 大小」缓存，
 * 同一文件重复组合或滚动回列表都不再解析。缓存条目限制 24 个，够覆盖一屏消息。
 */
internal object ApkIconLoader {

    private const val TAG = "ApkIconLoader"
    private const val MAX_ENTRIES = 24
    private const val ICON_EDGE_PX = 96

    private val cache = object : LinkedHashMap<String, ImageBitmap?>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>): Boolean =
            size > MAX_ENTRIES
    }

    fun peek(path: String, stamp: String): ImageBitmap? = synchronized(cache) { cache[key(path, stamp)] }

    suspend fun load(context: Context, path: String, stamp: String): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val key = key(path, stamp)
            synchronized(cache) { cache[key] }?.let { return@withContext it }
            val bitmap = runCatching { readIcon(context, path) }
                .onFailure { FileLogger.w(TAG, "读取 APK 图标失败: $path", it) }
                .getOrNull()
            synchronized(cache) { cache[key] = bitmap }
            bitmap
        }

    /** 文件指纹：路径不足以区分「同一路径被重新构建覆盖」的情况。 */
    fun stampOf(path: String): String {
        val file = File(path)
        return "${file.length()}:${file.lastModified()}"
    }

    private fun key(path: String, stamp: String) = "$path@$stamp"

    private fun readIcon(context: Context, path: String): ImageBitmap? {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(path, 0) ?: return null
        val appInfo = archive.applicationInfo ?: return null
        // 不设置 sourceDir/publicSourceDir 时 getApplicationIcon 会去查「已安装包」，
        // 对磁盘上的 APK 文件必然查不到；改成指向文件本身才会从包里取。
        appInfo.sourceDir = path
        appInfo.publicSourceDir = path
        val drawable = pm.getApplicationIcon(appInfo)
        return drawable.toImageBitmap(ICON_EDGE_PX)
    }

    /** 直接画进固定尺寸位图：不依赖 core-ktx 的 Drawable.toBitmap，自适应图标也走同一路径。 */
    private fun Drawable.toImageBitmap(size: Int): ImageBitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, size, size)
        draw(canvas)
        return bitmap.asImageBitmap()
    }
}

/** 是否按安装包处理（只看扩展名，MIME 对 apk 常常是空的）。 */
internal fun isApkAttachment(fileName: String): Boolean {
    val lower = fileName.lowercase()
    return lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk")
}
