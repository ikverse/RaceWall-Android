package com.racewall.android.home

import android.content.Context
import com.racewall.android.state.JsonStore
import com.racewall.topics.ScheduledSession
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The four home-screen widgets. */
enum class HomeKind(val label: String) { WEEKEND("Race weekend"), SCHEDULE("Schedule"), STANDINGS("Standings"), LIVE("Live") }

/** One widget's own settings. A null driver or team follows the shared one. */
@Serializable
data class HomeWidgetSettings(
    val kind: HomeKind,
    /** Standings: "drivers" or "constructors". */
    val table: String = DRIVERS,
    /** Standings: "points" or "gap" (to the leader). */
    val value: String = POINTS,
    val driver: String? = null,
    val team: String? = null,
    /** How much of the background shows, in percent: 100, 75, 50, 25 or 0. The wallpaper shows through the rest. */
    val opacity: Int = 100,
    /** "black", or "dim": the design lock's Dim panels fill, #0b0b0b. */
    val tone: String = BLACK,
    /** The 1 dp outline. */
    val border: Boolean = true,
    /** "launcher" rounds the corners as the launcher's own widgets; "square" does not. */
    val corners: String = LAUNCHER,
    /** "small", "normal" or "large": every text, line and gap grows or shrinks together. */
    val text: String = NORMAL,
    /** The small capitals heading at the top. */
    val title: Boolean = true,
    /** The team colour line beside each driver or team. */
    val teamBars: Boolean = true,
) {
    /** How the picture looks, for [Faces]. */
    fun look() = Look(
        opacity = opacity.coerceIn(0, 100) / 100f,
        dim = tone == DIM,
        border = border,
        square = corners == SQUARE,
        scale = when (text) {
            SMALL -> 0.88f
            LARGE -> 1.18f
            else -> 1f
        },
        title = title,
        bars = teamBars,
    )

    companion object {
        const val DRIVERS = "drivers"
        const val CONSTRUCTORS = "constructors"
        const val POINTS = "points"
        const val GAP = "gap"
        const val BLACK = "black"
        const val DIM = "dim"
        const val LAUNCHER = "launcher"
        const val SQUARE = "square"
        const val SMALL = "small"
        const val NORMAL = "normal"
        const val LARGE = "large"
        val OPACITIES = listOf(100, 75, 50, 25, 0)
    }
}

/** A widget's appearance, as [HomeWidgetSettings] sets it. */
data class Look(
    val opacity: Float = 1f,
    val dim: Boolean = false,
    val border: Boolean = true,
    val square: Boolean = false,
    val scale: Float = 1f,
    val title: Boolean = true,
    val bars: Boolean = true,
)

/** Set once, as Deskglow does: the driver and team every widget follows unless told otherwise. */
@Serializable
data class HomeSettings(
    /** A driver code, "NOR"; empty for none. */
    val driver: String = "",
    /** A constructor name as Jolpica writes it, "McLaren"; empty for none. */
    val team: String = "",
    /** "phone" follows the phone's 12 or 24-hour setting; "12" or "24" set it. */
    val clock: String = PHONE,
    val widgets: Map<String, HomeWidgetSettings> = emptyMap(),
) {
    fun of(id: Int, kind: HomeKind): HomeWidgetSettings = widgets[id.toString()]?.takeIf { it.kind == kind } ?: HomeWidgetSettings(kind)
    fun driverFor(w: HomeWidgetSettings) = w.driver ?: driver
    fun teamFor(w: HomeWidgetSettings) = w.team ?: team
    fun h24(phone: Boolean) = when (clock) {
        "12" -> false
        "24" -> true
        else -> phone
    }

    companion object {
        const val PHONE = "phone"
    }
}

object HomeSettingsStore {
    private val lock = Any()
    private fun store(context: Context) = JsonStore(File(context.filesDir, "home-widget-settings.json"))
    fun read(context: Context): HomeSettings = synchronized(lock) { store(context).read(HomeSettings()) }
    fun write(context: Context, s: HomeSettings) = synchronized(lock) { store(context).write(s) }
    fun update(context: Context, change: (HomeSettings) -> HomeSettings) = synchronized(lock) {
        val st = store(context)
        st.write(change(st.read(HomeSettings())))
    }
}

