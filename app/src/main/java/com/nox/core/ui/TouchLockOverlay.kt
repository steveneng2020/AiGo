package com.nox.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nox.R
import com.nox.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Lock
import kotlin.math.max

/** 长按多久退出：3 秒足够长到不会误触，又不至于让人等。 */
internal const val TOUCH_LOCK_HOLD_DURATION_MS = 3_000L

/**
 * 把毫秒格式化成「秒」的展示文本，保留一位小数但去掉多余的 .0。
 *
 * 0.7 秒 → "0.7"，1 秒 → "1"，3 秒 → "3"，2.5 秒 → "2.5"。
 * 直接用整数除法会把 0.7 算成 0（再兜成 1），与设置页显示的值对不上。
 */
internal fun formatHoldSeconds(holdDurationMs: Long): String {
    val tenths = ((holdDurationMs.coerceAtLeast(1L) + 50L) / 100L)  // 四舍五入到 0.1 秒
    return if (tenths % 10L == 0L) {
        (tenths / 10L).toString()
    } else {
        "${tenths / 10L}.${tenths % 10L}"
    }
}

/** 遮罩期间把屏幕调到近乎全黑，既省电又不晃眼。 */
internal const val TOUCH_LOCK_WINDOW_BRIGHTNESS = 0.02f

private val UNLOCK_BUTTON_SIZE = 112.dp
private val UNLOCK_RING_STROKE = 3.5.dp
private val UNLOCK_TOUCH_TOLERANCE = 16.dp

