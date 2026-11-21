package com.nox.feature.settings.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateAssetSelectorTest {

    // ---- preferredAbiToken ----

    @Test
    fun preferredAbiToken_arm64() {
        assertEquals("arm64", preferredAbiToken(listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
    }

    @Test
    fun preferredAbiToken_x86_64() {
        assertEquals("x86_64", preferredAbiToken(listOf("x86_64", "x86")))
    }

    @Test
    fun preferredAbiToken_followsDevicePreferenceOrder() {
        // Build.SUPPORTED_ABIS 按优先级排序，取第一个认识的架构
        assertEquals("arm64", preferredAbiToken(listOf("arm64-v8a", "x86_64")))
        assertEquals("x86_64", preferredAbiToken(listOf("x86_64", "arm64-v8a")))
    }

    @Test
    fun preferredAbiToken_unknownOrEmpty() {
        assertNull(preferredAbiToken(listOf("armeabi-v7a", "mips")))
        assertNull(preferredAbiToken(emptyList()))
    }

    // ---- selectUpdateAsset ----

    private fun asset(name: String) = UpdateAsset(
        name = name,
        url = "https://example.com/$name",
        size = 1L,
        sha256 = null
    )

    @Test
    fun selectUpdateAsset_exactAbiMatch() {
        val assets = listOf(
            asset("nox-arm64-v1.12.0-rc4.apk"),
            asset("nox-universal-v1.12.0-rc4.apk"),
            asset("nox-x86_64-v1.12.0-rc4.apk")
        )
        assertEquals(
            "nox-arm64-v1.12.0-rc4.apk",
            selectUpdateAsset(assets, "v1.12.0-rc4", "arm64")?.name
        )
        assertEquals(
            "nox-x86_64-v1.12.0-rc4.apk",
            selectUpdateAsset(assets, "v1.12.0-rc4", "x86_64")?.name
        )
    }

    @Test
    fun selectUpdateAsset_matchesAiGoNamingWithDateAndSummary() {
        // 本仓库 CI 的产物命名：AiGo-<架构>-<versionName>-<摘要>.apk，
        // versionName 里带构建日期与 +hash，摘要含中文。改名后一度因只认 nox- 前缀
        // 导致应用内更新匹配不到任何包，退化成跳浏览器下载。
        val assets = listOf(
            asset("AiGo-arm64-1.0.0+20261121-附件可用性等5项.apk"),
            asset("AiGo-universal-1.0.0+20261121-附件可用性等5项.apk"),
            asset("AiGo-universal-1.0.0+20261121-mapping.txt.gz")
        )
        assertEquals(
            "AiGo-arm64-1.0.0+20261121-附件可用性等5项.apk",
            selectUpdateAsset(assets, "v1.0.0", "arm64")?.name
        )
        assertEquals(
            "AiGo-universal-1.0.0+20261121-附件可用性等5项.apk",
            selectUpdateAsset(assets, "v1.0.0", null)?.name
        )
    }

    @Test
    fun selectUpdateAsset_prefersAiGoOverLegacyNoxPrefix() {
        // 同一 Release 里如果同时存在两种前缀，优先本仓库的 AiGo-*。
        val assets = listOf(
            asset("nox-arm64-v1.0.0.apk"),
            asset("AiGo-arm64-1.0.0+20261121-摘要.apk")
        )
        assertEquals(
            "AiGo-arm64-1.0.0+20261121-摘要.apk",
            selectUpdateAsset(assets, "v1.0.0", "arm64")?.name
        )
    }

    @Test
    fun selectUpdateAsset_fallsBackToUniversal() {
        val assets = listOf(
            asset("nox-arm64-v1.12.0-rc4.apk"),
            asset("nox-universal-v1.12.0-rc4.apk")
        )
        // 目标架构的包缺失时退到 universal，而不是直接放弃应用内更新
        assertEquals(
            "nox-universal-v1.12.0-rc4.apk",
            selectUpdateAsset(assets, "v1.12.0-rc4", "x86_64")?.name
        )
        // 设备架构认不出来（null）同样退 universal
        assertEquals(
            "nox-universal-v1.12.0-rc4.apk",
            selectUpdateAsset(assets, "v1.12.0-rc4", null)?.name
        )
    }

    @Test
    fun selectUpdateAsset_ignoresMappingAndNonApk() {
        val assets = listOf(
            asset("nox-universal-v1.12.0-rc4-mapping.txt.gz"),
            asset("nox-universal-v1.12.0-rc4.apk")
        )
        assertEquals(
            "nox-universal-v1.12.0-rc4.apk",
            selectUpdateAsset(assets, "v1.12.0-rc4", "arm64")?.name
        )
    }

    @Test
    fun selectUpdateAsset_toleratesNameVariation() {
        // 精确名没命中（例如 CI 改了资产命名）时按「含架构段」退让
        val assets = listOf(
            asset("nox-arm64-1.12.0-rc4.apk"),
            asset("nox-universal-1.12.0-rc4.apk")
        )
        assertEquals(
            "nox-arm64-1.12.0-rc4.apk",
            selectUpdateAsset(assets, "v1.12.0-rc4", "arm64")?.name
        )
    }

    @Test
    fun selectUpdateAsset_nullWhenNoApk() {
        assertNull(
            selectUpdateAsset(
                listOf(asset("nox-universal-v1.12.0-rc4-mapping.txt.gz")),
                "v1.12.0-rc4",
                "arm64"
            )
        )
        assertNull(selectUpdateAsset(emptyList(), "v1.12.0-rc4", "arm64"))
    }
}
