package com.racewall.archive

import com.racewall.bus.Bus
import com.racewall.bus.FeedJson
import com.racewall.topics.CarPositions
import com.racewall.topics.CarTelemetry
import com.racewall.topics.CarTelemetryMap
import com.racewall.topics.CircuitOutline
import com.racewall.topics.LapHistory
import com.racewall.topics.LapTime
import com.racewall.topics.RaceControlMessages
import com.racewall.topics.RadioClips
import com.racewall.topics.TelemetrySample
import com.racewall.topics.Topic
import com.racewall.topics.Weather
import com.racewall.topics.WeatherSample
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rest of the desktop's archive.test.ts: topics read from the whole recording, checked against OpenF1. */
class RecordingTopicsTest {
    private fun read(f: String) = RecordingTopicsTest::class.java.getResource("/2025-british-gp-race/$f")!!.readText(Charsets.UTF_8)
    private val files = SessionFiles(read("SessionInfo.json"), STREAMS.associateWith { read("$it.jsonStream") })

    @Suppress("UNCHECKED_CAST")
    private val expected = FeedJson.parse(read("expected.json")) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun sub(key: String) = expected[key] as Map<String, Any?>

    private val bus = Bus()
    private val session = buildSession(files, bus)

    /** Session time for a UTC moment, using the session's own anchor. */
    private fun at(utc: String) = Instant.parse(utc).toEpochMilli() - session.utcAt(0.0)!!

    private fun <T> topic(name: String, t: Double): T = bus.typed<T>(name, t)!!

    private fun d(v: Any?) = (v as Number).toDouble()

    @Test
    fun `matches OpenF1 for the weather at a moment`() {
        val w = sub("weather")
        val t = at(w["utc"] as String)
        @Suppress("UNCHECKED_CAST")
        val c = w["current"] as Map<String, Any?>
        val now = topic<Weather>(Topic.WEATHER, t)
        assertEquals(d(c["airTemp"]), now.airTemp)
        assertEquals(d(c["trackTemp"]), now.trackTemp)
        assertEquals(d(c["humidity"]), now.humidity)
        assertEquals(d(c["windSpeed"]), now.windSpeed)
        assertEquals(d(c["windDirection"]), now.windDirection)
        assertEquals(c["rainfall"], now.rainfall)
        val history = topic<List<WeatherSample>>(Topic.WEATHER_HISTORY, t)
        assertEquals((w["samplesBefore"] as Long).toInt(), history.count { it.t <= t })
    }

    @Test
    fun `matches OpenF1 for the team radio clips so far`() {
        val r = sub("radio")
        val clips = topic<RadioClips>(Topic.RADIO, at(sub("weather")["utc"] as String))
        assertEquals((r["count"] as Long).toInt(), clips.size)
        assertEquals(r["first"], clips[0].url)
        assertEquals("4", clips[0].number)
    }

    @Test
    fun `lists race control messages up to the moment`() {
        val messages = topic<RaceControlMessages>(Topic.RACE_CONTROL, 800_000.0)
        assertTrue(messages.any { it.message == "GREEN LIGHT - PIT EXIT OPEN" })
        assertTrue(messages.all { it.utc <= session.utcAt(800_000.0)!! + 2000 })
    }

    @Test
    fun `builds each driver's lap history from the feed`() {
        val history = topic<LapHistory>(Topic.LAP_HISTORY, d(expected["offsetMs"]))
        // The feed gives no time for lap 1 (the standing start). Laps 2 and 3 match OpenF1's
        // lap_duration for driver 81: 135.131 and 137.795 seconds.
        assertEquals(listOf(LapTime(2, "2:15.131", 135_131), LapTime(3, "2:17.795", 137_795)), history["81"])
        assertEquals(88_123L, lapMs("1:28.123"))
        assertEquals(59_500L, lapMs("59.5"))
    }

