package com.racewall.archive

import com.racewall.bus.Bus
import com.racewall.bus.FeedJson
import com.racewall.topics.Compound
import com.racewall.topics.DriverList
import com.racewall.topics.Laps
import com.racewall.topics.Positions
import com.racewall.topics.SessionState
import com.racewall.topics.SessionStatus
import com.racewall.topics.Stints
import com.racewall.topics.Topic
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ported from the desktop's packages/f1-archive/test/archive.test.ts. */
private object Fixture {
    private fun read(f: String): String =
        ArchiveTest::class.java.getResource("/2025-british-gp-race/$f")!!.readText(Charsets.UTF_8)

    /** The real first 66 minutes of the 2025 British Grand Prix race feed (lights out is at 56:09). */
    val files = SessionFiles(read("SessionInfo.json"), STREAMS.associateWith { read("$it.jsonStream") })

    /** OpenF1's independently processed running order at one moment, used as the answer key. */
    @Suppress("UNCHECKED_CAST")
    val expected = FeedJson.parse(read("expected.json")) as Map<String, Any?>
    val offsetMs = (expected["offsetMs"] as Long).toDouble()
    val utc = expected["utc"] as String

    @Suppress("UNCHECKED_CAST")
    val order = expected["order"] as List<String>
}

class ParseStreamTest {
    @Test
    fun `reads times and JSON, and skips the byte-order mark`() {
        val entries = parseStream("\uFEFF00:00:08.247{\"Status\":\"Inactive\"}\r\n00:56:09.003{\"Status\":\"Started\"}\n")
        assertEquals(
            listOf(FeedEntry(8247.0, mapOf("Status" to "Inactive")), FeedEntry(3369003.0, mapOf("Status" to "Started"))),
            entries,
        )
    }

    @Test
    fun `skips a truncated last line`() {
        assertEquals(1, parseStream("00:00:01.000{\"a\":1}\n00:00:02.000{\"a\":").size)
    }
}

class ArchiveTest {
    private val bus = Bus()
    private val session = buildSession(Fixture.files, bus)

    private fun <T> at(topic: String, t: Double): T = bus.typed<T>(topic, t)!!

    @Test
    fun `reads the session information`() {
        val info = session.info
        assertEquals(9947, info.key)
        assertEquals(12, info.round)
        assertEquals(2025, info.year)
        assertEquals("British Grand Prix", info.meeting)
        assertEquals("Race", info.name)
        assertEquals("Silverstone", info.circuit)
        assertEquals(3369003.0, session.startedAt)
    }

    @Test
    fun `lists all 20 drivers with codes and team colours`() {
        val list = at<DriverList>(Topic.DRIVERS_LIST, session.end)
        assertEquals(20, list.size)
        assertEquals("VER", list["1"]!!.code)
        assertEquals("Red Bull Racing", list["1"]!!.team)
        assertEquals("#4781D7", list["1"]!!.teamColour)
        assertEquals("PIA", list["81"]!!.code)
        assertEquals("#F47600", list["81"]!!.teamColour)
    }

    @Test
    fun `matches OpenF1 for the running order during lap 4`() {
        val order = at<Positions>(Topic.TIMING_POSITIONS, Fixture.offsetMs).map { it.number }
        assertEquals(Fixture.order, order.take(Fixture.order.size))
    }

    @Test
    fun `ties session time to UTC from race control messages`() {
        val utc = session.utcAt(Fixture.offsetMs)!!
        assertTrue(abs(utc - Instant.parse(Fixture.utc).toEpochMilli()) < 1500)
    }

    @Test
    fun `reports the lap, the flag and the session state`() {
        assertEquals(SessionState.INACTIVE, at<SessionStatus>(Topic.SESSION_STATUS, 3_000_000.0).state)
        val during = at<SessionStatus>(Topic.SESSION_STATUS, Fixture.offsetMs)
        // LapCount moves to lap 5 at 01:04:13 in the feed, just after this moment.
        assertEquals(SessionState.STARTED, during.state)
        assertEquals(4, during.lap)
        assertEquals(52, during.totalLaps)
    }

