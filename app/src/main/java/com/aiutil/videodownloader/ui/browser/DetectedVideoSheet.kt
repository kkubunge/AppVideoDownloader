package com.aiutil.videodownloader.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiutil.videodownloader.model.DetectedVideo
import com.aiutil.videodownloader.model.MediaKind
import com.aiutil.videodownloader.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectedVideoSheet(
    videos: List<DetectedVideo>,
    onDownload: (DetectedVideo) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("찾은 영상 ${videos.size}개", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClear) { Text("목록 비우기") }
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.heightIn(max = 420.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(videos, key = { it.url }) { video ->
                DetectedVideoRow(video = video, onDownload = { onDownload(video) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetectedVideoRow(video: DetectedVideo, onDownload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDownload)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = video.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                AssistChip(
                    onClick = onDownload,
                    label = { Text(if (video.kind == MediaKind.HLS) "스트리밍" else "파일") },
                )
                Text(
                    text = buildString {
                        append(Format.host(video.url))
                        if (video.sizeBytes > 0) append(" · ${Format.bytes(video.sizeBytes)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Button(onClick = onDownload, modifier = Modifier.padding(start = 12.dp)) {
            Icon(Icons.Default.Download, contentDescription = null)
            Text("받기", modifier = Modifier.padding(start = 6.dp))
        }
    }
}
