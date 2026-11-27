package com.nox.feature.agent.presentation.component

// ui-tokens-exempt: 上游新引入文件（Nox 5c1a494 消息导航器），字号尚未迁移到 PanelTokens（2 处）。
// 保持与上游一致便于后续合并；迁移时按信息密度选档位，不要机械替换成最小值。
// 迁移完成前本文件不参与 UI 门禁检查。

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nox.core.theme.semanticColors
import com.nox.feature.agent.presentation.AIAgentViewModel
import com.nox.feature.agent.presentation.AgentUIMessage
import com.nox.feature.settings.data.repository.ChatBubbleStyle
import kotlin.math.abs

/** 单个槽位高度。按放大后的中间项（四行 × 17sp）留足空间，上下两条才不拥挤。 */
private val NAV_SLOT = 100.dp

/** 同时露出的气泡数：中间待选 + 上下各两条。 */
private const val NAV_VISIBLE = 5

/** 预览字符上限。 */
private const val NAV_PREVIEW_CHARS = 600

/** 中心判定：离视口中心半个槽位以内视为居中，此时放大并展开多行。 */
private const val NAV_CENTER_SLOTS = 0.5f

/**
 * 消息导航器：聊天区右缘竖排三点，长按弹出一列「我发过的消息」气泡。
 *
 * 数据取整个会话的用户消息（不走聊天列表分页），滚动交给 LazyColumn 原生实现——
 * 原生 fling、原生吸附、原生惯性，手感与列表一致；自己算偏移的版本再优化也不如它跟手。
 */
@Composable
internal fun MessageNavigatorLayer(
    viewModel: AIAgentViewModel,
    /** 消息列表是否超出视口；不够一屏时没有可导航的历史。 */
    scrollable: Boolean,
    /** 聊天视口顶部那条消息的 id，用于决定展开时停在哪一条附近。 */
    anchorMessageId: String?,
    modifier: Modifier = Modifier
) {
    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()
    val sessionId = messagesState.sessionId

    // 所有状态与副作用都在提前 return 之前创建，避免条件分支导致 remember 顺序错乱
    var expanded by remember { mutableStateOf(false) }
    var userMessages by remember(sessionId) { mutableStateOf(emptyList<AgentUIMessage>()) }
    LaunchedEffect(sessionId, expanded) {
        if (expanded && sessionId != null) {
            userMessages = viewModel.loadUserMessagesForNavigator(sessionId)
        }
    }

    // 按时间戳定位：锚点可能是助手回复或工具行，落到最近的一条用户消息
    val startIndex = remember(anchorMessageId, userMessages, messagesState.messages) {
        val anchorTimestamp = anchorMessageId
            ?.let { id -> messagesState.messages.firstOrNull { it.id == id }?.timestamp }
        if (anchorTimestamp == null) {
            userMessages.lastIndex.coerceAtLeast(0)
        } else {
            userMessages.indexOfLast { it.timestamp <= anchorTimestamp }.coerceAtLeast(0)
        }
    }

    // 把手只在确实有可翻的历史时出现
    if (!scrollable || sessionId == null || messagesState.messages.isEmpty()) return

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        NavigatorHandle(onOpen = { expanded = true })
    }

    if (expanded && userMessages.isNotEmpty()) {
        val activeSessionId = sessionId
        NavigatorOverlay(
            messages = userMessages,
            startIndex = startIndex,
            onJump = { messageId ->
                expanded = false
                viewModel.scrollToMessageInSession(activeSessionId, messageId)
            },
            onDismiss = { expanded = false }
        )
    }
}

