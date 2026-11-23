package com.nox.feature.plugin.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import com.nox.core.util.FileLogger
import com.nox.feature.plugin.data.index.NoMatchingReleaseException
import com.nox.feature.plugin.data.index.PluginIndexClient
import com.nox.feature.plugin.data.index.PluginIndexDefaults
import com.nox.feature.plugin.data.index.PluginIndexException
import com.nox.feature.plugin.data.index.PluginIndexPlugin
import com.nox.feature.plugin.data.index.PluginIndexSource
import com.nox.feature.plugin.data.index.PluginOfficialRepositories
import com.nox.feature.plugin.data.index.PluginIndexSourceRepository
import com.nox.feature.plugin.domain.PluginError
import com.nox.feature.plugin.domain.PluginInstallOrigin
import com.nox.feature.plugin.domain.PluginManager
import com.nox.feature.plugin.domain.PluginPreview
import com.nox.feature.plugin.domain.PluginPreviewResult
import com.nox.feature.plugin.domain.SemVer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PluginStoreItem(
    val plugin: PluginIndexPlugin,
    val source: PluginIndexSource,
    val installedVersion: String?,
    val updateAvailable: Boolean,
    val compatible: Boolean,
    /** 插件源码仓库是否归属 AiCode 官方账号。 */
    val official: Boolean
)

data class PluginStorePreview(
    val preview: PluginPreview,
    val origin: PluginInstallOrigin,
    val expectedRepository: String?,
    val sourceLabel: String,
    val officialSource: Boolean
)

enum class PluginStoreErrorCode {
    NETWORK,
    INVALID_INDEX,
    NO_COMPATIBLE_RELEASE,
    DOWNLOAD_FAILED,
    PREVIEW_FAILED
}

data class PluginStoreError(
    val code: PluginStoreErrorCode,
    val detail: String? = null,
    val pluginError: PluginError? = null,
    /** 安装/下载错误所属的插件；索引错误为 null。 */
    val pluginId: String? = null
)

/** 安装阶段：先测速选线路，再下载。 */
enum class PluginInstallStage { IDLE, PROBING, DOWNLOADING }

data class PluginStoreUiState(
    /** 首次读取来源前就显示加载态，避免先闪一下空列表。 */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val sources: List<PluginIndexSource> = emptyList(),
    val items: List<PluginStoreItem> = emptyList(),
    /** 每个已安装插件可用的最高版本更新条目；供已安装页展示「可更新」。 */
    val updatesByPluginId: Map<String, PluginStoreItem> = emptyMap(),
    val error: PluginStoreError? = null,
    val installingPluginId: String? = null,
    val installStage: PluginInstallStage = PluginInstallStage.IDLE,
    val preview: PluginStorePreview? = null,
    /** 正在导出（下载）安装包的插件 id，用于按钮转圈。 */
    val exportingPluginId: String? = null,
    /** 导出完成后的分享文件；UI 消费后置空。 */
    val exportShareFile: File? = null
)

