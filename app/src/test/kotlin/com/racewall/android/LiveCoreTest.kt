package com.racewall.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.racewall.android.ui.RaceWallTheme
import com.racewall.android.ui.Transport
import com.racewall.android.ui.TransportActions
import com.racewall.archive.ArchiveMeeting
import com.racewall.archive.OutlineText
import com.racewall.clock.ClockState
import com.racewall.live.LiveHandlers
import com.racewall.live.LiveStatus
import com.racewall.topics.Bounds
import com.racewall.topics.CircuitOutline
import com.racewall.topics.Positions
import com.racewall.topics.SessionInfo
import com.racewall.topics.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from the desktop's app/main/core.test.ts, with following the live edge and Sync. */
class LiveCoreTest {
    private fun info(name: String, path: String) = mapOf(
        "Meeting" to mapOf("Name" to "Singapore Grand Prix", "Number" to 17L, "Circuit" to mapOf("ShortName" to "Singapore"), "Country" to mapOf("Name" to "Singapore")),
        "Key" to if (name == "Race") 2L else 1L,
        "Type" to name,
        "Name" to name,
        "StartDate" to "2026-10-11T20:00:00",
        "GmtOffset" to "08:00:00",
        "Path" to path,
    )

    private val quali = "2026/2026-10-11_Singapore_Grand_Prix/2026-10-10_Qualifying/"
    private val race = "2026/2026-10-11_Singapore_Grand_Prix/2026-10-11_Race/"

    private class Setup(val core: Core, val feed: LiveHandlers, val wall: () -> Long, val advance: (Long) -> Unit, val recordings: java.io.File, val circuits: java.io.File)