/** The schedule as the widgets read it: which sessions, when they end, and what is on. Ported from Deskglow's F1.kt. */
object HomeLogic {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    const val DAY = 24 * HOUR

    /** How long each kind of session runs, to dim it once it is over. */
    fun lengthMs(name: String): Long = when (name) {
        "Race" -> 2 * HOUR
        "Sprint Qualifying" -> 45 * MIN
        else -> HOUR
    }

    fun end(s: ScheduledSession): Long? = s.start?.let { it + lengthMs(s.name) }
    fun isOn(s: ScheduledSession, now: Long) = s.start != null && now >= s.start!! && now < end(s)!!
    fun isPast(s: ScheduledSession, now: Long) = end(s)?.let { now >= it } ?: false

    fun short(name: String): String = when (name) {
        "Practice 1" -> "FP1"
        "Practice 2" -> "FP2"
        "Practice 3" -> "FP3"
        "Sprint Qualifying" -> "Sprint quali"
        "Qualifying" -> "Quali"
        else -> name
    }

    /** The race weekend to show: the one with a session still to finish, or the season's last. */
    fun weekend(schedule: List<ScheduledSession>, now: Long): List<ScheduledSession> {
        val timed = schedule.filter { it.start != null }
        if (timed.isEmpty()) return emptyList()
        val round = timed.firstOrNull { !isPast(it, now) }?.round ?: timed.last().round
        return timed.filter { it.round == round }
    }

    fun next(schedule: List<ScheduledSession>, now: Long): ScheduledSession? =
        schedule.firstOrNull { it.start != null && it.start!! > now }

    fun onNow(schedule: List<ScheduledSession>, now: Long): ScheduledSession? = schedule.firstOrNull { isOn(it, now) }

    /** What the race weekend widget shows. */
    sealed interface WeekendView {
        data class Live(val session: ScheduledSession) : WeekendView
        data class Upcoming(val session: ScheduledSession) : WeekendView
        data class Result(val podium: HomePodium, val next: ScheduledSession?) : WeekendView
        data object Empty : WeekendView
    }

    /** A race's top three stay for two days after it, then the countdown to the next session takes over. */
    const val RESULT_FOR_MS = 2 * DAY

    fun weekendView(schedule: List<ScheduledSession>, podium: HomePodium?, now: Long): WeekendView {
        onNow(schedule, now)?.let { return WeekendView.Live(it) }
        val next = next(schedule, now)
        if (podium != null) {
            val race = schedule.firstOrNull { it.name == "Race" && it.round == podium.round && it.start != null && year(it.start!!) == podium.season }
            val ended = race?.let(::end)
            if (ended != null && now >= ended && now - ended < RESULT_FOR_MS) return WeekendView.Result(podium, next)
        }
        return next?.let { WeekendView.Upcoming(it) } ?: WeekendView.Empty
    }

