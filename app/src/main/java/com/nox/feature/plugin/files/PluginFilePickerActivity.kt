package com.nox.feature.plugin.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 系统文件选择器桥：插件调用 host.file.pick 时由宿主拉起，
 * 选完回传 uri 列表，内容读取与体积限制由调用方处理。
 */
class PluginFilePickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mimeTypes = intent.getStringArrayListExtra(EXTRA_MIME_TYPES).orEmpty()
        val multiple = intent.getBooleanExtra(EXTRA_MULTIPLE, false)
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (mimeTypes.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        startActivityForResult(picker, REQUEST_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_PICK) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val uris = if (resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            when {
                clip != null -> (0 until clip.itemCount).map { clip.getItemAt(it).uri }
                data.data != null -> listOf(data.data!!)
                else -> emptyList()
            }
        } else {
            emptyList()
        }
        PluginFilePickerSession.complete(uris.ifEmpty { null })
        finish()
    }

    companion object {
        const val EXTRA_MIME_TYPES = "mimeTypes"
        const val EXTRA_MULTIPLE = "multiple"
        private const val REQUEST_PICK = 4101
    }
}

/** 文件选择器的等待队列：同一时间只允许一个会话。 */
object PluginFilePickerSession {

    private var pending: CompletableDeferred<List<Uri>?>? = null

    suspend fun launch(
        context: Context,
        mimeTypes: List<String>,
        multiple: Boolean
    ): List<Uri>? = withContext(Dispatchers.Main) {
        pending?.complete(null)
        val deferred = CompletableDeferred<List<Uri>?>()
        pending = deferred
        val intent = Intent(context, PluginFilePickerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putStringArrayListExtra(PluginFilePickerActivity.EXTRA_MIME_TYPES, ArrayList(mimeTypes))
            putExtra(PluginFilePickerActivity.EXTRA_MULTIPLE, multiple)
        }
        context.startActivity(intent)
        deferred.await()
    }

    fun complete(uris: List<Uri>?) {
        pending?.complete(uris)
        pending = null
    }
}
