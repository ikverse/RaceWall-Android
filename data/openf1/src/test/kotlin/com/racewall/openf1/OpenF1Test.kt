package com.racewall.openf1

import com.racewall.archive.Fetcher
import com.racewall.archive.HttpResult
import com.racewall.bus.Bus
import com.racewall.topics.LapHistory
import com.racewall.topics.LapTime
import com.racewall.topics.RadioClips
import com.racewall.topics.Topic
import com.racewall.topics.Weather
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ported from the desktop's packages/openf1/test/openf1.test.ts. */
class OpenF1Test {
    /** Rows copied from real OpenF1 responses for session 9947 (2025 British GP race). */
    private val responses = mapOf(
        "weather" to """[{"date":"2025-07-06T14:12:00.523000+00:00","air_temperature":18.6,"track_temperature":24.9,"humidity":75.0,"pressure":988.7,"rainfall":0,"wind_speed":0.9,"wind_direction":313}]""",
        "team_radio" to """[{"date":"2025-07-06T13:24:06.017000+00:00","driver_number":4,"recording_url":"https://livetiming.formula1.com/static/2025/2025-07-06_British_Grand_Prix/2025-07-06_Race/TeamRadio/LANNOR01_4_20250706_142344.mp3"}]""",
        "laps" to """[{"driver_number":81,"lap_number":2,"lap_duration":135.131,"date_start":"2025-07-06T14:05:36.568000+00:00"},
            {"driver_number":81,"lap_number":1,"lap_duration":null,"date_start":"2025-07-06T14:03:49.615000+00:00"}]""",
    )

    private fun fake(calls: MutableList<String>) = Fetcher { url, _ ->
        calls.add(url)
        HttpResult(200, responses[url.substringAfter("/v1/").substringBefore('?')] ?: "[]")
    }

    private val base = Instant.parse("2025-07-06T13:07:40.500Z").toEpochMilli().toDouble()
    private val utcToT = { utc: Double -> utc - base }

    @Test
    fun `asks only for the topics it was given`() = runTest {
        val calls = mutableListOf<String>()
        OpenF1.provide(Bus(), listOf(Topic.WEATHER), 9947, utcToT, fake(calls))
        assertEquals(listOf("https://api.openf1.org/v1/weather?session_key=9947"), calls)
    }

    @Test
    fun `turns OpenF1 rows into RaceWall topics on session time`() = runTest {
        val bus = Bus()
        val offered = OpenF1.provide(bus, listOf(Topic.WEATHER, Topic.RADIO, Topic.LAP_HISTORY), 9947, utcToT, fake(mutableListOf()))
        assertEquals(listOf(Topic.WEATHER, Topic.RADIO, Topic.LAP_HISTORY), offered)
        val t = utcToT(Instant.parse("2025-07-06T14:12:40Z").toEpochMilli().toDouble())
        val w = bus.typed<Weather>(Topic.WEATHER, t)!!
        assertEquals(18.6, w.airTemp)
        assertEquals(24.9, w.trackTemp)
        assertEquals(false, w.rainfall)
        assertEquals("4", bus.typed<RadioClips>(Topic.RADIO, t)!![0].number)
        assertEquals(mapOf("81" to listOf(LapTime(2, "2:15.131", 135_131))), bus.typed<LapHistory>(Topic.LAP_HISTORY, t))
        assertEquals(emptyList(), bus.typed<RadioClips>(Topic.RADIO, 0.0))
    }

    @Test
    fun `writes lap times the way the feed does`() {
        assertEquals("2:15.131", OpenF1.lapText(135.131))
        assertEquals("59.500", OpenF1.lapText(59.5))
        assertEquals("9.500", OpenF1.lapText(9.5))
    }
}
