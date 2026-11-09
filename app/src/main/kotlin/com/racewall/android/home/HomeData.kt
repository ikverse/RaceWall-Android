package com.racewall.android.home

import android.content.Context
import com.racewall.android.state.JsonStore
import com.racewall.archive.OkHttpFetcher
import com.racewall.bus.FeedJson
import com.racewall.jolpica.Jolpica
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * What the home-screen widgets show, kept in home-widgets.json. The widgets never fetch anything:
 * the app writes this file (the refresh work, and the live hook while a session is followed) and
 * the widgets draw from it. The season schedule comes from the session alerts' own schedule.json.
 */
@Serializable
data class HomeDriver(val position: Int, val code: String, val name: String, val team: String, val points: Double)

@Serializable
data class HomeTeam(val position: Int, val name: String, val points: Double)

@Serializable
data class HomePlace(val position: Int, val code: String, val name: String, val team: String)

/** The top of the last race: its round and season, so it is shown only for the weekend it belongs to. */
@Serializable
data class HomePodium(val season: Int, val round: Int, val meeting: String, val places: List<HomePlace>, val at: Long)

@Serializable
data class HomeLiveRow(
    val position: Int,
    val number: String,
    val code: String,
    val team: String,
    /** "#RRGGBB" from the feed, or null. */
    val colour: String?,
    val gap: String,
    val inPit: Boolean = false,
    val out: Boolean = false,
)

/** A followed live session, as the live widget shows it. */
@Serializable
data class HomeLive(
    val meeting: String,
    val session: String,
    /** The `TrackFlag` wire name: "green", "yellow", "sc", "vsc", "vsc-ending", "red". */
    val flag: String,
    /** The `SessionState` wire name. */
    val state: String,
    val lap: Int? = null,
    val totalLaps: Int? = null,
    val race: Boolean = false,
    val rows: List<HomeLiveRow> = emptyList(),
    /** The latest race control message, or null. */
    val news: String? = null,
)

@Serializable
data class HomeSnapshot(
    val season: Int = 0,
    val round: Int = 0,
    val drivers: List<HomeDriver> = emptyList(),
    val teams: List<HomeTeam> = emptyList(),
    val podium: HomePodium? = null,
    val live: HomeLive? = null,
    val fetchedAt: Long = 0,
)

object HomeStore {
    private val lock = Any()
    private fun store(context: Context) = JsonStore(File(context.filesDir, "home-widgets.json"))

    fun read(context: Context): HomeSnapshot = synchronized(lock) { store(context).read(HomeSnapshot()) }

    fun update(context: Context, change: (HomeSnapshot) -> HomeSnapshot): HomeSnapshot = synchronized(lock) {
        val s = store(context)
        val before = s.read(HomeSnapshot())
        val after = change(before)
        if (after != before) s.write(after)
        after
    }
}

/** Fetches the standings and the last race from Jolpica. Each part that fails keeps what was saved. */
object HomeFetch {
    private fun year(now: Long) = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).year

    private suspend fun json(path: String): Any? {
        val res = OkHttpFetcher().get(Jolpica.BASE + path, emptyMap())
        if (!res.ok) throw java.io.IOException("Jolpica: ${res.status} for $path")
        return FeedJson.parse(res.body ?: "null")
    }

    /** True when anything new arrived. */
    suspend fun refresh(context: Context, now: Long = System.currentTimeMillis()): Boolean = withContext(Dispatchers.IO) {
        val season = year(now)
        coroutineScope {
            val drivers = async { attempt { Jolpica.parseDriverStandings(json("$season/driverStandings.json")) } }
            val teams = async { attempt { Jolpica.parseTeamStandings(json("$season/constructorStandings.json")) } }
            val podium = async { attempt { parsePodium(json("$season/last/results.json"), now) } }
            val d = drivers.await()
            val t = teams.await()
            val p = podium.await()
            val before = HomeStore.read(context)
            val after = HomeStore.update(context) { s ->
                s.copy(
                    season = d?.season ?: s.season,
                    round = d?.round ?: s.round,
                    drivers = d?.rows?.map { HomeDriver(it.position, it.code, it.name, it.team, it.points) } ?: s.drivers,
                    teams = t?.rows?.map { HomeTeam(it.position, it.name, it.points) } ?: s.teams,
                    // A race already saved keeps the time it was first seen.
                    podium = p?.let { new -> s.podium?.takeIf { it.season == new.season && it.round == new.round }?.let { new.copy(at = it.at) } ?: new } ?: s.podium,
                    fetchedAt = if (d != null || t != null || p != null) now else s.fetchedAt,
                )
            }
            after != before
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()
    private fun arr(v: Any?): List<Map<String, Any?>> = (v as? List<*>)?.map(::obj) ?: emptyList()
    private fun text(v: Any?) = v?.toString() ?: ""
    private fun int(v: Any?) = text(v).trim().toDoubleOrNull()?.toInt() ?: 0

    /** The top three of a Jolpica race results answer. */
    fun parsePodium(json: Any?, now: Long): HomePodium? {
        val race = arr(obj(obj(obj(json)["MRData"])["RaceTable"])["Races"]).firstOrNull() ?: return null
        val places = arr(race["Results"]).map { r ->
            val driver = obj(r["Driver"])
            HomePlace(
                int(r["position"]),
                text(driver["code"]),
                "${text(driver["givenName"])} ${text(driver["familyName"])}".trim(),
                text(obj(r["Constructor"])["name"]),
            )
        }.sortedBy { it.position }.take(3)
        if (places.isEmpty()) return null
        return HomePodium(int(race["season"]), int(race["round"]), text(race["raceName"]), places, now)
    }
}
