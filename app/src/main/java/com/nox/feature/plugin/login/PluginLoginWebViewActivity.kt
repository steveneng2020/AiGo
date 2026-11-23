package com.nox.feature.plugin.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
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
        val accentOverride = intent.getStringExtra(EXTRA_ACCENT_COLOR)
            ?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        val radiusDp = intent.getIntExtra(EXTRA_CORNER_RADIUS, 0)
        val dimAlpha = intent.getFloatExtra(EXTRA_DIM_ALPHA, 0.4f).coerceIn(0f, 0.9f)
        val showClose = intent.getBooleanExtra(EXTRA_SHOW_CLOSE, true)
        val corner = if (radiusDp > 0) dp(radiusDp).toFloat() else dp(24).toFloat()

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable((dimAlpha * 255).toInt() shl 24))

        val background = themeColor(android.R.attr.colorBackground, Color.WHITE)
        val surface = blend(background, if (isLight(background)) Color.WHITE else Color.BLACK, 0.55f)
        val onSurface = themeColor(android.R.attr.textColorPrimary, Color.DKGRAY)
        val accent = accentOverride ?: themeColor(android.R.attr.colorAccent, Color.parseColor("#3B82F6"))

        val root = FrameLayout(this).apply {
            setPadding(dp(14), dp(28), dp(14), dp(20))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackground(rounded(surface, corner))
            elevation = dp(18).toFloat()
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(12), dp(10))
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(onSurface)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            letterSpacing = 0.01f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeView = TextView(this).apply {
            text = "关闭"
            textSize = 14f
            setTextColor(onSurface)
            alpha = 0.6f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { finishWith(null) }
        }
        bar.addView(titleView)
        if (showClose) bar.addView(closeView)

        val webCard = FrameLayout(this).apply {
            setBackground(rounded(background, dp(18).toFloat()))
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { setMargins(dp(14), 0, dp(14), dp(12)) }
        }

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
        view.setBackgroundColor(background)
        view.loadUrl(url)
        webCard.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), dp(14))
        }
        val finishButton = TextView(this).apply {
            text = finishText
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackground(rounded(accent, dp(14).toFloat()))
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener { finishWith(readResult(cookieUrls)) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        bottom.addView(finishButton)

        card.addView(bar)
        card.addView(webCard)
        card.addView(bottom)
        root.addView(card)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) return fallback
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun isLight(color: Int): Boolean {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        return luminance > 0.5
    }

    private fun blend(base: Int, overlay: Int, ratio: Float): Int {
        fun mix(a: Int, b: Int) = (a * (1 - ratio) + b * ratio).toInt().coerceIn(0, 255)
        return Color.rgb(
            mix(Color.red(base), Color.red(overlay)),
            mix(Color.green(base), Color.green(overlay)),
            mix(Color.blue(base), Color.blue(overlay))
        )
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FINISH_TEXT = "finishText"
        const val EXTRA_COOKIE_URLS = "cookieUrls"
        const val EXTRA_ACCENT_COLOR = "accentColor"
        const val EXTRA_CORNER_RADIUS = "cornerRadius"
        const val EXTRA_DIM_ALPHA = "dimAlpha"
        const val EXTRA_SHOW_CLOSE = "showClose"
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
        cookieUrls: List<String>,
        accentColor: String? = null,
        cornerRadiusDp: Int = 0,
        dimAlpha: Float = 0.4f,
        showClose: Boolean = true
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
            accentColor?.let { putExtra(PluginLoginWebViewActivity.EXTRA_ACCENT_COLOR, it) }
            putExtra(PluginLoginWebViewActivity.EXTRA_CORNER_RADIUS, cornerRadiusDp)
            putExtra(PluginLoginWebViewActivity.EXTRA_DIM_ALPHA, dimAlpha)
            putExtra(PluginLoginWebViewActivity.EXTRA_SHOW_CLOSE, showClose)
        }
        context.startActivity(intent)
        deferred.await()
    }

    fun complete(result: JsonObject?) {
        pending?.complete(result)
        pending = null
    }
}
