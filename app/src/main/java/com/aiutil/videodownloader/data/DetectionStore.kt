package com.aiutil.videodownloader.data

import com.aiutil.videodownloader.download.HlsDownloader
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

    /**
     * 화면에 보이는 영상 하나. 많이 보이는 순서대로 들어온다.
     *
     * 항목별로 따로 들고 있어야 어느 글의 영상인지 가릴 수 있다. 전부 뭉쳐 두면
     * 받은 영상에 이웃 글의 주소가 붙는다.
     */
    data class ScreenItem(
        val urls: List<String>,
        val posterUrl: String?,
        val link: String?,
    )

    /** [ScreenItem]을 주소·미디어 번호로 빨리 찾기 위해 미리 펼쳐 둔 값. */
    private class ItemIndex(
        val urls: Set<String>,
        val keys: Set<String>,
        val link: String?,
    )

    private var itemIndex: List<ItemIndex> = emptyList()

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

    /**
     * 새로 찾은 영상을 **앞에** 넣는다.
     *
     * 뒤에 붙이면 목록 맨 위가 늘 '맨 처음 감지된 영상'으로 고정돼, 스크롤해서 새 영상을
     * 봐도 정작 받고 싶은 것은 한참 아래에 묻힌다.
     */
    @Synchronized
    fun add(video: DetectedVideo) {
        val existing = _videos.value
        if (existing.any { it.url == video.url }) return
        _videos.value = (listOf(video) + existing).take(MAX_VIDEOS)
        recomputeOnScreen()
    }

    /**
     * DOM 스캐너가 보고한 화면 상태를 반영한다.
     *
     * @param urls 화면에 보이는 <video>에서 직접 읽어낸 주소
     * @param posters 그 영상들의 썸네일 주소
     * @param count 화면에 보이는 <video> 요소 수
     * @param changed 직전 보고와 견줘 보이는 영상이 바뀌었는지
     */
    @Synchronized
    fun setOnScreen(items: List<ScreenItem>, count: Int, changed: Boolean) {
        itemIndex = items.map { item ->
            val keys = buildSet {
                item.posterUrl?.let { addAll(mediaKeys(it)) }
                item.urls.forEach { addAll(mediaKeys(it)) }
            }
            ItemIndex(
                urls = item.urls.mapTo(HashSet()) { normalize(it) },
                keys = keys,
                link = item.link,
            )
        }

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
        val matched = _videos.value.mapNotNull { video ->
            matchOf(video, focus)?.let { video to it }
        }

        // 확실한 짝(주소 → 미디어 번호 → 시점) 순으로, 같은 등급 안에서는 최근 것부터.
        _onScreenVideos.value = matched
            .sortedWith(compareBy<Pair<DetectedVideo, Match>> { it.second.rank }
                .thenByDescending { it.first.detectedAt })
            .map { (video, match) ->
                // 피드 주소 대신 그 영상이 실린 글의 주소를 달아 준다.
                // 어느 글의 것인지 가리지 못했다면 건드리지 않는다.
                if (match.link.isNullOrBlank()) video else video.copy(pageUrl = match.link)
            }
    }

    private class Match(val rank: Int, val link: String?)

    /**
     * 화면에 보이는 영상으로 칠 수 있는지, 그리고 얼마나 확실한지.
     *
     * @return 작을수록 확실한 짝. null이면 화면 밖으로 본다.
     */
    private fun matchOf(video: DetectedVideo, focus: Long): Match? {
        val key = normalize(video.url)
        val penalty = subtitlePenalty(video.url)

        // 1) DOM이 알려준 주소와 그대로 일치. 많이 보이는 순서를 그대로 쓴다.
        itemIndex.forEachIndexed { index, item ->
            if (key in item.urls) return Match(index + penalty, item.link)
        }

        // 2) 썸네일과 같은 미디어 번호를 공유. blob 스트림을 짝짓는 주된 방법이다.
        val videoKeys = mediaKeys(video.url)
        if (videoKeys.isNotEmpty()) {
            itemIndex.forEachIndexed { index, item ->
                if (videoKeys.any { it in item.keys }) {
                    return Match(RANK_MEDIA_KEY + index + penalty, item.link)
                }
            }
        }

        // 3) 보이는 영상이 바뀐 직후에 잡힌 것. 위 둘이 모두 안 될 때의 마지막 수단.
        // 어느 글의 것인지는 가릴 수 없으므로 글 주소를 붙이지 않는다.
        if (focus > 0 && video.detectedAt in (focus - MATCH_BEFORE_MS)..(focus + MATCH_AFTER_MS)) {
            return Match(RANK_RECENT + penalty, null)
        }

        return null
    }

    /**
     * 자막 트랙은 뒤로 민다.
     *
     * 한 영상의 영상 트랙과 자막 트랙은 같은 미디어 번호를 쓴다. 그래서 순위가
     * 같아지고, 보통 나중에 불리는 자막이 이겨 버린다.
     */
    private fun subtitlePenalty(url: String): Int =
        if (HlsDownloader.looksLikeNonVideo(url)) RANK_SUBTITLE_PENALTY else 0

    /** 주소에 박힌 긴 숫자(미디어 번호)를 뽑는다. */
    private fun mediaKeys(url: String): Set<String> =
        MEDIA_ID.findAll(url).map { it.value }.toSet()

    private fun reset(pageUrl: String) {
        currentPage = pageUrl
        _videos.value = emptyList()
        _onScreenVideos.value = emptyList()
        itemIndex = emptyList()
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

        /** 미디어 번호로 볼 만한 길이의 숫자. 짧은 숫자는 해상도·날짜 등 잡음이 많다. */
        val MEDIA_ID = Regex("""\d{10,}""")

        /** 주소가 그대로 맞은 것들(0, 1, 2 …) 뒤에 오도록 충분히 큰 값을 쓴다. */
        const val RANK_MEDIA_KEY = 1_000
        const val RANK_RECENT = 2_000

        /** 자막으로 보이는 주소는 어떤 등급이든 영상보다 뒤로 간다. */
        const val RANK_SUBTITLE_PENALTY = 10_000

        /** 스크롤이 멎기 직전에 미리 요청된 스트림까지 품기 위한 여유. */
        const val MATCH_BEFORE_MS = 1_000L

        /** 영상이 화면에 들어온 뒤 스트림 요청이 들어오기까지의 여유. */
        const val MATCH_AFTER_MS = 4_000L
    }
}
