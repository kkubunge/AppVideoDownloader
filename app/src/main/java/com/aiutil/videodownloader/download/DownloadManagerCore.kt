package com.aiutil.videodownloader.download

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.aiutil.videodownloader.core.DebugLog
import com.aiutil.videodownloader.data.DownloadHistoryStore
import com.aiutil.videodownloader.data.DownloadStore
import com.aiutil.videodownloader.model.DownloadItem
import com.aiutil.videodownloader.model.DownloadStatus
import com.aiutil.videodownloader.model.MediaKind
import com.aiutil.videodownloader.util.Names
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 다운로드 작업의 실제 주인. UI와 서비스는 여기에 명령만 보낸다.
 *
 * 작업은 앱 프로세스 수명 동안 살아 있는 스코프에서 돌고, [DownloadService]는 프로세스가
 * 죽지 않도록 포그라운드 상태만 붙잡아 준다.
 */
class DownloadManagerCore(
    private val appContext: Context,
    private val store: DownloadStore,
    private val history: DownloadHistoryStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val limiter = Semaphore(MAX_CONCURRENT)

    /** 받는 중인 임시 파일. 완료되기 전까지는 갤러리에 보이지 않는다. */
    private fun partFile(id: String) = File(File(appContext.cacheDir, "downloads"), "$id.part")

    fun enqueue(
        url: String,
        title: String,
        kind: MediaKind,
        headers: Map<String, String>,
        pageUrl: String? = null,
    ): String {
        store.findActiveByUrl(url)?.let { return it.id }

        val id = UUID.randomUUID().toString()
        val fileName = Names.buildFileName(title, url, kind)
        val item = DownloadItem(
            id = id,
            url = url,
            title = title.ifBlank { fileName },
            fileName = fileName,
            kind = kind,
            headers = headers,
            pageUrl = pageUrl,
        )
        store.add(item)
        // 받기 시작한 시점에 기록해 둔다. 중간에 실패해도 "시도한 적 있다"는 남아야 한다.
        history.record(item)
        startJob(id)
        return id
    }

    fun resume(id: String) = startJob(id)

    fun pause(id: String) {
        store.update(id) { it.copy(status = DownloadStatus.PAUSED) }
        jobs[id]?.cancel()
    }

    fun cancel(id: String) {
        store.update(id) { it.copy(status = DownloadStatus.CANCELED) }
        history.markFinished(id, DownloadStatus.CANCELED, sizeBytes = -1)
        val job = jobs[id]
        if (job != null && job.isActive) {
            job.invokeOnCompletion { partFile(id).delete() }
            job.cancel()
        } else {
            partFile(id).delete()
        }
    }

    fun retry(id: String) {
        partFile(id).delete()
        store.update(id) {
            it.copy(
                status = DownloadStatus.QUEUED,
                bytesDownloaded = 0,
                totalBytes = -1,
                segmentIndex = 0,
                errorMessage = null,
            )
        }
        startJob(id)
    }

    /** 목록에서 지운다. [deleteFile]이면 저장된 영상 파일까지 지운다. */
    fun remove(id: String, deleteFile: Boolean) {
        val item = store.get(id)
        jobs[id]?.cancel()
        partFile(id).delete()
        if (deleteFile && item?.outputUri != null) {
            runCatching {
                appContext.contentResolver.delete(Uri.parse(item.outputUri), null, null)
            }.onFailure { Log.w(TAG, "저장된 파일 삭제 실패", it) }
        }
        store.remove(id)
    }

    /**
     * 보관함의 항목을 같은 주소로 다시 받는다.
     *
     * 스트림 주소에는 보통 유효 기간이 있어서 시간이 지났으면 실패한다.
     * 그때는 '원본 페이지 열기'로 다시 찾아야 한다.
     */
    fun redownload(item: DownloadItem): String = enqueue(
        url = item.url,
        title = item.title,
        kind = item.kind,
        headers = item.headers,
        pageUrl = item.pageUrl,
    )

    /** 여러 항목을 한 번에 지운다. 보관함의 선택 삭제가 쓴다. */
    fun removeAll(ids: Collection<String>, deleteFile: Boolean) {
        if (ids.isEmpty()) return
        val idSet = ids.toSet()

        store.items.value.filter { it.id in idSet }.forEach { item ->
            jobs[item.id]?.cancel()
            partFile(item.id).delete()
            if (deleteFile && item.outputUri != null) {
                runCatching {
                    appContext.contentResolver.delete(Uri.parse(item.outputUri), null, null)
                }.onFailure { Log.w(TAG, "저장된 파일 삭제 실패: ${item.fileName}", it) }
            }
        }

        store.removeWhere { it.id in idSet }
    }

    fun pauseAll() {
        store.items.value.filter { it.status.isActive }.forEach { pause(it.id) }
    }

    /** 멈춰 둔 것을 한꺼번에 다시 시작한다. 동시 실행 수는 [MAX_CONCURRENT]가 조절한다. */
    fun resumeAll() {
        store.items.value
            .filter { it.status == DownloadStatus.PAUSED }
            .forEach { resume(it.id) }
    }

    fun retryAllFailed() {
        store.items.value
            .filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELED }
            .forEach { retry(it.id) }
    }

    /** 실패·취소로 끝난 항목을 목록에서 치운다. 저장된 영상 파일은 건드리지 않는다. */
    fun clearFailed() {
        val isFailure = { item: DownloadItem ->
            item.status == DownloadStatus.FAILED || item.status == DownloadStatus.CANCELED
        }
        store.items.value.filter(isFailure).forEach { partFile(it.id).delete() }
        store.removeWhere(isFailure)
    }

    private fun startJob(id: String) {
        if (jobs[id]?.isActive == true) return
        val item = store.get(id) ?: return
        if (item.status == DownloadStatus.COMPLETED) return

        store.update(id) { it.copy(status = DownloadStatus.QUEUED, errorMessage = null) }
        ensureServiceRunning()

        val job = scope.launch {
            limiter.withPermit { runItem(id) }
        }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id, job) }
    }

    private suspend fun runItem(id: String) {
        val item = store.get(id) ?: return
        if (item.status == DownloadStatus.CANCELED) return

        store.update(id) { it.copy(status = DownloadStatus.RUNNING) }
        DebugLog.log("받기", "시작 · ${item.kind} · ${item.title.take(40)}")
        DebugLog.log("받기", "주소 ${item.url.take(140)}")
        val part = partFile(id)

        try {
            when (item.kind) {
                MediaKind.MP4 -> {
                    FileDownloader.download(item.url, item.headers, part) { downloaded, total ->
                        store.update(id, immediate = false) {
                            it.copy(bytesDownloaded = downloaded, totalBytes = total)
                        }
                    }
                    publish(id, part, Names.extensionOf(item.fileName).ifEmpty { "mp4" })
                }

                MediaKind.HLS -> {
                    val outcome = HlsDownloader.download(
                        playlistUrl = item.url,
                        headers = item.headers,
                        target = part,
                        startSegment = item.segmentIndex,
                        startBytes = item.bytesDownloaded,
                    ) { index, count, bytes ->
                        store.update(id, immediate = false) {
                            it.copy(segmentIndex = index, segmentCount = count, bytesDownloaded = bytes)
                        }
                    }
                    publishHls(id, part, outcome)
                }
            }
        } catch (ce: CancellationException) {
            // 일시정지/취소로 끊긴 경우다. 상태는 요청한 쪽이 이미 바꿔 두었다.
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "다운로드 실패: ${item.url}", t)
            val reason = t.message ?: t::class.java.simpleName
            DebugLog.log("받기", "실패 · ${t::class.java.simpleName} · $reason")
            store.update(id) { it.copy(status = DownloadStatus.FAILED, errorMessage = reason) }
            history.markFinished(id, DownloadStatus.FAILED, sizeBytes = -1)
            store.get(id)?.let { Notifications.showFailed(appContext, it, reason) }
        }
    }

    /**
     * HLS 결과를 재생 가능한 컨테이너로 정리한 뒤 저장한다.
     *
     * 조각난 MP4(fMP4)도 반드시 거쳐야 한다. 조각을 이어 붙인 것만으로는 색인이
     * 없어서, 확장자가 mp4여도 갤러리와 기본 재생기가 열지 못한다.
     */
    private fun publishHls(id: String, part: File, outcome: HlsDownloader.Outcome) {
        val converted = File(part.parentFile, "$id.mp4")
        if (Mp4Remuxer.remux(part, converted)) {
            part.delete()
            publish(id, converted, "mp4", remuxed = true)
            return
        }

        // 변환이 안 되면 받은 그대로 둔다. 전용 재생기로는 열 수 있다.
        publish(
            id = id,
            source = part,
            extension = if (outcome.isFragmentedMp4) "mp4" else "ts",
            remuxed = false,
            note = Mp4Remuxer.lastFailure?.let { "MP4 변환 실패 · $it" },
        )
    }

    private fun publish(
        id: String,
        source: File,
        extension: String,
        remuxed: Boolean? = null,
        note: String? = null,
    ) {
        val item = store.get(id) ?: return
        validateOrThrow(source)

        val fileName = Names.withExtension(item.fileName, extension)
        val saved = MediaStoreSaver.save(
            context = appContext,
            source = source,
            displayName = fileName,
            mimeType = Names.mimeForExtension(extension),
        )
        val size = item.bytesDownloaded

        store.update(id) {
            it.copy(
                status = DownloadStatus.COMPLETED,
                fileName = fileName,
                outputUri = saved.uri.toString(),
                outputDisplayPath = saved.displayPath,
                remuxed = remuxed,
                totalBytes = if (it.totalBytes > 0) it.totalBytes else size,
                completedAt = System.currentTimeMillis(),
                // 완료됐지만 변환에 실패했다면 그 사정을 남겨 둔다.
                errorMessage = note,
            )
        }
        DebugLog.log(
            "저장",
            "$fileName · ${saved.displayPath} · 변환 ${
                when (remuxed) {
                    true -> "성공"
                    false -> "실패"
                    null -> "해당없음"
                }
            }",
        )

        val finished = store.get(id)
        history.markFinished(
            downloadId = id,
            status = DownloadStatus.COMPLETED,
            sizeBytes = finished?.totalBytes ?: size,
            fileName = fileName,
        )
        finished?.let { Notifications.showCompleted(appContext, it) }
    }

    /**
     * 저장하기 전에 받은 것이 정말 영상인지 확인한다.
     *
     * 주소가 만료됐거나 로그인이 필요하면 서버가 오류 페이지(HTML)를 200으로 돌려준다.
     * 그대로 저장하면 보관함에 0바이트거나 재생되지 않는 파일이 쌓인다.
     * 쓸 수 없는 파일은 지워서, 다시 시도할 때 처음부터 받게 한다.
     */
    private fun validateOrThrow(file: File) {
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            throw IOException("받은 파일이 비어 있습니다. 주소가 만료됐거나 로그인이 필요한 영상일 수 있습니다")
        }
        if (file.length() < MIN_VALID_BYTES) {
            val size = file.length()
            file.delete()
            throw IOException("받은 내용이 영상이 아닙니다 (${size}바이트)")
        }
        when (inspect(file)) {
            Content.UNREADABLE -> {
                file.delete()
                throw IOException("받은 파일을 재생할 수 없습니다. 영상 주소가 아닐 수 있습니다")
            }

            // 주소 모양으로 거르지 못한 오디오 전용 트랙은 여기서 걸린다.
            // 소리만 든 파일을 영상이라고 보관함에 넣으면 안 된다.
            Content.AUDIO_ONLY -> {
                file.delete()
                throw IOException(HlsDownloader.AUDIO_ONLY_MESSAGE)
            }

            Content.VIDEO -> DebugLog.log("검증", "통과 · 영상 트랙 있음 · ${file.length()}바이트")
        }
    }

    private enum class Content { VIDEO, AUDIO_ONLY, UNREADABLE }

    private fun inspect(file: File): Content {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            when {
                hasVideo -> Content.VIDEO
                duration > 0L -> Content.AUDIO_ONLY
                else -> Content.UNREADABLE
            }
        } catch (t: Throwable) {
            Log.w(TAG, "받은 파일을 읽지 못했다: ${file.name}", t)
            Content.UNREADABLE
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun ensureServiceRunning() {
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, DownloadService::class.java),
            )
        }.onFailure { Log.w(TAG, "다운로드 서비스를 시작하지 못했다", it) }
    }

    companion object {
        private const val TAG = "DownloadManager"

        /** 동시에 받는 개수. 모바일 회선에서 이보다 늘리면 오히려 느려진다. */
        const val MAX_CONCURRENT = 3

        /** 이보다 작으면 영상이 아니라 오류 페이지로 본다. */
        const val MIN_VALID_BYTES = 32L * 1024
    }
}
