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
    val videos: StateFlow<List<DetectedVideo>> = _videos.asStateFlow()

    private var currentPage: String = ""

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
     * 불리므로, 호스트가 실제로 바뀐 게 아니면 찾아 둔 영상을 유지한다 —
     * 스크롤 한 번에 '받기' 버튼이 사라지면 쓸 수가 없다.
     */
    @Synchronized
    fun onSoftNavigation(pageUrl: String) {
        if (hostOf(pageUrl) == hostOf(currentPage)) {
            currentPage = pageUrl
            return
        }
        reset(pageUrl)
    }

    private fun reset(pageUrl: String) {
        currentPage = pageUrl
        _videos.value = emptyList()
        inspected.clear()
    }

    /** 처음 보는 주소면 true. 이미 검토한 적이 있으면 false. */
    @Synchronized
    fun markInspected(url: String): Boolean = inspected.put(url, Unit) == null

    @Synchronized
    fun add(video: DetectedVideo) {
        val existing = _videos.value
        if (existing.any { it.url == video.url }) return
        _videos.value = (existing + video).takeLast(MAX_VIDEOS)
    }

    @Synchronized
    fun clear() {
        _videos.value = emptyList()
        inspected.clear()
    }

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
    }
}
