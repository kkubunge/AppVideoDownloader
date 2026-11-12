package com.aiutil.videodownloader.download

import com.aiutil.videodownloader.core.Http
import com.aiutil.videodownloader.model.HlsVariant
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
            val text = fetchText(url, headers)
            if (M3u8Parser.isMaster(text)) {
                val variants = M3u8Parser.parseMaster(text, url)
                if (variants.isEmpty()) {
                    throw IOException("마스터 플레이리스트에서 화질 목록을 찾지 못했다")
                }
                // 재생 길이는 어느 화질이든 같다. 가장 가벼운 것 하나만 읽어 길이를 얻는다.
                val duration = runCatching {
                    val lightest = variants.minByOrNull { it.bandwidth } ?: variants.last()
                    M3u8Parser.parseMedia(fetchText(lightest.url, headers), lightest.url)
                        .totalDurationSec
                }.getOrDefault(0.0)

                Resolved.Variants(variants, duration)
            } else {
                val playlist = M3u8Parser.parseMedia(text, url)
                Resolved.Media(url, playlist, estimateFromFirstSegment(playlist, headers))
            }
        }

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
        val playlistText = fetchText(playlistUrl, headers)
        val playlist = M3u8Parser.parseMedia(playlistText, playlistUrl)

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

    private fun fetchSegment(
        segment: M3u8Parser.Segment,
        headers: Map<String, String>,
        keyCache: ConcurrentHashMap<String, ByteArray>,
    ): ByteArray {
        val raw = fetchBytes(segment.url, headers)
        val key = segment.key ?: return raw
        val keyBytes = keyCache.getOrPut(key.keyUrl) { fetchBytes(key.keyUrl, headers) }
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

    private fun fetchText(url: String, headers: Map<String, String>): String =
        Http.client.newCall(Http.requestBuilder(url, headers).get().build()).execute().use {
            if (!it.isSuccessful) throw IOException("플레이리스트를 받지 못했다 (${it.code})")
            it.body?.string() ?: throw IOException("플레이리스트가 비어 있다")
        }

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray =
        Http.client.newCall(Http.requestBuilder(url, headers).get().build()).execute().use {
            if (!it.isSuccessful) throw IOException("조각을 받지 못했다 (${it.code}) $url")
            it.body?.bytes() ?: throw IOException("조각이 비어 있다")
        }
}
