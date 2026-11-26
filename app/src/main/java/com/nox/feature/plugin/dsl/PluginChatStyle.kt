package com.nox.feature.plugin.dsl

import androidx.compose.runtime.staticCompositionLocalOf
import com.nox.feature.plugin.domain.PluginStyleTokens

/**
 * 插件对宿主聊天输入框的样式覆盖。
 *
 * 每个字段为 null 都表示「用宿主原值」，因此插件只改自己关心的项，
 * 其余继续跟随宿主主题与版本更新。
 */
data class ChatInputStyle(
    val radius: Float? = null,
    val shadow: Float? = null,
    val minHeight: Float? = null,
    val background: DslColor? = null
) {
    companion object {
        val Default = ChatInputStyle()
    }
}

/**
 * 当前生效的输入框样式。
 *
 * 用 CompositionLocal 往下发而不是层层传参：输入框埋在聊天面板深处，
 * 沿途十几个 composable 都不关心样式，穿参只会污染签名。
 */
val LocalChatInputStyle = staticCompositionLocalOf { ChatInputStyle.Default }

/** 把清单里的样式令牌转成可渲染样式；颜色写错时忽略该项，不让一个笔误把整屏样式做废。 */
fun chatInputStyleOf(tokens: PluginStyleTokens?): ChatInputStyle {
    if (tokens == null) return ChatInputStyle.Default
    return ChatInputStyle(
        radius = tokens.chatInputRadius?.coerceIn(0f, 48f),
        shadow = tokens.chatInputShadow?.coerceIn(0f, 40f),
        minHeight = tokens.chatInputMinHeight?.coerceIn(0f, 160f),
        background = tokens.chatInputColor?.let { text ->
            PluginDslParser.parseColorText(text)
        }
    )
}

/**
 * 插件对聊天界面其他部位的样式覆盖：气泡、顶栏、侧栏、消息正文。
 *
 * 与 [ChatInputStyle] 分开是因为两者作用面不同——输入框令牌一直生效，
 * 而这些是“看起来像宿主还是像插件”的外观开关，以后可能要单独授受。
 */
data class PluginUiStyle(
    val bubbleRadius: Float? = null,
    val bubbleUserColor: DslColor? = null,
    val bubbleUserTextColor: DslColor? = null,
    val topBarColor: DslColor? = null,
    val topBarTextColor: DslColor? = null,
    val drawerColor: DslColor? = null,
    val drawerTextColor: DslColor? = null,
    val messageTextColor: DslColor? = null,
    val iconColor: DslColor? = null
) {
    companion object {
        val Default = PluginUiStyle()
    }
}

val LocalPluginUiStyle = staticCompositionLocalOf { PluginUiStyle.Default }

/** 把清单令牌转成界面样式；未声明的项保持 null，由调用点回退到宿主值。 */
fun pluginUiStyleOf(tokens: PluginStyleTokens?): PluginUiStyle {
    if (tokens == null) return PluginUiStyle.Default
    return PluginUiStyle(
        bubbleRadius = tokens.bubbleRadius?.coerceIn(0f, 32f),
        bubbleUserColor = tokens.bubbleUserColor?.let { PluginDslParser.parseColorText(it) },
        bubbleUserTextColor = tokens.bubbleUserTextColor?.let { PluginDslParser.parseColorText(it) },
        topBarColor = tokens.topBarColor?.let { PluginDslParser.parseColorText(it) },
        topBarTextColor = tokens.topBarTextColor?.let { PluginDslParser.parseColorText(it) },
        drawerColor = tokens.drawerColor?.let { PluginDslParser.parseColorText(it) },
        drawerTextColor = tokens.drawerTextColor?.let { PluginDslParser.parseColorText(it) },
        messageTextColor = tokens.messageTextColor?.let { PluginDslParser.parseColorText(it) },
        iconColor = tokens.iconColor?.let { PluginDslParser.parseColorText(it) }
    )
}

/**
 * 图标着色：只接管原本用中性色的图标。
 *
 * 选中态/强调色（primary、error 等）走原值——插件把图标改成自定义色时，
 * 不该把「当前选中」的语义也一并搅乱。
 */
@androidx.compose.runtime.Composable
fun pluginIconTint(fallback: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color =
    LocalPluginUiStyle.current.iconColor.resolve(fallback)
