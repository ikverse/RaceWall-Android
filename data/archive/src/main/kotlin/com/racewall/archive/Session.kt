package com.racewall.archive

import com.racewall.bus.Bus
import com.racewall.bus.FeedJson
import com.racewall.bus.Timeline
import com.racewall.bus.mergeFeed
import com.racewall.topics.SessionInfo
import com.racewall.topics.Topic
import java.time.Instant

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

/**
 * One session's data, built up record by record. A replay adds every record at once; live timing
 * adds them as they arrive. Either way the same topics are offered on the bus, under `source`.
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
        STREAMS.associateWith { Timeline<Any?, Any?>({ LinkedHashMap<String, Any?>() }, { s, d -> mergeFeed(s, d) }) }
    private val anchors = ArrayList<Double>()

    /** Adds one record of a feed at session time `t`. */
    fun add(stream: String, t: Double, data: Any?) {
        if (stream == "SessionInfo") {
            rawInfo = mergeFeed(rawInfo, data)
            info = Normalize.sessionInfo(rawInfo)
            return
        }
        val timeline = tl[stream] ?: return
        // Live records can arrive a few milliseconds out of order; keep each feed's times rising.
        val at = maxOf(t, timeline.end ?: t)
        timeline.append(at, data)
        start = minOf(start, at)
        end = maxOf(end, at)

        if (stream == "RaceControlMessages") {
            if (base == null) for (utc in messageTimes(data)) if (utc != 0.0 && anchors.size < 15) anchors.add(utc - at)
        } else if (stream == "SessionStatus" && startedAt == null && (data as? Map<*, *>)?.get("Status") == "Started") startedAt = at
    }

    /** Converts a session time to UTC milliseconds, or null when there is no anchor. */
    fun utcAt(t: Double): Double? {
        if (base != null) return base + t
        if (anchors.isEmpty()) return null
        val sorted = anchors.sorted()
        return sorted[sorted.size / 2] + t
    }

    /** Offers every topic on the bus. Call once, after the first records. */
    fun register() {
        val views: Map<String, (Double) -> Any?> = mapOf(
            Topic.SESSION_INFO to { _ -> info },
            Topic.SESSION_STATUS to { t ->
                Normalize.sessionStatus(state("SessionStatus", t), state("TrackStatus", t), state("LapCount", t))
            },
            Topic.DRIVERS_LIST to { t -> Normalize.drivers(state("DriverList", t)) },
            Topic.TIMING_POSITIONS to { t -> Normalize.positions(state("TimingData", t)) },
            Topic.TIMING_LAPS to { t -> Normalize.laps(state("TimingData", t)) },
            Topic.TIMING_STINTS to { t -> Normalize.stints(state("TimingAppData", t)) },
        )
        bus.removeSource(source)
        for ((name, view) in views) bus.register(name, source, view)
    }

    /**
     * The merged state of one feed at `t`. Normalising reads it straight away and builds fresh
     * values, so it is read under the timeline's lock and never kept.
     */
    private fun state(stream: String, t: Double): Any? = tl.getValue(stream).let { synchronized(it) { it.stateAt(t) } }

    companion object {
        private val Z = Regex("Z?$")

        /** The UTC time of each race control message in a record, in ms; 0 when unreadable. */
        @Suppress("UNCHECKED_CAST")
        fun messageTimes(data: Any?): List<Double> {
            val messages = (data as? Map<String, Any?>)?.get("Messages")
            val items = when (messages) {
                is List<*> -> messages
                is Map<*, *> -> messages.values.toList()
                else -> emptyList()
            }
            return items.map { raw ->
                val text = ((raw as? Map<String, Any?>)?.get("Utc") as? String).orEmpty()
                try {
                    Instant.parse(text.replaceFirst(Z, "Z")).toEpochMilli().toDouble()
                } catch (_: Exception) {
                    0.0
                }
            }
        }
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
