package com.aiutil.videodownloader.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aiutil.videodownloader.R
import com.aiutil.videodownloader.model.DownloadItem
import com.aiutil.videodownloader.model.DownloadStatus
import com.aiutil.videodownloader.ui.MainActivity

object Notifications {

    const val CHANNEL_PROGRESS = "download_progress"
    const val CHANNEL_DONE = "download_done"
    const val PROGRESS_NOTIFICATION_ID = 1001

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.notif_channel_progress_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_progress_desc)
                setShowBadge(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DONE,
                context.getString(R.string.notif_channel_done_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notif_channel_done_desc)
            }
        )
    }

    /** 진행 중인 모든 다운로드를 한 줄로 요약하는 포그라운드 알림. */
    fun buildProgressNotification(context: Context, active: List<DownloadItem>): Notification {
        val running = active.filter { it.status == DownloadStatus.RUNNING }
        val title = when {
            active.isEmpty() -> "다운로드 정리 중"
            active.size == 1 -> active.first().title.take(40)
            else -> "${active.size}개 다운로드 중"
        }

        val determinate = running.isNotEmpty() && running.all { it.progress >= 0f }
        val percent = if (determinate) {
            (running.sumOf { it.progress.toDouble() } / running.size * 100).toInt()
        } else 0

        val text = when {
            running.isEmpty() -> "대기 중"
            determinate -> "$percent%"
            else -> "받는 중…"
        }

        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent, !determinate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun showCompleted(context: Context, item: DownloadItem) {
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("다운로드 완료")
            .setContentText(item.title.take(60))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        notify(context, item.id.hashCode(), notification)
    }

    fun showFailed(context: Context, item: DownloadItem, reason: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("다운로드 실패")
            .setContentText("${item.title.take(30)} · $reason")
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        notify(context, item.id.hashCode(), notification)
    }

    /** POST_NOTIFICATIONS 권한이 없으면 조용히 넘어간다. 다운로드 자체는 계속되어야 한다. */
    private fun notify(context: Context, id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
