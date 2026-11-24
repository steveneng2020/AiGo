package com.nox.feature.agent.presentation.component

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nox.R
import com.nox.core.theme.Spacing
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

/** 面板里的一个区块标题。 */
@Composable
private fun PanelSection(
    title: String,
    trailing: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.md, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 键值行。value 用等宽数字对齐，便于纵向对比大小。 */
@Composable
private fun StatRow(
    label: String,
    value: String,
    emphasize: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
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
 * 六个区块：上下文占用 / 会话累计 / 性能 / 调用明细 / 思考档位 / 压缩管理。
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
    val percent = (progress * 100).toInt()

    Column(modifier = Modifier.fillMaxWidth()) {

        // ── ① 上下文占用 ──
        PanelSection(
            title = stringResource(R.string.context_panel_section_context),
            trailing = if (contextEstimated) {
                stringResource(R.string.context_panel_estimated)
            } else null
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatTokenCount(contextInputTokens.toLong()),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = " / ${formatTokenCount(contextBudget.toLong())}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (percent >= thresholdPercent) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        // 进度条 + 阈值刻度线：既看占用，又知道离自动压缩还有多远。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                if (percent >= thresholdPercent) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        )
                    )
            )
            if (thresholdPercent in 1..100) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(thresholdPercent / 100f)
                        .height(8.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(8.dp)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                    )
                }
            }
        }

        SectionDivider()

        // ── ② 会话累计 ──
        PanelSection(
            title = stringResource(R.string.context_panel_section_tokens),
            trailing = "${formatTokenCount(stats.totalTokens)} tok"
        )
        StatRow(stringResource(R.string.context_panel_input), "${formatTokenCount(stats.inputTokens)} tok")
        stats.cacheHitPercent?.let { hit ->
            StatRow(
                label = stringResource(R.string.context_panel_cache_hit),
                value = "$hit%  (${formatTokenCount(stats.cachedInputTokens)})",
                valueColor = MaterialTheme.colorScheme.primary
            )
            StatRow(stringResource(R.string.context_panel_cache_miss), "${formatTokenCount(stats.uncachedInputTokens)} tok")
        }
        if (stats.cacheCreationTokens > 0) {
            StatRow(
                stringResource(R.string.context_panel_cache_write),
                "${formatTokenCount(stats.cacheCreationTokens)} tok"
            )
        }
        StatRow(stringResource(R.string.context_panel_output), "${formatTokenCount(stats.outputTokens)} tok")

        SectionDivider()

        // ── ③ 性能 ──
        PanelSection(stringResource(R.string.context_panel_section_performance))
        stats.avgTtfbMillis?.let { ttfb ->
            StatRow(
                label = stringResource(R.string.context_panel_ttfb),
                value = buildString {
                    append(formatPanelDuration(ttfb.toLong()))
                    if (stats.ttfbSamples > 0) append("  (${stats.ttfbSamples} 次)")
                }
            )
        }
        stats.avgDurationMillis?.let {
            StatRow(stringResource(R.string.context_panel_avg_duration), formatPanelDuration(it.toLong()))
        }
        stats.outputTps?.let {
            StatRow(
                stringResource(R.string.context_panel_tps),
                String.format(Locale.US, "%.0f tok/s", it)
            )
        }

        SectionDivider()

        // ── ④ 调用明细（AiGo 独有：失败 / 重试 / 压缩调用分开计数）──
        PanelSection(stringResource(R.string.context_panel_section_calls))
        StatRow(stringResource(R.string.context_panel_total_calls), stats.calls.toString(), emphasize = true)
        StatRow(stringResource(R.string.context_panel_chat_calls), stats.chatCalls.toString())
        if (stats.compactionCalls > 0) {
            StatRow(stringResource(R.string.context_panel_compaction_calls), stats.compactionCalls.toString())
        }
        if (stats.errors > 0) {
            StatRow(
                label = stringResource(R.string.context_panel_errors),
                value = stats.errors.toString(),
                valueColor = MaterialTheme.colorScheme.error
            )
        }
        if (stats.retryCount > 0) {
            StatRow(stringResource(R.string.context_panel_retries), stats.retryCount.toString())
        }

        SectionDivider()

        // ── ⑤ 压缩管理 ──
        PanelSection(
            title = stringResource(R.string.context_panel_section_compaction),
            trailing = "$thresholdPercent%"
        )
        Text(
            text = stringResource(R.string.context_panel_threshold_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        var slider by remember(thresholdPercent) { mutableFloatStateOf(thresholdPercent.toFloat()) }
        Slider(
            value = slider,
            onValueChange = { slider = it },
            onValueChangeFinished = { onThresholdChange(slider.toInt()) },
            valueRange = 50f..95f,
            steps = 8,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("50%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("95%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(Spacing.sm))
        TextButton(
            onClick = onCompact,
            enabled = canCompact && !compacting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (compacting) {
                    stringResource(R.string.context_panel_compacting)
                } else {
                    stringResource(R.string.context_panel_compact_now)
                }
            )
        }
    }
}
