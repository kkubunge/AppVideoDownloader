package com.aiutil.videodownloader.download

import com.aiutil.videodownloader.core.DebugLog
import com.aiutil.videodownloader.core.Http
import com.aiutil.videodownloader.model.HlsVariant
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/**
 * HLS(.m3u8) 다운로더. 세그먼트를 순서대로 이어 붙여 하나의 파일로 만든다.
 *
 * AES-128 암호화 플레이리스트는 복호화한다. SAMPLE-AES(FairPlay 등 DRM)는 지원하지 않는다.
 */
object HlsDownloader {

    /** 동시에 받아둘 세그먼트 수. 너무 키우면 메모리만 먹고 속도 이득이 적다. */
    private const val PREFETCH = 3

    sealed interface Resolved {
        /** 마스터 플레이리스트 — 화질을 골라야 한다. [durationSec]은 화질과 무관하게 같다. */
        data class Variants(val variants: List<HlsVariant>, val durationSec: Double) : Resolved

        /** 바로 받을 수 있는 미디어 플레이리스트. */
        data class Media(
            val playlistUrl: String,
            val playlist: M3u8Parser.MediaPlaylist,
            val estimatedBytes: Long,
        ) : Resolved
    }

    data class Outcome(val bytes: Long, val isFragmentedMp4: Boolean)

    suspend fun resolve(url: String, headers: Map<String, String>): Resolved =
        withContext(Dispatchers.IO) {
            val document = fetchPlaylist(url, headers)
            if (M3u8Parser.isMaster(document.text)) {
                val variants = M3u8Parser.parseMaster(document.text, document.baseUrl)
                if (variants.isEmpty()) {
                    throw IOException("마스터 플레이리스트에서 화질 목록을 찾지 못했다")
                }
                // 재생 길이는 어느 화질이든 같다. 가장 가벼운 것 하나만 읽어 길이를 얻는다.
                val duration = runCatching {
                    val lightest = variants.minByOrNull { it.bandwidth } ?: variants.last()
                    val media = fetchPlaylist(lightest.url, headers)
                    M3u8Parser.parseMedia(media.text, media.baseUrl).totalDurationSec
                }.getOrDefault(0.0)

                Resolved.Variants(variants, duration)
            } else {
                val playlist = M3u8Parser.parseMedia(document.text, document.baseUrl)
                rejectIfNotVideo(url, playlist)
                Resolved.Media(url, playlist, estimateFromFirstSegment(playlist, headers))
            }
        }

    /**
     * 자막 트랙을 영상으로 착각해 받는 것을 막는다.
     *
     * HLS 마스터에는 영상 말고 자막 트랙의 플레이리스트도 함께 들어 있다. 그 주소가
     * 감지 목록에 올라오면 .vtt 조각을 이어 붙여 재생되지 않는 파일이 만들어진다.
     */
    private fun segmentExtension(playlist: M3u8Parser.MediaPlaylist): String =
        playlist.segments.firstOrNull()
            ?.url?.substringBefore('?')?.substringAfterLast('.', "")?.lowercase()
            .orEmpty()

    private fun rejectIfNotVideo(url: String, playlist: M3u8Parser.MediaPlaylist) {
        if (playlist.segments.isEmpty()) return
        if (segmentExtension(playlist) in SUBTITLE_EXTENSIONS) {
            throw IOException(SUBTITLE_MESSAGE)
        }
        if (looksAudioOnly(url)) {
            throw IOException(AUDIO_ONLY_MESSAGE)
        }
    }

    private val SUBTITLE_EXTENSIONS = setOf("vtt", "webvtt", "srt", "ttml")

    private val SUBTITLE_URL_HINTS = listOf("/sub/", "/subs/", "subtitle", "caption", ".vtt")

    /** 소리만 담긴 트랙. HLS 마스터는 영상과 오디오를 따로 담는 일이 흔하다. */
    private val AUDIO_URL_HINTS = listOf("/mp4a/", "/audio/", "/aac/", "audio-only")

    const val AUDIO_ONLY_MESSAGE = "소리만 있는 주소입니다. 같은 영상의 영상 주소를 골라 주세요"

    fun looksAudioOnly(url: String): Boolean {
        val lower = url.lowercase()
        return AUDIO_URL_HINTS.any { it in lower }
    }

    /**
     * 주소 모양만 보고 영상이 아닐 가능성을 가늠한다(자막·오디오 전용).
     *
     * 확실하지 않다 — 사이트마다 경로가 다르다. 그래서 거르는 데는 쓰지 않고,
     * 어느 것부터 시도할지 순서를 정하는 데만 쓴다.
     */
    fun looksLikeNonVideo(url: String): Boolean {
        val lower = url.lowercase()
        return SUBTITLE_URL_HINTS.any { it in lower } || AUDIO_URL_HINTS.any { it in lower }
    }

    /** 이 문구를 보면 화면이 감지 목록을 열어 준다. 영상 주소를 바로 고를 수 있게. */
    const val SUBTITLE_MESSAGE = "이 주소는 자막 트랙입니다. 같은 영상의 영상 주소를 골라 주세요"

