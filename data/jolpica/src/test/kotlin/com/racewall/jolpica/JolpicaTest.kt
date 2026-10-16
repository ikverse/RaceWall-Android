package com.racewall.jolpica

import com.racewall.archive.Fetcher
import com.racewall.archive.HttpResult
import com.racewall.bus.Bus
import com.racewall.bus.FeedJson
import com.racewall.topics.DriverStanding
import com.racewall.topics.Schedule
import com.racewall.topics.Standings
import com.racewall.topics.TeamStanding
import com.racewall.topics.Topic
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ported from the desktop's packages/jolpica/test/jolpica.test.ts, with its real responses saved on 2026-10-10. */
class JolpicaTest {
    private fun text(f: String) = JolpicaTest::class.java.getResource("/$f")!!.readText()
    private fun fixture(f: String) = FeedJson.parse(text(f))

    @Test
    fun `reads driver standings after the 2025 British GP`() {
        val s = Jolpica.parseDriverStandings(fixture("2025-r12-driverStandings.json"))!!
        assertEquals(2025, s.season)
        assertEquals(12, s.round)
        assertEquals(DriverStanding(1, 234.0, 5, "PIA", "Oscar Piastri", "McLaren"), s.rows[0])
        assertEquals("NOR", s.rows[1].code)
        assertEquals(226.0, s.rows[1].points)
    }

    @Test
    fun `reads team standings`() {
        assertEquals(TeamStanding(1, 460.0, 9, "McLaren"), Jolpica.parseTeamStandings(fixture("2025-r12-constructorStandings.json"))!!.rows[0])
    }

    @Test
    fun `turns the season into a sorted list of sessions in UTC`() {
        val schedule = Jolpica.parseSchedule(fixture("2026-schedule.json"))
        val singapore = schedule.filter { it.meeting == "Singapore Grand Prix" }
        assertEquals(listOf("Practice 1", "Sprint Qualifying", "Sprint", "Qualifying", "Race"), singapore.map { it.name })
        assertEquals(17, singapore.last().round)
        assertEquals("Marina Bay", singapore.last().location)
        assertEquals(Instant.parse("2026-10-11T12:00:00Z").toEpochMilli(), singapore.last().start)
        val starts = schedule.map { it.start ?: 0 }
        assertEquals(starts.sorted(), starts)
    }

    @Test
    fun `offers what it could load on the bus and skips what failed`() = runTest {
        val bus = Bus()
        val fetch = Fetcher { url, _ ->
            when {
                url.endsWith("driverStandings.json") -> HttpResult(200, text("2025-r12-driverStandings.json"))
                url.endsWith("2025.json") -> HttpResult(200, text("2026-schedule.json"))
                else -> HttpResult(500, null)
            }
        }
        val offered = Jolpica.provide(bus, 2025, 12, 2025, fetch)
        assertEquals(listOf(Topic.STANDINGS_DRIVERS, Topic.SCHEDULE), offered)
        // 21, not 20: Colapinto replaced Doohan mid-season, and both scored or started.
        assertEquals(21, bus.typed<Standings<DriverStanding>>(Topic.STANDINGS_DRIVERS, 0.0)!!.rows.size)
        assertTrue(bus.typed<Schedule>(Topic.SCHEDULE, 0.0)!!.size > 100)
        assertFalse(bus.has(Topic.STANDINGS_TEAMS))
    }

    @Test
    fun `uses the latest standings when the round asked for is not published yet`() = runTest {
        val calls = mutableListOf<String>()
        val fetch = Fetcher { url, _ ->
            synchronized(calls) { calls.add(url.substringAfter("/f1/")) }
            when {
                "/17/" in url -> HttpResult(200, """{"MRData":{"StandingsTable":{"StandingsLists":[]}}}""")
                url.endsWith("driverStandings.json") -> HttpResult(200, text("2025-r12-driverStandings.json"))
                else -> HttpResult(404, null)
            }
        }
        val offered = Jolpica.provide(Bus(), 2026, 17, 2026, fetch)
        assertTrue(Topic.STANDINGS_DRIVERS in offered)
        assertTrue("2026/17/driverStandings.json" in calls)
        assertTrue("2026/driverStandings.json" in calls)
    }
}
