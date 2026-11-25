package com.nox.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nox.feature.agent.data.local.entity.LlmCallRecordEntity
import kotlinx.coroutines.flow.Flow

/** 按天（本地时区）聚合的单日调用统计，供趋势图使用。day 为本地时区下的 epoch 天序号。 */
data class DayCallStats(
    val day: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val calls: Int,
    val errors: Int,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?,
    val retryCount: Long = 0
)

/** 按渠道（provider）聚合的调用统计，供渠道排行使用。 */
data class ProviderCallStats(
    val providerId: String?,
    val providerName: String?,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val errors: Int,
    val retryCount: Long = 0
)

/** 按模型聚合的调用统计，供模型排行使用。 */
data class ModelCallStats(
    val model: String?,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    /** 写入缓存的 token 数之和（Anthropic cache_creation），按单独单价计入费用估算。 */
    val cacheCreationTokens: Long,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?,
    val errors: Int,
    val retryCount: Long = 0
)

/** 单次调用的轻量时间线记录：轮末统计行按轮时间窗聚合用，避免把整条记录（含提示词级字段）拉进内存。 */
data class SessionCallPoint(
    val createdAt: Long,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedInputTokens: Int,
    val ttfbMillis: Int?,
    val durationMillis: Int?
)

/**
 * 按「渠道 + 模型」聚合的用量，专供周期总费用估算。
 * 单价由渠道（自定义元数据以「提供商ID:模型名」为键）与模型共同决定，
 * 只按模型聚合会丢掉渠道维度、算不到自定义单价。
 */
data class ModelProviderCostStats(
    val providerId: String?,
    val model: String?,
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val outputTokens: Long,
    val cacheCreationTokens: Long
)

/**
 * 会话级汇总：上下文用量面板的数据源。
 *
 * 比 [CallSummary] 多两项：
 * - compactionCalls / chatCalls：按 kind 拆开，压缩调用单独计数；
 * - maxContextTokens：本会话单次请求的最大输入 token，即「当前上下文占用」的实测上界。
 *
 * 为什么用 max 而不是最后一次：上下文只增不减（除压缩），max 就是当前真实占用，
 * 且不受「最后一次是压缩调用（输入只有摘要摘要）」干扰。
 */
/**
 * 子代理用量汇总：子代理是独立会话（`parentId` 指向父会话），其调用记录挂在子会话 id 下，
 * 因此要跨会话聚合才能算出「这次任务连带子代理一共花了多少」。
 */
data class SubagentCallSummary(
    val subagentCount: Int,
    val promptTokens: Long,
    val cachedTokens: Long,
    val outputTokens: Long
)

