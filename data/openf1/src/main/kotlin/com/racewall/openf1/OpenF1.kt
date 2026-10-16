package com.racewall.openf1

import com.racewall.archive.Fetcher
import com.racewall.archive.OkHttpFetcher
import com.racewall.archive.lastAt
import com.racewall.archive.parseUtc
import com.racewall.bus.Bus
import com.racewall.bus.FeedJson
import com.racewall.bus.TopicProvider
import com.racewall.bus.jsString
import com.racewall.topics.CarPosition
import com.racewall.topics.CarTelemetry
import com.racewall.topics.LapTime
import com.racewall.topics.RaceControlMessage
import com.racewall.topics.RadioClip
import com.racewall.topics.TelemetrySample
import com.racewall.topics.Topic
import com.racewall.topics.Weather
import com.racewall.topics.WeatherSample
import java.io.IOException
import kotlin.math.roundToLong

/**
 * OpenF1 (api.openf1.org) history, as a backup data source, as the desktop's packages/openf1. It is
 * asked only for topics that F1's own archive could not provide for the loaded session, so in the
 * normal case it makes no requests. Session keys are the same as F1's.
 */
object OpenF1 {
    const val SOURCE = "openf1"
    const val BASE = "https://api.openf1.org/v1/"

    /** The topics this source can stand in for. */
    val BACKUP_TOPICS = listOf(
        Topic.WEATHER,
        Topic.WEATHER_HISTORY,
        Topic.RACE_CONTROL,
        Topic.RADIO,
        Topic.LAP_HISTORY,
        Topic.CAR_POSITION,
        Topic.CAR_TELEMETRY,
    )

    private fun n(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }.let { if (it.isNaN()) 0.0 else it }

