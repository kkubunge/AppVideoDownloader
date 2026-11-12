package com.aiutil.videodownloader.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiutil.videodownloader.App
import com.aiutil.videodownloader.model.DownloadItem
import com.aiutil.videodownloader.model.DownloadStatus
import com.aiutil.videodownloader.model.MediaKind
import com.aiutil.videodownloader.util.Clip
import com.aiutil.videodownloader.util.Format

@Composable
fun DownloadsScreen(
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val all by App.store.items.collectAsStateWithLifecycle()
    val items = all.filter { it.status != DownloadStatus.COMPLETED }

    if (items.isEmpty()) {
        EmptyMessage(
            text = "진행 중인 다운로드가 없습니다.\n브라우저 탭에서 영상이 있는 페이지를 열어 보세요.",
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        BulkActionBar(items)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { item ->
                DownloadCard(item = item, onOpenLink = onOpenLink)
            }
        }
    }
}

/**
 * 목록 전체를 한 번에 다루는 막대.
 *
 * 받다가 자리를 옮기거나 데이터를 아껴야 할 때 항목마다 눌러 멈추는 것은 번거롭다.
 */
@Composable
private fun BulkActionBar(items: List<DownloadItem>) {
    val active = items.count { it.status.isActive }
    val paused = items.count { it.status == DownloadStatus.PAUSED }
    val failed = items.count {
        it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELED
    }
    var menuOpen by remember { mutableStateOf(false) }

    Surface(tonalElevation = 2.dp) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Text(
                text = buildList {
                    if (active > 0) add("진행 중 $active")
                    if (paused > 0) add("일시정지 $paused")
                    if (failed > 0) add("실패 $failed")
                }.joinToString(" · ").ifEmpty { "대기 중인 항목 없음" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { App.downloads.pauseAll() },
                    enabled = active > 0,
                ) { Text("모두 일시정지") }

                TextButton(
                    onClick = { App.downloads.resumeAll() },
                    enabled = paused > 0,
                ) { Text("모두 이어받기") }

                Spacer(Modifier.weight(1f))

                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "일괄 작업")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("실패한 항목 다시 시도") },
                            enabled = failed > 0,
                            onClick = {
                                menuOpen = false
                                App.downloads.retryAllFailed()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("실패·취소 항목 지우기") },
                            enabled = failed > 0,
                            onClick = {
                                menuOpen = false
                                App.downloads.clearFailed()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(item: DownloadItem, onOpenLink: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                LinkMenu(item = item, onOpenLink = onOpenLink)
            }

            Text(
                text = statusLine(item),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.status == DownloadStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 4.dp),
            )

            val progress = item.progress
            if (item.status != DownloadStatus.FAILED && item.status != DownloadStatus.CANCELED) {
                if (progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                when (item.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED -> {
                        TextButton(onClick = { App.downloads.pause(item.id) }) { Text("일시정지") }
                        TextButton(onClick = { App.downloads.cancel(item.id) }) { Text("취소") }
                    }

                    DownloadStatus.PAUSED -> {
                        TextButton(onClick = { App.downloads.resume(item.id) }) { Text("이어받기") }
                        TextButton(onClick = { App.downloads.remove(item.id, false) }) { Text("삭제") }
                    }

                    DownloadStatus.FAILED, DownloadStatus.CANCELED -> {
                        TextButton(onClick = { App.downloads.retry(item.id) }) { Text("다시 시도") }
                        TextButton(onClick = { App.downloads.remove(item.id, false) }) { Text("삭제") }
                    }

                    DownloadStatus.COMPLETED -> Unit
                }
            }
        }
    }
}

/**
 * 영상의 출처로 돌아가는 메뉴.
 *
 * 받다가 실패했을 때 "이게 어디서 온 영상이었지"를 찾느라 헤매지 않게 하기 위한 것이다.
 */
@Composable
fun LinkMenu(item: DownloadItem, onOpenLink: (String) -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val pageUrl = item.pageUrl?.takeIf { it.isNotBlank() }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "링크 메뉴")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("원본 페이지 열기") },
                enabled = pageUrl != null,
                onClick = {
                    open = false
                    pageUrl?.let(onOpenLink)
                },
            )
            DropdownMenuItem(
                text = { Text("페이지 주소 복사") },
                enabled = pageUrl != null,
                onClick = {
                    open = false
                    pageUrl?.let { Clip.copy(context, it) }
                },
            )
            DropdownMenuItem(
                text = { Text("영상 주소 복사") },
                onClick = {
                    open = false
                    Clip.copy(context, item.url)
                },
            )
        }
    }
}

private fun statusLine(item: DownloadItem): String = when (item.status) {
    DownloadStatus.QUEUED -> "대기 중"
    DownloadStatus.PAUSED -> "일시정지 · ${sizeLine(item)}"
    DownloadStatus.CANCELED -> "취소됨"
    DownloadStatus.FAILED -> item.errorMessage ?: "다운로드 실패"
    DownloadStatus.COMPLETED -> "완료"
    DownloadStatus.RUNNING ->
        if (item.kind == MediaKind.HLS && item.segmentCount > 0) {
            "${item.segmentIndex}/${item.segmentCount} 조각 · ${Format.bytes(item.bytesDownloaded)}"
        } else {
            sizeLine(item)
        }
}

private fun sizeLine(item: DownloadItem): String = when {
    item.totalBytes > 0 ->
        "${Format.bytes(item.bytesDownloaded)} / ${Format.bytes(item.totalBytes)} · ${Format.percent(item.progress)}"
    else -> Format.bytes(item.bytesDownloaded)
}

@Composable
fun EmptyMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}
