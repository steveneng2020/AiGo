package com.nox.feature.plugin.dsl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nox.feature.plugin.domain.InstalledPlugin
import com.nox.feature.plugin.domain.PluginManager
import com.nox.feature.plugin.domain.PluginScreen
import com.nox.feature.plugin.runtime.PluginRuntimeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject

/** 一个屏幕槽当前的接管状态。 */
sealed interface PluginScreenSlot {
    data object Idle : PluginScreenSlot
    data object Loading : PluginScreenSlot
    data class Ready(
        val screen: PluginScreen,
        val pluginName: String,
        val document: DslDocument
    ) : PluginScreenSlot

    data class Failed(
        val pluginName: String,
        val message: String
    ) : PluginScreenSlot
}

/**
 * 整屏接管的状态中心。
 *
 * 取 DSL 有两条路径：包内静态文件（无需 runtime）与插件进程的 handler（可动态生成）。
 * 交互事件统一回传给同一 handler，由插件返回新文档——宿主不维护插件的业务状态，
 * 只把当前节点值和文档自带的 state 一起带过去，避免宿主变成插件的运行时。
 *
 * 用户可以对某个接管点选择「恢复默认」，只对本次运行有效：插件声明了要接管就该接管，
 * 想永久关掉接管就停用插件——这与插件系统其余开关的语义一致，不会出现「关掉后再也找不到恢复入口」。
 */