    @Test
    fun `gives gaps, last laps with their colours, and three sectors`() {
        val rows = at<Positions>(Topic.TIMING_POSITIONS, Fixture.offsetMs)
        assertTrue(Regex("""^\+\d+\.\d{3}$""").matches(rows[1].gap), rows[1].gap)
        assertTrue(Regex("""^\+\d+\.\d{3}$""").matches(rows[1].interval), rows[1].interval)
        val laps = at<Laps>(Topic.TIMING_LAPS, Fixture.offsetMs)
        assertTrue(Regex("""^\d:\d\d\.\d{3}$""").matches(laps["81"]!!.lastLap.value))
        assertEquals(3, laps["81"]!!.sectors.size)
    }

    @Test
    fun `knows each car started on intermediates`() {
        assertEquals(Compound.INTERMEDIATE, at<Stints>(Topic.TIMING_STINTS, Fixture.offsetMs)["81"]!![0].compound)
    }

    @Test
    fun `answers the same whether time is visited in order or by jumping`() {
        fun at(t: Double) = bus.stateAt(Topic.TIMING_POSITIONS, t).toString()
        val a = at(3_600_000.0)
        at(500_000.0)
        at(3_900_000.0)
        assertEquals(a, at(3_600_000.0))
    }
}

class FetchSessionFilesTest {
    private val cacheDir: File = Files.createTempDirectory("racewall-").toFile()

    @AfterTest
    fun cleanUp() {
        cacheDir.deleteRecursively()
    }

    private class FakeFetch(private val complete: Boolean) : Fetcher {
        val calls = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>): HttpResult {
            synchronized(calls) { calls.add(url) }
            return when {
                url.endsWith("SessionInfo.json") ->
                    HttpResult(200, """{"Name":"Race","ArchiveStatus":{"Status":"${if (complete) "Complete" else "Generating"}"}}""")
                url.endsWith("LapCount.jsonStream") -> HttpResult(200, "00:00:01.000{\"CurrentLap\":1}\n")
                else -> HttpResult(404, null)
            }
        }
    }

    @Test
    fun `caches a complete session, so the second load makes one request only`() = runTest {
        val first = FakeFetch(true)
        val a = Archive(first, cacheDir).fetchSessionFiles("2025/x/Race")
        assertTrue("CurrentLap" in a.streams.getValue("LapCount"))
        assertEquals("", a.streams["TimingData"])
        val second = FakeFetch(true)
        val b = Archive(second, cacheDir).fetchSessionFiles("2025/x/Race")
        assertEquals(a.streams["LapCount"], b.streams["LapCount"])
        assertTrue(second.calls.count { it.endsWith(".jsonStream") } < STREAMS.size)
        assertFalse(second.calls.any { it.endsWith("LapCount.jsonStream") })
    }

    @Test
    fun `does not cache a session the archive is still writing`() = runTest {
        Archive(FakeFetch(false), cacheDir).fetchSessionFiles("2025/y/Race")
        val again = FakeFetch(false)
        Archive(again, cacheDir).fetchSessionFiles("2025/y/Race")
        assertTrue(again.calls.any { it.endsWith("LapCount.jsonStream") })
    }

    @Test
    fun `sends the F1 TV token with every request when signed in`() = runTest {
        val auth = mutableListOf<String?>()
        val fetch = Fetcher { _, headers ->
            synchronized(auth) { auth.add(headers["Authorization"]) }
            HttpResult(200, """{"Name":"Race"}""")
        }
        Archive(fetch, token = { "abc.def.ghi" }).fetchSessionFiles("2025/t/Race")
        assertEquals(STREAMS.size + 1, auth.size)
        assertTrue(auth.all { it == "Bearer abc.def.ghi" })
    }

    @Test
    fun `says to sign in when the archive answers 401`() = runTest {
        val fetch = Fetcher { _, _ -> HttpResult(401, null) }
        assertFailsWith<ArchiveSignInException> { Archive(fetch).fetchSeason(2025) }
        val e = assertFailsWith<ArchiveSignInException> { Archive(fetch).fetchSessionFiles("2025/u/Race") }
        assertTrue("needs an F1 TV sign-in" in e.message!!)
    }

    @Test
    fun `fails on a server error, but treats a missing file as no data`() = runTest {
        val fetch = Fetcher { url, _ ->
            when {
                url.endsWith("SessionInfo.json") -> HttpResult(200, """{"Name":"Race"}""")
                url.endsWith("LapCount.jsonStream") -> HttpResult(500, null)
                else -> HttpResult(404, null)
            }
        }
        val e = assertFailsWith<IOException> { Archive(fetch).fetchSessionFiles("2025/z/Race") }
        assertTrue("500" in e.message!!)
    }
}
