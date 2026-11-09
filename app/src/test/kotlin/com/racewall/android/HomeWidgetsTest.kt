package com.racewall.android

import com.racewall.android.home.HomeFetch
import com.racewall.android.home.HomeLogic
import com.racewall.android.home.HomeLogic.WeekendView
import com.racewall.bus.FeedJson
import com.racewall.jolpica.Jolpica
import com.racewall.topics.Schedule
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The home-screen widgets' logic, ported from Deskglow, with the 2025 British GP weekend from Jolpica. */
class HomeWidgetsTest {
    private fun res(f: String) = HomeWidgetsTest::class.java.getResource("/jolpica/$f")!!.readText()
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    val schedule: Schedule by lazy { Jolpica.parseSchedule(FeedJson.parse(res("2025-schedule.json"))) }
    val podium by lazy { HomeFetch.parsePodium(FeedJson.parse(res("2025-r12-results.json")), at("2025-07-06T16:00:00Z"))!! }

    @Test
    fun `countdowns read in days, hours and minutes, then minutes and seconds`() {
        val now = at("2026-10-09T00:00:00Z")
        assertEquals(listOf("2" to "DAYS", "05" to "HRS", "00" to "MIN"), HomeLogic.countdownParts(now, at("2026-10-11T05:00:00Z")))
        assertEquals(listOf("1" to "DAY", "00" to "HRS", "00" to "MIN"), HomeLogic.countdownParts(now, at("2026-10-10T00:00:00Z")))
        assertEquals(listOf("04" to "HRS", "30" to "MIN"), HomeLogic.countdownParts(now, at("2026-10-09T04:30:00Z")))
        assertEquals(listOf("12" to "MIN", "05" to "SEC"), HomeLogic.countdownParts(now, now + 725_000))
        assertEquals(listOf("00" to "MIN", "00" to "SEC"), HomeLogic.countdownParts(now, now - 5_000))
        val utc = java.time.ZoneOffset.UTC
        assertEquals(2, HomeLogic.daysLeft(now, at("2026-10-11T05:00:00Z"), utc))
        // Calendar days: Friday 23:00 to Sunday 01:00 is two days, though it is only 26 hours.
        assertEquals(2, HomeLogic.daysLeft(at("2026-10-09T23:00:00Z"), at("2026-10-11T01:00:00Z"), utc))
        assertTrue(HomeLogic.ticking(now, at("2026-10-09T23:00:00Z")))
        assertTrue(!HomeLogic.ticking(now, at("2026-10-10T00:00:00Z")))
    }

    @Test
    fun `standings show points or the gap to the leader`() {
        assertEquals("331", HomeLogic.valueText(331.0, 331.0, gap = false))
        assertEquals("200.5", HomeLogic.valueText(200.5, 331.0, gap = false))
        assertEquals("—", HomeLogic.valueText(331.0, 331.0, gap = true))
        assertEquals("−130.5", HomeLogic.valueText(200.5, 331.0, gap = true))
    }

    @Test
    fun `the followed row is always shown`() {
        val rows = (1..20).toList()
        assertEquals(listOf(1, 2, 3, 4), HomeLogic.visibleRows(rows, 4, 2))
        assertEquals(listOf(1, 2, 3, 12), HomeLogic.visibleRows(rows, 4, 11))
        assertEquals(listOf(1, 2, 3, 4), HomeLogic.visibleRows(rows, 4, -1))
        assertEquals(listOf(1, 2), HomeLogic.visibleRows(listOf(1, 2), 4, 1))
    }

    @Test
    fun `the British GP weekend reads as its sessions, a countdown, live, then the result`() {
        val weekend = HomeLogic.weekend(schedule, at("2025-07-05T12:00:00Z"))
        assertEquals(listOf("Practice 1", "Practice 2", "Practice 3", "Qualifying", "Race"), weekend.map { it.name })
        assertEquals("British Grand Prix", weekend.first().meeting)
        assertEquals(12, weekend.first().round)

        val before = HomeLogic.weekendView(schedule, podium, at("2025-07-02T12:00:00Z"))
        assertIs<WeekendView.Upcoming>(before)
        assertEquals("Practice 1", before.session.name)
        assertIs<WeekendView.Live>(HomeLogic.weekendView(schedule, podium, at("2025-07-06T14:30:00Z")))
        val after = HomeLogic.weekendView(schedule, podium, at("2025-07-06T18:00:00Z"))
        assertIs<WeekendView.Result>(after)
        assertEquals("Austrian Grand Prix", schedule.first { it.round == 11 }.meeting)
        assertEquals("Belgian Grand Prix", after.next?.meeting)
        // Two days after the race the countdown to the next weekend takes over.
        assertIs<WeekendView.Upcoming>(HomeLogic.weekendView(schedule, podium, at("2025-07-08T17:00:00Z")))
    }

    @Test
    fun `the last race's top three come from Jolpica`() {
        assertEquals(2025, podium.season)
        assertEquals(12, podium.round)
        // Silverstone 2025: Norris, Piastri, Hülkenberg.
        assertEquals(listOf("NOR", "PIA", "HUL"), podium.places.map { it.code })
        assertEquals(listOf(1, 2, 3), podium.places.map { it.position })
        assertEquals("Sauber", podium.places[2].team)
    }

    @Test
    fun `timeline progress runs from session to session`() {
        val starts = listOf(0L, 100L, 300L)
        assertEquals(0f, HomeLogic.timelineProgress(starts, -5))
        assertEquals(0.5f, HomeLogic.timelineProgress(starts, 50))
        assertEquals(1.5f, HomeLogic.timelineProgress(starts, 200))
        assertEquals(2f, HomeLogic.timelineProgress(starts, 400))
    }

    @Test
    fun `widgets redraw at each start, end and countdown day`() {
        val now = at("2025-07-02T12:00:00Z")
        val m = HomeLogic.moments(schedule, now, java.time.ZoneId.of("Europe/London"))
        // The next midnight in London, when the countdown's day count changes.
        assertEquals(at("2025-07-02T23:00:00Z"), m.first())
        // A day before Practice 1, when the countdown starts to tick.
        assertTrue(at("2025-07-03T11:30:00Z") in m)
        assertTrue(at("2025-07-04T11:30:00Z") in m)
        assertTrue(at("2025-07-04T12:30:00Z") in m)
        assertTrue(m.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun `team colours come from Jolpica's constructor names`() {
        assertEquals(0xFFFF8000, HomeLogic.teamColour("McLaren"))
        assertEquals(0xFF3671C6, HomeLogic.teamColour("Red Bull"))
        assertEquals(0xFF6692FF, HomeLogic.teamColour("RB F1 Team"))
        assertEquals(0xFF52E252, HomeLogic.teamColour("Sauber"))
        assertEquals(0xFF8C8C8C, HomeLogic.teamColour("someone new"))
        assertEquals(0xFFFF8000, HomeLogic.colourOf("#FF8000", "x"))
        assertEquals(0xFF3671C6, HomeLogic.colourOf(null, "Red Bull Racing"))
    }
}
