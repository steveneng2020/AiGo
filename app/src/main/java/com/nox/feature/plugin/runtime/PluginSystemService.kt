package com.nox.feature.plugin.runtime

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import com.nox.core.util.FileLogger
import com.nox.feature.plugin.domain.PluginHostException
import com.nox.feature.plugin.domain.PluginManager
import com.nox.feature.plugin.files.PluginFilePickerSession
import com.nox.feature.plugin.login.PluginLoginSession
import com.nox.feature.plugin.overlay.AppForegroundMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * 插件可用的宿主系统能力：剪贴板、系统分享、打开链接、宿主 App 信息与日志。
 *
 * 每个入口都以插件启用为前提（停用/损坏的插件不能借用宿主身份操作系统），参数校验在切主线程之前完成，
 * 失败统一抛 [PluginHostException]，code 稳定：`PLUGIN_INACTIVE`、`PARAM_INVALID`、`TOO_LARGE`、
 * `NO_ACTIVITY`、`SERVICE_UNAVAILABLE`、`APP_INFO_UNAVAILABLE`。
 *
 * 剪贴板与 Intent 都在主线程执行：剪贴板服务与 `startActivity` 都面向 UI，放主线程避免跨线程的时序问题。
 */
@Singleton
class PluginSystemService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pluginManager: PluginManager,
    private val foreground: AppForegroundMonitor
) {

    /** 读取剪贴板第一条文本；剪贴板为空（或内容不是文本）时返回空串。 */
    suspend fun clipboardRead(pluginId: String): JsonElement {
        ensureActive(pluginId)
        return withContext(Dispatchers.Main) {
            val clip = clipboard().primaryClip
            val text = if (clip == null || clip.itemCount == 0) {
                ""
            } else {
                clip.getItemAt(0).text?.toString().orEmpty()
            }
            if (text.toByteArray(Charsets.UTF_8).size > MAX_CLIPBOARD_BYTES) {
                throw PluginHostException("TOO_LARGE", "clipboard text too large")
            }
            buildJsonObject { put("text", text) }
        }
    }

    /** 写入剪贴板。即使在后台也允许写。 */
    suspend fun clipboardWrite(pluginId: String, text: String): JsonElement {
        ensureActive(pluginId)
        if (text.toByteArray(Charsets.UTF_8).size > MAX_CLIPBOARD_BYTES) {
            throw PluginHostException("TOO_LARGE", "clipboard text too large")
        }
        return withContext(Dispatchers.Main) {
            clipboard().setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            buildJsonObject { put("ok", true) }
        }
    }

    /** 拉起系统分享面板；[title] 非空时同时用作选择器标题与分享主题。 */
    suspend fun shareText(pluginId: String, text: String, title: String? = null): JsonElement {
        ensureActive(pluginId)
        if (text.length > MAX_SHARE_CHARS) {
            throw PluginHostException("TOO_LARGE", "share text too long")
        }
        val subject = title?.trim()?.takeIf { it.isNotEmpty() }
        withContext(Dispatchers.Main) {
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
                },
                subject
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(chooser)
            } catch (e: ActivityNotFoundException) {
                throw PluginHostException("NO_ACTIVITY", "no activity to handle share")
            }
        }
        return ok()
    }

    /** 用系统浏览器或其他已注册的 App 打开 http/https 链接。 */
    suspend fun openUrl(pluginId: String, url: String): JsonElement {
        ensureActive(pluginId)
        val target = requireHttpUrl(url)
        withContext(Dispatchers.Main) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(target))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: ActivityNotFoundException) {
                throw PluginHostException("NO_ACTIVITY", "no activity to open url")
            }
        }
        return ok()
    }

    /** 打开受控 WebView 完成第三方网页登录，回传 { finalUrl, cookies }；用户取消时抛 LOGIN_CANCELED。 */
    suspend fun openLoginWebView(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val url = requireHttpUrl(requireString(params, "url"))
        val title = (params["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val finishText = (params["finishText"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val cookieUrls = (params["cookieUrls"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.map { requireHttpUrl(it) }
            ?.ifEmpty { listOf(url) }
            ?: listOf(url)
        val accentColor = (params["accentColor"] as? JsonPrimitive)?.contentOrNull
        val cornerRadius = (params["cornerRadius"] as? JsonPrimitive)?.intOrNull ?: 0
        val dimAlpha = (params["dimAlpha"] as? JsonPrimitive)?.floatOrNull ?: 0.4f
        val showClose = (params["showClose"] as? JsonPrimitive)?.booleanOrNull ?: false
        val capture = (params["capture"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.also {
                if (it.length > MAX_CAPTURE_SCRIPT_CHARS) {
                    throw PluginHostException("PARAM_INVALID", "capture script too long")
                }
            }
        val autoCapture = (params["autoCapture"] as? JsonPrimitive)?.booleanOrNull ?: false
        val autoFinish = (params["autoFinish"] as? JsonPrimitive)?.booleanOrNull ?: true
        val successWhen = (params["successWhen"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.also {
                if (it.length > MAX_CAPTURE_SCRIPT_CHARS) {
                    throw PluginHostException("PARAM_INVALID", "successWhen script too long")
                }
            }
        val showFinishButton = (params["showFinishButton"] as? JsonPrimitive)?.booleanOrNull ?: false
        val showCancel = (params["showCancel"] as? JsonPrimitive)?.booleanOrNull ?: true
        val cancelText = (params["cancelText"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return PluginLoginSession.launch(
            context = context,
            url = url,
            title = title,
            finishText = finishText,
            cookieUrls = cookieUrls,
            accentColor = accentColor,
            cornerRadiusDp = cornerRadius,
            dimAlpha = dimAlpha,
            showClose = showClose,
            capture = capture,
            autoCapture = autoCapture,
            autoFinish = autoFinish,
            successWhen = successWhen,
            showFinishButton = showFinishButton,
            showCancel = showCancel,
            cancelText = cancelText
        ) ?: throw PluginHostException("LOGIN_CANCELED", "user canceled login")
    }

    /** 拉起系统文件选择器，返回 { files: [{ name, size, mime, base64 }] }；取消抛 PICK_CANCELED。 */
    suspend fun pickFiles(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val mimeTypes = (params["mimeTypes"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val multiple = (params["multiple"] as? JsonPrimitive)?.booleanOrNull ?: false
        val uris = PluginFilePickerSession.launch(context, mimeTypes, multiple)
            ?: throw PluginHostException("PICK_CANCELED", "user canceled file pick")
        val files = buildJsonArray {
            var total = 0L
            uris.take(if (multiple) MAX_PICK_FILES else 1).forEach { uri ->
                val meta = queryFileMeta(uri)
                if (meta.size > MAX_PICK_FILE_BYTES || total + meta.size > MAX_PICK_TOTAL_BYTES) return@forEach
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull() ?: return@forEach
                total += bytes.size
                add(buildJsonObject {
                    put("name", meta.name)
                    put("size", bytes.size.toLong())
                    put("mime", meta.mime)
                    put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                })
            }
        }
        return buildJsonObject { put("files", files) }
    }

    private data class FileMeta(val name: String, val size: Long, val mime: String)

    private fun queryFileMeta(uri: Uri): FileMeta {
        var name = uri.lastPathSegment ?: "file"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0) cursor.getString(nameIndex)?.let { name = it }
                        if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                    }
                }
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        return FileMeta(name, size, mime)
    }

    /** 宿主 App 的包名、版本、系统 API 级别、界面语言与前后台状态。 */
    suspend fun appInfo(pluginId: String): JsonElement {
        ensureActive(pluginId)
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            throw PluginHostException("APP_INFO_UNAVAILABLE", "cannot read host package info")
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return buildJsonObject {
            put("packageName", context.packageName)
            put("versionName", info.versionName.orEmpty())
            put("versionCode", versionCode)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("languageTag", Locale.getDefault().toLanguageTag())
            put("isForeground", foreground.current())
        }
    }

    /** 写一行宿主日志；[tag] 缺省为 `plugin:<pluginId>`，便于按插件过滤。 */
    suspend fun log(pluginId: String, level: String, message: String, tag: String? = null): JsonElement {
        ensureActive(pluginId)
        if (message.length > MAX_LOG_MESSAGE_CHARS) {
            throw PluginHostException("TOO_LARGE", "log message too long")
        }
        val logTag = when {
            tag.isNullOrEmpty() -> DEFAULT_TAG_PREFIX + pluginId
            tag.length > MAX_LOG_TAG_CHARS -> throw PluginHostException("PARAM_INVALID", "log tag too long")
            else -> tag
        }
        when (level) {
            "debug" -> FileLogger.d(logTag, message)
            "info" -> FileLogger.i(logTag, message)
            "warn" -> FileLogger.w(logTag, message)
            "error" -> FileLogger.e(logTag, message)
            else -> throw PluginHostException("PARAM_INVALID", "unknown log level: $level")
        }
        return ok()
    }

    private fun ensureActive(pluginId: String) {
        pluginManager.ensureLoaded()
        pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }

    private fun clipboard(): ClipboardManager =
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?: throw PluginHostException("SERVICE_UNAVAILABLE", "clipboard service unavailable")

    /** 只放行 http/https；URI 解析失败、缺 scheme 或没有 authority 都算参数非法。 */
    private fun requireString(params: JsonObject, key: String): String =
        (params[key] as? JsonPrimitive)?.contentOrNull
            ?: throw PluginHostException("PARAM_INVALID", "missing $key")

    private fun requireHttpUrl(url: String): String {
        if (url.length > MAX_URL_CHARS) throw PluginHostException("TOO_LARGE", "url too long")
        val parsed = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw PluginHostException("PARAM_INVALID", "invalid url")
        }
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            throw PluginHostException("PARAM_INVALID", "only http/https urls are allowed")
        }
        if (parsed.rawAuthority.isNullOrEmpty()) {
            throw PluginHostException("PARAM_INVALID", "url has no host")
        }
        return url
    }

    private fun ok(): JsonElement = buildJsonObject { put("ok", true) }

    private companion object {
        const val CLIP_LABEL = "text"
        const val MAX_CLIPBOARD_BYTES = 1024 * 1024
        const val MAX_SHARE_CHARS = 100_000
        const val MAX_URL_CHARS = 4096

        /** 插件传入的抓取脚本长度上限：脚本会在登录页上下文执行，不宜过长。 */
        const val MAX_CAPTURE_SCRIPT_CHARS = 8 * 1024
        const val MAX_LOG_MESSAGE_CHARS = 2000
        const val MAX_LOG_TAG_CHARS = 64
        const val DEFAULT_TAG_PREFIX = "plugin:"
        const val MAX_PICK_FILES = 8
        const val MAX_PICK_FILE_BYTES = 2L * 1024 * 1024
        const val MAX_PICK_TOTAL_BYTES = 4L * 1024 * 1024
    }
}
