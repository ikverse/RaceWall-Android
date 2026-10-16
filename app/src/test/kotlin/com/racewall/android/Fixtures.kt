package com.racewall.android

import com.racewall.archive.Archive
import com.racewall.archive.ArchiveMeeting
import com.racewall.archive.Fetcher
import com.racewall.archive.HttpResult
import com.racewall.archive.STREAMS
import com.racewall.archive.SessionFiles
import com.racewall.archive.buildSession
import com.racewall.bus.Bus
import com.racewall.clock.ClockState
import com.racewall.topics.Topic
import java.io.File

/** The desktop's British GP 2025 race fixture, shared with the archive module's tests. */
object BritishGp {
    private val dir = File("../data/archive/src/test/resources/2025-british-gp-race")
    private fun read(f: String) = File(dir, f).readText(Charsets.UTF_8)

    val files = SessionFiles(read("SessionInfo.json"), STREAMS.associateWith { read("$it.jsonStream") })

    /** OpenF1's moment during lap 4, 14:11:40 UTC. */
    const val LAP_4 = 3_840_000.0

    /** The frame the core sends at session time `t`, built straight from the bus. */
    fun frameAt(t: Double, playing: Boolean = false): Frame {
        val bus = Bus()
        val s = buildSession(files, bus)
        fun <T> at(topic: String): T? = bus.typed<T>(topic, t)
        return Frame(
            clock = ClockState(t, playing, 1.0, s.start, s.end),
            utc = s.utcAt(t),
            info = at(Topic.SESSION_INFO),
            status = at(Topic.SESSION_STATUS),
            drivers = at(Topic.DRIVERS_LIST) ?: emptyMap(),
            positions = at(Topic.TIMING_POSITIONS) ?: emptyList(),
            laps = at(Topic.TIMING_LAPS) ?: emptyMap(),
            stints = at(Topic.TIMING_STINTS) ?: emptyMap(),
            sources = listOf("f1-archive"),
        )
    }

    /** An archive that answers from the fixture, with one season holding this one session. */
    fun archive(): Archive = Archive(Fetcher { url, _ ->
        val path = url.removePrefix(com.racewall.archive.ARCHIVE_BASE)
        when {
            path.endsWith("Index.json") -> HttpResult(200, INDEX)
            path.endsWith("SessionInfo.json") -> HttpResult(200, files.sessionInfo)
            else -> STREAMS.firstOrNull { path.endsWith("/$it.jsonStream") }?.let { HttpResult(200, files.streams.getValue(it)) } ?: HttpResult(404, null)
        }
    }, now = { 1_760_000_000_000 })

    const val PATH = "2025/2025-07-06_British_Grand_Prix/2025-07-06_Race/"

    private val INDEX = """{"Meetings":[{"Key":1277,"Name":"British Grand Prix","Location":"Silverstone","Country":{"Name":"United Kingdom"},
        "Sessions":[{"Key":9946,"Name":"Qualifying","Type":"Qualifying","StartDate":"2025-07-05T16:00:00","EndDate":"2025-07-05T17:00:00","GmtOffset":"01:00:00","Path":"2025/2025-07-06_British_Grand_Prix/2025-07-05_Qualifying/"},
        {"Key":9947,"Name":"Race","Type":"Race","StartDate":"2025-07-06T15:00:00","EndDate":"2025-07-06T17:00:00","GmtOffset":"01:00:00","Path":"$PATH"}]}]}"""

    val meetings: List<ArchiveMeeting> by lazy { kotlinx.coroutines.runBlocking { archive().fetchSeason(2025) } }
}
