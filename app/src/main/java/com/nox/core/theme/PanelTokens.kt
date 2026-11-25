package com.nox.core.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 浮层卡片与统计面板的设计令牌。
 *
 * 为什么单独抽一层：这些卡片有大量**精确的小尺寸**（13sp 标题 / 12sp 正文 /
 * 0.5dp 分隔线 / 14dp 图标），散落在各个 Composable 里手写会立刻失控——
 * 同一个面板里出现 12sp/13sp/15sp 三种正文、0.5dp 和 1dp 两种分隔线，
 * 视觉上就是「拼凑感」的来源。集中在这里之后，调用方只引用名字，改一处全站生效。
 *
 * 与 [Spacing] 的分工：[Spacing] 是全局页面级间距（4/8/12/16/24/32），
 * 本对象是**卡片内部**的紧凑尺寸，两者不可互换使用。
 */
object PanelTokens {

    // ── 浮层卡片 ────────────────────────────────────────────────────────────
    /** 浮层卡片圆角。比页面级 [Radius.lg] 略大，浮层边界更柔和。 */
    val cardCorner = Radius.lg
    /** 卡片内边距：横向 16dp、纵向 14dp。纵向略小是因为标题行本身有行高留白。 */
    val cardPaddingH = 16.dp
    val cardPaddingV = 14.dp
    /** 卡片宽度区间：窄屏不挤，宽屏也不铺满整行。 */
    val cardMinWidth = 256.dp
    val cardMaxWidth = 328.dp
    /** 浮层与窗口边缘的最小留白。 */
    val popupMargin = 12.dp
    /** 浮层与锚点之间的垂直间隙。 */
    val popupAnchorGap = 8.dp
    /** 浮层投影。 */
    val cardShadowRadius = 16.dp
    const val cardShadowAlpha = 0.12f
    /** 浮层描边宽度，比常规边框更细，只用来看清边界。 */
    val cardBorderWidth = 0.5.dp

    // ── 分组标题 ────────────────────────────────────────────────────────────
    /** 分组图标边长。比页面级图标小，避免在紧凑卡片里显得笨重。 */
    val sectionIconSize = 14.dp
    /** 图标与标题之间的间距。 */
    val sectionIconGap = 6.dp
    /** 分组标题字号 / 行高。 */
    val sectionTitleSize = 13.sp
    val sectionTitleLine = 18.sp
    /** 分组标题字重：Medium，比正文略重但不至于抢视线。 */
    val sectionTitleWeight = FontWeight.Medium
    /** 标题右侧补充信息：与标题同字号，数字用等宽。 */
    val sectionTrailingSize = 13.sp
    /** 标题与右侧补充信息之间的最小间距。 */
    val sectionTrailingGap = 16.dp

    // ── 分隔线 ──────────────────────────────────────────────────────────────
    /** 分隔线粗细。0.5dp 在 2x 屏会被抗锯齿抹成半透明而看不见，
     *  0.75dp 在 2x/3x 上都稳定落在 1~2 个物理像素，既细又可见。 */
    val dividerHeight = 0.75.dp
    /** 分隔线上下留白：上紧下松，让下一组内容与标题的关系更近。 */
    val dividerPadTop = 8.dp
    val dividerPadBottom = 10.dp
    /** 分隔线颜色透明度：过重会割裂卡片，过淡看不出分组。 */
    const val dividerAlpha = 0.7f

    // ── 键值行 ──────────────────────────────────────────────────────────────
    /** 键值行标签字号 / 行高。与分组标题形成 12 / 13 两级层级。 */
    val rowLabelSize = 12.sp
    val rowLine = 18.sp
    /** 键值行数值字号：与标签同字号，靠颜色与等宽数字区分主次。 */
    val rowValueSize = 12.sp
    /** 数值颜色透明度。纯黑过重，0.82 保留可读性同时弱于标题。 */
    const val rowValueAlpha = 0.82f
    /** 标签与数值之间的最小间距。 */
    val rowGap = 16.dp
    /** 相邻两行之间的垂直间距（行内上下各取一半）。 */
    val rowSpacing = 6.dp
    /** 数值统一使用等宽数字，纵向对比时小数点能对齐。 */
    const val rowValueFeature = "tnum"

    // ── 操作按钮 ────────────────────────────────────────────────────────────
    /**
     * 面板内主操作按钮高度。
     *
     * 比 Material 默认 40dp 矮一档：这是信息面板，按钮太高压过数据本身；
     * 也不宜更矮，低于 28dp 在触屏上容易点不中。
     */
    val actionButtonHeight = 30.dp
    /** 按钮圆角取高度的一半，两端成胶囊形。 */
    val actionButtonCorner = 15.dp

    // ── 组间距 ──────────────────────────────────────────────────────────────
    /** 组与组之间的垂直间距。比行间距大一档，形成清晰的分组节奏。 */
    val groupSpacing = 14.dp
}
