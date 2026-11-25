package com.nox.feature.agent.presentation.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 统计面板专用的分组图标。
 *
 * 这三个图标是 16×16 画布、1.35dp 描边，与项目其他位置使用的 24×24/2dp 图标体系不同：
 * 面板是紧凑卡片，图标过大或过粗会压过文字。集中在此定义，渲染时由调用方指定尺寸，
 * 避免各处手绘导致线宽与画布不统一。
 */
internal object PanelIcons {

    /** 描边宽度（16×16 画布下）。1.35 在紧凑卡片里既不糊也不抢视线。 */
    private const val STROKE = 1.35f

    /**
     * Token 用量：堆叠的存储柱体（顶部椭圆 + 两侧竖线 + 中底弧线）。
     * 语义是「累计存储量」，比硬盘或数据库图标更贴使用量统计。
     */
    val TokenUsage: ImageVector by lazy {
        icon("PanelTokenUsage") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // 顶部椭圆
                moveTo(13.8f, 3.8f)
                arcTo(5.8f, 2.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 2.2f, 3.8f)
                arcTo(5.8f, 2.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 13.8f, 3.8f)
                // 两侧竖线与中底弧线
                moveTo(2.2f, 3.8f)
                verticalLineTo(11.8f)
                arcTo(5.8f, 2.2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 13.8f, 11.8f)
                verticalLineTo(3.8f)
                moveTo(2.2f, 7.8f)
                arcTo(5.8f, 2.2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 13.8f, 7.8f)
            }
        }
    }

    /**
     * 会话统计：仪表盘外弧 + 指针 + 中心轴承圆点。
     * 仪表盘表达「本会话的整体表现」，比时钟更贴统计语义。
     */
    val SessionStats: ImageVector by lazy {
        icon("PanelSessionStats") {
            // 仪表盘外弧（从左下 135° 顺时针到右下 45°）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3.4f, 13.1f)
                arcTo(
                    horizontalEllipseRadius = 6.5f,
                    verticalEllipseRadius = 6.5f,
                    theta = 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    x1 = 12.6f,
                    y1 = 13.1f
                )
            }
            // 指针
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(8.0f, 8.5f)
                lineTo(11.5f, 5.0f)
            }
            // 中心轴承圆点
            path(fill = SolidColor(Color.Black)) {
                moveTo(9.2f, 8.5f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 6.8f, 8.5f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9.2f, 8.5f)
                close()
            }
        }
    }

    /**
     * 自动压缩：上下相对的双向折叠箭头，表示「向内收缩」。
     * 与上面两个图标同一画布与线宽，不引入 24×24 体系以免粗细不一。
     */
    val Compact: ImageVector by lazy {
        icon("PanelCompact") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // 上箭头：向内收擢
                moveTo(5.0f, 6.0f)
                lineTo(8.0f, 3.4f)
                lineTo(11.0f, 6.0f)
                // 下箭头：同为向内收擢
                moveTo(5.0f, 10.0f)
                lineTo(8.0f, 12.6f)
                lineTo(11.0f, 10.0f)
            }
        }
    }

    private inline fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 16f,
            viewportHeight = 16f
        ).apply(block).build()
}
