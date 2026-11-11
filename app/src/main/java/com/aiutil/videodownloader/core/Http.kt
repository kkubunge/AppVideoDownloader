package com.aiutil.videodownloader.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Http {

    /**
     * 모바일 UA. 사이트가 모바일 레이아웃을 주도록 해서, 데스크톱 전용 플레이어 스크립트 대신
     * 단순한 <video> 태그를 만나는 확률을 높인다.
     */
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    /** 다운로드는 길게 걸리므로 callTimeout은 두지 않고 읽기 타임아웃만 건다. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }

    /** 스니핑용 짧은 클라이언트. 페이지 로딩을 막지 않도록 타임아웃을 공격적으로 짧게 잡는다. */
    val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun requestBuilder(url: String, headers: Map<String, String>): Request.Builder {
        val b = Request.Builder().url(url)
        var hasUa = false
        headers.forEach { (k, v) ->
            if (v.isNotBlank()) {
                b.header(k, v)
                if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
            }
        }
        if (!hasUa) b.header("User-Agent", MOBILE_UA)
        return b
    }
}
