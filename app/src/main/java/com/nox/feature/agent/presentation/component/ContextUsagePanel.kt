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
    /** 被用户中断的调用数。单独可见：中断既不算成功也不算失败，混在别处会被忽略。 */
    val cancelledCalls: Int = 0,
    /** 用户真正发起的对话轮数（排除压缩锚点/摘要）。 */
    val turns: Int = 0,
    val avgTtfbMillis: Double? = null,
    /** 响应头到达平均耗时（ms）：网络往返 + 服务端排队，不含模型生成。 */
    val avgHeadersMillis: Double? = null,
    /** 与 [avgHeadersMillis] 同批样本的首 token 平均耗时；两者相减才是服务端耗时。 */
    val avgTtfbWithHeadersMillis: Double? = null,
    val avgDurationMillis: Double? = null,
    /**
     * 所有调用耗时之和（ms）。模型用时展示必须用它。
     *
     * 不能用 `avgDurationMillis × calls`：durationMillis 可空，AVG 跳过 NULL 行
     * 而 COUNT(*) 不跳，相乘在存在 NULL 时会把总耗时算大。
     */
    val totalDurationMillis: Long = 0,
    val retryCount: Long = 0L,
    /** 本会话单次请求的最大输入 token，作为当前上下文占用的实测上界。 */
    val maxContextTokens: Long = 0L,
    /** 最近一次压缩的输入 token 数；为 0 表示本会话还没压缩过。 */
    val lastCompactionInputTokens: Long = 0L,
    /** 最近一次压缩的缓存命中 token 数。 */
    val lastCompactionCachedTokens: Long = 0L,
    /** 所有压缩调用的输出 token 合计。 */
    val compactionOutputTokens: Long = 0L,
    /** 所有压缩调用的输入 token 合计；与输出相加才是压缩的真实代价，也参与 [totalTokens] 求和。 */
    val compactionInputTokens: Long = 0L,
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
    val reasoningTokens: Long = 0L,
    /**
     * 纯生成耗时合计（ms）：逐次调用取 `durationMillis − ttfbMillis` 后求和。
     *
     * 与「总时长均值 − 首 token 均值」的区别：后者对两个**样本集不同**的均值相减，
     * 历史调用缺少 ttfb 埋点会把结果拉偏。这里逐行相减再累加，
     * 语义与逐次测量 firstToken→completed 的生成窗口完全一致。
     */
    val decodeMillis: Long = 0L,
    /** 参与 [decodeMillis] 统计的输出 token 合计（与生成耗时同批样本）。 */
    val decodeTokens: Long = 0L
) {
    /**
     * 组标题右侧的「总用量」。
     *
     * **不能照抄参考实现的六项全加**：那个公式成立的前提是 `promptTokens` 与
     * `compactionPromptTokens` 互斥（一个排除压缩、一个只含压缩）。
     * 而本项目的 `getSessionSummary` 里 `SUM(inputTokens)` 统计的是**全部行**，
     * 已经包含压缩调用在内；再单独加一遍 `compactionInputTokens` 就是重复计入
     * （实测重复 12,576,972 tok）。
     *
     * 所以正确的组合是：主会话总额（已含压缩）+ 子代理（独立会话，不在主会话统计内）。
     */
    val totalTokens: Long
        get() = inputTokens + outputTokens +
            subagentPromptTokens + subagentOutputTokens
    /**
     * 缓存命中率文本，null 表示本会话没有缓存活动（整行不显示）。
     *
     * 统一走 [hitPercent]：此前这里是 `.toInt()` 硬截断，而气泡用的是另一套取整，
     * 同一份数据在两处会显示成 72% 与 72.25% 两个数。
     */
    val cacheHitText: String?
        get() = if (inputTokens > 0 && cachedInputTokens > 0) {
            hitPercent(cachedInputTokens, inputTokens)
        } else null
    val uncachedInputTokens: Long get() = (inputTokens - cachedInputTokens).coerceAtLeast(0L)
    /**
     * 输出速度（tok/s）= 生成 token / 纯生成耗时。
     *
     * 只在有精确生成窗口样本时给值。不用「总时长均值 − 首 token 均值」估算：
     * 两个均值来自不同样本集（缺 ttfb 埋点的历史调用只进前者），相减后
     * 分母会偏大，速度被系统性低估。
     */
    val outputTps: Double?
        get() {
            if (decodeMillis <= 0L || decodeTokens <= 0L) return null
            return decodeTokens.toDouble() / (decodeMillis / 1000.0)
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
 * 缓存命中率文本，**统一保留两位小数**（如 `72.25%` / `99.79%`）。
 *
 * 精度规则（按用户要求：有小数就保留两位，不取整）：
 * 1. 真正全部命中（cached >= total）→ `100%`（不带无意义的小数）；
 * 2. 其余一律两位小数，且**四舍五入一旦会进位到 100.00，就改为向下截断**，
 *    因为 99.79% 显示成 100.00% 会让用户以为「零成本」，而实际仍有上万 token
 *    按全价计费。少报一点点远好于虚报满分。
 */
/**
 * 输出速度文本，与参考实现同口径：≥10 取整、<10 保留一位小数。
 *
 * 为什么不做两位小数：TPS 是观测值不是精确量，整数位已足够区分模型档位；
 * 小数位越多越容易被误读成「精确测量结果」。
 */
internal fun formatTps(tps: Double?): String {
    val v = (tps ?: 0.0).coerceAtLeast(0.0)
    if (v >= 10.0) return Math.round(v).toString()
    val tenths = Math.round(v * 10.0)
    val whole = tenths / 10
    val decimal = tenths % 10
    return if (decimal == 0L) whole.toString() else "$whole.$decimal"
}

internal fun hitPercent(cached: Long, total: Long): String {
    if (total <= 0) return "0%"
    if (cached >= total) return "100%"
    val ratio = cached * 100.0 / total
    val rounded = String.format(Locale.US, "%.2f", ratio)
    // 未真正满命中却四舍五入成 100.00：改为向下截断两位，绝不放行满分。
    if (rounded == "100.00") {
        val truncated = kotlin.math.floor(ratio * 100.0) / 100.0
        return String.format(Locale.US, "%.2f%%", truncated)
    }
    return "$rounded%"
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


/**
 * 秒级指标（首 token 平均）在气泡统计行里的短写法。
 *
 * 单位用拉丁 s，小数统一两位（整数不带小数点），与面板内其他指标同精度：
 * 曾经这里是 ≥10 取整、<10 一位小数，同一屏里「11.4s」和「11s」并存。
 */
internal fun formatMetaSeconds(millis: Double): String {
    val seconds = (millis / 1000.0).coerceAtLeast(0.0)
    val hundredths = Math.round(seconds * 100.0)
    val whole = hundredths / 100L
    val decimal = hundredths % 100L
    return if (decimal == 0L) "${whole}s"
    else String.format(Locale.US, "%d.%02ds", whole, decimal)
}

/**
 * 毫秒转面板里的时间文本，统一用拉丁单位（s / min / h），不写中文「秒」「分」。
 *
 * 为什么统一成 s：面板里同时有「tok/s」这种拉丁单位，紧挨着再写「秒」会中英混排；
 * 一列数值右对齐时，「18.5秒」比「18.5s」宽出半个字，两位小数的数字就挤不到一条线上。
 *
 * 精度：
 * - 60 秒以下保留两位小数（整数不带小数点），与其他指标的小数位一致；
 * - 60 秒以上用「Nm Ms」，一小时以上用「Hh Mm」——这两档只给量级，不再带小数。
 */
internal fun formatPanelDuration(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val seconds = clamped / 1000.0
    if (seconds < 60.0) {
        val hundredths: Long = Math.round(seconds * 100.0)
        val whole = hundredths / 100L
        val decimal = hundredths % 100L
        return if (decimal == 0L) "${whole}s"
        else String.format(Locale.US, "%d.%02ds", whole, decimal)
    }
    val totalSeconds = Math.round(seconds)
    if (totalSeconds >= 3600) {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        return if (m > 0) "${h}h ${m}m" else "${h}h"
    }
    val minutes = totalSeconds / 60
    val rem = totalSeconds % 60
    return "${minutes}m ${rem}s"
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
    /** 标称上下文窗口（1M / 128K），用于展示分母；为 0 时回退到预算。 */
    contextWindow: Int,
    contextEstimated: Boolean,
    thresholdPercent: Int,
    onThresholdChange: (Int) -> Unit,
    compacting: Boolean,
    canCompact: Boolean,
    onCompact: () -> Unit
) {
    // 展示分母：标称窗口优先（厂商规格，一眼能对上），缺失时回退到真实预算。
    val displayWindow = contextWindow.takeIf { it > 0 } ?: contextBudget
    // 百分比必须与**显示出来的分母**同源：此前分子用预算、分母写标称窗口，
    // 占用落在 [预算, 窗口) 区间时会显示「995.00K / 1M」配「100.0%」，用户算不出来。
    val percent = if (displayWindow > 0) {
        contextInputTokens * 100.0 / displayWindow
    } else 0.0
    // 进度条、百分比、分母、阈值标记全部同源（displayWindow）：
    // 曾经分母用标称窗口、进度条用真实预算，同一张卡里出现「103%」与「90% 满」两个读数，
    // 用户无法判断哪个才代表「快触发压缩」。
    val progress = (percent / 100.0).toFloat().coerceIn(0f, 1f)
    // 阈值标记按同一分母换算，保证「滑杆设在 80%」时进度条上的红线也在 80% 位置。
    val overThreshold = percent >= thresholdPercent

    Column(modifier = Modifier.fillMaxWidth()) {

        // ── 顶部概览：大数字 + 百分比 + 带阈值刻度的进度条。
        // 顶部概览区不带图标与标签行，上下文用量的明细值放在 Token 用量组内。
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatCompactTokenCount(contextInputTokens.toLong()),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                // 分母与上面的百分比同源（displayWindow），保证「分子 / 分母」能算回百分比。
                text = " / ${formatCompactTokenCount(displayWindow.toLong())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp, start = 2.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                // 与组内「上下文用量」行同精度：两处用不同小数位时，
                // 同一个占用率会显示成「53.2%」和「53.28%」两个数，像是两份数据。
                text = String.format(Locale.US, "%.2f%%", percent),
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
                "%s / %s · %.2f%%",
                formatCompactTokenCount(contextInputTokens.toLong()),
                formatCompactTokenCount(displayWindow.toLong()),
                percent
            ),
            valueColor = if (overThreshold) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface
        )
        // 缓存命中与否直接决定花费，所以放在最前；只有存在缓存活动时才占位置。
        stats.cacheHitText?.let { hitText ->
            PanelValueRow(
                label = stringResource(R.string.context_panel_cache_hit),
                value = hitText,
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
        } ?: run {
            PanelValueRow(
                label = stringResource(R.string.context_panel_input),
                value = formatExactTokenCount(stats.inputTokens),
                suffix = "tok"
            )
        }
        // 输出行：推理 token 标在输出后面，括号内、灰色小字。
        // 推理已含在输出里，这里只作拆分说明，不再相加。
        // 括号拆分不做 note：note 会在单位之后追加，而用户期望的读法是
        // 「1,240,692（其中推理 4,297）tok」——括号紧跟数字。所以值里面
        // 只放数字与括号，单位仍走 suffix。
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
                "· 命中 ${hitPercent(stats.lastCompactionCachedTokens, stats.lastCompactionInputTokens)}"
            } else ""
            PanelValueRow(
                label = stringResource(R.string.context_panel_last_compaction, stats.compactionCalls),
                value = formatExactTokenCount(stats.lastCompactionInputTokens),
                suffix = "tok",
                note = hit
            )
            if (stats.compactionCalls > 1) {
                // 合计 = 输入 + 输出：压缩要读入整段上下文再写摘要，
                // 只报输出会把真实代价低估一个数量级（输入通常是输出的上百倍）。
                PanelValueRow(
                    label = stringResource(R.string.context_panel_compaction_total),
                    value = formatExactTokenCount(
                        stats.compactionInputTokens + stats.compactionOutputTokens
                    ),
                    suffix = "tok"
                )
            }
        }
        // 子代理：独立会话独立计费，但花的是同一次任务的钱，必须并进来才看得全。
        if (stats.subagentCount > 0) {
            val hit = if (stats.subagentPromptTokens > 0) {
                "· 命中 ${hitPercent(stats.subagentCachedTokens, stats.subagentPromptTokens)}"
            } else ""
            PanelValueRow(
                label = stringResource(R.string.context_panel_subagent_count, stats.subagentCount),
                value = formatExactTokenCount(stats.subagentPromptTokens),
                suffix = "tok",
                note = hit
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
            // 显示「N 轮 M 步」而不是「N 次调用」：后者把压缩调用也算进去，
            // 用户容易误读成「聊了 N 轮」。轮=用户发起次数，步=轮内模型调用次数。
            trailing = if (stats.calls > 0) {
                stringResource(R.string.context_panel_turns_steps, stats.turns, stats.calls)
            } else null,
            topSpacing = PanelTokens.groupSpacing
        ) {
        // 四项始终显示：无数据时给 0，不整行消失，避免空态跳版。
        PanelValueRow(
            stringResource(R.string.context_panel_model_time),
            // 直接用 SUM(duration)：AVG × COUNT 在存在 NULL 耗时的调用时会高估。
            formatPanelDuration(stats.totalDurationMillis)
        )
        // 工具调用用时：工具执行起止的实测累加（tool_execution_records）。
        // 升级前的老会话没有这份埋点，累计为 0，这里会显示「0秒」而不是隐藏 ——
        // 隐藏会让用户以为没跑过工具，而 0 秒至少说明「本会话没有可统计的工具耗时」。
        PanelValueRow(
            stringResource(R.string.chat_turn_detail_tool_time),
            formatPanelDuration(stats.toolMillis)
        )
        // 首 token 平均（首字速度）。
        //
        // 只保留这一个加总项，**不做「网络与排队 / 服务端处理」拆解**：那两行需要真实的
        // HTTP 响应头到达时刻，而当前的 headersMillis 与 ttfbMillis 都在「第一个流式
        // chunk 到达」时打点，实测 1286 个样本的差值恒为 0.01ms。拆解出来永远是两个
        // 相等的数、服务端恒显示「可忽略」，用户减出来的 0 不代表真实情况。
        // 数据源不具备就宁可不显示，免得给出看似精确的假结论。
        PanelValueRow(
            label = stringResource(R.string.context_panel_ttfb),
            value = formatPanelDuration(
                (stats.avgTtfbWithHeadersMillis ?: stats.avgTtfbMillis ?: 0.0).toLong()
            )
        )
        PanelValueRow(
            stringResource(R.string.context_panel_tps),
            formatTps(stats.outputTps),
            suffix = "tok/s"
        )
        // 失败、中断、重试是异常信号，只在真的发生时才出现，正常使用时完全不占版面。
        if (stats.errors > 0 || stats.cancelledCalls > 0 || stats.retryCount > 0) {
            // 标签与数值必须同段数：有中断时是三段「失败 / 中断 / 重试」，
            // 没有中断时退回两段「失败 / 重试」。曾只改数值不改标签，
            // 出现「两个名称配三个数值」，用户看不出中间那个数是什么。
            val hasCancelled = stats.cancelledCalls > 0
            PanelValueRow(
                label = stringResource(
                    if (hasCancelled) R.string.context_panel_errors_cancelled_retries
                    else R.string.context_panel_errors_retries
                ),
                value = if (hasCancelled) {
                    "${stats.errors} / ${stats.cancelledCalls} / ${stats.retryCount}"
                } else {
                    "${stats.errors} / ${stats.retryCount}"
                },
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
        // 滑杆不再占满整行：整宽滑杆在卡片里像一条分割线，视觉上压过数据；
        // 收到 60% 宽并左对齐，右侧留白刚好放「50%..95%」的区间提示，
        // 省掉原来独占一行的两端标签。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onThresholdChange(sliderValue.toInt()) },
                valueRange = 50f..95f,
                steps = 8,
                enabled = !compacting,
                // Slider 默认自带 48dp 高度，其中大块是触摸预留空白。
                // 不限制会在信息卡片里凭空多吃一行半，让压缩组显得很空。
                modifier = Modifier
                    .weight(PanelTokens.sliderWeight)
                    .height(PanelTokens.sliderHeight)
            )
            Spacer(Modifier.width(PanelTokens.sliderTrailingGap))
            Text(
                text = stringResource(R.string.context_panel_threshold_range),
                style = TextStyle(fontSize = PanelTokens.rowLabelSize, lineHeight = PanelTokens.rowLine),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 不再放「上下文占用达到该比例时自动压缩」这行说明：
        // 组标题已写「自动压缩」、右侧是当前百分比、下面是滑杆，
        // 语义已经完整，再加一行解释只是把按钮推低、增加视觉噪声。
        Spacer(Modifier.height(PanelTokens.compactButtonGap))
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
