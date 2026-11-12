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

    /** 새 페이지로 이동하면 이전 후보를 버린다. 같은 페이지 내 이동(해시 변경 등)은 유지. */
    @Synchronized
    fun onPageChanged(pageUrl: String) {
        if (normalize(pageUrl) == normalize(currentPage)) return
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

    private fun normalize(url: String): String = url.substringBefore('#')

    private companion object {
        const val MAX_VIDEOS = 50
        const val MAX_INSPECTED = 600
    }
}
