package com.racewall.analysis

import com.racewall.bus.FeedJson
import com.racewall.topics.Compound
import com.racewall.topics.LapTime
import com.racewall.topics.PositionRow
import com.racewall.topics.RaceControlMessage
import com.racewall.topics.SessionInfo
import com.racewall.topics.SessionState
import com.racewall.topics.SessionStatus
import com.racewall.topics.Stint
import com.racewall.topics.TrackFlag
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reads the desktop's plugins/_shared/fixtures/races.json: RaceWall topics as the desktop writes them. */
@Suppress("UNCHECKED_CAST")
object Races {
    private fun read(f: String) = Races::class.java.getResource("/$f")!!.readText()
    private fun m(v: Any?) = v as Map<String, Any?>
    private fun l(v: Any?) = (v as? List<Any?>) ?: emptyList()
    private fun i(v: Any?) = (v as Number).toInt()
    private fun s(v: Any?) = v?.toString() ?: ""

    private val all = m(FeedJson.parse(read("races.json")))

    fun topics(name: String): RaceTopics {
        val t = m(all[name])
        val info = m(t["session.info"])
        val status = m(t["session.status"])
        return RaceTopics(
            info = SessionInfo(i(info["key"]), i(info["round"]), i(info["year"]), s(info["meeting"]), s(info["name"]), s(info["type"]), s(info["circuit"]), s(info["country"]), s(info["startDate"]), s(info["gmtOffset"]), s(info["path"])),
            status = SessionStatus(
                SessionState.entries.first { it.wire == status["state"] },
                TrackFlag.entries.first { it.wire == status["flag"] },
                (status["lap"] as? Number)?.toInt(),
                (status["totalLaps"] as? Number)?.toInt(),
            ),
            positions = l(t["timing.positions"]).map(::m).map { r ->
                PositionRow(s(r["number"]), i(r["position"]), s(r["gap"]), s(r["interval"]), r["inPit"] == true, r["pitOut"] == true, r["retired"] == true, r["stopped"] == true, r["knockedOut"] == true, (r["laps"] as? Number)?.toInt())
            },
            lapHistory = m(t["timing.lapHistory"]).mapValues { (_, v) -> l(v).map(::m).map { LapTime(i(it["lap"]), s(it["time"]), (it["ms"] as? Number)?.toLong()) } },
            stints = m(t["timing.stints"]).mapValues { (_, v) -> l(v).map(::m).map { Stint(Compound.valueOf(s(it["compound"])), it["isNew"] == true, i(it["tyreAge"]), i(it["startLap"])) } },
            messages = l(t["race_control.messages"]).map(::m).map { RaceControlMessage((it["utc"] as Number).toLong(), (it["lap"] as? Number)?.toInt(), s(it["category"]), it["flag"] as String?, s(it["message"])) },
        )
    }

    fun json(f: String) = m(FeedJson.parse(read(f)))
}

/** Ported from the desktop's plugins/battles/test/battles.test.ts. */
class BattlesTest {
    private val lap32 = Races.topics("britishLap32")
    private val result = Battles.find(lap32)

    /** OpenF1's lap start times and gap to the leader for the same cars, as the answer key. */
    @Suppress("UNCHECKED_CAST")
    private val openf1 = Races.json("openf1-british.json")

    /** OpenF1's gap between two cars just after the car behind finished `lap`. */
    @Suppress("UNCHECKED_CAST")
    private fun openf1Gap(behind: String, ahead: String, lap: Int): Double {
        val laps = openf1["laps"] as Map<String, List<Map<String, Any?>>>
        val gaps = openf1["gaps"] as Map<String, List<Map<String, Any?>>>
        val end = Instant.parse((laps.getValue(behind).first { (it["lap"] as Number).toInt() == lap + 1 }["start"] as String).let(::iso)).toEpochMilli()
        fun at(n: String) = gaps.getValue(n).filter { Instant.parse(iso(it["date"] as String)).toEpochMilli() >= end }
            .minBy { Instant.parse(iso(it["date"] as String)).toEpochMilli() }["leader"].let { (it as Number).toDouble() }
        return at(behind) - at(ahead)
    }

    private fun iso(text: String) = java.time.OffsetDateTime.parse(text).toInstant().toString()

    @Test
    fun `finds every car within a second of the car ahead`() {
        assertTrue(result.race)
        assertNull(result.neutral)
        assertEquals(listOf(Triple("27", "18", 0.929), Triple("63", "10", 0.651), Triple("14", "63", 0.709), Triple("16", "23", 0.678)), result.battles.map { Triple(it.number, it.ahead, it.interval) })
    }

    @Test
    fun `works out the gap at earlier lap lines as OpenF1 recorded it`() {
        for (b in result.battles) {
            val gaps = Battles.gapsBack(lap32.lapHistory, b.number, b.ahead, 31, b.interval, 8)
            assertEquals(8, gaps.size)
            gaps.forEachIndexed { i, gap -> assertTrue(abs(gap - openf1Gap(b.number, b.ahead, 31 - i)) < 0.3, "${b.number} lap ${31 - i}") }
        }
    }

