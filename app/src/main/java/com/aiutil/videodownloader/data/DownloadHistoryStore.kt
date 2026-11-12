package com.aiutil.videodownloader.data

import android.content.Context
import com.aiutil.videodownloader.model.DownloadHistoryEntry
import com.aiutil.videodownloader.model.DownloadItem
import com.aiutil.videodownloader.model.DownloadStatus
import kotlinx.serialization.builtins.ListSerializer

/**
 * 과거 다운로드 기록.
 *
 * 보관함([DownloadStore])에서 영상을 지워도 "전에 받은 적 있다"는 사실은 남겨야 하므로
 * 따로 보관한다. 같은 영상을 다시 받으려 할 때 확인 팝업을 띄우는 근거가 된다.
 */
class DownloadHistoryStore(context: Context) : JsonListStore<DownloadHistoryEntry>(
    context = context,
    fileName = "download-history.json",
    serializer = ListSerializer(DownloadHistoryEntry.serializer()),
    tag = "DownloadHistory",
) {

    fun record(item: DownloadItem) {
        val entry = DownloadHistoryEntry(
            downloadId = item.id,
            url = item.url,
            title = item.title,
            fileName = item.fileName,
            kind = item.kind,
            pageUrl = item.pageUrl,
            status = item.status,
            sizeBytes = item.totalBytes,
        )
        snapshot = (listOf(entry) + snapshot).take(MAX_ENTRIES)
        persist()
    }

    fun markFinished(downloadId: String, status: DownloadStatus, sizeBytes: Long, fileName: String? = null) {
        var changed = false
        snapshot = snapshot.map {
            if (it.downloadId == downloadId) {
                changed = true
                it.copy(
                    status = status,
                    sizeBytes = if (sizeBytes > 0) sizeBytes else it.sizeBytes,
                    fileName = fileName ?: it.fileName,
                    finishedAt = System.currentTimeMillis(),
                )
            } else it
        }
        if (changed) persist()
    }

    /**
     * 같은 영상을 전에 받은 적이 있는지 찾는다.
     *
     * 스트리밍 주소는 서명이 붙어 매번 달라지는 경우가 많아, 영상 주소가 안 맞으면
     * 영상이 있던 페이지 주소로도 비교한다.
     */
    fun findMatches(url: String, pageUrl: String?): List<DownloadHistoryEntry> {
        val byUrl = items.value.filter { sameAddress(it.url, url) }
        if (byUrl.isNotEmpty()) return byUrl.sortedByDescending { it.recordedAt }

        if (pageUrl.isNullOrBlank()) return emptyList()
        return items.value
            .filter { !it.pageUrl.isNullOrBlank() && sameAddress(it.pageUrl, pageUrl) }
            .sortedByDescending { it.recordedAt }
    }

    fun remove(downloadId: String) {
        snapshot = snapshot.filterNot { it.downloadId == downloadId }
        persist()
    }

    fun clear() {
        snapshot = emptyList()
        persist()
    }

    private fun sameAddress(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return normalize(a) == normalize(b)
    }

    private fun normalize(url: String): String =
        url.substringBefore('#').trimEnd('/').lowercase()

    private companion object {
        /** 무한정 쌓이지 않도록 상한을 둔다. */
        const val MAX_ENTRIES = 500
    }
}
