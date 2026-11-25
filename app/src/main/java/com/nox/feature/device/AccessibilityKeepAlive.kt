package com.nox.feature.device

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import com.nox.core.util.FileLogger

/**
 * 无障碍服务保活。
 *
 * 系统（尤其 MIUI/HyperOS）会在内存紧张、省电策略介入、应用被强停或覆盖安装后
 * 静默关掉无障碍服务，用户感知就是「手机控制忽然不能用了」，每次都要手动回去重开。
 *
 * 应用层拿不到「重新开启无障碍」的权限（那是 system_server 的权限），
 * 所以这里做两件能做到的事：
 * 1. 记录服务被关的时刻与次数，供界面提示与排查；
 * 2. 应用回到前台时检测服务是否还活着，被关了就引导用户一键跳转发设置页，
 *    把「发现不能用了 → 自己去翻设置」缩短成「发现 → 点一下就回去开」。
 */
object AccessibilityKeepAlive {

    private const val TAG = "AccessibilityKeepAlive"
    private const val PREFS = "accessibility_keepalive"
    private const val KEY_LAST_DISABLED_AT = "last_disabled_at"
    private const val KEY_DISABLED_COUNT = "disabled_count"

    /** 服务被系统关掉时调用，记录时间与累计次数。 */
    fun recordDisabled(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_DISABLED_COUNT, 0) + 1
        prefs.edit()
            .putLong(KEY_LAST_DISABLED_AT, System.currentTimeMillis())
            .putInt(KEY_DISABLED_COUNT, count)
            .apply()
        FileLogger.w(TAG, "无障碍服务被关闭（累计 $count 次），应用回前台时会提示恢复")
    }

    /** 累计被关闭次数，用于提示文案与排查。 */
    fun disabledCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_DISABLED_COUNT, 0)

    /**
     * 服务当前是否真的在系统里启用。
     *
     * 不能只看 [PhoneControlService.current()]：进程刚启动时服务可能还没来得及 onServiceConnected，
     * 那种情况会被误判成「被关闭」而弹提示。这里读系统设置里的启用列表，是权威来源。
     */
    fun isServiceEnabledInSystem(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = "${context.packageName}/${PhoneControlService::class.java.name}"
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (item in splitter) {
            if (item.equals(target, ignoreCase = true)) return true
        }
        return false
    }

    /**
     * 应用回到前台时调用：服务被系统关了就返回 true，调用方据此提示用户。
     *
     * 只在「用户本来开过（被关次数 > 0 或当前未启用）」时提示，避免从没开过的用户
     * 每次进应用都被弹一次。
     */
    fun shouldPromptRestore(context: Context): Boolean {
        if (isServiceEnabledInSystem(context)) return false
        return disabledCount(context) > 0
    }

    /** 跳到系统无障碍设置页，让用户重新开启。 */
    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { FileLogger.w(TAG, "打开无障碍设置失败", it) }
    }
}
