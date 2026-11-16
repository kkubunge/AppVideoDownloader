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
    restoredUrl: String? = null,
    restoredTitle: String? = null,
    private val onDownloadRequested: (DetectedVideo) -> Unit,
    private val onStateChanged: () -> Unit = {},
) {

    /** 탭마다 따로 둔다 — 다른 탭에서 찾은 영상이 섞이면 안 된다. */
    val detections = DetectionStore()

    /**
     * 스니핑용 백그라운드 작업 스코프. 컴포지션이 아니라 이 탭의 수명을 따른다 —
     * 탭을 옮겼다고 확인 중이던 요청이 끊기면 안 된다.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var title by mutableStateOf(restoredTitle.orEmpty())
        private set
    var url by mutableStateOf(restoredUrl.orEmpty())
        private set

    /**
     * 복원된 탭이 아직 불러오지 않은 주소.
     *
     * 앱을 켤 때 열려 있던 탭을 전부 한꺼번에 불러오면 느리고 데이터도 많이 쓴다.
     * 그 탭을 실제로 볼 때 비로소 불러온다.
     */
    private var pendingUrl: String? = restoredUrl?.takeIf { it.isNotBlank() }

    /** 주소별로 마지막에 보던 세로 위치. 오래된 것부터 버린다. */
    private val scrollPositions = object : LinkedHashMap<String, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>) =
            size > MAX_SCROLL_MEMORY
    }

    private var returningBack = false

    /** 새 복원이 시작되면 이전 복원 시도를 무효로 만든다. */
    private var restoreToken = 0
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
                // about:blank은 '빈 탭'을 뜻한다. 주소로 보이면 시작 화면이 가려진다.
                url = if (navigatedUrl == BLANK_PAGE) "" else navigatedUrl
                canGoBack = back
                onStateChanged()
            },
            onLoadingChanged = { loading ->
                isLoading = loading
                if (!loading) progress = 100
            },
            onPageSettled = ::restoreScrollIfReturning,
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

            // 보던 자리를 계속 기억해 둔다. 떠날 때 따로 챙기려 하면 이미 늦다.
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val key = snifferClient.currentUrl
                if (key.isNotBlank()) scrollPositions[key] = scrollY
            }

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
                    onStateChanged()
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
        pendingUrl = null
        url = target
        webView.loadUrl(target)
        onStateChanged()
    }

    /** 시작 화면으로 돌아간다. 보던 페이지를 비우고 찾아 둔 영상도 버린다. */
    fun goHome() {
        pendingUrl = null
        url = ""
        title = ""
        detections.clear()
        webView.loadUrl(BLANK_PAGE)
        onStateChanged()
    }

    /** 복원된 탭을 처음 볼 때 호출한다. 불러올 것이 없으면 아무 일도 하지 않는다. */
    fun loadPendingIfNeeded() {
        val target = pendingUrl ?: return
        pendingUrl = null
        webView.loadUrl(target)
    }

    fun goBack() {
        if (!webView.canGoBack()) return
        // 돌아간 페이지에서 보던 자리로 되돌리기 위한 표시.
        returningBack = true
        webView.goBack()
    }

    /**
     * 뒤로 갔을 때 보던 자리로 되돌린다.
     *
     * 사이트가 스스로 해 주는 곳도 있지만(X 등) 그렇지 않은 곳이 많다. 사이트가
     * 이미 되돌려 놓았으면 건드리지 않는다.
     */
    private fun restoreScrollIfReturning(pageUrl: String) {
        if (!returningBack) return
        returningBack = false

        val target = scrollPositions[pageUrl]?.takeIf { it > 0 } ?: return
        val token = ++restoreToken

        // 내용이 다 그려지기 전에는 그만큼 내려갈 수 없다. 자리가 날 때까지 몇 번 시도한다.
        RESTORE_DELAYS_MS.forEach { delay ->
            webView.postDelayed({
                if (token != restoreToken) return@postDelayed
                if (webView.scrollY >= target) return@postDelayed
                webView.scrollTo(0, target)
            }, delay)
        }
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

    private companion object {
        const val BLANK_PAGE = "about:blank"

        /** 기억해 둘 주소 수. 한 탭에서 오간 페이지를 넉넉히 덮는다. */
        const val MAX_SCROLL_MEMORY = 40

        /**
         * 되돌리기를 시도할 시점들.
         *
         * 한 번만 해서는 거의 안 된다. 페이지가 뜬 직후에는 내용이 짧아 그만큼
         * 내려갈 수 없고, 이미지와 지연 로딩이 채워지면서 길어진다.
         */
        val RESTORE_DELAYS_MS = longArrayOf(120, 300, 600, 1_000, 1_600)
    }
}
