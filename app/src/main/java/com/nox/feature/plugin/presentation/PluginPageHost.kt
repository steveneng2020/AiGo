package com.nox.feature.plugin.presentation

import android.app.Activity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.nox.core.theme.semanticColors
import com.nox.feature.plugin.domain.PluginPage

/**
 * 插件页面宿主：设置页、侧栏、顶栏与大屏右栏共用同一套 WebView 与桥接逻辑。
 *
 * 底色用 [semanticColors.pageBackground] 而不是 [MaterialTheme.colorScheme.background]：
 * WebView 内容区铺的是 pageBackground，而 statusBarsPadding() 让出的那条状态栏
 * 露的是本 Surface 的底。两者取不同值时顶部会留一条纯白色带，与页面色断开。
 */
@Composable
fun PluginPageHost(
    page: PluginPage,
    languageTag: String,
    pluginsViewModel: PluginsViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    applySystemBarInsets: Boolean = true,
    context: () -> PluginEntryContext = { PluginEntryContext() }
) {
    val background = MaterialTheme.semanticColors.pageBackground
    val isDark = background.luminance() < 0.5f
    ApplySystemBarAppearance(lightBackground = !isDark)
    Surface(color = background, modifier = modifier.fillMaxSize()) {
        PluginWebPanelScreen(
            page = page,
            onClosePage = onClose,
            bridgeFactory = { post, close ->
                pluginsViewModel.createWebBridge(
                    page = page,
                    pluginVersion = page.pluginVersion,
                    languageTag = languageTag,
                    theme = { if (isDark) "dark" else "light" },
                    onClosePage = close,
                    post = post,
                    pageContext = context
                )
            },
            modifier = Modifier.fillMaxSize(),
            applySystemBarInsets = applySystemBarInsets,
            entryContext = context
        )
    }
}

/**
 * 按本页背景亮度接管系统栏图标色，退出时还原。
 *
 * MainActivity 只在主题变化时设过一次全局图标色，插件页从侧栏、聊天头菜单等入口
 * 叠上来时不会重设。窗口里留的可能是深色主题定下的白色图标，压在浅色页面上就成了
 * 白底白字，状态栏的时间和电量都看不见。
 */
@Composable
private fun ApplySystemBarAppearance(lightBackground: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    // onDispose 不是 @Composable 上下文，读不到 MaterialTheme，先在这里按外层主题算好。
    // 用 rememberUpdatedState 兜住主题变化：外层切主题时还原值要跟着变。
    val restoreLight by rememberUpdatedState(
        MaterialTheme.colorScheme.background.luminance() > 0.5f
    )
    SideEffect {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBackground
            isAppearanceLightNavigationBars = lightBackground
        }
    }
    DisposableEffect(window) {
        onDispose {
            // 离开插件页时按外层主题还原，否则返回聊天页后仍留着插件页的取值。
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = restoreLight
                isAppearanceLightNavigationBars = restoreLight
            }
        }
    }
}