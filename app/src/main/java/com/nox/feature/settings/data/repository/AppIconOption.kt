package com.nox.feature.settings.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.nox.MainActivity
import com.nox.R

/**
 * 可切换的启动图标。每个选项对应 Manifest 里一个 activity-alias，
 * 切换时启用目标 alias、禁用其余全部，桌面入口随之变化。
 *
 * 排序即 UI 展示顺序：鲲（《山海经》神兽）六套配色在最前，
 * 原有三套（默认猫头鹰 / 绿镜 / 扳手）在后。DEFAULT 是声明顺序第一个，
 * 也就是「金黄色鲲」，与 manifest 里 android:enabled="true" 的 alias 保持一致。
 */
enum class AppIconOption(
    val id: String,
    val aliasSuffix: String,
    @StringRes val labelRes: Int,
    @DrawableRes val previewRes: Int,
    @DrawableRes val smallIconRes: Int
) {
    KUN_DEFAULT(
        "kun_default", ".MainActivityKunDefault",
        R.string.app_icon_kun_default, R.mipmap.ic_launcher_default,
        R.drawable.kun_brand_fg_default
    ),
    KUN_MOYU(
        "kun_moyu", ".MainActivityKunMoyu",
        R.string.app_icon_kun_moyu, R.mipmap.ic_launcher_moyu,
        R.drawable.kun_brand_fg_moyu
    ),
    KUN_TIANQING(
        "kun_tianqing", ".MainActivityKunTianqing",
        R.string.app_icon_kun_tianqing, R.mipmap.ic_launcher_tianqing,
        R.drawable.kun_brand_fg_tianqing
    ),
    KUN_ZHESHI(
        "kun_zheshi", ".MainActivityKunZheshi",
        R.string.app_icon_kun_zheshi, R.mipmap.ic_launcher_zheshi,
        R.drawable.kun_brand_fg_zheshi
    ),
    KUN_BIYU(
        "kun_biyu", ".MainActivityKunBiyu",
        R.string.app_icon_kun_biyu, R.mipmap.ic_launcher_biyu,
        R.drawable.kun_brand_fg_biyu
    ),
    KUN_ZISHOU(
        "kun_zishou", ".MainActivityKunZishou",
        R.string.app_icon_kun_zishou, R.mipmap.ic_launcher_zishou,
        R.drawable.kun_brand_fg_zishou
    ),
    DEFAULT("default", ".MainActivityDefault", R.string.app_icon_default, R.drawable.app_icon_preview_default, R.drawable.app_icon_preview_default),
    GLASSES("glasses", ".MainActivityGlasses", R.string.app_icon_glasses, R.drawable.app_icon_preview_glasses, R.drawable.app_icon_preview_glasses),
    WRENCH("wrench", ".MainActivityWrench", R.string.app_icon_wrench, R.drawable.app_icon_preview_wrench, R.drawable.app_icon_preview_wrench);

    companion object {
        /** 首次安装与无法识别的 id 都落到金黄色鲲。 */
        fun byId(id: String?): AppIconOption = entries.firstOrNull { it.id == id } ?: KUN_DEFAULT

        /**
         * 把某个选项的图标渲染成位图，供 Compose 预览。
         *
         * 不能用 painterResource：鲲的六套与原有三套都是 mipmap-anydpi-v26 下的
         * adaptive-icon XML，painterResource 只接受 VectorDrawable 与位图，
         * 遇到 adaptive-icon 会抛 IllegalArgumentException，导致图标设置页直接崩溃。
         * 这里用 Resources.getDrawable 拿到已适配的 Drawable 再画成位图。
         */
        fun previewBitmap(context: Context, option: AppIconOption, sizePx: Int): android.graphics.Bitmap? =
            runCatching {
                val side = sizePx.coerceAtLeast(1)
                val bitmap = android.graphics.Bitmap.createBitmap(
                    side, side, android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bitmap)
                // 优先用 PackageManager 加载应用级图标（它能正确处理 adaptive-icon），
                // 但那只反映「当前生效」的图标；要展示其它选项必须按资源 id 取。
                // AdaptiveIconDrawable 直接 draw 在部分 ROM 上会抛异常，故逐层兜底。
                val drawable = androidx.core.content.ContextCompat.getDrawable(context, option.previewRes)
                    ?: return@runCatching null
                drawable.setBounds(0, 0, side, side)
                drawable.draw(canvas)
                bitmap
            }.getOrNull()
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

    /**
     * activity-alias 所在的包名，即 AndroidManifest 的 package（源码命名空间 com.nox），
     * **不是** applicationId。二者在本项目里被刻意分开（applicationId=com.aigo，
     * namespace=com.nox）。用 context.packageName 会拼出 com.aigo.MainActivityDefault，
     * 而真实组件是 com.nox.MainActivityDefault，setComponentEnabledSetting 静默失败，
     * 表现为切换图标后桌面毫无变化。
     */
    private fun aliasPackage(context: Context): String =
        runCatching {
            context.packageManager
                .getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
                .packageName
        }.getOrDefault(context.packageName)

    fun apply(context: Context, option: AppIconOption) {
        val pm = context.packageManager
        val aliasPkg = aliasPackage(context)
        AppIconOption.entries.forEach { candidate ->
            val component = ComponentName(aliasPkg, aliasPkg + candidate.aliasSuffix)
            val newState = if (candidate == option) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            // 切换失败不能静默吞掉：组件名拼错时 setComponentEnabledSetting 不报错也不生效，
            // 用户只会看到「点了没反应」。失败时至少记录日志，便于定位。
            runCatching {
                pm.setComponentEnabledSetting(component, newState, PackageManager.DONT_KILL_APP)
            }.onFailure {
                android.util.Log.w("AppIconManager", "切换图标失败: $component", it)
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
    /**
     * 当前图标对应的通知栏小图标。
     *
     * 必须能安全加载：通知小图标在系统进程里解析，拿到 adaptive-icon XML
     * （mipmap-anydpi-v26 下的 ic_launcher_*）会直接失败。所以这里用
     * 各密度目录下的 PNG 位图，与桌面图标保持同一套配色。
     *
     * 逐层兜底：资源不可用时退回系统图标，不让通知构建失败。
     */
    fun smallIconRes(context: Context): Int {
        val candidate = current(context).smallIconRes
        val usable = runCatching {
            context.resources.getDrawable(candidate, null)
            true
        }.getOrDefault(false)
        return if (usable) candidate else android.R.drawable.stat_notify_chat
    }
}
