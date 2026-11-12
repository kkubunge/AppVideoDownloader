package com.aiutil.videodownloader.data

import com.aiutil.videodownloader.model.DetectedVideo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 한 탭이 보고 있는 페이지에서 감지한 영상 후보.
 *
 * 탭마다 하나씩 둔다 — 다른 탭에서 찾은 영상이 섞여 보이면 어느 페이지 것인지 알 수 없다.
 * 메모리에만 두며, 페이지를 떠나면 버린다.
 */
class DetectionStore {

    private val _videos = MutableStateFlow<List<DetectedVideo>>(emptyList())

    /** 이 페이지에서 찾은 전체 목록. */
    val videos: StateFlow<List<DetectedVideo>> = _videos.asStateFlow()

    private val _onScreenVideos = MutableStateFlow<List<DetectedVideo>>(emptyList())

    /**
     * 지금 화면에 보이는 영상만 추린 목록.
     *
     * 피드를 스크롤하면 지나간 영상의 스트림까지 전부 잡히기 때문에, 전체 목록만으로는
     * "몇 개를 받을 수 있는지"가 실제 화면과 전혀 맞지 않는다.
     */
    val onScreenVideos: StateFlow<List<DetectedVideo>> = _onScreenVideos.asStateFlow()

    private var currentPage: String = ""

    /** DOM에서 확인한, 화면에 보이는 영상의 주소. */
    private val onScreenUrls = mutableSetOf<String>()

    /** 화면에 보이는 <video> 요소 수. 주소를 못 얻는 blob 스트림도 세어진다. */
    private var onScreenCount = 0

    /** 보이는 영상이 마지막으로 바뀐 시각. */
    private var focusChangedAt = 0L

    /**
     * 이미 검토한 주소. HEAD 요청을 중복으로 날리지 않기 위한 기록이며,
     * 오래된 것부터 버리는 LRU다.
     */
    private val inspected = object : LinkedHashMap<String, Unit>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) =
            size > MAX_INSPECTED
    }

    /** 실제로 새 문서를 열었을 때. 이전 후보는 의미가 없으므로 버린다. */
    @Synchronized
    fun onNewDocument(pageUrl: String) {
        if (samePath(pageUrl, currentPage)) {
            currentPage = pageUrl
            return
        }
        reset(pageUrl)
    }

    /**
     * SPA 내부 이동(주소만 바뀌고 문서는 그대로). 피드를 스크롤하는 것만으로도 수시로
     * 불리므로, 호스트가 실제로 바뀐 게 아니면 찾아 둔 영상을 유지한다.
     */
    @Synchronized
    fun onSoftNavigation(pageUrl: String) {
        if (hostOf(pageUrl) == hostOf(currentPage)) {
            currentPage = pageUrl
            return
        }
        reset(pageUrl)
    }

    /** 처음 보는 주소면 true. 이미 검토한 적이 있으면 false. */
    @Synchronized
    fun markInspected(url: String): Boolean = inspected.put(url, Unit) == null

    @Synchronized
    fun add(video: DetectedVideo) {
        val existing = _videos.value
        if (existing.any { it.url == video.url }) return
        _videos.value = (existing + video).takeLast(MAX_VIDEOS)
        recomputeOnScreen()
    }

    /**
     * DOM 스캐너가 보고한 화면 상태를 반영한다.
     *
     * @param urls 화면에 보이는 <video>에서 직접 읽어낸 주소
     * @param count 화면에 보이는 <video> 요소 수
     * @param changed 직전 보고와 견줘 보이는 영상이 바뀌었는지
     */
    @Synchronized
    fun setOnScreen(urls: List<String>, count: Int, changed: Boolean) {
        onScreenUrls.clear()
        urls.forEach { onScreenUrls.add(normalize(it)) }
        onScreenCount = count
        if (changed) focusChangedAt = System.currentTimeMillis()
        recomputeOnScreen()
    }

    @Synchronized
    fun clear() {
        _videos.value = emptyList()
        _onScreenVideos.value = emptyList()
        inspected.clear()
    }

    /**
     * 화면에 보이는 것으로 칠 영상을 추린다.
     *
     * 두 가지로 짝짓는다.
     *  1) DOM이 알려준 주소와 일치 — 단일 MP4처럼 <video src>가 그대로인 경우
     *  2) 보이는 영상이 바뀐 직후에 잡힌 것 — HLS처럼 src가 blob이라 주소를 못 얻는 경우,
     *     그 시점 전후로 들어온 스트림 요청이 지금 보고 있는 영상의 것이다
     */
    private fun recomputeOnScreen() {
        if (onScreenCount == 0) {
            _onScreenVideos.value = emptyList()
            return
        }

        val focus = focusChangedAt
        _onScreenVideos.value = _videos.value.filter { video ->
            normalize(video.url) in onScreenUrls ||
                (focus > 0 && video.detectedAt in (focus - MATCH_BEFORE_MS)..(focus + MATCH_AFTER_MS))
        }
    }

    private fun reset(pageUrl: String) {
        currentPage = pageUrl
        _videos.value = emptyList()
        _onScreenVideos.value = emptyList()
        onScreenUrls.clear()
        onScreenCount = 0
        focusChangedAt = 0L
        inspected.clear()
    }

    private fun normalize(url: String): String = url.substringBefore('#').trimEnd('/')

    /** 질의 문자열과 해시를 뺀 경로만 비교한다. `?t=123` 같은 변화로 목록을 날리지 않기 위해서다. */
    private fun samePath(a: String, b: String): Boolean =
        pathKey(a) == pathKey(b) && pathKey(a).isNotEmpty()

    private fun pathKey(url: String): String = runCatching {
        val u = java.net.URL(url)
        "${u.host}${u.path}".lowercase().trimEnd('/')
    }.getOrDefault("")

    private fun hostOf(url: String): String = runCatching {
        java.net.URL(url).host.lowercase()
    }.getOrDefault("")

    private companion object {
        const val MAX_VIDEOS = 50
        const val MAX_INSPECTED = 600

        /** 스크롤이 멎기 직전에 미리 요청된 스트림까지 품기 위한 여유. */
        const val MATCH_BEFORE_MS = 1_000L

        /** 영상이 화면에 들어온 뒤 스트림 요청이 들어오기까지의 여유. */
        const val MATCH_AFTER_MS = 4_000L
    }
}
