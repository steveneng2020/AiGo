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
    }

    /** 切完图标回桌面，让启动器重新读取入口。 */
    fun goHome(context: Context) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(intent) }
    }
}
