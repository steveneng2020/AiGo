package com.nox.feature.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.nox.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 一次手机控制动作的结果。 */
data class PhoneActionResult(
    val ok: Boolean,
    val detail: String,
    val errorCode: String? = null
)

/**
 * 手机控制：通过无障碍服务操作本机。
 *
 * 全部动作都要求无障碍服务已开启——这是系统给普通应用的唯一一条「能读界面、能点界面」的通道。
 * 服务未开启时统一返回 [ERROR_NOT_ENABLED]，由调用方提示用户去系统设置里打开。
 */
@Singleton
class PhoneControlManager @Inject constructor(
    @ApplicationContext private val appContext: Context
) {

    companion object {
        const val ERROR_NOT_ENABLED = "PHONE_CONTROL_DISABLED"
        private const val TAG = "PhoneControlManager"
        private const val MAX_NODES_IN_DUMP = 120
        private const val OPEN_APP_WAIT_MS = 2_000L
        private const val READY_POLL_MS = 50L
        private const val SNAPSHOT_MAX_AGE_MS = 2_000L
        private const val MAX_TEXT_LENGTH = 60
    }

    /** 无障碍服务是否已开启。 */
    fun isEnabled(): Boolean = PhoneControlService.current() != null

    /** 跳转系统的无障碍设置页（各家 ROM 入口不同，ACTION_ACCESSIBILITY_SETTINGS 最通用）。 */
    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appContext.startActivity(intent) }
            .onFailure { FileLogger.w(TAG, "打开无障碍设置失败", it) }
    }

    /** 当前前台应用包名。 */
    fun foregroundPackage(): String? = PhoneControlService.foregroundPackage

    // ================= 应用启动 =================

    /**
     * 按应用名或包名打开应用。
     *
     * 名称匹配是模糊的：先精确匹配，再唯一前缀/包含匹配；命中多个时返回候选让模型自己选，
     * 不擅自挑一个——猜错应用比报错更糟。
     */
    suspend fun openApp(query: String): PhoneActionResult = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext fail("应用名或包名不能为空")

        val pm = appContext.packageManager

        // 先当包名试
        if (trimmed.contains('.')) {
            val direct = pm.getLaunchIntentForPackage(trimmed)
            if (direct != null) return@withContext launch(direct, trimmed)
        }

        val installed = installedApps()
        val exact = installed.filter { it.second.equals(trimmed, ignoreCase = true) }
        val matches = when {
            exact.size == 1 -> exact
            else -> installed.filter { it.second.contains(trimmed, ignoreCase = true) }
        }

        when {
            matches.isEmpty() -> fail("未找到应用：$trimmed")
            matches.size == 1 -> {
                val (pkg, label) = matches.single()
                val intent = pm.getLaunchIntentForPackage(pkg)
                    ?: return@withContext fail("应用不可启动：$label（$pkg）", "APP_NOT_LAUNCHABLE")
                launch(intent, "$label（$pkg）")
            }
            else -> {
                val names = matches.take(8).joinToString("、") { "${it.second}(${it.first})" }
                fail("匹配到多个应用，请改用包名：$names", "AMBIGUOUS_APP")
            }
        }
    }

    private suspend fun launch(intent: Intent, label: String): PhoneActionResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val targetPackage = intent.`package`
        return runCatching {
            appContext.startActivity(intent)
        }.fold(
            onSuccess = {
                // 等目标应用真正到前台再返回：不等的话紧接着的 click 会落在旧界面上扑空。
                awaitForeground(targetPackage)
                PhoneActionResult(true, "已打开 $label")
            },
            onFailure = { fail("打开失败：${it.message}", "LAUNCH_FAILED") }
        )
    }

    /** 轮询等待目标包名成为前台，最多等 [OPEN_APP_WAIT_MS]。 */
    private suspend fun awaitForeground(targetPackage: String?) {
        if (targetPackage.isNullOrBlank()) return
        val deadline = System.currentTimeMillis() + OPEN_APP_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (PhoneControlService.foregroundPackage == targetPackage) return
            delay(READY_POLL_MS)
        }
    }

    private fun installedApps(): List<Pair<String, String>> {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).mapNotNull { info ->
            val appInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrElse { appInfo.packageName }
            appInfo.packageName to label
        }.distinctBy { it.first }
    }

    /** 当前前台应用的包名与显示名。 */
    suspend fun currentApp(): PhoneActionResult = withContext(Dispatchers.IO) {
        val pkg = PhoneControlService.foregroundPackage
            ?: return@withContext fail("还拿不到前台应用，稍后再试", "NO_FOREGROUND")
        val label = runCatching {
            val info = appContext.packageManager.getApplicationInfo(pkg, 0)
            appContext.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
        PhoneActionResult(true, if (label != null) "$label（$pkg）" else pkg)
    }

    // ================= 全局动作 =================

    suspend fun globalAction(action: String): PhoneActionResult = withContext(Dispatchers.Main) {
        val service = PhoneControlService.current() ?: return@withContext notEnabled()
        val resolved = when (action.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return@withContext fail("未知的全局动作：$action", "INVALID_ACTION")
        }
        val ok = service.performGlobalAction(resolved)
        if (ok) PhoneActionResult(true, "已执行 $action") else fail("执行 $action 失败")
    }

    // ================= 点击 =================

    /**
     * 点击。优先按可见文本/描述找节点并执行点击动作；找不到节点再退回坐标手势。
     *
     * 按节点点比按坐标点可靠：列表滚动或分辨率变化时坐标会偏，节点点击跟着控件走。
     */
    suspend fun click(
        text: String? = null,
        contentDescription: String? = null,
        x: Int? = null,
        y: Int? = null
    ): PhoneActionResult = withContext(Dispatchers.Main) {
        val service = PhoneControlService.current() ?: return@withContext notEnabled()

        if (!text.isNullOrBlank() || !contentDescription.isNullOrBlank()) {
            val query = (text ?: contentDescription).orEmpty()

            // ① 先查常驻快照：无障碍事件到达时就已建好，这里只是一次线性扫描，
            //    省掉每次从 root 重新遍历整棵树，打开应用后能立刻点。
            //    快照太旧（界面可能已变）就先重建，避免按过期坐标点到别的控件。
            if (System.currentTimeMillis() - ScreenSnapshot.updatedAt > SNAPSHOT_MAX_AGE_MS) {
                ScreenSnapshot.rebuildNow(service)
            }
            val snapshotHit = ScreenSnapshot.find(query)
            if (snapshotHit != null && snapshotHit.bounds.width() > 0) {
                val cx = snapshotHit.bounds.centerX().toFloat()
                val cy = snapshotHit.bounds.centerY().toFloat()
                if (tapAt(service, cx, cy)) {
                    return@withContext PhoneActionResult(true, "已点击「$query」")
                }
            }

            // ② 快照没有或点不动，实时遍历一次（界面刚变化、快照还没跟上时走这里）
            val root = service.rootInActiveWindow
            if (root != null) {
                val node = findNode(root, text, contentDescription)
                if (node != null) {
                    val clicked = clickNodeOrAncestor(node)
                    node.recycleIfPossible()
                    if (clicked) {
                        return@withContext PhoneActionResult(true, "已点击「$query」")
                    }
                }
            }

            // ③ 两条路径都没成，才要求重新识别界面
            if (x == null || y == null) {
                return@withContext fail(
                    "界面上没找到「$query」，可先 screen 看当前结构",
                    "NODE_NOT_FOUND"
                )
            }
        }

        if (x == null || y == null) return@withContext fail("需要提供 text、contentDescription 或 x/y 坐标")
        val done = tapAt(service, x.toFloat(), y.toFloat())
        if (done) PhoneActionResult(true, "已点击坐标 ($x, $y)") else fail("点击坐标失败")
    }

    /** 在子树里找第一个匹配的节点（文本或描述）。 */
    private fun findNode(
        root: AccessibilityNodeInfo,
        text: String?,
        contentDescription: String?
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            val node = queue.removeFirst()
            visited++
            val nodeText = node.text?.toString()
            val nodeDesc = node.contentDescription?.toString()
            val textHit = text != null && nodeText != null && nodeText.contains(text, ignoreCase = true)
            val descHit = contentDescription != null && nodeDesc != null &&
                nodeDesc.contains(contentDescription, ignoreCase = true)
            if ((textHit || descHit) && node.isVisibleToUser) return node

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** 点击节点；节点本身不可点时向上找可点击祖先（很多列表项的可点击区在父层）。 */
    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        // 没有可点击祖先时，对节点自身发点击（部分控件用 accessibility click 也能响应）
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun tapAt(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    // ================= 输入文本 =================

    /** 往当前聚焦的输入框写文本；先点一下目标再输入更可靠。 */
    suspend fun setText(text: String): PhoneActionResult = withContext(Dispatchers.Main) {
        val service = PhoneControlService.current() ?: return@withContext notEnabled()
        val root = service.rootInActiveWindow ?: return@withContext fail("拿不到当前窗口")

        val target = findFocusedEditable(root) ?: findFirstEditable(root)
            ?: return@withContext fail("当前界面没有可输入的文本框", "NO_EDITABLE")

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) PhoneActionResult(true, "已输入：$text") else fail("输入失败，可能该输入框不支持直接设值")
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return if (focused != null && focused.isEditable) focused else null
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 400) {
            val node = queue.removeFirst()
            visited++
            if (node.isEditable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // ================= 滑动 =================

    suspend fun swipe(
        direction: String,
        distanceRatio: Float = 0.6f
    ): PhoneActionResult = withContext(Dispatchers.Main) {
        val service = PhoneControlService.current() ?: return@withContext notEnabled()
        val metrics = appContext.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val ratio = distanceRatio.coerceIn(0.15f, 0.9f)

        val (startX, startY, endX, endY) = when (direction.lowercase()) {
            "up" -> listOf(w / 2, h * (0.5f + ratio / 2), w / 2, h * (0.5f - ratio / 2))
            "down" -> listOf(w / 2, h * (0.5f - ratio / 2), w / 2, h * (0.5f + ratio / 2))
            "left" -> listOf(w * (0.5f + ratio / 2), h / 2, w * (0.5f - ratio / 2), h / 2)
            "right" -> listOf(w * (0.5f - ratio / 2), h / 2, w * (0.5f + ratio / 2), h / 2)
            else -> return@withContext fail("方向只能是 up/down/left/right", "INVALID_DIRECTION")
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 300L))
            .build()
        val ok = service.dispatchGesture(gesture, null, null)
        if (ok) PhoneActionResult(true, "已向 $direction 滑动") else fail("滑动失败")
    }

    /** 长按：支持文本定位，也接受坐标。 */
    suspend fun longPress(
        text: String? = null,
        x: Int? = null,
        y: Int? = null,
        durationMs: Long = 600L
    ): PhoneActionResult = withContext(Dispatchers.Main) {
        val service = PhoneControlService.current() ?: return@withContext notEnabled()

        var targetX = x
        var targetY = y
        if (!text.isNullOrBlank()) {
            if (System.currentTimeMillis() - ScreenSnapshot.updatedAt > SNAPSHOT_MAX_AGE_MS) {
                ScreenSnapshot.rebuildNow(service)
            }
            ScreenSnapshot.find(text)?.let {
                targetX = it.bounds.centerX()
                targetY = it.bounds.centerY()
            }
        }

        if (targetX == null || targetY == null) {
            return@withContext fail("没找到长按目标，可先 screen 看当前结构", "NODE_NOT_FOUND")
        }

        val path = Path().apply { moveTo(targetX.toFloat(), targetY.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(300L, 3_000L))
            )
            .build()
        val ok = service.dispatchGesture(gesture, null, null)
        if (ok) PhoneActionResult(true, "已长按 ($targetX, $targetY)") else fail("长按失败")
    }

    // ================= 音量 / 手电筒 / 亮度 / 按键 =================

    /** 音量：up / down / mute / unmute / set（配合 level 0–100）。 */
    suspend fun volume(action: String, level: Int? = null): PhoneActionResult =
        withContext(Dispatchers.IO) {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return@withContext fail("拿不到 AudioManager")
            val stream = AudioManager.STREAM_MUSIC
            val max = am.getStreamMaxVolume(stream)
            when (action.lowercase()) {
                "up" -> {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    PhoneActionResult(true, "音量已增大（${am.getStreamVolume(stream)}/$max）")
                }

                "down" -> {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    PhoneActionResult(true, "音量已减小（${am.getStreamVolume(stream)}/$max）")
                }

                "mute" -> {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    PhoneActionResult(true, "已静音")
                }

                "unmute" -> {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    PhoneActionResult(true, "已取消静音")
                }

                "set" -> {
                    val target = level ?: return@withContext fail("set 需要 level（0-100）")
                    val steps = target.coerceIn(0, 100) * max / 100
                    am.setStreamVolume(stream, steps, AudioManager.FLAG_SHOW_UI)
                    PhoneActionResult(true, "音量已设为 $target%（$steps/$max）")
                }

                else -> fail("音量动作只能是 up/down/mute/unmute/set", "INVALID_ACTION")
            }
        }

    /** 手电筒开关。 */
    suspend fun flashlight(enabled: Boolean): PhoneActionResult = withContext(Dispatchers.IO) {
        val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return@withContext fail("拿不到 CameraManager")
        val cameraId = runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return@withContext fail("设备没有可用闪光灯", "NO_FLASH")

        runCatching {
            cameraManager.setTorchMode(cameraId, enabled)
        }.fold(
            onSuccess = { PhoneActionResult(true, if (enabled) "手电筒已打开" else "手电筒已关闭") },
            onFailure = { fail("手电筒切换失败：${it.message}", "TORCH_FAILED") }
        )
    }

    /** 屏幕亮度（0–100）。需在「应用权限 → 修改系统设置」里授权。 */
    suspend fun setBrightness(percent: Int): PhoneActionResult = withContext(Dispatchers.IO) {
        if (!Settings.System.canWrite(appContext)) {
            return@withContext fail(
                "没有修改系统设置的权限：请到「应用权限 → 修改系统设置」里允许 Nox",
                "WRITE_SETTINGS_DENIED"
            )
        }
        val value = percent.coerceIn(1, 100) * 255 / 100
        val ok = runCatching {
            Settings.System.putInt(
                appContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                appContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                value
            )
        }.isSuccess
        if (ok) PhoneActionResult(true, "亮度已设为 ${percent.coerceIn(1, 100)}%") else fail("设置亮度失败")
    }

    /**
     * 按键。
     *
     * 音量与系统导航走系统 API；其余键需要注入输入事件权限，普通应用发不了，
     * 这里明确告知要走 Root 的 `input keyevent`，不假装成功。
     */
    suspend fun key(name: String): PhoneActionResult = when (name.lowercase()) {
        "back", "home", "recents", "notifications" -> globalAction(name)
        "volume_up" -> volume("up")
        "volume_down" -> volume("down")
        "mute" -> volume("mute")
        else -> fail(
            "按键 $name 需要注入输入事件权限，普通应用发不了；请用 Root 工具执行 input keyevent <KEYCODE>",
            "KEY_NOT_SUPPORTED"
        )
    }

    /** 等待一段时间，让界面完成动画或加载。 */
    suspend fun waitMs(millis: Long): PhoneActionResult {
        val clamped = millis.coerceIn(100L, 10_000L)
        delay(clamped)
        return PhoneActionResult(true, "已等待 $clamped ms")
    }

    // ================= 截图 =================

    /** 截图（Android 11 起无障碍可截屏），保存到应用私有目录并返回路径。 */
    suspend fun screenshot(): PhoneActionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return fail("截图需要 Android 11 及以上；低版本可用 Root 的 screencap", "UNSUPPORTED_VERSION")
        }
        val service = PhoneControlService.current() ?: return notEnabled()

        return suspendCancellableCoroutine { cont ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                appContext.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val outcome = runCatching {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer,
                                result.colorSpace
                            ) ?: error("解码截图失败")
                            val dir = File(appContext.cacheDir, "screenshots").apply { mkdirs() }
                            val file = File(dir, "screen_${System.currentTimeMillis()}.png")
                            FileOutputStream(file).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            bitmap.recycle()
                            file.absolutePath
                        }.also {
                            runCatching { result.hardwareBuffer.close() }
                        }

                        cont.resumeWith(
                            Result.success(
                                outcome.fold(
                                    onSuccess = { path -> PhoneActionResult(true, path) },
                                    onFailure = { e ->
                                        fail("截图保存失败：${e.message}", "SCREENSHOT_FAILED")
                                    }
                                )
                            )
                        )
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resumeWith(
                            Result.success(fail("截图失败（错误码 $errorCode）", "SCREENSHOT_FAILED"))
                        )
                    }
                }
            )
        }
    }

    // ================= 读屏 =================

    /**
     * 把当前界面压成文本。
     *
     * 直接渲染 [ScreenSnapshot]：与点击用的是同一份数据，模型照着这里的文本去点能命中；
     * 也不必重新遍历树。只保留定位所需字段，并限制条数与文本长度——
     * 整棵树动辄上千节点，全塞给模型既超预算也没有额外信息量。
     */
    suspend fun describeScreen(): PhoneActionResult = withContext(Dispatchers.Main) {
        val service = PhoneControlService.current() ?: return@withContext notEnabled()
        ScreenSnapshot.rebuildNow(service)

        val nodes = ScreenSnapshot.nodes()
        val sb = StringBuilder()
        val pkg = ScreenSnapshot.packageName.orEmpty()
        sb.append("前台应用：").append(pkg.ifBlank { "未知" }).append('\n')

        if (nodes.isEmpty()) {
            sb.append("（界面上没有可读的文本或可交互控件）")
            return@withContext PhoneActionResult(true, sb.toString())
        }

        val shown = nodes.take(MAX_NODES_IN_DUMP)
        shown.forEach { node ->
            val text = node.text?.take(MAX_TEXT_LENGTH)
            val desc = node.contentDescription?.take(MAX_TEXT_LENGTH)
            if (!text.isNullOrBlank()) sb.append("「").append(text).append("」")
            if (!desc.isNullOrBlank()) sb.append("[desc=").append(desc).append("]")
            sb.append(' ').append(simpleClassName(node.className))
            if (node.clickable) sb.append(" 可点")
            if (node.editable) sb.append(" 可输入")
            sb.append(" (").append(node.bounds.left).append(',')
                .append(node.bounds.top).append(',')
                .append(node.bounds.right).append(',')
                .append(node.bounds.bottom).append(')')
            sb.append('\n')
        }
        if (nodes.size > shown.size) {
            sb.append("…（还有 ").append(nodes.size - shown.size)
                .append(" 个节点未显示，可先滑动再看）")
        }
        PhoneActionResult(true, sb.toString().trimEnd())
    }

    private fun simpleClassName(name: String?): String =
        name?.substringAfterLast('.')?.take(28).orEmpty()

    // ================= 辅助 =================

    private fun fail(message: String, code: String? = null) =
        PhoneActionResult(false, message, code)

    private fun notEnabled() = PhoneActionResult(
        ok = false,
        detail = "手机控制未开启：请到「软件权限 → 手机控制」打开 Nox 的无障碍服务",
        errorCode = ERROR_NOT_ENABLED
    )

    private fun AccessibilityNodeInfo.recycleIfPossible() {
        runCatching { recycle() }
    }
}
