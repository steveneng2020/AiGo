package com.nox.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nox.core.util.FileLogger
import com.nox.feature.device.AccessibilityGuard
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 无障碍保活页的 ViewModel。
 *
 * ## 为什么开关要能「关掉」
 *
 * 自动写回系统设置是有副作用的行为（会 fork su、会有毫秒级设置抖动）。
 * 即使设备有 Root，也应该由用户决定要不要开。同类实现里这个开关默认关闭，
 * 这里保持一致：**默认关，用户主动开**。
 *
 * ## 为什么状态要带「等级」
 *
 * 界面需要根据等级决定开关是否可用。等级由 [AccessibilityGuard.currentLevel] 判定：
 * 有 LSPosed → HOOK_ENFORCED；有 Root → ROOT_REPAIR；否则 OBSERVE_ONLY。
 * 把判定放在 ViewModel 而不是 Composable 里，是因为它要走 `su` 探测（有 IO 开销）。
 */
@HiltViewModel
class AccessibilityGuardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val guard: AccessibilityGuard,
) : ViewModel() {

    private companion object {
        const val TAG = "AccessibilityGuardVM"
        const val PREFS = "accessibility_guard"
        const val KEY_KEEP_ENABLED = "keep_enabled"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _status = MutableStateFlow<AccessibilityGuard.Status?>(null)
    val status: StateFlow<AccessibilityGuard.Status?> = _status.asStateFlow()

    /** 用户是否开启自动保持。默认关——写系统设置有副作用，应由用户决定。 */
    private val _keepEnabled = MutableStateFlow(prefs.getBoolean(KEY_KEEP_ENABLED, false))
    val keepEnabled: StateFlow<Boolean> = _keepEnabled.asStateFlow()

    init {
        // 应用启动即开始感知（L1），不依赖用户进这个页面。
        // 这里在 ViewModel init 里兜一次，防止 Application 那边漏调。
        guard.start()
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { guard.status() }
                .onSuccess { _status.value = it }
                .onFailure { FileLogger.w(TAG, "读取无障碍状态失败：${it.message}") }
        }
    }

    fun setKeepEnabled(enabled: Boolean) {
        // 等级不支持时不允许打开，避免「开关开着但什么也没发生」的困惑。
        val level = _status.value?.level
        if (enabled && level != AccessibilityGuard.Level.ROOT_REPAIR) {
            FileLogger.w(TAG, "当前等级 $level 不支持自动保持，忽略开启请求")
            return
        }
        _keepEnabled.value = enabled
        prefs.edit().putBoolean(KEY_KEEP_ENABLED, enabled).apply()
        FileLogger.i(TAG, "自动保持无障碍：${if (enabled) "开启" else "关闭"}")
        refresh()
    }

    fun openSystemSettings() {
        guard.openSystemSettings()
    }
}
