package com.nox.feature.settings.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.nox.R

/**
 * 可切换的启动图标。每个选项对应 Manifest 里一个 activity-alias，
 * 切换时启用目标 alias、禁用其余两个，桌面入口随之变化。
 */
enum class AppIconOption(
    val id: String,
    val aliasSuffix: String,
    @StringRes val labelRes: Int,
    @DrawableRes val previewRes: Int
) {
    DEFAULT("default", ".MainActivityDefault", R.string.app_icon_default, R.drawable.app_icon_preview_default),
    GLASSES("glasses", ".MainActivityGlasses", R.string.app_icon_glasses, R.drawable.app_icon_preview_glasses),
    WRENCH("wrench", ".MainActivityWrench", R.string.app_icon_wrench, R.drawable.app_icon_preview_wrench);

    companion object {
        fun byId(id: String?): AppIconOption = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

object AppIconManager {
    private const val PREFS = "app_icon_prefs"
    private const val KEY = "selected_icon"

    /** 图标变更广播：常驻通知的持有者收到后重建通知，否则状态栏仍是旧图标。 */
    const val ACTION_ICON_CHANGED = "com.aigo.action.APP_ICON_CHANGED"

    fun current(context: Context): AppIconOption =
        AppIconOption.byId(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        )

    fun apply(context: Context, option: AppIconOption) {
        val pm = context.packageManager
        AppIconOption.entries.forEach { candidate ->
            val component = ComponentName(context.packageName, context.packageName + candidate.aliasSuffix)
            val newState = if (candidate == option) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            runCatching {
                pm.setComponentEnabledSetting(component, newState, PackageManager.DONT_KILL_APP)
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, option.id)
            .apply()
        // 已发出的通知不会自己换图标，广播一句让常驻通知重建。
        context.sendBroadcast(Intent(ACTION_ICON_CHANGED).setPackage(context.packageName))
    }

    /** 切完图标回桌面，让启动器重新读取入口。 */
    fun goHome(context: Context) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * 当前图标对应的通知栏 / 任务卡片小图标。
     *
     * 之前各处通知直接用 `android.R.drawable.ic_menu_info_details` 等系统图标，
     * 与用户在「图标设置」里选的图标毫无关系，所以改了图标后状态栏仍是旧样子。
     * 这里按当前选项解析出实际 drawable，已发出的通知由调用方重新 notify 刷新。
     *
     * 小图标建议是单色剪影：彩色图会被系统映成灰块，但在 API 28 上会尽量保留原色，
     * 这里直接用应用图标本身，与桌面图标保持视觉一致。
     */
    fun smallIconRes(context: Context): Int = when (current(context)) {
        AppIconOption.DEFAULT -> R.mipmap.ic_launcher
        AppIconOption.GLASSES -> R.mipmap.ic_launcher_glasses
        AppIconOption.WRENCH -> R.mipmap.ic_launcher_wrench
    }
}
