package com.aiutil.videodownloader.browser

import android.util.Log
import android.webkit.JavascriptInterface
import com.aiutil.videodownloader.data.DetectionStore
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray

/**
 * 페이지 안에서 돌아가는 스캐너가 찾은 주소를 받는 창구.
 *
 * 네트워크 가로채기만으로는 아직 재생을 시작하지 않은 영상을 놓친다. DOM 쪽에서도 한 번 훑어
 * 두 경로를 합친다.
 */
class VideoJsBridge(
    private val scope: CoroutineScope,
    private val detections: DetectionStore,
    private val pageInfo: () -> PageInfo,
    private val headers: () -> Map<String, String>,
) {
    data class PageInfo(val url: String, val title: String)

    @JavascriptInterface
    fun onVideosFound(payload: String) {
        try {
            val info = pageInfo()
            val hdrs = headers()
            val arr = JSONArray(payload)
            for (i in 0 until arr.length()) {
                val url = arr.optJSONObject(i)?.optString("url").orEmpty()
                if (url.isNotBlank()) {
                    VideoSniffer.offerFromDom(scope, detections, url, hdrs, info.url, info.title)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DOM 스캔 결과 처리 실패", t)
        }
    }

    companion object {
        const val NAME = "AndroidVideoBridge"
        private const val TAG = "VideoJsBridge"

        /**
         * 페이지 로드가 끝나면 주입한다. 동적으로 플레이어를 꽂는 사이트가 많아서 한 번이 아니라
         * 잠시 동안 반복해서 훑고, 재생이 시작될 때도 다시 훑는다.
         */
        val SCANNER_JS = """
        (function () {
          if (window.__vdlScannerInstalled) return;
          window.__vdlScannerInstalled = true;

          function absolute(u) {
            try { return new URL(u, document.baseURI).href; } catch (e) { return u; }
          }

          function collect() {
            var out = [];
            var seen = {};
            function push(u) {
              if (!u) return;
              if (u.indexOf('blob:') === 0 || u.indexOf('data:') === 0) return;
              u = absolute(u);
              if (seen[u]) return;
              seen[u] = 1;
              out.push({ url: u });
            }

            var videos = document.querySelectorAll('video');
            for (var i = 0; i < videos.length; i++) {
              push(videos[i].currentSrc);
              push(videos[i].getAttribute('src'));
              var srcs = videos[i].querySelectorAll('source');
              for (var j = 0; j < srcs.length; j++) push(srcs[j].getAttribute('src'));
            }

            var links = document.querySelectorAll(
              'source[type*="video"], source[type*="mpegurl"], a[href*=".mp4"], a[href*=".m3u8"]'
            );
            for (var k = 0; k < links.length; k++) {
              push(links[k].getAttribute('src') || links[k].getAttribute('href'));
            }

            var metas = document.querySelectorAll(
              'meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"]'
            );
            for (var m = 0; m < metas.length; m++) push(metas[m].getAttribute('content'));

            if (out.length && window.${NAME}) {
              window.${NAME}.onVideosFound(JSON.stringify(out));
            }
          }

          collect();
          var ticks = 0;
          var timer = setInterval(function () {
            collect();
            if (++ticks > 12) clearInterval(timer);
          }, 1500);
          document.addEventListener('play', collect, true);
          document.addEventListener('loadedmetadata', collect, true);
        })();
        """.trimIndent()
    }
}
