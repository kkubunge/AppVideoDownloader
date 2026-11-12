package com.aiutil.videodownloader.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import com.aiutil.videodownloader.model.DownloadHistoryEntry
import com.aiutil.videodownloader.model.DownloadStatus
import com.aiutil.videodownloader.util.Format

/**
 * 전에 받은 적 있는 영상을 다시 받으려 할 때 뜨는 확인 팝업.
 * 과거 기록을 보여주고 진행 여부를 고르게 한다.
 */
@Composable
fun DownloadHistoryDialog(
    entries: List<DownloadHistoryEntry>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val completed = entries.count { it.status == DownloadStatus.COMPLETED }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("전에 받은 적이 있습니다") },
        text = {
            Column {
                Text(
                    text = if (completed > 0) {
                        "이 영상은 이미 ${completed}번 받았습니다. 다시 받으면 파일이 하나 더 생깁니다."
                    } else {
                        "이 영상을 받으려고 시도한 기록이 있습니다."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .padding(top = 12.dp)
                ) {
                    items(entries, key = { it.downloadId }) { entry ->
                        HistoryRow(entry)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("다시 받기") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}

/** 메뉴에서 여는 전체 다운로드 이력 목록. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadHistorySheet(
    entries: List<DownloadHistoryEntry>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("다운로드 이력 ${entries.size}건", style = MaterialTheme.typography.titleMedium)
            if (entries.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("전체 삭제") }
            }
        }
        HorizontalDivider()

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "아직 다운로드 기록이 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@ModalBottomSheet
        }

        LazyColumn(
            modifier = Modifier.heightIn(max = 420.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            items(entries, key = { it.downloadId }) { entry ->
                HistoryRow(entry)
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: DownloadHistoryEntry) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append(Format.timestamp(entry.finishedAt ?: entry.recordedAt))
                append(" · ")
                append(statusLabel(entry.status))
                if (entry.sizeBytes > 0) append(" · ${Format.bytes(entry.sizeBytes)}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.status == DownloadStatus.COMPLETED) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.COMPLETED -> "완료"
    DownloadStatus.FAILED -> "실패"
    DownloadStatus.CANCELED -> "취소"
    DownloadStatus.PAUSED -> "일시정지"
    DownloadStatus.RUNNING, DownloadStatus.QUEUED -> "진행 중"
}
