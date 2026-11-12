package com.aiutil.videodownloader.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiutil.videodownloader.App
import com.aiutil.videodownloader.download.HlsDownloader
import com.aiutil.videodownloader.model.DetectedVideo
import com.aiutil.videodownloader.model.DownloadHistoryEntry
import com.aiutil.videodownloader.model.HlsVariant
import com.aiutil.videodownloader.model.MediaKind
import com.aiutil.videodownloader.util.Names
import kotlinx.coroutines.launch

class BrowserViewModel : ViewModel() {

    /** 브라우저 위에 띄우는 바텀시트 종류. 한 번에 하나만 뜬다. */
    enum class Sheet { DETECTED, TABS, BOOKMARKS, HISTORY }

    /** 전에 받은 적 있는 영상을 다시 받으려 할 때 보여줄 확인 내용. */
    data class HistoryPrompt(val source: DetectedVideo, val entries: List<DownloadHistoryEntry>)

    /** 고를 수 있는 화질 하나와 그 예상 용량. */
    data class VariantOption(val variant: HlsVariant, val estimatedBytes: Long)

    /**
     * 받기 직전에 보여줄 내용. 파일 이름과 용량을 먼저 확인시키고, 화질이 여러 개면
     * 몇 개인지와 각각의 용량을 함께 보여준다.
     */
    data class DownloadPlan(
        val source: DetectedVideo,
        val fileName: String,
        val kind: MediaKind,
        val sizeBytes: Long,
        val variants: List<VariantOption>,
        val durationSec: Double,
        /** HLS 용량은 계산으로 가늠한 값이라 "약"을 붙여 보여준다. */
        val isEstimate: Boolean,
    )

    var addressText by mutableStateOf("")
    var sheet by mutableStateOf<Sheet?>(null)
    var downloadPlan by mutableStateOf<DownloadPlan?>(null)
        private set
    var selectedVariantIndex by mutableStateOf(0)
        private set
    var historyPrompt by mutableStateOf<HistoryPrompt?>(null)
        private set
    var resolving by mutableStateOf(false)
        private set
    var toast by mutableStateOf<String?>(null)
        private set

    /**
     * 아직 WebView에 전달되지 않은 이동 요청. 상태로 둔 이유는, 다른 탭을 보고 있을 때
     * 들어온 공유 링크가 구독자 부재로 사라지지 않게 하기 위해서다.
     */
    var pendingLoad by mutableStateOf<String?>(null)
        private set

    private var isEditingAddress = false

    /** 자막을 골랐을 때 대신 시도해 볼 같은 화면의 다른 영상 주소들. */
    private var candidates: List<DetectedVideo> = emptyList()

    fun consumePendingLoad() {
        pendingLoad = null
    }

    /** 활성 탭이 이동했을 때 주소창을 따라가게 한다. 사용자가 입력 중이면 건드리지 않는다. */
    fun syncAddress(url: String) {
        if (!isEditingAddress) addressText = url
    }

    fun onAddressFocusChanged(focused: Boolean, currentUrl: String) {
        isEditingAddress = focused
        if (!focused && currentUrl.isNotBlank()) addressText = currentUrl
    }

    /** 주소처럼 보이면 그대로 열고, 아니면 검색어로 취급한다. */
    fun submitAddress(input: String = addressText) {
        val target = normalize(input.trim())
        if (target.isNotBlank()) pendingLoad = target
    }

    fun open(url: String) {
        addressText = url
        pendingLoad = normalize(url)
    }

    // ----- 다운로드 -----

    /**
     * 다운로드 요청의 입구. 과거 기록이 있으면 먼저 확인을 받는다 —
     * 네트워크를 타기 전에 묻기 때문에 기다릴 필요가 없다.
     */
    fun onDownloadRequested(video: DetectedVideo, alternatives: List<DetectedVideo> = emptyList()) {
        // 고른 주소가 자막으로 드러나면 같은 화면의 다른 후보로 넘어가야 한다.
        candidates = alternatives.distinctBy { it.url }

        val past = App.history.findMatches(video.url, video.pageUrl)
        if (past.isNotEmpty()) {
            historyPrompt = HistoryPrompt(video, past)
            return
        }
        preparePlan(video)
    }

    /** 이력 팝업에서 "다시 받기"를 고른 경우. */
    fun confirmHistoryDownload() {
        val prompt = historyPrompt ?: return
        historyPrompt = null
        preparePlan(prompt.source)
    }

    fun dismissHistoryPrompt() {
        historyPrompt = null
    }

    fun selectVariant(index: Int) {
        selectedVariantIndex = index
    }

    /** 확인 시트에서 "받기"를 누른 경우. 고른 화질로 큐에 넣는다. */
    fun confirmDownload() {
        val plan = downloadPlan ?: return
        downloadPlan = null

        if (plan.variants.isEmpty()) {
            enqueue(plan.source.url, plan.source.displayName, plan.kind, plan.source)
            return
        }

        val chosen = plan.variants.getOrNull(selectedVariantIndex) ?: plan.variants.first()
        enqueue(
            url = chosen.variant.url,
            title = "${plan.source.displayName} (${chosen.variant.label})",
            kind = MediaKind.HLS,
            source = plan.source,
        )
    }

