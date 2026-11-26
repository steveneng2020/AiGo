package com.nox.feature.plugin.dsl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.core.theme.semanticColors
import com.nox.feature.plugin.presentation.resolvePluginBuiltinIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 网络图片上限，超过直接放弃解码，避免插件用大图把内存吃满。 */
private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

/** 当前是否已经处在滚动容器内；用于阻止嵌套滚动导致的测量崩溃。 */
private val LocalDslInScroll = staticCompositionLocalOf { false }

/** 一次交互回调：渲染器只负责把动作交出去，具体语义由接管层解释。 */
typealias DslActionHandler = (DslAction, DslNode) -> Unit

/**
 * 把一份 DSL 节点树渲染成原生 Compose。
 *
 * 渲染是完全声明式的：不做代码求值、不加载插件脚本，插件能影响的范围被限制在
 * [PluginDslTypes] 的节点集与 [DslAction] 的动作集之内。
 */
@Composable
fun PluginDslRenderer(
    node: DslNode,
    onAction: DslActionHandler,
    modifier: Modifier = Modifier
) {
    when (node.type) {
        PluginDslTypes.COLUMN -> DslColumn(node, onAction, modifier, scroll = false)
        PluginDslTypes.SCROLL -> DslColumn(node, onAction, modifier, scroll = true)
        PluginDslTypes.ROW -> DslRow(node, onAction, modifier)
        PluginDslTypes.BOX -> DslBox(node, onAction, modifier)
        PluginDslTypes.CARD -> DslCard(node, onAction, modifier)
        PluginDslTypes.TEXT -> DslText(node)
        PluginDslTypes.ICON -> DslIcon(node)
        PluginDslTypes.IMAGE -> DslImage(node, modifier)
        PluginDslTypes.DIVIDER -> DslDivider(node, modifier)
        PluginDslTypes.SPACER -> DslSpacer(node)
        PluginDslTypes.BADGE -> DslBadge(node)
        PluginDslTypes.PROGRESS -> DslProgress(node, modifier)
        PluginDslTypes.BUTTON -> DslButton(node, onAction, modifier)
        PluginDslTypes.ICON_BUTTON -> DslIconButton(node, onAction)
        PluginDslTypes.SWITCH -> DslSwitch(node, onAction)
        PluginDslTypes.SLIDER -> DslSlider(node, onAction, modifier)
        PluginDslTypes.TEXT_FIELD -> DslTextField(node, onAction, modifier)
    }
}

@Composable
private fun DslColumn(node: DslNode, onAction: DslActionHandler, modifier: Modifier, scroll: Boolean) {
    val alreadyScrolling = LocalDslInScroll.current
    // 嵌套 verticalScroll 会因“无限高约束”直接抛 IllegalStateException，内层降级成普通列
    val effectiveScroll = scroll && !alreadyScrolling
    val base = modifier
        .then(node.sizeModifier())
        .then(node.style.decoration())
    val gap = (node.style.gap ?: 0f).dp
    val outer = if (effectiveScroll) base.verticalScroll(rememberScrollState()) else base
    CompositionLocalProvider(LocalDslInScroll provides (alreadyScrolling || effectiveScroll)) {
        Column(
            modifier = outer,
            verticalArrangement = if (gap.value > 0f) Arrangement.spacedBy(gap) else Arrangement.Top,
            horizontalAlignment = node.style.horizontalAlignment()
        ) {
            node.children.forEach { child -> PluginDslRenderer(child, onAction) }
        }
    }
}

