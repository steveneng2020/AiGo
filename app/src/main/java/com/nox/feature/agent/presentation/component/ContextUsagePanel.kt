package com.nox.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nox.R
import com.nox.core.theme.Brand
import com.nox.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.Aperture
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.ChevronsDown
import compose.icons.feathericons.Clock
import java.util.Locale

/**
 * 会话级用量明细，来自本地 `llm_call_records` 聚合。
 *
 * 与 [com.nox.feature.agent.domain.workflow.AgentEvent.ContextUsage] 的分工：
 * 后者是「此刻请求的实时占用」（单次请求的 inputTokens / budget），由 workflow 在请求前发出；
 * 本类是会话累计的统计（含缓存命中、TTFB、失败重试），供面板展示。
 */
data class SessionStatsUi(
    val calls: Int = 0,
    val chatCalls: Int = 0,
    val compactionCalls: Int = 0,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cachedInputTokens: Long = 0L,
    val cacheCreationTokens: Long = 0L,
    val errors: Int = 0,
    val avgTtfbMillis: Double? = null,
    val avgDurationMillis: Double? = null,
    val retryCount: Long = 0L,
    /** 本会话单次请求的最大输入 token，作为当前上下文占用的实测上界。 */
    val maxContextTokens: Long = 0L,
    /** TTFT 的有效样本数：用于区分「每次都慢」与「偶发一次慢」。 */
    val ttfbSamples: Int = 0
) {
    val totalTokens: Long get() = inputTokens + outputTokens
    val cacheHitPercent: Int?
        get() = if (inputTokens > 0 && cachedInputTokens > 0) {
            ((cachedInputTokens * 100.0) / inputTokens).toInt()
        } else null
    val uncachedInputTokens: Long get() = (inputTokens - cachedInputTokens).coerceAtLeast(0L)
    /** 输出速度（tok/s）：用平均耗时粗算，仅在两者都有效时给值。 */
    val outputTps: Double?
        get() {
            val ms = avgDurationMillis?.takeIf { it > 0 } ?: return null
            val out = outputTokens.takeIf { it > 0 } ?: return null
            val avgOut = out.toDouble() / calls.coerceAtLeast(1)
            return avgOut / (ms / 1000.0)
        }
}

/**
 * 面板分组标题：小图标 + 名称，右侧可选补充信息。
 *
 * 图标不只是装饰：分组多了以后纯靠字号和颜色区分，扫视时容易串行，
 * 给每组一个固定图形后，视线可以先落到图标上再读数值。
 */
