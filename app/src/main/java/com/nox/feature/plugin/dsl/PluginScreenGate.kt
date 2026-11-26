package com.nox.feature.plugin.dsl

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nox.core.theme.semanticColors

/**
 * 插件整屏接管层。
 *
 * 必须挂在抽屉与导航之上（与插件槽位页面同一层级），否则会被 ModalNavigationDrawer 的
 * scrim 盖住；也留在主窗口内而不是 Dialog，否则拿不到全局背景水印。
 *
 * 没有插件接管该屏幕时它什么都不画，宿主默认界面原样可见。
 */
@Composable
fun PluginScreenOverlay(target: String) {
    val viewModel: PluginScreenViewModel = hiltViewModel()
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val revision by viewModel.revision.collectAsStateWithLifecycle()
    val candidate = remember(plugins, revision, target) { viewModel.candidateFor(target) }
        ?: return

    LaunchedEffect(candidate.key) { viewModel.ensureLoaded(candidate) }
    // 接管界面上按返回等于退出接管，而不是直接把应用退回后台
    BackHandler { viewModel.dismiss(candidate) }
    val slots by viewModel.slots.collectAsStateWithLifecycle()
    val state = slots[candidate.key] ?: PluginScreenSlot.Idle

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.semanticColors.pageBackground
    ) {
        Column(Modifier.fillMaxSize()) {
            PluginScreenBanner(
                pluginName = when (state) {
                    is PluginScreenSlot.Ready -> state.pluginName
                    is PluginScreenSlot.Failed -> state.pluginName
                    else -> viewModel.pluginNameOf(candidate)
                },
                onReload = { viewModel.reload(candidate) },
                onDismiss = { viewModel.dismiss(candidate) }
            )
            Box(Modifier.fillMaxSize()) {
                when (state) {
                    PluginScreenSlot.Idle, PluginScreenSlot.Loading -> CenteredProgress()
                    is PluginScreenSlot.Failed -> PluginScreenError(
                        message = state.message,
                        onRetry = { viewModel.reload(candidate) },
                        onDismiss = { viewModel.dismiss(candidate) }
                    )
                    is PluginScreenSlot.Ready -> ReadyScreen(
                        target = target,
                        state = state,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyScreen(
    target: String,
    state: PluginScreenSlot.Ready,
    viewModel: PluginScreenViewModel
) {
    val uriHandler = LocalUriHandler.current
    PluginDslRenderer(
        node = state.document.root,
        modifier = Modifier.fillMaxSize().padding(16.dp),
        onAction = { action, node ->
            when (action.type) {
                DslAction.CLOSE -> viewModel.dismiss(state.screen)
                DslAction.OPEN_URL -> action.url?.let { runCatching { uriHandler.openUri(it) } }
                DslAction.EVENT -> viewModel.dispatch(target, action, node, node.value)
            }
        }
    )
}

/**
 * 接管提示条。
 *
 * 常驻是刻意的：使用者需要随时知道「这一屏不是宿主画的」，并且一键能退回默认界面。
 */
@Composable
private fun PluginScreenBanner(
    pluginName: String,
    onReload: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "「$pluginName」接管中",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        BannerAction("刷新", onReload)
        Spacer(Modifier.width(12.dp))
        BannerAction("恢复默认", onDismiss)
    }
}

@Composable
private fun BannerAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun CenteredProgress() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 出错时给出原因与重试，不静默退回宿主界面——否则作者看不到自己插件的错误。 */
@Composable
private fun PluginScreenError(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "插件界面加载失败",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = message,
                fontSize = 12.sp,
                color = MaterialTheme.semanticColors.subtleText,
                textAlign = TextAlign.Center
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("重试", primary = true, onClick = onRetry)
                PillButton("恢复默认", primary = false, onClick = onDismiss)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun PillButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val background = if (primary) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.semanticColors.buttonMutedBg
    }
    val textColor = if (primary) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 13.sp, color = textColor, fontWeight = FontWeight.Medium)
    }
}
