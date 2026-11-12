package com.aiutil.videodownloader.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var pendingSingleDelete by remember { mutableStateOf<DownloadItem?>(null) }
    var confirmBulkDelete by remember { mutableStateOf(false) }

    // 목록에서 사라진 항목이 선택에 남아 있으면 개수가 어긋난다.
    LaunchedEffect(completed) {
        val ids = completed.mapTo(HashSet()) { it.id }
        if (selectedIds.any { it !in ids }) selectedIds = selectedIds.intersect(ids)
    }

    val selectionMode = selectedIds.isNotEmpty()
    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }

    if (completed.isEmpty()) {
        EmptyMessage(text = "저장된 영상이 없습니다.", modifier = modifier)
        return
    }

    Column(modifier.fillMaxSize()) {
        if (selectionMode) {
            SelectionBar(
                selectedCount = selectedIds.size,
                totalCount = completed.size,
                onSelectAll = { selectedIds = completed.mapTo(HashSet()) { it.id } },
                onClear = { selectedIds = emptySet() },
                onDelete = { confirmBulkDelete = true },
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(completed, key = { it.id }) { item ->
                LibraryRow(
                    item = item,
                    selectionMode = selectionMode,
                    selected = item.id in selectedIds,
                    onToggleSelect = {
                        selectedIds = if (item.id in selectedIds) {
                            selectedIds - item.id
                        } else {
                            selectedIds + item.id
                        }
                    },
                    onOpenLink = onOpenLink,
                    onDeleteRequest = { pendingSingleDelete = item },
                )
            }
        }
    }

    pendingSingleDelete?.let { target ->
        DeleteDialog(
            title = "영상을 삭제할까요?",
            message = "${target.fileName}\n기기에 저장된 파일까지 지워집니다.",
            onConfirm = {
                App.downloads.remove(target.id, deleteFile = true)
                pendingSingleDelete = null
            },
            onDismiss = { pendingSingleDelete = null },
        )
    }

    if (confirmBulkDelete) {
        DeleteDialog(
            title = "선택한 ${selectedIds.size}개를 삭제할까요?",
            message = "기기에 저장된 파일까지 지워집니다. 되돌릴 수 없습니다.",
            onConfirm = {
                App.downloads.removeAll(selectedIds, deleteFile = true)
                selectedIds = emptySet()
                confirmBulkDelete = false
            },
            onDismiss = { confirmBulkDelete = false },
        )
    }
}

@Composable
private fun SelectionBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = "선택 해제")
            }
            Text(
                text = "${selectedCount}개 선택",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSelectAll, enabled = selectedCount < totalCount) {
                Text("전체 선택")
            }
            TextButton(onClick = onDelete) { Text("삭제") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRow(
    item: DownloadItem,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onOpenLink: (String) -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val mimeType = Names.mimeForExtension(Names.extensionOf(item.fileName))
    val pageUrl = item.pageUrl?.takeIf { it.isNotBlank() }

    Card(
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                // 선택 중에는 탭이 선택 전환, 평소에는 재생.
                onClick = {
                    if (selectionMode) onToggleSelect()
                    else MediaIntents.play(context, item.outputUri, mimeType)
                },
                onLongClick = onToggleSelect,
            )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
            }

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
                    // 형식을 보여 준다. 재생이 안 될 때 무엇 때문인지 바로 알 수 있다.
                    text = "${Names.extensionOf(item.fileName).uppercase()} · " +
                        "${Format.bytes(item.totalBytes)} · " +
                        Format.timestamp(item.completedAt ?: item.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                // TS는 스트리밍 조각을 그대로 이어 붙인 형식이다. MP4 변환이 실패했을
                // 때만 남으며, 갤러리 앱이 인식하지 못하는 경우가 있어 알려 준다.
                if (Names.extensionOf(item.fileName) == "ts") {
                    Text(
                        text = "TS 형식 · MP4 변환 실패, 일부 재생기에서만 열립니다",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            // 선택 중에는 개별 메뉴를 숨긴다. 두 조작이 겹치면 헷갈린다.
            if (!selectionMode) {
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
                            text = { Text("다시 받기") },
                            onClick = {
                                menuOpen = false
                                App.downloads.redownload(item)
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
                            text = { Text("선택") },
                            onClick = {
                                menuOpen = false
                                onToggleSelect()
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
}

@Composable
private fun DeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("삭제") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
