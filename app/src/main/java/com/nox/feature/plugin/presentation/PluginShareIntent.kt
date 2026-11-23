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
 * 2. **EXTRA_STREAM 放单值 Uri**
 *    ACTION_SEND 的规范是单个 Uri，ArrayList 属于 ACTION_SEND_MULTIPLE。
 *    两者共用 EXTRA_STREAM，同时写只会保留最后一个，必须选对形态。
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
    // 用 Provider 查出的真实 MIME，不要硬编码。
    // 之前改成 octet-stream 无效：微信对 application/* 只注册 ShareImgUI
    // 和 AddFavoriteUI 两个入口，换 MIME 不改变解析到的组件，症状必然不变。
    // 而且硬编码会让 Intent.type、ClipDescription、Provider.getType() 三处
    // 不一致，接收方以哪个为准是未定义的。
    val mime = context.contentResolver.getType(uri) ?: "application/zip"
    // 平台推荐写法：newUri 会一并取出真实 MIME 填进 description，三处自然一致。
    val clip = ClipData.newUri(context.contentResolver, displayName, uri)

    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        // ACTION_SEND 的 EXTRA_STREAM 规范是单个 Uri；ArrayList 属于
        // ACTION_SEND_MULTIPLE。两者共用 EXTRA_STREAM 这个 key，
        // 同时写只会留下最后写的那个，所以必须选对形态。
        //
        // 真机日志：微信由 ShareImgUI（继承 AutoLoginActivity，老式单文件
        // 分享入口）接收，按 getParcelableExtra 取值；拿到 ArrayList
        // 既无法转成 Parcelable 也不是 Uri，于是初始化即失败，
        // 窗口 104ms 后销毁 —— 这正是「获取资源失败」的表现。
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = clip
    }

    val targets = context.packageManager
        .queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)
    val grantFailures = mutableListOf<String>()
    targets.forEach { info ->
        val pkg = info.activityInfo.packageName
        runCatching {
            context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { grantFailures += "$pkg: ${it.message}" }
    }
    if (grantFailures.isNotEmpty()) {
        FileLogger.w("PluginShare", "grantUriPermission 失败 ${grantFailures.size} 个: $grantFailures")
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
    // 外层 chooser 必须自己也带 flags 与 clipData。
    // FLAG_GRANT_READ_URI_PERMISSION 只作用于「直接接收者」，而直接把
    // send 交给 startActivity 时，系统会插入 ResolverActivity，
    // 真正启动微信的是那次二次启动 —— 标志不会自动沿传递。
    return Intent.createChooser(send, title).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = clip
    }
}
