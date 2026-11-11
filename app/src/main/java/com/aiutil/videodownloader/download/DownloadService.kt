package com.aiutil.videodownloader.download

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.aiutil.videodownloader.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 다운로드가 도는 동안 프로세스를 포그라운드로 붙잡아 두는 역할만 한다.
 * 실제 전송은 [DownloadManagerCore]가 앱 스코프에서 수행한다.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startedAt = 0L
    private var lastNotifiedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startedAt = System.currentTimeMillis()
        goForeground()
        observeDownloads()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 이후 제한 시간 안에 반드시 다시 알려야 한다.
        goForeground()
        when (intent?.action) {
            ACTION_PAUSE_ALL -> App.downloads.pauseAll()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun observeDownloads() = scope.launch {
        App.store.items.collectLatest { items ->
            val active = items.filter { it.status.isActive }

            if (active.isEmpty()) {
                // 막 올라온 직후에는 아직 큐가 비어 보일 수 있다. 잠깐 여유를 둔다.
                if (System.currentTimeMillis() - startedAt > STARTUP_GRACE_MS) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@collectLatest
            }

            val now = System.currentTimeMillis()
            if (now - lastNotifiedAt < NOTIFY_INTERVAL_MS) return@collectLatest
            lastNotifiedAt = now
            goForeground()
        }
    }

    private fun goForeground() {
        val items = App.store.items.value.filter { it.status.isActive }
        val notification = Notifications.buildProgressNotification(this, items)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        runCatching {
            ServiceCompat.startForeground(this, Notifications.PROGRESS_NOTIFICATION_ID, notification, type)
        }
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.aiutil.videodownloader.PAUSE_ALL"
        private const val STARTUP_GRACE_MS = 3_000L
        private const val NOTIFY_INTERVAL_MS = 900L
    }
}
