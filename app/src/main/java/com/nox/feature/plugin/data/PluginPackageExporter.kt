package com.nox.feature.plugin.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把已安装插件重新打成安装包，供导出/分享。
 *
 * 只打包 `installed/<pluginId>/`（清单与代码），**不含** `data/<pluginId>/` 里的用户数据
 * （两者在 PluginManager 里是分开的目录）。因此导出物可以在另一台设备上直接安装，
 * 不会带上原设备的会话、账号或配置。
 *
 * 导出位置：首次导出时让用户选一次（SAF 目录选择器），选定后记住；
 * 之后直接存到该目录，不再反复弹窗。用户可在插件设置里重选。
 * 未选过时退回默认的 Download/AiGo/AiGo开发/导出插件。
 */
@Singleton
class PluginPackageExporter @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val PREFS = "plugin_export_prefs"
        const val KEY_DIR_URI = "export_dir_uri"
        const val DEFAULT_SUBDIR = "AiGo/AiGo开发/导出插件"
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户选定的导出目录（持久化权限的 tree uri）。未选过返回 null。 */
    fun chosenDirUri(): Uri? =
        prefs().getString(KEY_DIR_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun rememberDir(uri: Uri) {
        prefs().edit().putString(KEY_DIR_URI, uri.toString()).apply()
    }

    fun clearChosenDir() {
        prefs().edit().remove(KEY_DIR_URI).apply()
    }

    /** 默认目录（用户未选时使用）。 */
    fun defaultDir(): File {
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            DEFAULT_SUBDIR
        )
        dir.mkdirs()
        return dir
    }

    /** 将文件写入用户选定的 SAF 目录；返回写入后的 URI 字符串（用于提示）。 */
    fun writeToChosenDir(src: File, dirUri: Uri): String {
        val resolver = context.contentResolver
        val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(dirUri)
        val newDocUri = android.provider.DocumentsContract.createDocument(
            resolver, dirUri, "application/zip", src.name
        ) ?: throw IllegalStateException("无法在所选目录创建文件")
        resolver.openOutputStream(newDocUri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("无法写入所选目录")
        src.delete()
        // 拼一个人类可读路径给提示用：treeDocId 通常是 primary:Download/xxx
        val readable = treeDocId.substringAfter(':', treeDocId)
        return "$readable/${src.name}"
    }

    /**
     * 把 [installDir] 打成 zip 并返回文件。失败时抛异常，由调用方提示。
     * 总是先打到缓存目录，再由调用方决定落盘位置。
     */
    fun export(pluginId: String, version: String, installDir: File): File {
        require(installDir.isDirectory) { "插件目录不存在：${installDir.path}" }
        val safeId = pluginId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val staging = File(File(context.cacheDir, "plugin-export").apply { mkdirs() }, "$safeId-$safeVersion.zip")
        PluginDevPackageBuilder.zip(installDir, staging)
        return staging
    }

    /** 把导出的包落到默认目录，返回落地文件。 */
    fun publishToDefault(staging: File): File {
        val target = File(defaultDir(), staging.name)
        staging.copyTo(target, overwrite = true)
        staging.delete()
        return target
    }

    /**
     * 分享用 Uri：分享仍走私有 cache，因为 FileProvider 的 cache-path 已覆盖那里。
     * 公共目录的文件要先拷回 cache 才能分享，否则 FileProvider 无权暴露。
     */
    fun shareUri(file: File): Uri {
        val shareable = if (file.parentFile == context.cacheDir || file.path.startsWith(context.cacheDir.path)) {
            file
        } else {
            val cached = File(File(context.cacheDir, "plugin-export").apply { mkdirs() }, file.name)
            file.copyTo(cached, overwrite = true)
            cached
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareable)
    }
}
