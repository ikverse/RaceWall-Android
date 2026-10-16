package com.racewall.jolpica

import com.racewall.archive.Fetcher
import com.racewall.archive.OkHttpFetcher
import com.racewall.archive.parseUtc
import com.racewall.bus.Bus
import com.racewall.bus.FeedJson
import com.racewall.bus.TopicProvider
import com.racewall.bus.jsString
import com.racewall.topics.DriverStanding
import com.racewall.topics.Schedule
import com.racewall.topics.ScheduledSession
import com.racewall.topics.Standings
import com.racewall.topics.TeamStanding
import com.racewall.topics.Topic
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.IOException

/**
 * Jolpica (api.jolpi.ca), the community successor to the Ergast API: results, standings and season
 * schedules back to 1950, as the desktop's packages/jolpica. RaceWall uses it for championship
 * standings and the schedule.
 */
object Jolpica {
    const val SOURCE = "jolpica"
    const val BASE = "https://api.jolpi.ca/ergast/f1/"

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()
    private fun arr(v: Any?): List<Map<String, Any?>> = (v as? List<*>)?.map(::obj) ?: emptyList()
    private fun num(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
    private fun text(v: Any?) = if (v == null) "" else jsString(v)

    private fun standingsList(json: Any?): Map<String, Any?> = arr(obj(obj(obj(json)["MRData"])["StandingsTable"])["StandingsLists"]).firstOrNull() ?: emptyMap()

    fun parseDriverStandings(json: Any?): Standings<DriverStanding>? {
        val list = standingsList(json)
        val rows = arr(list["DriverStandings"]).map { d ->
            val driver = obj(d["Driver"])
            DriverStanding(
                position = num(d["position"]).toInt(),
                points = num(d["points"]),
                wins = num(d["wins"]).toInt(),
                code = text(driver["code"]),
                name = "${text(driver["givenName"])} ${text(driver["familyName"])}".trim(),
                team = text(arr(d["Constructors"]).lastOrNull()?.get("name")),
            )
        }
        if (rows.isEmpty()) return null
        return Standings(num(list["season"]).toInt(), num(list["round"]).toInt(), rows)
    }

    fun parseTeamStandings(json: Any?): Standings<TeamStanding>? {
        val list = standingsList(json)
        val rows = arr(list["ConstructorStandings"]).map { c ->
            TeamStanding(num(c["position"]).toInt(), num(c["points"]), num(c["wins"]).toInt(), text(obj(c["Constructor"])["name"]))
        }
        if (rows.isEmpty()) return null
        return Standings(num(list["season"]).toInt(), num(list["round"]).toInt(), rows)
    }

    private val SESSIONS = listOf(
        "FirstPractice" to "Practice 1",
        "SecondPractice" to "Practice 2",
        "ThirdPractice" to "Practice 3",
        "SprintQualifying" to "Sprint Qualifying",
        "Sprint" to "Sprint",
        "Qualifying" to "Qualifying",
    )

    private fun whenUtc(date: Any?, time: Any?): Long? {
        if (date !is String) return null
        return parseUtc(if (time is String) "${date}T$time" else "${date}T00:00:00Z")
    }

    fun parseSchedule(json: Any?): Schedule {
        val out = ArrayList<ScheduledSession>()
        for (race in arr(obj(obj(obj(json)["MRData"])["RaceTable"])["Races"])) {
            val location = obj(obj(race["Circuit"])["Location"])
            val meeting = text(race["raceName"])
            val place = text(location["locality"])
            val country = text(location["country"])
            val round = num(race["round"]).toInt()
            for ((key, name) in SESSIONS) {
                val s = obj(race[key])
                if (s["date"] != null && s["date"] != "") out.add(ScheduledSession(meeting, place, country, round, name, whenUtc(s["date"], s["time"])))
            }
            out.add(ScheduledSession(meeting, place, country, round, "Race", whenUtc(race["date"], race["time"])))
        }
        return out.sortedBy { it.start ?: 0 }
    }

    private suspend fun json(path: String, fetcher: Fetcher): Any? {
        val res = fetcher.get(BASE + path, emptyMap())
        if (!res.ok) throw IOException("Jolpica: ${res.status} for $path")
        return FeedJson.parse(res.body ?: "null")
    }

    /**
     * Loads standings after `round` of `season` (the latest when round is null) and the schedule of
     * `scheduleSeason`, and offers them on the bus. Each part that fails is left out.
     */
    suspend fun provide(bus: Bus, season: Int, round: Int?, scheduleSeason: Int, fetcher: Fetcher = OkHttpFetcher()): List<String> = coroutineScope {
        val at = if (round != null && round != 0) "$season/$round" else "$season"
        // Standings after a round that has only just finished may not be published yet: then the
        // latest standings of the season stand in.
        suspend fun <T> withFallback(kind: String, parse: (Any?) -> T?): T? {
            val exact = parse(json("$at/$kind.json", fetcher))
            if (exact != null || round == null || round == 0) return exact
            return parse(json("$season/$kind.json", fetcher))
        }
        val drivers = async { runCatching { withFallback("driverStandings", ::parseDriverStandings) }.getOrNull() }
        val teams = async { runCatching { withFallback("constructorStandings", ::parseTeamStandings) }.getOrNull() }
        val schedule = async { runCatching { parseSchedule(json("$scheduleSeason.json", fetcher)) }.getOrNull() }
        bus.removeSource(SOURCE)
        val offered = ArrayList<String>()
        fun offer(topic: String, value: Any?) {
            if (value == null) return
            bus.provide(topic, TopicProvider(SOURCE, { value }))
            offered.add(topic)
        }
        offer(Topic.STANDINGS_DRIVERS, drivers.await())
        offer(Topic.STANDINGS_TEAMS, teams.await())
        offer(Topic.SCHEDULE, schedule.await())
        offered
    }
}
