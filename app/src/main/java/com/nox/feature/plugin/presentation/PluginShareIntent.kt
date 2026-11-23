package com.nox.feature.plugin.presentation

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * 构造插件包的分享 Intent，并显式把读权限授予所有能接收的应用。
 *
 * 分享 zip 到微信/QQ 有三个必须同时满足的点（缺一个就报「获取资源失败」/「文件不存在」）：
 *
 * 1. **ClipData 必须是 newUri 而不是 newRawUri**
 *    `newRawUri` 不查询 MIME，产出的 description 里 type 为空；
 *    部分接收方（微信）据此判定「没有可读内容」。
 *    `newUri` 会经 ContentResolver 查出真实 MIME，description 完整。
 *
 * 2. **EXTRA_STREAM 同时给单个和列表两种形态**
 *    系统在 `UriGrantsManagerService` 里检查授权时，读的是
 *    `getParcelableArrayListExtra(EXTRA_STREAM)`；我们只放单个 Uri 时它拿到 null，
 *    授权检查直接跳过，接收方最终没有读权限（真机日志：
 *    `Key android.intent.extra.STREAM expected ArrayList<Uri> but value was of a different type`）。
 *    两个 key 都放，两种读取路径都能命中。
 *
 * 3. **逐个 grantUriPermission**
 *    `FLAG_GRANT_READ_URI_PERMISSION` 只对 Intent 的直接接收者生效，
 *    走 createChooser 时中间隔了一层系统 Chooser，接收方拿不到临时授权。
 */
internal fun buildPluginShareIntent(
    context: Context,
    uri: Uri,
    title: String
): Intent {
    val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "plugin.zip"
    val mime = context.contentResolver.getType(uri) ?: "application/zip"
    // newUri 会查询 MIME，description 里带真实 type；newRawUri 不会（见上面第 1 点）
    val clip = ClipData.newUri(context.contentResolver, displayName, uri)

    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        // 单个形态：多数接收方走这个
        putExtra(Intent.EXTRA_STREAM, uri)
        // 列表形态：系统授权检查读的是这个（见上面第 2 点）
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = clip
    }

    // 诊断：确认 provider 能否真正解析这个 URI。解析失败会抛 IllegalArgumentException，
    // 那才是「接收方读不到文件」的真正起点。
    val probe = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.read() }
    }
    Log.i("PluginShare", "probe read=${probe.getOrNull()} err=${probe.exceptionOrNull()?.message}")

    val targets = context.packageManager
        .queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)
    targets.forEach { info ->
        runCatching {
            context.grantUriPermission(
                info.activityInfo.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }
    // 排查用：真机上看不到授权细节时，这几行是唯一线索。
    Log.i(
        "PluginShare",
        "uri=$uri name=$displayName mime=$mime targets=${targets.map { it.activityInfo.packageName }}"
    )
    return Intent.createChooser(send, title)
}
