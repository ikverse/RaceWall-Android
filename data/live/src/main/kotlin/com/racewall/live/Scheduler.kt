package com.racewall.live

import com.racewall.topics.Schedule
import com.racewall.topics.ScheduledSession

/**
 * Watches the season schedule, as the desktop's live-scheduler.ts: which session is on now, and
 * which to announce, as "starts soon" 15 minutes before and as "is live" at its start. RaceWall
 * never switches to live by itself; the user goes live from the notification, the banner or the bar.
 */
object LiveScheduler {
    const val SOON_MS = 15 * 60_000L

    /** How long each kind of session can run, to know when it is still on. */
    private fun lengthMs(s: ScheduledSession): Long = when (s.name) {
        "Race" -> 3 * 3_600_000L
        "Sprint" -> (1.5 * 3_600_000).toLong()
        else -> 2 * 3_600_000L
    }

    /** A session's own key; the start time keeps round numbers of different seasons apart. */
    fun key(s: ScheduledSession) = "${s.round}|${s.name}|${s.start}"
    fun soonKey(s: ScheduledSession) = "soon|${key(s)}"
    fun liveKey(s: ScheduledSession) = "live|${key(s)}"

    /** The session that is on right now: from its scheduled start until it can be over. */
    fun sessionNow(schedule: Schedule, now: Long): ScheduledSession? =
        schedule.firstOrNull { it.start != null && now >= it.start!! && now <= it.start!! + lengthMs(it) }

    /** Sessions that start within the next `SOON_MS` and have not been announced yet. */
    fun startingSoon(schedule: Schedule, now: Long, announced: Set<String>): List<ScheduledSession> =
        schedule.filter { it.start != null && it.start!! > now && it.start!! - now <= SOON_MS && soonKey(it) !in announced }

    /** The session that is on now, if its "is live" notification has not been sent yet. */
    fun goneLive(schedule: Schedule, now: Long, announced: Set<String>): ScheduledSession? =
        sessionNow(schedule, now)?.takeIf { liveKey(it) !in announced }

    /** The next moment worth waking for: 15 minutes before a session, or its start. Null when none is left. */
    fun nextAlarm(schedule: Schedule, now: Long): Long? =
        schedule.mapNotNull { it.start }.flatMap { listOf(it - SOON_MS, it) }.filter { it > now }.minOrNull()
}
