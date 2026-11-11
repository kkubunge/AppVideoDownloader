package com.aiutil.videodownloader.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiutil.videodownloader.App
import com.aiutil.videodownloader.data.DetectionStore
import com.aiutil.videodownloader.download.HlsDownloader
import com.aiutil.videodownloader.model.DetectedVideo
import com.aiutil.videodownloader.model.HlsVariant
import com.aiutil.videodownloader.model.MediaKind
import kotlinx.coroutines.launch

class BrowserViewModel : ViewModel() {

    /** HLS 마스터를 열었을 때 사용자에게 보여줄 화질 목록. */
    data class QualityPrompt(val source: DetectedVideo, val variants: List<HlsVariant>)

    val detectedVideos = DetectionStore.videos

    var addressText by mutableStateOf("")
    var currentUrl by mutableStateOf("")
        private set
    var isLoading by mutableStateOf(false)
        private set
    var canGoBack by mutableStateOf(false)
        private set

    var qualityPrompt by mutableStateOf<QualityPrompt?>(null)
        private set
    var resolving by mutableStateOf(false)
        private set
    var toast by mutableStateOf<String?>(null)
        private set

    /**
     * 아직 WebView에 전달되지 않은 이동 요청. SharedFlow 대신 상태로 둔 이유는, 다른 탭을
     * 보고 있을 때 들어온 공유 링크가 구독자 부재로 사라지지 않게 하기 위해서다.
     */
    var pendingLoad by mutableStateOf<String?>(null)
        private set

    fun consumePendingLoad() {
        pendingLoad = null
    }

    fun onNavigated(url: String, canGoBack: Boolean) {
        currentUrl = url
        this.canGoBack = canGoBack
        if (!isEditingAddress) addressText = url
    }

    fun onLoadingChanged(loading: Boolean) {
        isLoading = loading
    }

    private var isEditingAddress = false

    fun onAddressFocusChanged(focused: Boolean) {
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

    fun onDownloadRequested(video: DetectedVideo) {
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

    fun consumeToast() {
        toast = null
    }

    fun clearDetections() = DetectionStore.clear()

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

    private fun normalize(input: String): String = when {
        input.isBlank() -> ""
        input.startsWith("http://", true) || input.startsWith("https://", true) -> input
        // 공백이 없고 점이 있으면 주소로 본다.
        !input.contains(' ') && input.contains('.') -> "https://$input"
        else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(input, "UTF-8")
    }
}
