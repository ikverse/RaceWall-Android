package com.racewall.archive

import com.racewall.bus.Bus
import com.racewall.bus.FeedJson
import com.racewall.bus.Timeline
import com.racewall.bus.TopicProvider
import com.racewall.bus.mergeFeed
import com.racewall.topics.CircuitOutline
import com.racewall.topics.SessionInfo
import com.racewall.topics.Topic
import com.racewall.topics.WeatherSample
import kotlin.math.hypot

/** The source name RaceWall's bus knows this reader by. */
const val SOURCE = "f1-archive"

/** The feeds read for every session, by file name without `.jsonStream`. */
val STREAMS = listOf(
    "SessionStatus",
    "TrackStatus",
    "LapCount",
    "DriverList",
    "TimingData",
    "TimingAppData",
    "RaceControlMessages",
    "WeatherData",
    "TeamRadio",
)

class SessionFiles(
    /** Contents of SessionInfo.json. */
    val sessionInfo: String,
    /** Contents of each `.jsonStream`; a feed the archive does not have is an empty string. */
    val streams: Map<String, String>,
)

/** The two compressed car feeds, by name ("Position.z", "CarData.z"). */
typealias CarFiles = Map<String, String>

private const val ARCHIVE = "https://livetiming.formula1.com/static/"

/**
 * One session's data, built up record by record, as the desktop's SessionModel. A replay adds every
 * record at once; live timing adds them as they arrive. Either way the same topics are offered on
 * the bus, under `source`.
 */
