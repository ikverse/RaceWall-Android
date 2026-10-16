package com.racewall.android

import com.racewall.archive.ARCHIVE_BASE
import com.racewall.archive.Archive
import com.racewall.archive.ArchiveMeeting
import com.racewall.archive.CAR_STREAMS
import com.racewall.archive.Fetcher
import com.racewall.archive.HttpResult
import com.racewall.archive.STREAMS
import com.racewall.archive.SessionFiles
import com.racewall.jolpica.Jolpica
import com.racewall.topics.Topic
import com.racewall.android.widgets.SHELL_TOPICS
import com.racewall.android.widgets.WIDGETS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File

/** The desktop's British GP 2025 race fixture, shared with the archive module's tests. */
object BritishGp {
    private val dir = File("../data/archive/src/test/resources/2025-british-gp-race")
    private fun read(f: String) = File(dir, f).readText(Charsets.UTF_8)

    val files = SessionFiles(read("SessionInfo.json"), STREAMS.associateWith { read("$it.jsonStream") })
    private val cars = CAR_STREAMS.associateWith { read("$it.jsonStream") }

    /** OpenF1's moment during lap 4, 14:11:40 UTC. */
    const val LAP_4 = 3_840_000.0

    /** A minute later, when the compressed car data in the fixture has the cars on track. */
    const val CARS = 3_900_000.0

    const val PATH = "2025/2025-07-06_British_Grand_Prix/2025-07-06_Race/"

    private val INDEX = """{"Meetings":[{"Key":1277,"Name":"British Grand Prix","Location":"Silverstone","Country":{"Name":"United Kingdom"},
        "Sessions":[{"Key":9946,"Name":"Qualifying","Type":"Qualifying","StartDate":"2025-07-05T16:00:00","EndDate":"2025-07-05T17:00:00","GmtOffset":"01:00:00","Path":"2025/2025-07-06_British_Grand_Prix/2025-07-05_Qualifying/"},
        {"Key":9947,"Name":"Race","Type":"Race","StartDate":"2025-07-06T15:00:00","EndDate":"2025-07-06T17:00:00","GmtOffset":"01:00:00","Path":"$PATH"}]}]}"""

    /** An archive that answers from the fixture, with one season holding this one session. */
    fun archive(): Archive = Archive(Fetcher { url, _ ->
        val path = url.removePrefix(ARCHIVE_BASE)
        when {
            path.endsWith("Index.json") -> HttpResult(200, INDEX)
            path.endsWith("SessionInfo.json") -> HttpResult(200, files.sessionInfo)
            else -> (STREAMS + CAR_STREAMS).firstOrNull { path.endsWith("/$it.jsonStream") }?.let { HttpResult(200, files.streams[it] ?: cars.getValue(it)) } ?: HttpResult(404, null)
        }
    }, now = { 1_760_000_000_000 })

    val meetings: List<ArchiveMeeting> by lazy { runBlocking { archive().fetchSeason(2025) } }

    private fun jolpica(f: String) = BritishGp::class.java.getResource("/jolpica/$f")!!.readText()

    /** Jolpica's standings after the British GP and the 2026 schedule, saved on 2026-10-10. */
    private val jolpicaFetcher = Fetcher { url, _ ->
        when {
            url.endsWith("driverStandings.json") -> HttpResult(200, jolpica("2025-r12-driverStandings.json"))
            url.endsWith("constructorStandings.json") -> HttpResult(200, jolpica("2025-r12-constructorStandings.json"))
            else -> HttpResult(200, jolpica("2026-schedule.json"))
        }
    }

    /** Every topic any widget reads. */
    val ALL_TOPICS: Set<String> = SHELL_TOPICS + WIDGETS.flatMap { it.reads }

    /**
     * A real core with the British GP loaded, car data attached, Jolpica's standings and schedule,
     * and every topic subscribed, at session time `t`.
     */
    val core: Core by lazy {
        val c = Core(
            BritishGp.archive(),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            now = { 1_760_000_000_000 },
            openF1 = null,
            jolpica = { bus, season, round, schedule -> Jolpica.provide(bus, season, round, schedule, jolpicaFetcher) },
        )
        c.subscribe(ALL_TOPICS)
        runBlocking { c.loadSession(PATH) }
        // The car data and Jolpica load in the background, as in the app.
        val until = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < until) {
            c.seek(CARS)
            val f = c.frame.value.topics
            if (Topic.CAR_POSITION in f && Topic.STANDINGS_DRIVERS in f && Topic.SCHEDULE in f) break
            Thread.sleep(100)
        }
        c
    }

    /** The frame the core sends at session time `t`. */
    fun frameAt(t: Double, playing: Boolean = false): Frame = synchronized(core) {
        core.seek(t)
        val f = core.frame.value
        f.copy(clock = f.clock.copy(playing = playing))
    }
}

/**
 * The full 2026 Singapore GP race, read where the desktop app cached it, for what needs a whole
 * race of car data (the circuit outline). Tests that use it are skipped on a machine without it.
 */
object Singapore {
    private val dir = File(System.getenv("APPDATA") ?: "", "racewall/archive/2026/2026-10-11_Singapore_Grand_Prix/2026-10-11_Race")
    val available: Boolean get() = File(dir, "Position.z.jsonStream").isFile

    const val PATH = "2026/2026-10-11_Singapore_Grand_Prix/2026-10-11_Race/"

    /** The archive's SessionInfo.json is never cached; these are its fields RaceWall reads. */
    private const val INFO = """{"Meeting":{"Key":1300,"Name":"Singapore Grand Prix","Number":17,"Country":{"Name":"Singapore"},"Circuit":{"ShortName":"Singapore"}},
        "ArchiveStatus":{"Status":"Complete"},"Key":11388,"Type":"Race","Name":"Race","StartDate":"2026-10-11T20:00:00","EndDate":"2026-10-11T22:00:00","GmtOffset":"08:00:00","Path":"$PATH"}"""

    private fun archive() = Archive(Fetcher { url, _ ->
        val name = url.substringAfterLast('/')
        when {
            name == "SessionInfo.json" -> HttpResult(200, INFO)
            File(dir, name).isFile -> HttpResult(200, File(dir, name).readText(Charsets.UTF_8))
            else -> HttpResult(404, null)
        }
    })

    val core: Core by lazy {
        val c = Core(archive(), CoroutineScope(SupervisorJob() + Dispatchers.Default), openF1 = null, jolpica = null)
        c.subscribe(BritishGp.ALL_TOPICS)
        runBlocking { c.loadSession(PATH) }
        val until = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < until) {
            c.seek(c.ui.value.session!!.end - 600_000)
            if (Topic.CIRCUIT in c.frame.value.topics && Topic.CAR_POSITION in c.frame.value.topics) break
            Thread.sleep(250)
        }
        c
    }

    /** Ten minutes before the end of the recording, with the cars on track. */
    fun frame(): Frame = synchronized(core) {
        core.seek(core.ui.value.session!!.end - 600_000)
        core.frame.value
    }
}
