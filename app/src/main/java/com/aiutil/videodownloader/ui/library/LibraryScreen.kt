package com.aiutil.videodownloader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiutil.videodownloader.App
import com.aiutil.videodownloader.model.DownloadItem
import com.aiutil.videodownloader.model.DownloadStatus
import com.aiutil.videodownloader.ui.components.VideoThumbnail
import com.aiutil.videodownloader.ui.downloads.EmptyMessage
import com.aiutil.videodownloader.util.Clip
import com.aiutil.videodownloader.util.Format
import com.aiutil.videodownloader.util.MediaIntents
import com.aiutil.videodownloader.util.Names

@Composable
fun LibraryScreen(
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val all by App.store.items.collectAsStateWithLifecycle()
    val completed = all
        .filter { it.status == DownloadStatus.COMPLETED }
        .sortedByDescending { it.completedAt ?: it.createdAt }

    var pendingDelete by remember { mutableStateOf<DownloadItem?>(null) }

    if (completed.isEmpty()) {
        EmptyMessage(text = "저장된 영상이 없습니다.", modifier = modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(completed, key = { it.id }) { item ->
            LibraryRow(
                item = item,
                onOpenLink = onOpenLink,
                onDeleteRequest = { pendingDelete = item },
            )
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("영상을 삭제할까요?") },
            text = { Text("${target.fileName}\n기기에 저장된 파일까지 지워집니다.") },
            confirmButton = {
                TextButton(onClick = {
                    App.downloads.remove(target.id, deleteFile = true)
                    pendingDelete = null
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun LibraryRow(
    item: DownloadItem,
    onOpenLink: (String) -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val mimeType = Names.mimeForExtension(Names.extensionOf(item.fileName))
    val pageUrl = item.pageUrl?.takeIf { it.isNotBlank() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { MediaIntents.play(context, item.outputUri, mimeType) }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VideoThumbnail(
                uri = item.outputUri,
                modifier = Modifier
                    .size(width = 96.dp, height = 58.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )

            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${Format.bytes(item.totalBytes)} · ${Format.timestamp(item.completedAt ?: item.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "더보기")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("공유") },
                        onClick = {
                            menuOpen = false
                            MediaIntents.share(context, item.outputUri, mimeType)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("원본 페이지 열기") },
                        enabled = pageUrl != null,
                        onClick = {
                            menuOpen = false
                            pageUrl?.let(onOpenLink)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("영상 주소 복사") },
                        onClick = {
                            menuOpen = false
                            Clip.copy(context, item.url)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("삭제") },
                        onClick = {
                            menuOpen = false
                            onDeleteRequest()
                        },
                    )
                }
            }
        }
    }
}
