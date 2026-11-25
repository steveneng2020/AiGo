package com.nox.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.R
import com.nox.core.theme.Brand
import com.nox.core.theme.Spacing
import com.nox.core.theme.PanelTokens
import com.nox.core.ui.PanelSection
import com.nox.core.ui.PanelSectionHeader
import com.nox.core.ui.PanelValueRow
import compose.icons.feathericons.Activity
import compose.icons.feathericons.Aperture
import compose.icons.feathericons.ChevronsDown
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Database
import compose.icons.feathericons.HardDrive
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
    /** 响应头到达平均耗时（ms）：网络往返 + 服务端排队，不含模型生成。 */
    val avgHeadersMillis: Double? = null,
    /** 与 [avgHeadersMillis] 同批样本的首 token 平均耗时；两者相减才是服务端耗时。 */
    val avgTtfbWithHeadersMillis: Double? = null,
    val avgDurationMillis: Double? = null,
    val retryCount: Long = 0L,
    /** 本会话单次请求的最大输入 token，作为当前上下文占用的实测上界。 */
    val maxContextTokens: Long = 0L,
    /** 最近一次压缩的输入 token 数；为 0 表示本会话还没压缩过。 */
    val lastCompactionInputTokens: Long = 0L,
    /** 最近一次压缩的缓存命中 token 数。 */
    val lastCompactionCachedTokens: Long = 0L,
    /** 所有压缩调用的输出 token 合计。 */
    val compactionOutputTokens: Long = 0L,
    /** 本会话派生的子代理数量。 */
    val subagentCount: Int = 0,
    /** 子代理累计输入 token。 */
    val subagentPromptTokens: Long = 0L,
    /** 子代理累计命中缓存 token。 */
    val subagentCachedTokens: Long = 0L,
    /** 子代理累计输出 token。 */
    val subagentOutputTokens: Long = 0L,
    /** 非增量未命中 token：写过缓存却没读到的输入量，按全价计费。 */
    val nonIncrementalMissTokens: Long = 0L,
    /** TTFT 的有效样本数：用于区分「每次都慢」与「偶发一次慢」。 */
    val ttfbSamples: Int = 0,
    /**
     * 工具调用耗时（ms）：相邻两次模型调用之间的空档之和。
     *
     * 口径：两次调用之间的间隔 = 工具执行 + 等待用户授权 + 网络间隙。这是从调用记录
     * 能反推出的唯一可行口径；比「轮挂钟 − 模型耗时」略小（不含首轮前与末轮后的空档），
     * 但不需要额外埋点，也不会把思考时间误计入工具。
     */
    val toolMillis: Long = 0L,
    /** 本会话输出中推理（思考）token 的合计，已包含在 [outputTokens] 内。 */
    val reasoningTokens: Long = 0L
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
            // 生成耗时 = 总耗时 − 首 token 等待。不扣 TTFT 会把等待当成生成，
            // 速度偏低一个量级（实测 23 tok/s vs 扣除后 ~152 tok/s）。
            val genMs = (ms - (avgTtfbMillis ?: 0.0)).coerceAtLeast(1.0)
            val avgOut = out.toDouble() / calls.coerceAtLeast(1)
            return avgOut / (genMs / 1000.0)
        }
}

/**
 * 分组分隔线：0.5dp 细线，上 8dp 下 10dp。
 */
@Composable
private fun PanelSectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 10.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    )
}

/**
 * 完整的 token 计数：千分位分隔，不缩写（如 12,987,460）。
 * 用在组标题右侧与各组明细值上；缩写只在空间紧张的气泡统计行用。
 */
internal fun formatExactTokenCount(tokens: Long): String =
    java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(tokens.coerceAtLeast(0))

/**
 * 紧凑的 token 计数：1M 以下保留两位小数的 K，1M 以上用 M（如 197.88K / 1M）。
 *
 * 与 [formatTokenCount] 的区别：那个用于气泡下方窄条，只留一位省宽度；
 * 面板空间充足，两位小数更接近真值，也便于跨行对比。
 */
