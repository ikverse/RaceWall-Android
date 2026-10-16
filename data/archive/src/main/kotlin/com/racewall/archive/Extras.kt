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

    @Synchronized
    fun add(t: Double, data: Any?) {
        val lines = obj(obj(data)["Lines"])
        state = mergeFeed(state, data)
        val wasFirst = first
        first = false
        for (number in lines.keys) {
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
            events.add(LapEvent(t, number, LapTime(lap, time, lapMs(time))))
        }
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
