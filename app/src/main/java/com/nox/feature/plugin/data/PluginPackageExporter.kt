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

    /** 分享用 Uri，走本应用自己的 FileProvider。 */
    fun shareUri(file: File): Uri {
        // 用本应用自己的 FileProvider，而不是 MediaStore。
        //
        // MediaStore 那条路走不通：content://media/... 的 authority 属于
        // media provider，不是本应用。第三方应用无法把「别人的 URI」
        // 授权给接收方，grantUriPermission 对它是无效的；而 Android 13+
        // 起普通应用也读不到别的应用写进 Downloads 的文件。
        // 真机表现就是微信「获取资源失败」——它拿不到读权限。
        //
        // FileProvider 的 authority 属于本应用，FLAG_GRANT_READ_URI_PERMISSION
        // 与 grantUriPermission 都能正常生效。
        // 之前 FileProvider 也失败，是被 EXTRA_STREAM 覆盖 bug 掩盖了
        // （单值与 ArrayList 共用同一个 key，后写的覆盖前者），已一并修掉。
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val target = File(dir, file.name)
        if (target.exists() && target.length() == file.length()) {
            target.setLastModified(System.currentTimeMillis())
        } else {
            runCatching { target.delete() }
            file.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        // 兜底：某些 ROM 上 cacheDir 的文件权限过严，显式放开可读。
        runCatching { target.setReadable(true, false) }
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            target
        )
    }
}