    @Test
    fun `counts the laps in a row within a second, as OpenF1 has them`() {
        val laps = result.battles.associate { it.number to it.laps }
        // OpenF1: 63 was 0.82, 0.70, 0.92, then 2.11 s behind 10; 14 was 0.72, 0.75, then 1.18 s behind 63.
        assertEquals(3, laps["63"])
        assertEquals(2, laps["14"])
        // 16 has been within a second of 23 since lap 21 here; OpenF1 agrees from lap 26 (1.03 s at lap 25).
        assertEquals(11, laps["16"])
        for (lap in 31 downTo 26) assertTrue(openf1Gap("16", "23", lap) < 1)
        assertEquals(1.03, openf1Gap("16", "23", 25), 0.005)
    }

    @Test
    fun `says whether the chaser is closing`() {
        // 63 was 1.90 s behind 10 at lap 28 and is 0.65 s behind now: closing by about 0.4 s a lap.
        assertEquals(-0.415, result.battles.first { it.number == "63" }.trend!!, 0.005)
    }

    @Test
    fun `shows none behind the safety car`() {
        val sc = lap32.copy(status = SessionStatus(SessionState.STARTED, TrackFlag.SC, 32))
        assertEquals(Battles.Result(true, "sc", emptyList()), Battles.find(sc))
    }

    @Test
    fun `works them out only in races and sprints`() {
        val quali = lap32.copy(info = lap32.info!!.copy(name = "Qualifying"))
        assertEquals(Battles.Result(false, null, emptyList()), Battles.find(quali))
    }

    @Test
    fun `reads the safety car and virtual safety car periods from race control`() {
        // 2025 British GP: VSC laps 2-4 and 5-7, safety car laps 14-17 and 18-21, one lap of margin each side.
        val laps = Race.neutralLaps(Races.topics("britishEnd").messages)
        for (l in listOf(1, 2, 5, 8, 13, 17, 22)) assertTrue(l in laps, "$l")
        for (l in listOf(9, 10, 11, 12, 23, 40, 52)) assertTrue(l !in laps, "$l")
    }
}

/** Ported from the desktop's plugins/pit-window/test/pit-window.test.ts. */
class PitWindowTest {
    private val end = Races.topics("bahrainEnd")
    private val stops = PitWindow.stopCosts(end.lapHistory, end.stints, end.messages)

    /** OpenF1's lap times and pit-lane times for the 2024 Bahrain GP, as the answer key. */
    @Suppress("UNCHECKED_CAST")
    private val openf1 = Races.json("openf1-bahrain.json")

    @Suppress("UNCHECKED_CAST")
    private val pits = openf1["pits"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private val laps = openf1["laps"] as List<Map<String, Any?>>

    @Test
    fun `measures nearly every stop OpenF1 lists`() {
        assertEquals(43, pits.size)
        assertEquals(40, stops.size)
        for (s in stops) assertTrue(pits.any { it["n"] == s.number && (it["lap"] as Number).toInt() == s.inLap })
    }

    @Test
    fun `uses the same in-lap and out-lap times as OpenF1`() {
        fun ours(n: String, lap: Int) = end.lapHistory!!.getValue(n).first { it.lap == lap }.ms!! / 1000.0
        fun theirs(n: String, lap: Int) = (laps.first { it["n"] == n && (it["lap"] as Number).toInt() == lap }["duration"] as Number).toDouble()
        for (s in stops) {
            assertEquals(theirs(s.number, s.inLap), ours(s.number, s.inLap), 0.0005)
            assertEquals(theirs(s.number, s.inLap + 1), ours(s.number, s.inLap + 1), 0.0005)
        }
    }

    @Test
    fun `comes to about the time OpenF1 measures in the pit lane`() {
        val loss = PitWindow.estimate(end).loss
        assertEquals(24.0, loss)
        val lane = Race.median(pits.map { (it["lane"] as Number).toDouble() })!!
        assertEquals(24.8, lane)
        assertTrue(abs(loss - lane) < 1.5)
    }

    private val now = PitWindow.estimate(Races.topics("bahrainLap31"))

    @Test
    fun `uses the stops so far`() {
        assertEquals("session", now.lossFrom)
        assertEquals(22, now.stops.size)
        assertEquals(24.1, now.loss)
    }

    @Test
    fun `places the car among the others by gap to the leader`() {
        // Verstappen leads; Sainz is 18.8 s and Russell 28.6 s behind. A 24.1 s stop puts him between them.
        val ver = now.rows.first { it.number == "1" }
        assertEquals(0.0, ver.gap)
        assertEquals(3, ver.rejoin)
        assertEquals("55", ver.ahead)
        assertEquals("63", ver.behind)
        assertEquals(24.1 - 18.82, ver.gapAhead!!, 0.05)
        assertEquals(28.611 - 24.1, ver.gapBehind!!, 0.05)
    }

    @Test
    fun `uses a typical cost until there is a stop to measure`() {
        val early = PitWindow.estimate(Races.topics("bahrainLap31").copy(stints = emptyMap()))
        assertEquals(PitWindow.DEFAULT_LOSS, early.loss)
        assertEquals("typical", early.lossFrom)
        assertEquals(emptyList(), early.stops)
    }

    @Test
    fun `leaves out stops behind the safety car and switches between wet and dry tyres`() {
        val british = PitWindow.estimate(Races.topics("britishEnd"))
        assertEquals(PitWindow.DEFAULT_LOSS, british.loss)
        assertEquals("typical", british.lossFrom)
    }
}
