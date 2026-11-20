package com.nox.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nox.R
import com.nox.core.theme.Radius
import com.nox.core.theme.Spacing

/** 上下文弧的默认颜色（蓝），与主题主色一致。 */
internal val DefaultContextArcColor = Color(0xFF3B82F6)

/** 预设色板：点一下即选中，覆盖常用色。 */
private val ArcColorPresets = listOf(
    Color(0xFF3B82F6), // 蓝
    Color(0xFF6366F1), // 靛
    Color(0xFF8B5CF6), // 紫
    Color(0xFFEC4899), // 粉
    Color(0xFFEF4444), // 红
    Color(0xFFF59E0B), // 琥珀
    Color(0xFF10B981), // 绿
    Color(0xFF14B8A6), // 青
    Color(0xFF64748B), // 灰
    Color(0xFF1C1C1E)  // 近黑
)

/** 色相档位（度），0..330 每 30 一档。 */
private val HueStops = (0..11).map { it * 30f }

/**
 * 长按发送键弹出的上下文弧取色盘。
 *
 * 不放进设置页：这是聊天时随手调的东西，长按就地改、即时生效更顺手。
 * 选色结果落进 DataStore（`GeneralSettingsRepository.contextArcColorFlow`）。
 */
@Composable
internal fun ContextArcColorDialog(
    current: Color,
    onConfirm: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_arc_color_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = stringResource(R.string.chat_arc_color_presets),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ArcColorPresets.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        row.forEach { color ->
                            Swatch(
                                color = color,
                                selected = selected.toArgb() == color.toArgb(),
                                onClick = { selected = color }
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.chat_arc_color_custom),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                ) {
                    HueStops.forEach { hue ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .background(Color.hsv(hue, 0.72f, 1f))
                                .clickable { selected = Color.hsv(hue, 0.72f, 1f) }
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                ) {
                    listOf(1.0f, 0.85f, 0.7f, 0.55f, 0.4f, 0.3f).forEach { value ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .background(
                                    Color(
                                        red = selected.red * value,
                                        green = selected.green * value,
                                        blue = selected.blue * value
                                    )
                                )
                                .clickable {
                                    selected = Color(
                                        red = selected.red * value,
                                        green = selected.green * value,
                                        blue = selected.blue * value
                                    )
                                }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Text(
                        text = stringResource(R.string.chat_arc_color_preview),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(selected)
                            .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}