    /**
     * 화질별 예상 용량. 비트레이트 × 재생시간 ÷ 8.
     * 실제 크기와는 보통 10% 안쪽으로 차이 난다.
     */
    fun estimateBytes(bandwidth: Long, durationSec: Double): Long =
        if (bandwidth <= 0 || durationSec <= 0) -1L else (bandwidth * durationSec / 8).toLong()

    /**
     * 마스터 없이 미디어 플레이리스트만 있을 때는 비트레이트를 알 수 없다.
     * 첫 조각 크기를 재생시간 비율로 환산해 전체를 가늠한다 — 요청 한 번이면 된다.
     */
    private fun estimateFromFirstSegment(
        playlist: M3u8Parser.MediaPlaylist,
        headers: Map<String, String>,
    ): Long {
        val first = playlist.segments.firstOrNull() ?: return -1L
        if (playlist.totalDurationSec <= 0 || first.durationSec <= 0) return -1L

        val size = runCatching { contentLengthOf(first.url, headers) }.getOrDefault(-1L)
        if (size <= 0) return -1L
        return (size / first.durationSec * playlist.totalDurationSec).toLong()
    }

    private fun contentLengthOf(url: String, headers: Map<String, String>): Long {
        Http.probeClient.newCall(Http.requestBuilder(url, headers).head().build()).execute()
            .use { resp ->
                if (resp.isSuccessful) {
                    resp.header("Content-Length")?.toLongOrNull()?.let { return it }
                }
            }
        return -1L
    }

    /**
     * [playlistUrl]의 세그먼트를 [target]에 이어 붙인다.
     *
     * @param startSegment 이어받기 시작할 세그먼트 번호
     * @param startBytes 그 시점까지 기록된 바이트 수. 파일을 여기까지 잘라낸 뒤 이어 쓴다.
     */
    suspend fun download(
        playlistUrl: String,
        headers: Map<String, String>,
        target: File,
        startSegment: Int,
        startBytes: Long,
        onProgress: (segmentIndex: Int, segmentCount: Int, bytes: Long) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val document = fetchPlaylist(playlistUrl, headers)

        // 받기 직전에 마스터가 넘어오는 경우를 대비한다(주소가 바뀌었을 수 있다).
        val media = if (M3u8Parser.isMaster(document.text)) {
            val best = M3u8Parser.parseMaster(document.text, document.baseUrl).firstOrNull()
                ?: throw IOException("마스터 플레이리스트에서 화질 목록을 찾지 못했다")
            fetchPlaylist(best.url, headers)
        } else {
            document
        }

        val playlist = M3u8Parser.parseMedia(media.text, media.baseUrl)
        rejectIfNotVideo(playlistUrl, playlist)

        DebugLog.log(
            "HLS",
            "조각 ${playlist.segments.size}개 · 길이 ${playlist.totalDurationSec.toInt()}초 · " +
                "첫 조각 .${segmentExtension(playlist)} · " +
                "초기화조각 ${if (playlist.initSegmentUrl != null) "있음(fMP4)" else "없음(TS)"} · " +
                "암호화 ${if (playlist.segments.any { it.key != null }) "AES-128" else "없음"}",
        )

        if (playlist.isLive) {
            throw IOException("라이브 스트림은 끝이 없어 통째로 받을 수 없다")
        }
        if (playlist.segments.isEmpty()) {
            throw IOException("플레이리스트에 세그먼트가 없다")
        }
        playlist.segments.firstOrNull { it.key?.method?.startsWith("SAMPLE-AES") == true }?.let {
            throw IOException("DRM(SAMPLE-AES)으로 보호된 스트림은 지원하지 않는다")
        }
        playlist.segments.firstOrNull {
            it.key != null && !it.key.method.equals("AES-128", ignoreCase = true)
        }?.let {
            throw IOException("지원하지 않는 암호화 방식: ${it.key?.method}")
        }

        target.parentFile?.mkdirs()
        var written = prepareTarget(target, startSegment, startBytes)
        var index = if (written == 0L) 0 else startSegment

        val keyCache = ConcurrentHashMap<String, ByteArray>()
        val segments = playlist.segments
        onProgress(index, segments.size, written)

        FileOutputStream(target, written > 0).use { out ->
            // fMP4는 초기화 세그먼트가 맨 앞에 와야 재생된다.
            if (written == 0L && playlist.initSegmentUrl != null) {
                val init = fetchBytes(playlist.initSegmentUrl, headers)
                out.write(init)
                written += init.size
            }

            coroutineScope {
                val inFlight = ArrayDeque<Deferred<ByteArray>>()
                var queued = index

                fun queueNext() {
                    if (queued >= segments.size) return
                    val segment = segments[queued]
                    queued++
                    inFlight.addLast(async(Dispatchers.IO) { fetchSegment(segment, headers, keyCache) })
                }

                repeat(PREFETCH) { queueNext() }

                while (inFlight.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    val data = inFlight.removeFirst().await()
                    out.write(data)
                    written += data.size
                    index++
                    onProgress(index, segments.size, written)
                    queueNext()
                }
            }
            out.flush()
        }

        DebugLog.log("HLS", "받기 끝 · ${written}바이트 기록")
        Outcome(bytes = written, isFragmentedMp4 = playlist.initSegmentUrl != null)
    }

