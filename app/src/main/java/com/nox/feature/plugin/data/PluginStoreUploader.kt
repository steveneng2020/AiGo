package com.nox.feature.plugin.data

import android.content.Context
import com.nox.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** 上传结果。 */
data class PluginUploadResult(
    val ok: Boolean,
    val message: String,
    val detailUrl: String? = null
)

/**
 * 把插件包提交到上传暂存区。
 *
 * 暂存区是独立仓库，客户端内置的凭据只能写这一处；能否上架由商店仓库的定时任务
 * 审计后决定，所以即使 APK 被反编译拿到凭据，也只能往暂存区塞包，进不了商店。
 */
@Singleton
class PluginStoreUploader @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        /** 暂存区仓库；App 内置凭据只能提交到这里。 */
        private const val INBOX_REPO = "yyds0399/Muse-Plugins-Inbox"

        /**
         * 暂存区写入凭据，构建时由 local.properties 的 PLUGIN_INBOX_TOKEN 注入，
         * 不写进源码：明文凭据随仓库分发会被 GitHub 的 push protection 直接拦下。
         */
        private val INBOX_TOKEN: String = BuildConfig.PLUGIN_INBOX_TOKEN

        private const val API = "https://api.github.com"

        /**
         * GitHub Contents API 要把整个文件读进内存再编码，手机上远达不到它的 100MB 上限，
         * 这里按 20MB 约束。
         */
        private const val MAX_PACKAGE_BYTES = 20L * 1024 * 1024
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean = true

    suspend fun upload(packageFile: File, meta: PluginPublishMeta): PluginUploadResult =
        withContext(Dispatchers.IO) {
            if (INBOX_TOKEN.isBlank()) {
                return@withContext PluginUploadResult(
                    false,
                    "这个构建没有配置上传服务，暂时无法提交插件"
                )
            }
            if (!packageFile.isFile || packageFile.length() == 0L) {
                return@withContext PluginUploadResult(false, "插件包不可读")
            }
            if (packageFile.length() > MAX_PACKAGE_BYTES) {
                return@withContext PluginUploadResult(
                    false,
                    "插件包超过 ${MAX_PACKAGE_BYTES / 1024 / 1024}MB"
                )
            }

            try {
                val path = "incoming/${meta.id}_${meta.version}.zip"
                val existingSha = existingFileSha(path)
                val encoded = android.util.Base64.encodeToString(
                    packageFile.readBytes(),
                    android.util.Base64.NO_WRAP
                )
                val body = JSONObject().apply {
                    put("message", "plugin: ${meta.id} ${meta.version}")
                    put("content", encoded)
                    if (existingSha != null) put("sha", existingSha)
                }
                val request = Request.Builder()
                    .url("$API/repos/$INBOX_REPO/contents/$path")
                    .header("Authorization", "Bearer $INBOX_TOKEN")
                    .header("Accept", "application/vnd.github+json")
                    .put(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val reason = runCatching {
                            JSONObject(text).optString("message")
                        }.getOrNull().orEmpty().ifBlank { text.take(160) }
                        return@withContext PluginUploadResult(
                            false,
                            "提交失败（HTTP ${response.code}）：$reason"
                        )
                    }
                    PluginUploadResult(
                        true,
                        "已提交：${meta.name} v${meta.version}\n" +
                            "商店会在 15 分钟内完成审计，通过后自动上架。"
                    )
                }
            } catch (e: Exception) {
                PluginUploadResult(false, e.message ?: "上传失败")
            }
        }

    /** 同名文件已存在时要带 sha 才能覆盖，否则 GitHub 返回 422。 */
    private fun existingFileSha(path: String): String? {
        val request = Request.Builder()
            .url("$API/repos/$INBOX_REPO/contents/$path")
            .header("Authorization", "Bearer $INBOX_TOKEN")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string().orEmpty()
            runCatching { JSONObject(text).optString("sha") }.getOrNull()?.ifBlank { null }
        }
    }
}

/** 发布所需的元数据（来自插件包内 plugin.json）。 */
data class PluginPublishMeta(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val license: String = "MIT"
)
