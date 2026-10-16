package com.racewall.android

import com.racewall.topics.PositionRow
import com.racewall.topics.SessionInfo
import com.racewall.topics.SessionStatus
import com.racewall.topics.TrackFlag
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** How timing values read on screen, as the desktop's app/ui/src/format.ts and plugins/_shared/ui.ts. */
object Format {
    /** "01:04:09" from session milliseconds. */
    fun elapsed(ms: Double): String {
        val s = maxOf(0L, Math.floor(ms / 1000).toLong())
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private val UTC_CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)

    /** "14:47:12" in UTC. */
    fun utcClock(ms: Double): String = UTC_CLOCK.format(Instant.ofEpochMilli(Math.floor(ms).toLong()))

    fun lapText(status: SessionStatus?): String {
        val lap = status?.lap ?: return ""
        if (lap == 0) return ""
        return if (status.totalLaps != null && status.totalLaps != 0) "Lap $lap / ${status.totalLaps}" else "Lap $lap"
    }

    fun isRace(info: SessionInfo?): Boolean = info?.name == "Race" || info?.name == "Sprint"

    fun gapText(row: PositionRow, index: Int, race: Boolean): String = when {
        row.retired -> "Out"
        row.stopped -> "Stopped"
        index == 0 -> if (race) "Leader" else ""
        else -> row.gap
    }

    fun intervalText(row: PositionRow, index: Int): String = if (index == 0 || row.retired || row.stopped) "" else row.interval

    /** The track flag as the transport bar shows it, or null when racing is green. */
    fun flag(flag: TrackFlag): Pair<String, androidx.compose.ui.graphics.Color>? = when (flag) {
        TrackFlag.GREEN, TrackFlag.UNKNOWN -> null
        TrackFlag.YELLOW -> "Yellow flag" to com.racewall.android.ui.Rw.yellow
        TrackFlag.SC -> "Safety car" to com.racewall.android.ui.Rw.sc
        TrackFlag.VSC -> "Virtual safety car" to com.racewall.android.ui.Rw.sc
        TrackFlag.VSC_ENDING -> "VSC ending" to com.racewall.android.ui.Rw.sc
        TrackFlag.RED -> "Red flag" to com.racewall.android.ui.Rw.red
    }

    /** The year of an ISO date as JavaScript's `new Date(text).getFullYear()` reads it. */
    fun year(date: String): String = date.take(4)
}