internal fun touchLockHoldProgress(
    elapsedMs: Long,
    holdDurationMs: Long = TOUCH_LOCK_HOLD_DURATION_MS
): Float {
    if (holdDurationMs <= 0L) return 1f
    return (elapsedMs.toFloat() / holdDurationMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * 剩余需按住的毫秒数。
 *
 * 早先返回「秒」并向上取整，于是 0.7 秒的设置在倒计时里全程显示「1 秒」，
 * 与设置页滑杆显示的值对不上（用户报的「提示值与设置值对不上」）。
 * 改为返回毫秒，由 [formatHoldSeconds] 统一按 0.1 秒格式化，
 * 提示与倒计时用同一套规则，不会再出现两处不一致。
 */
internal fun touchLockRemainingMs(
    elapsedMs: Long,
    holdDurationMs: Long = TOUCH_LOCK_HOLD_DURATION_MS
): Long = (holdDurationMs - elapsedMs.coerceAtLeast(0L)).coerceAtLeast(0L)

/** 手指是否仍落在解锁按钮（含容差）范围内；偏离就取消长按。 */
internal fun isPointerWithinTouchLockTarget(
    positionX: Float,
    positionY: Float,
    widthPx: Float,
    heightPx: Float,
    tolerancePx: Float
): Boolean {
    if (widthPx <= 0f || heightPx <= 0f) return false
    val radius = max(widthPx, heightPx) / 2f + tolerancePx.coerceAtLeast(0f)
    val dx = positionX - widthPx / 2f
    val dy = positionY - heightPx / 2f
    return dx * dx + dy * dy <= radius * radius
}

/** 若用户原本亮度已比省电目标更暗，保留原亮度以免反向提亮。 */
internal fun resolveTouchLockTargetBrightness(previousBrightness: Float): Float =
    if (previousBrightness in 0f..TOUCH_LOCK_WINDOW_BRIGHTNESS) {
        previousBrightness
    } else {
        TOUCH_LOCK_WINDOW_BRIGHTNESS
    }

/**
 * 全屏防误触遮罩：纯黑底色省电并吞掉全部触摸与返回手势，仅中央按钮长按可退出（时长见 [holdDurationMs]）。
 *
 * 用于长任务运行时把手机放兜里、或者把屏幕交给别人看时防止误操作。
 */
@Composable
fun TouchLockOverlay(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 长按解锁所需时长。
     *
     * 上游原版写死 3 秒；本项目在早期实现里做成了可调（0.1~5 秒）并保留了
     * 设置项，所以这里开放参数。不传时仍是上游默认的 3 秒，行为不变。
     */
    holdDurationMs: Long = TOUCH_LOCK_HOLD_DURATION_MS,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    val latestOnUnlock by rememberUpdatedState(onUnlock)

    LaunchedEffect(Unit) {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
    }

    // 返回键也吃掉：锁屏状态下按返回不应该退出
    BackHandler(enabled = true) { }

    var holding by remember { mutableStateOf(false) }
    var unlocked by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var holdProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(holding) {
        if (!holding || unlocked) {
            if (!unlocked) {
                elapsedMs = 0L
                holdProgress = 0f
            }
            return@LaunchedEffect
        }
        val startNs = withFrameNanos { it }
        while (holding && !unlocked) {
            val frameNs = withFrameNanos { it }
            val currentElapsed = ((frameNs - startNs) / 1_000_000L).coerceAtLeast(0L)
            elapsedMs = currentElapsed
            val progress = touchLockHoldProgress(currentElapsed, holdDurationMs)
            holdProgress = progress
            if (progress >= 1f) {
                unlocked = true
                holding = false
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                latestOnUnlock()
                break
            }
        }
    }

    val buttonScale by animateFloatAsState(
        targetValue = if (holding) 0.94f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "touch-lock-button-scale"
    )
    // 解锁时长支持 0.1 秒精度（设置页滑杆 0.1~5 秒），所有展示时长的地方都要跟着走。
    //
    // 早先用 (holdDurationMs / 1000).toInt() 取整，0.7 秒会被算成 0，
    // 再被 coerceAtLeast(1) 兜成 1，于是界面上写「长按 1 秒退出」而实际 0.7 秒。
    // 另外无障碍描述与长按中的倒计时也曾各自写死或单独取整，三处对不上。
    // 现在统一由 formatHoldSeconds 产出，必须先于任何使用处定义。
    val totalSecondsText = formatHoldSeconds(holdDurationMs)
    val unlockDescription = stringResource(R.string.touch_lock_unlock_cd, totalSecondsText)
    val tolerancePx = with(density) { UNLOCK_TOUCH_TOLERANCE.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // 消费按钮之外的所有触摸，避免穿透到底层聊天、终端或抽屉
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .systemBarsPadding()
                .padding(horizontal = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.touch_lock_active_title),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.52f)
            )
            Spacer(modifier = Modifier.height(Spacing.xl))
            Box(
                modifier = Modifier
                    .size(UNLOCK_BUTTON_SIZE)
                    .graphicsLayer {
                        scaleX = buttonScale
                        scaleY = buttonScale
                    }
                    .clip(CircleShape)
                    .semantics {
                        role = Role.Button
                        contentDescription = unlockDescription
                    }
                    .pointerInput(tolerancePx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            if (unlocked) return@awaitEachGesture
                            holding = true
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                    val activePointers = event.changes.filter { it.pressed }
                                    if (activePointers.size != 1) break
                                    val current = activePointers.first()
                                    if (current.id != down.id) break
                                    // 手指滑出按钮范围就取消，避免误触后一直计时
                                    if (!isPointerWithinTouchLockTarget(
                                            positionX = current.position.x,
                                            positionY = current.position.y,
                                            widthPx = size.width.toFloat(),
                                            heightPx = size.height.toFloat(),
                                            tolerancePx = tolerancePx
                                        )
                                    ) {
                                        break
                                    }
                                }
                            } finally {
                                holding = false
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val trackColor = Color.White.copy(alpha = if (holding) 0.24f else 0.16f)
                val fillColor = Color.White.copy(alpha = if (holding) 0.10f else 0.04f)
                val arcColor = Color.White.copy(alpha = 0.92f)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokePx = UNLOCK_RING_STROKE.toPx()
                    val diameter = size.minDimension - strokePx
                    val topLeft = Offset(strokePx / 2f, strokePx / 2f)
                    val arcSize = Size(diameter, diameter)
                    drawCircle(color = fillColor, radius = diameter / 2f)
                    drawArc(
                        color = trackColor,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx)
                    )
                    if (holdProgress > 0f) {
                        drawArc(
                            color = arcColor,
                            startAngle = -90f,
                            sweepAngle = 360f * holdProgress,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                    }
                }
                Icon(
                    imageVector = FeatherIcons.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = Color.White.copy(alpha = if (holding) 0.95f else 0.72f)
                )
            }
            Spacer(modifier = Modifier.height(Spacing.lg))
            Text(
                text = if (holding) {
                    stringResource(
                        R.string.touch_lock_holding_countdown,
                        formatHoldSeconds(touchLockRemainingMs(elapsedMs, holdDurationMs))
                    )
                } else {
                    stringResource(R.string.touch_lock_hold_hint, totalSecondsText)
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = if (holding) 0.80f else 0.42f)
            )
        }
    }
}
