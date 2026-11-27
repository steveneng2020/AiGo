package com.nox.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import compose.icons.FeatherIcons
import compose.icons.feathericons.Lock
import compose.icons.feathericons.UserCheck
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nox.R
import com.nox.core.theme.Spacing
import com.nox.core.theme.semanticColors
import com.nox.feature.settings.presentation.PermissionHealthProbe
import com.nox.feature.settings.presentation.PermissionHealthViewModel

/**
 * 权限健康页。
 *
 * ## 布局取向：一屏看完，不折叠
 *
 * 权限问题的特点是「用户不知道缺什么」，所以这一页不做分组折叠、
 * 不做分页——把所有项平铺出来，配合顶部一句「N/M 项就绪」，
 * 用户扫一眼就知道该补哪个。
 *
 * ## 状态用「三档」而非「开/关」
 *
 * 无障碍这类权限存在「设置里开着但服务没连上」的中间态。
 * 只看系统开关会误报为正常，而实际所有手机控制都会失败。
 * 因此状态分 READY（就绪）/ ATTENTION（需注意）/ MISSING（未授予），
 * 用三种颜色 + 三档文案区分，且旁边始终有文字，不单靠颜色传意。
 */
@Composable
internal fun PermissionHealthScreen(
    onBack: () -> Unit,
    viewModel: PermissionHealthViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()

    // 存储权限走运行时请求，不跳设置页：本应用 targetSdk=28，
    // READ/WRITE_EXTERNAL_STORAGE 是普通运行时权限，一步到位。
    // 系统没有直达单个 runtime 权限的稳定 Action，跳「应用信息」只会多翻两层。
    val storageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refresh()
    }

    // 从系统设置页返回时重新探测：用户可能刚授权完。
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val (ready, total) = viewModel.summary()

    // 不要自己的 Scaffold/TopAppBar：外层 SettingsScreen 已经提供了标题栏与返回按钮，
    // 再套一层会出现「权限健康」重复两次（实测截图里就是两层标题）。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // 顶部总结：一句话说清「还差什么」
        Text(
            text = stringResource(R.string.settings_permission_health_summary, ready, total),
            style = MaterialTheme.typography.bodyMedium,
            color = if (ready == total) {
                MaterialTheme.semanticColors.success
            } else {
                MaterialTheme.semanticColors.warning
            },
            modifier = Modifier.padding(vertical = Spacing.sm),
        )

        SettingsGroupHeader(text = stringResource(R.string.settings_permission_health_group))

        SettingsGroup {
            items.forEach { item ->
                PermissionHealthRow(
                    item = item,
                    onClick = {
                        // 只有「运行时权限」需要分情况：
                        // 已授予时 requestPermissions 会立刻返回、不弹框，
                        // 用户看到的就是「点了没反应」——所以要改跳设置页。
                        //
                        // 其余项都是特殊权限（悬浮窗 / 修改系统设置 / 安装应用 /
                        // 通知 / 电池优化 / 无障碍），它们的系统页无论是否已授权
                        // 都能进、且用户可能想进去撤销，所以一律照常跳转。
                        if (item.action == PermissionHealthProbe.Action.STORAGE) {
                            if (item.state == PermissionHealthProbe.State.READY) {
                                // 已授予：没有可申请的，跳到应用详情让用户自己看/撤销
                                viewModel.openAppDetails()
                            } else {
                                storageLauncher.launch(
                                    arrayOf(
                                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    ),
                                )
                            }
                        } else {
                            viewModel.onItemClick(item.action)
                        }
                    },
                )
            }
        }

        // 底部提示：说明这一页只读不改，避免用户以为在这里能直接授权
        Text(
            text = stringResource(R.string.settings_permission_health_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm, start = Spacing.sm, end = Spacing.sm),
        )
    }
}

@Composable
private fun PermissionHealthRow(
    item: PermissionHealthProbe.Item,
    onClick: () -> Unit,
) {
    SettingsRow(
        title = item.title,
        subtitle = item.description,
        onClick = onClick,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                // 状态文字 + 圆点：颜色辅助扫读，文字保证准确
                Text(
                    text = stateLabel(item.state),
                    style = MaterialTheme.typography.bodySmall,
                    color = stateColor(item.state),
                    fontWeight = FontWeight.Medium,
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(stateColor(item.state), CircleShape),
                )
            }
        },
    )
}

