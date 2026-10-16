package com.racewall.android.live

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.racewall.android.MainActivity
import com.racewall.android.R

/** Notification channels: the quiet one while following live, and the one for alerts. */
object Notify {
    const val LIVE = "live"
    const val ALERTS = "alerts"
    private const val LIVE_ID = 1

    fun channels(context: Context) {
        val m = context.getSystemService(NotificationManager::class.java) ?: return
        m.createNotificationChannel(NotificationChannel(LIVE, "Following live timing", NotificationManager.IMPORTANCE_LOW))
        m.createNotificationChannel(NotificationChannel(ALERTS, "Sessions and flags", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun open(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    fun liveNotification(context: Context, text: String) = NotificationCompat.Builder(context, LIVE)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle("RaceWall is following live timing")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(open(context))
        .build()

    /** A session starting soon, a red flag or the safety car, as the desktop's notifications. */
    fun alert(context: Context, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(context, ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(open(context))
            .build()
        NotificationManagerCompat.from(context).notify((title + body).hashCode(), n)
    }

    const val ID = LIVE_ID
}

/**
 * Keeps RaceWall running while it follows a live session, with an ongoing notification, so live
 * timing carries on with the screen off or another app in front. Started and stopped by the app.
 */
class LiveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: ""
        ServiceCompat.startForeground(
            this,
            Notify.ID,
            Notify.liveNotification(this, text),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        return START_NOT_STICKY
    }

    companion object {
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, LiveService::class.java).putExtra(EXTRA_TEXT, text)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveService::class.java))
        }
    }
}
