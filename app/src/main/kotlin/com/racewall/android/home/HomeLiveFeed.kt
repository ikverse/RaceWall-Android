package com.racewall.android.home

import android.content.Context
import com.racewall.android.Format
import com.racewall.bus.Bus
import com.racewall.topics.DriverList
import com.racewall.topics.Positions
import com.racewall.topics.RaceControlMessages
import com.racewall.topics.SessionInfo
import com.racewall.topics.SessionStatus
import com.racewall.topics.Topic

/**
 * The live widget's data while a session is followed: read from the bus at the clock's time, so
 * it is what the app shows, whatever panels are open. Saved and drawn at most every 5 seconds,
 * and only when it changed.
 */
object HomeLiveFeed {
    const val EVERY_MS = 5_000L
    private var lastAt = 0L
    private var last: HomeLive? = null

    fun of(bus: Bus, t: Double): HomeLive? {
        val info = bus.typed<SessionInfo>(Topic.SESSION_INFO, t) ?: return null
        val status = bus.typed<SessionStatus>(Topic.SESSION_STATUS, t)
        val drivers = bus.typed<DriverList>(Topic.DRIVERS_LIST, t) ?: emptyMap()
        val positions = bus.typed<Positions>(Topic.TIMING_POSITIONS, t) ?: emptyList()
        val news = bus.typed<RaceControlMessages>(Topic.RACE_CONTROL, t)?.lastOrNull()?.message
        val race = Format.isRace(info)
        val rows = positions.mapIndexed { i, p ->
            val d = drivers[p.number]
            HomeLiveRow(
                position = p.position,
                number = p.number,
                code = d?.code ?: p.number,
                team = d?.team ?: "",
                colour = d?.teamColour,
                gap = Format.gapText(p, i, race),
                inPit = p.inPit,
                out = p.retired || p.stopped,
            )
        }
        return HomeLive(
            meeting = info.meeting,
            session = info.name,
            flag = status?.flag?.wire ?: "unknown",
            state = status?.state?.wire ?: "inactive",
            lap = status?.lap,
            totalLaps = status?.totalLaps,
            race = race,
            rows = rows,
            news = news,
        )
    }

    /** Saves and draws the live widget, at most every [EVERY_MS]. */
    fun push(context: Context, live: HomeLive?, now: Long = System.currentTimeMillis()) {
        if (live == last) return
        if (live != null && last != null && now - lastAt < EVERY_MS) return
        lastAt = now
        last = live
        if (HomeWidgets.ids(context, HomeKind.LIVE).isEmpty() && live != null) return
        HomeStore.update(context) { it.copy(live = live) }
        HomeWidgets.redraw(context, listOf(HomeKind.LIVE))
    }

    /** Live timing stopped: the live widget goes back to the next session. */
    fun clear(context: Context) {
        last = null
        HomeStore.update(context) { it.copy(live = null) }
        HomeWidgets.redraw(context, listOf(HomeKind.LIVE))
    }
}