class SessionModel(
    private val bus: Bus,
    private val source: String,
    info: Any?,
    /**
     * When known (live timing), the UTC time of session time 0. Otherwise it is worked out from the
     * UTC times race control messages carry.
     */
    private val base: Double? = null,
    /** Wall-clock milliseconds, for retrying the circuit outline; tests pass a fake. */
    private val now: () -> Long = System::currentTimeMillis,
) {
    var info: SessionInfo = Normalize.sessionInfo(info)
        private set

    /** First and last moment with data, in ms from the start of the recording. */
    var start = Double.POSITIVE_INFINITY
    var end = 0.0
        private set

    /** When the session itself started (lights out, or the pit exit opening), if it did. */
    var startedAt: Double? = null
        private set

    private var rawInfo: Any? = info ?: LinkedHashMap<String, Any?>()
    private val tl: Map<String, Timeline<Any?, Any?>> =
        // Keyframes kept as compact JSON text: a race's full-state copies are far too large as objects.
        STREAMS.associateWith {
            Timeline<Any?, Any?>({ LinkedHashMap<String, Any?>() }, { s, d -> mergeFeed(s, d) }, pack = FeedJson::write, unpack = { FeedJson.parse(it as String) })
        }
    private val history = LapHistoryBuilder()
    private val messages = ArrayList<TimedMessage>()
    private val weather = ArrayList<WeatherEntry>()
    private val clips = ArrayList<TimedClip>()
    private var cars: CarData? = null
    private var outline: CircuitOutline? = null
    private var outlineTried = Long.MIN_VALUE / 2
    private val anchors = ArrayList<Double>()

    /** Whether car positions and telemetry are attached. */
    val hasCars: Boolean get() = cars != null

    fun setInfo(raw: Any?) {
        rawInfo = raw ?: LinkedHashMap<String, Any?>()
        info = Normalize.sessionInfo(raw)
    }

    /** Adds one record of a feed at session time `t`. Car feeds may come as base64 text. */
    fun add(stream: String, t: Double, data: Any?) {
        if (stream == "Position.z" || stream == "CarData.z") {
            val payload = if (data is String) decodeZ(data) else data
            val c = ensureCars()
            if (stream == "Position.z") c.addPosition(payload) else c.addCarData(payload)
            return
        }
        if (stream == "SessionInfo") {
            setInfo(mergeFeed(rawInfo, data))
            return
        }
        val timeline = tl[stream] ?: return
        // Live records can arrive a few milliseconds out of order; keep each feed's times rising.
        val at = maxOf(t, timeline.end ?: t)
        timeline.append(at, data)
        synchronized(this) {
            start = minOf(start, at)
            end = maxOf(end, at)
            when (stream) {
                "TimingData" -> history.add(at, data)
                "RaceControlMessages" -> for (m in raceControlMessages(at, data)) {
                    messages.add(m)
                    if (base == null && m.message.utc != 0L && anchors.size < 15) anchors.add(m.message.utc - at)
                }
                "WeatherData" -> weather.add(weatherSample(at, data))
                "TeamRadio" -> clips.addAll(radioClips(at, data, ARCHIVE + info.path))
                "SessionStatus" -> if (startedAt == null && (data as? Map<*, *>)?.get("Status") == "Started") startedAt = at
            }
        }
    }

    /** When a feed last changed at or before `t`, or null. Live, this tells when the leader crossed the line. */
    fun lastChange(stream: String, t: Double): Double? {
        val timeline = tl[stream] ?: return null
        return if (timeline.indexAt(t) < 0) null else timeline.timesBetween(Double.NEGATIVE_INFINITY, t).lastOrNull()
    }

    /** Converts a session time to UTC milliseconds, or null when there is no anchor. */
    @Synchronized
    fun utcAt(t: Double): Double? {
        if (base != null) return base + t
        if (anchors.isEmpty()) return null
        val sorted = anchors.sorted()
        return sorted[sorted.size / 2] + t
    }

    /** The merged state of one feed at `t`, read under the timeline's lock and never kept. */
    private fun state(stream: String, t: Double): Any? = tl.getValue(stream).let { synchronized(it) { it.stateAt(t) } }

    private fun <T> upTo(list: List<T>, t: Double, time: (T) -> Double): List<T> = synchronized(this) { list.subList(0, lastAt(list, t, time) + 1).toList() }

    /** Offers every topic on the bus. Call once, after the first records. */
    fun register() {
        val views: Map<String, (Double) -> Any?> = mapOf(
            Topic.SESSION_INFO to { _ -> info },
            Topic.SESSION_STATUS to { t -> Normalize.sessionStatus(state("SessionStatus", t), state("TrackStatus", t), state("LapCount", t)) },
            Topic.DRIVERS_LIST to { t -> Normalize.drivers(state("DriverList", t)) },
            Topic.TIMING_POSITIONS to { t -> Normalize.positions(state("TimingData", t)) },
            Topic.TIMING_LAPS to { t -> Normalize.laps(state("TimingData", t)) },
            Topic.TIMING_STINTS to { t -> Normalize.stints(state("TimingAppData", t)) },
            Topic.LAP_HISTORY to { t -> if (tl.getValue("TimingData").length > 0) history.at(t) else null },
            Topic.RACE_CONTROL to { t -> if (messages.isNotEmpty()) upTo(messages, t) { it.t }.map { it.message } else null },
            Topic.WEATHER to { t ->
                synchronized(this) {
                    val i = lastAt(weather, t) { it.t }
                    if (i < 0) null else weather[i].full
                }
            },
            Topic.WEATHER_HISTORY to { _ ->
                synchronized(this) { if (weather.isEmpty()) null else weather.map { WeatherSample(it.t, it.full.airTemp, it.full.trackTemp, it.full.rainfall) } }
            },
            Topic.RADIO to { t -> if (tl.getValue("TeamRadio").length > 0) upTo(clips, t) { it.t }.map { it.clip } else null },
        )
        bus.removeSource(source)
        for ((name, view) in views) bus.register(name, source, view)
        if (cars != null) registerCars()
    }

    /** Adds car positions, telemetry and the circuit outline, from the compressed archive feeds. */
    fun attachCars(files: CarFiles) {
        val utc0 = utcAt(0.0) ?: return
        cars = CarData.build(files["Position.z"] ?: "", files["CarData.z"] ?: "") { it - utc0 }
        outline = null
        outlineTried = Long.MIN_VALUE / 2
        registerCars()
    }

    private fun ensureCars(): CarData {
        cars?.let { return it }
        val c = CarData { utc -> utcAt(0.0)?.let { utc - it } ?: Double.NaN }
        cars = c
        registerCars()
        return c
    }

    private fun registerCars() {
        val c = cars ?: return
        bus.provide(Topic.CAR_POSITION, TopicProvider(source, { t -> c.positionsAt(t) }))
        bus.provide(Topic.CAR_TELEMETRY, TopicProvider(source, { t -> c.telemetryAt(t) }, { from, to, filter -> c.telemetryRange(from, to, filter) }))
        bus.provide(Topic.CIRCUIT, TopicProvider(source, { _ -> circuitOutline() }))
    }

    /**
     * One lap of the racing line. The typical lap time is the fastest quarter of laps, so slow laps
     * behind the safety car do not stretch the window. Live, it is retried every ten seconds until a
     * clean lap has been driven.
     */
    @Synchronized
    private fun circuitOutline(): CircuitOutline? {
        outline?.let { return it }
        val c = cars ?: return null
        val time = now()
        if (time - outlineTried < 10_000) return null
        outlineTried = time
        val times = history.events.mapNotNull { it.lap.ms }.sorted()
        val typical = if (times.isNotEmpty()) times[times.size / 4].toDouble() else 100_000.0
        val from = startedAt ?: if (start.isFinite()) start else 0.0
        for (laps in listOf(5, 10, 20, 30, 2, 1)) {
            val o = c.outline(from + typical * laps, typical) ?: continue
            return mark(o, c).also { outline = it }
        }
        return null
    }

    /**
     * Marks the start line and the sector boundaries on the outline: where each car was when its lap
     * count or sector time came through, snapped to the nearest outline point, the median of them
     * all. The timing feed runs a little behind the cars, so a mark can sit a few metres down the
     * track. Left out until five crossings have been seen.
     */
    private fun mark(o: CircuitOutline, c: CarData): CircuitOutline {
        val n = o.points.size
        fun nearest(x: Double, y: Double): Int {
            var best = 0
            var gap = Double.POSITIVE_INFINITY
            for (i in 0 until n) {
                val d = hypot(o.points[i].first - x, o.points[i].second - y)
                if (d < gap) {
                    gap = d
                    best = i
                }
            }
            return best
        }
        fun median(events: List<Pair<Double, String>>): Int? {
            val idx = ArrayList<Int>()
            for ((t, number) in events) {
                val p = c.positionsAt(t)?.get(number) ?: continue
                if (p.onTrack) idx.add(nearest(p.x.toDouble(), p.y.toDouble()))
            }
            if (idx.size < 5) return null
            idx.sort()
            // The outline is a loop: a mark near its first point has crossings on both ends of it.
            val wrapped = if (idx.last() - idx.first() > n / 2.0) idx.map { if (it < n / 2.0) it + n else it }.sorted() else idx
            return wrapped[wrapped.size / 2] % n
        }
        val startLine = median(history.events.map { it.t to it.number })
        val s1 = median(history.sectorEvents.filter { it.sector == 0 }.map { it.t to it.number })
        val s2 = median(history.sectorEvents.filter { it.sector == 1 }.map { it.t to it.number })
        return o.copy(start = startLine ?: o.start, sectors = if (s1 != null && s2 != null) s1 to s2 else o.sectors)
    }

    companion object {
        /** The UTC time of each race control message in a record, in ms; 0 when unreadable. */
        fun messageTimes(data: Any?): List<Double> = raceControlMessages(0.0, data).map { it.message.utc.toDouble() }
    }
}

/** Builds a replay from archive files and offers its topics as the "f1-archive" source. */
fun buildSession(files: SessionFiles, bus: Bus): SessionModel {
    val model = SessionModel(bus, SOURCE, FeedJson.parse(stripBom(files.sessionInfo)))
    // Race control first, so the UTC anchor exists before anything needs it.
    val order = listOf("RaceControlMessages") + STREAMS.filter { it != "RaceControlMessages" }
    for (name in order) for (entry in parseStream(files.streams[name] ?: "")) model.add(name, entry.t, entry.data)
    if (!model.start.isFinite()) model.start = 0.0
    model.register()
    return model
}
