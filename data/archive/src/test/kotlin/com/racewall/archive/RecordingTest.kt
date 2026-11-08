package com.racewall.archive

import com.racewall.bus.Bus
import com.racewall.topics.RaceControlMessages
import com.racewall.topics.Topic
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Ported from the desktop's archive.test.ts: UTC times and recordings. */
class RecordingTest {
    private val info = """{"Meeting":{"Name":"São Paulo Grand Prix"},"Name":"Race","Path":"2026/x/"}"""
    private fun files(streams: Map<String, String>) = SessionFiles(info, STREAMS.associateWith { streams[it] ?: "" })
    private fun utc(iso: String) = Instant.parse(iso).toEpochMilli().toDouble()
    private fun rcLine(t: String, utc: String, text: String, kf: Boolean = false) =
        if (kf) """$t{"Messages":[{"Utc":"$utc","Message":"$text"}],"_kf":true}""" else """$t{"Messages":{"1":{"Utc":"$utc","Message":"$text"}}}"""

    @Test
    fun `takes no UTC anchor from a snapshot's old race control messages`() {
        val rc = listOf("﻿" + rcLine("00:00:01.000", "2026-11-08T16:13:18", "OLD", true), rcLine("00:05:42.000", "2026-11-08T17:26:03", "NEW")).joinToString("\r\n")
        val s = buildSession(files(mapOf("RaceControlMessages" to rc)), Bus())
        assertEquals(utc("2026-11-08T17:20:21Z"), s.utcAt(0.0))
    }

    @Test
    fun `uses the UTC time a recording saved for its time 0`() {
        val s = buildRecording(files(mapOf("LapCount" to "﻿00:00:01.000{\"CurrentLap\":2}")), """{"baseUtc":"2026-11-08T17:20:00.000Z"}""", Bus())
        assertEquals(utc("2026-11-08T17:20:01Z"), s.utcAt(1000.0))
    }

    @Test
    fun `puts an older recording's runs, each starting again at 0, back on one timeline`() {
        // Run 1 started at 17:20:21, run 2 (after a restart) at 17:45:38.
        val rc = listOf(
            "﻿" + rcLine("00:00:01.000", "2026-11-08T16:13:18", "OLD", true),
            rcLine("00:05:42.000", "2026-11-08T17:26:03", "ONE"),
            "﻿" + rcLine("00:00:00.800", "2026-11-08T16:13:18", "OLD", true),
            rcLine("00:00:14.000", "2026-11-08T17:45:52", "TWO"),
        ).joinToString("\r\n")
        val laps = listOf("﻿00:00:01.000{\"CurrentLap\":12}", "00:23:36.000{\"CurrentLap\":30}", "﻿00:00:00.800{\"CurrentLap\":31}", "00:00:50.000{\"CurrentLap\":32}").joinToString("\r\n")
        val joined = joinRuns(files(mapOf("RaceControlMessages" to rc, "LapCount" to laps)))
        assertEquals(utc("2026-11-08T17:20:21Z"), joined.baseUtc)
        val run2 = utc("2026-11-08T17:45:38Z") - utc("2026-11-08T17:20:21Z")
        assertEquals(listOf(1000.0, 23 * 60_000.0 + 36_000, run2 + 800, run2 + 50_000), parseStream(joined.files.streams.getValue("LapCount")).map { it.t })
        assertEquals(2, joined.files.streams.getValue("LapCount").split('﻿').size)

        val bus = Bus()
        val s = buildRecording(files(mapOf("RaceControlMessages" to rc, "LapCount" to laps)), "", bus)
        assertEquals(run2 + 50_000, s.end)
        // The second snapshot's old messages are not shown twice.
        assertEquals(3, (bus.answer(Topic.RACE_CONTROL, s.end)?.value as RaceControlMessages).size)
    }

    @Test
    fun `follows a run with no race control on straight after the one before`() {
        val laps = listOf("﻿00:00:01.000{\"CurrentLap\":1}", "00:01:00.000{\"CurrentLap\":2}", "﻿00:00:02.000{\"CurrentLap\":3}").joinToString("\r\n")
        val joined = joinRuns(files(mapOf("LapCount" to laps)))
        assertNull(joined.baseUtc)
        assertEquals(listOf(1000.0, 60_000.0, 62_000.0), parseStream(joined.files.streams.getValue("LapCount")).map { it.t })
    }

    @Test
    fun `leaves a recording of one run as it is`() {
        val f = files(mapOf("LapCount" to "﻿00:00:01.000{\"CurrentLap\":2}"))
        val joined = joinRuns(f)
        assertSame(f, joined.files)
        assertNull(joined.baseUtc)
    }
}