    private fun connect(): Setup {
        var wall = 1_760_000_000_000L
        var handlers: LiveHandlers? = null
        val dir = Files.createTempDirectory("racewall-core").toFile()
        val circuits = Files.createTempDirectory("racewall-circuits").toFile()
        val core = Core(
            BritishGp.archive(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            now = { wall },
            openF1 = null,
            jolpica = null,
            recordings = dir,
            connect = { h, _ ->
                handlers = h
                object : LiveConnection {
                    override suspend fun start() {}
                    override fun stop() {}
                    override val lastMessage = 0L
                }
            },
            circuits = circuits,
        )
        core.subscribe(setOf(Topic.TIMING_POSITIONS, Topic.SESSION_INFO))
        core.goLive(null)
        return Setup(core, handlers!!, { wall }, { wall += it }, dir, circuits)
    }

    private fun outline(core: Core) = core.bus.answer(Topic.CIRCUIT, 0.0)?.value as CircuitOutline?
    private fun square(size: Double) = CircuitOutline(
        listOf(Triple(0.0, 0.0, 0.0), Triple(size, 0.0, 0.0), Triple(size, size, 0.0), Triple(0.0, size, 0.0)),
        Bounds(0.0, size, 0.0, size),
    )

    @Test
    fun `shows the circuit saved this season at once, or else the newest earlier season's`() {
        val s = connect()
        fun save(name: String, o: CircuitOutline) = s.circuits.resolve(name).writeText(OutlineText.write(o))
        save("2024-Singapore.json", square(1.0))
        save("2025-Singapore.json", square(2.0))
        save("2027-Singapore.json", square(9.0))
        save("2025-Monza.json", square(5.0))
        assertNull(outline(s.core))
        s.feed.snapshot("SessionInfo", info("Qualifying", quali))
        assertEquals(square(2.0), outline(s.core))

        save("2026-Singapore.json", square(3.0))
        s.feed.message("SessionInfo", info("Race", race), s.wall())
        assertEquals(square(3.0), outline(s.core))
        s.core.stopLive()
    }

    @Test
    fun `saves the outline live timing works out, for the next session at the circuit`() {
        val s = connect()
        s.feed.snapshot("SessionInfo", info("Race", race))
        s.feed.snapshot("SessionStatus", mapOf("Status" to "Started"))
        // Every car round a 5 km circle in 100 seconds, four samples a second, for seven minutes.
        val base = s.wall()
        val r = 50_000 / (2 * Math.PI)
        for (t in 0L until 420_000L step 250) {
            val a = t / 100_000.0 * 2 * Math.PI
            val entries = listOf("1", "4", "16", "63").withIndex().associate { (i, n) ->
                n to mapOf("Status" to "OnTrack", "X" to Math.round(r * Math.cos(a + i / 50.0)), "Y" to Math.round(r * Math.sin(a + i / 50.0)), "Z" to 0L)
            }
            s.feed.message("Position.z", mapOf("Position" to listOf(mapOf("Timestamp" to java.time.Instant.ofEpochMilli(base + t).toString(), "Entries" to entries))), base + t)
        }
        val file = s.circuits.resolve("2026-Singapore.json")
        assertFalse(file.exists())
        s.core.tick()
        // The file is written off the main thread.
        val until = System.currentTimeMillis() + 5_000
        while (!file.exists() && System.currentTimeMillis() < until) Thread.sleep(20)
        val saved = OutlineText.read(file.readText())!!
        assertTrue(saved.bounds.maxX - saved.bounds.minX > 15_000)
        s.core.stopLive()
    }

    private fun positions(core: Core) = (core.bus.answer(Topic.TIMING_POSITIONS, Double.MAX_VALUE)?.value as? Positions ?: emptyList())
    private fun sessionInfo(core: Core) = core.bus.answer(Topic.SESSION_INFO, 0.0)?.value as SessionInfo

    @Test
    fun `drops the last session when the feed moves on to the next one`() {
        val s = connect()
        s.feed.snapshot("SessionInfo", info("Qualifying", quali))
        s.feed.snapshot("TimingData", mapOf("Lines" to mapOf("1" to mapOf("Position" to "1", "Line" to 1L), "4" to mapOf("Position" to "2", "Line" to 2L))))
        assertEquals(listOf("1", "4"), positions(s.core).map { it.number })
        assertEquals("Qualifying", sessionInfo(s.core).name)

        s.feed.message("SessionInfo", info("Race", race), s.wall())
        assertEquals("Race", sessionInfo(s.core).name)
        assertEquals(emptyList(), positions(s.core))

        s.feed.message("TimingData", mapOf("Lines" to mapOf("63" to mapOf("Position" to "1", "Line" to 1L))), s.wall())
        assertEquals(listOf("63"), positions(s.core).map { it.number })
        s.core.stopLive()
    }

    @Test
    fun `keeps the session when a reconnect sends the same one again`() {
        val s = connect()
        s.feed.snapshot("SessionInfo", info("Race", race))
        s.feed.snapshot("TimingData", mapOf("Lines" to mapOf("63" to mapOf("Position" to "1", "Line" to 1L))))
        s.feed.snapshot("SessionInfo", info("Race", race))
        assertEquals(listOf("63"), positions(s.core).map { it.number })
        s.core.stopLive()
    }

    @Test
    fun `follows the live edge, held back by the delay, until moved, and Go live brings it back`() {
        val s = connect()
        s.feed.snapshot("SessionInfo", info("Race", race))
        s.core.setDelay(10_000)
        s.advance(60_000)
        s.core.tick()
        assertEquals(50_000.0, s.core.frame.value.clock.time, 300.0)
        assertTrue(s.core.ui.value.session!!.live!!.following)
        assertEquals(Mode.LIVE, s.core.mode())
        s.core.seek(20_000.0)
        s.advance(1_000)
        s.core.tick()
        assertFalse(s.core.ui.value.session!!.live!!.following)
        s.core.followLive()
        assertEquals(51_000.0, s.core.frame.value.clock.time, 300.0)
        s.core.stopLive()
        assertNull(s.core.ui.value.session)
    }

    @Test
    fun `Sync sets the delay from the last lap count change`() {
        val s = connect()
        s.feed.snapshot("SessionInfo", info("Race", race))
        s.advance(30_000)
        s.feed.message("LapCount", mapOf("CurrentLap" to 2L, "TotalLaps" to 62L), s.wall())
        s.advance(12_000)
        assertEquals(12_000L, s.core.syncDelay())
        s.advance(6 * 60_000)
        assertNull(s.core.syncDelay(), "a crossing more than five minutes ago is not the one on TV")
        s.core.stopLive()
    }

    @Test
    fun `records the live session in the archive's format, and lists it as a recording`() {
        val s = connect()
        s.feed.snapshot("SessionInfo", info("Race", race))
        s.feed.message("LapCount", mapOf("CurrentLap" to 2L), s.wall() + 1234)
        s.core.stopLive()
        val list = s.core.listRecordings()
        assertEquals(1, list.size)
        assertEquals("recording:$race".removeSuffix("/"), list[0].path)
        assertEquals("Singapore Grand Prix", list[0].meeting)
        assertTrue(s.recordings.resolve(race).resolve("LapCount.jsonStream").readText().startsWith("﻿00:00:01.234"))
        // And it replays like an archived session.
        runBlocking { s.core.loadSession(list[0].path) }
        assertEquals("Race", s.core.ui.value.session?.info?.name)
        s.recordings.deleteRecursively()
    }

    @Test
    fun `refuses a recording path outside the recordings folder`() {
        val s = connect()
        s.core.stopLive()
        runBlocking { s.core.loadSession("recording:../../etc") }
        assertTrue("outside the recordings folder" in s.core.ui.value.error)
    }
}

/** The transport bar while live: the status, Go live when behind, the delay and Sync. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
class LiveTransportTest {
    @get:Rule
    val compose = createComposeRule()

    private var live = 0
    private var syncs = 0
    private val actions = object : TransportActions {
        override fun toggle() {}
        override fun seek(t: Double) {}
        override fun setSpeed(speed: Double) {}
        override suspend fun listSeason(year: Int): List<ArchiveMeeting> = emptyList()
        override fun replay(path: String) {}
        override fun goLive() {
            live++
        }
        override fun syncDelay() {
            syncs++
        }
    }

    private fun show(ui: UiState, frame: Frame = Frame(ClockState(40_000.0, true, 1.0, 0.0, 52_000.0))) = compose.setContent {
        var picker by remember { mutableStateOf(false) }
        RaceWallTheme { Transport(frame, ui, actions, picker, { picker = it }) }
    }

    private fun meta(status: LiveStatus, following: Boolean) =
        SessionMeta(SessionInfo(0, 0, 0, "", "", "", "", "", "", "", ""), 0.0, 52_000.0, null, false, null, LiveInfo(status, "", following, 8_000))

    @Test
    fun `shows Live, the delay and Sync, and offers Go live when behind the live moment`() {
        show(UiState(session = meta(LiveStatus.CONNECTED, following = false)))
        compose.onNodeWithText("Live").assertExists()
        compose.onNodeWithText("Delay 8 s").assertExists()
        compose.onNodeWithText("Go live (12 s behind)").performClick()
        assertEquals(1, live)
        compose.onNodeWithContentDescription("Sync").performClick()
        assertEquals(1, syncs)
    }

    @Test
    fun `says when the connection is being made again`() {
        show(UiState(session = meta(LiveStatus.RECONNECTING, following = true)))
        compose.onNodeWithText("Reconnecting…").assertExists()
    }

    @Test
    fun `offers to go live when a session is on`() {
        show(UiState(onNow = OnNow("Singapore Grand Prix", "Race")))
        compose.onNodeWithText("Go live · Race").performClick()
        assertEquals(1, live)
        compose.onNodeWithContentDescription("Pick a past session").performClick()
        compose.onNodeWithText("Live now: Singapore Grand Prix · Race").assertExists()
    }
}
