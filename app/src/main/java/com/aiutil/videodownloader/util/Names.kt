package com.aiutil.videodownloader.util

import com.aiutil.videodownloader.model.MediaKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Names {

    /** 파일 이름에 쓸 수 없는 문자와 제어 문자. */
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")
    private val WHITESPACE = Regex("\\s+")
    private val KNOWN_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "3gp")
    private const val MAX_BASE_LENGTH = 80

    /** 제목에서 파일 이름을 만든다. 제목이 쓸 수 없으면 URL, 그것도 안 되면 날짜를 쓴다. */
    fun buildFileName(title: String, url: String, kind: MediaKind): String {
        val base = sanitize(title)
            .ifBlank { sanitize(baseNameFromUrl(url)) }
            .ifBlank { "video_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) }
            .take(MAX_BASE_LENGTH)
            .trim()

        return "$base.${extensionFor(url, kind)}"
    }

    /** 확장자만 바꾼다. HLS는 다 받아봐야 MP4로 담을 수 있는지 알 수 있다. */
    fun withExtension(fileName: String, extension: String): String =
        "${fileName.substringBeforeLast('.', fileName)}.$extension"

    fun mimeForExtension(extension: String): String = when (extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "3gp" -> "video/3gpp"
        "flv" -> "video/x-flv"
        "ts" -> "video/mp2t"
        else -> "video/mp4"
    }

    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    private fun extensionFor(url: String, kind: MediaKind): String {
        if (kind == MediaKind.HLS) return "mp4"
        val ext = baseNameFromUrl(url).substringAfterLast('.', "").lowercase()
        return if (ext in KNOWN_EXTENSIONS) ext else "mp4"
    }

    private fun baseNameFromUrl(url: String): String =
        url.substringBefore('?').substringBefore('#').substringAfterLast('/')

    private fun sanitize(input: String): String =
        ILLEGAL.replace(input, " ").replace(WHITESPACE, " ").trim().trim('.').trim()
}