@HiltViewModel
class PluginScreenViewModel @Inject constructor(
    private val pluginManager: PluginManager,
    private val runtimeManager: PluginRuntimeManager
) : ViewModel() {

    /** 本进程内被临时退出的接管点。只存内存，重启应用后插件界面重新生效。 */
    private val dismissed = MutableStateFlow<Set<String>>(emptySet())

    private val _slots = MutableStateFlow<Map<String, PluginScreenSlot>>(emptyMap())
    val slots: StateFlow<Map<String, PluginScreenSlot>> = _slots.asStateFlow()

    /** 接管开关的代次。「恢复默认」与「重新接管」都会递增，让界面重算接管者。 */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** 插件列表，供界面在插件启停后重新判定接管者。 */
    val plugins: StateFlow<List<InstalledPlugin>> = pluginManager.plugins

    /** 找出当前接管某个屏幕的插件；被用户关掉或插件停用时返回 null。 */
    fun candidateFor(target: String): PluginScreen? {
        val active = plugins.value.filter { it.isActive }
        for (plugin in active) {
            val screen = plugin.content.screens.firstOrNull { it.target == target } ?: continue
            if (isDismissed(screen)) continue
            return screen
        }
        return null
    }

    fun pluginNameOf(screen: PluginScreen): String {
        val plugin = plugins.value.firstOrNull { it.id == screen.pluginId }
        return plugin?.manifest?.name?.resolve(LANGUAGE_TAG) ?: screen.title.resolve(LANGUAGE_TAG)
    }

    /** 首次进入某个屏幕时加载一次；已加载过的不重复请求，避免每次切页都拉起插件进程。 */
    fun ensureLoaded(screen: PluginScreen) {
        val existing = _slots.value[screen.key]
        if (existing is PluginScreenSlot.Ready) return
        if (existing is PluginScreenSlot.Loading) return
        load(screen)
    }

    fun reload(screen: PluginScreen) = load(screen)

    /** 用户选择「恢复默认」：本次运行内不再接管该屏幕，并清掉已加载文档。 */
    fun dismiss(screen: PluginScreen) {
        dismissed.value = dismissed.value + screen.key
        _slots.value = _slots.value - screen.key
        _revision.value = _revision.value + 1
    }

    /** 从「恢复默认」恢复接管。 */
    fun restore(screen: PluginScreen) {
        dismissed.value = dismissed.value - screen.key
        _revision.value = _revision.value + 1
        load(screen)
    }

    fun isDismissed(screen: PluginScreen): Boolean = screen.key in dismissed.value

    private fun load(screen: PluginScreen) {
        setSlot(screen.key, PluginScreenSlot.Loading)
        viewModelScope.launch {
            val pluginName = pluginNameOf(screen)
            val result = withContext(Dispatchers.IO) { runCatching { readDocument(screen) } }
            result.fold(
                onSuccess = { parsed ->
                    when (parsed) {
                        is DslParseResult.Ok -> setSlot(
                            screen.key,
                            PluginScreenSlot.Ready(screen, pluginName, parsed.document)
                        )
                        is DslParseResult.Failed -> setSlot(
                            screen.key,
                            PluginScreenSlot.Failed(pluginName, parsed.error.message)
                        )
                    }
                },
                onFailure = { error ->
                    setSlot(
                        screen.key,
                        PluginScreenSlot.Failed(pluginName, error.message ?: "读取界面失败")
                    )
                }
            )
        }
    }

    private suspend fun readDocument(screen: PluginScreen): DslParseResult {
        val file = screen.file
        if (file != null) {
            if (!file.isFile) {
                return DslParseResult.Failed(DslParseError("界面文件不存在：${file.name}"))
            }
            if (file.length() > PluginDslLimits.MAX_DOC_BYTES) {
                return DslParseResult.Failed(DslParseError("界面文件过大"))
            }
            return PluginDslParser.parse(file.readText())
        }
        val handler = screen.handler
            ?: return DslParseResult.Failed(DslParseError("清单里没有声明界面来源"))
        val plugin = plugins.value.firstOrNull { it.id == screen.pluginId }
            ?: return DslParseResult.Failed(DslParseError("插件已停用"))
        val runtime = plugin.content.runtime
            ?: return DslParseResult.Failed(DslParseError("清单声明了 handler 但没有 runtime"))
        val response = runtimeManager.invoke(
            pluginId = screen.pluginId,
            version = plugin.record.version,
            runtime = runtime,
            handler = handler,
            args = buildJsonObject {
                put("target", screen.target)
                put("screen", screen.localId)
            },
            method = "ui.invoke",
            timeoutMs = LOAD_TIMEOUT_MS
        )
        return parseResponse(response)
    }

    /**
     * 交互回传：把节点、事件名、当前值与文档 state 一起发给插件，等它返回新文档。
     *
     * 插件没声明 handler 的静态界面只支持 close / openUrl，发事件直接忽略——
     * 静默忽略比弹错误更合适，作者本就没打算做交互。
     */
    fun dispatch(target: String, action: DslAction, node: DslNode, value: String? = null) {
        val slot = _slots.value.values.firstOrNull { it is PluginScreenSlot.Ready && it.screen.target == target }
            as? PluginScreenSlot.Ready ?: return
        val screen = slot.screen
        val handler = screen.handler ?: return
        val plugin = plugins.value.firstOrNull { it.id == screen.pluginId } ?: return
        val runtime = plugin.content.runtime ?: return

        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    runtimeManager.invoke(
                        pluginId = screen.pluginId,
                        version = plugin.record.version,
                        runtime = runtime,
                        handler = handler,
                        args = buildJsonObject {
                            put("target", screen.target)
                            put("screen", screen.localId)
                            put("action", "event")
                            put("event", action.name.orEmpty())
                            put("nodeId", node.id.orEmpty())
                            if (value != null) put("value", value)
                            put("state", slot.document.state)
                        },
                        method = "ui.invoke",
                        timeoutMs = EVENT_TIMEOUT_MS
                    )
                }
            }
            outcome.onSuccess { response ->
                when (val parsed = parseResponse(response)) {
                    is DslParseResult.Ok -> setSlot(
                        screen.key,
                        PluginScreenSlot.Ready(screen, slot.pluginName, parsed.document)
                    )
                    is DslParseResult.Failed -> setSlot(
                        screen.key,
                        PluginScreenSlot.Failed(slot.pluginName, parsed.error.message)
                    )
                }
            }
        }
    }

    /**
     * 插件应答支持两种形状：`{"root": ..., "state": ...}` 完整文档，
     * 或 `{"document": ...}` 包一层。其余形状视为作者写错，报错而不是静默保留旧界面。
     */
    private fun parseResponse(response: JsonElement): DslParseResult {
        val obj = response as? JsonObject
            ?: return DslParseResult.Failed(DslParseError("插件返回的不是对象"))
        val error = (obj["error"] as? JsonPrimitive)?.contentOrNull
        if (error != null) return DslParseResult.Failed(DslParseError("插件报错：$error"))
        val document = obj["document"] ?: obj
        return PluginDslParser.parse(document.toString())
    }

    private fun setSlot(key: String, slot: PluginScreenSlot) {
        _slots.value = _slots.value + (key to slot)
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 8_000L
        const val EVENT_TIMEOUT_MS = 8_000L

        /** 插件名字按中文优先解析，与宿主其余位置的展示保持一致。 */
        const val LANGUAGE_TAG = "zh-CN"
    }
}
