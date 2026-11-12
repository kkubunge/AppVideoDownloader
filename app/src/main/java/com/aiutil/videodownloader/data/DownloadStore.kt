package com.aiutil.videodownloader.data

import android.content.Context
import com.aiutil.videodownloader.model.DownloadItem
import com.aiutil.videodownloader.model.DownloadStatus
import kotlinx.serialization.builtins.ListSerializer

/**
 * 진행 중·완료된 다운로드 목록.
 *
 * 진행률 갱신은 초당 여러 번 들어오므로 디스크에는 즉시 쓰지 않고, 상태가 바뀔 때만 즉시
 * 플러시하고 나머지는 모아 쓴다.
 */
class DownloadStore(context: Context) : JsonListStore<DownloadItem>(
    context = context,
    fileName = "downloads.json",
    serializer = ListSerializer(DownloadItem.serializer()),
    tag = "DownloadStore",
) {

    fun get(id: String): DownloadItem? = items.value.firstOrNull { it.id == id }

    fun add(item: DownloadItem) {
        snapshot = listOf(item) + snapshot
        persist()
    }

    /** 이미 같은 URL로 끝나지 않은 작업이 있으면 그 항목을 돌려준다. 중복 큐잉 방지. */
    fun findActiveByUrl(url: String): DownloadItem? =
        items.value.firstOrNull { it.url == url && !it.status.isTerminal }

    /**
     * 항목을 수정한다. [immediate]가 true면 바로 디스크에 쓴다(상태 전이 등).
     * 진행률만 바뀌는 호출은 false로 두어 I/O를 아낀다.
     */
    fun update(id: String, immediate: Boolean = true, transform: (DownloadItem) -> DownloadItem) {
        var changed = false
        snapshot = snapshot.map {
            if (it.id == id) {
                changed = true
                transform(it)
            } else it
        }
        if (changed) persist(immediate)
    }

    fun remove(id: String) {
        snapshot = snapshot.filterNot { it.id == id }
        persist()
    }

    /**
     * 프로세스가 비정상 종료되면 RUNNING 상태가 그대로 남는다. 시작 시 한 번 PAUSED로
     * 내려 두어야 UI에 "진행 중인데 아무 일도 안 일어나는" 항목이 남지 않는다.
     */
    fun reconcileOnStartup() {
        snapshot = snapshot.map {
            if (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED) {
                it.copy(status = DownloadStatus.PAUSED)
            } else it
        }
        persist()
    }
}