    @Test
    fun `matches OpenF1 for a car's position and telemetry, from the compressed feeds`() {
        session.attachCars(mapOf("Position.z" to read("Position.z.jsonStream"), "CarData.z" to read("CarData.z.jsonStream")))
        val cars = sub("cars")
        @Suppress("UNCHECKED_CAST")
        val p = cars["position"] as Map<String, Any?>
        val t = at(cars["utc"] as String)
        val pos = topic<CarPositions>(Topic.CAR_POSITION, t).getValue("81")
        assertEquals(listOf(p["x"], p["y"], p["z"]).map { (it as Long).toInt() }, listOf(pos.x, pos.y, pos.z))
        @Suppress("UNCHECKED_CAST")
        val e = (cars["telemetry"] as Map<String, Any?>).mapValues { (it.value as Long).toInt() }
        assertEquals(
            CarTelemetry(e.getValue("speed"), e.getValue("rpm"), e.getValue("gear"), e.getValue("throttle"), e.getValue("brake"), e.getValue("drs")),
            topic<CarTelemetryMap>(Topic.CAR_TELEMETRY, at(cars["telemetryUtc"] as String))["81"],
        )
        @Suppress("UNCHECKED_CAST")
        val range = bus.range(Topic.CAR_TELEMETRY, t - 10_000, t, listOf("81", "4")) as Map<String, List<TelemetrySample>>
        assertEquals(listOf("4", "81"), range.keys.sorted())
        assertTrue(range.getValue("81").size > 20)
        assertTrue(range.getValue("81").all { it.t >= t - 10_000 && it.t <= t })
        val outline = bus.typed<CircuitOutline>(Topic.CIRCUIT, t)
        assertTrue(outline == null || outline.points.size > 20)
    }
}

/** Ported from the desktop's packages/f1-archive/test/laps.test.ts. */
class LapHistoryTest {
    private fun j(text: String) = FeedJson.parse(text)

    /** Real TimingData records for car 77 (Bottas), 2024 Bahrain GP race, laps 49 to 51. */
    private val bahrain = listOf(
        """{"Lines":{"77":{"NumberOfLaps":49,"Sectors":{"2":{"Value":"23.853"}},"Speeds":{"FL":{"Value":"280"}},"LastLapTime":{"Value":"1:36.910"}}}}""",
        // Lap 50 was 1:36.910 again (sectors 31.025 + 41.997 + 23.888), so the feed sends no lap time.
        """{"Lines":{"77":{"NumberOfLaps":50,"Sectors":{"2":{"Value":"23.888"}},"Speeds":{"FL":{"Value":"280"}}}}}""",
        """{"Lines":{"77":{"NumberOfLaps":51,"Sectors":{"2":{"Value":"23.864"}},"Speeds":{"FL":{"Value":"280"}},"LastLapTime":{"Value":"1:36.819"}}}}""",
    )

    /** A real 2024 Bahrain qualifying record: leaving the pits raises the lap count, every sector empty. */
    private val pitExit = """{"Lines":{"16":{"InPit":false,"PitOut":true,"Status":608,"NumberOfLaps":4,
        "Sectors":{"0":{"Value":""},"1":{"Value":""},"2":{"Value":""}},"Speeds":{"I1":{"Value":""},"I2":{"Value":""},"ST":{"Value":""}}}}}"""

    private fun laps(b: LapHistoryBuilder, n: String) = b.at(Double.MAX_VALUE)[n]?.map { it.lap to it.time }

    @Test
    fun `keeps a lap exactly as fast as the one before`() {
        val b = LapHistoryBuilder()
        bahrain.forEachIndexed { i, r -> b.add(i * 97_000.0, j(r)) }
        assertEquals(listOf(49 to "1:36.910", 50 to "1:36.910", 51 to "1:36.819"), laps(b, "77"))
    }

    @Test
    fun `does not count leaving the pits as a lap`() {
        val b = LapHistoryBuilder()
        b.add(0.0, j("""{"Lines":{"16":{"NumberOfLaps":3,"Sectors":{"2":{"Value":"29.104"}},"LastLapTime":{"Value":"1:51.926"}}}}"""))
        b.add(60_000.0, j(pitExit))
        assertEquals(listOf(3 to "1:51.926"), laps(b, "16"))
    }

    @Test
    fun `records a normal lap once, even when other parts of the line update later`() {
        val b = LapHistoryBuilder()
        b.add(0.0, j(bahrain[0]))
        b.add(10.0, j("""{"Lines":{"77":{"Sectors":{"2":{"PreviousValue":"23.853"}}}}}"""))
        b.add(20.0, j("""{"Lines":{"77":{"Sectors":{"2":{"Value":""}}}}}"""))
        b.add(30.0, j("""{"Lines":{"77":{"NumberOfLaps":49}}}"""))
        assertEquals(listOf(49 to "1:36.910"), laps(b, "77"))
    }

    @Test
    fun `gives lap 1 no time, as the feed has none`() {
        val b = LapHistoryBuilder()
        b.add(0.0, j("""{"Lines":{"1":{"NumberOfLaps":1,"Sectors":{"2":{"Value":"35.002"}}}}}"""))
        assertNull(laps(b, "1"))
    }
}
