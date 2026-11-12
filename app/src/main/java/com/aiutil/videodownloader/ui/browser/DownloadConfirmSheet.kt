package com.aiutil.videodownloader.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiutil.videodownloader.model.MediaKind
import com.aiutil.videodownloader.util.Format
import java.util.Locale

/**
 * 받기 직전 확인 시트.
 *
 * 저장될 파일 이름과 예상 용량을 먼저 보여준다. 화질이 여러 개인 스트림이면
 * 몇 개인지와 화질별 용량을 함께 보여주고 고르게 한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadConfirmSheet(
    plan: BrowserViewModel.DownloadPlan,
    selectedIndex: Int,
    onSelectVariant: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hasChoices = plan.variants.size > 1
    val shownSize = if (hasChoices) {
        plan.variants.getOrNull(selectedIndex)?.estimatedBytes ?: plan.sizeBytes
    } else {
        plan.sizeBytes
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("다운로드", style = MaterialTheme.typography.titleMedium)

            Text(
                text = plan.fileName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )

            Text(
                text = buildString {
                    append(if (plan.kind == MediaKind.HLS) "스트리밍" else "단일 파일")
                    append(" · ")
                    append(sizeText(shownSize, plan.isEstimate))
                    if (plan.durationSec > 0) append(" · ${durationText(plan.durationSec)}")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (hasChoices) {
                Text(
                    text = "화질 ${plan.variants.size}개 중 선택",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
        }

        if (hasChoices) {
            HorizontalDivider()
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                itemsIndexed(plan.variants, key = { _, option -> option.variant.url }) { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectVariant(index) }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = { onSelectVariant(index) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(option.variant.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = buildString {
                                    append(sizeText(option.estimatedBytes, true))
                                    if (option.variant.width > 0) {
                                        append(" · ${option.variant.width}x${option.variant.height}")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("취소") }
            Button(onClick = onConfirm, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.Default.Download, contentDescription = null)
                Text("받기", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

private fun sizeText(bytes: Long, isEstimate: Boolean): String = when {
    bytes <= 0 -> "용량 미상"
    isEstimate -> "약 ${Format.bytes(bytes)}"
    else -> Format.bytes(bytes)
}

private fun durationText(seconds: Double): String {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}
