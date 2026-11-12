package com.aiutil.videodownloader.browser

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.aiutil.videodownloader.core.Http
import com.aiutil.videodownloader.data.DetectionStore
import kotlinx.coroutines.CoroutineScope

/**
 * 한 탭의 모든 요청을 지켜보는 WebViewClient.
 *
 * [shouldInterceptRequest]는 UI 스레드가 아닌 곳에서 불린다. 그래서 현재 페이지 정보를
 * WebView에서 직접 읽지 않고 [currentUrl]/[currentTitle]에 복사해 둔 값을 쓴다.
 */
class SnifferWebViewClient(
    private val scope: CoroutineScope,
    private val detections: DetectionStore,
    private val onNavigate: (url: String, canGoBack: Boolean) -> Unit,
    private val onLoadingChanged: (Boolean) -> Unit,
) : WebViewClient() {

    @Volatile
    var currentUrl: String = ""
        private set

    @Volatile
    var currentTitle: String = ""

    /** 다운로드할 때도 같은 자격증명이 필요하다. 감지 시점의 헤더를 그대로 물려준다. */
    fun downloadHeaders(forUrl: String): Map<String, String> = buildMap<String, String> {
        put("User-Agent", Http.MOBILE_UA)
        if (currentUrl.isNotBlank()) {
            put("Referer", currentUrl)
            originOf(currentUrl)?.let { put("Origin", it) }
        }
        runCatching { CookieManager.getInstance().getCookie(forUrl) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { put("Cookie", it) }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        currentUrl = url
        currentTitle = view.title.orEmpty()
        detections.onPageChanged(url)
        onLoadingChanged(true)
        onNavigate(url, view.canGoBack())
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        currentUrl = url
        currentTitle = view.title.orEmpty()
        onLoadingChanged(false)
        onNavigate(url, view.canGoBack())
        view.evaluateJavascript(VideoJsBridge.SCANNER_JS, null)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        // SPA는 페이지 전환을 해도 onPageStarted가 오지 않는다. 여기서 보강한다.
        currentUrl = url
        detections.onPageChanged(url)
        onNavigate(url, view.canGoBack())
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        // intent://, market://, tel:// 등은 WebView가 처리하지 못한다. 그냥 무시해서
        // "페이지를 찾을 수 없음"으로 빠지는 것을 막는다.
        return !(url.startsWith("http://", true) || url.startsWith("https://", true))
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (request.method.equals("GET", ignoreCase = true)) {
            val url = request.url.toString()
            val headers = buildMap<String, String> {
                request.requestHeaders?.forEach { (k, v) -> if (!k.equals("Range", true)) put(k, v) }
                putAll(downloadHeaders(url))
            }
            VideoSniffer.offer(scope, detections, url, headers, currentUrl, currentTitle)
        }
        // 응답은 건드리지 않는다. 관찰만 한다.
        return null
    }

    private fun originOf(url: String): String? = runCatching {
        val u = java.net.URL(url)
        val port = if (u.port > 0 && u.port != u.defaultPort) ":${u.port}" else ""
        "${u.protocol}://${u.host}$port"
    }.getOrNull()
}
