package com.nox.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
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
    // 用调用方测得的锚点矩形定位：系统给 PopupPositionProvider 的 anchorBounds
    // 是宿主容器的矩形，不是触发控件的矩形，直接拿来算会与控件位置无关。
    val positionProvider = remember(density, anchor) {
        AnchorPopupPositionProvider(density, anchor)
    }
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
 *
 * **必须给高度封顶并允许滚动**：面板内容行数会随会话变长而增加（实测已到
 * 1915px，占窗口 2273px 的 84%，上下都放不下，只能被强制顶到屏幕边缘，
 * 与触发它的那一行隔了大半屏）。
 *
 * 上限取「可用屏高 − 上下留白」而不是固定比例：比例式上限（试过 70%）
 * 会把本来放得下的内容也裁掉——实测 70% = 1591px，而内容需要 1915px，
 * 结果底部「自动压缩」的滑杆、说明和按钮被切成两半。
 * 只减掉两条 margin 后上限 ≈ 2205px，1915px 的内容能完整放下；
 * 真的超长时（会话再翻一倍）才需要内部滚动。
 */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(PanelTokens.cardCorner),
    content: @Composable ColumnScope.() -> Unit
) {
    // 上限 = 可用屏高 − 上下两条留白。按 dp 算而不是取像素，
    // 平板/折叠屏/分屏下屏幕高度差别很大，比例式会误裁内容。
    val maxCardHeight = with(LocalConfiguration.current) {
        (screenHeightDp.dp - PanelTokens.popupMargin * 2)
    }
    Column(
        modifier = modifier
            .widthIn(min = PanelTokens.cardMinWidth, max = PanelTokens.cardMaxWidth)
            // 高度封顶（可用屏高的 70%）：留出上下各 15%，让卡片无论向上还是向下
            // 都能贴住锚点，而不是被迫顶到屏幕边缘。超出部分内部滚动。
            .heightIn(max = maxCardHeight)
            .verticalScroll(rememberScrollState())
            .shadow(
                elevation = PanelTokens.cardShadowRadius,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = PanelTokens.cardShadowAlpha),
                spotColor = Color.Black.copy(alpha = PanelTokens.cardShadowAlpha)
            )
            .background(MaterialTheme.colorScheme.surface, shape)
            .padding(
                start = PanelTokens.cardPaddingH,
                end = PanelTokens.cardPaddingH,
                top = PanelTokens.cardPaddingTop,
                bottom = PanelTokens.cardPaddingBottom
            ),
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
    density: Density,
    /**
     * 真正要贴住的目标矩形（窗口坐标系），由调用方用 `boundsInWindow()` 测得。
     *
     * **必须传进来，不能依赖 [calculatePosition] 的 `anchorBounds`**：
     * 那个参数是 Popup 宿主容器在窗口中的矩形，不是触发控件的矩形。
     * 肉眼可见的后果：输入栏里的用量按钮，宿主是整个输入栏
     * （实测 (0,1846,1080,2400)），拿它当锚点算出来的位置与按钮毫无关系；
     * 消息统计行的浮层则拿到整条消息内容的矩形（top 可能是大负数）。
     */
    private val target: IntRect
) : PopupPositionProvider {

    private val marginPx = with(density) { PanelTokens.popupMargin.roundToPx() }
    private val gapPx = with(density) { PanelTokens.popupAnchorGap.roundToPx() }

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        // 用调用方测得的真实目标矩形；未测量到（Zero）时退回系统给的宿主矩形。
        val ab = if (target == IntRect.Zero) anchorBounds else target
        val minX = marginPx
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(minX)
        val preferredX = if (layoutDirection == LayoutDirection.Rtl) {
            ab.right - popupContentSize.width
        } else {
            ab.left
        }
        val x = preferredX.coerceIn(minX, maxX)

        // 锚点可能已经滚出屏幕（LazyColumn 回收后 onGloballyPositioned 不再更新，
        // 残留的矩形 top/bottom 会变成大负数或超出窗口）。此时「贴着锚点」已无意义：
        // 硬算 aboveY 会得到 -1573 这样的值，再被 margin 钳到顶部，
        // 卡片就贴着屏幕顶端、与用户点的那一行隔了大半屏。
        // 处理：把锚点夹回可视区，贴住离它最近的那条边。
        val visibleTop = ab.top.coerceIn(marginPx, windowSize.height - marginPx)
        val visibleBottom = ab.bottom.coerceIn(marginPx, windowSize.height - marginPx)
        // 只有「锚点整体跑到可视区上方」才算真正出屏。
        // 不能把 bottom > windowHeight 当作出屏：位于屏幕底部的统计行，其
        // boundsInWindow().bottom 常常正好等于屏幕物理高度（2400），
        // 而 Popup 的 windowSize.height 是去掉导航栏的可用高度（2273）。
        // 把它误判成出屏，卡片会被强制贴底/贴顶，反而离锚点更远。
        val anchorIsOffScreen = ab.top < 0

        val aboveY = visibleTop - popupContentSize.height - gapPx
        val belowY = visibleBottom + gapPx
        android.util.Log.d(
            "AnchorPopup",
            "anchor=$ab visible=($visibleTop,$visibleBottom) offScreen=$anchorIsOffScreen " +
                "window=$windowSize popup=$popupContentSize aboveY=$aboveY belowY=$belowY margin=$marginPx"
        )
        // 上方放得下就向上；否则若下方也放不下，仍取上方（内容多时优先保住靠近锚点的一侧）。
        // 锚点在屏幕外时不选边，直接贴住它所在的那一侧：
        // 在屏幕上方就贴顶部，在下方就贴底部，保证卡片出现在用户视线的同一侧。
        val y = if (anchorIsOffScreen) {
            if (ab.top < 0) marginPx
            else (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
        } else if (aboveY >= marginPx || belowY + popupContentSize.height > windowSize.height - marginPx) {
            aboveY.coerceAtLeast(marginPx)
        } else {
            belowY.coerceAtMost((windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx))
        }
        return IntOffset(x, y)
    }
}
