package com.racewall.live

import com.racewall.archive.STREAMS
import com.racewall.archive.SessionFiles
import com.racewall.archive.SessionModel
import com.racewall.archive.buildSession
import com.racewall.archive.parseStream
import com.racewall.archive.stripBom
import com.racewall.bus.Bus
import com.racewall.bus.FeedJson
import com.racewall.topics.Schedule
import com.racewall.topics.ScheduledSession
import com.racewall.topics.Topic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RS = "\u001e"

/** A WebSocket stand-in that records what the client sends and lets the test play the server. */
private class FakeSocket(val url: String, val headers: Map<String, String>, val events: SocketEvents) : SocketLike {
    val sent = mutableListOf<String>()
    override fun send(text: String) {
        sent.add(text)
    }
    override fun close() = events.closed()
    fun server(vararg messages: String) = events.message(messages.joinToString("") { it + RS })
}

private class Setup(token: String? = null, scope: TestScope) {
    val sockets = mutableListOf<FakeSocket>()
    val requests = mutableListOf<Triple<String, String, Map<String, String>>>()
    val events = mutableListOf<String>()
    val network = object : LiveNetwork {
        override suspend fun request(method: String, url: String, headers: Map<String, String>): Negotiated {
            requests.add(Triple(method, url, headers))
            return if (method == "OPTIONS") Negotiated(405, "AWSALBCORS=abc123; Path=/", null)
            else Negotiated(200, null, """{"connectionId":"cid","connectionToken":"ctoken"}""")
        }
        override fun socket(url: String, headers: Map<String, String>, events: SocketEvents): SocketLike = FakeSocket(url, headers, events).also { sockets.add(it) }
    }
    val handlers = object : LiveHandlers {
        override fun snapshot(topic: String, data: Any?) {
            events.add("snapshot $topic $data")
        }
        override fun message(topic: String, data: Any?, utc: Long) {
            events.add("message $topic $data ${Instant.ofEpochMilli(utc)}")
        }
        override fun status(status: LiveStatus, detail: String) {
            events.add("status ${status.name.lowercase()}${if (detail.isNotEmpty()) " $detail" else ""}")
        }
    }
    val client = LiveClient(handlers, token, listOf("SessionInfo", "TimingData"), network, scope)
}

/** Ported from the desktop's packages/f1-live/test/live.test.ts. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveClientTest {
    @Test
    fun `negotiates with the cookie and token, then handshakes and subscribes`() = runTest {
        val s = Setup("jwt-token", this)
        s.client.start()
        assertEquals(listOf("OPTIONS", "POST"), s.requests.map { it.first })
        assertEquals("Bearer jwt-token", s.requests[1].third["Authorization"])
        val socket = s.sockets[0]
        assertEquals("wss://livetiming.formula1.com/signalrcore?id=ctoken", socket.url)
        assertEquals(mapOf("Cookie" to "AWSALBCORS=abc123", "Authorization" to "Bearer jwt-token"), socket.headers)
        socket.events.open()
        assertEquals("""{"protocol":"json","version":1}$RS""", socket.sent[0])
        socket.server("{}")
        assertEquals(FeedJson.parse("""{"type":1,"invocationId":"0","target":"Subscribe","arguments":[["SessionInfo","TimingData"]]}"""), FeedJson.parse(socket.sent[1].removeSuffix(RS)))
        s.client.stop()
    }

    @Test
    fun `passes on the snapshot, then each change with its UTC time`() = runTest {
        val s = Setup(scope = this)
        s.client.start()
        val socket = s.sockets[0]
        socket.events.open()
        socket.server("{}")
        socket.server("""{"type":3,"invocationId":"0","result":{"SessionInfo":{"Name":"Race"},"TimingData":{"Lines":{}}}}""")
        socket.server("""{"type":1,"target":"feed","arguments":["TimingData",{"Lines":{"1":{"Position":"1"}}},"2026-10-11T12:00:01.250Z"]}""", """{"type":6}""")
        assertEquals(
            listOf(
                "status connecting",
                "status connected",
                "snapshot SessionInfo {Name=Race}",
                "snapshot TimingData {Lines={}}",
                "message TimingData {Lines={1={Position=1}}} 2026-10-11T12:00:01.250Z",
            ),
            s.events,
        )
        s.client.stop()
    }

    @Test
    fun `pings every 15 seconds once connected`() = runTest {
        val s = Setup(scope = this)
        s.client.start()
        s.sockets[0].events.open()
        s.sockets[0].server("{}")
        advanceTimeBy(15_001)
        assertEquals("""{"type":6}$RS""", s.sockets[0].sent.last())
        s.client.stop()
    }

    @Test
    fun `reconnects with a growing pause after the connection drops, and not after stop`() = runTest {
        val s = Setup(scope = this)
        s.client.start()
        s.sockets[0].events.closed()
        assertEquals("status error connection closed", s.events.last())
        advanceTimeBy(2001)
        runCurrent()
        assertEquals(2, s.sockets.size)
        assertTrue("status reconnecting" in s.events)
        s.client.stop()
        advanceTimeBy(120_000)
        assertEquals(2, s.sockets.size)
    }
}

/** The live and replay readers agree, and a recording reads back as the archive. */
class LiveReplayTest {
    private fun read(f: String) = LiveReplayTest::class.java.getResource("/2025-british-gp-race/$f")?.readText()
        ?: java.io.File("../archive/src/test/resources/2025-british-gp-race/$f").readText()

