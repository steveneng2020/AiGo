package com.nox.feature.plugin.tool

import com.nox.core.util.FileLogger
import com.nox.feature.agent.domain.tool.AgentTool
import com.nox.feature.agent.domain.tool.ParameterType
import com.nox.feature.agent.domain.tool.ToolCapability
import com.nox.feature.agent.domain.tool.ToolParameter
import com.nox.feature.agent.domain.tool.ToolResult
import com.nox.feature.plugin.domain.InstalledPlugin
import com.nox.feature.plugin.domain.PluginInstallOrigin
import com.nox.feature.plugin.domain.PluginInstallResult
import com.nox.feature.plugin.domain.PluginInstallService
import com.nox.feature.plugin.domain.PluginManager
import com.nox.feature.plugin.overlay.PluginOverlayManager
import com.nox.feature.plugin.runtime.PluginProviderRegistrar
import com.nox.feature.plugin.runtime.PluginSecretStore
import com.nox.feature.plugin.runtime.PluginTerminalRunner
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 让 AI 自己管理插件：列出、安装、启停、卸载、热重载、看日志。
 *
 * 开发目录 `~/.nox/plugin-dev/<dir>` 与工作区同处一个容器，AI 写完代码调 `reload`
 * 就能把新工具装进自己身上，不必等人去界面点。
 */
