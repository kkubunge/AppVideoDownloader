package com.aiutil.videodownloader.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aiutil.videodownloader.App
import com.aiutil.videodownloader.data.TabStateStore
import com.aiutil.videodownloader.model.DetectedVideo
import java.util.UUID

/**
 * 열려 있는 탭들을 관리한다.
 *
 * WebView는 Activity Context를 쥐고 있으므로 ViewModel이 아니라 Activity 수명에 맞춰
 * 보관한다. Activity가 사라질 때 [destroyAll]로 정리해야 한다.
 */
class TabManager(private val onDownloadRequested: (DetectedVideo) -> Unit) {

    private val _tabs = mutableStateListOf<BrowserTab>()
    val tabs: List<BrowserTab> get() = _tabs

    var activeTabId by mutableStateOf<String?>(null)
        private set

    val activeTab: BrowserTab?
        get() = _tabs.firstOrNull { it.id == activeTabId }

    val count: Int get() = _tabs.size

    /**
     * 지난번에 열어 두었던 탭을 되살린다. 없으면 빈 탭을 하나 만든다.
     *
     * 주소만 복원하고 실제로 불러오지는 않는다. 그 탭을 볼 때 비로소 불러온다.
     */
    fun restore(context: Context) {
        if (_tabs.isNotEmpty()) return

        val saved = App.tabs.load()
        saved.tabs.take(MAX_TABS).forEach { snapshot ->
            _tabs.add(
                BrowserTab(
                    id = snapshot.id,
                    context = context,
                    restoredUrl = snapshot.url,
                    restoredTitle = snapshot.title,
                    onDownloadRequested = onDownloadRequested,
                    onStateChanged = ::persist,
                )
            )
        }

        if (_tabs.isEmpty()) {
            openTab(context)
            return
        }
        activeTabId = saved.activeTabId?.takeIf { id -> _tabs.any { it.id == id } } ?: _tabs.first().id
    }

    private fun persist() {
        App.tabs.save(
            TabStateStore.TabsState(
                tabs = _tabs.map { TabStateStore.TabSnapshot(it.id, it.url, it.title) },
                activeTabId = activeTabId,
            )
        )
    }

    /**
     * 새 탭을 연다. 상한([MAX_TABS])에 도달하면 null을 돌려준다 —
     * WebView는 하나하나가 무거워서 무제한으로 열면 메모리가 버티지 못한다.
     */
    fun openTab(context: Context, url: String? = null): BrowserTab? {
        if (_tabs.size >= MAX_TABS) return null

        val tab = BrowserTab(
            id = UUID.randomUUID().toString(),
            context = context,
            onDownloadRequested = onDownloadRequested,
            onStateChanged = ::persist,
        )
        _tabs.add(tab)
        select(tab.id)
        if (!url.isNullOrBlank()) tab.loadUrl(url)
        persist()
        return tab
    }

    fun select(id: String) {
        if (_tabs.none { it.id == id }) return
        // 이전 탭의 WebView를 화면에서 떼어내야 새 탭을 붙일 수 있다.
        activeTab?.detachFromParent()
        activeTabId = id
        persist()
    }

    /** 탭을 닫는다. 마지막 하나를 닫으면 빈 탭을 새로 만들어 둔다. */
    fun closeTab(context: Context, id: String) {
        val index = _tabs.indexOfFirst { it.id == id }
        if (index < 0) return

        val closing = _tabs.removeAt(index)
        val wasActive = closing.id == activeTabId
        closing.destroy()

        if (_tabs.isEmpty()) {
            activeTabId = null
            openTab(context)
            return
        }
        if (wasActive) {
            val next = _tabs.getOrNull(index) ?: _tabs.last()
            activeTabId = next.id
        }
        persist()
    }

    fun closeAll(context: Context) {
        _tabs.forEach { it.destroy() }
        _tabs.clear()
        activeTabId = null
        openTab(context)
    }

    /**
     * 화면이 사라질 때 WebView만 정리한다.
     *
     * 저장된 탭 목록은 건드리지 않는다. 여기서 비우면 다음에 켤 때 되살릴 것이 없어진다.
     */
    fun destroyAll() {
        _tabs.forEach { it.destroy() }
        _tabs.clear()
        activeTabId = null
    }

    companion object {
        const val MAX_TABS = 8
    }
}
