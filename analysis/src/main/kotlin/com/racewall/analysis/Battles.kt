package com.racewall.analysis

import com.racewall.topics.LapHistory
import com.racewall.topics.TrackFlag

/**
 * Who is within a second of the car ahead, and for how many laps, as the desktop's
 * plugins/battles.
 *
 * Only the current interval is in the feed, but the gap at each earlier lap line follows from the
 * two cars' lap times: going back one lap, the gap shrinks by however much slower the car behind
 * was on that lap. So the battle length needs no history of intervals, and is right after a seek.
 */
object Battles {
    /** A car this close to the one ahead at the line can use DRS. */
    const val WITHIN = 1.0

    data class Battle(
        /** The car behind. */
        val number: String,
        /** The car it is chasing. */
        val ahead: String,
        /** Position of the car behind. */
        val position: Int,
        /** Seconds to the car ahead now. */
        val interval: Double,
        /** Laps in a row it has crossed the line within a second of this car, counting the last one. */
        val laps: Int,
        /** Change in the gap per lap over the last three laps, in seconds: negative is closing. */
        val trend: Double?,
    )

    /** Why battles are held: "sc", "vsc" or "red" while the field is neutralised, else null. */
    data class Result(val race: Boolean, val neutral: String?, val battles: List<Battle>)

    private val NEUTRAL = mapOf(TrackFlag.SC to "sc", TrackFlag.VSC to "vsc", TrackFlag.VSC_ENDING to "vsc", TrackFlag.RED to "red")

    /** The gap at each lap line, newest first, going back while both cars have lap times. */
    fun gapsBack(history: LapHistory?, behind: String, ahead: String, lap: Int, now: Double, max: Int): List<Double> {
        val b = Race.lapTimes(history, behind)
        val a = Race.lapTimes(history, ahead)
        val gaps = arrayListOf(now)
        var gap = now
        var l = lap
        while (l > 1 && gaps.size < max) {
            val tb = b[l] ?: break
            val ta = a[l] ?: break
            gap -= (tb - ta) / 1000.0
            gaps.add(gap)
            l--
        }
        return gaps
    }

    fun find(topics: RaceTopics): Result {
        val race = Race.isRace(topics.info)
        val neutral = NEUTRAL[topics.status?.flag ?: TrackFlag.GREEN]
        if (!race || neutral != null) return Result(race, neutral, emptyList())
        val rows = topics.positions
        val battles = ArrayList<Battle>()
        for (i in 1 until rows.size) {
            val car = rows[i]
            val front = rows[i - 1]
            val interval = Race.parseGap(car.interval) ?: continue
            if (interval > WITHIN) continue
            if (listOf(car, front).any { it.inPit || it.pitOut || it.retired || it.stopped }) continue
            val lap = car.laps ?: continue
            val frontLaps = front.laps ?: continue
            // Same lap, or the car ahead has just crossed the line the one behind is about to cross.
            if (frontLaps - lap < 0 || frontLaps - lap > 1) continue
            val gaps = gapsBack(topics.lapHistory, car.number, front.number, lap, interval, 200)
            var laps = 0
            while (laps < gaps.size && gaps[laps] > 0 && gaps[laps] <= WITHIN) laps++
            val trend = if (gaps.size > 3) (gaps[0] - gaps[3]) / 3 else null
            battles.add(Battle(car.number, front.number, car.position, interval, maxOf(1, laps), trend))
        }
        return Result(race, null, battles)
    }
}
