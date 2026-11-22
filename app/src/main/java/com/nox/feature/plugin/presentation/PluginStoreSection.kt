package com.nox.feature.plugin.presentation

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.produceState
import com.nox.feature.agent.presentation.component.RemoteImageCache
import com.nox.feature.plugin.domain.PluginIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nox.R
import com.nox.core.theme.Spacing
import com.nox.core.theme.semanticColors
import com.nox.feature.settings.presentation.component.LazyGroupItem
import com.nox.feature.settings.presentation.component.McpPill
import com.nox.feature.settings.presentation.component.ModelSearchField
import com.nox.feature.settings.presentation.component.SettingsDivider
import com.nox.feature.settings.presentation.component.SettingsEmptyState
import com.nox.feature.settings.presentation.component.SettingsGroup
import com.nox.feature.settings.presentation.component.SettingsGroupHeader
import com.nox.feature.settings.presentation.component.SettingsRow
import com.nox.feature.settings.presentation.component.openUrl
import compose.icons.FeatherIcons
import compose.icons.feathericons.Book
import compose.icons.feathericons.Download
import compose.icons.feathericons.Share2
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.CloudOff
import compose.icons.feathericons.Github
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Package
import compose.icons.feathericons.Server
import compose.icons.feathericons.ShoppingBag
import compose.icons.feathericons.Tag

