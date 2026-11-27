package com.nox.feature.agent.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nox.feature.agent.presentation.AgentAttachment
import com.nox.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import com.nox.feature.agent.presentation.COMPACTION_FAILURE_TOOL_NAME
import com.nox.feature.agent.presentation.MessageRole
import com.nox.feature.agent.presentation.AgentUIMessage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Entity(
    tableName = "agent_messages",
    indices = [Index(value = ["sessionId"])]
)
data class AgentMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    // 仅 ASSISTANT 行：结构化的 tool_calls（Json 编码的 List<ToolCall>），无工具调用时为 null。
    val toolCallsJson: String? = null,
    // 仅 TOOL 行：对应 assistant tool_use 的 id，用于回放时重建合法配对。
    val toolCallId: String? = null,
    // 仅 TOOL 行：工具名，用于 UI 渲染。
    val toolName: String? = null,
    // 仅 TOOL 行：本次调用传入的参数（argsPreview，JSON 文本），用于 UI 显示「执行的指令」。
    val toolArgs: String? = null,
    // 仅 TOOL 行：工具是否执行失败，用于 UI 渲染状态圆点（绿/红）。
    val isError: Boolean = false,
    // 仅 ASSISTANT 行：本轮模型的思考过程（reasoning）。供 UI 持久化展示；Anthropic 开启 thinking 时随 signature 一起原样回传。无则为 null。
    val reasoning: String? = null,
    // 仅 ASSISTANT 行：Anthropic extended thinking 的加密签名。与 reasoning 一起回传（工具循环必须），否则 400。其他 provider 为 null。
    val signature: String? = null,
    // 仅 USER 行：用户上传附件的展示元数据。内部模型提示仍不落入 content。
    val attachmentsJson: String? = null,
    /** 该消息已被上下文压缩归入摘要，不应再参与上下文回放或 UI 展示。默认 false。 */
    val isCompacted: Boolean = false,
    /** 上下文压缩生成的内部摘要：参与模型回放，但不作为普通聊天气泡展示。 */
    val isContextSummary: Boolean = false,
    /** 上下文压缩生成的内部用户锚点：参与模型回放，UI 渲染为压缩分隔线。 */
    val isCompactionMarker: Boolean = false,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    // 仅 ASSISTANT 行：Anthropic thinking / redacted_thinking 内容块的原样快照（JSON 数组文本）。
    // 文档要求这些块回传时不得修改且保持原序，故存原块而不拆字段（redacted 的 data 不可重建）。
    // 位置在末尾：备份 DTO 映射按位置参数，插到中间会错位。
    val thinkingBlocksJson: String? = null,
    // 仅 ASSISTANT 行：本轮输入中命中服务端缓存的 token 数，UI 据此显示缓存命中率。同样只能追加在末尾。
    val cachedInputTokens: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val isContextExcluded: Boolean = false,
    val compactedBySummaryId: String? = null,
    /** 用户插话（AI 运行中补充的要求）：气泡内需标识与普通对话区分，默认 false。 */
    @ColumnInfo(defaultValue = "0")
    val isUserInterjection: Boolean = false,
    /**
     * 仅 ASSISTANT 行：本次调用的首 token 耗时（ms）。0 或 null 表示该渠道没报。
     *
     * 为什么存进消息表而不是只读 llm_call_records：
     * 气泡底部的统计行左右两半原本来自两张表——token 随消息落库（同步，立刻可见），
     * 首 token 与输出速度只能等 workflow 在 finally 里 insert 调用记录。
     * 两个写库动作时机不同，于是出现「左边已显示、右边空着，过几秒才补上」的闪动。
     * 把这两个值随消息一起写，左右同源同步，天然同时出现。
     *
     * llm_call_records 仍是统计页/面板的数据源，两边各自独立、互为校验。
     */
    // 不加 defaultValue：可空列必须与迁移里的 `DEFAULT NULL` 一致。
    // 写成 defaultValue = "0" 会让 Room 校验期望 `DEFAULT 0`，
    // 与实际表结构（DEFAULT NULL）不符，启动时直接 IllegalStateException。
    val ttfbMillis: Long? = null,
    /** 仅 ASSISTANT 行：本次调用的总耗时（ms），用于算输出速度。口径同 [ttfbMillis]。 */
    val durationMillis: Long? = null
) {
    fun toUIMessage(): AgentUIMessage {
        val roleEnum = MessageRole.valueOf(role)
        return AgentUIMessage(
            id = id,
            role = roleEnum,
            content = content,
            timestamp = timestamp,
            toolName = toolName,
            toolArgs = toolArgs,
            isError = isError,
            reasoning = reasoning,
            attachments = decodeAttachments(attachmentsJson),
            isCompactionMarker = isCompactionMarker,
            isContextSummary = isContextSummary,
            isCompactionFailure = roleEnum == MessageRole.TOOL && toolName == COMPACTION_FAILURE_TOOL_NAME,
            isBackgroundNotification = roleEnum == MessageRole.USER &&
                content.startsWith(BACKGROUND_NOTIFICATION_PREFIX),
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cachedInputTokens = cachedInputTokens,
            isUserInterjection = isUserInterjection,
            ttfbMillis = ttfbMillis,
            durationMillis = durationMillis
        )
    }

    private fun decodeAttachments(value: String?): List<AgentAttachment> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<AgentAttachment>>(value) }.getOrDefault(emptyList())
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
