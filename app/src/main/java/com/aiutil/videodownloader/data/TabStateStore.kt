package com.aiutil.videodownloader.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 앱을 껐다 켜도 열어 두었던 탭이 남아 있도록 저장한다. */
class TabStateStore(context: Context) {

    @Serializable
    data class TabSnapshot(val id: String, val url: String, val title: String)

    @Serializable
    data class TabsState(
        val tabs: List<TabSnapshot> = emptyList(),
        val activeTabId: String? = null,
    )

    private val file = File(context.filesDir, "tabs.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun load(): TabsState = try {
        if (file.exists()) json.decodeFromString(TabsState.serializer(), file.readText())
        else TabsState()
    } catch (t: Throwable) {
        Log.w(TAG, "탭 상태를 읽지 못해 빈 상태로 시작한다", t)
        TabsState()
    }

    fun save(state: TabsState) {
        scope.launch {
            mutex.withLock {
                runCatching { file.writeText(json.encodeToString(TabsState.serializer(), state)) }
                    .onFailure { Log.w(TAG, "탭 상태 저장 실패", it) }
            }
        }
    }

    private companion object {
        const val TAG = "TabStateStore"
    }
}