data class SessionCallSummary(
    val calls: Int,
    val chatCalls: Int,
    val compactionCalls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val cacheCreationTokens: Long,
    val errors: Int,
    /** 被用户中断的调用数（status='cancelled'）。此前它既不算成功也不算失败，面板完全看不见。 */
    val cancelledCalls: Int = 0,
    val avgTtfbMillis: Double?,
    /**
     * 与 [avgHeadersMillis] **同批样本**的首 token 平均耗时（ms）。
     *
     * 单独一份而不是复用 avgTtfbMillis：后者包含全部历史调用（其中多数没有 headers 埋点），
     * 两者相减会得出负数或倒挂的拆分项。
     */
    val avgTtfbWithHeadersMillis: Double? = null,
    /** 响应头到达平均耗时（ms）：与 avgTtfbWithHeadersMillis 相减才是服务端耗时。 */
    val avgHeadersMillis: Double? = null,
    val avgDurationMillis: Double?,
    /**
     * 所有调用耗时之和（ms）。模型用时展示必须用它。
     *
     * 不能用 `avgDurationMillis × calls`：durationMillis 可空，AVG 跳过 NULL 行
     * 而 COUNT(*) 不跳，相乘在存在 NULL 时会把总耗时算大。
     */
    val totalDurationMillis: Long = 0,
    val reasoningTokens: Long = 0,
    val retryCount: Long,
    val maxContextTokens: Long = 0,
    /** 最近一次压缩的输入 token 数。 */
    val lastCompactionInputTokens: Long = 0,
    /** 最近一次压缩的缓存命中 token 数。 */
    val lastCompactionCachedTokens: Long = 0,
    /** 所有压缩调用的输出 token 合计。 */
    val compactionOutputTokens: Long = 0,
    /**
     * 纯生成耗时合计（ms）：逐行取 `durationMillis − ttfbMillis` 后求和。
     *
     * 必须逐行相减再累加，不能「AVG(duration) − AVG(ttfb)」：
     * 两个均值各自跳过 NULL 行，样本集不同，相减结果偏大（速度被低估）。
     */
    val decodeMillis: Long = 0,
    /** 与 [decodeMillis] 同批样本的输出 token 合计。 */
    val decodeTokens: Long = 0,
    /**
     * 所有压缩调用的输入 token 合计。
     *
     * 「压缩产出合计」要把输入与输出相加：压缩的代价是**读入整段上下文再写摘要**，
     * 只报输出会低估一个数量级（输入通常是输出的上百倍）。这也是「压缩值不值」
     * 唯一能算清的口径。
     */
    val compactionInputTokens: Long = 0
)

/** 当前周期的整体汇总，供概览卡片使用。 */
data class CallSummary(
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val cacheCreationTokens: Long,
    val errors: Int,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?,
    val retryCount: Long = 0
)

/** 调用明细分页记录：单次调用 + 渠道名（LEFT JOIN ai_providers 取，渠道被删除后仍显示）。 */
data class RecentCallRecord(
    @Embedded val record: LlmCallRecordEntity,
    val providerName: String?
)

