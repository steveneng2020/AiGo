package com.nox.feature.device

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.nox.core.util.FileLogger
import java.util.concurrent.atomic.AtomicBoolean

/** 界面节点的不可变快照。只留定位所需字段，节点本身不跨线程保留。 */
data class NodeSnapshot(
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean
) {
    /** 命中给定文本或描述（包含匹配、忽略大小写）。 */
    fun matches(query: String): Boolean =
        text?.contains(query, ignoreCase = true) == true ||
            contentDescription?.contains(query, ignoreCase = true) == true
}

/**
 * 常驻的界面快照。
 *
 * 无障碍事件到达时就地重建，点击时直接查这份快照——不用每次从 root 重新遍历整棵树，
 * 打开应用后立刻就能点。快照只存不可变数据（字符串、Rect），不持有 AccessibilityNodeInfo，
 * 避免节点失效带来的崩溃。
 */
object ScreenSnapshot {

    private const val TAG = "ScreenSnapshot"

    /** 重建节流：事件可能连续几十条，太频繁重建反而抢主线程。 */
    private const val REBUILD_INTERVAL_MS = 250L

    private const val MAX_NODES = 800

    @Volatile
    private var nodes: List<NodeSnapshot> = emptyList()

    @Volatile
    var packageName: String? = null
        private set

    @Volatile
    var updatedAt: Long = 0L
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val pending = AtomicBoolean(false)
    private var lastRebuild = 0L

    /** 当前快照里的节点（只读）。 */
    fun nodes(): List<NodeSnapshot> = nodes

    fun isEmpty(): Boolean = nodes.isEmpty()

    /** 事件驱动的重建请求，带节流。 */
    fun requestRebuild(service: AccessibilityService) {
        val now = System.currentTimeMillis()
        if (now - lastRebuild < REBUILD_INTERVAL_MS) return
        if (!pending.compareAndSet(false, true)) return
        handler.postDelayed({
            pending.set(false)
            rebuild(service)
        }, REBUILD_INTERVAL_MS)
    }

    /** 立即重建（点击未命中、读屏时调用）。 */
    fun rebuildNow(service: AccessibilityService) {
        rebuild(service)
    }

    private fun rebuild(service: AccessibilityService) {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return
        val collected = ArrayList<NodeSnapshot>(128)
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        var visited = 0
        try {
            while (stack.isNotEmpty() && visited < MAX_NODES) {
                val (node, depth) = stack.removeLast()
                visited++
                val text = node.text?.toString()?.take(120)
                val desc = node.contentDescription?.toString()?.take(120)
                val clickable = node.isClickable
                val editable = node.isEditable
                if ((!text.isNullOrBlank() || !desc.isNullOrBlank() || clickable || editable) &&
                    node.isVisibleToUser
                ) {
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    if (!bounds.isEmpty) {
                        collected.add(
                            NodeSnapshot(
                                text = text,
                                contentDescription = desc,
                                className = node.className?.toString(),
                                bounds = bounds,
                                clickable = clickable,
                                editable = editable
                            )
                        )
                    }
                }
                if (depth < 30) {
                    for (i in node.childCount - 1 downTo 0) {
                        node.getChild(i)?.let { stack.add(it to depth + 1) }
                    }
                }
            }
            nodes = collected
            packageName = root.packageName?.toString()
            updatedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            FileLogger.w(TAG, "重建界面快照失败", e)
        } finally {
            runCatching { root.recycle() }
        }
    }

    /** 按文本或描述查快照。 */
    fun find(query: String): NodeSnapshot? {
        val current = nodes
        // 先找完全相等的，命中概率最高也最准
        current.firstOrNull {
            it.text.equals(query, ignoreCase = true) || it.contentDescription.equals(query, ignoreCase = true)
        }?.let { return it }
        return current.firstOrNull { it.matches(query) }
    }

    fun findEditable(): NodeSnapshot? = nodes.firstOrNull { it.editable }
}
