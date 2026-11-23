package com.nox.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nox.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.Lock
import kotlinx.coroutines.delay

/**
 * 防误触全屏遮罩。
 *
 * 设计取舍：
 * - 遮罩用**不透明**背景而不是半透明，否则下面的按钮仍会被误点到（半透明只是视觉遮挡）。
 * - 拦截所有触摸：只接受「长按 3 秒」这一个手势，点击、滑动全部吞掉。
 * - 长按过程中画进度环，松手立即归零——用户能看出「要一直按着」。
 */
@Composable
fun TouchLockOverlay(
    onUnlock: () -> Unit,
    holdMillis: Long = 1000L
) {
    var pressing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    // 已触发解锁后立刻隐藏遮罩：DataStore 写入到 Flow 回流有几十到上百毫秒延迟，
    // 若只靠 touchLockActive 驱动，用户会觉得「按满了还没反应」。
    var unlocked by remember { mutableStateOf(false) }

    // 按住计时：progress 从 0 走到 1 即触发解锁；中途松手由 pressing 变化重置。
    LaunchedEffect(pressing) {
        if (!pressing) {
            progress = 0f
            return@LaunchedEffect
        }
        val step = 50L
        var elapsed = 0L
        while (elapsed < holdMillis) {
            delay(step)
            elapsed += step
            progress = (elapsed.toFloat() / holdMillis).coerceIn(0f, 1f)
        }
        unlocked = true
        onUnlock()
    }

    if (unlocked) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressing = true
                        // awaitRelease 在手指抬起时返回，无论按住多久。
                        tryAwaitRelease()
                        pressing = false
                    },
                    onTap = { /* 点击不做事：防误触的全部意义就是屏蔽点击 */ },
                    onLongPress = { /* 长按逻辑由上面的计时驱动，这里不重复触发 */ }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(96.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    strokeWidth = 4.dp
                )
                Icon(
                    imageVector = FeatherIcons.Lock,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.touch_lock_active_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.touch_lock_unlock_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 长按解锁时长的选择弹窗。
 *
 * 放在顶栏菜单「防误触」的长按里，而不是设置页：它是防误触自身的参数，
 * 从同一处进入更符合直觉，也不占设置页篇幅。
 */
@Composable
fun TouchLockHoldPicker(
    current: Long,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.touch_lock_hold_title),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.touch_lock_hold_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                com.nox.feature.agent.data.touchlock.TouchLockManager.HOLD_OPTIONS_MS.forEach { ms ->
                    val secs = ms / 1000f
                    val label = if (secs % 1f == 0f) {
                        stringResource(R.string.touch_lock_hold_seconds, secs.toInt().toString())
                    } else {
                        stringResource(R.string.touch_lock_hold_seconds_decimal, secs.toString())
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(ms) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = ms == current,
                            onClick = { onPick(ms) }
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}
