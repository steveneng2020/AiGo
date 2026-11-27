package com.nox.feature.settings.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nox.core.util.FileLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 权限健康页的 ViewModel。
 *
 * 职责很薄：探测 + 跳转。真正的判定逻辑在 [PermissionHealthProbe] 里，
 * 因为那里可以用 Context 直接读系统状态，不需要经过 UI 层。
 */
@HiltViewModel
class PermissionHealthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: PermissionHealthProbe,
) : ViewModel() {

    private companion object {
        const val TAG = "PermissionHealthVM"
    }

    private val _items = MutableStateFlow<List<PermissionHealthProbe.Item>>(emptyList())
    val items: StateFlow<List<PermissionHealthProbe.Item>> = _items.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { probe.probeAll() }
                .onSuccess { _items.value = it }
                .onFailure { FileLogger.w(TAG, "探测权限状态失败：${it.message}") }
        }
    }

    fun summary(): Pair<Int, Int> = probe.summary(_items.value)

    /**
     * 按动作跳到对应的系统页面。
     *
     * 每个分支都单独 try：某些 ROM 缺某个 Action 时会抛 ActivityNotFoundException，
     * 不能让一项失败影响其他项。
     */
    /**
     * 打开本应用的系统详情页。
     *
     * 用于「运行时权限已授予」的情况：此时 requestPermissions 会立刻返回、
     * 不弹框（用户看到的是点了没反应），而系统又没有直达单个 runtime 权限的
     * 稳定 Action。跳应用详情是能做到的最接近的落点——用户可以在那里看到
     * 权限列表并自行撤销。
     */
    fun openAppDetails() {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            FileLogger.w(TAG, "打开应用详情失败：${it.message}")
        }
    }

    fun onItemClick(action: PermissionHealthProbe.Action) {
        runCatching {
            val intent: Intent? = when (action) {
                PermissionHealthProbe.Action.BATTERY_OPTIMIZATION ->
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

                PermissionHealthProbe.Action.OVERLAY ->
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )

                PermissionHealthProbe.Action.APK_INSTALL ->
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    )

                // 存储权限交给 UI 层用运行时请求弹框处理，这里不跳设置页。
                // 原因：本应用 targetSdk=28，READ/WRITE_EXTERNAL_STORAGE 是普通运行时权限，
                // 直接 requestPermissions 一步到位；跳「应用信息 → 权限」要多点两次，
                // 而且系统没有直达单个 runtime 权限的稳定 Action。
                // 这里返回 null 表示「无系统页可跳」，由调用方决定弹框。
                PermissionHealthProbe.Action.STORAGE -> null

                PermissionHealthProbe.Action.NOTIFICATION ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    } else {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:${context.packageName}"))
                    }

                PermissionHealthProbe.Action.ACCESSIBILITY ->
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

                PermissionHealthProbe.Action.WRITE_SETTINGS ->
                    Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    )
            }
            if (intent != null) {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }.onFailure {
            FileLogger.w(TAG, "跳转失败（$action）：${it.message}")
        }
    }
}