/** 非官方徽标固定用黄色，与主题主色区分开。 */
private val UnofficialBadgeBackground = Color(0xFFFFE082)
private val UnofficialBadgeText = Color(0xFF5C4300)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginStoreSection(
    onShowPreview: (PluginStorePreview) -> Unit,
    onOpenDetail: (PluginStoreItem) -> Unit,
    viewModel: PluginStoreViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val languageTag = currentLanguageTag()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 长按商店里的插件条目：弹导出/分享菜单（不必先进详情页）
    var menuItem by remember { mutableStateOf<PluginStoreItem?>(null) }

    LaunchedEffect(state.preview) {
        state.preview?.let { preview ->
            onShowPreview(preview)
            viewModel.consumePreview()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.refreshResult.collect { failed ->
            val message = if (failed > 0) {
                context.getString(R.string.plugins_store_refresh_done_failed, failed)
            } else {
                context.getString(R.string.plugins_store_refresh_done)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val filteredItems = remember(state.items, searchQuery, languageTag) {
        val query = searchQuery.trim().lowercase()
        if (query.isEmpty()) {
            state.items
        } else {
            state.items.filter { item ->
                item.plugin.name.resolve(languageTag).lowercase().contains(query) ||
                    item.plugin.id.lowercase().contains(query) ||
                    item.plugin.author.lowercase().contains(query) ||
                    item.plugin.description.resolve(languageTag).lowercase().contains(query)
            }
        }
    }
    // 安装/下载错误归属具体插件，只在该插件详情页展示；整页错误只处理索引错误。
    val indexError = state.error?.takeIf { it.pluginId == null }

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            when {
                state.loading && state.items.isEmpty() -> item(key = "loading", contentType = "loading") {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                }
                indexError != null && state.items.isEmpty() -> item(key = "error", contentType = "empty") {
                    StoreErrorState(
                        error = indexError,
                        onRetry = viewModel::refresh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = 48.dp)
                    )
                }
                state.items.isEmpty() -> item(key = "empty", contentType = "empty") {
                    SettingsEmptyState(
                        icon = FeatherIcons.ShoppingBag,
                        title = stringResource(R.string.plugins_store_empty_title),
                        hint = stringResource(R.string.plugins_store_empty_hint),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = 48.dp)
                    )
                }
                else -> {
                    item(key = "total", contentType = "total") {
                        Text(
                            text = if (searchQuery.isBlank()) {
                                stringResource(R.string.plugins_store_total, state.items.size)
                            } else {
                                stringResource(
                                    R.string.plugins_store_total_filtered,
                                    state.items.size,
                                    filteredItems.size
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = Spacing.xs,
                                bottom = Spacing.xs
                            )
                        )
                    }
                    if (state.items.size >= 3) {
                        item(key = "search", contentType = "search") {
                            ModelSearchField(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                placeholder = stringResource(R.string.plugins_search_placeholder),
                                modifier = Modifier.padding(bottom = Spacing.xs)
                            )
                        }
                    }
                    if (filteredItems.isEmpty()) {
                        item(key = "search-empty", contentType = "empty") {
                            PluginSearchEmptyState()
                        }
                    } else {
                        itemsIndexed(
                            items = filteredItems,
                            key = { _, item -> "${item.source.id}:${item.plugin.id}" },
                            contentType = { _, _ -> "store-row" }
                        ) { index, item ->
                            LazyGroupItem(
                                index = index,
                                lastIndex = filteredItems.lastIndex,
                                individual = true
                            ) {
                                StorePluginRow(
                                    item = item,
                                    languageTag = languageTag,
                                    onClick = { onOpenDetail(item) },
                                    onLongClick = { menuItem = item }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 长按商店条目：直接下载并分享，不必先进详情页再找按钮。
    menuItem?.let { item ->
        com.nox.core.ui.AdaptiveModalBottomSheet(
            onDismissRequest = { menuItem = null },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.xl)
            ) {
                Text(
                    text = item.plugin.name.resolve(languageTag),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = Spacing.lg)
                        .padding(bottom = Spacing.md)
                )
                StoreSheetActionRow(
                    icon = FeatherIcons.Download,
                    label = stringResource(R.string.plugin_menu_export),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        menuItem = null
                        viewModel.exportPackage(item, share = false)
                    }
                )
                StoreSheetActionRow(
                    icon = FeatherIcons.Share2,
                    label = stringResource(R.string.plugin_menu_share),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        menuItem = null
                        viewModel.exportPackage(item, share = true)
                    }
                )
            }
        }
    }
}

/** 商店页操作菜单的单行：图标 + 文字。 */
@Composable
private fun StoreSheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.md))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun pluginVersionText(version: String?): String =
    version?.let { "v$it" } ?: stringResource(R.string.plugins_store_version_latest)

/**
 * 商店里的插件图标槽：索引带 logo 时加载远端图，否则退回内置图标。
 *
 * 索引的 logo 是可选字段，第三方源可能没填；此时按 id 到已安装插件里找本地图标，
 * 装上过的插件至少还能显示自己的图标，不会统一变成方盒子。
 */
@Composable
private fun StorePluginIcon(item: PluginStoreItem, slotSize: Dp, iconSize: Dp) {
    val context = LocalContext.current
    val installed = rememberInstalledPluginIcon(item.plugin.id)
    val logo = item.plugin.logo
    val remote by produceState<ImageBitmap?>(initialValue = null, logo) {
        value = if (logo != null && RemoteImageCache.isRemote(logo)) {
            withContext(Dispatchers.IO) {
                RemoteImageCache.decode(context, logo, STORE_ICON_MAX_EDGE, STORE_ICON_MAX_PIXELS)
            }
        } else {
            null
        }
    }
    Box(
        modifier = Modifier
            .size(slotSize)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            remote != null -> Image(
                bitmap = remote!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
            )
            // 已安装的插件有本地图标文件，比自己声明的内置图标名更准确。
            installed != null -> PluginIconContent(
                icon = installed,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                iconSize = iconSize,
                shape = RoundedCornerShape(10.dp)
            )
            // 索引带内置图标名的直接映射成 Feather 图标；
            // 之前这里一律画方盒子，一个列表看下来全是同一个图标。
            item.plugin.icon != null -> Icon(
                imageVector = resolvePluginBuiltinIcon(item.plugin.icon!!),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(iconSize)
            )
            else -> Icon(
                imageVector = FeatherIcons.Package,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/** 已安装插件里同 id 的图标；商店列表要显示「装上过的插件」自己的图标时用。 */
@Composable
private fun rememberInstalledPluginIcon(pluginId: String): PluginIcon? {
    val viewModel: PluginsViewModel = hiltViewModel()
    val installed by viewModel.plugins.collectAsStateWithLifecycle()
    return remember(installed, pluginId) {
        installed.firstOrNull { it.id == pluginId }?.content?.icon
    }
}

/** 商店图标解码尺寸：列表 40dp、详情 48dp，3 倍屏 144px 够用。 */
private const val STORE_ICON_MAX_EDGE = 160
private const val STORE_ICON_MAX_PIXELS = 160L * 160L

/** 商店列表与已安装列表同版式：图标 + 名称 + 描述；商店不需要开关，点击进入独立详情页。 */
@Composable
private fun StorePluginRow(
    item: PluginStoreItem,
    languageTag: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = Spacing.lg, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StorePluginIcon(item = item, slotSize = 40.dp, iconSize = 22.dp)

        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.plugin.name.resolve(languageTag),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (item.installedVersion != null && !item.updateAvailable) {
                    Spacer(modifier = Modifier.width(6.dp))
                    McpPill(
                        text = stringResource(R.string.plugins_store_badge_installed),
                        textColor = MaterialTheme.colorScheme.tertiary,
                        backgroundColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                    )
                }
                if (item.updateAvailable) {
                    Spacer(modifier = Modifier.width(6.dp))
                    McpPill(
                        text = stringResource(R.string.plugins_store_badge_update),
                        textColor = MaterialTheme.colorScheme.primary,
                        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    )
                }
                if (!item.source.official) {
                    Spacer(modifier = Modifier.width(6.dp))
                    McpPill(
                        text = stringResource(R.string.plugins_store_badge_third_party),
                        textColor = MaterialTheme.colorScheme.secondary,
                        backgroundColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                    )
                }
                if (!item.official) {
                    Spacer(modifier = Modifier.width(6.dp))
                    McpPill(
                        text = stringResource(R.string.plugins_store_badge_unofficial),
                        textColor = UnofficialBadgeText,
                        backgroundColor = UnofficialBadgeBackground
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.plugin.description.resolve(languageTag),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.width(Spacing.sm))

        Icon(
            imageVector = FeatherIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
internal fun PluginStoreDetailSection(
    item: PluginStoreItem,
    onShowPreview: (PluginStorePreview) -> Unit,
    viewModel: PluginStoreViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val latest = state.items.firstOrNull {
        it.source.id == item.source.id && it.plugin.id == item.plugin.id
    } ?: item
    val context = LocalContext.current
    val languageTag = currentLanguageTag()
    val installing = state.installingPluginId == latest.plugin.id

    LaunchedEffect(state.preview) {
        // 预览是全局单槽状态；只弹出当前插件的预览，避免 A 的安装结果覆盖到 B 的详情页。
        val preview = state.preview?.takeIf { it.preview.manifest.id == latest.plugin.id }
            ?: return@LaunchedEffect
        onShowPreview(preview)
        viewModel.consumePreview()
    }

    // 商店页导出：下载完直接拉系统分享面板（可选微信/QQ 等）。
    LaunchedEffect(state.exportShareFile) {
        val file = state.exportShareFile ?: return@LaunchedEffect
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(
                    intent,
                    context.getString(R.string.plugins_share_package)
                )
            )
        }
        viewModel.consumeExportShareFile()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.md, bottom = Spacing.xl)
    ) {
        StoreHeaderCard(item = latest, languageTag = languageTag)

        SettingsGroupHeader(text = stringResource(R.string.plugins_section_info))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Tag,
                title = stringResource(R.string.plugins_store_detail_version),
                trailing = { StoreInfoValue(pluginVersionText(latest.plugin.version)) }
            )
            if (latest.plugin.tags.isNotEmpty()) {
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Package,
                    title = stringResource(R.string.plugins_store_detail_tags),
                    trailing = { StoreInfoValue(latest.plugin.tags.joinToString(" · ")) }
                )
            }
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Server,
                title = stringResource(R.string.plugins_store_detail_index_source),
                trailing = { StoreInfoValue(latest.source.name) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Github,
                title = stringResource(R.string.plugins_store_detail_source),
                onClick = { openUrl(context, latest.plugin.repository) }
            )
            latest.plugin.license?.let { license ->
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Book,
                    title = stringResource(R.string.plugins_store_detail_license),
                    trailing = { StoreInfoValue(license) }
                )
            }
            latest.plugin.homepage?.let { homepage ->
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Globe,
                    title = stringResource(R.string.plugins_store_detail_homepage),
                    onClick = { openUrl(context, homepage) }
                )
            }
        }

        Button(
            onClick = { viewModel.install(latest) },
            enabled = latest.compatible && !installing &&
                (latest.installedVersion == null || latest.updateAvailable),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.lg)
        ) {
            if (installing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
            }
            Text(
                when {
                    installing -> stringResource(
                        when (state.installStage) {
                            com.nox.feature.plugin.presentation.PluginInstallStage.PROBING ->
                                R.string.plugins_probing
                            com.nox.feature.plugin.presentation.PluginInstallStage.DOWNLOADING ->
                                R.string.plugins_downloading
                            else -> R.string.plugins_installing
                        }
                    )
                    !latest.compatible -> latest.plugin.minAppVersion?.let { minimum ->
                        stringResource(R.string.plugins_store_requires_min_version, minimum)
                    } ?: stringResource(R.string.plugins_store_incompatible)
                    latest.installedVersion != null && !latest.updateAvailable ->
                        stringResource(R.string.plugins_store_installed)
                    latest.updateAvailable -> stringResource(R.string.plugins_store_update)
                    else -> stringResource(R.string.plugins_store_install)
                }
            )
        }
        // 版本要求只作提示：插件实际能否运行由安装时的清单校验决定，
        // 作者写了个偏保守的 min_app_version 不该直接把安装按钮禁掉。
        latest.plugin.minAppVersion?.takeIf { !latest.compatible }?.let { minimum ->
            Text(
                text = stringResource(R.string.plugins_store_min_version_notice, minimum),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xs, end = Spacing.xs)
            )
        }
        // 从商店直接拿安装包分享出去（不必先安装），装好再导出则由长按菜单提供。
        OutlinedButton(
            onClick = { viewModel.exportPackage(latest, share = true) },
            enabled = latest.compatible && state.exportingPluginId == null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.sm)
        ) {
            if (state.exportingPluginId == latest.plugin.id) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
            }
            Text(stringResource(R.string.plugins_store_export_share))
        }
        val installError = state.error?.takeIf {
            it.pluginId == latest.plugin.id &&
                (it.code == PluginStoreErrorCode.DOWNLOAD_FAILED ||
                    it.code == PluginStoreErrorCode.NO_COMPATIBLE_RELEASE ||
                    it.code == PluginStoreErrorCode.PREVIEW_FAILED)
        }
        if (installError != null) {
            Text(
                text = buildString {
                    append(storeErrorMessage(installError))
                    // 通用文案看不出是节点全挂还是包地址有问题，把具体原因一起显示。
                    installError.detail?.takeIf { it.isNotBlank() }?.let {
                        append("\n")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xs, end = Spacing.xs)
            )
        }
    }
}