/**
 * 把「可用输入预算」还原成模型的标称上下文窗口，用于展示分母。
 *
 * 为什么展示标称值而不是可用预算：预算 = 标称 − 输出预留（1K~8K），
 * 所以 1M 模型显示成 `991.81K`，用户会以为自己没买够或数据算错。
 * 标称窗口是厂商给的整数（128K / 1M），一眼能对上模型规格；
 * 进度条与阈值判断仍用真实预算，两者分工不同。
 *
 * 换算方式：预算必然落在某个标称档位减去预留后的区间内，取第一个包含它的档位。
 */
internal fun nominalContextWindow(budget: Int): Int {
    if (budget <= 0) return 0
    val candidates = intArrayOf(
        8_192, 16_384, 32_768, 65_536,
        128_000, 200_000, 256_000, 512_000,
        1_000_000, 1_048_576, 2_000_000, 2_097_152
    )
    for (nominal in candidates) {
        // 预留最多 1/10 且不超过 8192，据此反推该标称档位的可用下限。
        val reserve = (nominal / 10).coerceIn(1_024, 8_192).coerceAtMost(nominal / 4)
        if (budget >= nominal - reserve) return nominal
    }
    // 超出已知档位（自定义大窗口）：原样返回，不编一个不存在的标称值。
    return budget
}

internal fun formatCompactTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000_000L -> {
        val v = tokens / 1_000_000_000.0
        if (v == v.toLong().toDouble()) "${v.toLong()}B" else String.format(Locale.US, "%.2fB", v)
    }
    tokens >= 1_000_000L -> {
        val v = tokens / 1_000_000.0
        if (v == v.toLong().toDouble()) "${v.toLong()}M" else String.format(Locale.US, "%.2fM", v)
    }
    tokens >= 1_000L -> String.format(Locale.US, "%.2fK", tokens / 1_000.0)
    else -> tokens.toString()
}


/** 秒级指标（首 token 平均）在统计行里的短写法：保留一位小数，整数不带小数点。 */
internal fun formatMetaSeconds(millis: Double): String {
    val seconds = millis / 1000.0
    return if (seconds >= 10 || seconds == seconds.toLong().toDouble()) {
        String.format(Locale.US, "%.0f", seconds)
    } else {
        String.format(Locale.US, "%.1f", seconds)
    }
}

