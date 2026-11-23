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

    /**
     * 用户选定的导出目录（持久化权限的 tree uri）。未选过返回 null。
     *
     * **必须原样返回 [Uri.toString] 存下的编码形式**，不能 Uri.decode：
     * takePersistableUriPermission 授权的就是编码形式，系统按字符串比对授权记录；
     * 一旦解码（`%3A` -> `:`、`%2F` -> `/`），Uri 与授权记录对不上，
     * DocumentsContract 会报
     * `Permission Denial: ... requires MANAGE_DOCUMENTS, or grantUriPermission()`。
     *
     * 之前那个 `Invalid URI` 的真正原因不是编码，而是 createDocument 要的是
     * **目录的 document uri**，不能直接传 tree uri。那个问题已在
     * [writeToChosenDir] 里用 buildDocumentUriUsingTree 修正。
     */
    fun chosenDirUri(): Uri? =
        prefs().getString(KEY_DIR_URI, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /**
     * 该 tree uri 是否仍持有持久化读写授权。
     *
     * 用户在系统设置里撤销授权、卸载重装、换 SD 卡、或历史版本曾用错误形式
     * 授权过，都会让记录失效；此时直接写入会抛
     * `Permission Denial: ... requires MANAGE_DOCUMENTS, or grantUriPermission()`。
     * 调用方应先检查，失效就走「清掉记忆 + 重新弹目录选择器」。
     */
    fun hasPersistedPermission(uri: Uri): Boolean = runCatching {
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    }.getOrDefault(false)

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
        // createDocument 要求的是「目录的 document uri」，不能直接传 OpenDocumentTree
        // 给的 tree uri：后者形如 content://.../tree/primary%3ADownload，
        // getDocumentId 会直接抛「Invalid URI」。
        val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(dirUri)
        val parentDocUri = android.provider.DocumentsContract
            .buildDocumentUriUsingTree(dirUri, treeDocId)
        val newDocUri = android.provider.DocumentsContract.createDocument(
            resolver, parentDocUri, "application/zip", src.name
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
     * 分享用 Uri。
     *
     * **不用 FileProvider**：真机上 FileProvider 的 content:// 交给微信/QQ 时
     * 始终报「获取资源失败」/「文件不存在」，已确认不是授权标志、ClipData、
     * EXTRA_STREAM 形态或 MIME 的问题（这四项都修过、都无效）。
     * 目标应用的读取路径在 MIUI 上对 content:// 有限制，而本应用 targetSdk=28
     * 仍允许对外暴露 file://，所以直接落一份到公共目录再分享 file://。
     *
     * 为什么放 Download 下的子目录而不是 cache：
     * - 接收方（微信/QQ）会在自己的进程里读这个路径，必须公共可读；
     * - 用户也能在文件管理器里找到导出的包，算附带的好处。
     */
    fun shareUri(file: File): Uri {
        // 走 MediaStore 写入系统「下载」集合，而不是 FileProvider。
        //
        // 为什么放弃 FileProvider：真机上 files/share/ 里的文件已经是 0644、
        // ClipData 与 EXTRA_STREAM 也都按规范填了，微信/QQ 仍然报
        // 「获取资源失败」/「文件不存在」。唯一还没试过的差异是
        // **content:// 的 authority 归属**——FileProvider 的 authority 属于本应用，
        // 而 MediaStore 的 content://media/... 由系统 media provider 持有，
        // 接收方读它走的是系统 provider，不涉及跨应用 URI 授权。
        //
        // MediaStore 的 Downloads 集合是系统官方的「共享文件」入口，
        // 微信、QQ、文件管理器都直接支持；代价是文件会留在系统下载目录里
        // （对「导出插件包」这个场景反而是好事，用户能在下载里找到）。
        val resolver = context.contentResolver
        val isQ = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            if (isQ) {
                // RELATIVE_PATH / IS_PENDING 是 API 29+ 才有的列
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/AiGo")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("无法在系统下载目录创建文件")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("无法写入系统下载目录")
            if (isQ) {
                values.clear()
                values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }
}
