package com.racewall.archive

import com.racewall.bus.jsString
import com.racewall.topics.Compound
import com.racewall.topics.Driver
import com.racewall.topics.DriverList
import com.racewall.topics.LapRow
import com.racewall.topics.Laps
import com.racewall.topics.PositionRow
import com.racewall.topics.Positions
import com.racewall.topics.SessionInfo
import com.racewall.topics.SessionState
import com.racewall.topics.SessionStatus
import com.racewall.topics.Stint
import com.racewall.topics.Stints
import com.racewall.topics.TimedValue
import com.racewall.topics.TrackFlag

/**
 * Turns the official feed's own shapes into RaceWall topics. Each function takes the merged feed
 * state at one moment and returns a fresh topic value.
 */
object Normalize {
    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()

    private fun str(v: Any?): String = if (v is String) v else if (v == null) "" else jsString(v)

    private fun bool(v: Any?): Boolean = v == true || v == "true"

    /** JavaScript's `Number(v) || 0` for the values the feed sends. */
    private fun number(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() ?: Double.NaN }
        is Boolean -> if (v) 1.0 else 0.0
        else -> Double.NaN
    }.let { if (it.isNaN()) 0.0 else it }

    private fun isNumber(v: Any?) = v is Long || v is Double || v is Int

    private val DIGITS = Regex("^\\d+$")

    /** Arrays arrive either as arrays or, after patches, sometimes as index-keyed objects. */
    private fun list(v: Any?): List<Any?> = when (v) {
        is List<*> -> v
        else -> obj(v).keys.filter { DIGITS.matches(it) }.sortedBy { it.toLong() }.map { obj(v)[it] }
    }

    /**
     * The order JavaScript lists an object's keys in: whole-number keys first, ascending, then the
     * others in the order they were added. Racing numbers are such keys.
     */
    private fun <V> jsEntries(m: Map<String, V>): List<Map.Entry<String, V>> {
        val (ints, rest) = m.entries.partition { DIGITS.matches(it.key) && it.key.length < 10 && (it.key == "0" || !it.key.startsWith("0")) }
        return ints.sortedBy { it.key.toLong() } + rest
    }

    fun sessionInfo(raw: Any?): SessionInfo {
        val s = obj(raw)
        val meeting = obj(s["Meeting"])
        return SessionInfo(
            key = number(s["Key"]).toInt(),
            round = number(meeting["Number"]).toInt(),
            year = str(s["StartDate"]).take(4).toIntOrNull() ?: 0,
            meeting = str(meeting["Name"]),
            name = str(s["Name"]),
            type = str(s["Type"]),
            circuit = str(obj(meeting["Circuit"])["ShortName"]),
            country = str(obj(meeting["Country"])["Name"]),
            startDate = str(s["StartDate"]),
            gmtOffset = str(s["GmtOffset"]),
            path = str(s["Path"]),
        )
    }

    private val FLAGS = mapOf("1" to TrackFlag.GREEN, "2" to TrackFlag.YELLOW, "4" to TrackFlag.SC, "5" to TrackFlag.RED, "6" to TrackFlag.VSC, "7" to TrackFlag.VSC_ENDING)
    private val STATES = mapOf(
        "Inactive" to SessionState.INACTIVE,
        "Started" to SessionState.STARTED,
        "Aborted" to SessionState.ABORTED,
        "Finished" to SessionState.FINISHED,
        "Finalised" to SessionState.FINALISED,
        "Ends" to SessionState.ENDED,
    )

    fun sessionStatus(status: Any?, track: Any?, lapCount: Any?): SessionStatus {
        val lc = obj(lapCount)
        return SessionStatus(
            state = STATES[str(obj(status)["Status"])] ?: SessionState.INACTIVE,
            flag = FLAGS[str(obj(track)["Status"])] ?: TrackFlag.UNKNOWN,
            lap = lc["CurrentLap"]?.takeIf(::isNumber)?.let { (it as Number).toInt() },
            totalLaps = lc["TotalLaps"]?.takeIf(::isNumber)?.let { (it as Number).toInt() },
        )
    }

    private val HEX = Regex("^[0-9a-fA-F]{6}$")

    fun drivers(raw: Any?): DriverList {
        val out = LinkedHashMap<String, Driver>()
        for ((num, value) in jsEntries(obj(raw))) {
            val d = obj(value)
            if (!truthy(d["RacingNumber"]) && !truthy(d["Tla"])) continue
            val colour = str(d["TeamColour"])
            out[num] = Driver(
                number = num,
                code = str(d["Tla"]),
                firstName = str(d["FirstName"]),
                lastName = str(d["LastName"]),
                team = str(d["TeamName"]),
                teamColour = if (HEX.matches(colour)) "#${colour.uppercase()}" else null,
            )
        }
        return out
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        null, false, "" -> false
        is Number -> v.toDouble() != 0.0 && !v.toDouble().isNaN()
        else -> true
    }

    /** In practice and qualifying the gaps live in other fields than in a race. */
    private fun gapOf(line: Map<String, Any?>): Pair<String, String> {
        if ("GapToLeader" in line) return str(line["GapToLeader"]) to str(obj(line["IntervalToPositionAhead"])["Value"])
        val stats = list(line["Stats"]).map(::obj)
        val lastStat = stats.lastOrNull { str(it["TimeDiffToFastest"]) != "" }
        if (lastStat != null) return str(lastStat["TimeDiffToFastest"]) to str(lastStat["TimeDifftoPositionAhead"] ?: lastStat["TimeDiffToPositionAhead"])
        return str(line["TimeDiffToFastest"]) to str(line["TimeDiffToPositionAhead"])
    }

    fun positions(timingData: Any?): Positions {
        val lines = obj(obj(timingData)["Lines"])
        return jsEntries(lines)
            .map { (num, value) ->
                val l = obj(value)
                val (gap, interval) = gapOf(l)
                val line = l["Line"].let { if (isNumber(it)) (it as Number).toDouble() else number(l["Position"]).takeIf { p -> p != 0.0 } ?: 99.0 }
                line to PositionRow(
                    number = num,
                    position = number(l["Position"]).toInt(),
                    gap = gap,
                    interval = interval,
                    inPit = bool(l["InPit"]),
                    pitOut = bool(l["PitOut"]),
                    retired = bool(l["Retired"]),
                    stopped = bool(l["Stopped"]),
                    knockedOut = bool(l["KnockedOut"]),
                    laps = l["NumberOfLaps"]?.takeIf(::isNumber)?.let { (it as Number).toInt() },
                )
            }
            .sortedBy { it.first }
            .map { it.second }
    }

    private fun timed(v: Any?): TimedValue {
        val o = obj(v)
        return TimedValue(str(o["Value"]), bool(o["OverallFastest"]), bool(o["PersonalFastest"]))
    }

    fun laps(timingData: Any?): Laps {
        val out = LinkedHashMap<String, LapRow>()
        for ((num, value) in jsEntries(obj(obj(timingData)["Lines"]))) {
            val l = obj(value)
            out[num] = LapRow(
                number = num,
                lastLap = timed(l["LastLapTime"]),
                bestLap = str(obj(l["BestLapTime"])["Value"]),
                sectors = list(l["Sectors"]).take(3).map(::timed),
            )
        }
        return out
    }

    private val COMPOUNDS = setOf("SOFT", "MEDIUM", "HARD", "INTERMEDIATE", "WET")

    fun stints(timingAppData: Any?): Stints {
        val out = LinkedHashMap<String, List<Stint>>()
        for ((num, value) in jsEntries(obj(obj(timingAppData)["Lines"]))) {
            var lap = 1
            out[num] = list(obj(value)["Stints"]).map { raw ->
                val s = obj(raw)
                val total = number(s["TotalLaps"]).toInt()
                val startAge = number(s["StartLaps"]).toInt()
                val compound = str(s["Compound"]).uppercase()
                val stint = Stint(
                    compound = if (compound in COMPOUNDS) Compound.valueOf(compound) else Compound.UNKNOWN,
                    isNew = bool(s["New"]),
                    tyreAge = total,
                    startLap = lap,
                )
                lap += maxOf(0, total - startAge)
                stint
            }
        }
        return out
    }
}
