package com.nox.feature.settings.data.repository

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

private val Context.pluginDrawerPrefs by preferencesDataStore(
    name = "plugin_drawer_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 抽屉「插件」区显示哪些插件入口。
 *
 * 默认全显示（`hiddenKeys` 为空），用户隐藏后才记入集合。之所以记录「隐藏的」而不是
 * 「显示的」，是因为插件随时可能新增或卸载：记录显示集合时，新装的插件默认不可见，
 * 用户会以为装坏了；记录隐藏集合同样能保留用户的选择，新增项默认可见。
 *
 * 键是 [com.nox.feature.plugin.domain.PluginUiEntry.key]（`pluginId:localId`），
 * 同一插件的多个入口可以独立控制。
 */
@Singleton
class PluginDrawerVisibilityRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val HIDDEN_KEYS = stringSetPreferencesKey("hidden_entry_keys")
    }

    /** 被隐藏的入口键集合。 */
    val hiddenKeysFlow: Flow<Set<String>> = context.pluginDrawerPrefs.data.map { prefs ->
        prefs[HIDDEN_KEYS].orEmpty()
    }

    /** 读取一次当前值。 */
    suspend fun hiddenKeys(): Set<String> = hiddenKeysFlow.first()

    suspend fun setVisible(key: String, visible: Boolean) {
        context.pluginDrawerPrefs.edit { prefs ->
            val current = prefs[HIDDEN_KEYS].orEmpty().toMutableSet()
            if (visible) current.remove(key) else current.add(key)
            prefs[HIDDEN_KEYS] = current
        }
    }

    /** 插件卸载后清掉它的记录，避免同 id 重装时沿用旧状态。 */
    suspend fun forget(prefix: String) {
        context.pluginDrawerPrefs.edit { prefs ->
            val current = prefs[HIDDEN_KEYS].orEmpty().toMutableSet()
            val removed = current.removeAll { it.startsWith("$prefix:") }
            if (removed) prefs[HIDDEN_KEYS] = current
        }
    }
}
