package com.nox.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.nox.core.theme.PanelTokens

/**
 * 统计类浮层：把内容画在锚点旁边的悬浮卡片里，不参与页面布局。
 *
 * 为什么用 Popup 而不是内联展开：内联展开会把下方内容整体推走，用户正在看的位置
 * 被顶飞；长会话滚动到底部时尤其明显。Popup 浮在内容之上，展开与收起都不改变
 * 任何布局高度。
 *
 * 位置策略见 [AnchorPopupPositionProvider]：优先向上，上方不够才翻到下方。
 */
@Composable
fun AnchorPopup(
    /** 锚点在窗口中的矩形；未测量到（[IntRect.Zero]）时不显示。 */
    anchor: IntRect,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    if (anchor == IntRect.Zero) return
    val density = LocalDensity.current
    val positionProvider = remember(density) { AnchorPopupPositionProvider(density) }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        PanelCard { content() }
    }
}

/**
 * 浮层卡片外观：圆角、投影、描边、内边距与宽度区间统一取自 [PanelTokens]。
 *
 * 描边单独画在最上层而不是用 `border`：`border` 会占掉内部尺寸，
 * 0.5dp 在部分密度下会让内容偏移半像素。
 */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(PanelTokens.cardCorner),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .widthIn(min = PanelTokens.cardMinWidth, max = PanelTokens.cardMaxWidth)
            .shadow(
                elevation = PanelTokens.cardShadowRadius,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = PanelTokens.cardShadowAlpha),
                spotColor = Color.Black.copy(alpha = PanelTokens.cardShadowAlpha)
            )
            .background(MaterialTheme.colorScheme.surface, shape)
            .padding(horizontal = PanelTokens.cardPaddingH, vertical = PanelTokens.cardPaddingV),
        content = content
    )
}

/**
 * 锚点浮层定位：竖直方向优先放在锚点上方，上方装不下才翻到下方。
 *
 * 为什么优先向上：这类浮层几乎都从屏幕底部的消息统计行唤起，向下会被输入框遮挡；
 * 向上是常态，翻转只是兜底。水平方向左对齐锚点并夹在边距内。
 *
 * 与「先估算卡片高度再决定方向」的区别：这里用的是 Popup 拿到的**真实内容尺寸**，
 * 不会出现「估得小了结果展开后被窗口裁掉」。
 */
class AnchorPopupPositionProvider(
    density: Density
) : PopupPositionProvider {

    private val marginPx = with(density) { PanelTokens.popupMargin.roundToPx() }
    private val gapPx = with(density) { PanelTokens.popupAnchorGap.roundToPx() }

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val minX = marginPx
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(minX)
        val preferredX = if (layoutDirection == LayoutDirection.Rtl) {
            anchorBounds.right - popupContentSize.width
        } else {
            anchorBounds.left
        }
        val x = preferredX.coerceIn(minX, maxX)

        val aboveY = anchorBounds.top - popupContentSize.height - gapPx
        val belowY = anchorBounds.bottom + gapPx
        // 上方放得下就向上；否则若下方也放不下，仍取上方（内容多时优先保住靠近锚点的一侧）。
        val y = if (aboveY >= marginPx || belowY + popupContentSize.height > windowSize.height - marginPx) {
            aboveY.coerceAtLeast(marginPx)
        } else {
            belowY.coerceAtMost((windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx))
        }
        return IntOffset(x, y)
    }
}