    @Test
    fun `feeding records one by one matches loading the archive at once`() {
        val files = SessionFiles(read("SessionInfo.json"), STREAMS.associateWith { read("$it.jsonStream") })
        val replayBus = Bus()
        buildSession(files, replayBus)
        // Live: records arrive interleaved in time order, as from the feed.
        val liveBus = Bus()
        val model = SessionModel(liveBus, "f1-live", FeedJson.parse(stripBom(files.sessionInfo)))
        val all = STREAMS.flatMap { name -> parseStream(files.streams.getValue(name)).map { Triple(name, it.t, it.data) } }.sortedBy { it.second }
        var registered = false
        for ((name, t, data) in all) {
            model.add(name, t, data)
            if (!registered) {
                model.register()
                registered = true
            }
        }
        for (t in listOf(3_400_000.0, 3_840_000.0, 3_960_000.0)) {
            for (topic in listOf(Topic.TIMING_POSITIONS, Topic.TIMING_LAPS, Topic.SESSION_STATUS, Topic.LAP_HISTORY, Topic.RACE_CONTROL, Topic.WEATHER)) {
                assertEquals(replayBus.stateAt(topic, t), liveBus.stateAt(topic, t), "$topic at $t")
            }
        }
    }

    @Test
    fun `a recording reads back as the same records`() = runTest {
        val dir = Files.createTempDirectory("racewall-rec").toFile()
        try {
            val rec = Recorder(dir, this)
            val original = parseStream(read("LapCount.jsonStream"))
            for (e in original) rec.add("LapCount", e.t, e.data)
            rec.add("Position.z", 1234.0, "base64text==")
            rec.close()
            assertEquals(original, parseStream(dir.resolve("LapCount.jsonStream").readText()))
            assertEquals("﻿00:00:01.234\"base64text==\"\r\n", dir.resolve("Position.z.jsonStream").readText())
            assertEquals("00:56:09.003", stamp(3_369_003.0))
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** Ported from the desktop's app/main/live-scheduler.test.ts. */
class LiveSchedulerTest {
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()
    private val schedule: Schedule = listOf(
        ScheduledSession("Singapore Grand Prix", "Marina Bay", "Singapore", 17, "Qualifying", at("2026-10-10T13:00:00Z")),
        ScheduledSession("Singapore Grand Prix", "Marina Bay", "Singapore", 17, "Race", at("2026-10-11T12:00:00Z")),
    )

    @Test
    fun `connects from 15 minutes before a session until it can be over`() {
        assertNull(LiveScheduler.sessionNow(schedule, at("2026-10-11T11:40:00Z")))
        assertEquals("Race", LiveScheduler.sessionNow(schedule, at("2026-10-11T11:46:00Z"))?.name)
        assertEquals("Race", LiveScheduler.sessionNow(schedule, at("2026-10-11T14:59:00Z"))?.name)
        assertNull(LiveScheduler.sessionNow(schedule, at("2026-10-11T15:01:00Z")))
        assertEquals("Qualifying", LiveScheduler.sessionNow(schedule, at("2026-10-10T14:30:00Z"))?.name)
    }

    @Test
    fun `announces each session once, in the 15 minutes before it`() {
        val announced = mutableSetOf<String>()
        assertEquals(emptyList(), LiveScheduler.startingSoon(schedule, at("2026-10-11T11:40:00Z"), announced))
        assertEquals(listOf("Race"), LiveScheduler.startingSoon(schedule, at("2026-10-11T11:50:00Z"), announced).map { it.name })
        announced.add("17|Race")
        assertEquals(emptyList(), LiveScheduler.startingSoon(schedule, at("2026-10-11T11:55:00Z"), announced))
    }

    @Test
    fun `takes over once per session, even from a replay, and leaves a user who went back alone`() {
        val taken = mutableSetOf<String>()
        val race = LiveScheduler.sessionNow(schedule, at("2026-10-11T11:46:00Z"))
        assertFalse(LiveScheduler.shouldTakeOver(null, false, taken))
        assertFalse(LiveScheduler.shouldTakeOver(race, true, taken))
        assertTrue(LiveScheduler.shouldTakeOver(race, false, taken))
        taken.add(LiveScheduler.key(race!!))
        assertFalse(LiveScheduler.shouldTakeOver(race, false, taken))
    }
}
