package com.smartoffline.reddit.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.smartoffline.reddit.MainActivity
import com.smartoffline.reddit.R

class Notifier(private val context: Context) {
    companion object {
        const val CH_SYNC = "sync"
        const val CH_ALERTS = "alerts"
        const val ID_SYNC = 1001
        const val ID_STORAGE = 1002
        const val ID_AUTH = 1003
        const val ID_FOLDER = 1004
        const val ID_SUMMARY = 1005
    }

    private val nm = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        nm.createNotificationChannel(NotificationChannel(CH_SYNC, "Synchronization progress", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while posts and media are being downloaded"
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Download alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Storage full, sign-in expired, folder unavailable and sync summaries"
        })
    }

    private fun openApp(screen: String): PendingIntent = PendingIntent.getActivity(
        context, screen.hashCode(),
        Intent(context, MainActivity::class.java).putExtra("open", screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun syncNotification(title: String, text: String, progress: Int, max: Int, indeterminate: Boolean): Notification =
        Notification.Builder(context, CH_SYNC)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, indeterminate)
            .setContentIntent(openApp("sync"))
            .build()

    fun alert(id: Int, title: String, text: String, screen: String) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        nm.notify(id, Notification.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(screen))
            .build())
    }

    fun update(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        nm.notify(id, n)
    }

    fun cancel(id: Int) = nm.cancel(id)
}
