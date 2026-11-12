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
                posters = toList(json.optJSONArray("posters")),
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

          // 피드는 재생을 누르기 전까지 <video>를 만들지 않고 썸네일만 보여 준다.
          // 그래서 썸네일 이미지도 '화면에 있는 영상'으로 센다. 썸네일 주소에는
          // 스트림과 같은 미디어 번호가 들어 있어 짝짓는 데도 쓸 수 있다.
          function thumbnailLike(src) {
            if (!src) return false;
            var s = src.toLowerCase();
            if (s.indexOf('video_thumb') >= 0) return true;
            if (s.indexOf('vid_thumb') >= 0) return true;
            if (s.indexOf('poster') >= 0) return true;
            return s.indexOf('thumb') >= 0 && s.indexOf('video') >= 0;
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
          var seq = 0;

          function scan() {
            var all = [];
            var items = [];
            var visibleCount = 0;

            var videos = document.querySelectorAll('video');
            for (var i = 0; i < videos.length; i++) {
              var v = videos[i];

              // src가 blob이면 주소로는 영상을 구분할 수 없다. 요소마다 번호를 붙여
              // '보이는 영상이 바뀌었다'는 사실만이라도 정확히 알 수 있게 한다.
              if (!v.__vdlId) { seq++; v.__vdlId = 'v' + seq; }

              var srcs = srcsOf(v);
              for (var j = 0; j < srcs.length; j++) all.push(srcs[j]);

              var ratio = visibleRatio(v);
              if (ratio >= ${VISIBLE_RATIO}) {
                visibleCount++;
                items.push({
                  id: v.__vdlId,
                  r: ratio,
                  s: srcs,
                  // 썸네일 주소에는 보통 스트림과 같은 미디어 번호가 들어 있다.
                  p: usable(v.poster) ? absolute(v.poster) : ''
                });
              }
            }

            // 아직 <video>가 만들어지지 않은 영상은 썸네일로 잡는다.
            // 프로필 사진 같은 작은 이미지는 건너뛴다.
            var images = document.querySelectorAll('img');
            for (var t = 0; t < images.length; t++) {
              var im = images[t];
              if (im.naturalWidth < 150 || im.naturalHeight < 150) continue;

              var isrc = im.currentSrc || im.getAttribute('src');
              if (!thumbnailLike(isrc)) continue;

              if (!im.__vdlId) { seq++; im.__vdlId = 'i' + seq; }
              var iratio = visibleRatio(im);
              if (iratio >= ${VISIBLE_RATIO}) {
                visibleCount++;
                items.push({ id: im.__vdlId, r: iratio, s: [], p: absolute(isrc) });
              }
            }

            // 많이 보이는 것부터 앞에 둔다. 앱은 이 순서를 그대로 목록에 쓴다.
            items.sort(function (a, b) { return b.r - a.r; });

            var visible = [];
            var posters = [];
            var key = '';
            for (var n = 0; n < items.length; n++) {
              for (var m = 0; m < items[n].s.length; m++) visible.push(items[n].s[m]);
              if (items[n].p) posters.push(items[n].p);
              key += items[n].id + '|';
            }

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

            // 요소 번호로 키를 만든다. 주소가 blob이라 전부 빈 문자열이어도
            // 보이는 영상이 바뀌면 키는 반드시 바뀐다.
            var changed = key !== lastKey;
            lastKey = key;

            bridge.onScan(JSON.stringify({
              all: all,
              visible: visible,
              posters: posters,
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