@Dao
interface LlmCallRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: LlmCallRecordEntity)

    @Query("DELETE FROM llm_call_records")
    suspend fun deleteAll()

    /**
     * 按天聚合（本地时区：把 createdAt 平移 [tzOffsetMillis] 后再按 86400000 取整切天）。
     * [tzOffsetMillis] 由调用方按当前系统时区提供（TimeZone.getDefault().getOffset(now)）。
     */
    @Query(
        """
        SELECT (createdAt + :tzOffsetMillis) / 86400000 AS day,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               COUNT(*) AS calls,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(retryCount) AS retryCount
        FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY day
        ORDER BY day ASC
        """
    )
    fun getDayStats(
        start: Long,
        tzOffsetMillis: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<DayCallStats>>

    /** 按小时聚合（本地时区），「今天」周期用小时粒度展示趋势。 */
    @Query(
        """
        SELECT (createdAt + :tzOffsetMillis) / 3600000 AS day,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               COUNT(*) AS calls,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(retryCount) AS retryCount
        FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY day
        ORDER BY day ASC
        """
    )
    fun getHourStats(
        start: Long,
        tzOffsetMillis: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<DayCallStats>>

    /** 按渠道聚合，LEFT JOIN ai_providers 取渠道名（渠道被删除后记录仍显示）。 */
    @Query(
        """
        SELECT r.providerId AS providerId,
               p.name AS providerName,
               COUNT(*) AS calls,
               SUM(r.inputTokens) AS inputTokens,
               SUM(r.outputTokens) AS outputTokens,
               SUM(r.cachedInputTokens) AS cachedInputTokens,
               SUM(CASE WHEN r.status = 'error' THEN 1 ELSE 0 END) AS errors,
               SUM(r.retryCount) AS retryCount
        FROM llm_call_records r
        LEFT JOIN ai_providers p ON p.id = r.providerId
        WHERE r.createdAt >= :start
          AND (:providerId IS NULL OR r.providerId = :providerId)
          AND (:model IS NULL OR r.model = :model)
        GROUP BY r.providerId
        ORDER BY (SUM(r.inputTokens) + SUM(r.outputTokens)) DESC
        """
    )
    fun getProviderStats(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<ProviderCallStats>>

    /** 按模型聚合，按总消耗倒序。 */
    @Query(
        """
        SELECT model AS model,
               COUNT(*) AS calls,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(cacheCreationTokens) AS cacheCreationTokens,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               SUM(retryCount) AS retryCount
        FROM llm_call_records
        WHERE createdAt >= :start AND model IS NOT NULL AND model != ''
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY model
        ORDER BY (SUM(inputTokens) + SUM(outputTokens)) DESC
        """
    )
    fun getModelStats(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<ModelCallStats>>

    /** 按「渠道 + 模型」聚合 token 用量，供周期总费用估算（费用必须带渠道才算得到自定义单价）。 */
    @Query(
        """
        SELECT providerId AS providerId,
               model AS model,
               SUM(inputTokens) AS inputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cacheCreationTokens) AS cacheCreationTokens
        FROM llm_call_records
        WHERE createdAt >= :start AND model IS NOT NULL AND model != ''
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY providerId, model
        """
    )
    fun getModelProviderCostStats(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<ModelProviderCostStats>>

    /** 周期整体汇总（无 GROUP BY，恒返回一行）。 */
    @Query(
        """
        SELECT COUNT(*) AS calls,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(cacheCreationTokens) AS cacheCreationTokens,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(retryCount) AS retryCount
        FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        """
    )
    fun getSummary(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<CallSummary>

    /**
     * 会话级汇总，供上下文用量面板使用。
     *
     * maxContextTokens 取 MAX(inputTokens)：上下文只增不减（压缩除外），
     * 最大值即当前占用上界；用最后一次会被压缩调用（输入仅摘要）拉低。
     * 压缩调用按 kind = 'compaction' 单独计数，不混进 chatCalls。
     */
    @Query(
        """
        SELECT COUNT(*) AS calls,
               SUM(CASE WHEN kind = 'chat' THEN 1 ELSE 0 END) AS chatCalls,
               SUM(CASE WHEN kind = 'compaction' THEN 1 ELSE 0 END) AS compactionCalls,
               COALESCE(SUM(inputTokens), 0) AS inputTokens,
               COALESCE(SUM(outputTokens), 0) AS outputTokens,
               COALESCE(SUM(cachedInputTokens), 0) AS cachedInputTokens,
               COALESCE(SUM(cacheCreationTokens), 0) AS cacheCreationTokens,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               SUM(CASE WHEN status = 'cancelled' THEN 1 ELSE 0 END) AS cancelledCalls,
               AVG(ttfbMillis) AS avgTtfbMillis,
               -- 首 token 拆解必须落在**同一批样本**上：两个独立 AVG 各自跳过 NULL 行时，
               -- 历史数据（无 headers 埋点）会把 avgTtfb 拉到与 avgHeaders 不可比的量级，
               -- 相减会出现「其中网络与排队 > 首 token 平均」的倒挂。
               -- 条件必须**双重且对称**（两个字段都非空）：只判 headersMillis 非空时，
               -- 若某行 headersMillis 有值但 ttfbMillis 为 NULL，第一个 AVG 跳过它、
               -- 第二个不跳，样本集依然不一致。
               AVG(CASE WHEN headersMillis IS NOT NULL AND ttfbMillis IS NOT NULL THEN ttfbMillis END) AS avgTtfbWithHeadersMillis,
               AVG(CASE WHEN headersMillis IS NOT NULL AND ttfbMillis IS NOT NULL THEN headersMillis END) AS avgHeadersMillis,
               AVG(durationMillis) AS avgDurationMillis,
               -- 「模型用时」用 SUM 而不是 AVG × COUNT：durationMillis 可空，
               -- AVG 跳过 NULL 行、COUNT(*) 不跳，相乘会把总耗时算大。
               COALESCE(SUM(durationMillis), 0) AS totalDurationMillis,
               COALESCE(SUM(retryCount), 0) AS retryCount,
               COALESCE(SUM(reasoningTokens), 0) AS reasoningTokens,
               COALESCE(MAX(CASE WHEN kind = 'chat' THEN inputTokens ELSE 0 END), 0) AS maxContextTokens,
               -- 最近一次压缩的输入规模与缓存命中：压缩本身也要花钱，
               -- 把它单独列出来，用户才能判断「这次压缩值不值」。
               --
               -- 必须用**同一行**的两个值：曾写成两个独立的 MAX(...)，
               -- 而 MAX 取的是“最大值”不是“最近一次”，且两个 MAX 可能落在不同行，
               -- 相除得到的命中率不对应任何一次真实压缩（实测出现过 21% 这种拼出来的数）。
               -- 子查询按 id 降序取第一条 compaction 记录，两个字段必然同源。
               COALESCE((SELECT inputTokens FROM llm_call_records
                         WHERE sessionId = :sessionId AND kind = 'compaction'
                         ORDER BY id DESC LIMIT 1), 0) AS lastCompactionInputTokens,
               COALESCE((SELECT cachedInputTokens FROM llm_call_records
                         WHERE sessionId = :sessionId AND kind = 'compaction'
                         ORDER BY id DESC LIMIT 1), 0) AS lastCompactionCachedTokens,
               COALESCE(SUM(CASE WHEN kind = 'compaction' THEN outputTokens ELSE 0 END), 0) AS compactionOutputTokens,
               COALESCE(SUM(CASE WHEN kind = 'compaction' THEN inputTokens ELSE 0 END), 0) AS compactionInputTokens,
               -- 生成耗时与生成 token 必须同批：两个条件完全一致（duration 与 ttfb 都非空），
               -- 保证 TPS 分子分母来自同一批调用。
               COALESCE(SUM(CASE WHEN durationMillis IS NOT NULL AND ttfbMillis IS NOT NULL
                                 THEN (durationMillis - ttfbMillis) ELSE 0 END), 0) AS decodeMillis,
               COALESCE(SUM(CASE WHEN durationMillis IS NOT NULL AND ttfbMillis IS NOT NULL
                                 THEN outputTokens ELSE 0 END), 0) AS decodeTokens
        FROM llm_call_records
        WHERE sessionId = :sessionId
        """
    )
    fun getSessionSummary(sessionId: String): Flow<SessionCallSummary>

    /**
     * 汇总本会话派生的**所有层级**子代理用量。
     *
     * 子代理会话与父会话是两条独立记录，token 也分别计费，只有跨会话求和
     * 才是这次任务真实的总额。无子代理时 subagentCount 为 0。
     *
     * 用递归 CTE 而不是单层 `parentId = ?`：子代理自己还能再派生子代理，
     * 单层查询会漏掉孙代理及其后代，总用量偏小（面板上表现为「总数少一截」）。
     */
    @Query(
        """
        WITH RECURSIVE descendants(id) AS (
            SELECT id FROM chat_sessions WHERE parentId = :parentSessionId
            UNION
            SELECT c.id FROM chat_sessions c
            JOIN descendants d ON c.parentId = d.id
        )
        SELECT COUNT(DISTINCT s.id) AS subagentCount,
               COALESCE(SUM(r.inputTokens), 0) AS promptTokens,
               COALESCE(SUM(r.cachedInputTokens), 0) AS cachedTokens,
               COALESCE(SUM(r.outputTokens), 0) AS outputTokens
        FROM chat_sessions s
        JOIN descendants d ON d.id = s.id
        LEFT JOIN llm_call_records r ON r.sessionId = s.id
        """
    )
    fun getSubagentSummary(parentSessionId: String): Flow<SubagentCallSummary>

    /**
     * 非增量未命中 token：**紧邻的上一次调用命中过缓存、本次却完全未命中**的输入量。
     *
     * 含义：前缀缓存本应继续命中却突然失效，通常是 TTL 过期、前缀被改动（编辑了早期消息）、
     * 或换了模型导致缓存键失效。这部分按全价计费，是最容易被忽视的浪费。
     *
     * 口径（四处修正，前三次修得都不够准）：
     * 1. 已限定 `cachedInputTokens = 0`，此时 `inputTokens - cachedInputTokens` 恒等于
     *    `inputTokens`，原写法的减法是无意义的。
     * 2. 不加 `inputTokens > 5000` 阈值：小额浪费也是浪费。
     * 3. 内层子查询也限定 `kind='chat'`：否则「某次压缩命中过缓存」会成为其后所有
     *    未命中调用的证据。
     * 4. **只看紧邻的上一条，而不是历史上任意一条**：这是关键修正。原口径下，
     *    一个会话最早期几百条「渠道根本不支持缓存」的调用，会在之后某次命中出现后
     *    全部被追溯认定为「浪费」——实测 510 条、2.17 亿 token，占输入 11.7%，
     *    而真相是那段时期缓存功能尚不可用，不存在「本应命中」。
     *    只有「上一条命中 → 这一条未命中」才是真正的失效点。
     */
    @Query(
        """
        SELECT COALESCE(SUM(r.inputTokens), 0)
        FROM llm_call_records r
        JOIN llm_call_records p
          ON p.sessionId = r.sessionId
         AND p.kind = 'chat'
         AND p.id = (
             SELECT MAX(x.id) FROM llm_call_records x
             WHERE x.sessionId = r.sessionId
               AND x.kind = 'chat'
               AND x.id < r.id
         )
        WHERE r.sessionId = :sessionId
          AND r.kind = 'chat'
          AND r.cachedInputTokens = 0
          AND p.cachedInputTokens > 0
        """
    )
    fun getNonIncrementalMissTokens(sessionId: String): Flow<Long>

    /** 会话内的 TTFT 样本数：用于区分「每次都慢」与「偶发一次慢」。 */
    @Query(
        """
        SELECT COUNT(*) FROM llm_call_records
        WHERE sessionId = :sessionId AND ttfbMillis IS NOT NULL AND kind = 'chat'
        """
    )
    fun getSessionTtfbSamples(sessionId: String): Flow<Int>

    /**
     * 会话内每次对话调用的时间线（按时间升序）。
     *
     * 供轮末统计行的「本轮明细」使用：消息表只存 token，首字延迟与耗时只在
     * llm_call_records 里有，UI 拿到按时间排序的调用点后用轮的时间窗（用户发送 → 轮末收工）
     * 切片聚合，比按消息 id 关联更稳——一次调用可能对应多条消息，也可能没有对应消息。
     */
    @Query(
        """
        SELECT createdAt AS createdAt,
               inputTokens AS inputTokens,
               outputTokens AS outputTokens,
               cachedInputTokens AS cachedInputTokens,
               ttfbMillis AS ttfbMillis,
               durationMillis AS durationMillis
        FROM llm_call_records
        WHERE sessionId = :sessionId AND kind = 'chat' AND status = 'success'
        ORDER BY createdAt ASC
        """
    )
    fun getSessionCallPoints(sessionId: String): Flow<List<SessionCallPoint>>

    /** 当前周期调用总数，供分页显示总页数。 */
    @Query(
        """
        SELECT COUNT(*) FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        """
    )
    fun getCallsCount(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<Int>

    /** 调用明细分页（倒序，[offset] 起取 [limit] 条），LEFT JOIN 渠道名。 */
    @Query(
        """
        SELECT r.*, p.name AS providerName
        FROM llm_call_records r
        LEFT JOIN ai_providers p ON p.id = r.providerId
        WHERE r.createdAt >= :start
          AND (:providerId IS NULL OR r.providerId = :providerId)
          AND (:model IS NULL OR r.model = :model)
        ORDER BY r.createdAt DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getRecentCalls(
        start: Long,
        limit: Int,
        offset: Int,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<RecentCallRecord>>
}
