package com.nox.feature.plugin.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nox.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pluginBlockPrefs by preferencesDataStore(
    name = "plugin_block_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 被屏蔽的插件 id 集合。
 *
 * 「屏蔽」与「关闭开关」不同：
 * - 关闭开关 = 插件仍装在设备上、仍在列表里，只是不加载。
 * - 屏蔽 = 从「已安装」与「插件市场」两个列表里彻底隐藏，眼不见为净；
 *   安装目录、用户数据、配置全部保留，随时可在「已屏蔽」里恢复。
 *
 * 记录「屏蔽的」而不是「可见的」，理由同 [com.nox.feature.settings.data.repository.PluginDrawerVisibilityRepository]：
 * 新装插件默认应该可见，记录可见集合会让新插件默认消失。
 *
 * 屏蔽对未安装的商店插件同样有效（按 id 记录，与安装状态无关）。
 */
@Singleton
class PluginBlockRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val BLOCKED_IDS = stringSetPreferencesKey("blocked_plugin_ids")
    }

    /** 被屏蔽的插件 id 集合。 */
    val blockedIdsFlow: Flow<Set<String>> = context.pluginBlockPrefs.data.map { prefs ->
        prefs[BLOCKED_IDS].orEmpty()
    }

    suspend fun blockedIds(): Set<String> = blockedIdsFlow.first()

    suspend fun block(pluginId: String) {
        context.pluginBlockPrefs.edit { prefs ->
            prefs[BLOCKED_IDS] = prefs[BLOCKED_IDS].orEmpty() + pluginId
        }
    }

    suspend fun unblock(pluginId: String) {
        context.pluginBlockPrefs.edit { prefs ->
            prefs[BLOCKED_IDS] = prefs[BLOCKED_IDS].orEmpty() - pluginId
        }
    }

    suspend fun setBlocked(pluginId: String, blocked: Boolean) {
        if (blocked) block(pluginId) else unblock(pluginId)
    }
}
