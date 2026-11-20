package com.nox.feature.plugin.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 上传进度与结果。用户只需要选包，这里只做状态回显，不再要求任何配置。
 */
@Composable
internal fun PluginPublishStatusDialog(
    busy: Boolean,
    statusText: String,
    onDismiss: () -> Unit,
    onPickPackage: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (busy) "正在上传" else "上传插件") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 10.dp),
                            strokeWidth = 2.dp
                        )
                        Text("正在提交，请稍候…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (busy) FontWeight.Normal else FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            if (onPickPackage != null) {
                TextButton(enabled = !busy, onClick = onPickPackage) { Text("选择插件包") }
            } else {
                TextButton(enabled = !busy, onClick = onDismiss) { Text("知道了") }
            }
        },
        dismissButton = if (onPickPackage != null) {
            { TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") } }
        } else {
            null
        }
    )
}
