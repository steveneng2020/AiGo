package com.nox.feature.plugin.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 受控 WebView 登录页：插件传入 URL 与需要读取 cookie 的地址，
 * 用户页内完成登录后回传 { finalUrl, cookies }；取消时返回 null。
 */
class PluginLoginWebViewActivity : Activity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finishWith(null)
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "登录"
        val finishText = intent.getStringExtra(EXTRA_FINISH_TEXT)?.takeIf { it.isNotBlank() } ?: "完成"
        val cookieUrls = intent.getStringArrayListExtra(EXTRA_COOKIE_URLS).orEmpty().ifEmpty { listOf(url) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 12, 12, 12)
            setBackgroundColor(Color.WHITE)
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.BLACK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeButton = Button(this).apply {
            text = "关闭"
            setOnClickListener { finishWith(null) }
        }
        val finishButton = Button(this).apply {
            text = finishText
            setOnClickListener { finishWith(readResult(cookieUrls)) }
        }
        bar.addView(titleView)
        bar.addView(closeButton)
        bar.addView(finishButton)

        val view = WebView(this)
        webView = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            builtInZoomControls = false
            displayZoomControls = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.webViewClient = WebViewClient()
        view.loadUrl(url)

        root.addView(bar)
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onDestroy() {
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun readResult(cookieUrls: List<String>): JsonObject {
        val manager = CookieManager.getInstance()
        val cookies = buildJsonObject {
            cookieUrls.forEach { target ->
                val value = manager.getCookie(target).orEmpty()
                if (value.isNotBlank()) put(target, value)
            }
        }
        manager.flush()
        return buildJsonObject {
            put("finalUrl", webView?.url ?: intent.getStringExtra(EXTRA_URL).orEmpty())
            put("cookies", cookies)
        }
    }

    private fun finishWith(result: JsonObject?) {
        PluginLoginSession.complete(result)
        finish()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FINISH_TEXT = "finishText"
        const val EXTRA_COOKIE_URLS = "cookieUrls"
    }
}

/** 登录 WebView 的等待队列：同一时间只允许一个会话，插件侧以 suspend 方式等结果。 */
object PluginLoginSession {

    private var pending: CompletableDeferred<JsonObject?>? = null

    suspend fun launch(
        context: Context,
        url: String,
        title: String,
        finishText: String,
        cookieUrls: List<String>
    ): JsonObject? = withContext(Dispatchers.Main) {
        pending?.complete(null)
        val deferred = CompletableDeferred<JsonObject?>()
        pending = deferred
        val intent = Intent(context, PluginLoginWebViewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(PluginLoginWebViewActivity.EXTRA_URL, url)
            putExtra(PluginLoginWebViewActivity.EXTRA_TITLE, title)
            putExtra(PluginLoginWebViewActivity.EXTRA_FINISH_TEXT, finishText)
            putStringArrayListExtra(PluginLoginWebViewActivity.EXTRA_COOKIE_URLS, ArrayList(cookieUrls))
        }
        context.startActivity(intent)
        deferred.await()
    }

    fun complete(result: JsonObject?) {
        pending?.complete(result)
        pending = null
    }
}
