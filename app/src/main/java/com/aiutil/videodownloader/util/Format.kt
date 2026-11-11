package com.aiutil.videodownloader.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {

    fun bytes(value: Long): String = when {
        value < 0 -> "크기 미상"
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", value / 1024.0)
        value < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", value / (1024.0 * 1024 * 1024))
    }

    fun percent(progress: Float): String =
        if (progress < 0f) "—" else "${(progress * 100).toInt()}%"

    fun timestamp(millis: Long): String =
        SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(millis))

    /** 호스트명만 뽑는다. 목록에서 출처를 한눈에 보여주는 용도. */
    fun host(url: String): String = runCatching {
        java.net.URL(url).host.removePrefix("www.")
    }.getOrDefault("")
}