@Composable
private fun StoreHeaderCard(item: PluginStoreItem, languageTag: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.semanticColors.cardSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StorePluginIcon(item = item, slotSize = 48.dp, iconSize = 26.dp)
            Spacer(modifier = Modifier.width(Spacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.plugin.name.resolve(languageTag),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!item.official) {
                        Spacer(modifier = Modifier.width(6.dp))
                        McpPill(
                            text = stringResource(R.string.plugins_store_badge_unofficial),
                            textColor = UnofficialBadgeText,
                            backgroundColor = UnofficialBadgeBackground
                        )
                    }
                }
                Text(
                    text = item.plugin.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val description = item.plugin.description.resolve(languageTag)
                if (description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun StoreInfoValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.semanticColors.subtleText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 190.dp)
    )
}

/** 索引读取失败时的整页占位，与商店空态用同一套版式。 */
@Composable
private fun StoreErrorState(
    error: PluginStoreError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsEmptyState(
        icon = FeatherIcons.CloudOff,
        title = storeErrorMessage(error),
        hint = error.detail?.takeIf { it.isNotBlank() },
        actions = {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.plugins_store_retry))
            }
        },
        modifier = modifier
    )
}

@Composable
private fun storeErrorMessage(error: PluginStoreError): String = when (error.code) {
    PluginStoreErrorCode.NETWORK -> stringResource(R.string.plugins_store_error_network)
    PluginStoreErrorCode.INVALID_INDEX -> stringResource(R.string.plugins_store_error_invalid)
    PluginStoreErrorCode.NO_COMPATIBLE_RELEASE -> stringResource(R.string.plugins_store_error_no_release)
    PluginStoreErrorCode.DOWNLOAD_FAILED -> stringResource(R.string.plugins_store_error_download)
    PluginStoreErrorCode.PREVIEW_FAILED -> error.pluginError?.let { pluginErrorMessage(it) }
        ?: stringResource(R.string.plugins_store_error_preview)
}
