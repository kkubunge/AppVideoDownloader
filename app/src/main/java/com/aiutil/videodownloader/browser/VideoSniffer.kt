package com.aiutil.videodownloader.browser

import android.util.Log
import com.aiutil.videodownloader.core.Http
import com.aiutil.videodownloader.data.DetectionStore
import com.aiutil.videodownloader.model.DetectedVideo
import com.aiutil.videodownloader.model.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Response

/**
 * WebView가 내보내는 네트워크 요청을 보고 "받을 만한 영상"을 골라낸다.
 *
 * 판정은 두 단계다.
 *  1) 확장자/경로만 보고 즉시 판정 — 네트워크를 타지 않으므로 페이지 로딩을 막지 않는다.
 *  2) 애매하면 백그라운드에서 HEAD로 Content-Type을 확인한다.
 */
object VideoSniffer {

    private const val TAG = "VideoSniffer"

    /** 바로 영상으로 인정하는 확장자. */
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "3gp")

    private val HLS_EXTENSIONS = setOf("m3u8", "m3u")

    /**
     * HLS/DASH의 조각들. 이걸 하나씩 잡으면 수백 개가 쏟아지므로 무조건 버린다 —
     * 어차피 상위 플레이리스트(.m3u8)를 잡으면 전부 따라온다.
     */
    private val SEGMENT_EXTENSIONS = setOf("ts", "m4s", "aac", "vtt", "key", "mpd")

    private val HLS_MIME = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
        "vnd.apple.mpegurl",
    )

    /** 광고/트래커 도메인. 영상처럼 생긴 요청이 꽤 나오는데 전부 쓸모없다. */
    private val NOISE_HOSTS = listOf(
        "doubleclick", "googlesyndication", "googleadservices", "adservice.",
        "scorecardresearch", "google-analytics", "googletagmanager", "amplitude",
        "criteo", "taboola", "outbrain", "moatads", "adsafeprotected",
    )

    /** 2단계 판정 후보로 올릴 만한 힌트. */
    private val PROBE_HINTS = listOf("video", "/media/", "stream", "playback", "mime=video")

    /** HEAD를 중복으로 날리지 않기 위한 기록. 오래된 것부터 버리는 LRU. */
    private val inspected = object : LinkedHashMap<String, Unit>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) = size > 600
    }

    /** 처음 보는 URL이면 true. 이미 검토한 적이 있으면 false. */
    private fun markInspected(url: String): Boolean = synchronized(inspected) {
        inspected.put(url, Unit) == null
    }

    /** MP4로 인정할 최소 크기. 이보다 작으면 광고 범퍼나 미리보기일 가능성이 높다. */
    private const val MIN_MP4_BYTES = 150L * 1024

    fun reset() {
        synchronized(inspected) { inspected.clear() }
    }

    /**
     * 요청 하나를 검토한다. 호출자(WebViewClient)는 결과를 기다리지 않는다.
     */
    fun offer(
        scope: CoroutineScope,
        url: String,
        requestHeaders: Map<String, String>,
        pageUrl: String,
        pageTitle: String,
    ) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return
        val lower = url.lowercase()
        if (NOISE_HOSTS.any { it in lower }) return

        val ext = extensionOf(lower)
        if (ext in SEGMENT_EXTENSIONS) return

        if (!markInspected(url)) return

        when {
            ext in HLS_EXTENSIONS -> DetectionStore.add(
                DetectedVideo(
                    url = url,
                    pageUrl = pageUrl,
                    pageTitle = pageTitle,
                    kind = MediaKind.HLS,
                    mimeType = "application/x-mpegURL",
                    headers = requestHeaders,
                )
            )

            ext in VIDEO_EXTENSIONS -> scope.launch(Dispatchers.IO) {
                // 확장자로 이미 확신하지만, 크기를 알면 UI에서 보여줄 수 있으니 가볍게 확인한다.
                val meta = probeMeta(url, requestHeaders)
                if (meta != null && meta.contentLength in 1 until MIN_MP4_BYTES) return@launch
                DetectionStore.add(
                    DetectedVideo(
                        url = url,
                        pageUrl = pageUrl,
                        pageTitle = pageTitle,
                        kind = MediaKind.MP4,
                        mimeType = meta?.contentType ?: "video/$ext",
                        sizeBytes = meta?.contentLength ?: -1L,
                        headers = requestHeaders,
                    )
                )
            }

            PROBE_HINTS.any { it in lower } -> scope.launch(Dispatchers.IO) {
                val meta = probeMeta(url, requestHeaders) ?: return@launch
                val type = meta.contentType?.lowercase()?.substringBefore(';')?.trim()
                val kind = when {
                    type != null && type in HLS_MIME -> MediaKind.HLS
                    type != null && type.startsWith("video/") -> MediaKind.MP4
                    else -> return@launch
                }
                if (kind == MediaKind.MP4 && meta.contentLength in 1 until MIN_MP4_BYTES) return@launch
                DetectionStore.add(
                    DetectedVideo(
                        url = url,
                        pageUrl = pageUrl,
                        pageTitle = pageTitle,
                        kind = kind,
                        mimeType = type,
                        sizeBytes = meta.contentLength,
                        headers = requestHeaders,
                    )
                )
            }
        }
    }

    /** 페이지 안의 <video> 태그에서 뽑아낸 주소. 네트워크 감지가 놓친 것을 메운다. */
    fun offerFromDom(
        scope: CoroutineScope,
        url: String,
        headers: Map<String, String>,
        pageUrl: String,
        pageTitle: String,
    ) {
        // blob:/data: 는 메모리 상의 스트림이라 직접 받을 수 없다. 이런 페이지는
        // 네트워크 단계에서 잡힌 실제 스트림 주소를 써야 한다.
        if (url.startsWith("blob:") || url.startsWith("data:")) return
        offer(scope, url, headers, pageUrl, pageTitle)
    }

    private data class Meta(val contentType: String?, val contentLength: Long)

    private fun probeMeta(url: String, headers: Map<String, String>): Meta? {
        headRequest(url, headers)?.let { return it }
        return rangeRequest(url, headers)
    }

    private fun headRequest(url: String, headers: Map<String, String>): Meta? = try {
        Http.probeClient.newCall(Http.requestBuilder(url, headers).head().build()).execute()
            .use { resp -> if (resp.isSuccessful) resp.toMeta(-1) else null }
    } catch (t: Throwable) {
        Log.v(TAG, "HEAD 실패: $url", t)
        null
    }

    /** HEAD를 막는 서버가 많다. 1바이트만 요청해서 헤더를 받아낸다. */
    private fun rangeRequest(url: String, headers: Map<String, String>): Meta? = try {
        Http.probeClient.newCall(
            Http.requestBuilder(url, headers).header("Range", "bytes=0-1").get().build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) null
            else resp.toMeta(parseContentRangeTotal(resp.header("Content-Range")))
        }
    } catch (t: Throwable) {
        Log.v(TAG, "Range 탐색 실패: $url", t)
        null
    }

    private fun Response.toMeta(totalFromRange: Long): Meta {
        val len = if (totalFromRange > 0) totalFromRange
        else header("Content-Length")?.toLongOrNull() ?: -1L
        return Meta(header("Content-Type"), len)
    }

    /** "bytes 0-1/123456" 에서 123456을 꺼낸다. */
    private fun parseContentRangeTotal(value: String?): Long {
        val total = value?.substringAfter('/', "")?.trim() ?: return -1
        return if (total == "*" || total.isEmpty()) -1 else total.toLongOrNull() ?: -1
    }

    private fun extensionOf(lowerUrl: String): String {
        val path = lowerUrl.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        if (!last.contains('.')) return ""
        return last.substringAfterLast('.')
    }
}
