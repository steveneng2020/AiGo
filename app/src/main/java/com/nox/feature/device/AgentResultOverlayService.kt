package com.nox.feature.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nox.MainActivity
import com.nox.R
import com.nox.core.util.FileLogger

/**
 * AI 操作手机时的悬浮反馈，两种形态：
 *
 * - 思考中：顶部的窄胶囊，打开应用后出现一次，告诉用户 AI 正在接管；
 * - 已完成：底部卡片，展示结果并提供回到 Nox 的入口。
 *
 * 全程用自绘 View：悬浮窗没有 ViewTreeLifecycleOwner，放 ComposeView 会崩。
 */
class AgentResultOverlayService : Service() {

    companion object {
        private const val TAG = "AgentResultOverlay"
        private const val CHANNEL_ID = "agent_result_overlay"
        private const val NOTIFICATION_ID = 4321
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_DETAIL = "detail"

        /** 完成面板停留多久后自动收起。 */
        private const val AUTO_DISMISS_MS = 20_000L

        private const val ACCENT_BLUE = 0xFF1A6DFF.toInt()
        private const val ACCENT_GREEN = 0xFF34C759.toInt()
        private const val TEXT_PRIMARY = 0xFF1A1A1A.toInt()
        private const val TEXT_SECONDARY = 0xFF6B6B6B.toInt()
        private const val TEXT_BODY = 0xFF333333.toInt()

        /** 底部完成面板。 */
        fun showResult(context: Context, title: String, detail: String) {
            start(context, title, detail)
        }

        private fun start(context: Context, title: String, detail: String) {
            val intent = Intent(context, AgentResultOverlayService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_DETAIL, detail)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { FileLogger.w(TAG, "启动悬浮反馈失败", it) }
        }
    }

    private lateinit var windowManager: WindowManager
    private var panel: View? = null
    private var dismissRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val detail = intent?.getStringExtra(EXTRA_DETAIL).orEmpty()

        showResultPanel(title, detail)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        dismissRunnable?.let { panel?.removeCallbacks(it) }
        removePanel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    // ── 已完成 ───────────────────────────────────────────

    private fun showResultPanel(title: String, detail: String) {
        removePanel()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dp(24).toFloat(), dp(24).toFloat(),
                    dp(24).toFloat(), dp(24).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(Color.WHITE)
            }
            setPadding(dp(20), dp(8), dp(20), dp(18))
        }

        // 顶部拖动指示条
        card.addView(View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(0xFFDDDDDD.toInt())
            }
        }, LinearLayout.LayoutParams(dp(40), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(4)
            bottomMargin = dp(12)
        })

        // 标题行：绿色对勾 + 已完成
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(CheckBadgeView(this), LinearLayout.LayoutParams(dp(22), dp(22)))
        titleRow.addView(TextView(this).apply {
            text = title.ifBlank { "已完成" }
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(17f))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        })
        card.addView(titleRow)

        // 正文（可滚动，最多占屏幕一半）
        val body = TextView(this).apply {
            text = detail
            setTextColor(TEXT_BODY)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(15f))
            setLineSpacing(sp(6f), 1f)
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        }
        card.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(12)
            // 正文最多占半屏，超出在卡片内滚动，不让面板顶到状态栏
            height = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        })

        // 底部按钮：复制 / 完成 / 回到 Nox
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(
            iconButton(CopyGlyphView(this)) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Nox", detail))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            },
            LinearLayout.LayoutParams(dp(48), dp(48))
        )
        actions.addView(pillButton("完成", filled = false) { stopSelf() }, weighted())
        actions.addView(pillButton("回到 Nox", filled = true) {
            stopSelf()
            val open = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            runCatching { startActivity(open) }
        }, weighted())

        card.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        attach(card, params)
        card.post {
            card.translationY = card.height.coerceAtLeast(dp(80)).toFloat()
            card.animate().translationY(0f).setDuration(220L).start()
        }

        val dismiss = Runnable { slideOutAndStop() }
        dismissRunnable = dismiss
        card.postDelayed(dismiss, AUTO_DISMISS_MS)
    }

    private fun weighted() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        leftMargin = dp(10)
    }

    private fun pillButton(label: String, filled: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(15f))
            setTextColor(if (filled) Color.WHITE else TEXT_PRIMARY)
            if (filled) setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(if (filled) ACCENT_BLUE else 0xFFF2F2F3.toInt())
            }
            setOnClickListener { onClick() }
        }

    private fun iconButton(content: View, onClick: () -> Unit): FrameLayout =
        FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xFFF2F2F3.toInt())
            }
            addView(content, FrameLayout.LayoutParams(dp(18), dp(18)).apply {
                gravity = Gravity.CENTER
            })
            setOnClickListener { onClick() }
        }

    private fun slideOutAndStop() {
        val current = panel ?: return
        current.animate()
            .translationY(current.height.coerceAtLeast(dp(120)).toFloat())
            .alpha(0f)
            .setDuration(180L)
            .withEndAction { stopSelf() }
            .start()
    }

    // ── 窗口 ─────────────────────────────────────────────

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun attach(view: View, params: WindowManager.LayoutParams) {
        runCatching { windowManager.addView(view, params) }
            .onFailure {
                FileLogger.w(TAG, "添加悬浮视图失败", it)
                stopSelf()
                return
            }
        panel = view
    }

    private fun removePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
    }

    // ── 自绘图标 ─────────────────────────────────────────

    /** 绿色圆形对勾。 */
    private class CheckBadgeView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_GREEN }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val r = minOf(width, height) / 2f
            canvas.drawCircle(width / 2f, height / 2f, r, fill)
            val path = android.graphics.Path().apply {
                moveTo(width * 0.30f, height * 0.52f)
                lineTo(width * 0.45f, height * 0.67f)
                lineTo(width * 0.72f, height * 0.36f)
            }
            canvas.drawPath(path, stroke)
        }
    }

    /** 复制图标：两个叠放的方框。 */
    private class CopyGlyphView(context: Context) : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6B6B6B.toInt()
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.5f
        }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val back = android.graphics.RectF(d * 2f, d * 2f, width - d * 7f, height - d * 7f)
            val front = android.graphics.RectF(d * 7f, d * 7f, width - d * 2f, height - d * 2f)
            canvas.drawRoundRect(back, d * 2f, d * 2f, stroke)
            canvas.drawRoundRect(front, d * 2f, d * 2f, stroke)
        }
    }

    // ── 通知（前台服务要求） ─────────────────────────────

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.agent_result_overlay_channel_title),
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.agent_result_overlay_channel_title))
            .setSmallIcon(com.nox.feature.settings.data.repository.AppIconManager.smallIconRes(applicationContext))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