    fun dismissPlan() {
        downloadPlan = null
    }

    /** 받기 전에 보여줄 정보(파일명·용량·화질 수)를 모은다. */
    private fun preparePlan(video: DetectedVideo) {
        when (video.kind) {
            MediaKind.MP4 -> {
                downloadPlan = DownloadPlan(
                    source = video,
                    fileName = Names.buildFileName(video.displayName, video.url, MediaKind.MP4),
                    kind = MediaKind.MP4,
                    sizeBytes = video.sizeBytes,
                    variants = emptyList(),
                    durationSec = 0.0,
                    isEstimate = false,
                )
            }

            MediaKind.HLS -> viewModelScope.launch {
                resolving = true
                try {
                    // 고른 것이 자막이면 같은 화면의 다른 주소로 자동으로 넘어간다.
                    // 한 영상의 영상 트랙과 자막 트랙은 구분하기 어려워 미리 가려낼 수 없다.
                    val queue = buildList {
                        add(video)
                        addAll(
                            candidates.filter { it.kind == MediaKind.HLS && it.url != video.url }
                        )
                    }.take(MAX_RESOLVE_ATTEMPTS)

                    var lastReason: String? = null
                    for (candidate in queue) {
                        try {
                            downloadPlan = buildHlsPlan(candidate)
                            return@launch
                        } catch (t: Throwable) {
                            lastReason = t.message ?: "알 수 없는 오류"
                            // 자막이 아닌 오류라면 다음 후보를 시도해도 같은 결과다.
                            if (lastReason != HlsDownloader.SUBTITLE_MESSAGE) break
                        }
                    }

                    toast = lastReason ?: "스트림 정보를 읽지 못했습니다"
                    // 끝까지 자막만 나왔다면 목록을 열어 직접 고르게 한다.
                    if (lastReason == HlsDownloader.SUBTITLE_MESSAGE) sheet = Sheet.DETECTED
                } finally {
                    resolving = false
                }
            }
        }
    }

    private suspend fun buildHlsPlan(video: DetectedVideo): DownloadPlan {
        val fileName = Names.buildFileName(video.displayName, video.url, MediaKind.HLS)

        return when (val resolved = HlsDownloader.resolve(video.url, video.headers)) {
            is HlsDownloader.Resolved.Variants -> {
                val options = resolved.variants.map {
                    VariantOption(it, HlsDownloader.estimateBytes(it.bandwidth, resolved.durationSec))
                }
                // 목록은 비트레이트 내림차순이라 0번이 가장 좋은 화질이다.
                selectedVariantIndex = 0
                DownloadPlan(
                    source = video,
                    fileName = fileName,
                    kind = MediaKind.HLS,
                    sizeBytes = options.firstOrNull()?.estimatedBytes ?: -1L,
                    variants = options,
                    durationSec = resolved.durationSec,
                    isEstimate = true,
                )
            }

            is HlsDownloader.Resolved.Media -> DownloadPlan(
                source = video,
                fileName = fileName,
                kind = MediaKind.HLS,
                sizeBytes = resolved.estimatedBytes,
                variants = emptyList(),
                durationSec = resolved.playlist.totalDurationSec,
                isEstimate = true,
            )
        }
    }

    private fun enqueue(url: String, title: String, kind: MediaKind, source: DetectedVideo) {
        App.downloads.enqueue(
            url = url,
            title = title,
            kind = kind,
            headers = source.headers,
            pageUrl = source.pageUrl,
        )
        toast = "다운로드를 시작했습니다"
    }

    // ----- 즐겨찾기 -----

    fun toggleBookmark(url: String, title: String) {
        if (url.isBlank()) {
            toast = "저장할 주소가 없습니다"
            return
        }
        toast = if (App.bookmarks.contains(url)) {
            App.bookmarks.removeByUrl(url)
            "즐겨찾기에서 제거했습니다"
        } else {
            App.bookmarks.add(title, url)
            "즐겨찾기에 추가했습니다"
        }
    }

    fun notify(message: String) {
        toast = message
    }

    fun consumeToast() {
        toast = null
    }

    private fun normalize(input: String): String = when {
        input.isBlank() -> ""
        input.startsWith("http://", true) || input.startsWith("https://", true) -> input
        // 공백이 없고 점이 있으면 주소로 본다.
        !input.contains(' ') && input.contains('.') -> "https://$input"
        else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(input, "UTF-8")
    }

    private companion object {
        /** 자막을 피해 다른 주소를 시도해 보는 횟수. 너무 많으면 기다림만 길어진다. */
        const val MAX_RESOLVE_ATTEMPTS = 4
    }
}
