package com.racewall.android

import com.racewall.live.LiveHandlers
import com.racewall.topics.Positions
import com.racewall.topics.RaceControlMessages
import com.racewall.topics.RadioClips
import com.racewall.topics.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ported from the desktop's app/main/core.test.ts: live timing and the clock. */
class LiveClockTest {
    /** Server time at the start of each test. */
    private val start = Instant.parse("2026-11-08T17:20:00Z").toEpochMilli()
    private val iso = { ms: Long -> Instant.ofEpochMilli(ms).toString() }
    private val race = "2026/2026-11-08_São_Paulo_Grand_Prix/2026-11-08_Race/"
    private val info = mapOf("Meeting" to mapOf("Name" to "São Paulo Grand Prix", "Circuit" to mapOf("ShortName" to "Interlagos")), "Key" to 2L, "Type" to "Race", "Name" to "Race", "Path" to race)
    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() {
        for (d in dirs) d.deleteRecursively()
    }

    private class Client(val handlers: LiveHandlers, val token: String?) {
        var stopped = false
    }

    private inner class Setup(delay: Long = 0, val recordings: File = Files.createTempDirectory("racewall-rec").toFile().also { dirs.add(it) }) {
        var wall = start
        val clients = ArrayList<Client>()
        val core = Core(
            BritishGp.archive(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            now = { wall },
            openF1 = null,
            jolpica = null,
            recordings = recordings,
            connect = { h, token ->
                val c = Client(h, token)
                clients.add(c)
                object : LiveConnection {
                    override suspend fun start() {}
                    override fun stop() {
                        c.stopped = true
                    }
                    override val lastMessage = 0L
                }
            },
            clockNow = { wall.toDouble() },
        )

        init {
            core.subscribe(setOf(Topic.TIMING_POSITIONS, Topic.RACE_CONTROL, Topic.RADIO))
            core.setDelay(delay)
            core.goLive(null)
        }

        val feed get() = clients.last().handlers
        fun advance(ms: Long) {
            wall += ms
        }
        fun tick() = core.tick()
        val time get() = core.frame.value.clock.time
        val playing get() = core.frame.value.clock.playing
        fun leader() = (core.bus.answer(Topic.TIMING_POSITIONS, time)?.value as Positions?)?.firstOrNull()?.number
        fun utcOf0(): Double? = core.frame.value.utc?.let { it - core.frame.value.clock.time }
    }

    private fun lines(n: String) = mapOf("Lines" to mapOf(n to mapOf("Position" to "1", "Line" to 1L)))

    @Test
    fun `fills the widgets from the first snapshot at once, even with a broadcast delay`() {
        val s = Setup(30_000)
        s.feed.snapshot("Heartbeat", mapOf("Utc" to iso(start - 4000), "_kf" to true))
        s.feed.snapshot("SessionInfo", info)
        s.feed.snapshot("TimingData", lines("63"))
        s.tick()
        assertEquals("63", s.leader())
        s.core.stopLive()
    }

    @Test
    fun `ties session time 0 to the server's time, from the snapshot's Heartbeat or else the first message`() {
        val s = Setup()
        s.feed.snapshot("Heartbeat", mapOf("Utc" to iso(start - 2000), "_kf" to true))
        s.feed.message("LapCount", mapOf("CurrentLap" to 3L), start + 1000)
        s.tick()
        assertEquals((start - 2000).toDouble(), s.utcOf0())
        s.core.stopLive()

        val o = Setup()
        o.feed.snapshot("TimingData", lines("1"))
        o.feed.message("LapCount", mapOf("CurrentLap" to 3L), start + 7000)
        o.tick()
        assertEquals((start + 7000).toDouble(), o.utcOf0())
        o.core.stopLive()
    }

    @Test
    fun `follows the live edge in server time when the device clock is behind`() {
        val s = Setup()
        s.feed.snapshot("Heartbeat", mapOf("Utc" to iso(start + 5000), "_kf" to true))
        s.feed.message("TimingData", lines("1"), start + 7000)
        s.tick()
        assertTrue(s.time >= 2000, "clock at ${s.time}")
        assertEquals("1", s.leader())
        s.core.stopLive()
    }

    @Test
    fun `lets the F1 TV player in RaceWall drive the clock with no delay, and keeps following live`() {
        val s = Setup(35_829)
        s.feed.snapshot("Heartbeat", mapOf("Utc" to iso(start), "_kf" to true))
        s.advance(60_000)
        s.feed.message("LapCount", mapOf("CurrentLap" to 5L), start + 60_000)
        s.tick()
        assertEquals(60_000.0 - 35_829, s.time, 100.0)
        s.core.videoReport(40_000.0, playing = true, stalled = false)
        s.tick()
        assertEquals(40_000.0, s.time, 100.0)
        assertTrue(s.playing)
        val live = s.core.ui.value.session?.live!!
        assertTrue(live.following)
        assertEquals(0L, live.delayMs)
        assertTrue(live.video)
        s.core.videoReport(40_100.0, playing = false, stalled = false)
        s.tick()
        assertFalse(s.playing)
        assertEquals(40_100.0, s.time)
        assertTrue(s.core.ui.value.session?.live!!.following)
        s.core.stopLive()
    }

    @Test
    fun `goes to the live edge while the player is stuck buffering for over 2 seconds, and back when it moves`() {
        val s = Setup()
        s.feed.snapshot("Heartbeat", mapOf("Utc" to iso(start), "_kf" to true))
        s.advance(60_000)
        s.core.videoReport(30_000.0, playing = true, stalled = false)
        s.advance(500)
        s.core.videoReport(30_500.0, playing = true, stalled = true)
        s.tick()
        assertEquals(30_500.0, s.time)
        repeat(10) {
            s.advance(250)
            s.core.videoReport(30_500.0, playing = true, stalled = true)
            s.tick()
        }
        assertEquals(63_000.0, s.time, 100.0)
        assertFalse(s.core.ui.value.session?.live!!.video)
        s.core.videoReport(30_600.0, playing = true, stalled = false)
        s.tick()
        assertEquals(30_600.0, s.time, 100.0)
        s.core.stopLive()
    }

    @Test
    fun `goes to the live edge when the player stops reporting`() {
        val s = Setup()
        s.feed.snapshot("Heartbeat", mapOf("Utc" to iso(start), "_kf" to true))
        s.advance(60_000)
        s.core.videoReport(30_000.0, playing = true, stalled = false)
        s.advance(2500)
        s.tick()
        assertEquals(62_500.0, s.time, 100.0)
        s.core.stopLive()
    }

    @Test
    fun `never rewinds to the start when play comes at the live end`() {
        val s = Setup()
        s.feed.snapshot("Heartbeat", mapOf("Utc" to iso(start), "_kf" to true))
        s.advance(10_000)
        s.tick()
        s.core.pause()
        s.advance(5000)
        s.core.videoReport(10_000.0, playing = true, stalled = false)
        s.tick()
        assertTrue(s.time >= 10_000, "clock at ${s.time}")
        s.core.play()
        assertTrue(s.time >= 10_000, "clock at ${s.time}")
        s.core.stopLive()
    }

    @Test
    fun `does not repeat race control messages or radio clips that a reconnect's snapshot sends again`() {
        val s = Setup()
        val rc = mapOf("Utc" to "2026-11-08T17:20:03", "Lap" to 1L, "Category" to "Other", "Message" to "TRACK LIMITS")
        val clip = mapOf("Utc" to "2026-11-08T17:20:04.000Z", "RacingNumber" to "1", "Path" to "TeamRadio/NOR_1.mp3")
        s.feed.snapshot("SessionInfo", info)
        s.feed.message("RaceControlMessages", mapOf("Messages" to mapOf("1" to rc)), start + 3500)
        s.feed.message("TeamRadio", mapOf("Captures" to mapOf("1" to clip)), start + 4500)
        s.advance(10_000)
        s.feed.snapshot("RaceControlMessages", mapOf("Messages" to listOf(rc), "_kf" to true))
        s.feed.snapshot("TeamRadio", mapOf("Captures" to listOf(clip), "_kf" to true))
        s.tick()
        assertEquals(1, (s.core.bus.answer(Topic.RACE_CONTROL, s.time)?.value as RaceControlMessages).size)
        assertEquals(1, (s.core.bus.answer(Topic.RADIO, s.time)?.value as RadioClips).size)
        s.core.stopLive()
    }

    @Test
    fun `swaps only the connection on a sign-in during live timing - the session and its history stay`() {
        val s = Setup()
        s.feed.snapshot("SessionInfo", info)
        s.feed.message("TimingData", lines("63"), start + 1000)
        s.advance(5000)
        s.core.reconnectLive("token")
        assertEquals(2, s.clients.size)
        assertTrue(s.clients[0].stopped)
        assertEquals("token", s.clients[1].token)
        // The old connection is not heard any more.
        s.clients[0].handlers.message("TimingData", lines("4"), start + 5000)
        s.tick()
        assertEquals("63", s.leader())
        // The new one's snapshot goes at the live edge, after what the session already had.
        s.feed.snapshot("TimingData", lines("16"))
        s.advance(100)
        s.tick()
        assertEquals("16", s.leader())
        assertEquals("63", (s.core.bus.answer(Topic.TIMING_POSITIONS, 1000.0)?.value as Positions).first().number)
        s.core.stopLive()
    }

    @Test
    fun `records the UTC time of time 0 beside the recording, and carries on in the same folder after a restart`() {
        val s = Setup()
        s.feed.snapshot("Heartbeat", mapOf("Utc" to iso(start), "_kf" to true))
        s.feed.snapshot("SessionInfo", info)
        s.feed.message("LapCount", mapOf("CurrentLap" to 2L), start + 1000)
        s.core.stopLive()
        val folder = File(s.recordings, race)
        assertEquals("{\"baseUtc\":\"2026-11-08T17:20:00Z\"}", File(folder, "recording.json").readText())

        // RaceWall starts again a minute later, in the same folder.
        val again = Setup(recordings = s.recordings)
        again.wall = start + 60_000
        again.core.stopLive()
        again.core.goLive(null)
        again.feed.snapshot("Heartbeat", mapOf("Utc" to iso(start + 60_000), "_kf" to true))
        again.feed.snapshot("SessionInfo", info)
        again.feed.message("LapCount", mapOf("CurrentLap" to 3L), start + 61_000)
        again.core.stopLive()
        val text = File(folder, "LapCount.jsonStream").readText()
        assertEquals(2, text.split('﻿').size)
        assertTrue("00:00:01.000{\"CurrentLap\":2}" in text, text)
        assertTrue("00:01:01.000{\"CurrentLap\":3}" in text, text)
    }
}
