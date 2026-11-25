package com.nox.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单次工具执行记录：一次工具调用一条。
 *
 * 与 [LlmCallRecordEntity] 的分工：
 * - `llm_call_records` 记录**模型调用**（请求 → 响应），一次 API 请求一条；
 * - 本表记录**工具执行**（拿到工具参数 → 工具返回），发生在两次模型调用之间。
 *
 * 为什么必须单独埋点而不是反推：工具耗时此前用「相邻两次调用之间的空档」估算，
 * 那段空档包含网络往返、用户授权等待、甚至切走 App 的时间，不是工具真实执行耗时。
 * 只有执行起止的实测差值才是可信值。
 */
@Entity(
    tableName = "tool_execution_records",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["startedAt"])
    ]
)
data class ToolExecutionRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String? = null,
    /** 该工具调用所属的助手消息 id：用于把耗时归到具体轮次。 */
    val messageId: String? = null,
    /** 模型给出的 tool_call id，便于与工具结果对齐。 */
    val toolCallId: String? = null,
    val toolName: String? = null,
    /** 工具开始执行的时刻（epoch 毫秒）。 */
    val startedAt: Long,
    /** 工具执行耗时（毫秒），由单调时钟测得，不受系统时间跳变影响。 */
    val durationMillis: Long,
    /** success / error。 */
    val status: String = "success",
    val errorMessage: String? = null
)