    private fun year(ms: Long) = Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).year

    /** Deskglow's countdown: days, hours and minutes, down to minutes and seconds in the last hour. */
    fun countdownParts(now: Long, then: Long): List<Pair<String, String>> {
        val s = ((then - now) / 1000).coerceAtLeast(0)
        fun two(v: Long) = String.format(Locale.US, "%02d", v)
        return when {
            s >= 86_400 -> listOf("${s / 86_400}" to (if (s < 2 * 86_400) "DAY" else "DAYS"), two(s % 86_400 / 3600) to "HRS", two(s % 3600 / 60) to "MIN")
            s >= 3600 -> listOf(two(s / 3600) to "HRS", two(s % 3600 / 60) to "MIN")
            else -> listOf(two(s / 60) to "MIN", two(s % 60) to "SEC")
        }
    }

    /**
     * Days left as a calendar counts them: Wednesday to Friday is 2, whatever the hour. A widget
     * redraws at each midnight before a session, so the number is never stale.
     */
    fun daysLeft(now: Long, then: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        java.time.temporal.ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), Instant.ofEpochMilli(then).atZone(zone).toLocalDate()).coerceAtLeast(0)

    /** Under a day the countdown ticks by itself, in Android's own timer. */
    fun ticking(now: Long, then: Long) = then > now && then - now < DAY

    /**
     * Where now is along the weekend's sessions: 2.5 is half way from the third to the fourth.
     * Ported from Deskglow's timelineProgress.
     */
    fun timelineProgress(starts: List<Long>, now: Long): Float {
        val started = starts.indexOfLast { it <= now }
        if (started < 0) return 0f
        if (started == starts.lastIndex) return started.toFloat()
        val from = starts[started]
        val span = (starts[started + 1] - from).coerceAtLeast(1)
        return started + (now - from).toFloat() / span
    }

    /** Points, or the gap to the leader: "—" for the leader. Ported from Deskglow's standings. */
    fun valueText(points: Double, leader: Double, gap: Boolean): String {
        fun fmt(v: Double) = if (v % 1.0 == 0.0) String.format(Locale.US, "%d", v.toLong()) else String.format(Locale.US, "%.1f", v)
        if (!gap) return fmt(points)
        val behind = leader - points
        return if (behind <= 0.0) "—" else "−" + fmt(behind)
    }

    /**
     * The rows that fit: the first `fit`, or, when the followed row is further down, the first
     * `fit - 1` and then the followed one, so it is always on screen.
     */
    fun <T> visibleRows(rows: List<T>, fit: Int, followed: Int): List<T> {
        if (fit <= 0) return emptyList()
        if (rows.size <= fit || followed < fit) return rows.take(fit)
        return rows.take(fit - 1) + rows[followed]
    }

    /** The moments a widget's picture changes by itself: each midnight of a countdown, a day before, each start and each end. */
    fun moments(schedule: List<ScheduledSession>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
        val out = ArrayList<Long>()
        for (s in schedule) {
            val start = s.start ?: continue
            val e = end(s) ?: continue
            if (e < now) continue
            out += start
            out += e
            out += e + RESULT_FOR_MS
            out += start - DAY
            val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            for (k in 0..7) out += day.minusDays(k.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        return out.filter { it > now }.sorted()
    }

    fun dayTime(ms: Long, h24: Boolean, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern(if (h24) "EEE HH:mm" else "EEE h:mm a", Locale.getDefault()).format(Instant.ofEpochMilli(ms).atZone(zone))

    fun time(ms: Long, h24: Boolean, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", Locale.getDefault()).format(Instant.ofEpochMilli(ms).atZone(zone))

    fun day(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("EEE", Locale.getDefault()).format(Instant.ofEpochMilli(ms).atZone(zone))

    /** A constructor's colour, from its name as Jolpica writes it. Deskglow's team colours. */
    fun teamColour(name: String): Long {
        val n = name.lowercase(Locale.ROOT)
        return when {
            "red bull" in n -> 0xFF3671C6
            "mclaren" in n -> 0xFFFF8000
            "ferrari" in n -> 0xFFE8002D
            "mercedes" in n -> 0xFF27F4D2
            "aston" in n -> 0xFF229971
            "alpine" in n -> 0xFFFF87BC
            "williams" in n -> 0xFF64C4FF
            "racing bulls" in n || "rb f1" in n || n == "rb" || "alphatauri" in n -> 0xFF6692FF
            "haas" in n -> 0xFFB6BABD
            "sauber" in n || "kick" in n -> 0xFF52E252
            "audi" in n -> 0xFFF50537
            "cadillac" in n -> 0xFFC9A75D
            else -> 0xFF8C8C8C
        }
    }

    fun colourOf(hex: String?, team: String): Long =
        hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000 or it } ?: teamColour(team)

    /** The live widget's status line: what the track says, and in which data colour. */
    data class Status(val label: String, val colour: Long)

    fun status(live: HomeLive): Status = when {
        live.state in setOf("finished", "finalised", "ended") -> Status("Finished", T2)
        live.flag == "red" -> Status("Red flag", RED)
        live.flag == "sc" -> Status("Safety car", AMBER)
        live.flag == "vsc" -> Status("Virtual safety car", AMBER)
        live.flag == "vsc-ending" -> Status("VSC ending", AMBER)
        live.flag == "yellow" -> Status("Yellow flag", YELLOW)
        live.state == "inactive" -> Status("Not started", T2)
        live.state == "aborted" -> Status("Suspended", T2)
        else -> Status("Track clear", T2)
    }

    // Colours from design/DESIGN-LOCK.md, as the app's Rw.
    const val T2 = 0xFF8A8A8A
    const val RED = 0xFFFF3B3B
    const val AMBER = 0xFFFF9F1A
    const val YELLOW = 0xFFFFD12E
}
