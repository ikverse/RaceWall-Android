package com.racewall.android.home

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.racewall.android.MainActivity
import com.racewall.android.R
import com.racewall.android.live.Alerts
import com.racewall.android.live.Notify
import com.racewall.topics.ScheduledSession
import java.util.concurrent.TimeUnit

/** The four widgets a launcher offers. Each draws its picture with [HomeWidgets]. */
abstract class HomeWidgetReceiver(private val kind: HomeKind) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        HomeWidgets.draw(context, manager, kind, ids)
        HomeWidgets.schedule(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        HomeWidgets.draw(context, manager, kind, intArrayOf(id))
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        HomeSettingsStore.update(context) { s -> s.copy(widgets = s.widgets - ids.map(Int::toString).toSet()) }
    }

    override fun onEnabled(context: Context) {
        HomeWidgets.schedule(context)
        HomeWidgets.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        if (!HomeWidgets.any(context)) HomeWidgets.unschedule(context)
    }
}

class WeekendWidgetReceiver : HomeWidgetReceiver(HomeKind.WEEKEND)
class ScheduleWidgetReceiver : HomeWidgetReceiver(HomeKind.SCHEDULE)
class StandingsWidgetReceiver : HomeWidgetReceiver(HomeKind.STANDINGS)
class LiveWidgetReceiver : HomeWidgetReceiver(HomeKind.LIVE)

object HomeWidgets {
    private val RECEIVERS = mapOf(
        HomeKind.WEEKEND to WeekendWidgetReceiver::class.java,
        HomeKind.SCHEDULE to ScheduleWidgetReceiver::class.java,
        HomeKind.STANDINGS to StandingsWidgetReceiver::class.java,
        HomeKind.LIVE to LiveWidgetReceiver::class.java,
    )

