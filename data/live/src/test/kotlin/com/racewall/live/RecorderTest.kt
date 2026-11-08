package com.racewall.live

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Ported from the desktop's live.test.ts: a recording made again into the same folder. */
class RecorderTest {
    @Test
    fun `keeps the first time 0, so its times carry on, with no second byte-order mark`() = runTest {
        val dir = Files.createTempDirectory("racewall-rec").toFile()
        try {
            val t0 = Instant.parse("2026-11-08T17:20:00Z").toEpochMilli().toDouble()
            val first = Recorder(dir, t0, this)
            first.add("LapCount", t0 + 1000, mapOf("CurrentLap" to 12L))
            first.close()
            // The app restarts 25 minutes later.
            val again = Recorder(dir, t0 + 25 * 60_000, this)
            assertEquals(t0, again.baseUtc)
            again.add("LapCount", t0 + 25 * 60_000 + 800, mapOf("CurrentLap" to 31L))
            again.close()
            assertEquals("﻿00:00:01.000{\"CurrentLap\":12}\r\n00:25:00.800{\"CurrentLap\":31}\r\n", File(dir, "LapCount.jsonStream").readText())
            assertEquals("{\"baseUtc\":\"2026-11-08T17:20:00Z\"}", File(dir, "recording.json").readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `made before recording json existed starts its run with a byte-order mark, as before`() = runTest {
        val dir = Files.createTempDirectory("racewall-rec").toFile()
        try {
            File(dir, "LapCount.jsonStream").writeText("﻿00:00:01.000{\"CurrentLap\":12}\r\n")
            val again = Recorder(dir, 5000.0, this)
            again.add("LapCount", 6000.0, mapOf("CurrentLap" to 31L))
            again.close()
            assertEquals("﻿00:00:01.000{\"CurrentLap\":12}\r\n﻿00:00:01.000{\"CurrentLap\":31}\r\n", File(dir, "LapCount.jsonStream").readText())
            assertFalse(File(dir, "recording.json").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
