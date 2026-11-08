package com.racewall.archive

import com.racewall.bus.Bus
import com.racewall.bus.FeedJson
import java.time.Instant

/**
 * RaceWall's own recordings of live sessions, as the desktop's recording.ts. They are in the
 * archive's format, plus `recording.json`, which says the UTC time of session time 0 so a replay
 * is tied to real time.
 *
 * Recordings made before that file existed may hold several runs: after an app restart or a
 * sign-in the recorder appended to the same files with times starting again at 0, each run
 * beginning with a byte-order mark. `joinRuns` puts the runs back on one timeline.
 */
const val RECORDING_META = "recording.json"

private const val BOM = '﻿'
private val LINE = Regex("""^(\d+):(\d\d):(\d\d)\.(\d{1,3})(.*)$""")
private val NEWLINE = Regex("\r?\n")

/** The UTC ms of time 0 from `recording.json`'s text, or null when it is missing or unreadable. */
fun recordingBase(text: String): Double? = try {
    val raw = (FeedJson.parse(stripBom(text)) as? Map<*, *>)?.get("baseUtc") as? String
    raw?.let { Instant.parse(it).toEpochMilli().toDouble() }
} catch (_: Exception) {
    null
}

/** A file's lines, split into runs: each byte-order mark at the start of a line begins one. */
private fun runs(text: String): List<List<String>> {
    val out = ArrayList<MutableList<String>>()
    for (line in text.split(NEWLINE)) {
        val bom = line.startsWith(BOM)
        if (bom || out.isEmpty()) out.add(ArrayList())
        val l = if (bom) line.substring(1) else line
        if (l.isNotEmpty()) out.last().add(l)
    }
    return out.filter { it.isNotEmpty() }
}

private fun timeOf(line: String): Double? {
    val m = LINE.matchEntire(line) ?: return null
    val (h, min, s, frac) = m.destructured
    return (((h.toLong() * 60 + min.toLong()) * 60 + s.toLong()) * 1000 + frac.padEnd(3, '0').toInt()).toDouble()
}

private fun stamp(ms: Double): String {
    val t = maxOf(0L, Math.floor(ms + 0.5).toLong())
    return "%02d:%02d:%02d.%03d".format(t / 3_600_000, (t % 3_600_000) / 60_000, (t % 60_000) / 1000, t % 1000)
}

/**
 * The UTC time of one run's time 0, from the race control messages that arrived during it. Its
 * snapshot (`_kf`) is left out: it holds old messages, stamped with the moment of connecting.
 */
private fun runBase(lines: List<String>): Double? {
    val anchors = ArrayList<Double>()
    for (entry in parseStream(lines.joinToString("\n"))) {
        if ((entry.data as? Map<*, *>)?.get("_kf") == true) continue
        for (m in raceControlMessages(entry.t, entry.data)) if (m.message.utc != 0L) anchors.add(m.message.utc - entry.t)
    }
    if (anchors.isEmpty()) return null
    anchors.sort()
    return anchors[anchors.size / 2]
}

/** The files of a recording on one timeline, and the UTC time of its time 0 when race control says it. */
class JoinedRuns(val files: SessionFiles, val baseUtc: Double?)

/**
 * Puts the runs of a recording on one timeline: each run moves by the time between its start and
 * the first run's, from race control's UTC times; a run with none of those follows the one before.
 * Returns the files unchanged, and no base, when there is a single run.
 */
fun joinRuns(files: SessionFiles): JoinedRuns {
    val split = files.streams.mapValues { (_, text) -> runs(text) }
    val count = split.values.maxOfOrNull { it.size } ?: 0
    if (count <= 1) return JoinedRuns(files, null)
    // Race control decides which run is which; a feed with fewer runs had nothing in the first ones.
    fun at(r: List<List<String>>, k: Int): List<String>? = r.getOrNull(k - (count - r.size))
    val rc = split["RaceControlMessages"] ?: emptyList()
    val bases = (0 until count).map { k -> at(rc, k)?.let(::runBase) }
    fun lastTime(k: Int): Double = split.values.flatMap { r -> (at(r, k) ?: emptyList()).map { timeOf(it) ?: 0.0 } }.maxOrNull() ?: 0.0
    val offsets = ArrayList<Double>().apply { add(0.0) }
    for (k in 1 until count) {
        val after = offsets[k - 1] + lastTime(k - 1)
        val b = bases[k]
        val b0 = bases[0]
        offsets.add(if (b != null && b0 != null) maxOf(after, b - b0) else after)
    }
    val streams = split.mapValues { (_, r) ->
        val out = ArrayList<String>()
        r.forEachIndexed { i, lines ->
            val off = offsets[i + (count - r.size)]
            for (l in lines) {
                val m = LINE.matchEntire(l) ?: continue
                out.add(stamp(timeOf(l)!! + off) + m.groupValues[5])
            }
        }
        if (out.isEmpty()) "" else BOM + out.joinToString("\r\n") + "\r\n"
    }
    return JoinedRuns(SessionFiles(files.sessionInfo, streams), bases[0])
}

/** Builds the replay of a recording, from its files and the text of its `recording.json` (or ""). */
fun buildRecording(files: SessionFiles, meta: String, bus: Bus): SessionModel {
    recordingBase(meta)?.let { return buildSession(files, bus, it) }
    val joined = joinRuns(files)
    return buildSession(joined.files, bus, joined.baseUtc)
}