    /** Seconds to the feed's lap time text, "1:28.123". */
    fun lapText(seconds: Double): String {
        val m = Math.floor(seconds / 60).toInt()
        val s = "%.3f".format(java.util.Locale.ROOT, seconds - m * 60).padStart(6, '0')
        return if (m != 0) "$m:$s" else s.replaceFirst(Regex("^0"), "")
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun rows(path: String, fetcher: Fetcher): List<Map<String, Any?>> {
        val res = fetcher.get(BASE + path, emptyMap())
        if (!res.ok) throw IOException("OpenF1: ${res.status} for $path")
        return (FeedJson.parse(res.body ?: "[]") as? List<Any?>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
    }

    private class At<T>(val t: Double, val v: T)

    /**
     * Loads `topics` from OpenF1 for one session and offers them on the bus as the "openf1" source.
     * `utcToT` turns UTC milliseconds into session time, from the archive's anchor. Returns the
     * topics it could provide.
     */
    suspend fun provide(bus: Bus, topics: List<String>, sessionKey: Int, utcToT: (Double) -> Double, fetcher: Fetcher = OkHttpFetcher()): List<String> {
        val key = sessionKey
        fun t(date: Any?) = parseUtc(jsString(date ?: ""))?.let { utcToT(it.toDouble()) } ?: Double.NaN
        val want = topics.toSet()
        val offered = ArrayList<String>()
        fun offer(topic: String, range: ((Double, Double, List<String>?) -> Any?)? = null, at: (Double) -> Any?) {
            bus.provide(topic, TopicProvider(SOURCE, at, range))
            offered.add(topic)
        }
        fun <T> upTo(list: List<At<T>>, time: Double) = list.subList(0, lastAt(list, time) { it.t } + 1).map { it.v }

        if (Topic.WEATHER in want || Topic.WEATHER_HISTORY in want) {
            val list = rows("weather?session_key=$key", fetcher).map { r ->
                At(
                    t(r["date"]),
                    Weather(
                        airTemp = n(r["air_temperature"]),
                        trackTemp = n(r["track_temperature"]),
                        humidity = n(r["humidity"]),
                        pressure = n(r["pressure"]),
                        rainfall = n(r["rainfall"]) > 0,
                        windSpeed = n(r["wind_speed"]),
                        windDirection = n(r["wind_direction"]),
                    ),
                )
            }.sortedBy { it.t }
            if (list.isNotEmpty()) {
                if (Topic.WEATHER in want) offer(Topic.WEATHER) { time -> list.getOrNull(lastAt(list, time) { it.t })?.v }
                if (Topic.WEATHER_HISTORY in want) {
                    val history = list.map { WeatherSample(it.t, it.v.airTemp, it.v.trackTemp, it.v.rainfall) }
                    offer(Topic.WEATHER_HISTORY) { history }
                }
            }
        }

        if (Topic.RACE_CONTROL in want) {
            val list = rows("race_control?session_key=$key", fetcher).map { r ->
                At(
                    t(r["date"]),
                    RaceControlMessage(
                        utc = parseUtc(jsString(r["date"] ?: "")) ?: 0L,
                        lap = (r["lap_number"] as? Number)?.toInt(),
                        category = jsString(r["category"] ?: ""),
                        flag = r["flag"]?.let(::jsString)?.ifEmpty { null },
                        message = jsString(r["message"] ?: ""),
                    ),
                )
            }.sortedBy { it.t }
            if (list.isNotEmpty()) offer(Topic.RACE_CONTROL) { time -> upTo(list, time) }
        }

        if (Topic.RADIO in want) {
            val list = rows("team_radio?session_key=$key", fetcher).map { r ->
                At(t(r["date"]), RadioClip(parseUtc(jsString(r["date"] ?: "")) ?: 0L, jsString(r["driver_number"] ?: ""), jsString(r["recording_url"] ?: "")))
            }.sortedBy { it.t }
            if (list.isNotEmpty()) offer(Topic.RADIO) { time -> upTo(list, time) }
        }

        if (Topic.LAP_HISTORY in want) {
            val list = rows("laps?session_key=$key", fetcher)
                .filter { it["lap_duration"] is Number && it["date_start"] != null && it["date_start"] != "" }
                .map { r ->
                    val seconds = n(r["lap_duration"])
                    At(t(r["date_start"]) + seconds * 1000, jsString(r["driver_number"]) to LapTime(n(r["lap_number"]).toInt(), lapText(seconds), (seconds * 1000).roundToLong()))
                }.sortedBy { it.t }
            if (list.isNotEmpty()) offer(Topic.LAP_HISTORY) { time ->
                val out = LinkedHashMap<String, MutableList<LapTime>>()
                for (i in 0..lastAt(list, time) { it.t }) out.getOrPut(list[i].v.first) { ArrayList() }.add(list[i].v.second)
                out
            }
        }

        if (Topic.CAR_POSITION in want || Topic.CAR_TELEMETRY in want) {
            val drivers = rows("drivers?session_key=$key", fetcher).map { jsString(it["driver_number"]) }
            if (Topic.CAR_POSITION in want) {
                val per = LinkedHashMap<String, List<At<CarPosition>>>()
                for (d in drivers) {
                    val list = rows("location?session_key=$key&driver_number=$d", fetcher)
                        .map { r -> At(t(r["date"]), CarPosition(n(r["x"]).toInt(), n(r["y"]).toInt(), n(r["z"]).toInt(), true)) }
                        .sortedBy { it.t }
                    if (list.isNotEmpty()) per[d] = list
                }
                if (per.isNotEmpty()) offer(Topic.CAR_POSITION) { time ->
                    val out = LinkedHashMap<String, CarPosition>()
                    for ((d, list) in per) {
                        val i = lastAt(list, time) { it.t }
                        if (i >= 0 && time - list[i].t < 5000) out[d] = list[i].v
                    }
                    out
                }
            }
            if (Topic.CAR_TELEMETRY in want) {
                val per = LinkedHashMap<String, List<TelemetrySample>>()
                for (d in drivers) {
                    val list = rows("car_data?session_key=$key&driver_number=$d", fetcher).map { r ->
                        TelemetrySample(t(r["date"]), n(r["speed"]).toInt(), n(r["rpm"]).toInt(), n(r["n_gear"]).toInt(), n(r["throttle"]).toInt(), n(r["brake"]).toInt(), n(r["drs"]).toInt())
                    }.sortedBy { it.t }
                    if (list.isNotEmpty()) per[d] = list
                }
                if (per.isNotEmpty()) offer(
                    Topic.CAR_TELEMETRY,
                    range = { from, to, filter -> per.filterKeys { filter == null || it in filter }.mapValues { (_, l) -> l.filter { it.t >= from && it.t <= to } } },
                ) { time ->
                    val out = LinkedHashMap<String, CarTelemetry>()
                    for ((d, list) in per) {
                        val i = lastAt(list, time) { it.t }
                        if (i >= 0 && time - list[i].t < 5000) list[i].let { out[d] = CarTelemetry(it.speed, it.rpm, it.gear, it.throttle, it.brake, it.drs) }
                    }
                    out
                }
            }
        }
        return offered
    }
}
