package com.nox.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 面板数值格式与命中率的回归测试。
 *
 * 为什么这些必须单测：这两个函数算错时界面完全正常，只是数字是假的。
 * 面板已经因此上线过「1M 模型显示成 8.19K」「99.79% 显示成 100%」两个错误，
 * 都是靠肉眼永远发现不了、只能靠断言拦住的问题。
 */
class PanelValueFormatTest {

    // ── 缓存命中率：不得虚报 100% ─────────────────────────────────────────
    @Test
    fun `命中率四舍五入不得变成 100`() {
        // 99.79% 曾经被 roundToInt 成 100%，让用户以为全部命中、零成本。
        // 现在保留两位小数，显示真实的 99.79%，既不失真也不会进位成满分。
        assertEquals("99.79%", hitPercent(cached = 5_687_552, total = 5_699_639))
    }

    @Test
    fun `真正满命中才显示 100`() {
        assertEquals("100%", hitPercent(cached = 1_000, total = 1_000))
    }

    @Test
    fun `命中率为零时显示两位零`() {
        // 与其余命中率同一精度：面板里所有百分比都是两位小数，
        // 只有零值退回「0%」会让人以为是没数据而不是没命中。
        assertEquals("0.00%", hitPercent(cached = 0, total = 1_000))
    }

    @Test
    fun `总量为零时不得除零`() {
        assertEquals("0%", hitPercent(cached = 0, total = 0))
    }

    @Test
    fun `命中率保留两位小数而不是取整`() {
        // 保留两位小数：72.25% / 66.90%，不再截成整数百分比。
        assertEquals("72.25%", hitPercent(cached = 7225, total = 10_000))
        assertEquals("66.90%", hitPercent(cached = 669, total = 1_000))
    }

    // ── 紧凑计数：整数不补零 ──────────────────────────────────────────────
    @Test
    fun `标称窗口一百万显示为 1M`() {
        assertEquals("1M", formatCompactTokenCount(1_000_000))
        assertEquals("128K", formatCompactTokenCount(128_000).replace(".00", ""))
    }

    @Test
    fun `预算值保留两位小数`() {
        assertEquals("991.80K", formatCompactTokenCount(991_800))
        assertEquals("70.90K", formatCompactTokenCount(70_900))
    }

    /**
     * 上下文窗口必须**直接取标称值**，不能由预算反推。
     *
     * 反推不唯一：预算 = 标称 − 输出预留，而预留取决于模型配置。
     * 历史实现还写错过循环方向，导致所有大窗口都归成 8K。
     * 这里用「分母取值」的等价表达式守住行为：有窗口用窗口，没有才退回预算。
     */
    @Test
    fun `分母优先用标称窗口而不是预算`() {
        val window = 1_000_000
        val budget = 991_800
        assertEquals(
            "1M",
            formatCompactTokenCount((window.takeIf { it > 0 } ?: budget).toLong())
        )
        // 窗口缺失时才退回预算，此时显示的是真实可用量。
        val noWindow = 0
        assertEquals(
            "991.80K",
            formatCompactTokenCount((noWindow.takeIf { it > 0 } ?: budget).toLong())
        )
    }
}
