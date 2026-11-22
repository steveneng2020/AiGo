package com.nox.feature.agent.data.touchlock

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nox.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.touchLockStore by preferencesDataStore(
    name = "touch_lock_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 防误触模式的持久化状态。
 *
 * 为什么需要持久化：防误触开启期间会临时调低屏幕亮度并加一层拦截触摸的全屏遮罩。
 * 如果进程被杀（后台回收、崩溃、手动划掉），遮罩和亮度都会「卡」在异常状态：
 * 亮度停在极暗、下次启动仍以为处于防误触。因此状态必须落盘，
 * 启动时按落盘值决定是否恢复。
 *
 * 亮度恢复是关键：进入时记下用户原亮度，退出时写回。若记录丢失，
 * 退出后宁可不动系统亮度（说明从未改过），也不要写一个猜测值。
 */
@Singleton
class TouchLockManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ACTIVE = booleanPreferencesKey("touch_lock_active")
        val SAVED_BRIGHTNESS = intPreferencesKey("touch_lock_saved_brightness")
        val SAVED_MODE = intPreferencesKey("touch_lock_saved_brightness_mode")
        /** 长按退出所需时长（毫秒）。3 秒是误触防护与操作成本之间的平衡点。 */
        const val UNLOCK_HOLD_MS = 3000L
        /** 防误触期间的目标亮度：尽量暗但仍可见，避免全黑让人以为死机。 */
        const val LOCKED_BRIGHTNESS = 1
    }

    val activeFlow: Flow<Boolean> = context.touchLockStore.data.map { it[ACTIVE] ?: false }

    suspend fun isActive(): Boolean = activeFlow.first()

    /**
     * 进入防误触：记下当前亮度并调暗。
     *
     * 只有能写系统设置的设备才调亮度；没授权时只做触摸拦截，
     * 不因权限缺失导致整个功能不可用。
     */
    suspend fun enter() {
        if (isActive()) return
        if (canWriteSystemSettings()) {
            val current = runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            }.getOrNull()
            val mode = runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
            }.getOrNull()
            context.touchLockStore.edit { prefs ->
                if (current != null) prefs[SAVED_BRIGHTNESS] = current
                if (mode != null) prefs[SAVED_MODE] = mode
                prefs[ACTIVE] = true
            }
            runCatching {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    LOCKED_BRIGHTNESS
                )
            }
        } else {
            context.touchLockStore.edit { it[ACTIVE] = true }
        }
    }

    /** 退出防误触：把亮度写回进入前记下的值。 */
    suspend fun exit() {
        val prefs = context.touchLockStore.data.first()
        val savedBrightness = prefs[SAVED_BRIGHTNESS]
        val savedMode = prefs[SAVED_MODE]
        context.touchLockStore.edit {
            it[ACTIVE] = false
            it.remove(SAVED_BRIGHTNESS)
            it.remove(SAVED_MODE)
        }
        if (!canWriteSystemSettings()) return
        runCatching {
            savedBrightness?.let {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    it
                )
            }
            savedMode?.let {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    it
                )
            }
        }
    }

    /**
     * 进程被系统杀掉后重启时，把亮度从防误触的暗值恢复回来。
     *
     * 不在这里退出防误触：用户可能确实还想保持锁定状态（比如把手机放兜里），
     * 只把亮度恢复，避免「重启后屏幕一直很暗以为坏了」。
     */
    suspend fun restoreBrightnessIfStale() {
        val prefs = context.touchLockStore.data.first()
        val active = prefs[ACTIVE] ?: false
        if (!active) return
        val saved = prefs[SAVED_BRIGHTNESS] ?: return
        if (!canWriteSystemSettings()) return
        runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, saved)
            prefs[SAVED_MODE]?.let {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    it
                )
            }
        }
        context.touchLockStore.edit { it.remove(SAVED_BRIGHTNESS); it.remove(SAVED_MODE) }
    }

    private fun canWriteSystemSettings(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }
}
