package com.aiutil.videodownloader.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aiutil.videodownloader.core.Http
import com.aiutil.videodownloader.data.DetectionStore
import com.aiutil.videodownloader.model.DetectedVideo
import com.aiutil.videodownloader.model.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 브라우저 탭 하나. 자기 WebView와 자기 감지 목록을 가진다.
 *
 * 상태를 Compose 상태로 들고 있어서, 탭 목록 화면과 주소창이 같은 값을 자동으로 따라간다.
 */
class BrowserTab(
    val id: String,
    context: Context,
    private val onDownloadRequested: (DetectedVideo) -> Unit,
) {

    /** 탭마다 따로 둔다 — 다른 탭에서 찾은 영상이 섞이면 안 된다. */
    val detections = DetectionStore()

    /**
     * 스니핑용 백그라운드 작업 스코프. 컴포지션이 아니라 이 탭의 수명을 따른다 —
     * 탭을 옮겼다고 확인 중이던 요청이 끊기면 안 된다.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var title by mutableStateOf("")
        private set
    var url by mutableStateOf("")
        private set
    var isLoading by mutableStateOf(false)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var progress by mutableIntStateOf(0)
        private set

    /** 탭 목록에 보여줄 이름. */
    val displayTitle: String
        get() = title.ifBlank { hostOf(url) }.ifBlank { "새 탭" }

    val client: SnifferWebViewClient
    val webView: WebView

    init {
        val snifferClient = SnifferWebViewClient(
            scope = scope,
            detections = detections,
            onNavigate = { navigatedUrl, back ->
                url = navigatedUrl
                canGoBack = back
            },
            onLoadingChanged = { loading ->
                isLoading = loading
                if (!loading) progress = 100
            },
        )
        client = snifferClient
        webView = createWebView(context, snifferClient)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, snifferClient: SnifferWebViewClient): WebView =
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            // 비워 두면 내용이 없을 때 검게 그려진다. 페이지가 뜨기 전 검은 화면이
            // 번쩍이는 것도 이걸로 막는다.
            setBackgroundColor(android.graphics.Color.WHITE)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                userAgentString = Http.MOBILE_UA
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                javaScriptCanOpenWindowsAutomatically = true
            }

            // 로그인이 필요한 사이트를 위해 서드파티 쿠키까지 허용한다.
            CookieManager.getInstance().also { cookies ->
                cookies.setAcceptCookie(true)
                cookies.setAcceptThirdPartyCookies(this, true)
            }

            webViewClient = snifferClient
            // apply의 수신자가 WebView라서 title/progress를 그냥 쓰면 WebView의
            // 읽기 전용 프로퍼티로 해석된다. 탭의 상태임을 명시해야 한다.
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, received: String?) {
                    snifferClient.currentTitle = received.orEmpty()
                    this@BrowserTab.title = received.orEmpty()
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    this@BrowserTab.progress = newProgress
                }
            }

            addJavascriptInterface(
                VideoJsBridge(
                    scope = scope,
                    detections = detections,
                    pageInfo = {
                        VideoJsBridge.PageInfo(snifferClient.currentUrl, snifferClient.currentTitle)
                    },
                    headers = { snifferClient.downloadHeaders(snifferClient.currentUrl) },
                ),
                VideoJsBridge.NAME,
            )

            // 사이트가 직접 내려주는 파일 링크(<a download>, Content-Disposition)를 가로챈다.
            setDownloadListener { downloadUrl, _, contentDisposition, mimeType, contentLength ->
                onDownloadRequested(
                    DetectedVideo(
                        url = downloadUrl,
                        pageUrl = snifferClient.currentUrl,
                        pageTitle = fileNameFrom(contentDisposition, downloadUrl),
                        kind = if (mimeType?.contains("mpegurl", true) == true) {
                            MediaKind.HLS
                        } else {
                            MediaKind.MP4
                        },
                        mimeType = mimeType,
                        sizeBytes = contentLength,
                        headers = snifferClient.downloadHeaders(downloadUrl),
                    )
                )
            }
        }

    fun loadUrl(target: String) {
        url = target
        webView.loadUrl(target)
    }

    fun goBack() {
        if (webView.canGoBack()) webView.goBack()
    }

    fun reload() = webView.reload()

    fun stopLoading() = webView.stopLoading()

    /** 다른 탭을 보여주기 전에 화면에서 떼어낸다. View는 부모가 하나뿐이다. */
    fun detachFromParent() {
        (webView.parent as? ViewGroup)?.removeView(webView)
    }

    fun destroy() {
        scope.cancel()
        detachFromParent()
        webView.destroy()
    }

    private fun fileNameFrom(contentDisposition: String?, target: String): String =
        contentDisposition
            ?.substringAfter("filename=", "")
            ?.trim('"', ' ', ';')
            ?.takeIf { it.isNotBlank() }
            ?: target.substringBefore('?').substringAfterLast('/')

    private fun hostOf(value: String): String = runCatching {
        java.net.URL(value).host.removePrefix("www.")
    }.getOrDefault("")
}
