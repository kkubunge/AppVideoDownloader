package com.aiutil.videodownloader.download

import com.aiutil.videodownloader.model.HlsVariant
import java.net.URL

/**
 * HLS 플레이리스트 파서. 필요한 만큼만 구현했다 — 마스터의 화질 목록, 미디어의 세그먼트 목록,
 * AES-128 키, fMP4 초기화 세그먼트.
 */
object M3u8Parser {

    data class KeyInfo(val method: String, val keyUrl: String, val ivHex: String?)

    data class Segment(
        val url: String,
        val durationSec: Double,
        val key: KeyInfo?,
        val sequence: Long,
    )

    data class MediaPlaylist(
        val segments: List<Segment>,
        val initSegmentUrl: String?,
        val totalDurationSec: Double,
        /** #EXT-X-ENDLIST가 없으면 라이브. 끝이 없으므로 통째로 받을 수 없다. */
        val isLive: Boolean,
    )

    fun isMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    fun parseMaster(text: String, baseUrl: String): List<HlsVariant> {
        val variants = mutableListOf<HlsVariant>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attrs = parseAttributes(line.substringAfter(':'))
                val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                if (uri != null) {
                    val (w, h) = attrs["RESOLUTION"]?.split('x')
                        ?.let { (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
                        ?: (0 to 0)
                    variants += HlsVariant(
                        url = resolve(baseUrl, uri.trim()),
                        bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                        width = w,
                        height = h,
                        codecs = attrs["CODECS"],
                    )
                }
            }
            i++
        }
        return variants.distinctBy { it.url }.sortedByDescending { it.bandwidth }
    }

    fun parseMedia(text: String, baseUrl: String): MediaPlaylist {
        val segments = mutableListOf<Segment>()
        var currentKey: KeyInfo? = null
        var pendingDuration = 0.0
        var initSegment: String? = null
        var sequence = text.lineSequence()
            .firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
            ?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
        var isLive = true

        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTINF:") ->
                    pendingDuration = line.substringAfter(':').substringBefore(',')
                        .trim().toDoubleOrNull() ?: 0.0

                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    val method = attrs["METHOD"].orEmpty()
                    currentKey = if (method.equals("NONE", true) || attrs["URI"] == null) null
                    else KeyInfo(method, resolve(baseUrl, attrs["URI"]!!), attrs["IV"])
                }

                line.startsWith("#EXT-X-MAP:") ->
                    parseAttributes(line.substringAfter(':'))["URI"]
                        ?.let { initSegment = resolve(baseUrl, it) }

                line == "#EXT-X-ENDLIST" -> isLive = false

                line.startsWith("#") -> Unit

                else -> {
                    segments += Segment(
                        url = resolve(baseUrl, line),
                        durationSec = pendingDuration,
                        key = currentKey,
                        sequence = sequence,
                    )
                    sequence++
                    pendingDuration = 0.0
                }
            }
        }

        return MediaPlaylist(
            segments = segments,
            initSegmentUrl = initSegment,
            totalDurationSec = segments.sumOf { it.durationSec },
            isLive = isLive,
        )
    }

    /** `KEY=VALUE,KEY="VALUE,WITH,COMMAS"` 형태를 따옴표를 존중하며 쪼갠다. */
    private fun parseAttributes(input: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val sb = StringBuilder()
        var inQuotes = false
        val parts = mutableListOf<String>()
        for (c in input) {
            when {
                c == '"' -> { inQuotes = !inQuotes; sb.append(c) }
                c == ',' && !inQuotes -> { parts += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) parts += sb.toString()

        for (part in parts) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val key = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim().trim('"')
            result[key] = value
        }
        return result
    }

    fun resolve(baseUrl: String, ref: String): String = runCatching {
        URL(URL(baseUrl), ref).toString()
    }.getOrDefault(ref)
}
