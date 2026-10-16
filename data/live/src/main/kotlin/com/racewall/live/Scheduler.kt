package com.racewall.live

import com.racewall.topics.Schedule
import com.racewall.topics.ScheduledSession

/**
 * Watches the season schedule, as the desktop's live-scheduler.ts: connects to live timing when a
 * session is about to start, goes back to the last result once it is over, and sends the "starting
 * soon" notification.
 */
object LiveScheduler {
    const val CONNECT_BEFORE_MS = 15 * 60_000L
    const val SOON_MS = 15 * 60_000L

    /** How long each kind of session can run, to know when live timing is worth keeping open. */
    private fun lengthMs(s: ScheduledSession): Long = when (s.name) {
        "Race" -> 3 * 3_600_000L
        "Sprint" -> (1.5 * 3_600_000).toLong()
        else -> 2 * 3_600_000L
    }

    /** The session live timing should be open for right now, if any. */
    fun sessionNow(schedule: Schedule, now: Long): ScheduledSession? =
        schedule.firstOrNull { it.start != null && now >= it.start!! - CONNECT_BEFORE_MS && now <= it.start!! + lengthMs(it) }

    /** Sessions that start within the next `SOON_MS` and have not been announced yet. */
    fun startingSoon(schedule: Schedule, now: Long, announced: Set<String>): List<ScheduledSession> =
        schedule.filter { it.start != null && it.start!! > now && it.start!! - now <= SOON_MS && key(it) !in announced }

    fun key(s: ScheduledSession) = "${s.round}|${s.name}"

    /**
     * Whether to connect to live timing for the session that is on: once per session, even over a
     * replay picked before it started. A session in `taken` was already joined once; if the user left
     * it for a replay, that choice stands.
     */
    fun shouldTakeOver(on: ScheduledSession?, isLive: Boolean, taken: Set<String>): Boolean = on != null && !isLive && key(on) !in taken
}
