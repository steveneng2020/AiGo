package com.nox.feature.plugin.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单对 `screens` / `styleTokens` 的解析。
 *
 * 重点回归「未声明 styleTokens」这一条：早期实现把「未声明」当成清单作废，
 * 却没记录错误，上层 `requireNotNull(contributes)` 直接抛 IllegalArgumentException，
 * 表现为装过插件的机器一启动就崩。
 */
class PluginManifestStyleTest {

    private fun manifest(contributes: String) = """
        {
          "manifestVersion": 1,
          "id": "demo.test",
          "name": "Demo",
          "version": "1.0.0",
          "publisher": "demo",
          "repository": "https://github.com/demo/demo",
          "description": "demo",
          "license": "MIT",
          "engines": { "pluginApi": "^1.0.0" },
          "contributes": $contributes
        }
    """.trimIndent()

    private val screenEntry =
        """{ "id": "s1", "title": "S", "target": "chat", "file": "ui/chat.json" }"""

    @Test
    fun `manifest without styleTokens parses`() {
        val result = PluginManifestParser.parse(manifest("""{ "screens": [ $screenEntry ] }"""))
        assertTrue("未声明 styleTokens 必须解析成功，实际：$result", result is ManifestParseResult.Success)
        val parsed = (result as ManifestParseResult.Success).manifest
        assertEquals(1, parsed.screens.size)
        assertEquals("chat", parsed.screens.first().target)
        assertNull(parsed.styleTokens)
    }

    @Test
    fun `valid styleTokens parsed`() {
        val result = PluginManifestParser.parse(
            manifest(
                """
                {
                  "styleTokens": {
                    "chatInputRadius": 26,
                    "bubbleRadius": 18,
                    "topBarColor": "surface",
                    "iconColor": "#FF3482FF"
                  }
                }
                """.trimIndent()
            )
        )
        assertTrue("合法 styleTokens 应解析成功，实际：$result", result is ManifestParseResult.Success)
        val tokens = (result as ManifestParseResult.Success).manifest.styleTokens
        assertEquals(26f, tokens?.chatInputRadius)
        assertEquals(18f, tokens?.bubbleRadius)
        assertEquals("surface", tokens?.topBarColor)
        assertEquals("#FF3482FF", tokens?.iconColor)
    }

    @Test
    fun `empty styleTokens rejected with error`() {
        val result = PluginManifestParser.parse(manifest("""{ "styleTokens": {} }"""))
        assertTrue("空 styleTokens 应判无效", result is ManifestParseResult.Failure)
        val errors = (result as ManifestParseResult.Failure).errors
        assertTrue("必须留下错误而不是静默作废", errors.isNotEmpty())
    }

    @Test
    fun `out of range styleTokens rejected`() {
        val result = PluginManifestParser.parse(manifest("""{ "styleTokens": { "chatInputRadius": 99 } }"""))
        assertTrue("圆角越界应判无效", result is ManifestParseResult.Failure)
    }

    @Test
    fun `screens requires exactly one source`() {
        val both = """{ "id": "s2", "title": "S", "target": "chat", "file": "ui/a.json", "handler": "ui.a" }"""
        assertTrue(
            "同时写 file 与 handler 应判无效",
            PluginManifestParser.parse(manifest("""{ "screens": [ $both ] }""")) is ManifestParseResult.Failure
        )
        val neither = """{ "id": "s3", "title": "S", "target": "chat" }"""
        assertTrue(
            "两者都不写应判无效",
            PluginManifestParser.parse(manifest("""{ "screens": [ $neither ] }""")) is ManifestParseResult.Failure
        )
    }

    @Test
    fun `screen target must be known`() {
        val bad = """{ "id": "s4", "title": "S", "target": "settings2", "file": "ui/a.json" }"""
        assertTrue(
            "未知 target 应判无效",
            PluginManifestParser.parse(manifest("""{ "screens": [ $bad ] }""")) is ManifestParseResult.Failure
        )
    }

    @Test
    fun `screen handler requires runtime`() {
        val byHandler = """{ "id": "s5", "title": "S", "target": "chat", "handler": "ui.panel" }"""
        val result = PluginManifestParser.parse(manifest("""{ "screens": [ $byHandler ] }"""))
        assertTrue("声明 handler 但无 runtime 应判无效", result is ManifestParseResult.Failure)
    }

    @Test
    fun `merge takes each field from the first plugin that declares it`() {
        val input = PluginStyleTokens(chatInputRadius = 26f, chatInputColor = "primary")
        val bubble = PluginStyleTokens(bubbleRadius = 0f, bubbleUserColor = "#FF7C4DFF")
        val merged = mergeStyleTokens(listOf(bubble, input))
        // 各自独有的字段都保留
        assertEquals(0f, merged?.bubbleRadius)
        assertEquals(26f, merged?.chatInputRadius)
        assertEquals("primary", merged?.chatInputColor)
        assertEquals("#FF7C4DFF", merged?.bubbleUserColor)
    }

    @Test
    fun `merge prefers earlier plugin on conflict`() {
        val first = PluginStyleTokens(chatInputColor = "primary")
        val second = PluginStyleTokens(chatInputColor = "surface")
        assertEquals("primary", mergeStyleTokens(listOf(first, second))?.chatInputColor)
        assertEquals("surface", mergeStyleTokens(listOf(second, first))?.chatInputColor)
    }

    @Test
    fun `merge of nothing is null`() {
        assertNull(mergeStyleTokens(emptyList()))
        assertNull(mergeStyleTokens(listOf(PluginStyleTokens())))
    }
}
