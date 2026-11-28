package com.nox.feature.settings.data.local

import android.content.Context
import com.nox.core.util.FileLogger
import com.nox.feature.settings.domain.model.ModelMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自定义模型元数据的本地存储（存于 app 私有目录）。
 * key 为「提供商ID:模型名」复合键；优先级高于拉取/内置元数据。
 *
 * 必须用 filesDir 而不是 cacheDir：这里是用户手改的参数，丢了没法从服务端拉回来，
 * 而 cacheDir 是系统随时可清理的临时目录（空间紧张、清除缓存、部分 ROM 定期清）。
 */
@Singleton
class CustomModelMetadataStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    suspend fun all(): Map<String, ModelMetadata> = mutex.withLock {
        withContext(Dispatchers.IO) { read() }
    }

    suspend fun get(providerId: String, model: String): ModelMetadata? =
        all()[key(providerId, model)]

    suspend fun put(providerId: String, model: String, metadata: ModelMetadata) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val map = read().toMutableMap()
            map[key(providerId, model)] = metadata
            write(map)
        }
    }

    suspend fun remove(providerId: String, model: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val map = read().toMutableMap()
            if (map.remove(key(providerId, model)) != null) {
                write(map)
            }
        }
    }

    private fun key(providerId: String, model: String): String = "$providerId:$model"

    private fun read(): Map<String, ModelMetadata> {
        val f = file()
        if (!f.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, ModelMetadata>>(f.readText(Charsets.UTF_8))
        }.getOrElse {
            FileLogger.w(TAG, "读取自定义模型元数据失败", it)
            emptyMap()
        }
    }

    private fun write(map: Map<String, ModelMetadata>) {
        runCatching {
            file().writeText(json.encodeToString(map), Charsets.UTF_8)
        }.onFailure {
            FileLogger.w(TAG, "写入自定义模型元数据失败", it)
        }
    }

    private fun file(): File {
        val target = File(context.filesDir, FILE_NAME)
        // 一次性迁移：旧版本把文件放在 cacheDir，升级后不清过来用户会以为配置凭空消失
        if (!target.exists()) {
            val legacy = File(context.cacheDir, FILE_NAME)
            if (legacy.isFile) {
                runCatching {
                    context.filesDir.mkdirs()
                    legacy.copyTo(target, overwrite = true)
                    legacy.delete()
                }.onFailure {
                    FileLogger.w(TAG, "迁移自定义模型元数据失败", it)
                }
            }
        }
        return target
    }

    private companion object {
        const val TAG = "CustomModelMetadataStore"
        const val FILE_NAME = "custom-model-metadata.json"
    }
}