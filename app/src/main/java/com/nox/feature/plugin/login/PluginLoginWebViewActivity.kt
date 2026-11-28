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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 受控 WebView 登录页：插件传入 URL 与需要读取 cookie 的地址，
 * 用户页内完成登录后回传 { finalUrl, cookies }；取消时返回 null。
 */
class PluginLoginWebViewActivity : Activity() {

    private var webView: WebView? = null

    /** 最近一次页面加载完成时的抓取结果，作为点「完成」时的兜底。 */
    private var lastCapture: JsonObject? = null

    /** 已回传结果，防止自动检测与手动点击重复完成。 */
    private var finished = false

    /** 自动检测的批次号：每次页面加载递增，让旧轮询自然失效。 */
    private var pollToken = 0

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
        // 标题栏关闭默认收起：底部已有「取消」，两个同一个作用只会让人不知道点哪个
        val showClose = intent.getBooleanExtra(EXTRA_SHOW_CLOSE, false)
        val capture = intent.getStringExtra(EXTRA_CAPTURE)?.takeIf { it.isNotBlank() }
        // 页面加载完成后自动抓一轮，避免插件漏掉登录后立即返回的情况
        val autoCapture = intent.getBooleanExtra(EXTRA_AUTO_CAPTURE, false)
        // 自动完成：默认开启，检测到登录成功就直接回传，不要求用户点按钮 —— 像浏览器一样
        val autoFinish = intent.getBooleanExtra(EXTRA_AUTO_FINISH, true)
        val successWhen = intent.getStringExtra(EXTRA_SUCCESS_WHEN)?.takeIf { it.isNotBlank() }
        // 按钮默认全隐：登录完自己关。插件需要手动确认时再打开
        val showFinishButton = intent.getBooleanExtra(EXTRA_SHOW_FINISH_BUTTON, false)
        val showCancel = intent.getBooleanExtra(EXTRA_SHOW_CANCEL, true)
        val cancelText = intent.getStringExtra(EXTRA_CANCEL_TEXT)?.takeIf { it.isNotBlank() } ?: "取消"
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
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 页面每次加载完都把最近一次快照存起来，点「完成」时即使
                // 页面已跳走也能拿到登录态
                if (capture != null || autoCapture) {
                    evaluateCapture(view, capture, cookieUrls) { lastCapture = it }
                }
                if (autoFinish) {
                    // 登录可能分多步（输账号 → 验证码 → 二次验证），单次检测太早；
                    // 改成轮询直到成功或页面关闭，而不是只等一次
                    view?.let { startAutoFinishPolling(it, successWhen) }
                }
            }
        }
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
            setOnClickListener {
                // 点完成时再抓一次：登录往往就在当前这一次跳转里完成，
                // onPageFinished 的快照可能是登录前的
                val view = webView
                if (capture != null && view != null) {
                    evaluateCapture(view, capture, cookieUrls) { fresh ->
                        finishWith(readResult(cookieUrls, capture, fresh ?: lastCapture))
                    }
                } else {
                    finishWith(readResult(cookieUrls, capture, lastCapture))
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        bottom.addView(finishButton)
        // 插件可以用 showFinishButton=false 彻底隐藏主按钮（配合 autoFinish 全自动）
        if (!showFinishButton) bottom.removeView(finishButton)
        // 取消按钮：插件可关闭，并自定义文案
        if (showCancel) {
            val cancelButton = TextView(this).apply {
                text = cancelText
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(onSurface)
                alpha = 0.7f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener { finishWith(null) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(8), 0, 0, 0) }
            }
            bottom.addView(cancelButton)
        }

        card.addView(bar)
        card.addView(webCard)
        // 底部栏完全由插件决定：autoFinish / showFinishButton / showClose 三者组合，
        // 都关掉时整条隐藏，登录成功的回传全交给自动检测
        if (bottom.childCount > 0) {
            card.addView(bottom)
        }
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

    private fun readResult(
        cookieUrls: List<String>,
        capture: String?,
        snapshot: JsonObject?
    ): JsonObject {
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
            // 页面上下文里的 localStorage / sessionStorage 等快照
            snapshot?.let { put("capture", it) }
        }
    }

    /**
     * 在页面上下文执行插件提供的 JS，把返回值写回 [onResult]。
     *
     * 插件侧只能拿到 evaluateJavascript 的字符串结果，所以约定脚本必须返回 JSON 字符串；
     * 解析失败时保留原文，避免因为一个格式问题把整次登录做成失败。
     */
    private fun evaluateCapture(
        view: WebView?,
        script: String?,
        cookieUrls: List<String>,
        onResult: (JsonObject?) -> Unit
    ) {
        if (view == null) return
        val target = script ?: DEFAULT_CAPTURE_SCRIPT
        view.evaluateJavascript(target) { raw ->
            if (raw.isNullOrBlank() || raw == "null") {
                onResult(null)
                return@evaluateJavascript
            }
            // evaluateJavascript 把返回值编码成 JSON 字面量：字符串会被再包一层引号
            val unwrapped = runCatching {
                Json.parseToJsonElement(raw).jsonPrimitive.content
            }.getOrElse { raw }
            val parsed = runCatching {
                Json.parseToJsonElement(unwrapped) as? JsonObject
            }.getOrNull()
            onResult(
                if (parsed != null) {
                    parsed
                } else {
                    buildJsonObject { put("raw", unwrapped.take(MAX_CAPTURE_CHARS)) }
                }
            )
        }
    }

    /**
     * 轮询检测登录状态，命中就回传结果。
     *
     * 页面每次加载都会重启一轮，旧的轮询靠批次号失效，
     * 避免多次跳转后叠加出多份定时器。
     */
    private fun startAutoFinishPolling(view: WebView, successWhen: String?) {
        val token = ++pollToken
        val runnable = object : Runnable {
            var attempts = 0
            override fun run() {
                if (finished || token != pollToken) return
                if (attempts++ >= MAX_POLL_ATTEMPTS) return
                checkLoginSucceeded(view, successWhen)
                if (!finished && token == pollToken) {
                    view.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
        // 首次稍等：刚加载完时凭据往往还没写进 storage
        view.postDelayed(runnable, AUTO_FINISH_DELAY_MS)
    }

    /**
     * 判断登录是否已完成，完成则自动回传。
     *
     * 优先用插件提供的 [successWhen] 表达式；没提供时用默认启发式：
     * cookie 数量变多、或 localStorage 里出现疑似 token 的键，就当作登录成功。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun checkLoginSucceeded(view: WebView?, successWhen: String?) {
        if (view == null || finished) return
        view.evaluateJavascript(successWhen ?: DEFAULT_SUCCESS_SCRIPT) { raw ->
            if (finished) return@evaluateJavascript
            // 表达式约定返回 truthy（"true" / "1" / 不空的字符串）即视为成功
            val truthy = raw != null && raw != "null" && raw != "false" && raw != "\"\""
            if (truthy) {
                // 命中后再抓一次"登录后"的最新状态，而不是用之前的快照
                val script = intent.getStringExtra(EXTRA_CAPTURE)?.takeIf { it.isNotBlank() }
                if (script != null) {
                    evaluateCapture(view, script, cookieUrlsOfIntent()) { fresh ->
                        finishWith(readResult(cookieUrlsOfIntent(), script, fresh ?: lastCapture))
                    }
                } else {
                    evaluateCapture(view, null, cookieUrlsOfIntent()) { fresh ->
                        finishWith(readResult(cookieUrlsOfIntent(), null, fresh ?: lastCapture))
                    }
                }
            }
        }
    }

    private fun cookieUrlsOfIntent(): List<String> = intent.getStringArrayListExtra(EXTRA_COOKIE_URLS)
        .orEmpty()
        .ifEmpty { listOfNotNull(intent.getStringExtra(EXTRA_URL)) }

    private fun finishWith(result: JsonObject?) {
        if (finished) return
        finished = true
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
        const val EXTRA_CAPTURE = "capture"
        const val EXTRA_AUTO_CAPTURE = "autoCapture"
        const val EXTRA_AUTO_FINISH = "autoFinish"
        const val EXTRA_SUCCESS_WHEN = "successWhen"
        const val EXTRA_SHOW_FINISH_BUTTON = "showFinishButton"
        const val EXTRA_SHOW_CANCEL = "showCancel"
        const val EXTRA_CANCEL_TEXT = "cancelText"

        /** 页面加载完后等这么久再判断登录：刚加载完脚本常还没把凭据写进 storage。 */
        private const val AUTO_FINISH_DELAY_MS = 600L

        /** 轮询间隔：登录分多步时靠反复检测捕捉最终状态。 */
        private const val POLL_INTERVAL_MS = 800L

        /** 最多检测次数：约 2 分钟，超过就不再打扰用户，由用户自行点按钮或关闭。 */
        private const val MAX_POLL_ATTEMPTS = 150

        /**
         * 默认的登录成功判据。
         *
         * 只看「凭据已经出现」这类可靠信号，不看 URL 是否变化：
         * 很多站点登录后会跳转，但也有不少是弹层式登录，URL 根本不变。
         */
        private val DEFAULT_SUCCESS_SCRIPT = """
            (function () {
              try {
                var keys = [];
                for (var i = 0; i < localStorage.length; i++) keys.push(localStorage.key(i));
                var tokenish = keys.some(function (k) {
                  return /token|auth|session|credential|ticket/i.test(k);
                });
                if (tokenish) return 'true';
                var cookieNames = document.cookie.split(';').map(function (c) {
                  return c.split('=')[0].trim();
                });
                var cookieHit = cookieNames.some(function (n) {
                  return /token|auth|session|sid|ticket/i.test(n);
                });
                return cookieHit ? 'true' : 'false';
              } catch (e) {
                return 'false';
              }
            })()
        """.trimIndent()

        /** 单次抓取结果的字符上限，防止插件拿到超大字符串后把配置写成巨型文件。 */
        private const val MAX_CAPTURE_CHARS = 256 * 1024

        /**
         * 默认抓取：localStorage + sessionStorage 全量，以及页面上常见存放 token 的位置。
         * 只读不写，不触碰页面的登录流程。
         */
        private val DEFAULT_CAPTURE_SCRIPT = """
            (function () {
              function dump(storage) {
                var out = {};
                try {
                  for (var i = 0; i < storage.length; i++) {
                    var k = storage.key(i);
                    out[k] = storage.getItem(k);
                  }
                } catch (e) {}
                return out;
              }
              var meta = {};
              try {
                var tags = document.querySelectorAll('meta[name],meta[property]');
                for (var j = 0; j < tags.length; j++) {
                  var n = tags[j].getAttribute('name') || tags[j].getAttribute('property');
                  if (n && /token|csrf|auth/i.test(n)) meta[n] = tags[j].getAttribute('content');
                }
              } catch (e) {}
              return JSON.stringify({
                localStorage: dump(window.localStorage),
                sessionStorage: dump(window.sessionStorage),
                meta: meta,
                url: location.href
              });
            })()
        """.trimIndent()
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
        showClose: Boolean = false,
        capture: String? = null,
        autoCapture: Boolean = false,
        autoFinish: Boolean = true,
        successWhen: String? = null,
        showFinishButton: Boolean = false,
        showCancel: Boolean = true,
        cancelText: String? = null
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
            capture?.let { putExtra(PluginLoginWebViewActivity.EXTRA_CAPTURE, it) }
            putExtra(PluginLoginWebViewActivity.EXTRA_AUTO_CAPTURE, autoCapture)
            putExtra(PluginLoginWebViewActivity.EXTRA_AUTO_FINISH, autoFinish)
            successWhen?.let { putExtra(PluginLoginWebViewActivity.EXTRA_SUCCESS_WHEN, it) }
            putExtra(PluginLoginWebViewActivity.EXTRA_SHOW_FINISH_BUTTON, showFinishButton)
            putExtra(PluginLoginWebViewActivity.EXTRA_SHOW_CANCEL, showCancel)
            cancelText?.let { putExtra(PluginLoginWebViewActivity.EXTRA_CANCEL_TEXT, it) }
        }
        context.startActivity(intent)
        deferred.await()
    }

    fun complete(result: JsonObject?) {
        pending?.complete(result)
        pending = null
    }
}
