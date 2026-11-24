package com.nox.feature.agent.presentation.component

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 保存结果：相册写入成功、缺少存储权限（仅 API 28 及以下）、或写入失败。 */
sealed interface SaveImageResult {
    data class Saved(val uri: Uri) : SaveImageResult
    data object PermissionDenied : SaveImageResult
    data object Failed : SaveImageResult
}

/**
 * 把图片文件保存进系统相册的 `Pictures/Nox`。
 *
 * API 29 起走 MediaStore，不需要存储权限；更早版本需要 WRITE_EXTERNAL_STORAGE，
 * 未授权时由调用方引导用户去设置里开启。
 */
suspend fun saveImageToGallery(
    context: Context,
    source: File,
    displayName: String
): SaveImageResult = withContext(Dispatchers.IO) {
    if (!source.isFile) return@withContext SaveImageResult.Failed
    val mime = guessImageMime(source.name)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Nox")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return@withContext SaveImageResult.Failed
        val written = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } ?: error("no output stream")
        }.isSuccess
        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext SaveImageResult.Failed
        }
        runCatching {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        SaveImageResult.Saved(uri)
    } else {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return@withContext SaveImageResult.PermissionDenied
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Nox")
        if (!dir.exists() && !dir.mkdirs()) return@withContext SaveImageResult.Failed
        val target = File(dir, displayName)
        val copied = runCatching { source.copyTo(target, overwrite = true) }.isSuccess
        if (!copied) return@withContext SaveImageResult.Failed
        runCatching {
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(target.absolutePath), arrayOf(mime), null
            )
        }
        SaveImageResult.Saved(Uri.fromFile(target))
    }
}

private fun guessImageMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "heic", "heif" -> "image/heic"
    else -> "image/jpeg"
}
