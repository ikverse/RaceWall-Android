package com.racewall.archive

import com.racewall.bus.FeedJson
import com.racewall.bus.jsString
import com.racewall.topics.Bounds
import com.racewall.topics.CarPosition
import com.racewall.topics.CarPositions
import com.racewall.topics.CarTelemetry
import com.racewall.topics.CarTelemetryMap
import com.racewall.topics.CircuitOutline
import com.racewall.topics.TelemetrySample
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater
import kotlin.math.hypot

/**
 * Car positions and telemetry, as the desktop's cars.ts. The archive stores these as compressed
 * lines (`.z.jsonStream`): each is base64 of raw-deflated JSON holding several samples, each with its
 * own UTC time. Samples are kept per car in flat number columns, so a whole race fits in a few
 * megabytes.
 */

/** The two large compressed feeds of car positions and telemetry. */
val CAR_STREAMS = listOf("Position.z", "CarData.z")

/** Decodes one compressed payload: base64 of raw-deflated JSON. */
fun decodeZ(base64: String): Any? {
    val bytes = Base64.getMimeDecoder().decode(base64)
    val inflater = Inflater(true)
    try {
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream(bytes.size * 6)
        val buf = ByteArray(16 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
        }
        return FeedJson.parse(out.toString(Charsets.UTF_8.name()))
    } finally {
        inflater.end()
    }
}

/**
 * Decodes each line of a `.z.jsonStream` file into its JSON payload, one line at a time as it is
 * read: a race's decoded payloads are far larger than the app's memory, so they are never held
 * together.
 */
fun decodeZStream(text: String): Sequence<Any?> = sequence {
    val body = stripBom(text)
    var from = 0
    while (from < body.length) {
        val nl = body.indexOf('\n', from).let { if (it < 0) body.length else it }
        val start = body.indexOf('"', from)
        val end = body.lastIndexOf('"', nl - 1)
        from = nl + 1
        if (start < 0 || start >= nl || end <= start) continue
        val payload = try {
            decodeZ(body.substring(start + 1, end))
        } catch (_: Exception) {
            continue // a damaged line; skip it
        }
        yield(payload)
    }
}

/**
 * Per car, columns of numbers sorted by time, kept small: a race has about 36,000 samples per car
 * in each feed. Times are whole session milliseconds (UTC minus a whole-millisecond anchor), and
 * values are whole numbers well inside a float's exact range, so nothing is lost.
 */
private class Series(width: Int, withFlag: Boolean = false) {
    var size = 0
        private set
    private var times = IntArray(256)
    private val cols = Array(width) { FloatArray(256) }
    private var flags = if (withFlag) ByteArray(256) else null

    fun t(i: Int): Double = times[i].toDouble()
    fun v(col: Int, i: Int): Double = cols[col][i].toDouble()
    fun flag(i: Int): Boolean = flags!![i] == 1.toByte()

    fun push(time: Double, values: DoubleArray, flag: Boolean = false) {
        val ms = Math.round(time).toInt()
        if (size > 0 && ms < times[size - 1]) return // ignore the odd out-of-order sample
        if (size == times.size) resize(size + (size shr 1))
        times[size] = ms
        for (i in values.indices) cols[i][size] = values[i].toFloat()
        flags?.let { it[size] = if (flag) 1 else 0 }
        size++
    }

    /** Drops the unused room at the end, once no more samples are coming. */
    fun trim() {
        if (size < times.size) resize(maxOf(size, 1))
    }

    private fun resize(n: Int) {
        times = times.copyOf(n)
        for (i in cols.indices) cols[i] = cols[i].copyOf(n)
        flags = flags?.copyOf(n)
    }

    /** Index of the last sample at or before `time`. */
    fun indexAt(time: Double): Int {
        var lo = 0
        var hi = size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= time) {
                found = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        return found
    }
}

/** Samples older than this are treated as missing, so a car that stopped sending disappears. */
private const val STALE_MS = 5000.0

/**
 * Car data that grows as payloads arrive. `utcToT` turns a sample's UTC milliseconds into session
 * time (NaN when there is no anchor yet).
 */
class CarData(private val utcToT: (Double) -> Double) {
    private val pos = LinkedHashMap<String, Series>() // x, y, z; the flag is on track
    private val tel = LinkedHashMap<String, Series>() // speed, rpm, gear, throttle, brake, drs

