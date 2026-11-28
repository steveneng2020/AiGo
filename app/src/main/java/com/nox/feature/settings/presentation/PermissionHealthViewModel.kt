package com.nox.feature.settings.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nox.R
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
     * 存储访问已授予时的提示。
     *
     * 为什么不再跳系统页：
     *
     * Android 没有官方常量能直达「某应用的运行时权限列表」。曾试过
     * `android.intent.action.MANAGE_APP_PERMISSIONS` + resolveActivity 判定，
     * 2026-11-28 在 MIUI/HyperOS 上实测 resolveActivity 返回 null，
     * 于是退回 ACTION_APPLICATION_DETAILS_SETTINGS——落在「应用信息」首页，
     * 权限入口还要用户自己往下滑。用户点「存储访问」却停在应用信息顶部，
     * 困惑大于帮助，等于白点。
     *
     * 现在的做法：直接告诉用户去哪改，不再跳转。
     * 优点是不依赖任何 ROM 私有实现，升级也不会失效。
     */
    fun showStorageGrantedHint() {
        val msg = context.getString(R.string.settings_permission_storage_granted_hint)
        runCatching {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }.onFailure {
            FileLogger.w(TAG, "显示存储权限提示失败：${it.message}")
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
