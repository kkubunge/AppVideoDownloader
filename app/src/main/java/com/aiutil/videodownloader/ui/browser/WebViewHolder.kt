package com.aiutil.videodownloader.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import com.aiutil.videodownloader.App
import com.aiutil.videodownloader.browser.SnifferWebViewClient
import com.aiutil.videodownloader.browser.VideoJsBridge
import com.aiutil.videodownloader.core.Http
import com.aiutil.videodownloader.model.MediaKind
import com.aiutil.videodownloader.util.Names
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * WebView 인스턴스를 탭 전환보다 오래 살려 둔다. 탭을 옮겼다 돌아올 때마다 페이지가
 * 다시 로드되면 재생 중이던 영상도, 모아 둔 감지 결과도 날아간다.
 */
class WebViewHolder {

    var webView: WebView? = null
        private set

    var client: SnifferWebViewClient? = null
        private set

    /**
     * 스니핑용 백그라운드 작업 스코프. 컴포지션이 아니라 이 홀더의 수명을 따른다 —
     * 탭을 옮겼다고 확인 중이던 요청이 끊기면 안 된다.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreate(context: Context, viewModel: BrowserViewModel): WebView {
        webView?.let { existing ->
            // 이전 부모에서 떼어내야 다시 붙일 수 있다.
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }

        val snifferClient = SnifferWebViewClient(
            scope = scope,
            onNavigate = viewModel::onNavigated,
            onLoadingChanged = viewModel::onLoadingChanged,
        )

        val view = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

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
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    snifferClient.currentTitle = title.orEmpty()
                }
            }

            addJavascriptInterface(
                VideoJsBridge(
                    scope = scope,
                    pageInfo = {
                        VideoJsBridge.PageInfo(snifferClient.currentUrl, snifferClient.currentTitle)
                    },
                    headers = { snifferClient.downloadHeaders(snifferClient.currentUrl) },
                ),
                VideoJsBridge.NAME,
            )

            // 사이트가 직접 내려주는 파일 링크(<a download>, Content-Disposition)를 가로챈다.
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                val name = fileNameFrom(contentDisposition, url)
                App.downloads.enqueue(
                    url = url,
                    title = name,
                    kind = if (mimeType?.contains("mpegurl", true) == true) MediaKind.HLS else MediaKind.MP4,
                    headers = snifferClient.downloadHeaders(url),
                    pageUrl = snifferClient.currentUrl,
                )
            }
        }

        webView = view
        client = snifferClient
        return view
    }

    fun destroy() {
        scope.cancel()
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        client = null
    }

    private fun fileNameFrom(contentDisposition: String?, url: String): String {
        val fromHeader = contentDisposition
            ?.substringAfter("filename=", "")
            ?.trim('"', ' ', ';')
            ?.takeIf { it.isNotBlank() }
        return fromHeader ?: Names.buildFileName("", url, MediaKind.MP4)
    }
}