/** 毫秒转「x 分 y 秒」/「x.x 秒」，面板里统一用这个。 */
internal fun formatPanelDuration(ms: Long): String = when {
    // 超过一小时按「x 时 y 分」：纯分钟数（如 942 分）无法直观判断时长量级。
    ms >= 3_600_000 -> {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        if (m > 0) "${h} 时 ${m} 分" else "${h} 时"
    }
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
 * 结构：顶部一行总览，下面按「Token 用量 / 会话统计 / 自动压缩」
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

    Column(modifier = Modifier.fillMaxWidth()) {

        // ── 顶部概览：大数字 + 百分比 + 带阈值刻度的进度条。
        // 顶部概览区不带图标与标签行，上下文用量的明细值放在 Token 用量组内。
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
        // 打开即全文，不折叠。下面按分组顺序平铺：
        // Token 用量（含上下文用量行）→ 会话统计 → 自动压缩。
        Spacer(Modifier.height(Spacing.sm))

        // ── ② Token 用量：会话总量 + 上下文占用量 + 缓存四项 + 输出 ──
        PanelSection(
            icon = PanelIcons.TokenUsage,
            title = stringResource(R.string.context_panel_section_tokens),
            trailing = if (stats.totalTokens > 0) formatExactTokenCount(stats.totalTokens) + " tok" else null
        ) {
        // 上下文用量作为组内第一行：值与占比、预算同排，读者不用回头找。
        // 数值用带两位小数的 K/M 缩写，比 310.2k 更接近原值的精度。
        PanelValueRow(
            label = stringResource(R.string.context_panel_section_context),
            value = String.format(
                Locale.US,
                "%s / %s · %.1f%%",
                formatCompactTokenCount(contextInputTokens.toLong()),
                formatCompactTokenCount(nominalContextWindow(contextBudget).toLong()),
                percent
            ),
            valueColor = if (overThreshold) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface
        )
        // 缓存命中与否直接决定花费，所以放在最前；只有存在缓存活动时才占位置。
        if (stats.cacheHitPercent != null) {
            PanelValueRow(
                label = stringResource(R.string.context_panel_cache_hit),
                value = "${stats.cacheHitPercent}%",
                valueColor = Color(0xFF2563EB),
                emphasize = true
            )
            PanelValueRow(
                label = stringResource(R.string.context_panel_cache_miss),
                value = formatExactTokenCount(stats.uncachedInputTokens),
                suffix = "tok"
            )
            // 缓存写入为 0 时不显示：0 值是噪音，且会让「没发生写入」
            // 看起来像是「写入了 0」，容易误读成异常。
            if (stats.cacheCreationTokens > 0) {
                PanelValueRow(
                    label = stringResource(R.string.context_panel_cache_write),
                    value = formatExactTokenCount(stats.cacheCreationTokens),
                    suffix = "tok"
                )
            }
            PanelValueRow(
                label = stringResource(R.string.context_panel_cache_read),
                value = formatExactTokenCount(stats.cachedInputTokens),
                suffix = "tok"
            )
        } else {
            PanelValueRow(
                label = stringResource(R.string.context_panel_input),
                value = formatExactTokenCount(stats.inputTokens),
                suffix = "tok"
            )
        }
        // 输出行：推理 token 标在输出后面，括号内、灰色小字。
        // 推理已含在输出里，这里只作拆分说明，不再相加。
        PanelValueRow(
            label = stringResource(R.string.context_panel_output),
            value = if (stats.reasoningTokens > 0) {
                stringResource(
                    R.string.context_panel_output_with_reasoning,
                    formatExactTokenCount(stats.outputTokens),
                    formatExactTokenCount(stats.reasoningTokens)
                )
            } else {
                formatExactTokenCount(stats.outputTokens)
            },
            suffix = "tok"
        )
        // 压缩记录：只有真的压缩过才出现。压缩本身要花一次完整请求的钱，
        // 把规模与命中率摆出来，用户才能判断这次压缩是否划算。
        if (stats.compactionCalls > 0) {
            val hit = if (stats.lastCompactionInputTokens > 0) {
                String.format(
                    Locale.US,
                    " · 命中 %.0f%%",
                    stats.lastCompactionCachedTokens * 100.0 / stats.lastCompactionInputTokens
                )
            } else ""
            PanelValueRow(
                label = stringResource(R.string.context_panel_last_compaction, stats.compactionCalls),
                value = formatExactTokenCount(stats.lastCompactionInputTokens) + " tok" + hit
            )
            if (stats.compactionCalls > 1) {
                PanelValueRow(
                    label = stringResource(R.string.context_panel_compaction_total),
                    value = formatExactTokenCount(stats.compactionOutputTokens) + " tok"
                )
            }
        }
        // 子代理：独立会话独立计费，但花的是同一次任务的钱，必须并进来才看得全。
        if (stats.subagentCount > 0) {
            val hit = if (stats.subagentPromptTokens > 0) {
                String.format(
                    Locale.US,
                    " · 命中 %.0f%%",
                    stats.subagentCachedTokens * 100.0 / stats.subagentPromptTokens
                )
            } else ""
            PanelValueRow(
                label = stringResource(R.string.context_panel_subagent_count, stats.subagentCount),
                value = formatExactTokenCount(stats.subagentPromptTokens) + " tok" + hit
            )
            PanelValueRow(
                label = stringResource(R.string.context_panel_subagent_output),
                value = formatExactTokenCount(stats.subagentOutputTokens),
                suffix = "tok"
            )
        }
        // 非增量未命中：写过缓存却掉回全价的输入量，是缓存策略失效的直接信号。
        if (stats.nonIncrementalMissTokens > 5_000) {
            PanelValueRow(
                label = stringResource(R.string.context_panel_non_incremental_miss),
                value = formatExactTokenCount(stats.nonIncrementalMissTokens),
                suffix = "tok",
                valueColor = MaterialTheme.colorScheme.error
            )
        }
        }

        // ── ③ 会话统计：始终显示，数据为空时各行为 0，不整块藏起来 ──
        PanelSection(
            icon = PanelIcons.SessionStats,
            title = stringResource(R.string.context_panel_section_session),
            // 原来这里把「对话调用次数 / 总调用次数」当成「轮 / 步」显示，语义是错的：
            // 两者都是 API 调用计数，不是用户发起的轮次，也不是工具步数。
            // 真实可得的只有调用次数，改为如实展示「n 次调用」。
            trailing = if (stats.calls > 0) {
                stringResource(R.string.context_panel_call_count, stats.calls)
            } else null,
            topSpacing = PanelTokens.groupSpacing
        ) {
        // 四项始终显示：无数据时给 0，不整行消失，避免空态跳版。
        PanelValueRow(
            stringResource(R.string.context_panel_model_time),
            formatPanelDuration(
                ((stats.avgDurationMillis ?: 0.0) * stats.calls.coerceAtLeast(1)).toLong()
            )
        )
        // 工具调用用时：由调用时间线的相邻空档反推（见 estimateToolMillis）。
        PanelValueRow(
            stringResource(R.string.chat_turn_detail_tool_time),
            formatPanelDuration(stats.toolMillis)
        )
        PanelValueRow(
            label = stringResource(R.string.context_panel_ttfb),
            value = formatPanelDuration((stats.avgTtfbMillis ?: 0.0).toLong())
        )
        // 首 token 耗时拆解：让用户看出长等到底在链路/排队还是服务端处理。
        // 只在有 headers 样本（新版本埋点后的调用）时显示，历史数据没这段。
        // 拆解只在「同一批样本」上做：avgTtfbWithHeadersMillis 与 avgHeadersMillis
        // 都只统计有 headers 埋点的调用，相减才有意义。用全部调用的 avgTtfbMillis
        // 相减会得到负数（历史调用没有 headers 数据）。
        stats.avgTtfbWithHeadersMillis?.let { ttfbSameBatch ->
            stats.avgHeadersMillis?.let { headers ->
                PanelValueRow(
                    label = stringResource(R.string.context_panel_ttfb_network),
                    value = formatPanelDuration(headers.toLong())
                )
                val serverMs = (ttfbSameBatch - headers).coerceAtLeast(0.0)
                PanelValueRow(
                    label = stringResource(R.string.context_panel_ttfb_server),
                    value = formatPanelDuration(serverMs.toLong())
                )
            }
        }
        PanelValueRow(
            stringResource(R.string.context_panel_tps),
            String.format(Locale.US, "%.0f", stats.outputTps ?: 0.0),
            suffix = "tok/s"
        )
        // 失败与重试是异常信号，只在真的发生时才出现，正常使用时完全不占版面。
        if (stats.errors > 0 || stats.retryCount > 0) {
            PanelValueRow(
                label = stringResource(R.string.context_panel_errors_retries),
                value = "${stats.errors} / ${stats.retryCount}",
                valueColor = MaterialTheme.colorScheme.error
            )
        }

        }

        // ── 自动压缩：阈值可调 + 立即压缩（本应用独有）──
        PanelSection(
            icon = PanelIcons.Compact,
            title = stringResource(R.string.context_panel_section_auto_compact),
            trailing = "$thresholdPercent%",
            trailingEmphasis = true,
            topSpacing = PanelTokens.groupSpacing
        ) {
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
            // 分隔线下方已有 10dp 留白，滑块再叠一层 top padding 会显得空一大块。
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "50%",
                style = TextStyle(fontSize = PanelTokens.rowValueSize, lineHeight = PanelTokens.rowLine),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "95%",
                style = TextStyle(fontSize = PanelTokens.rowValueSize, lineHeight = PanelTokens.rowLine),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = stringResource(R.string.context_panel_threshold_desc),
            style = TextStyle(fontSize = PanelTokens.rowValueSize, lineHeight = PanelTokens.rowLine),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs)
        )
        Spacer(Modifier.height(Spacing.md))
        // 实心主色按钮，高度收到 30dp：比标准按钮矮一档，在信息面板里不至于
        // 压过数据本身；圆角取半高形成胶囊，与卡片 18dp 圆角各自成立。
        Button(
            onClick = onCompact,
            enabled = canCompact,
            modifier = Modifier.fillMaxWidth().height(PanelTokens.actionButtonHeight),
            shape = RoundedCornerShape(PanelTokens.actionButtonCorner),
            contentPadding = PaddingValues(horizontal = PanelTokens.cardPaddingH, vertical = 0.dp)
        ) {
            Text(
                text = stringResource(
                    if (compacting) R.string.context_panel_compacting
                    else R.string.context_panel_compact_now_sc
                ),
                style = TextStyle(fontSize = PanelTokens.rowValueSize, lineHeight = PanelTokens.rowLine),
                fontWeight = FontWeight.Medium
            )
        }
        }
    }
}
