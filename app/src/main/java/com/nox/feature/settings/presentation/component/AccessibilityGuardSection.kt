package com.nox.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nox.R
import com.nox.core.theme.semanticColors
import com.nox.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertTriangle
import compose.icons.feathericons.Layers
import compose.icons.feathericons.Lock
import compose.icons.feathericons.Shield
import com.nox.feature.device.AccessibilityGuard

/**
 * 无障碍保活设置区。
 *
 * ## 设计取向：说清「能做到什么」而不是「一键搞定」
 *
 * 无障碍能否自动修复，取决于设备有没有 Root / LSPosed——这不是我们能决定的。
 * 同类产品（Eta）的做法是把「强制保持无障碍」做成一个开关，但它默认关闭，
 * 且只在装了 LSPosed 的设备上真正生效。
 *
 * 这里换一种表达：**先显示当前设备实际处于哪一级，再让开关只在该级可用的范围内生效**。
 * 用户不会遇到「开了开关却没用」的困惑，因为开关本身的可用性就跟着等级走。
 *
 * | 等级 | 界面表现 |
 * | --- | --- |
 * | 仅感知 | 开关置灰，副标题说明「需要 Root 才能自动恢复」，并给出手动入口 |
 * | 可修复 | 开关可用，副标题说明会自动写回，并提示保留其他无障碍服务 |
 * | 已接管 | 开关置灰并标注「由系统模块接管」，避免两个纠正者打架 |
 *
 * ## 与旧实现的关系
 *
 * 旧版只有「无障碍服务」一行（跳系统设置）+ 一个「强制保持无障碍」开关，
 * 但开关背后没有任何实际动作。本组件把开关背后的真实能力显式化。
 */
@Composable
internal fun AccessibilityGuardSection(
    status: AccessibilityGuard.Status?,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (status == null) return

    val levelLabel = stringResource(
        when (status.level) {
            AccessibilityGuard.Level.HOOK_ENFORCED -> R.string.settings_a11y_level_hook
            AccessibilityGuard.Level.ROOT_REPAIR -> R.string.settings_a11y_level_root
            AccessibilityGuard.Level.OBSERVE_ONLY -> R.string.settings_a11y_level_observe
        }
    )

    // 只有「有 Root 且未被 hook 接管」时开关才有意义。
    val switchUsable = status.level == AccessibilityGuard.Level.ROOT_REPAIR
    val effectiveEnabled = enabled && switchUsable

    Column(modifier = modifier.fillMaxWidth()) {
        SettingsGroupHeader(text = stringResource(R.string.settings_a11y_group_title))

        SettingsGroup {
            // —— 状态行：一眼看出现在是「真的连上了」还是「只是设置里开着」——
            //
            // 这两种状态看着一样、用起来完全不同：设置里开着但没连上时，
            // 所有手机控制操作都会超时失败。旧版只显示「已启用」，把人骗过。
            SettingsRow(
                icon = FeatherIcons.Shield,
                title = stringResource(R.string.settings_a11y_status),
                subtitle = accessibilityStatusText(status),
                onClick = if (status.connected) null else onOpenSystemSettings,
                trailing = {
                    AccessibilityStateDot(connected = status.connected)
                },
            )

            // —— 能力等级：把「你的设备能做到什么」讲明白 ——
            SettingsRow(
                icon = FeatherIcons.Layers,
                title = stringResource(R.string.settings_a11y_level),
                subtitle = levelLabel,
            )

            // —— 自动恢复开关：只在真能生效时可用 ——
            SettingsRow(
                icon = FeatherIcons.Lock,
                title = stringResource(R.string.settings_a11y_keep_title),
                subtitle = stringResource(
                    when (status.level) {
                        AccessibilityGuard.Level.HOOK_ENFORCED ->
                            R.string.settings_a11y_keep_desc_hook
                        AccessibilityGuard.Level.ROOT_REPAIR ->
                            R.string.settings_a11y_keep_desc_root
                        AccessibilityGuard.Level.OBSERVE_ONLY ->
                            R.string.settings_a11y_keep_desc_observe
                    }
                ),
                enabled = switchUsable,
                trailing = {
                    Switch(
                        checked = effectiveEnabled,
                        onCheckedChange = if (switchUsable) onEnabledChange else null,
                        enabled = switchUsable,
                    )
                },
            )

            // —— 被关闭次数：只在真的掉过线时才显示，避免正常用户看到数字困惑 ——
            if (status.disabledCount > 0) {
                SettingsRow(
                    icon = FeatherIcons.AlertTriangle,
                    title = stringResource(R.string.settings_a11y_disabled_count),
                    subtitle = stringResource(
                        R.string.settings_a11y_disabled_count_desc,
                        status.disabledCount,
                    ),
                )
            }
        }

        // —— 兜底说明：无 Root 用户这里就是唯一出路 ——
        if (!switchUsable) {
            Text(
                text = stringResource(
                    if (status.level == AccessibilityGuard.Level.HOOK_ENFORCED) {
                        R.string.settings_a11y_hint_hook
                    } else {
                        R.string.settings_a11y_hint_no_root
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = Spacing.sm,
                    end = Spacing.sm,
                    top = Spacing.xs,
                ),
            )
        }
    }
}

/** 状态文案：区分「真的连上了」与「设置里开着但没连上」。 */
@Composable
private fun accessibilityStatusText(status: AccessibilityGuard.Status): String = when {
    status.connected -> stringResource(R.string.settings_a11y_state_connected)
    status.inEnabledList && status.switchOn ->
        stringResource(R.string.settings_a11y_state_listed_only)
    else -> stringResource(R.string.settings_a11y_state_off)
}

/**
 * 小圆点状态指示。
 *
 * 用颜色而非纯文字表达「正常/异常」，扫一眼就能判断；
 * 但旁边始终有文字说明，不依赖颜色单独传意（色盲友好）。
 */
@Composable
private fun AccessibilityStateDot(connected: Boolean) {
    val color = if (connected) {
        MaterialTheme.semanticColors.success
    } else {
        MaterialTheme.colorScheme.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = color, shape = CircleShape),
        )
    }
}
