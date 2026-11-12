package com.aiutil.videodownloader

import android.app.Application
import com.aiutil.videodownloader.data.BookmarkStore
import com.aiutil.videodownloader.data.DownloadHistoryStore
import com.aiutil.videodownloader.data.DownloadStore
import com.aiutil.videodownloader.data.TabStateStore
import com.aiutil.videodownloader.download.DownloadManagerCore
import com.aiutil.videodownloader.download.Notifications

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = DownloadStore(this)
        history = DownloadHistoryStore(this)
        bookmarks = BookmarkStore(this)
        tabs = TabStateStore(this)
        downloads = DownloadManagerCore(this, store, history)
        Notifications.createChannels(this)

        // 지난 실행이 비정상 종료됐다면 "진행 중"으로 멈춰 있는 항목이 남는다.
        store.reconcileOnStartup()
    }

    companion object {
        lateinit var instance: App
            private set

        lateinit var store: DownloadStore
            private set

        lateinit var history: DownloadHistoryStore
            private set

        lateinit var bookmarks: BookmarkStore
            private set

        lateinit var tabs: TabStateStore
            private set

        lateinit var downloads: DownloadManagerCore
            private set
    }
}
