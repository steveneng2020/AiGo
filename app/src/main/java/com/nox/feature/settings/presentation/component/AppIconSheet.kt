package com.nox.feature.settings.presentation.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import com.nox.R
import com.nox.core.theme.semanticColors
import com.nox.core.ui.AdaptiveModalBottomSheet
import com.nox.feature.settings.data.repository.AppIconOption

/**
 * 图标选择弹层：九套图标按网格排列，点选即切换并回到桌面。
 *
 * 三列网格而不是单行：九套图标挤在一行里每格只有 40dp 左右，预览看不清也点不准。
 * 预览走 [AppIconOption.previewBitmap] 而不是 painterResource——鲲的六套与原有三套
 * 都是 adaptive-icon XML，painterResource 会直接抛异常。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppIconSheet(
    current: AppIconOption,
    onDismiss: () -> Unit,
    onSelect: (AppIconOption) -> Unit
) {
    val context = LocalContext.current
    val columns = 3
    val options = AppIconOption.entries

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.app_icon_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.app_icon_sheet_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            options.chunked(columns).forEach { rowOptions ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowOptions.forEach { option ->
                        IconCard(
                            option = option,
                            selected = option == current,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(option) }
                        )
                    }
                    // 末行不满时补空位，避免最后几个图标被拉宽。
                    repeat(columns - rowOptions.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun IconCard(
    option: AppIconOption,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp)
    val background = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        MaterialTheme.semanticColors.capsuleSurface
    }
    val bitmap = remember(option) {
        AppIconOption.previewBitmap(context, option, 192)?.asImageBitmap()
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(background)
            .then(
                if (selected) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Fit
            )
        } else {
            // 极端情况下图标解析失败的占位，保持格子高度一致
            Spacer(Modifier.size(56.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(option.labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}
