package com.racewall.archive

import com.racewall.bus.FeedJson

/** One change record from an archived feed: `t` is milliseconds from the start of the recording. */
data class FeedEntry(val t: Double, val data: Any?)

private val LINE = Regex("""^(\d+):(\d\d):(\d\d)\.(\d{1,3})(.*)$""")
private val NEWLINE = Regex("\r?\n")

/** Removes the byte-order mark the archive puts at the start of every file. */
fun stripBom(text: String): String = if (text.isNotEmpty() && text[0] == '﻿') text.substring(1) else text

/**
 * Parses a `.jsonStream` file: one record per line, "HH:MM:SS.mmm" then the JSON for that moment.
 * Lines that do not match are skipped.
 */
fun parseStream(text: String): List<FeedEntry> {
    val out = ArrayList<FeedEntry>()
    for (raw in stripBom(text).split(NEWLINE)) {
        val m = LINE.matchEntire(raw) ?: continue
        val (h, min, s, frac, json) = m.destructured
        val ms = frac.padEnd(3, '0').toInt()
        val t = ((h.toLong() * 60 + min.toLong()) * 60 + s.toLong()) * 1000 + ms
        try {
            out.add(FeedEntry(t.toDouble(), FeedJson.parse(json)))
        } catch (_: Exception) {
            // A truncated last line in an archive that is still being written; skip it.
        }
    }
    return out
}
