package com.aiutil.videodownloader.data

import android.content.Context
import android.util.Log
import com.aiutil.videodownloader.model.DownloadItem
import com.aiutil.videodownloader.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 다운로드 목록을 JSON 한 파일로 보관한다.
 *
 * 진행률 갱신은 초당 여러 번 들어오므로 디스크에는 즉시 쓰지 않고, 상태가 바뀔 때만 즉시
 * 플러시하고 나머지는 [FLUSH_INTERVAL_MS] 주기로 모아 쓴다.
 */
class DownloadStore(context: Context) {

    private val file = File(context.filesDir, "downloads.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 직렬화기를 명시한다. reified 확장(`encodeToString(value)`)은 import 여부에 따라
     * serializer를 요구하는 멤버 함수로 해석돼 컴파일이 깨진다.
     */
    private val itemsSerializer = ListSerializer(DownloadItem.serializer())
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var dirty = false

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    init {
        _items.value = readFromDisk()
        scope.launch {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                if (dirty) flush()
            }
        }
    }

    fun get(id: String): DownloadItem? = _items.value.firstOrNull { it.id == id }

    fun add(item: DownloadItem) {
        _items.value = listOf(item) + _items.value
        scope.launch { flush() }
    }

    /** 이미 같은 URL로 끝나지 않은 작업이 있으면 그 id를 돌려준다. 중복 큐잉 방지. */
    fun findActiveByUrl(url: String): DownloadItem? =
        _items.value.firstOrNull { it.url == url && !it.status.isTerminal }

    /**
     * 항목을 수정한다. [immediate]가 true면 바로 디스크에 쓴다(상태 전이 등).
     * 진행률만 바뀌는 호출은 false로 두어 I/O를 아낀다.
     */
    fun update(id: String, immediate: Boolean = true, transform: (DownloadItem) -> DownloadItem) {
        var changed = false
        _items.value = _items.value.map {
            if (it.id == id) {
                changed = true
                transform(it)
            } else it
        }
        if (!changed) return
        if (immediate) scope.launch { flush() } else dirty = true
    }

    fun remove(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
        scope.launch { flush() }
    }

    fun removeAll(predicate: (DownloadItem) -> Boolean) {
        _items.value = _items.value.filterNot(predicate)
        scope.launch { flush() }
    }

    /**
     * 프로세스가 비정상 종료되면 RUNNING 상태가 그대로 남는다. 시작 시 한 번 PAUSED로
     * 내려 두어야 UI에 "진행 중인데 아무 일도 안 일어나는" 항목이 남지 않는다.
     */
    fun reconcileOnStartup() {
        _items.value = _items.value.map {
            if (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED) {
                it.copy(status = DownloadStatus.PAUSED)
            } else it
        }
        scope.launch { flush() }
    }

    private fun readFromDisk(): List<DownloadItem> = try {
        if (file.exists()) json.decodeFromString(itemsSerializer, file.readText()) else emptyList()
    } catch (t: Throwable) {
        Log.w(TAG, "다운로드 목록을 읽지 못해 비우고 시작한다", t)
        emptyList()
    }

    private suspend fun flush() = mutex.withLock {
        dirty = false
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(itemsSerializer, _items.value))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "다운로드 목록 저장 실패", t)
        }
    }

    private companion object {
        const val TAG = "DownloadStore"
        const val FLUSH_INTERVAL_MS = 2_000L
    }
}
