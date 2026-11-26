package com.nox.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 单条消息的缓存命中率展示（[formatCacheHitRate]）。
 *
 * 该函数在 1.0.4 起与面板共用 [hitPercent]，统一显示两位小数，
 * 因此这里的期望值也必须是两位小数——一位整数的期望值会让
 * 同一份数据在气泡里显示成 80%，在面板里显示成 80.00%，属于回归。
 */
class CacheHitRateTest {

    @Test
    fun ratio_of_cached_over_total_input() {
        assertEquals("80.00%", formatCacheHitRate(inputTokens = 1_000, cachedInputTokens = 800))
        assertEquals("50.00%", formatCacheHitRate(inputTokens = 2_000, cachedInputTokens = 1_000))
    }

    @Test
    fun keeps_two_decimals() {
        // 2/3 = 66.666…% → 66.67%；1/3 = 33.333…% → 33.33%
        assertEquals("66.67%", formatCacheHitRate(inputTokens = 3, cachedInputTokens = 2))
        assertEquals("33.33%", formatCacheHitRate(inputTokens = 3, cachedInputTokens = 1))
    }

    @Test
    fun anthropic_style_input_without_cache_read_is_capped_at_full() {
        // Anthropic 的 input_tokens 不含 cache_read，比值会超过 1，展示封顶 100%
        assertEquals("100%", formatCacheHitRate(inputTokens = 100, cachedInputTokens = 5_000))
    }

    @Test
    fun no_cache_hit_or_no_usage_shows_nothing() {
        assertNull(formatCacheHitRate(inputTokens = 1_000, cachedInputTokens = 0))
        assertNull(formatCacheHitRate(inputTokens = 0, cachedInputTokens = 0))
        assertNull(formatCacheHitRate(inputTokens = 0, cachedInputTokens = 500))
    }
}