/** 右缘的三点把手。只有三个点，不铺底色；触摸区比点本身大一圈，长按好按。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavigatorHandle(onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .width(30.dp)
            .height(72.dp)
            .semantics { contentDescription = "消息导航，长按展开" }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onOpen
            ),
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.semanticColors.subtleText)
            )
        }
    }
}

/**
 * 悬浮的气泡列。
 *
 * 高度固定五个槽位，上下各留两个槽位的内边距——这样任意一条滚到中间时都正好停在
 * 视口中心，同时上下各露出两条。滚动用 LazyColumn 原生实现并开启吸附。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavigatorOverlay(
    messages: List<AgentUIMessage>,
    startIndex: Int,
    onJump: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = startIndex.coerceIn(0, messages.lastIndex),
        initialFirstVisibleItemScrollOffset = 0
    )

    BackHandler(onBack = onDismiss)

    // 视口中心最近的那条即待选；只遍历可见项，历史再长也是常数开销
    val centerIndex by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo.minByOrNull { item ->
                abs(item.offset + item.size / 2f - viewportCenter)
            }?.index ?: -1
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .width(250.dp)
                .height(NAV_SLOT * NAV_VISIBLE.toFloat()),
            contentPadding = PaddingValues(vertical = NAV_SLOT * 2f),
            verticalArrangement = Arrangement.Top
        ) {
            itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                NavigatorBubble(
                    message = message,
                    selected = index == centerIndex,
                    onClick = { onJump(message.id) }
                )
            }
        }
    }
}

/**
 * 单条气泡。
 *
 * 外观取宿主当前的用户气泡样式（颜色、描边、圆角走同一套取色函数），只是整体缩小。
 * 居中那条明显放大并展开四行，两侧保持较小且单行，视觉层次一眼能分辨。
 */
@Composable
private fun NavigatorBubble(
    message: AgentUIMessage,
    selected: Boolean,
    onClick: () -> Unit
) {
    val preview = remember(message.id) { message.content.toPreview() }

    val bubbleStyle = LocalChatBubbleStyle.current
    val bubbleColor = chatUserBubbleColor()
    val borderColor = chatUserBubbleBorderColor()
    val textColor = chatUserBubbleTextColor()
    val shape = RoundedCornerShape(
        com.nox.feature.plugin.dsl.LocalPluginUiStyle.current.bubbleRadius?.dp ?: when (bubbleStyle) {
            ChatBubbleStyle.SQUARE -> 2.dp
            ChatBubbleStyle.MINIMAL, ChatBubbleStyle.GREEN -> 16.dp
            else -> ChatStyle.bubbleCorner
        }
    )

    // 基准尺寸就是原尺寸；只把居中那条放大，两侧不缩
    val scale = if (selected) 1.18f else 1f
    val alpha = if (selected) 1f else 0.82f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(NAV_SLOT),
        contentAlignment = Alignment.CenterEnd
    ) {
        Surface(
            shape = shape,
            color = bubbleColor,
            border = borderColor.takeIf { it != Color.Transparent }
                ?.let { BorderStroke(if (bubbleStyle == ChatBubbleStyle.CLASSIC) 1.4.dp else 1.2.dp, it) },
            modifier = Modifier
                // 左右内边距放在缩放外；缩放以中心为轴，右边缘会随缩放变化，
                // 因此用 transformOrigin 钉在右边，保证所有气泡右对齐
                .padding(end = 10.dp)
                .widthIn(max = 220.dp)
                .graphicsLayer {
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .then(
                    if (bubbleStyle == ChatBubbleStyle.SHADOW) {
                        Modifier.shadow(elevation = 4.dp, shape = shape, clip = false)
                    } else Modifier
                )
                .clickable(onClick = onClick)
        ) {
            Text(
                text = preview,
                maxLines = if (selected) 4 else 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                color = textColor,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
            )
        }
    }
}

/** 预览文本：换行与连续空白压成单空格，超长截断，保证单条不会无限长。 */
private fun String.toPreview(): String {
    val flat = buildString(length.coerceAtMost(NAV_PREVIEW_CHARS * 2)) {
        var lastWasSpace = false
        for (char in this@toPreview) {
            val isSpace = char.isWhitespace()
            if (isSpace) {
                if (!lastWasSpace) append(' ')
            } else {
                append(char)
            }
            lastWasSpace = isSpace
            if (length > NAV_PREVIEW_CHARS) break
        }
    }.trim()
    return if (flat.length > NAV_PREVIEW_CHARS) flat.take(NAV_PREVIEW_CHARS) + "…" else flat
}