@HiltViewModel
class PluginStoreViewModel @Inject constructor(
    @param:ApplicationContext private val context: android.content.Context,
    private val client: PluginIndexClient,
    private val sourceRepository: PluginIndexSourceRepository,
    private val manager: PluginManager
) : ViewModel() {

    private val _state = MutableStateFlow(PluginStoreUiState())
    val state: StateFlow<PluginStoreUiState> = _state.asStateFlow()

    /** 一次加载结束后要提示的失败来源数；仅在用户下拉刷新或确实有来源失败时发送。 */
    private val _refreshResult = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val refreshResult: SharedFlow<Int> = _refreshResult.asSharedFlow()

    /** 来源地址列表变化时才重新拉取；markFetched 等只改时间戳的写入不会触发加载。 */
    private var loadedUrls: List<String>? = null
    private var loadToken = 0

    init {
        viewModelScope.launch {
            sourceRepository.sources.collect { sources ->
                _state.update { it.copy(sources = sources) }
                val urls = sources.map { it.indexUrl }
                if (urls != loadedUrls) {
                    loadedUrls = urls
                    loadAll(force = false)
                }
            }
        }
        viewModelScope.launch {
            manager.plugins.collect { installed ->
                val versions = installed.associate { it.id to it.record.version }
                _state.update { current ->
                    val updatedItems = current.items.map { item ->
                        val version = versions[item.plugin.id]
                        item.copy(
                            installedVersion = version,
                            updateAvailable = isNewer(item.plugin.version, version)
                        )
                    }
                    current.copy(
                        items = updatedItems,
                        updatesByPluginId = updatesOf(updatedItems)
                    )
                }
            }
        }
    }

    fun refresh() {
        loadAll(force = true)
    }

    /** 按来源顺序依次拉取，条目也按来源顺序叠加；单个来源失败时保留它上次的条目。 */
    private fun loadAll(force: Boolean) {
        val sources = _state.value.sources
        if (sources.isEmpty()) return
        val token = ++loadToken
        _state.update { it.copy(loading = true, refreshing = it.items.isNotEmpty(), error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val installed = manager.plugins.value.associate { it.id to it.record.version }
            val previous = _state.value.items.groupBy { it.source.id }
            val items = mutableListOf<PluginStoreItem>()
            var firstError: PluginStoreError? = null
            var failureCount = 0
            sources.forEach { source ->
                val result = runCatching { client.fetchIndex(source, forceRefresh = force) }
                    .recoverCatching { e ->
                        // GitHub 仓库页面被规范成 main 分支的索引；默认分支是 master 时回退再试一次。
                        val fallback = PluginIndexDefaults.fallbackIndexUrl(source.indexUrl) ?: throw e
                        client.fetchIndex(source.copy(indexUrl = fallback), forceRefresh = true)
                    }
                if (token != loadToken) return@launch
                result.onSuccess { snapshot ->
                    items += snapshot.index.plugins.map { plugin ->
                        val installedVersion = installed[plugin.id]
                        PluginStoreItem(
                            plugin = plugin,
                            source = source,
                            installedVersion = installedVersion,
                            updateAvailable = isNewer(plugin.version, installedVersion),
                            compatible = client.isCompatible(plugin),
                            official = PluginOfficialRepositories.isOfficial(plugin.repository)
                        )
                    }
                    if (!snapshot.stale) {
                        sourceRepository.markFetched(source.id, snapshot.fetchedAt)
                    }
                }.onFailure { e ->
                    FileLogger.w(TAG, "读取插件索引失败: ${source.id}", e)
                    failureCount++
                    if (firstError == null) {
                        firstError = PluginStoreError(
                            code = if (e is PluginIndexException) {
                                PluginStoreErrorCode.INVALID_INDEX
                            } else {
                                PluginStoreErrorCode.NETWORK
                            },
                            detail = e.message
                        )
                    }
                    items += previous[source.id].orEmpty()
                }
            }
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    items = dedupeItems(items),
                    updatesByPluginId = updatesOf(items),
                    error = firstError
                )
            }
            if (force || failureCount > 0) {
                _refreshResult.tryEmit(failureCount)
            }
        }
    }

    fun install(item: PluginStoreItem) {
        // 静默 return 会让用户以为「点了没反应」，这里给出可见的错误。
        if (!item.compatible) {
            _state.update {
                it.copy(
                    error = PluginStoreError(
                        code = PluginStoreErrorCode.NO_COMPATIBLE_RELEASE,
                        pluginId = item.plugin.id
                    )
                )
            }
            return
        }
        if (_state.value.installingPluginId != null) return
        _state.update {
            it.copy(
                installingPluginId = item.plugin.id,
                installStage = PluginInstallStage.PROBING,
                error = null
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val asset = client.resolveRelease(item.plugin)
                // 先把候选线路测出来，界面上能看到「正在检测线路」这一段。
                val candidates = client.rankCandidates(asset)
                _state.update { it.copy(installStage = PluginInstallStage.DOWNLOADING) }
                val packageFile = client.download(asset, candidates)
                val result = packageFile.file.inputStream().use { manager.preview(it) }
                when (result) {
                    is PluginPreviewResult.Ready -> {
                        val preview = result.preview
                        val manifestVersion = preview.manifest.version.toString()
                        // repository 与 publisher 都不参与比对：
                        // 商店把包重新托管到自己的仓库后地址本就不同；
                        // 索引里的 author 为了兼容旧客户端会转成 ASCII，与包内清单写的中文
                        // publisher 不可能逐字相等。id 与 version 足以确认是同一个包。
                        if (preview.manifest.id != item.plugin.id ||
                            asset.version != manifestVersion
                        ) {
                            manager.discard(preview.token)
                            _state.update {
                                it.copy(
                                    installingPluginId = null,
                                    installStage = PluginInstallStage.IDLE,
                                    error = PluginStoreError(
                                        code = PluginStoreErrorCode.PREVIEW_FAILED,
                                        pluginId = item.plugin.id
                                    )
                                )
                            }
                            return@launch
                        }
                        // 商店自己的索引已经托管并审计过包，不再要求使用者手抄仓库地址；
                        // 只有来自第三方索引源的插件才走「确认来源」流程。
                        val official = item.source.official || item.official
                        _state.update {
                            it.copy(
                                installingPluginId = null,
                                installStage = PluginInstallStage.IDLE,
                                preview = PluginStorePreview(
                                    preview = preview,
                                    origin = PluginInstallOrigin(
                                        kind = PluginInstallOrigin.KIND_INDEX,
                                        sourceId = item.source.id,
                                        sourceName = item.source.name,
                                        indexUrl = item.source.indexUrl,
                                        repository = item.plugin.repository,
                                        official = official
                                    ),
                                    // 索引里已给出仓库地址并随来源一起记录，不再要求使用者手抄一遍。
                                    expectedRepository = null,
                                    sourceLabel = item.source.name,
                                    officialSource = item.source.official
                                )
                            )
                        }
                    }
                    is PluginPreviewResult.Failed -> _state.update {
                        it.copy(
                            installingPluginId = null,
                            installStage = PluginInstallStage.IDLE,
                            error = PluginStoreError(
                                code = PluginStoreErrorCode.PREVIEW_FAILED,
                                pluginError = result.errors.firstOrNull(),
                                pluginId = item.plugin.id
                            )
                        )
                    }
                }
            } catch (e: NoMatchingReleaseException) {
                // 这条最常见：索引里的 version 与 Release 资产名对不上
                // （资产名约定是 {id}_{version}.zip，上游改了命名或版本号写法就会失配）。
                FileLogger.w(TAG, "未找到可安装版本: ${item.plugin.id} v${item.plugin.version}", e)
                _state.update {
                    it.copy(
                        installingPluginId = null,
                        installStage = PluginInstallStage.IDLE,
                        error = PluginStoreError(
                            code = PluginStoreErrorCode.NO_COMPATIBLE_RELEASE,
                            detail = e.message,
                            pluginId = item.plugin.id
                        )
                    )
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "下载插件失败: ${item.plugin.id}", e)
                _state.update {
                    it.copy(
                        installingPluginId = null,
                        installStage = PluginInstallStage.IDLE,
                        error = PluginStoreError(
                            code = PluginStoreErrorCode.DOWNLOAD_FAILED,
                            detail = e.message,
                            pluginId = item.plugin.id
                        )
                    )
                }
            }
        }
    }

    fun consumePreview() {
        _state.update { it.copy(preview = null) }
    }

    /** 分享文件已被 UI 消费（拉起分享面板），清空避免重复触发。 */
    fun consumeExportShareFile() {
        _state.update { it.copy(exportShareFile = null) }
    }

    /**
     * 从商店下载安装包到本地，供导出/分享。
     *
     * 不复用 install 的暂存管线：那条路会解包并落盘成已安装状态。这里只要原始 zip，
     * 所以下载后直接拷到 cacheDir 并清掉临时文件。
     */
    fun exportPackage(item: PluginStoreItem, share: Boolean) {
        if (!item.compatible) return
        if (_state.value.exportingPluginId != null) return
        _state.update { it.copy(exportingPluginId = item.plugin.id, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val downloaded = runCatching {
                val asset = client.resolveRelease(item.plugin)
                val candidates = client.rankCandidates(asset)
                client.download(asset, candidates)
            }.getOrNull()
            if (downloaded == null) {
                _state.update {
                    it.copy(
                        exportingPluginId = null,
                        error = PluginStoreError(
                            code = PluginStoreErrorCode.NETWORK,
                            pluginId = item.plugin.id
                        )
                    )
                }
                return@launch
            }
            val cached = runCatching {
                val safeId = item.plugin.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val target = File(context.cacheDir, "plugin-export/$safeId-${downloaded.asset.version}.zip")
                target.parentFile?.mkdirs()
                downloaded.file.copyTo(target, overwrite = true)
                target
            }.getOrNull()
            // 下载的临时文件用完就删，避免 cache 里留两份。
            runCatching { downloaded.file.delete() }
            _state.update {
                it.copy(
                    exportingPluginId = null,
                    exportShareFile = if (share) cached else null,
                    error = if (cached == null) {
                        PluginStoreError(PluginStoreErrorCode.NETWORK, pluginId = item.plugin.id)
                    } else {
                        it.error
                    }
                )
            }
        }
    }

    private fun isNewer(candidate: String?, installed: String?): Boolean {
        if (candidate == null || installed == null) return false
        val left = SemVer.parse(candidate) ?: return false
        val right = SemVer.parse(installed) ?: return false
        return left > right
    }

    /** 多个来源可能含同一插件：按 id 合并成一条，取版本最高者（同版本取来源顺序靠前的）。 */
    private fun dedupeItems(items: List<PluginStoreItem>): List<PluginStoreItem> =
        items.groupBy { it.plugin.id }
            .map { (_, group) ->
                group.maxByOrNull { item ->
                    item.plugin.version?.let(SemVer::parse) ?: SemVer(0, 0, 0)
                }!!
            }

    /** 每个插件取一个可用的最高版本，同一插件出现在多个来源时只提示一次。 */
    private fun updatesOf(items: List<PluginStoreItem>): Map<String, PluginStoreItem> =
        items.asSequence()
            .filter { it.updateAvailable && it.compatible }
            .groupBy { it.plugin.id }
            .mapValues { (_, candidates) ->
                candidates.maxByOrNull { item ->
                    item.plugin.version?.let(SemVer::parse) ?: SemVer(0, 0, 0)
                }!!
            }

    private companion object {
        const val TAG = "PluginStoreViewModel"
    }
}
