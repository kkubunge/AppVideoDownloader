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
import kotlinx.coroutines.launch

class BrowserViewModel : ViewModel() {

    /** 브라우저 위에 띄우는 바텀시트 종류. 한 번에 하나만 뜬다. */
    enum class Sheet { DETECTED, TABS, BOOKMARKS, HISTORY }

    /** HLS 마스터를 열었을 때 사용자에게 보여줄 화질 목록. */
    data class QualityPrompt(val source: DetectedVideo, val variants: List<HlsVariant>)

    /** 전에 받은 적 있는 영상을 다시 받으려 할 때 보여줄 확인 내용. */
    data class HistoryPrompt(val source: DetectedVideo, val entries: List<DownloadHistoryEntry>)

    var addressText by mutableStateOf("")
    var sheet by mutableStateOf<Sheet?>(null)
    var qualityPrompt by mutableStateOf<QualityPrompt?>(null)
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
    fun onDownloadRequested(video: DetectedVideo) {
        val past = App.history.findMatches(video.url, video.pageUrl)
        if (past.isNotEmpty()) {
            historyPrompt = HistoryPrompt(video, past)
            return
        }
        startDownload(video)
    }

    /** 이력 팝업에서 "다시 받기"를 고른 경우. */
    fun confirmHistoryDownload() {
        val prompt = historyPrompt ?: return
        historyPrompt = null
        startDownload(prompt.source)
    }

    fun dismissHistoryPrompt() {
        historyPrompt = null
    }

    fun chooseVariant(variant: HlsVariant) {
        val prompt = qualityPrompt ?: return
        qualityPrompt = null
        enqueue(
            url = variant.url,
            title = "${prompt.source.displayName} (${variant.label})",
            kind = MediaKind.HLS,
            source = prompt.source,
        )
    }

    fun dismissQualityPrompt() {
        qualityPrompt = null
    }

    private fun startDownload(video: DetectedVideo) {
        when (video.kind) {
            MediaKind.MP4 -> enqueue(video.url, video.displayName, MediaKind.MP4, video)

            MediaKind.HLS -> viewModelScope.launch {
                resolving = true
                try {
                    when (val resolved = HlsDownloader.resolve(video.url, video.headers)) {
                        is HlsDownloader.Resolved.Variants ->
                            qualityPrompt = QualityPrompt(video, resolved.variants)

                        is HlsDownloader.Resolved.Media ->
                            enqueue(video.url, video.displayName, MediaKind.HLS, video)
                    }
                } catch (t: Throwable) {
                    toast = "스트림 정보를 읽지 못했습니다: ${t.message ?: "알 수 없는 오류"}"
                } finally {
                    resolving = false
                }
            }
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
}
