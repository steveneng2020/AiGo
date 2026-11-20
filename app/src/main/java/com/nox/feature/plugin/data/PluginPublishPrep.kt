package com.nox.feature.plugin.data

import com.nox.feature.plugin.domain.ManifestParseResult
import com.nox.feature.plugin.domain.PluginManifestParser
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 从插件包里读出发布所需的元数据。
 *
 * 只解压 `plugin.json` 一个条目，不做整包解压——上传前校验不落地文件，
 * 也避免恶意包在解析阶段就把磁盘撑满。
 */
@Singleton
class PluginPublishPrep @Inject constructor() {

    fun readMeta(packageFile: File): Result<PluginPublishMeta> = runCatching {
        require(packageFile.isFile && packageFile.length() > 0L) { "插件包不可读" }

        val manifestText = ZipFile(packageFile).use { zip ->
            val entry = zip.getEntry(MANIFEST_FILE)
                ?: zip.entries().asSequence().firstOrNull {
                    it.name.substringAfterLast('/') == MANIFEST_FILE
                }
                ?: error("插件包内没有 $MANIFEST_FILE")
            if (entry.size > MAX_MANIFEST_BYTES) error("plugin.json 过大")
            zip.getInputStream(entry).use { input ->
                input.readBytes().decodeToString()
            }
        }

        when (val parsed = PluginManifestParser.parse(manifestText)) {
            is ManifestParseResult.Success -> {
                val manifest = parsed.manifest
                PluginPublishMeta(
                    id = manifest.id,
                    name = primaryText(manifest.name),
                    version = manifest.version.toString(),
                    description = primaryText(manifest.description),
                    author = manifest.publisher,
                    license = manifest.license.ifBlank { "MIT" }
                )
            }

            is ManifestParseResult.Failure -> error(
                "plugin.json 校验失败：${parsed.errors.firstOrNull()?.detail?.ifBlank { parsed.errors.first().code.name } ?: "格式不正确"}"
            )
        }
    }

    private fun primaryText(text: com.nox.feature.plugin.domain.LocalizedText): String =
        text.resolve("zh-CN")

    private companion object {
        const val MANIFEST_FILE = "plugin.json"
        const val MAX_MANIFEST_BYTES = 1024L * 1024L
    }
}