    private fun series(m: MutableMap<String, Series>, key: String, width: Int, withFlag: Boolean = false) = m.getOrPut(key) { Series(width, withFlag) }

    private fun number(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() ?: 0.0 }
        else -> 0.0
    }.let { if (it.isNaN()) 0.0 else it }

    private fun time(text: Any?): Double = parseUtc(jsString(text ?: ""))?.let { utcToT(it.toDouble()) } ?: Double.NaN

    /** Adds one decoded Position.z payload. */
    @Synchronized
    fun addPosition(payload: Any?) {
        for (sample in (obj(payload)["Position"] as? List<*>) ?: emptyList<Any?>()) {
            val p = obj(sample)
            val t = time(p["Timestamp"])
            if (t.isNaN()) continue
            for ((car, e) in obj(p["Entries"])) {
                val v = obj(e)
                series(pos, car, 3, withFlag = true).push(t, doubleArrayOf(number(v["X"]), number(v["Y"]), number(v["Z"])), v["Status"] == "OnTrack")
            }
        }
    }

    /** Adds one decoded CarData.z payload. */
    @Synchronized
    fun addCarData(payload: Any?) {
        for (entry in (obj(payload)["Entries"] as? List<*>) ?: emptyList<Any?>()) {
            val e = obj(entry)
            val t = time(e["Utc"])
            if (t.isNaN()) continue
            for ((car, c) in obj(e["Cars"])) {
                val ch = obj(obj(c)["Channels"])
                // Channel numbers as the feed uses them: 0 RPM, 2 speed, 3 gear, 4 throttle, 5 brake, 45 DRS.
                series(tel, car, 6).push(t, doubleArrayOf(number(ch["2"]), number(ch["0"]), number(ch["3"]), number(ch["4"]), number(ch["5"]), number(ch["45"])))
            }
        }
    }

    @Synchronized
    fun positionsAt(t: Double): CarPositions? {
        if (pos.isEmpty()) return null
        val out = LinkedHashMap<String, CarPosition>()
        for ((car, s) in pos) {
            val i = s.indexAt(t)
            if (i < 0 || t - s.t(i) > STALE_MS) continue
            val j = minOf(i + 1, s.size - 1)
            val span = s.t(j) - s.t(i)
            val k = if (j > i && span > 0 && span < STALE_MS) (t - s.t(i)) / span else 0.0
            fun at(c: Int) = s.v(c, i) + (s.v(c, j) - s.v(c, i)) * k
            out[car] = CarPosition(jsRound(at(0)), jsRound(at(1)), jsRound(at(2)), s.flag(i))
        }
        return out
    }

    @Synchronized
    fun telemetryAt(t: Double): CarTelemetryMap? {
        if (tel.isEmpty()) return null
        val out = LinkedHashMap<String, CarTelemetry>()
        for ((car, s) in tel) {
            val i = s.indexAt(t)
            if (i < 0 || t - s.t(i) > STALE_MS) continue
            fun c(n: Int) = s.v(n, i).toInt()
            out[car] = CarTelemetry(c(0), c(1), c(2), c(3), c(4), c(5))
        }
        return out
    }

    @Synchronized
    fun telemetryRange(from: Double, to: Double, cars: List<String>?): Map<String, List<TelemetrySample>>? {
        if (tel.isEmpty()) return null
        val out = LinkedHashMap<String, List<TelemetrySample>>()
        for ((car, s) in tel) {
            if (cars != null && car !in cars) continue
            val list = ArrayList<TelemetrySample>()
            var i = maxOf(0, s.indexAt(from))
            while (i < s.size && s.t(i) <= to) {
                if (s.t(i) >= from) {
                    fun c(n: Int) = s.v(n, i).toInt()
                    list.add(TelemetrySample(s.t(i), c(0), c(1), c(2), c(3), c(4), c(5)))
                }
                i++
            }
            out[car] = list
        }
        return out
    }

    /** Drops the unused room in every column, once a replay's files are fully read. */
    @Synchronized
    fun trim() {
        pos.values.forEach(Series::trim)
        tel.values.forEach(Series::trim)
    }

    private class Candidate(val s: Series, val a: Int, val b: Int, val length: Double, val gap: Double)

    /**
     * One lap of the racing line. A clean lap closes on itself: its last point is next to its first,
     * and it covers about a lap's distance. A parked car "closes" too, and a car through the pit lane
     * or behind the safety car drives a different distance, so only cars near the typical distance
     * count.
     */
    @Synchronized
    fun outline(fromT: Double, lapMs: Double): CircuitOutline? {
        val candidates = ArrayList<Candidate>()
        for (s in pos.values) {
            val a = maxOf(0, s.indexAt(fromT))
            val last = s.indexAt(fromT + lapMs * 1.3)
            if (last - a < 40) continue
            // End where the car comes back closest to where it started, after most of a lap.
            val x0 = s.v(0, a)
            val y0 = s.v(1, a)
            var b = -1
            var gap = Double.POSITIVE_INFINITY
            for (i in a + ((last - a) * 0.6).toInt()..last) {
                val d = hypot(s.v(0, i) - x0, s.v(1, i) - y0)
                if (d < gap) {
                    gap = d
                    b = i
                }
            }
            if (b < 0) continue
            var on = 0.0
            var length = 0.0
            for (i in a..b) {
                if (s.flag(i)) on += 1.0
                if (i > a) length += hypot(s.v(0, i) - s.v(0, i - 1), s.v(1, i) - s.v(1, i - 1))
            }
            if (on < (b - a) * 0.95) continue
            candidates.add(Candidate(s, a, b, length, gap))
        }
        val lengths = candidates.map { it.length }.sorted()
        val typical = lengths.getOrNull(lengths.size / 2) ?: 0.0
        val moving = candidates.filter { typical > 0 && it.length > typical * 0.85 && it.length < typical * 1.25 }
        // Most cars do not pit on any given lap, so the lap closest to the typical distance is a
        // racing lap; a pit-lane lap is a few percent shorter.
        val best = moving.filter { it.gap <= it.length * 0.03 }.minByOrNull { kotlin.math.abs(it.length - typical) } ?: return null
        val points = ArrayList<Triple<Double, Double, Double>>()
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (i in best.a..best.b) {
            val x = best.s.v(0, i)
            val y = best.s.v(1, i)
            if (x == 0.0 && y == 0.0) continue
            points.add(Triple(x, y, best.s.v(2, i)))
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }
        return if (points.isEmpty()) null else CircuitOutline(points, Bounds(minX, maxX, minY, maxY))
    }

    companion object {
        /** Builds car data from the two compressed feeds read whole, as for a replay, one line at a time. */
        fun build(positionText: String, carDataText: String, utcToT: (Double) -> Double): CarData {
            val cars = CarData(utcToT)
            for (payload in decodeZStream(positionText)) cars.addPosition(payload)
            for (payload in decodeZStream(carDataText)) cars.addCarData(payload)
            cars.trim()
            return cars
        }

        /** JavaScript's Math.round: halves go up, towards positive infinity. */
        private fun jsRound(v: Double): Int = Math.floor(v + 0.5).toInt()
    }
}

