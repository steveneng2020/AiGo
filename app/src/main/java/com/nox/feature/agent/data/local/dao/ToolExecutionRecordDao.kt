package com.nox.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.nox.feature.agent.data.local.entity.ToolExecutionRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * 工具执行记录读写。
 *
 * 只提供「写入一条」与「按会话/消息聚合耗时」两类操作：
 * 面板需要的是**实测总耗时**，不需要逐条明细，聚合查询保持轻量。
 */
@Dao
interface ToolExecutionRecordDao {

    @Insert
    suspend fun insert(record: ToolExecutionRecordEntity): Long

    /**
     * 本会话所有工具执行的累计耗时（毫秒）。
     *
     * 这与「相邻两次模型调用之间的空档」不同：空档包含网络往返与用户授权等待，
     * 只有工具自身的执行起止差值才是真实耗时。
     */
    @Query("SELECT COALESCE(SUM(durationMillis), 0) FROM tool_execution_records WHERE sessionId = :sessionId")
    fun getSessionToolMillis(sessionId: String): Flow<Long>

    /** 与 [getSessionToolMillis] 相同的口径，供 suspend 场景一次性取值。 */
    @Query("SELECT COALESCE(SUM(durationMillis), 0) FROM tool_execution_records WHERE sessionId = :sessionId")
    suspend fun getSessionToolMillisOnce(sessionId: String): Long

    /** 指定消息（一轮）内的工具耗时累计。 */
    @Query("SELECT COALESCE(SUM(durationMillis), 0) FROM tool_execution_records WHERE messageId = :messageId")
    fun getMessageToolMillis(messageId: String): Flow<Long>

    @Query("SELECT COALESCE(SUM(durationMillis), 0) FROM tool_execution_records WHERE messageId = :messageId")
    suspend fun getMessageToolMillisOnce(messageId: String): Long

    /**
     * 本会话的工具执行时间线：只取「开始时刻与耗时」，供 UI 按轮时间区间切分。
     *
     * 面板需要把工具耗时归到具体某一轮，而轮次边界来自消息时间戳，
     * 因此这里返回原始区间数据，切分逻辑放在展示层。
     */
    @Query(
        "SELECT startedAt, durationMillis FROM tool_execution_records " +
            "WHERE sessionId = :sessionId ORDER BY startedAt ASC"
    )
    fun getSessionToolTimeline(sessionId: String): Flow<List<ToolTimePoint>>

    /** 本会话工具调用次数（用于「N 次工具调用」这类补充信息）。 */
    @Query("SELECT COUNT(*) FROM tool_execution_records WHERE sessionId = :sessionId")
    fun getSessionToolCallCount(sessionId: String): Flow<Int>
}

/** 工具执行时间线上的一点：开始时刻 + 耗时。 */
data class ToolTimePoint(
    val startedAt: Long,
    val durationMillis: Long
)
