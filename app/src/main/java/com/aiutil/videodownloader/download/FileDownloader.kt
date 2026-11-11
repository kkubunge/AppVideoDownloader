package com.aiutil.videodownloader.download

import com.aiutil.videodownloader.core.Http
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * 단일 파일(MP4 등) 다운로더. Range 헤더로 이어받기를 지원한다.
 */
object FileDownloader {

    private const val BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_INTERVAL_MS = 300L

    /**
     * [target]에 내려받는다. 파일이 이미 있으면 그 지점부터 이어받는다.
     *
     * @return 최종 파일 크기
     * @throws IOException 서버 오류나 연결 실패
     */
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Long {
        target.parentFile?.mkdirs()
        var start = if (target.exists()) target.length() else 0L

        val builder = Http.requestBuilder(url, headers)
        if (start > 0) builder.header("Range", "bytes=$start-")

        Http.client.newCall(builder.get().build()).execute().use { response ->
            when {
                response.code == 416 -> {
                    // 서버가 "요청한 범위 없음"이라 답했다. 이미 다 받았다고 본다.
                    onProgress(start, start)
                    return start
                }

                !response.isSuccessful ->
                    throw IOException("서버 응답 ${response.code} ${response.message}")
            }

            // Range를 무시하고 200으로 전체를 보내는 서버가 있다. 이땐 처음부터 다시 쓴다.
            val appending = response.code == 206
            if (!appending && start > 0) {
                start = 0
                target.delete()
            }

            val body = response.body ?: throw IOException("응답 본문이 비어 있다")
            val total = contentTotal(response.header("Content-Range"), body.contentLength(), start)

            var downloaded = start
            var lastReport = 0L
            onProgress(downloaded, total)

            FileOutputStream(target, appending && start > 0).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                            lastReport = now
                            onProgress(downloaded, total)
                        }
                    }
                }
                out.flush()
            }

            onProgress(downloaded, if (total > 0) total else downloaded)
            return downloaded
        }
    }

    /** 전체 크기 계산. 206이면 Content-Range의 총합을, 아니면 본문 길이를 쓴다. */
    private fun contentTotal(contentRange: String?, bodyLength: Long, start: Long): Long {
        val fromRange = contentRange?.substringAfter('/', "")?.trim()
        if (!fromRange.isNullOrEmpty() && fromRange != "*") {
            fromRange.toLongOrNull()?.let { return it }
        }
        return if (bodyLength > 0) bodyLength + start else -1L
    }
}
