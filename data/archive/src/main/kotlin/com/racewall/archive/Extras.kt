package com.racewall.archive

import com.racewall.bus.jsString
import com.racewall.bus.mergeFeed
import com.racewall.topics.LapHistory
import com.racewall.topics.LapTime
import com.racewall.topics.RaceControlMessage
import com.racewall.topics.RadioClip
import com.racewall.topics.Weather
import java.time.Instant

/**
 * Topics built from everything that happened so far rather than from one merged state: lap
 * history, race control, weather and team radio, as the desktop's extras.ts. Each is a time-ordered
 * list that grows as feed records arrive; the view at time t returns what had happened by then.
 */
@Suppress("UNCHECKED_CAST")
internal fun obj(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()

private fun num(v: Any?): Double = when (v) {
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull() ?: leadingNumber(v)
    else -> 0.0
}.let { if (it.isFinite()) it else 0.0 }

/** `parseFloat`: the number at the start of the text, or 0. */
private fun leadingNumber(text: String): Double = Regex("""^\s*[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?""").find(text)?.value?.trim()?.toDoubleOrNull() ?: 0.0

private fun items(v: Any?): List<Any?> = when (v) {
    is List<*> -> v
    is Map<*, *> -> v.values.toList()
    else -> emptyList()
}

/** Index of the last element with `t` at or before `t`, for lists sorted by time. */
fun <T> lastAt(list: List<T>, t: Double, time: (T) -> Double): Int {
    var lo = 0
    var hi = list.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (time(list[mid]) <= t) {
            found = mid
            lo = mid + 1
        } else hi = mid - 1
    }
    return found
}

private val LAP = Regex("""^(?:(\d+):)?(\d+)\.(\d+)$""")

/** "1:28.123" or "28.123" to milliseconds. */
fun lapMs(text: String): Long? {
    val m = LAP.matchEntire(text.trim()) ?: return null
    val minutes = m.groupValues[1].ifEmpty { "0" }.toLong()
    return (minutes * 60 + m.groupValues[2].toLong()) * 1000 + m.groupValues[3].padEnd(3, '0').take(3).toLong()
}

/**
 * UTC milliseconds of an ISO time, as `Date.parse`; null when unreadable. Takes "Z" and "+00:00"
 * alike: Android's own `Instant.parse` refuses an offset.
 */