/**
 * A circuit outline as JSON text, saved so the next session at the same circuit can show it straight
 * away, before its car positions have arrived.
 */
object OutlineText {
    fun write(o: CircuitOutline): String = FeedJson.write(
        mapOf(
            "points" to o.points.map { (x, y, z) -> listOf(x, y, z) },
            "bounds" to listOf(o.bounds.minX, o.bounds.maxX, o.bounds.minY, o.bounds.maxY),
            "start" to o.start,
            "sectors" to o.sectors?.let { listOf(it.first, it.second) },
        ),
    )

    /** The outline in `text`, or null when it is not one. */
    fun read(text: String): CircuitOutline? = try {
        val m = FeedJson.parse(text) as Map<*, *>
        fun num(v: Any?) = (v as Number).toDouble()
        val points = (m["points"] as List<*>).map { p -> (p as List<*>).let { Triple(num(it[0]), num(it[1]), num(it[2])) } }
        val b = (m["bounds"] as List<*>).map(::num)
        val sectors = (m["sectors"] as? List<*>)?.let { (it[0] as Number).toInt() to (it[1] as Number).toInt() }
        if (points.isEmpty()) null else CircuitOutline(points, Bounds(b[0], b[1], b[2], b[3]), (m["start"] as? Number)?.toInt(), sectors)
    } catch (_: Exception) {
        null
    }
}
