package com.nox.feature.plugin.presentation

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.nox.core.util.FileLogger

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
    // 不能用 uri.lastPathSegment：MediaStore 的 URI 末段是 _id（如 465404），
    // 不是文件名。微信拿这个当文件名，得到没有 .zip 后缀的纯数字，认不出类型。
    // 必须回查 ContentResolver 的 DISPLAY_NAME。
    val displayName = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "plugin.zip"
    // 用 octet-stream 而不是 application/zip：微信的 ShareImgUI 是按
    // 「application/*」注册的图片分享界面，zip 进去后它按图片流程处理，
    // 拿不到缩略图就报「获取资源失败」。octet-stream 会路由到通用的
    // 文件转发界面（ForwardRecentActivity），按文件处理。
    val mime = "application/octet-stream"
    // newUri 会查询 MIME，description 里带真实 type；newRawUri 不会（见上面第 1 点）
    val clip = ClipData.newUri(context.contentResolver, displayName, uri)

    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        // 只放 ArrayList 一种形态。
        // 之前同时 putExtra(单值) + putParcelableArrayListExtra，两者共用
        // EXTRA_STREAM 这个 key，后写的会覆盖前者，最终 Intent 里是 ArrayList。
        // 微信按 getParcelableExtra 取值拿到 null，直接报「获取资源失败」。
        // UriGrantsManagerService 的授权检查读的正是 ArrayList，保留它就够。
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        clipData = clip
    }

    val targets = context.packageManager
        .queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)
    targets.forEach { info ->
        runCatching {
            context.grantUriPermission(
                info.activityInfo.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }
    // 排查用：真机上看不到授权细节时，这几行是唯一线索。
    // 用 Log.w 而不是 Log.i —— 部分 ROM 默认过滤 info 级日志。
    // 双写：logcat 会被缓冲区冲掉（真机上抓不到），FileLogger 落盘可持久查看。
    val detail = "scheme=${uri.scheme} authority=${uri.authority} path=${uri.path} " +
        "name=$displayName mime=$mime targets=${targets.map { it.activityInfo.packageName }}"
    Log.w("PluginShare", detail)
    FileLogger.w("PluginShare", detail)
    // 自己读一次：读失败说明写入环节就有问题，与接收方无关。
    val probe = runCatching { context.contentResolver.openInputStream(uri)?.use { it.read() } }
    val probeMsg = "probe read=${probe.getOrNull()} err=${probe.exceptionOrNull()}"
    Log.w("PluginShare", probeMsg)
    FileLogger.w("PluginShare", probeMsg)

    // MediaStore 条目状态：SIZE=0 或 IS_PENDING=1 时接收方读不到内容，
    // 那正是「获取资源失败」的典型成因，必须确认。
    val meta = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(
                android.provider.OpenableColumns.SIZE,
                android.provider.MediaStore.MediaColumns.IS_PENDING,
                android.provider.OpenableColumns.DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                buildString {
                    append("size=").append(c.getLong(0))
                    append(" pending=").append(c.getInt(1))
                    append(" name=").append(c.getString(2))
                }
            } else "no row"
        }
    }.getOrNull() ?: "query failed"
    Log.w("PluginShare", "meta $meta")
    FileLogger.w("PluginShare", "meta $meta")
    return Intent.createChooser(send, title)
}
