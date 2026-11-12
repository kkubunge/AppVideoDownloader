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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    onScreenVideos: List<DetectedVideo>,
    allVideos: List<DetectedVideo>,
    downloadedUrls: Set<String>,
    onDownload: (DetectedVideo) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 화면에 보이는 것이 있으면 그것만 먼저 보여준다. 피드를 훑고 온 뒤에는 전체 목록이
    // 수십 개가 되어 정작 지금 보고 있는 영상을 찾기 어렵다.
    var showAll by remember(onScreenVideos.isEmpty()) { mutableStateOf(onScreenVideos.isEmpty()) }
    val shown = if (showAll) allVideos else onScreenVideos

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (showAll) "이 페이지에서 찾음 ${allVideos.size}개"
                else "화면에 보이는 영상 ${onScreenVideos.size}개",
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onClear) { Text("목록 비우기") }
        }

        if (onScreenVideos.isNotEmpty() && allVideos.size > onScreenVideos.size) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !showAll,
                    onClick = { showAll = false },
                    label = { Text("화면 ${onScreenVideos.size}") },
                )
                FilterChip(
                    selected = showAll,
                    onClick = { showAll = true },
                    label = { Text("전체 ${allVideos.size}") },
                )
            }
        }

        HorizontalDivider()

        if (shown.isEmpty()) {
            Text(
                text = "화면에 보이는 영상이 없습니다.\n영상이 보이도록 스크롤해 보세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
            return@ModalBottomSheet
        }

        LazyColumn(
            modifier = Modifier.heightIn(max = 420.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(shown, key = { it.url }) { video ->
                DetectedVideoRow(
                    video = video,
                    alreadyDownloaded = normalizeUrl(video.url) in downloadedUrls,
                    onDownload = { onDownload(video) },
                )
            }
        }
    }
}

/** 목록 어디서든 같은 방식으로 주소를 맞춰 보기 위한 정규화. */
fun normalizeUrl(url: String): String = url.substringBefore('#').trimEnd('/')

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetectedVideoRow(
    video: DetectedVideo,
    alreadyDownloaded: Boolean,
    onDownload: () -> Unit,
) {
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
                    label = {
                        Text(
                            // 눌러 보기 전에 이미 받은 것인지 알 수 있어야 한다.
                            if (alreadyDownloaded) "받음"
                            else if (video.kind == MediaKind.HLS) "스트리밍"
                            else "파일"
                        )
                    },
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