fun parseUtc(text: String): Long? = try {
    Instant.parse(text).toEpochMilli()
} catch (_: Exception) {
    try {
        java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

class LapEvent(val t: Double, val number: String, val lap: LapTime)
class SectorEvent(val t: Double, val number: String, val sector: Int)

/**
 * Every completed lap, read from the moments a car's last-lap time changes, or its lap count goes
 * up as it crosses the line.
 *
 * The feed only sends what changed, so a lap exactly as fast as the one before arrives as a new lap
 * count and a final sector time, with no lap time (2024 Bahrain, Bottas lap 50: 1:36.910 twice; its
 * three sectors add up to that). A lap count that goes up with an empty final sector is a car
 * leaving the pits in practice or qualifying, not a lap.
 */
class LapHistoryBuilder {
    val events = ArrayList<LapEvent>()

    /** The moments a car's sector 1 or sector 2 time came through: where the sector boundaries are. */
    val sectorEvents = ArrayList<SectorEvent>()
    private var state: Any? = LinkedHashMap<String, Any?>()
    private var first = true
    private val lastSeen = HashMap<String, String>()
    private val lastLap = HashMap<String, Int>()
    private val counts = HashMap<String, Int>()

    /** "number:lap" of every lap in `events`, so a lap is never listed twice. */
    private val known = HashSet<String>()

    /** The laps that came from a backfill: the live feed's own copy of one is not added again. */
    private val filled = HashSet<String>()

    /** Each car's lap count each time it changed: how many laps a set of tyres has done. */
    private val lapCounts = HashMap<String, ArrayList<Pair<Double, Int>>>()

    /** The moments any car's lap count changed. */
    private val lapChanges = ArrayList<Double>()

    /** Goes up when laps are added out of time order, by a backfill. */
    private var gen = 0

    @Synchronized
    fun add(t: Double, data: Any?) {
        val lines = obj(obj(data)["Lines"])
        state = mergeFeed(state, data)
        val wasFirst = first
        first = false
        for (number in lines.keys) {
            (obj(lines[number])["NumberOfLaps"] as? Number)?.toInt()?.let { laps ->
                val list = lapCounts.getOrPut(number) { ArrayList() }
                if (list.lastOrNull()?.second != laps) {
                    list.add(t to laps)
                    lapChanges.add(t)
                }
            }
            if (!wasFirst) {
                val sectors = obj(obj(lines[number])["Sectors"])
                for (sector in 0..1) if (truthy(obj(sectors["$sector"])["Value"])) sectorEvents.add(SectorEvent(t, number, sector))
            }
            val line = obj(obj(obj(state)["Lines"])[number])
            val time = obj(line["LastLapTime"])["Value"]?.let(::jsString) ?: ""
            if (time.isEmpty()) continue
            val count = (line["NumberOfLaps"] as? Number)?.toInt()
            val changed = lastSeen[number] != time
            val record = obj(lines[number])
            val crossedLine = record["NumberOfLaps"] is Number && truthy(obj(obj(record["Sectors"])["2"])["Value"])
            val repeated = !changed && crossedLine && count != null && count > (lastLap[number] ?: 0)
            if (!changed && !repeated) continue
            lastSeen[number] = time
            val n = (counts[number] ?: 0) + 1
            counts[number] = n
            val lap = count ?: n
            lastLap[number] = lap
            val key = "$number:$lap"
            if (key in filled) continue
            known.add(key)
            events.add(LapEvent(t, number, LapTime(lap, time, lapMs(time))))
        }
    }

    /**
     * Adds laps driven before RaceWall joined the session, from the archive or an earlier
     * recording. A lap already known is kept as it is; the rest are slotted in by time.
     */
    @Synchronized
    fun backfill(laps: List<LapEvent>): Int {
        var added = 0
        for (e in laps) {
            val key = "${e.number}:${e.lap.lap}"
            if (!known.add(key)) continue
            filled.add(key)
            events.add(e)
            added++
        }
        if (added > 0) {
            events.sortBy { it.t }
            gen++
        }
        return added
    }

    /** A key that changes whenever `at(t)` may give something else. */
    @Synchronized
    fun version(t: Double): String = "$gen:${lastAt(events, t) { it.t }}"

    /** A key that changes whenever any car's lap count at `t` may be different. */
    @Synchronized
    fun lapsVersion(t: Double): Int = lastAt(lapChanges, t) { it }

    /** A car's lap count at `t`; before its first known count, that first count. */
    @Synchronized
    fun lapsAt(number: String, t: Double): Int? {
        val list = lapCounts[number] ?: return null
        if (list.isEmpty()) return null
        return list[maxOf(0, lastAt(list, t) { it.first })].second
    }

    @Synchronized
    fun at(t: Double): LapHistory {
        val out = LinkedHashMap<String, MutableList<LapTime>>()
        val end = lastAt(events, t) { it.t }
        for (i in 0..end) out.getOrPut(events[i].number) { ArrayList() }.add(events[i].lap)
        return out
    }

    private fun truthy(v: Any?): Boolean = v != null && v != "" && v != false && !(v is Number && v.toDouble() == 0.0)
}

class TimedMessage(val t: Double, val message: RaceControlMessage)

private val Z = Regex("Z?$")

fun raceControlMessages(t: Double, data: Any?): List<TimedMessage> = items(obj(data)["Messages"]).map { raw ->
    val m = obj(raw)
    val utc = parseUtc(jsString(m["Utc"] ?: "").replaceFirst(Z, "Z")) ?: 0L
    TimedMessage(
        t,
        RaceControlMessage(
            utc = utc,
            lap = (m["Lap"] as? Number)?.toInt(),
            category = jsString(m["Category"] ?: ""),
            flag = m["Flag"]?.let(::jsString)?.ifEmpty { null },
            message = jsString(m["Message"] ?: ""),
        ),
    )
}

class WeatherEntry(val t: Double, val full: Weather)

fun weatherSample(t: Double, data: Any?): WeatherEntry {
    val w = obj(data)
    return WeatherEntry(
        t,
        Weather(
            airTemp = num(w["AirTemp"]),
            trackTemp = num(w["TrackTemp"]),
            humidity = num(w["Humidity"]),
            pressure = num(w["Pressure"]),
            rainfall = num(w["Rainfall"]) > 0,
            windSpeed = num(w["WindSpeed"]),
            windDirection = num(w["WindDirection"]),
        ),
    )
}

class TimedClip(val t: Double, val clip: RadioClip)

fun radioClips(t: Double, data: Any?, sessionUrl: String): List<TimedClip> = items(obj(data)["Captures"]).mapNotNull { raw ->
    val c = obj(raw)
    val path = jsString(c["Path"] ?: "")
    if (path.isEmpty()) return@mapNotNull null
    TimedClip(t, RadioClip(parseUtc(jsString(c["Utc"] ?: "")) ?: 0L, jsString(c["RacingNumber"] ?: ""), sessionUrl + path))
}

/**
 * When each stint first appeared, and how old its tyres were then, so a stint's tyre age can be
 * counted from the car's laps since: the feed's own TotalLaps lags behind the lap count, and
 * arrives as 0 when the car comes in. As the desktop's extras.ts.
 */
class StintStarts {
    class Start(val t: Double, val age: Int)

    private val seen = HashMap<String, Start>()
    private var state: Any? = LinkedHashMap<String, Any?>()
    private val digits = Regex("^\\d+$")

    @Synchronized
    fun add(t: Double, data: Any?) {
        state = mergeFeed(state, data)
        for ((number, line) in obj(obj(data)["Lines"])) {
            val stints = obj(line)["Stints"] ?: continue
            val merged = obj(obj(obj(state)["Lines"])[number])["Stints"]
            val keys = if (stints is List<*>) stints.indices.map { "$it" } else obj(stints).keys.toList()
            for (k in keys) {
                if (!digits.matches(k)) continue
                val id = "$number:$k"
                if (id in seen) continue
                val s = obj(if (merged is List<*>) merged.getOrNull(k.toInt()) else obj(merged)[k])
                seen[id] = Start(t, maxOf(num(s["TotalLaps"]), num(s["StartLaps"])).toInt())
            }
        }
    }

    @Synchronized
    fun get(number: String, index: Int): Start? = seen["$number:$index"]
}

/**
 * Tyre age of one stint: the age when it was first seen plus the laps the car has driven since
 * (up to the next stint), or TotalLaps when that is higher.
 */
fun tyreAge(starts: StintStarts, history: LapHistoryBuilder, number: String, index: Int, t: Double, startAge: Int, total: Int): Int {
    val start = starts.get(number, index) ?: return total
    val from = history.lapsAt(number, start.t) ?: return total
    val next = starts.get(number, index + 1)
    val to = history.lapsAt(number, if (next != null && next.t <= t) next.t else t) ?: return total
    return maxOf(total, maxOf(startAge, start.age) + maxOf(0, to - from))
}

/**
 * Splits a recording's records into runs: an app restart begins again at 00:00:00 with a snapshot
 * ("_kf"). Live records can also arrive a few milliseconds out of order; that is not a new run.
 */
private fun runs(entries: List<FeedEntry>): List<List<FeedEntry>> {
    val out = ArrayList<ArrayList<FeedEntry>>()
    var last = Double.POSITIVE_INFINITY
    for (e in entries) {
        val kf = obj(e.data)["_kf"] == true
        if (out.isEmpty() || (e.t < last && (kf || e.t < last - 30_000))) out.add(ArrayList())
        out.last().add(e)
        last = e.t
    }
    return out
}

/** UTC of time 0 of one run, from the race control messages that arrived during it (not the snapshot). */
private fun runBase(entries: List<FeedEntry>): Double? {
    val anchors = ArrayList<Double>()
    for (e in entries) {
        if (obj(e.data)["_kf"] == true) continue
        for (m in raceControlMessages(e.t, e.data)) if (m.message.utc != 0L) anchors.add(m.message.utc - e.t)
    }
    if (anchors.isEmpty()) return null
    anchors.sort()
    return anchors[anchors.size / 2]
}

/** A lap from before RaceWall joined, with its UTC time. */
class BackfillLap(val utc: Double, val number: String, val lap: LapTime)

/**
 * Every lap in a TimingData file of the archive or a recording, with its UTC time. `baseUtc` is
 * the UTC time of 00:00:00 when known (a recording's recording.json); otherwise, and for runs of a
 * recording made before that file existed, each run is placed by its race control messages.
 */
fun lapsFromFiles(timingData: String, raceControl: String, baseUtc: Double?): List<BackfillLap> {
    val td = runs(parseStream(timingData))
    val rc = runs(parseStream(raceControl))
    val out = ArrayList<BackfillLap>()
    td.forEachIndexed { i, run ->
        val base = (if (baseUtc != null && td.size == 1) baseUtc else if (rc.size == td.size) runBase(rc[i]) else null) ?: return@forEachIndexed
        val builder = LapHistoryBuilder()
        for (e in run) builder.add(e.t, e.data)
        for (e in builder.events) out.add(BackfillLap(base + e.t, e.number, e.lap))
    }
    return out
}
