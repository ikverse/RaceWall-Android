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

/** Decodes every line of a `.z.jsonStream` file into its JSON payload. */
fun decodeZStream(text: String): List<Any?> {
    val out = ArrayList<Any?>()
    for (line in stripBom(text).split('\n')) {
        val start = line.indexOf('"')
        val end = line.lastIndexOf('"')
        if (start < 0 || end <= start) continue
        try {
            out.add(decodeZ(line.substring(start + 1, end)))
        } catch (_: Exception) {
            // a damaged line; skip it
        }
    }
    return out
}

/** Per car, columns of numbers sorted by time. */
private class Series(width: Int) {
    var size = 0
        private set
    var t = DoubleArray(256)
        private set
    val cols = Array(width) { DoubleArray(256) }

    fun push(time: Double, values: DoubleArray) {
        if (size > 0 && time < t[size - 1]) return // ignore the odd out-of-order sample
        if (size == t.size) {
            t = t.copyOf(size * 2)
            for (i in cols.indices) cols[i] = cols[i].copyOf(size * 2)
        }
        t[size] = time
        for (i in values.indices) cols[i][size] = values[i]
        size++
    }

    /** Index of the last sample at or before `time`. */
    fun indexAt(time: Double): Int {
        var lo = 0
        var hi = size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] <= time) {
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
    private val pos = LinkedHashMap<String, Series>() // x, y, z, onTrack
    private val tel = LinkedHashMap<String, Series>() // speed, rpm, gear, throttle, brake, drs

    private fun series(m: MutableMap<String, Series>, key: String, width: Int) = m.getOrPut(key) { Series(width) }

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
                series(pos, car, 4).push(t, doubleArrayOf(number(v["X"]), number(v["Y"]), number(v["Z"]), if (v["Status"] == "OnTrack") 1.0 else 0.0))
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
            if (i < 0 || t - s.t[i] > STALE_MS) continue
            val j = minOf(i + 1, s.size - 1)
            val span = s.t[j] - s.t[i]
            val k = if (j > i && span > 0 && span < STALE_MS) (t - s.t[i]) / span else 0.0
            fun at(c: Int) = s.cols[c][i] + (s.cols[c][j] - s.cols[c][i]) * k
            out[car] = CarPosition(jsRound(at(0)), jsRound(at(1)), jsRound(at(2)), s.cols[3][i] == 1.0)
        }
        return out
    }

    @Synchronized
    fun telemetryAt(t: Double): CarTelemetryMap? {
        if (tel.isEmpty()) return null
        val out = LinkedHashMap<String, CarTelemetry>()
        for ((car, s) in tel) {
            val i = s.indexAt(t)
            if (i < 0 || t - s.t[i] > STALE_MS) continue
            val c = s.cols
            out[car] = CarTelemetry(c[0][i].toInt(), c[1][i].toInt(), c[2][i].toInt(), c[3][i].toInt(), c[4][i].toInt(), c[5][i].toInt())
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
            while (i < s.size && s.t[i] <= to) {
                if (s.t[i] >= from) {
                    val c = s.cols
                    list.add(TelemetrySample(s.t[i], c[0][i].toInt(), c[1][i].toInt(), c[2][i].toInt(), c[3][i].toInt(), c[4][i].toInt(), c[5][i].toInt()))
                }
                i++
            }
            out[car] = list
        }
        return out
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
            val x0 = s.cols[0][a]
            val y0 = s.cols[1][a]
            var b = -1
            var gap = Double.POSITIVE_INFINITY
            for (i in a + ((last - a) * 0.6).toInt()..last) {
                val d = hypot(s.cols[0][i] - x0, s.cols[1][i] - y0)
                if (d < gap) {
                    gap = d
                    b = i
                }
            }
            if (b < 0) continue
            var on = 0.0
            var length = 0.0
            for (i in a..b) {
                on += s.cols[3][i]
                if (i > a) length += hypot(s.cols[0][i] - s.cols[0][i - 1], s.cols[1][i] - s.cols[1][i - 1])
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
            val x = best.s.cols[0][i]
            val y = best.s.cols[1][i]
            if (x == 0.0 && y == 0.0) continue
            points.add(Triple(x, y, best.s.cols[2][i]))
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }
        return if (points.isEmpty()) null else CircuitOutline(points, Bounds(minX, maxX, minY, maxY))
    }

    companion object {
        /** Builds car data from the two compressed feeds read whole, as for a replay. */
        fun build(positionText: String, carDataText: String, utcToT: (Double) -> Double): CarData {
            val cars = CarData(utcToT)
            for (payload in decodeZStream(positionText)) cars.addPosition(payload)
            for (payload in decodeZStream(carDataText)) cars.addCarData(payload)
            return cars
        }

        /** JavaScript's Math.round: halves go up, towards positive infinity. */
        private fun jsRound(v: Double): Int = Math.floor(v + 0.5).toInt()
    }
}