    fun ids(context: Context, kind: HomeKind): IntArray =
        runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, RECEIVERS.getValue(kind))) }.getOrNull() ?: IntArray(0)

    fun any(context: Context) = HomeKind.entries.any { ids(context, it).isNotEmpty() }

    /** Draws every widget of these kinds again from what is saved. */
    fun redraw(context: Context, kinds: Collection<HomeKind> = HomeKind.entries) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        for (k in kinds) {
            val ids = ids(context, k)
            if (ids.isNotEmpty()) draw(context, manager, k, ids)
        }
    }

    fun kindOf(context: Context, id: Int): HomeKind? {
        val provider = runCatching { AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider?.className }.getOrNull() ?: return null
        return RECEIVERS.entries.firstOrNull { it.value.name == provider }?.key
    }

    fun draw(context: Context, manager: AppWidgetManager, kind: HomeKind, ids: IntArray) {
        val snapshot = HomeStore.read(context)
        val schedule = Alerts.schedule(context)
        val settings = HomeSettingsStore.read(context)
        val pen = Pen(context)
        val now = System.currentTimeMillis()
        val h24 = settings.h24(android.text.format.DateFormat.is24HourFormat(context))
        for (id in ids) {
            val views = runCatching { views(context, manager, kind, id, pen, snapshot, schedule, settings, now, h24) }.getOrNull() ?: continue
            runCatching { manager.updateAppWidget(id, views) }
        }
    }

    /** The widget's sizes in dp: each size a launcher may show it at (Android 12 and later), or the one for this orientation. */
    private fun sizes(context: Context, manager: AppWidgetManager, id: Int): List<Pair<Float, Float>> {
        val o = manager.getAppWidgetOptions(id)
        if (Build.VERSION.SDK_INT >= 31) {
            val list = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            if (!list.isNullOrEmpty()) return list.map { it.width to it.height }.distinct().take(4)
        }
        val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val minW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val maxW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val minH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        val maxH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        val w = (if (portrait) minW else maxW).takeIf { it > 0 } ?: 250
        val h = (if (portrait) maxH else minH).takeIf { it > 0 } ?: 110
        return listOf(w.toFloat() to h.toFloat())
    }

    private fun radius(context: Context): Float {
        val d = context.resources.displayMetrics.density
        return if (Build.VERSION.SDK_INT >= 31) runCatching { context.resources.getDimension(android.R.dimen.system_app_widget_background_radius) / d }.getOrDefault(16f) else 16f
    }

    private fun views(
        context: Context, manager: AppWidgetManager, kind: HomeKind, id: Int, pen: Pen,
        snapshot: HomeSnapshot, schedule: List<ScheduledSession>, settings: HomeSettings, now: Long, h24: Boolean,
    ): RemoteViews {
        val sizes = sizes(context, manager, id)
        val radius = radius(context)
        fun one(w: Float, h: Float) = face(context, kind, id, pen, FaceEnv(w, h, now, h24, radius), snapshot, schedule, settings)
        if (Build.VERSION.SDK_INT >= 31 && sizes.size > 1) return RemoteViews(sizes.associate { (w, h) -> SizeF(w, h) to one(w, h) })
        val (w, h) = sizes.first()
        return one(w, h)
    }

    /** One widget at one size: its picture, its text for screen readers, its tap, and the ticking countdown. */
    fun face(context: Context, kind: HomeKind, id: Int, pen: Pen, env: FaceEnv, snapshot: HomeSnapshot, schedule: List<ScheduledSession>, settings: HomeSettings): RemoteViews {
        val own = settings.of(id, kind)
        val driver = settings.driverFor(own)
        val density = context.resources.displayMetrics.density
        val rv = RemoteViews(context.packageName, R.layout.home_widget)
        var tickAt: Long? = null
        var watch = false
        val described: String
        val bitmap = when (kind) {
            HomeKind.WEEKEND -> {
                val view = HomeLogic.weekendView(schedule, snapshot.podium, env.now)
                if (view is HomeLogic.WeekendView.Upcoming && HomeLogic.ticking(env.now, view.session.start!!)) tickAt = view.session.start
                watch = view is HomeLogic.WeekendView.Live
                described = describeWeekend(view, env)
                Faces.bitmap(density, env) { Faces.weekend(it, pen, env, view, schedule, driver, tickAt != null) }
            }
            HomeKind.SCHEDULE -> {
                described = HomeLogic.weekend(schedule, env.now).let { s -> if (s.isEmpty()) "Schedule: none yet" else "${s.first().meeting}: " + s.joinToString("; ") { "${it.name} ${HomeLogic.dayTime(it.start!!, env.h24)}" } }
                Faces.bitmap(density, env) { Faces.schedule(it, pen, env, schedule) }
            }
            HomeKind.STANDINGS -> {
                val teams = own.table == HomeWidgetSettings.CONSTRUCTORS
                described = if (teams) "Constructors' standings: " + snapshot.teams.take(5).joinToString("; ") { "${it.position} ${it.name} ${HomeLogic.valueText(it.points, it.points, false)}" }
                else "Drivers' standings: " + snapshot.drivers.take(5).joinToString("; ") { "${it.position} ${it.name} ${HomeLogic.valueText(it.points, it.points, false)}" }
                Faces.bitmap(density, env) { Faces.standings(it, pen, env, snapshot, own, driver, settings.teamFor(own)) }
            }
            HomeKind.LIVE -> {
                val live = snapshot.live
                watch = live == null && HomeLogic.onNow(schedule, env.now) != null
                described = live?.let { l -> "${l.meeting} ${l.session}, ${HomeLogic.status(l).label}: " + l.rows.take(3).joinToString("; ") { "${it.position} ${it.code}" } } ?: "Live timing: not following a session"
                Faces.bitmap(density, env) { Faces.live(it, pen, env, live, schedule, driver) }
            }
        }
        rv.setImageViewBitmap(R.id.face, bitmap)
        rv.setContentDescription(R.id.face, described)
        val at = tickAt
        if (at != null) {
            rv.setViewVisibility(R.id.tick, View.VISIBLE)
            rv.setTextViewTextSize(R.id.tick, TypedValue.COMPLEX_UNIT_DIP, Tick.size(env.w, env.h))
            // The chronometer counts from its base in elapsed time: the session's start in that clock.
            rv.setChronometer(R.id.tick, SystemClock.elapsedRealtime() + (at - env.now), null, true)
            rv.setChronometerCountDown(R.id.tick, true)
        } else {
            rv.setViewVisibility(R.id.tick, View.GONE)
            rv.setChronometer(R.id.tick, SystemClock.elapsedRealtime(), null, false)
        }
        rv.setOnClickPendingIntent(android.R.id.background, open(context, watch || (kind == HomeKind.LIVE && snapshot.live != null)))
        return rv
    }

    private fun describeWeekend(view: HomeLogic.WeekendView, env: FaceEnv): String = when (view) {
        is HomeLogic.WeekendView.Upcoming -> "${view.session.meeting}: ${view.session.name}, ${HomeLogic.dayTime(view.session.start!!, env.h24)}"
        is HomeLogic.WeekendView.Live -> "${view.session.meeting}: ${view.session.name} is on now. Tap to follow live."
        is HomeLogic.WeekendView.Result -> "${view.podium.meeting} result: " + view.podium.places.joinToString("; ") { "${it.position} ${it.name}" }
        HomeLogic.WeekendView.Empty -> "Race weekend: no schedule yet"
    }

    /** Opens RaceWall; `watch` goes straight into live timing, as the "Watch live" notification does. */
    private fun open(context: Context, watch: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (watch) intent.putExtra(Notify.EXTRA_WATCH_LIVE, true)
        return PendingIntent.getActivity(context, if (watch) 11 else 10, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // ─── Keeping them current ────────────────────────────────────────────────────

    private const val WORK = "home-widgets-refresh"
    private const val WORK_NOW = "home-widgets-refresh-now"

    /** Every 3 hours, with a network: the schedule, the standings and the last race. */
    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HomeRefreshWorker>(3, TimeUnit.HOURS).setConstraints(constraints).build(),
            )
        }
        arm(context)
    }

    fun refreshNow(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<HomeRefreshWorker>().setConstraints(constraints).build())
        }
    }

    fun unschedule(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK) }
        context.getSystemService(AlarmManager::class.java)?.cancel(tickIntent(context))
    }

    private fun tickIntent(context: Context) =
        PendingIntent.getBroadcast(context, 20, Intent(context, HomeTickReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * An alarm at the next moment a picture changes by itself: a session's start and end, the day
     * before it (the countdown starts to tick), each day boundary of the countdown and the end of
     * a result's two days. Exact only where Android already allows it, as the session alerts.
     */
    fun arm(context: Context, now: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = tickIntent(context)
        if (!any(context)) return alarms.cancel(pending)
        val at = HomeLogic.moments(Alerts.schedule(context, now), now).firstOrNull() ?: return alarms.cancel(pending)
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC, at, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC, at, pending)
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC, at, pending)
        }
    }

    /** Fetches, saves and draws: the refresh work, and at once when a widget is first added. */
    suspend fun refresh(context: Context) {
        Alerts.refresh(context)
        HomeFetch.refresh(context)
        redraw(context)
        arm(context)
        previews(context)
    }

    /** Android 15 and later: the widget picker shows each widget as it looks now, with real data. */
    private fun previews(context: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        val manager = AppWidgetManager.getInstance(context) ?: return
        val snapshot = HomeStore.read(context)
        val schedule = Alerts.schedule(context)
        val settings = HomeSettings(clock = HomeSettingsStore.read(context).clock)
        val pen = Pen(context)
        val now = System.currentTimeMillis()
        val h24 = settings.h24(android.text.format.DateFormat.is24HourFormat(context))
        for ((kind, receiver) in RECEIVERS) {
            val (w, h) = PREVIEW_SIZES.getValue(kind)
            val views = face(context, kind, -1, pen, FaceEnv(w, h, now, h24, radius(context)), snapshot, schedule, settings)
            // Android limits how often previews may be set; a refused one keeps the last.
            runCatching { manager.setWidgetPreview(ComponentName(context, receiver), android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, views) }
        }
    }

    private val PREVIEW_SIZES = mapOf(
        HomeKind.WEEKEND to (320f to 150f),
        HomeKind.SCHEDULE to (250f to 200f),
        HomeKind.STANDINGS to (250f to 260f),
        HomeKind.LIVE to (250f to 260f),
    )
}

/** The scheduled moment a widget's picture changes by itself. */
class HomeTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        HomeWidgets.redraw(app)
        HomeWidgets.arm(app)
    }
}

class HomeRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!HomeWidgets.any(applicationContext)) return Result.success()
        HomeWidgets.refresh(applicationContext)
        return Result.success()
    }
}
