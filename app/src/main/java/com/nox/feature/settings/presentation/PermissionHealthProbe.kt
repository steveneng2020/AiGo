package com.nox.feature.settings.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.nox.feature.device.AccessibilityGuard
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 权限健康探测。
 *
 * ## 为什么单开一个页面
 *
 * 权限是否就绪，用户很难自己判断：有的看运行时授权、有的看系统开关、
 * 有的没有标准 API（比如「被厂商杀后台」只能靠电池优化白名单近似）。
 * 散在设置页里，用户只能一项项点进去看，且不知道「没开的后果」。
 *
 * 这里集中探测，每项给三样：**状态**、**影响**、**去处**。
 *
 * ## 只列真实用到的权限
 *
 * 不照搬同类实现把所有系统权限都列出来——列了但本应用不用，
 * 用户会以为「不授权会少功能」，那是误导。每一项都对应真实功能。
 */
@Singleton
class PermissionHealthProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 单项状态。分三档而不是「有/无」，因为有些权限是「部分可用」。 */
    enum class State {
        /** 已就绪。 */
        READY,

        /** 看着正常但实际不可用，或依赖系统策略。 */
        ATTENTION,

        /** 未授予，对应功能不可用。 */
        MISSING,
    }

    /** 点击动作。用枚举而不是 lambda，方便 ViewModel 持有并分派。 */
    enum class Action {
        BATTERY_OPTIMIZATION,
        OVERLAY,
        APK_INSTALL,
        STORAGE,
        NOTIFICATION,
        ACCESSIBILITY,
        WRITE_SETTINGS,
    }

    data class Item(
        val key: String,
        val title: String,
        val description: String,
        val state: State,
        val action: Action,
    )

    fun probeAll(): List<Item> = listOf(
        battery(), overlay(), storage(), notification(),
        accessibility(), writeSettings(), apkInstall(),
    )

    private fun battery(): Item {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(context.packageName)
        return Item(
            key = "battery",
            title = "后台运行",
            description = if (exempt) {
                "已加入电池优化白名单，长时间任务不易被系统中断"
            } else {
                "未加入白名单。执行长任务时，息屏后可能被系统中断"
            },
            state = if (exempt) State.READY else State.MISSING,
            action = Action.BATTERY_OPTIMIZATION,
        )
    }

    private fun overlay(): Item {
        val allowed = Settings.canDrawOverlays(context)
        return Item(
            key = "overlay",
            title = "悬浮窗",
            description = if (allowed) {
                "可在其他应用之上显示状态浮层与桌宠"
            } else {
                "未授予。桌宠与悬浮状态条无法显示"
            },
            state = if (allowed) State.READY else State.MISSING,
            action = Action.OVERLAY,
        )
    }

    private fun storage(): Item {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 分媒体权限；项目实际通过 SAF 与自有目录工作，只作提示。
            true
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return Item(
            key = "storage",
            title = "存储访问",
            description = if (granted) {
                "可读写共享目录，导入导出与备份可用"
            } else {
                "未授予。导入插件、导出安装包可能失败"
            },
            state = if (granted) State.READY else State.MISSING,
            action = Action.STORAGE,
        )
    }

    private fun notification(): Item {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return Item(
            key = "notification",
            title = "通知",
            description = if (granted) {
                "可发送进度与完成提醒；离开界面后也能收到结果"
            } else {
                "未授予。任务完成时收不到通知，需要自己回来看"
            },
            state = if (granted) State.READY else State.MISSING,
            action = Action.NOTIFICATION,
        )
    }

    /**
     * 无障碍：**唯一需要区分三档的项**。
     *
     * 「设置里开着」与「服务真的连上」是两回事——前者看着正常，
     * 实际所有手机控制操作都会超时失败。只显示前者会把人骗过。
     */
    private fun accessibility(): Item {
        val connected = AccessibilityGuard.isServiceConnectedStatic()
        val inList = AccessibilityGuard.isInEnabledListStatic(context)
        val switchOn = AccessibilityGuard.isGlobalSwitchOnStatic(context)

        val state = when {
            connected -> State.READY
            inList && switchOn -> State.ATTENTION
            else -> State.MISSING
        }
        val description = when {
            connected -> "已连接，AI 可以打开应用、点击与输入"
            inList && switchOn -> "设置里是开着的，但服务没连上。手机控制会失败，需要重新开启一次"
            else -> "未开启。AI 无法代替你操作手机"
        }
        return Item(
            key = "accessibility",
            title = "无障碍服务",
            description = description,
            state = state,
            action = Action.ACCESSIBILITY,
        )
    }

    private fun writeSettings(): Item {
        val allowed = Settings.System.canWrite(context)
        return Item(
            key = "write_settings",
            title = "修改系统设置",
            description = if (allowed) {
                "AI 可以调整屏幕亮度等系统项"
            } else {
                "未授予。调亮度这类操作会失败"
            },
            state = if (allowed) State.READY else State.MISSING,
            action = Action.WRITE_SETTINGS,
        )
    }

    private fun apkInstall(): Item {
        val allowed = context.packageManager.canRequestPackageInstalls()
        return Item(
            key = "apk_install",
            title = "安装应用",
            description = if (allowed) {
                "AI 可以直接安装它自己构建的 APK"
            } else {
                "未授予。安装时会停在系统确认框，需要你手动点一下"
            },
            state = if (allowed) State.READY else State.MISSING,
            action = Action.APK_INSTALL,
        )
    }

    /** 汇总就绪数，用于页面顶部一句话总结。 */
    fun summary(items: List<Item>): Pair<Int, Int> =
        items.count { it.state == State.READY } to items.size
}
