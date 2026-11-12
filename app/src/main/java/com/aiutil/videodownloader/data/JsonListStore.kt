package com.aiutil.videodownloader.data

import android.content.Context
import android.util.Log
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 목록 하나를 JSON 파일로 보관하는 저장소의 공통 뼈대.
 *
 * 쓰기 빈도가 높은 저장소(진행률 갱신 등)를 위해 [persist]에 즉시/지연 두 가지 모드를 둔다.
 * 지연 쓰기는 [FLUSH_INTERVAL_MS] 주기로 모아 처리한다.
 */
abstract class JsonListStore<T>(
    context: Context,
    fileName: String,
    private val serializer: KSerializer<List<T>>,
    private val tag: String,
) {

    private val file = File(context.filesDir, fileName)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var dirty = false

    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items.asStateFlow()

    /** 현재 목록. 하위 클래스는 이 값을 갈아끼우는 방식으로 변경한다. */
    protected var snapshot: List<T>
        get() = _items.value
        set(value) {
            _items.value = value
        }

    init {
        _items.value = readFromDisk()
        scope.launch {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                if (dirty) flush()
            }
        }
    }

    protected fun persist(immediate: Boolean = true) {
        if (immediate) scope.launch { flush() } else dirty = true
    }

    private fun readFromDisk(): List<T> = try {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
    } catch (t: Throwable) {
        Log.w(tag, "목록을 읽지 못해 비우고 시작한다", t)
        emptyList()
    }

    private suspend fun flush() = mutex.withLock {
        dirty = false
        try {
            // 쓰는 도중 프로세스가 죽어도 기존 파일이 깨지지 않도록 임시 파일에 쓴 뒤 교체한다.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, snapshot))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(tag, "목록 저장 실패", t)
        }
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 2_000L
    }
}