@Composable
private fun DslRow(node: DslNode, onAction: DslActionHandler, modifier: Modifier) {
    Row(
        modifier = modifier
            .then(node.sizeModifier())
            .then(node.style.decoration()),
        horizontalArrangement = if ((node.style.gap ?: 0f) > 0f) {
            Arrangement.spacedBy((node.style.gap ?: 0f).dp)
        } else {
            Arrangement.Start
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        node.children.forEach { child -> PluginDslRenderer(child, onAction) }
    }
}

@Composable
private fun DslBox(node: DslNode, onAction: DslActionHandler, modifier: Modifier) {
    Box(
        modifier = modifier
            .then(node.sizeModifier())
            .then(node.style.decoration()),
        contentAlignment = when (node.style.align) {
            "start" -> Alignment.CenterStart
            "end" -> Alignment.CenterEnd
            else -> Alignment.Center
        }
    ) {
        node.children.forEach { child -> PluginDslRenderer(child, onAction) }
    }
}

@Composable
private fun DslCard(node: DslNode, onAction: DslActionHandler, modifier: Modifier) {
    val shape = RoundedCornerShape((node.style.radius ?: 16f).dp)
    val background = node.style.background.resolve(MaterialTheme.semanticColors.cardSurface)
    Column(
        modifier = modifier
            .then(node.sizeModifier())
            .clip(shape)
            .background(background)
            .border(1.dp, MaterialTheme.semanticColors.subtleBorder, shape)
            .padding(node.style.padding.paddingValues(16f)),
        verticalArrangement = if ((node.style.gap ?: 0f) > 0f) {
            Arrangement.spacedBy((node.style.gap ?: 0f).dp)
        } else {
            Arrangement.Top
        }
    ) {
        node.children.forEach { child -> PluginDslRenderer(child, onAction) }
    }
}

@Composable
private fun DslText(node: DslNode) {
    val align = when (node.style.align) {
        "center" -> TextAlign.Center
        "end" -> TextAlign.End
        else -> TextAlign.Start
    }
    Text(
        text = node.text.orEmpty(),
        modifier = node.sizeModifier().padding(node.style.padding.paddingValues(0f)),
        color = node.style.foreground.resolve(MaterialTheme.colorScheme.onSurface),
        fontSize = (node.style.fontSize ?: 15f).sp,
        fontWeight = node.style.weight?.let { weightOf(it) } ?: FontWeight.Normal,
        textAlign = align,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun DslIcon(node: DslNode) {
    Icon(
        imageVector = resolvePluginBuiltinIcon(node.icon ?: "box"),
        contentDescription = node.text,
        tint = node.style.foreground.resolve(MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.size((node.size ?: 20f).dp).padding(node.style.padding.paddingValues(0f))
    )
}

@Composable
private fun DslImage(node: DslNode, modifier: Modifier) {
    val url = node.src ?: return
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        value = loadBitmap(url)
    }
    val shape = RoundedCornerShape((node.style.radius ?: 12f).dp)
    val target = modifier
        .then(node.sizeModifier())
        .clip(shape)
    val image = bitmap
    if (image == null) {
        Box(
            modifier = target
                .height((node.height ?: 120f).dp)
                .background(MaterialTheme.semanticColors.mutedSurface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = resolvePluginBuiltinIcon("image"),
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText
            )
        }
    } else {
        Image(
            bitmap = image,
            contentDescription = node.text,
            modifier = target,
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun DslDivider(node: DslNode, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(node.style.foreground.resolve(MaterialTheme.semanticColors.subtleBorder))
    )
}

@Composable
private fun DslSpacer(node: DslNode) {
    Spacer(modifier = Modifier.height((node.size ?: 8f).dp))
}

@Composable
private fun DslBadge(node: DslNode) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                node.style.background.resolve(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = node.text.orEmpty(),
            color = node.style.foreground.resolve(MaterialTheme.colorScheme.primary),
            fontSize = (node.style.fontSize ?: 11f).sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DslProgress(node: DslNode, modifier: Modifier) {
    // NaN/Infinity 会让进度条绘制异常，直接当作不确定进度
    val value = node.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    val target = modifier.then(node.sizeModifier())
    if (value == null) {
        LinearProgressIndicator(modifier = target.fillMaxWidth())
    } else {
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            modifier = target.fillMaxWidth()
        )
    }
}

@Composable
private fun DslButton(node: DslNode, onAction: DslActionHandler, modifier: Modifier) {
    val shape = RoundedCornerShape((node.style.radius ?: 999f).dp)
    val enabled = node.action != null
    Row(
        modifier = modifier
            .then(node.sizeModifier())
            .clip(shape)
            .background(
                node.style.background.resolve(
                    if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.semanticColors.mutedSurface
                )
            )
            .clickable(enabled = enabled) { node.action?.let { onAction(it, node) } }
            .padding(node.style.padding.paddingValues(horizontal = 16f, vertical = 10f)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        node.icon?.let { icon ->
            Icon(
                imageVector = resolvePluginBuiltinIcon(icon),
                contentDescription = null,
                tint = node.style.foreground.resolve(MaterialTheme.colorScheme.onPrimary),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = node.text.orEmpty(),
            color = node.style.foreground.resolve(MaterialTheme.colorScheme.onPrimary),
            fontSize = (node.style.fontSize ?: 14f).sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DslIconButton(node: DslNode, onAction: DslActionHandler) {
    Box(
        modifier = Modifier
            .size((node.size ?: 40f).dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = node.action != null) { node.action?.let { onAction(it, node) } },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = resolvePluginBuiltinIcon(node.icon ?: "more-horizontal"),
            contentDescription = node.text,
            tint = node.style.foreground.resolve(MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun DslSwitch(node: DslNode, onAction: DslActionHandler) {
    // 开关状态以插件文档为准；本地先翻转再回传，避免等一轮进程往返才动
    var checked by remember(node.id, node.checked) { mutableStateOf(node.checked) }
    Switch(
        checked = checked,
        onCheckedChange = { next ->
            checked = next
            node.action?.let { onAction(it, node.copy(checked = next)) }
        }
    )
}

@Composable
private fun DslSlider(node: DslNode, onAction: DslActionHandler, modifier: Modifier) {
    val range = if (node.max > node.min) node.min..node.max else 0f..1f
    var value by remember(node.id, node.progress) {
        mutableStateOf(node.progress?.coerceIn(range.start, range.endInclusive) ?: range.start)
    }
    Slider(
        value = value,
        onValueChange = { next -> value = next },
        valueRange = range,
        modifier = modifier.then(node.sizeModifier())
    )
}

@Composable
private fun DslTextField(node: DslNode, onAction: DslActionHandler, modifier: Modifier) {
    var value by remember(node.id, node.value) { mutableStateOf(node.value.orEmpty()) }
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            value = next
            node.action?.let { onAction(it, node.copy(value = next)) }
        },
        modifier = modifier.then(node.sizeModifier()),
        placeholder = node.placeholder?.let { { Text(it, fontSize = 14.sp) } },
        singleLine = true,
        shape = RoundedCornerShape((node.style.radius ?: 12f).dp),
        keyboardOptions = KeyboardOptions.Default
    )
}

// ——— 样式解析 ———

@Composable
private fun DslStyle.decoration(): Modifier {
    var result: Modifier = Modifier
    val shape = RoundedCornerShape((radius ?: 0f).dp)
    // 阴影要在 clip 之前，clip 之后路径已被裁切，再画阴影会被裁掉
    shadow?.let { result = result.shadow(elevation = it.dp, shape = shape, clip = false) }
    background?.let { result = result.clip(shape).background(it.resolve(Color.Transparent)) }
    border?.let { result = result.border(1.dp, it.resolve(MaterialTheme.colorScheme.outline), shape) }
    result = result.padding(padding.paddingValues(0f))
    return result
}

/** 节点尺寸：先看填充标志，再叠显式宽高。 */
@Composable
private fun DslNode.sizeModifier(): Modifier {
    val inScroll = LocalDslInScroll.current
    var result: Modifier = Modifier
    if (style.fillWidth) result = result.fillMaxWidth()
    // 滚动容器内高度无上限，fillMaxHeight 会抛异常，直接跳过
    if (style.fillHeight && !inScroll) result = result.fillMaxHeight()
    width?.let { result = result.width(it.dp) }
    height?.let { result = result.height(it.dp) }
    size?.let { result = result.size(it.dp) }
    return result
}

private fun DslInsets?.paddingValues(default: Float): androidx.compose.foundation.layout.PaddingValues {
    val insets = this
    return if (insets == null) {
        androidx.compose.foundation.layout.PaddingValues(default.dp)
    } else {
        androidx.compose.foundation.layout.PaddingValues(
            start = insets.start.dp,
            top = insets.top.dp,
            end = insets.end.dp,
            bottom = insets.bottom.dp
        )
    }
}

@Composable
private fun DslInsets?.paddingValues(
    horizontal: Float,
    vertical: Float
): androidx.compose.foundation.layout.PaddingValues {
    val insets = this
    return if (insets == null) {
        androidx.compose.foundation.layout.PaddingValues(horizontal = horizontal.dp, vertical = vertical.dp)
    } else {
        androidx.compose.foundation.layout.PaddingValues(
            start = insets.start.dp,
            top = insets.top.dp,
            end = insets.end.dp,
            bottom = insets.bottom.dp
        )
    }
}

@Composable
private fun DslStyle.horizontalAlignment(): Alignment.Horizontal = when (align) {
    "center" -> Alignment.CenterHorizontally
    "end" -> Alignment.End
    else -> Alignment.Start
}

@Composable
internal fun DslColor?.resolve(fallback: Color): Color {
    val color = this ?: return fallback
    return when (color) {
        is DslColor.Literal -> Color(color.argb)
        is DslColor.Token -> color.resolveToken(fallback)
    }
}

@Composable
private fun DslColor.Token.resolveToken(fallback: Color): Color {
    val scheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.semanticColors
    return when (name) {
        "primary" -> scheme.primary
        "onPrimary" -> scheme.onPrimary
        "primaryContainer" -> scheme.primaryContainer
        "secondary" -> scheme.secondary
        "tertiary" -> scheme.tertiary
        "background" -> scheme.background
        "surface" -> scheme.surface
        "surfaceVariant" -> scheme.surfaceVariant
        "onSurface" -> scheme.onSurface
        "onSurfaceVariant" -> scheme.onSurfaceVariant
        "outline" -> scheme.outline
        "outlineVariant" -> scheme.outlineVariant
        "error" -> scheme.error
        "success" -> semantic.success
        "warning" -> semantic.warning
        "info" -> semantic.info
        "cardSurface" -> semantic.cardSurface
        "pageBackground" -> semantic.pageBackground
        "mutedSurface" -> semantic.mutedSurface
        "capsuleSurface" -> semantic.capsuleSurface
        "subtleText" -> semantic.subtleText
        "subtleBorder" -> semantic.subtleBorder
        "buttonMutedBg" -> semantic.buttonMutedBg
        else -> fallback
    }
}

private fun weightOf(weight: Int): FontWeight = when {
    weight >= 700 -> FontWeight.Bold
    weight >= 600 -> FontWeight.SemiBold
    weight >= 500 -> FontWeight.Medium
    else -> FontWeight.Normal
}

/**
 * 按 URL 取图并解码。
 *
 * 只接受 https（解析阶段已校验）；限制响应大小与超时，失败一律返回 null 由界面显示占位，
 * 不让一次网络异常把整屏渲染打断。
 */
private suspend fun loadBitmap(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 8000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            val bytes = connection.inputStream.use { stream ->
                val buffer = ByteArray(64 * 1024)
                val output = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_IMAGE_BYTES) return@runCatching null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

/** 供宿主列表等场景复用的空态：插件界面没有内容时给一句提示，而不是整屏空白。 */
@Composable
fun DslEmptyState(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = MaterialTheme.semanticColors.subtleText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}
