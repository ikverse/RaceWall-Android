package com.racewall.analysis

import com.racewall.topics.Compound
import com.racewall.topics.LapHistory
import com.racewall.topics.PositionRow
import com.racewall.topics.RaceControlMessages
import com.racewall.topics.Stints

/**
 * Pit window, as the desktop's plugins/pit-window: what a pit stop costs at this track, and where
 * each car would come back out if it stopped now.
 *
 * The cost is measured from this session's own stops: the in-lap and out-lap together, minus two
 * of the driver's normal laps around the stop. Stops under a safety car, virtual safety car or red
 * flag are left out, because they cost less, and so are switches between wet and dry tyres. Until
 * the first measurable stop, a typical cost is used.
 */
object PitWindow {
    /** A typical pit stop cost, used before this session has one to measure. */
    const val DEFAULT_LOSS = 22.0

    /** Measured costs outside this range are a mistake in the data, not a pit stop. */
    private const val MIN_LOSS = 10.0
    private const val MAX_LOSS = 60.0

    /** Laps either side of a stop used for the driver's normal lap time. */
    private const val AROUND = 4
    private val WET = setOf(Compound.INTERMEDIATE, Compound.WET)

    data class StopCost(
        val number: String,
        /** The lap the car came in at the end of. */
        val inLap: Int,
        /** Seconds lost against two normal laps. */
        val loss: Double,
    )

    data class Row(
        val number: String,
        val position: Int,
        /** Seconds behind the leader now; null when lapped, retired or not timed. */
        val gap: Double?,
        /** Position it would rejoin in, if it stopped now. */
        val rejoin: Int? = null,
        /** The cars it would come out between, and how far from each in seconds. */
        val ahead: String? = null,
        val gapAhead: Double? = null,
        val behind: String? = null,
        val gapBehind: Double? = null,
    )

    data class Estimate(
        val race: Boolean,
        /** Seconds a stop costs here. */
        val loss: Double,
        /** "session" when measured from this session's stops, else "typical". */
        val lossFrom: String,
        /** The stops the cost was measured from. */
        val stops: List<StopCost>,
        val rows: List<Row>,
    )

    /** JavaScript's Math.round. */
    private fun round(v: Double): Double = Math.floor(v + 0.5)

    /** What each green-flag stop so far cost. */
    fun stopCosts(history: LapHistory?, stints: Stints?, messages: RaceControlMessages?): List<StopCost> {
        val neutral = Race.neutralLaps(messages)
        val out = ArrayList<StopCost>()
        for ((number, list) in stints ?: emptyMap()) {
            val laps = Race.lapTimes(history, number)
            for (i in 1 until list.size) {
                val outLap = list[i].startLap
                val inLap = outLap - 1
                if (inLap < 2 || inLap in neutral || outLap in neutral) continue
                // Switching between wet and dry tyres means the track is changing: the laps around say little.
                if ((list[i - 1].compound in WET) != (list[i].compound in WET)) continue
                val tin = laps[inLap] ?: continue
                val tout = laps[outLap] ?: continue
                val near = ArrayList<Double>()
                for (l in inLap - AROUND..outLap + AROUND) {
                    if (l == inLap || l == outLap || l < 2 || l in neutral) continue
                    // Laps that are themselves part of another stop are not normal laps.
                    if (list.any { it.startLap == l || it.startLap - 1 == l }) continue
                    laps[l]?.let { near.add(it.toDouble()) }
                }
                val typical = Race.median(near) ?: continue
                if (near.size < 3) continue
                val reference = Race.median(near.filter { it <= typical * 1.07 }) ?: typical
                val loss = (tin + tout - 2 * reference) / 1000
                if (loss in MIN_LOSS..MAX_LOSS) out.add(StopCost(number, inLap, round(loss * 1000) / 1000))
            }
        }
        return out.sortedWith(compareBy<StopCost> { it.inLap }.thenBy { it.number.toDoubleOrNull() ?: 0.0 })
    }

    fun estimate(topics: RaceTopics): Estimate {
        val race = Race.isRace(topics.info)
        val stops = stopCosts(topics.lapHistory, topics.stints, topics.messages)
        val measured = Race.median(stops.map { it.loss })
        val loss = if (measured == null) DEFAULT_LOSS else round(measured * 10) / 10
        val positions = topics.positions
        class Running(val r: PositionRow, val gap: Double)
        val running = positions.mapIndexedNotNull { i, r ->
            val gap = if (i == 0 && !r.retired && !r.stopped) 0.0 else if (r.retired || r.stopped) null else Race.parseGap(r.gap)
            gap?.let { Running(r, it) }
        }.sortedBy { it.gap }
        val rows = positions.map { r ->
            val me = running.firstOrNull { it.r.number == r.number }
            val row = Row(r.number, r.position, me?.gap)
            if (!race || me == null) return@map row
            val after = me.gap + loss
            val others = running.filter { it.r.number != r.number }
            val front = others.filter { it.gap < after }
            val back = others.filter { it.gap >= after }
            val a = front.lastOrNull()
            val b = back.firstOrNull()
            row.copy(
                rejoin = front.size + 1,
                ahead = a?.r?.number,
                gapAhead = a?.let { round((after - it.gap) * 10) / 10 },
                behind = b?.r?.number,
                gapBehind = b?.let { round((it.gap - after) * 10) / 10 },
            )
        }
        return Estimate(race, loss, if (measured == null) "typical" else "session", stops, rows)
    }
}