@Composable
private fun stateLabel(state: PermissionHealthProbe.State): String = when (state) {
    PermissionHealthProbe.State.READY -> stringResource(R.string.settings_perm_state_ready)
    PermissionHealthProbe.State.ATTENTION -> stringResource(R.string.settings_perm_state_attention)
    PermissionHealthProbe.State.MISSING -> stringResource(R.string.settings_perm_state_missing)
}

@Composable
private fun stateColor(state: PermissionHealthProbe.State): Color = when (state) {
    PermissionHealthProbe.State.READY -> MaterialTheme.semanticColors.success
    PermissionHealthProbe.State.ATTENTION -> MaterialTheme.semanticColors.warning
    PermissionHealthProbe.State.MISSING -> MaterialTheme.colorScheme.error
}

/**
 * 主设置页里的「无障碍服务」+「自动保持无障碍」两行。
 *
 * ## 为什么从权限子页挪到主菜单
 *
 * 手机控制用不了时，用户第一步是翻设置。埋在「设置 → 权限 → 无障碍保活」
 * 三层里，排查成本太高；而这两项正是「为什么用不了」的最直接答案。
 * 同类实现也是把它们放在主设置页的「权限与增强」组里。
 *
 * ## 状态文案的取舍
 *
 * 列表行位置有限，写不下完整三档解释；这里只区分「可用 / 看着开了但没连上 / 未开」，
 * 详细说明留给 [PermissionHealthScreen] 和 [AccessibilityGuardSection]。
 */
@Composable
internal fun PermissionHealthAccessibilityRows(
    viewModel: PermissionHealthViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val a11y = items.firstOrNull { it.key == "accessibility" }
    val connected = a11y?.state == PermissionHealthProbe.State.READY

    // 自动保持开关单独取：它属于无障碍保活，不在权限探测范围内。
    val guardViewModel: com.nox.feature.settings.presentation.AccessibilityGuardViewModel =
        hiltViewModel()
    val guardStatus by guardViewModel.status.collectAsStateWithLifecycle()
    val keepEnabled by guardViewModel.keepEnabled.collectAsStateWithLifecycle()

    SettingsRow(
        icon = FeatherIcons.UserCheck,
        title = stringResource(R.string.settings_a11y_service_row),
        subtitle = a11y?.description ?: stringResource(R.string.settings_perm_state_missing),
        onClick = { viewModel.onItemClick(PermissionHealthProbe.Action.ACCESSIBILITY) },
        trailing = {
            Text(
                text = if (connected) {
                    stringResource(R.string.settings_a11y_state_enabled)
                } else {
                    stringResource(R.string.settings_a11y_state_disabled)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (connected) {
                    MaterialTheme.semanticColors.success
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        },
    )

    SettingsDivider()

    // 自动保持：只在有 Root（或 L3 接管）时可用，与权限子页那个开关同一份状态。
    val switchUsable = guardStatus?.level == com.nox.feature.device.AccessibilityGuard.Level.ROOT_REPAIR
    SettingsRow(
        icon = FeatherIcons.Lock,
        title = stringResource(R.string.settings_a11y_keep_title),
        subtitle = when (guardStatus?.level) {
            com.nox.feature.device.AccessibilityGuard.Level.HOOK_ENFORCED ->
                stringResource(R.string.settings_a11y_keep_desc_hook)
            com.nox.feature.device.AccessibilityGuard.Level.ROOT_REPAIR ->
                stringResource(R.string.settings_a11y_keep_desc_root)
            else -> stringResource(R.string.settings_a11y_keep_desc_observe)
        },
        enabled = switchUsable,
        trailing = {
            androidx.compose.material3.Switch(
                checked = keepEnabled && switchUsable,
                onCheckedChange = if (switchUsable) guardViewModel::setKeepEnabled else null,
                enabled = switchUsable,
            )
        },
    )
}