@Composable
private fun PanelGroupHeader(
    icon: ImageVector,
    title: String,
    trailing: String? = null,
    trailingEmphasis: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (trailingEmphasis) FontWeight.SemiBold else FontWeight.Normal,
                color = if (trailingEmphasis) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 键值行。value 用等宽数字对齐，便于纵向对比大小。 */
@Composable
private fun StatRow(
    label: String,
    value: String,
    suffix: String? = null,
    emphasize: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Medium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (suffix != null) {
            Spacer(Modifier.width(3.dp))
            Text(
                text = suffix,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 区块之间的细分割线。比整块留白更省高度，也比什么都不留更清晰。 */
@Composable
private fun GroupDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    )
}

/** 毫秒转「x 分 y 秒」/「x.x 秒」，面板里统一用这个。 */
internal fun formatPanelDuration(ms: Long): String = when {
    ms >= 60_000 -> {
        val m = ms / 60_000
        val s = (ms % 60_000) / 1000
        if (s > 0) "${m} 分 ${s} 秒" else "${m} 分"
    }
    ms >= 1_000 -> String.format(Locale.US, "%.1f 秒", ms / 1000.0)
    else -> "${ms} 毫秒"
}

/**
 * 上下文用量面板。
 *
 * 结构参照 Eta：顶部一行总览，下面按「上下文用量 / Token 明细 / 会话统计 / 自动压缩」
 * 四组分列，每组一个图标一个标题，字段用紧凑键值行平铺。
 * 去掉了原来「本次会话累计」「性能」「调用明细」这类三层嵌套与内部术语
 * （总调用 / 对话调用 / TTFT / TPS），用户不需要知道底层调了几次 API。
 *
 * 所有数值都来自本地记录，开关面板不产生网络请求。
 */
@Composable
fun ContextUsagePanel(
    stats: SessionStatsUi,
    contextInputTokens: Int,
    contextBudget: Int,
    contextEstimated: Boolean,
    thresholdPercent: Int,
    onThresholdChange: (Int) -> Unit,
    compacting: Boolean,
    canCompact: Boolean,
    onCompact: () -> Unit
) {
    val progress = if (contextBudget > 0) {
        (contextInputTokens.toFloat() / contextBudget).coerceIn(0f, 1f)
    } else 0f
    val percent = (progress * 100)
    val overThreshold = percent >= thresholdPercent
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {

        // ── 上下文用量：标签行（图标 + 名称 + 估值标签）→ 值 + 占比 → 带阈值刻度的进度条 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = FeatherIcons.Aperture,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.context_panel_section_context),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (contextEstimated) {
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = stringResource(R.string.context_panel_estimated),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB54708),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFFEF0C7))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatTokenCount(contextInputTokens.toLong()),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = " / ${formatTokenCount(contextBudget.toLong())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp, start = 2.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = String.format(Locale.US, "%.1f%%", percent),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (overThreshold) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        // 进度条 + 阈值刻度：既看占用，又知道离自动压缩还有多远。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            if (overThreshold) {
                                listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error)
                            } else {
                                listOf(Color(0xFF2563EB), Color(0xFF3B82F6))
                            }
                        )
                    )
            )
            if (thresholdPercent in 1..99) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(thresholdPercent / 100f)
                        .height(6.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(6.dp)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.75f))
                    )
                }
            }
        }
        // 「估算」已上提到标签行，此处不再重复占一行。

        // 展开条：常态只显示上面的上下文用量概要，点开才看其余明细。
        Spacer(Modifier.height(Spacing.sm))
        GroupDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(
                    if (expanded) R.string.common_collapse_action
                    else R.string.context_panel_expand_detail
                ),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                contentDescription = null,
                tint = Brand.IconGray,
                modifier = Modifier.size(14.dp)
            )
        }

        if (expanded) {
        Spacer(Modifier.height(Spacing.sm))

        // ── ② Token 明细：缓存四项 + 输出（含推理）──
        // 缓存命中与否直接决定花费，所以放在最前；只有存在缓存活动时才占位置。
        if (stats.cacheHitPercent != null) {
            StatRow(
                label = stringResource(R.string.context_panel_cache_hit),
                value = "${stats.cacheHitPercent}%",
                valueColor = Color(0xFF2563EB),
                emphasize = true
            )
            StatRow(
                label = stringResource(R.string.context_panel_cache_miss),
                value = formatTokenCount(stats.uncachedInputTokens),
                suffix = "tok"
            )
            StatRow(
                label = stringResource(R.string.context_panel_cache_write),
                value = formatTokenCount(stats.cacheCreationTokens),
                suffix = "tok"
            )
            StatRow(
                label = stringResource(R.string.context_panel_cache_read),
                value = formatTokenCount(stats.cachedInputTokens),
                suffix = "tok"
            )
        } else {
            StatRow(
                label = stringResource(R.string.context_panel_input),
                value = formatTokenCount(stats.inputTokens),
                suffix = "tok"
            )
        }
        StatRow(
            label = stringResource(R.string.context_panel_output),
            value = formatTokenCount(stats.outputTokens),
            suffix = "tok"
        )

        if (stats.calls > 0 || stats.avgTtfbMillis != null || stats.avgDurationMillis != null) {
            Spacer(Modifier.height(Spacing.md))
            GroupDivider()
            Spacer(Modifier.height(Spacing.md))

            // ── ③ 会话统计：调用次数 + 用时 + 速度 ──
            PanelGroupHeader(
                icon = FeatherIcons.Clock,
                title = stringResource(R.string.context_panel_section_session),
                // 原来这里把「对话调用次数 / 总调用次数」当成「轮 / 步」显示，语义是错的：
                // 两者都是 API 调用计数，不是用户发起的轮次，也不是工具步数。
                // 真实可得的只有调用次数，改为如实展示「n 次调用」。
                trailing = if (stats.calls > 0) {
                    stringResource(R.string.context_panel_call_count, stats.calls)
                } else null
            )
            stats.avgDurationMillis?.let {
                StatRow(
                    stringResource(R.string.context_panel_model_time),
                    formatPanelDuration((it * stats.calls.coerceAtLeast(1)).toLong())
                )
            }
            stats.avgTtfbMillis?.let { ttfb ->
                StatRow(
                    label = stringResource(R.string.context_panel_ttfb),
                    value = formatPanelDuration(ttfb.toLong())
                )
            }
            stats.outputTps?.let {
                StatRow(
                    stringResource(R.string.context_panel_tps),
                    String.format(Locale.US, "%.0f", it),
                    suffix = "tok/s"
                )
            }
            // 失败与重试是异常信号，只在真的发生时才出现，正常使用时完全不占版面。
            if (stats.errors > 0 || stats.retryCount > 0) {
                StatRow(
                    label = stringResource(R.string.context_panel_errors_retries),
                    value = "${stats.errors} / ${stats.retryCount}",
                    valueColor = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))
        GroupDivider()
        Spacer(Modifier.height(Spacing.md))

        // ── ④ 自动压缩：阈值可调 + 立即压缩 ──
        PanelGroupHeader(
            icon = FeatherIcons.ChevronsDown,
            title = stringResource(R.string.context_panel_section_auto_compact),
            trailing = "$thresholdPercent%",
            trailingEmphasis = true
        )
        var sliderValue by remember(thresholdPercent) {
            mutableFloatStateOf(thresholdPercent.toFloat())
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onThresholdChange(sliderValue.toInt()) },
            valueRange = 50f..95f,
            steps = 8,
            enabled = !compacting,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "50%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "95%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = stringResource(R.string.context_panel_threshold_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs)
        )
        Spacer(Modifier.height(Spacing.md))
        Button(
            onClick = onCompact,
            enabled = canCompact,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = FeatherIcons.ChevronsDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = stringResource(
                    if (compacting) R.string.context_panel_compacting
                    else R.string.context_panel_compact_now_sc
                )
            )
        }
        }
    }
}
