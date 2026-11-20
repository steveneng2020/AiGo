package com.nox.feature.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import com.nox.core.util.FileLogger
import java.lang.ref.WeakReference

/**
 * 手机控制的无障碍服务。
 *
 * 职责有三：把实例暴露给 [PhoneControlManager]、记录前台包名、
 * 以及在界面变化时刷新 [ScreenSnapshot]——点击依赖这份常驻快照才够快。
 */
class PhoneControlService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        // 常驻快照：带上不重要的视图，否则很多自绘控件的文本拿不到
        serviceInfo = serviceInfo?.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        ScreenSnapshot.requestRebuild(this)
        FileLogger.i(TAG, "无障碍服务已连接，快照已初始化")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                event.packageName?.toString()?.takeIf { it.isNotBlank() }?.let {
                    foregroundPackage = it
                }
                ScreenSnapshot.requestRebuild(this)
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        FileLogger.i(TAG, "无障碍服务已断开")
        instance = null
        foregroundPackage = null
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "PhoneControlService"

        private var instance: WeakReference<PhoneControlService>? = null

        /** 最近一次窗口事件的包名，用于「当前在哪个应用」。 */
        @Volatile
        var foregroundPackage: String? = null
            private set

        internal fun current(): PhoneControlService? = instance?.get()
    }
}
