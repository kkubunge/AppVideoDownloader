package com.aiutil.videodownloader.data

import com.aiutil.videodownloader.model.DetectedVideo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 현재 보고 있는 페이지에서 감지한 영상 후보. 메모리에만 둔다 — 페이지를 떠나면 의미가 없다.
 */
object DetectionStore {

    private const val MAX_ITEMS = 50

    private val _videos = MutableStateFlow<List<DetectedVideo>>(emptyList())
    val videos: StateFlow<List<DetectedVideo>> = _videos.asStateFlow()

    private var currentPage: String = ""

    /** 새 페이지로 이동하면 이전 후보를 버린다. 같은 페이지 내 이동(해시 변경 등)은 유지. */
    @Synchronized
    fun onPageChanged(pageUrl: String) {
        if (normalize(pageUrl) == normalize(currentPage)) return
        currentPage = pageUrl
        _videos.value = emptyList()
    }

    @Synchronized
    fun add(video: DetectedVideo) {
        val existing = _videos.value
        if (existing.any { it.url == video.url }) return
        _videos.value = (existing + video).takeLast(MAX_ITEMS)
    }

    @Synchronized
    fun clear() {
        _videos.value = emptyList()
    }

    private fun normalize(url: String): String = url.substringBefore('#')
}
