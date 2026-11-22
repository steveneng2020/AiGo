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
 * 缓存目录固定为 `cacheDir/plugin-export/`：每次导出覆盖同名文件，避免多次导出堆积。
 */
@Singleton
class PluginPackageExporter @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private fun exportDir(): File = File(context.cacheDir, "plugin-export").apply { mkdirs() }

    /**
     * 把 [installDir] 打成 zip 并返回文件。失败时抛异常，由调用方提示。
     */
    fun export(pluginId: String, version: String, installDir: File): File {
        require(installDir.isDirectory) { "插件目录不存在：${installDir.path}" }
        val safeId = pluginId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(exportDir(), "$safeId-$safeVersion.zip")
        PluginDevPackageBuilder.zip(installDir, target)
        return target
    }

    /** 拿到分享用 Uri（复用 AndroidManifest 里已有的 FileProvider，cache-path 已覆盖 cacheDir）。 */
    fun shareUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