    /**
     * 이어받기 준비. 기록된 바이트 수까지만 남기고 잘라낸다 — 중간에 끊긴 세그먼트 조각이
     * 남아 있으면 파일이 깨진다.
     */
    private fun prepareTarget(target: File, startSegment: Int, startBytes: Long): Long {
        if (startSegment <= 0 || startBytes <= 0 || !target.exists()) {
            target.delete()
            return 0L
        }
        return try {
            RandomAccessFile(target, "rw").use { raf ->
                if (raf.length() > startBytes) raf.setLength(startBytes)
                raf.length()
            }
        } catch (t: Throwable) {
            target.delete()
            0L
        }
    }

    private suspend fun fetchSegment(
        segment: M3u8Parser.Segment,
        headers: Map<String, String>,
        keyCache: ConcurrentHashMap<String, ByteArray>,
    ): ByteArray {
        val raw = fetchBytes(segment.url, headers)
        val key = segment.key ?: return raw
        val keyBytes = keyCache[key.keyUrl]
            ?: fetchBytes(key.keyUrl, headers).also { keyCache[key.keyUrl] = it }
        return decryptAes128(raw, keyBytes, key.ivHex, segment.sequence)
    }

    private fun decryptAes128(
        data: ByteArray,
        key: ByteArray,
        ivHex: String?,
        sequence: Long,
    ): ByteArray {
        val iv = if (ivHex != null) hexToBytes(ivHex) else sequenceToIv(sequence)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** IV가 명시되지 않으면 미디어 시퀀스 번호를 16바이트 빅엔디안으로 쓴다(RFC 8216). */
    private fun sequenceToIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 8) {
            iv[15 - i] = ((sequence shr (8 * i)) and 0xFF).toByte()
        }
        return iv
    }

    private fun hexToBytes(input: String): ByteArray {
        val hex = input.removePrefix("0x").removePrefix("0X").trim()
        val padded = if (hex.length % 2 == 0) hex else "0$hex"
        return ByteArray(padded.length / 2) {
            padded.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    /** 플레이리스트 본문과, 상대 경로를 풀 때 기준이 될 주소. */
    private data class Playlist(val text: String, val baseUrl: String)

    /**
     * 플레이리스트를 받는다.
     *
     * 리다이렉트를 따라갔다면 조각의 상대 경로는 **최종 주소**를 기준으로 풀어야 한다.
     * 원래 주소로 풀면 전혀 다른 곳을 가리키게 되고, 서버는 보통 오류 페이지를 돌려준다.
     */
    private fun fetchPlaylist(url: String, headers: Map<String, String>): Playlist =
        Http.client.newCall(Http.requestBuilder(url, headers).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("플레이리스트를 받지 못했다 (${resp.code})")
            val body = resp.body?.string() ?: throw IOException("플레이리스트가 비어 있다")
            if (!body.contains("#EXTM3U")) {
                throw IOException("플레이리스트가 아닌 응답을 받았습니다. 주소가 만료됐을 수 있습니다")
            }
            Playlist(body, resp.request.url.toString())
        }

    /** 한 번 끊겼다고 전체가 날아가면 아깝다. 몇 번 더 시도해 본다. */
    private suspend fun fetchBytes(url: String, headers: Map<String, String>): ByteArray {
        var last: IOException? = null
        repeat(SEGMENT_ATTEMPTS) { attempt ->
            try {
                return fetchBytesOnce(url, headers)
            } catch (e: IOException) {
                last = e
                if (attempt < SEGMENT_ATTEMPTS - 1) delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw last ?: IOException("조각을 받지 못했다")
    }

    private fun fetchBytesOnce(url: String, headers: Map<String, String>): ByteArray =
        Http.client.newCall(Http.requestBuilder(url, headers).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("조각을 받지 못했다 (${resp.code})")

            // 오류를 404가 아니라 200 + HTML/JSON으로 돌려주는 서버가 많다.
            // 그대로 이어 붙이면 재생되지 않는 파일이 만들어진다.
            val type = resp.header("Content-Type")?.lowercase().orEmpty()
            if (TEXTUAL_TYPES.any { it in type }) {
                throw IOException(
                    "조각 대신 오류 응답을 받았습니다 " +
                        "(${resp.code} ${type.substringBefore(';')} · ${hostOf(url)}). " +
                        "주소가 만료됐거나 로그인이 필요할 수 있습니다"
                )
            }

            val bytes = resp.body?.bytes() ?: throw IOException("조각이 비어 있다")
            if (bytes.isEmpty()) throw IOException("조각이 비어 있다")
            bytes
        }

    private fun hostOf(url: String): String =
        runCatching { java.net.URL(url).host }.getOrDefault("")

    private val TEXTUAL_TYPES = listOf("text/", "html", "json", "xml")

    /** 조각 하나를 포기하기 전까지의 시도 횟수. */
    private const val SEGMENT_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 400L
}
