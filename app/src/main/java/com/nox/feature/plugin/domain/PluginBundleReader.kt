package com.nox.feature.plugin.domain

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 拆「一个压缩包里放多个插件包」的分发形态。
 *
 * 单包：根目录直接有 plugin.json。
 * 组合包：根目录没有 plugin.json，但一级子目录里有；每个这样的子目录算一个插件包。
 *
 * 只认一级子目录，避免把插件包内部的嵌套目录误认成另一个插件。
 */
object PluginBundleReader {

    const val MANIFEST = "plugin.json"

    /** 根目录没有清单、但一级子目录里有清单：按组合包处理。 */
    fun isBundle(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.getEntry(MANIFEST) == null && bundleDirectories(zip).isNotEmpty()
        }
    }.getOrDefault(false)

    fun bundleDirectories(file: File): List<String> =
        runCatching { ZipFile(file).use { bundleDirectories(it) } }.getOrDefault(emptyList())

    private fun bundleDirectories(zip: ZipFile): List<String> =
        zip.entries().asSequence()
            .map { it.name }
            .filter { it.endsWith("/$MANIFEST") }
            .map { it.removeSuffix("/$MANIFEST") }
            .filter { it.isNotEmpty() && '/' !in it && it != "." && it != ".." }
            .distinct()
            .sorted()
            .toList()

    /**
     * 把 [subDir] 目录下的内容重打包成独立的插件包字节流。
     *
     * 条目名去掉前缀后原样保留，越界路径由安装侧的清单/包校验负责拦截。
     */
    fun extract(file: File, subDir: String): ByteArray {
        val prefix = "$subDir/"
        val out = ByteArrayOutputStream()
        ZipFile(file).use { zip ->
            ZipOutputStream(out).use { zos ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith(prefix) }
                    .forEach { entry ->
                        val name = entry.name.removePrefix(prefix)
                        if (name.isEmpty()) return@forEach
                        zos.putNextEntry(ZipEntry(name))
                        zip.getInputStream(entry).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
            }
        }
        return out.toByteArray()
    }
}
