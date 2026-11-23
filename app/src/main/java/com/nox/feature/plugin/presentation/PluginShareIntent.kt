package com.nox.feature.plugin.presentation

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * 构造插件包的分享 Intent，并显式把读权限授予所有能接收的应用。
 *
 * 为什么不能只依赖 `FLAG_GRANT_READ_URI_PERMISSION`：
 * 该标志只对 Intent 的**直接接收者**生效。分享走 `createChooser` 时中间隔了一层
 * 系统 Chooser，部分 ROM 与微信 / QQ 拿不到临时 URI 授权，读文件时报
 * 「获取资源失败」/「文件不存在」。
 *
 * 直接对所有能响应的包逐个 `grantUriPermission` 是唯一在各类 ROM 上都稳的做法；
 * 同时把 URI 写进 `clipData`，因为部分接收方只从 ClipData 取授权。
 */
internal fun buildPluginShareIntent(
    context: Context,
    uri: Uri,
    title: String
): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri(null, uri)
    }
    context.packageManager
        .queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)
        .forEach { info ->
            runCatching {
                context.grantUriPermission(
                    info.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    return Intent.createChooser(send, title)
}
