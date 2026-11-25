package com.nox.feature.agent.presentation.component

// ui-tokens-exempt: 存量文件，字号尚未迁移到 PanelTokens（1 处）。
// 迁移时按界面的信息密度选择令牌档位，不要机械替换成最小值；
// 迁移完成前本文件不参与 UI 门禁检查。

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nox.core.theme.Spacing
import com.nox.feature.settings.data.repository.ChatBubbleStyle
import androidx.compose.ui.res.stringResource
import com.nox.R

/**
 * 用户消息气泡：按当前所选样式绘制外观。
 *
 * 同一段文字在不同样式下共享排版（14sp / 20sp 行高），只有圆角、底色、描边和
 * 装饰元素不同，因此切换样式不会改变消息区的垂直节奏。
 */
@Composable
internal fun UserMessageBubble(
    text: String,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    /** 加粗显示：用户运行中插话的原文用它强调，让用户一眼确认内容被完整看到。 */
    emphasized: Boolean = false,
    /** 用户插话：气泡首行加「用户中途插话，具体内容如下：」标识，与普通对话区分。 */
    interjection: Boolean = false
) {
    val style = LocalChatBubbleStyle.current
    val bubbleColor = chatUserBubbleColor()
    val borderColor = chatUserBubbleBorderColor()
    val textColor = chatUserBubbleTextColor()

    val shape = RoundedCornerShape(
        when (style) {
            ChatBubbleStyle.SQUARE -> 2.dp
            ChatBubbleStyle.MINIMAL, ChatBubbleStyle.GREEN -> 16.dp
            else -> ChatStyle.bubbleCorner
        }
    )

    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            if (interjection) {
                // 标识行用主色 + 小字号：一眼能看出这条不是普通提问，但不抢正文的注意力。
                Text(
                    text = stringResource(R.string.chat_interjection_header),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(Spacing.xs))
            }
            SelectionContainer {
                CompositionLocalProvider(
                    LocalTextSelectionColors provides TextSelectionColors(
                        handleColor = MaterialTheme.colorScheme.primary,
                        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                    )
                ) {
                    Text(
                        text = text,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                        fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }

    Box(modifier = modifier) {
        when (style) {
            // 方块样式：墨线方格 + 右侧三角箭头，漫画对话框观感。
            ChatBubbleStyle.SQUARE -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .widthIn(max = maxWidth)
                        .clip(shape)
                        .background(bubbleColor)
                        .border(1.dp, borderColor, shape)
                ) { content() }
                Box(
                    modifier = Modifier
                        .size(width = 6.dp, height = 8.dp)
                        .drawBehind {
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(0f, size.height * 0.15f)
                                lineTo(size.width, size.height / 2)
                                lineTo(0f, size.height * 0.85f)
                                close()
                            }
                            drawPath(path, borderColor)
                        }
                )
            }

            // 心动时刻样式：灰底描边，不加底部装饰。
            ChatBubbleStyle.VALENTINE -> Box(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .clip(shape)
                    .background(bubbleColor)
                    .border(1.dp, borderColor, shape)
            ) { content() }

            // 其余样式统一走 Surface：投影/描边/纯净由 color 与 border 表达。
            else -> Surface(
                shape = shape,
                color = bubbleColor,
                border = borderColor.takeIf { it != Color.Transparent }
                    ?.let { BorderStroke(if (style == ChatBubbleStyle.CLASSIC) 1.8.dp else 1.5.dp, it) },
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .then(
                        if (style == ChatBubbleStyle.SHADOW) {
                            Modifier.shadow(elevation = 4.dp, shape = shape, clip = false)
                        } else Modifier
                    )
            ) { content() }
        }
    }
}
