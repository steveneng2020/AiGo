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
    /**
     * 导出的插件包写到公共目录，用户能在文件管理器里直接找到。
     *
     * 之前写到 `cacheDir/plugin-export/`（应用私有缓存），用户看不到、也没有保存路径提示，
     * 表现为「不知道导出成功没」。这里改到 Download/AiGo/AiGo开发/导出插件。
     */
    private fun exportDir(): File {
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            "AiGo/AiGo开发/导出插件"
        )
        dir.mkdirs()
        return dir
    }

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
