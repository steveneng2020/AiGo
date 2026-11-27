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
    fun onItemClick(action: PermissionHealthProbe.Action) {
        runCatching {
            val intent = when (action) {
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

                PermissionHealthProbe.Action.STORAGE ->
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${context.packageName}"))

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
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            FileLogger.w(TAG, "跳转失败（$action）：${it.message}")
        }
    }
}