class PluginTool @Inject constructor(
    private val pluginManager: PluginManager,
    // 安装服务依赖 hook 派发器，后者最终又依赖 ToolRegistry；只在本工具被调用时才取，
    // 避免 Dagger 在构建图阶段形成 ToolRegistry → PluginTool → … → ToolRegistry 的环。
    private val installService: Provider<PluginInstallService>,
    private val providerRegistrar: Provider<PluginProviderRegistrar>,
    private val terminalRunner: Provider<PluginTerminalRunner>,
    private val overlayManager: Provider<PluginOverlayManager>,
    private val secretStore: Provider<PluginSecretStore>
) : AgentTool() {

    override val name = "plugin"

    override val description = """
        管理本机插件。插件可以给 AI 增加工具、技能、子代理、工作区工具、MCP server 与模型供应商。

        - list：列出已安装插件及其贡献（不传 plugin_id）
        - install：安装插件包。source=dev 按目录名装开发目录里的项目；source=zip 按路径装本地插件包
        - reload：重新打包并热替换开发目录里的项目，改完代码立刻生效
        - enable / disable：启用或停用；uninstall：卸载（同时删除插件数据目录）
        - logs：读取该插件最近的运行日志，排查启动失败

        开发目录在容器内 ~/.nox/plugin-dev/<目录名>，用 writeFile 写完代码直接 reload 即可。
    """.trimIndent()

    override val capabilities = setOf(ToolCapability.MODIFY_AGENT_CONFIG)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return when (args.string("action")) {
            "list", "logs" -> setOf(ToolCapability.READ_AGENT_CONFIG)
            else -> capabilities
        }
    }

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作类型",
            enum = listOf("list", "install", "reload", "enable", "disable", "uninstall", "logs")
        ),
        "plugin_id" to ToolParameter(
            name = "plugin_id",
            type = ParameterType.STRING,
            description = "插件 ID（list 之外的动作必填）",
            required = false
        ),
        "source" to ToolParameter(
            name = "source",
            type = ParameterType.STRING,
            description = "install 的来源：dev=开发目录里的项目，zip=本地插件包文件",
            required = false,
            enum = listOf("dev", "zip")
        ),
        "path" to ToolParameter(
            name = "path",
            type = ParameterType.STRING,
            description = "install 时的目标：source=dev 填开发目录名，source=zip 填插件包路径",
            required = false
        ),
        "lines" to ToolParameter(
            name = "lines",
            type = ParameterType.INTEGER,
            description = "logs 返回的行数，默认 200，上限 2000",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return when (val action = args.string("action")) {
            "list" -> list()
            "install" -> install(args.string("source"), args.string("path"))
            "reload" -> reload(args.string("plugin_id"))
            "enable" -> setEnabled(args.string("plugin_id"), true)
            "disable" -> setEnabled(args.string("plugin_id"), false)
            "uninstall" -> uninstall(args.string("plugin_id"))
            "logs" -> logs(args.string("plugin_id"), args.int("lines") ?: 200)
            else -> ToolResult.Error("未知 action：$action", "INVALID_ARGUMENT")
        }
    }

    private fun list(): ToolResult {
        pluginManager.ensureLoaded()
        val languageTag = Locale.getDefault().toLanguageTag()
        val tools = buildJsonObject {
            put("devDir", pluginManager.devDir.path)
            put("plugins", JsonArray(pluginManager.plugins.value.map { it.describe(languageTag) }))
        }
        return ToolResult.Success(tools)
    }

    private fun InstalledPlugin.describe(languageTag: String): JsonElement = buildJsonObject {
        put("id", id)
        put("version", record.version)
        put("enabled", record.enabled)
        put("active", isActive)
        put("status", status.name)
        put("name", manifest?.name?.resolve(languageTag) ?: id)
        put("origin", record.origin)
        record.repository?.let { put("repository", it) }
        put("tools", JsonArray(content.tools.map { JsonPrimitive(it.localId) }))
        put("skills", JsonArray(content.skills.map { JsonPrimitive(it.localId) }))
        put("agents", JsonArray(content.agents.map { JsonPrimitive(it.localId) }))
        put("workspaceTools", JsonArray(content.workspaceTools.map { JsonPrimitive(it.localId) }))
        put("providers", JsonArray(content.providers.map { JsonPrimitive(it.localId) }))
        put("hasRuntime", content.runtime != null)
        if (errors.isNotEmpty()) {
            put("errors", JsonArray(errors.map { JsonPrimitive(it.code.name) }))
        }
    }

    private suspend fun install(source: String?, path: String?): ToolResult {
        if (path.isNullOrBlank()) {
            return ToolResult.Error("缺少必需参数：path", "MISSING_ARGUMENT")
        }
        val result = when (source ?: "dev") {
            "dev" -> installService.get().installDevProject(path.trim())
            "zip" -> installService.get().installArchive(File(path.trim()))
            else -> return ToolResult.Error("未知 source：$source（只接受 dev 或 zip）", "INVALID_ARGUMENT")
        }
        return installed(result)
    }

    /** reload 只针对开发目录：从目录名反查插件 ID，再重新打包替换。 */
    private suspend fun reload(pluginId: String?): ToolResult {
        if (pluginId.isNullOrBlank()) {
            return ToolResult.Error("缺少必需参数：plugin_id", "MISSING_ARGUMENT")
        }
        val target = pluginManager.plugins.value.firstOrNull { it.id == pluginId }
            ?: return ToolResult.Error("未安装插件「$pluginId」", "PLUGIN_NOT_FOUND")
        val dirName = devDirName(target) ?: run {
            // 不是从开发目录装的：看 devDir 里是否有同名清单对应的目录。
            findDevDirByPluginId(pluginId)
        } ?: return ToolResult.Error(
            "开发目录里没有「$pluginId」对应的项目。用 list 看 devDir，或改用 install(source=dev)。",
            "DEV_DIR_NOT_FOUND"
        )
        return installed(installService.get().installDevProject(dirName))
    }

    private fun devDirName(plugin: InstalledPlugin): String? {
        val devDir = pluginManager.devDir
        return devDir.listFiles()
            ?.filter { it.isDirectory }
            ?.firstOrNull { it.name == plugin.id }
            ?.name
    }

    private suspend fun findDevDirByPluginId(pluginId: String): String? {
        val devDir = pluginManager.devDir
        val candidates = devDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (dir in candidates) {
            val manifest = File(dir, "plugin.json")
            if (!manifest.isFile) continue
            val text = runCatching { manifest.readText() }.getOrNull() ?: continue
            if (text.contains("\"$pluginId\"")) return dir.name
        }
        return null
    }

    private suspend fun setEnabled(pluginId: String?, enabled: Boolean): ToolResult {
        if (pluginId.isNullOrBlank()) {
            return ToolResult.Error("缺少必需参数：plugin_id", "MISSING_ARGUMENT")
        }
        if (pluginManager.plugins.value.none { it.id == pluginId }) {
            return ToolResult.Error("未安装插件「$pluginId」", "PLUGIN_NOT_FOUND")
        }
        if (!pluginManager.setEnabled(pluginId, enabled)) {
            return ToolResult.Error("切换启停状态失败：$pluginId", "UPDATE_FAILED")
        }
        if (enabled) {
            runCatching { providerRegistrar.get().sync(pluginId) }
        } else {
            runCatching { providerRegistrar.get().sync(pluginId) }
            terminalRunner.get().closePlugin(pluginId)
            overlayManager.get().closePlugin(pluginId)
        }
        return ToolResult.Success(
            buildJsonObject {
                put("id", pluginId)
                put("enabled", enabled)
            }
        )
    }

    private suspend fun uninstall(pluginId: String?): ToolResult {
        if (pluginId.isNullOrBlank()) {
            return ToolResult.Error("缺少必需参数：plugin_id", "MISSING_ARGUMENT")
        }
        if (pluginManager.plugins.value.none { it.id == pluginId }) {
            return ToolResult.Error("未安装插件「$pluginId」", "PLUGIN_NOT_FOUND")
        }
        terminalRunner.get().closePlugin(pluginId)
        overlayManager.get().closePlugin(pluginId)
        val removed = pluginManager.uninstall(pluginId)
        if (removed) {
            runCatching { providerRegistrar.get().sync(pluginId) }
            // 与界面卸载保持一致：密钥目录不在 plugins/ 下，需要单独清。
            runCatching { secretStore.get().clear(pluginId) }
        }
        return ToolResult.Success(
            buildJsonObject {
                put("id", pluginId)
                put("uninstalled", removed)
            }
        )
    }

    /** 从按天日志里筛出该插件进程的痕迹。插件进程日志统一带 `[pluginId]` 前缀。 */
    private fun logs(pluginId: String?, lines: Int): ToolResult {
        if (pluginId.isNullOrBlank()) {
            return ToolResult.Error("缺少必需参数：plugin_id", "MISSING_ARGUMENT")
        }
        val dir = FileLogger.logDirectory()
            ?: return ToolResult.Error("日志目录尚未初始化", "LOG_UNAVAILABLE")
        val limit = lines.coerceIn(1, MAX_LOG_LINES)
        val marker = "[$pluginId]"
        val matched = dir.listFiles { f -> f.isFile && f.name.startsWith("log-") }
            .orEmpty()
            .sortedByDescending { it.name }
            .firstNotNullOfOrNull { file ->
                val hits = runCatching {
                    file.useLines { seq -> seq.filter { it.contains(marker) }.toList() }
                }.getOrDefault(emptyList())
                if (hits.isEmpty()) null else hits.takeLast(limit) to file.name
            }
        if (matched == null) {
            return ToolResult.Success(
                buildJsonObject {
                    put("id", pluginId)
                    put("lines", JsonArray(emptyList()))
                    put("note", "日志里还没有该插件的记录，插件可能尚未启动过")
                }
            )
        }
        val (hits, fileName) = matched
        return ToolResult.Success(
            buildJsonObject {
                put("id", pluginId)
                put("file", fileName)
                put("lines", JsonArray(hits.map { JsonPrimitive(it) }))
            }
        )
    }

    private suspend fun installed(result: PluginInstallResult): ToolResult = when (result) {
        is PluginInstallResult.Installed -> {
            val plugin = pluginManager.plugins.value.firstOrNull { it.id == result.pluginId }
            val languageTag = Locale.getDefault().toLanguageTag()
            ToolResult.Success(
                buildJsonObject {
                    put("installed", true)
                    put("id", result.pluginId)
                    plugin?.let { put("plugin", it.describe(languageTag)) }
                    put(
                        "note",
                        "新增的工具/技能已注册，本回合内即可使用；技能正文用 loadSkill 读取。"
                    )
                }
            )
        }
        is PluginInstallResult.Failed -> ToolResult.Error(
            "安装失败：${result.error.code.name}" +
                (result.error.detail?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""),
            result.error.code.name
        )
    }

    private fun Map<String, JsonElement>.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<String, JsonElement>.int(key: String): Int? =
        this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private companion object {
        const val MAX_LOG_LINES = 2000
    }
}
