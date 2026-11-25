package com.nox.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.nox.core.theme.PanelTokens

/**
 * 统计面板的分组容器：**图标 + 标题 + 分隔线 + 内容**四件套一体。
 *
 * 为什么要包成组件而不是让每处自己拼：分组视觉由三件事共同决定——图标定位置、
 * 标题定层级、分隔线定边界。少任何一件，卡片就会退化成「一片灰字」；
 * 而三件套分散写时，漏掉分隔线或改小字号这类问题**在代码审查里看不出来**，
 * 只能在真机上才发现「怎么没有分组感」。
 *
 * 调用方只需给图标、标题和内容，尺寸与颜色全部取自 [PanelTokens]，无法写歪。
 */
@Composable
fun PanelSection(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    /** 右侧补充信息是否强调（如阈值百分比）。 */
    trailingEmphasis: Boolean = false,
    /** 首个分组上方不需要额外间距：由调用方控制组间距离。 */
    topSpacing: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (topSpacing > 0.dp) Spacer(Modifier.height(topSpacing))
        PanelSectionHeader(
            icon = icon,
            title = title,
            trailing = trailing,
            trailingEmphasis = trailingEmphasis
        )
        PanelSectionDivider()
        Column(verticalArrangement = Arrangement.spacedBy(PanelTokens.rowSpacing), content = content)
    }
}

/** 分组标题行：独立暴露，供需要自定义内容排布的场景复用。 */
@Composable
fun PanelSectionHeader(
    icon: ImageVector,
    title: String,
    trailing: String? = null,
    /** 右侧信息是否强调（如阈值百分比）。 */
    trailingEmphasis: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 图标与标题必须包在同一个子 Row 里：若三者为平级子项，
        // SpaceBetween 会把图标和标题也拉开，视觉上图标就飘到左边孤零零一个。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(PanelTokens.sectionIconSize)
            )
            Spacer(Modifier.width(PanelTokens.sectionIconGap))
            Text(
                text = title,
                style = TextStyle(
                    fontSize = PanelTokens.sectionTitleSize,
                    lineHeight = PanelTokens.sectionTitleLine,
                    fontWeight = PanelTokens.sectionTitleWeight
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!trailing.isNullOrBlank()) {
            Spacer(Modifier.width(PanelTokens.sectionTrailingGap))
            Text(
                text = trailing,
                style = TextStyle(
                    fontSize = PanelTokens.sectionTrailingSize,
                    lineHeight = PanelTokens.sectionTitleLine,
                    fontFeatureSettings = PanelTokens.rowValueFeature,
                    fontWeight = if (trailingEmphasis) PanelTokens.sectionTitleWeight
                    else androidx.compose.ui.text.font.FontWeight.Normal
                ),
                color = if (trailingEmphasis) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = PanelTokens.rowValueAlpha)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1
            )
        }
    }
}

/**
 * 分组分隔线。粗细与上下留白取自 [PanelTokens]。
 *
 * 颜色不能用 `outlineVariant`：浅色主题里它是 `#EBEBED`，在白色卡片上
 * 即使不透明也几乎看不出，叠加透明度后彻底消失。这里用 `onSurface` 带低透明度，
 * 深浅主题都能得到「比背景深一点点」的线，这才是分隔线该有的对比度。
 */
@Composable
fun PanelSectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = PanelTokens.dividerPadTop, bottom = PanelTokens.dividerPadBottom)
            .height(PanelTokens.dividerHeight)
            .background(
                MaterialTheme.colorScheme.onSurface.copy(
                    // 深色主题下 onSurface 是近白，同样的透明度在浅底上更显眼，
                    // 用主题亮度判断分别给值，避免一边看不清另一边太重。
                    alpha = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) 0.12f else 0.20f
                )
            )
    )
}

/**
 * 键值行：标签靠左，`数值 + 单位` 作为一个整体贴行尾，单位右边缘即列右边缘。
 *
 * 曾经的写法是 5 个平级子项（标签/间隔/数值/间隔/单位）配 `SpaceBetween`，
 * 自由空间被平均分到**每一对相邻子项**之间，于是数字与单位之间凭空多出一段
 * 随数字长度变化的空白——`38` 与 `tok/s` 被拉开几十 dp，每行右边缘也各不相同。
 * 视觉上就是「数字在中间飘、单位孤零零贴右」，完全不是右对齐。
 *
 * 正确做法只有一条：把数值与单位合并成**同一个可绘制单元**再整体靠右。
 * 这里用 [AnnotatedString] 分段（数值浓、单位淡）而不是两个 Text，正是为了让
 * 「数值+单位」构成一个不可拆分的排版单元：段间距由文字排版决定，永远不会被
 * 父布局的自由空间撑开。
 */
@Composable
fun PanelValueRow(
    label: String,
    value: String,
    suffix: String? = null,
    /**
     * 单位之后的补充注记（如 `· 命中 96%`）。
     *
     * 必须单独传，不能拼进 [value]：拼进 value 会让这行与其它行构成
     * 完全不同的排版单元，注记长度不受控，右边缘必然与「纯数值行」对不齐。
     */
    note: String? = null,
    emphasize: Boolean = false,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface.copy(
        alpha = PanelTokens.rowValueAlpha
    )
) {
    val suffixColor = MaterialTheme.colorScheme.onSurfaceVariant
    // 数值与单位合成一个字符串单元：中间只留一个空格，宽度固定不随布局变化。
    val valueText = buildAnnotatedString {
        withStyle(
            SpanStyle(
                color = valueColor,
                fontWeight = if (emphasize) PanelTokens.sectionTitleWeight
                else androidx.compose.ui.text.font.FontWeight.Normal
            )
        ) {
            append(value)
        }
        if (suffix != null) {
            withStyle(SpanStyle(color = suffixColor)) {
                append(" ")
                append(suffix)
            }
        }
        if (!note.isNullOrBlank()) {
            withStyle(SpanStyle(color = suffixColor)) {
                append(" ")
                append(note)
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = PanelTokens.rowLabelSize,
                lineHeight = PanelTokens.rowLine
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // weight 吃掉全部剩余空间，把数值列顶到行尾：
            // 只有两段（标签 / 数值组）时，多宽的屏都不会在数值内部产生间隙。
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(PanelTokens.rowGap))
        Text(
            text = valueText,
            style = TextStyle(
                fontSize = PanelTokens.rowValueSize,
                lineHeight = PanelTokens.rowLine,
                fontFeatureSettings = PanelTokens.rowValueFeature
            ),
            textAlign = TextAlign.End,
            maxLines = 1
        )
    }
}
