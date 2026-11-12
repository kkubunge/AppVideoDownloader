package com.aiutil.videodownloader.model

import kotlinx.serialization.Serializable

/** 다운로드 대상의 형태. 단일 파일(MP4 계열)과 분할 스트리밍(HLS)은 처리 경로가 다르다. */
enum class MediaKind { MP4, HLS }

enum class DownloadStatus {
    QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED;

    val isActive: Boolean get() = this == QUEUED || this == RUNNING
    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELED
}

/** 브라우저가 가로챈, 아직 다운로드하지 않은 후보 영상. */
data class DetectedVideo(
    val url: String,
    val pageUrl: String,
    val pageTitle: String,
    val kind: MediaKind,
    val mimeType: String? = null,
    val sizeBytes: Long = -1,
    val headers: Map<String, String> = emptyMap(),
    val label: String? = null,
    val detectedAt: Long = System.currentTimeMillis(),
) {
    val displayName: String
        get() = pageTitle.ifBlank { url.substringAfterLast('/').substringBefore('?') }
}

/** HLS 마스터 플레이리스트가 제공하는 화질 선택지. */
data class HlsVariant(
    val url: String,
    val bandwidth: Long,
    val width: Int = 0,
    val height: Int = 0,
    val codecs: String? = null,
) {
    val label: String
        get() = when {
            height > 0 -> "${height}p"
            bandwidth > 0 -> "${bandwidth / 1000} kbps"
            else -> "기본"
        }
}

@Serializable
data class Bookmark(
    val id: String,
    val title: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val displayTitle: String
        get() = title.ifBlank { url.substringAfter("://").substringBefore('/') }
}

/**
 * 과거 다운로드 기록. [DownloadItem]과 따로 두는 이유는, 보관함에서 영상을 지워도
 * "전에 받은 적 있다"는 사실은 남아야 하기 때문이다.
 */
@Serializable
data class DownloadHistoryEntry(
    val downloadId: String,
    val url: String,
    val title: String,
    val fileName: String,
    val kind: MediaKind,
    val pageUrl: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val sizeBytes: Long = -1L,
    val recordedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
)

@Serializable
data class DownloadItem(
    val id: String,
    val url: String,
    val title: String,
    val fileName: String,
    val kind: MediaKind,
    val pageUrl: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    /** HLS 전용: 이미 기록을 끝낸 세그먼트 수. 이어받기 시작점이 된다. */
    val segmentIndex: Int = 0,
    val segmentCount: Int = 0,
    val headers: Map<String, String> = emptyMap(),
    /** 완료 시 MediaStore(또는 파일) URI. */
    val outputUri: String? = null,
    val outputDisplayPath: String? = null,
    /**
     * 색인 있는 MP4로 다시 담는 데 성공했는지. 단일 파일 다운로드는 해당 없음(null).
     *
     * 실패하면 확장자가 mp4여도 갤러리와 기본 재생기가 열지 못한다. 겉보기로는
     * 구분되지 않아, 재생이 안 될 때 원인을 가리려면 이 값이 필요하다.
     */
    val remuxed: Boolean? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
) {
    /** 0f~1f. 전체 크기를 모르면 -1f(불확정). */
    val progress: Float
        get() = when {
            status == DownloadStatus.COMPLETED -> 1f
            kind == MediaKind.HLS && segmentCount > 0 ->
                (segmentIndex.toFloat() / segmentCount).coerceIn(0f, 1f)
            totalBytes > 0 -> (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
            else -> -1f
        }
}
