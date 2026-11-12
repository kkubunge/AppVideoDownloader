package com.aiutil.videodownloader.browser

import android.util.Log
import android.webkit.JavascriptInterface
import com.aiutil.videodownloader.data.DetectionStore
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * 페이지 안에서 돌아가는 스캐너가 보내는 결과를 받는 창구.
 *
 * 네트워크 가로채기만으로는 "지금 화면에 보이는 영상"을 알 수 없다. 피드를 스크롤하면
 * 지나간 영상의 스트림까지 전부 잡히기 때문이다. 그래서 DOM 쪽에서 뷰포트 안에 있는
 * <video>만 따로 골라 알려 준다.
 */
class VideoJsBridge(
    private val scope: CoroutineScope,
    private val detections: DetectionStore,
    private val pageInfo: () -> PageInfo,
    private val headers: () -> Map<String, String>,
) {
    data class PageInfo(val url: String, val title: String)

    @JavascriptInterface
    fun onScan(payload: String) {
        try {
            val info = pageInfo()
            val hdrs = headers()
            val json = JSONObject(payload)

            toList(json.optJSONArray("all")).forEach { url ->
                VideoSniffer.offerFromDom(scope, detections, url, hdrs, info.url, info.title)
            }

            detections.setOnScreen(
                urls = toList(json.optJSONArray("visible")),
                count = json.optInt("visibleCount", 0),
                changed = json.optBoolean("changed", false),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "화면 스캔 결과 처리 실패", t)
        }
    }

    private fun toList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            array.optString(i).takeIf { it.isNotBlank() }?.let(out::add)
        }
        return out
    }

    companion object {
        const val NAME = "AndroidVideoBridge"
        private const val TAG = "VideoJsBridge"

        /** 영상이 이만큼 이상 보이면 "화면에 있다"고 본다. */
        private const val VISIBLE_RATIO = "0.5"

        /**
         * 페이지 로드가 끝나면 주입한다.
         *
         * 스크롤·재생·크기 변경 때마다 다시 훑고, 동적으로 플레이어를 꽂는 사이트를 위해
         * 주기적으로도 확인한다.
         */
        val SCANNER_JS = """
        (function () {
          if (window.__vdlScannerInstalled) return;
          window.__vdlScannerInstalled = true;

          var bridge = window.${NAME};
          if (!bridge) return;

          function absolute(u) {
            try { return new URL(u, document.baseURI).href; } catch (e) { return u; }
          }

          // blob:/data: 는 메모리 상의 스트림이라 직접 받을 수 없다.
          function usable(u) {
            return !!u && u.indexOf('blob:') !== 0 && u.indexOf('data:') !== 0;
          }

          function srcsOf(v) {
            var out = [];
            if (usable(v.currentSrc)) out.push(absolute(v.currentSrc));
            var attr = v.getAttribute('src');
            if (usable(attr)) out.push(absolute(attr));
            var sources = v.querySelectorAll('source');
            for (var i = 0; i < sources.length; i++) {
              var s = sources[i].getAttribute('src');
              if (usable(s)) out.push(absolute(s));
            }
            return out;
          }

          // 요소가 뷰포트와 겹치는 비율.
          function visibleRatio(el) {
            var r = el.getBoundingClientRect();
            if (r.width <= 0 || r.height <= 0) return 0;
            var vh = window.innerHeight || document.documentElement.clientHeight;
            var vw = window.innerWidth || document.documentElement.clientWidth;
            var top = r.top > 0 ? r.top : 0;
            var left = r.left > 0 ? r.left : 0;
            var bottom = r.bottom < vh ? r.bottom : vh;
            var right = r.right < vw ? r.right : vw;
            if (bottom <= top || right <= left) return 0;
            return ((bottom - top) * (right - left)) / (r.width * r.height);
          }

          var lastKey = '';

          function scan() {
            var all = [];
            var entries = [];
            var visibleCount = 0;

            var videos = document.querySelectorAll('video');
            for (var i = 0; i < videos.length; i++) {
              var v = videos[i];
              var srcs = srcsOf(v);
              var ratio = visibleRatio(v);
              var onScreen = ratio >= ${VISIBLE_RATIO};
              if (onScreen) visibleCount++;
              for (var j = 0; j < srcs.length; j++) {
                all.push(srcs[j]);
                if (onScreen) entries.push({ u: srcs[j], r: ratio });
              }
            }

            // 많이 보이는 것부터 앞에 둔다. 앱은 이 순서를 그대로 목록에 쓴다.
            entries.sort(function (a, b) { return b.r - a.r; });
            var visible = [];
            for (var n = 0; n < entries.length; n++) visible.push(entries[n].u);

            var extra = document.querySelectorAll(
              'source[type*="video"], source[type*="mpegurl"], a[href*=".mp4"], a[href*=".m3u8"]'
            );
            for (var k = 0; k < extra.length; k++) {
              var u = extra[k].getAttribute('src') || extra[k].getAttribute('href');
              if (usable(u)) all.push(absolute(u));
            }

            var metas = document.querySelectorAll(
              'meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"]'
            );
            for (var m = 0; m < metas.length; m++) {
              var mu = metas[m].getAttribute('content');
              if (usable(mu)) all.push(absolute(mu));
            }

            // 보이는 영상이 바뀐 순간을 알려야, blob이라 주소를 못 얻는 스트림도
            // "이 시점 직후에 잡힌 것"으로 짝지을 수 있다.
            var key = visibleCount + ':' + visible.join('|');
            var changed = key !== lastKey;
            lastKey = key;

            bridge.onScan(JSON.stringify({
              all: all,
              visible: visible,
              visibleCount: visibleCount,
              changed: changed
            }));
          }

          var pending = false;
          function schedule() {
            if (pending) return;
            pending = true;
            setTimeout(function () { pending = false; scan(); }, 300);
          }

          scan();
          window.addEventListener('scroll', schedule, true);
          window.addEventListener('resize', schedule, true);
          document.addEventListener('play', schedule, true);
          document.addEventListener('loadedmetadata', schedule, true);
          setInterval(scan, 2000);
        })();
        """.trimIndent()
    }
}
