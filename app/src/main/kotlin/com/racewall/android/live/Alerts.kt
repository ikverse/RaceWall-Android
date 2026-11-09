package com.racewall.android.live

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.racewall.android.state.JsonStore
import com.racewall.android.state.Settings
import com.racewall.archive.OkHttpFetcher
import com.racewall.bus.FeedJson
import com.racewall.jolpica.Jolpica
import com.racewall.live.LiveScheduler
import com.racewall.topics.Schedule
import com.racewall.topics.ScheduledSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * The session notifications, as the desktop's scheduleTick: "starts soon" 15 minutes before and
 * "is live" at the scheduled start, with a Watch live button. They arrive with RaceWall closed too:
 * the schedule is kept in schedule.json and an alarm wakes [AlarmReceiver] at the next of those
 * moments. The open app runs the same check every 30 seconds; a shared announced.json keeps each
 * notification to once.
 *
 * The alarm is exact only where Android already allows it (`canScheduleExactAlarms`, granted by
 * default before Android 13). Otherwise it is inexact (`setAndAllowWhileIdle`): it never fires
 * early, and may come a little late in deep sleep. An exact alarm on Android 13 and later needs a
 * permission the user must grant on a system screen; a notification a minute late is better than
 * sending them there.
 */
object Alerts {
    @Serializable
    private data class Entry(val meeting: String, val location: String, val country: String, val round: Int, val name: String, val start: Long?)

    @Serializable
    private data class Cached(val year: Int, val sessions: List<Entry>)

    private val lock = Any()
    private fun year(now: Long) = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).year

    private fun scheduleStore(context: Context) = JsonStore(File(context.filesDir, "schedule.json"))
    private fun announcedStore(context: Context) = JsonStore(File(context.filesDir, "announced.json"))

    /** The schedule saved last time, for this season; empty when there is none. */
    fun schedule(context: Context, now: Long = System.currentTimeMillis()): Schedule {
        val saved = scheduleStore(context).read<Cached?>(null) ?: return emptyList()
        if (saved.year != year(now)) return emptyList()
        return saved.sessions.map { ScheduledSession(it.meeting, it.location, it.country, it.round, it.name, it.start) }
    }

    /** Keeps a schedule for the next start, and for the alarm. */
    fun saveSchedule(context: Context, schedule: Schedule, now: Long = System.currentTimeMillis()) {
        if (schedule.isEmpty()) return
        synchronized(lock) {
            scheduleStore(context).write(Cached(year(now), schedule.map { Entry(it.meeting, it.location, it.country, it.round, it.name, it.start) }))
        }
    }

    /** Fetches this season's schedule from Jolpica and saves it. Null when it could not be fetched. */
    suspend fun refresh(context: Context): Schedule? = withContext(Dispatchers.IO) {
        try {
            val res = OkHttpFetcher().get("${Jolpica.BASE}${year(System.currentTimeMillis())}.json", emptyMap())
            if (!res.ok) return@withContext null
            Jolpica.parseSchedule(FeedJson.parse(res.body ?: "null")).also {
                saveSchedule(context, it)
                // A new schedule moves the home-screen widgets' own redraw moments too.
                com.racewall.android.home.HomeWidgets.arm(context)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Sends the notifications that are due, sets the alarm for the next one, and returns the
     * session that is on now. `isLive`: the app already follows live timing, so "is live" is not sent.
     */
    fun check(context: Context, settings: Settings? = null, isLive: Boolean = false, now: Long = System.currentTimeMillis()): ScheduledSession? = synchronized(lock) {
        val s = settings ?: JsonStore(File(context.filesDir, "settings.json")).read(Settings())
        val schedule = schedule(context, now)
        val store = announcedStore(context)
        val announced = store.read<List<String>>(emptyList())
            .filter { (it.substringAfterLast('|').toLongOrNull() ?: 0) > now - 2 * 86_400_000L }
            .toMutableSet()
        val before = announced.size
        for (soon in LiveScheduler.startingSoon(schedule, now, announced)) {
            announced += LiveScheduler.soonKey(soon)
            val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(soon.start!!))
            if (s.notifySoon) Notify.alert(context, "${soon.name} starts soon", "${soon.meeting}, $at")
        }
        LiveScheduler.goneLive(schedule, now, announced)?.let { gone ->
            announced += LiveScheduler.liveKey(gone)
            if (s.notifyLive && !isLive) Notify.live(context, "${gone.name} is live", gone.meeting)
        }
        if (announced.size != before) store.write(announced.toList())
        arm(context, schedule, now)
        LiveScheduler.sessionNow(schedule, now)
    }

    /** Sets the alarm for the next "starts soon" or "is live" moment, replacing the last one. */
    fun arm(context: Context, schedule: Schedule = schedule(context), now: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(context, 0, Intent(context, AlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val at = LiveScheduler.nextAlarm(schedule, now) ?: return alarms.cancel(pending)
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }
}

/** Wakes at a session's "starts soon" or start, with RaceWall closed or open. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Alerts.check(context.applicationContext)
        // A session starting is also when the home-screen widgets change.
        com.racewall.android.home.HomeWidgets.redraw(context.applicationContext)
    }
}

/** Alarms do not survive a restart, an update or a clock change: set the next one again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in ACTIONS) return
        Alerts.arm(context.applicationContext)
        com.racewall.android.home.HomeWidgets.arm(context.applicationContext)
        com.racewall.android.home.HomeWidgets.redraw(context.applicationContext)
    }

    private companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
    }
}
