package com.racewall.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** "HH:MM:SS.mmm" of session milliseconds, as the archive writes it. */
fun stamp(ms: Double): String {
    val t = maxOf(0L, Math.floor(ms + 0.5).toLong())
    return "%02d:%02d:%02d.%03d".format(t / 3_600_000, (t % 3_600_000) / 60_000, (t % 60_000) / 1000, t % 1000)
}

/** A feed value as JSON text, as `JSON.stringify`. */
fun toJson(value: Any?): String = Json.encodeToString(JsonElement.serializer(), element(value))

private fun element(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Long -> JsonPrimitive(v)
    is Int -> JsonPrimitive(v)
    is Double -> if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 1e15) JsonPrimitive(v.toLong()) else JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to element(x) })
    is List<*> -> JsonArray(v.map(::element))
    else -> JsonPrimitive(v.toString())
}

/**
 * Records a live session to disk in exactly the format of F1's archive, as the desktop's
 * recorder.ts: SessionInfo.json and one `.jsonStream` file per feed, each line "HH:MM:SS.mmm"
 * followed by the JSON (or, for the compressed car feeds, the quoted base64). So a recorded session
 * replays through the same reader as an archived one.
 */
class Recorder(val dir: File, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val started = HashSet<String>()
    private val buffer = LinkedHashMap<String, MutableList<String>>()
    private val timer: Job

    init {
        dir.mkdirs()
        // Writes are batched once a second, so a busy feed does not hit the disk for every message.
        timer = scope.launch {
            while (isActive) {
                delay(1000)
                flush()
            }
        }
    }

    @Synchronized
    fun writeInfo(info: Any?) {
        File(dir, "SessionInfo.json").writeText("﻿" + toJson(info), Charsets.UTF_8)
    }

    /** One record of a feed at session time `t` (ms from the start of the recording). */
    @Synchronized
    fun add(topic: String, t: Double, data: Any?) {
        if (topic == "SessionInfo" || topic == "Heartbeat") return
        // Compressed car feeds arrive as base64 text and are stored quoted, as the archive does.
        buffer.getOrPut(topic) { ArrayList() }.add(stamp(t) + toJson(data))
    }

    @Synchronized
    fun flush() {
        for ((topic, lines) in buffer) {
            if (lines.isEmpty()) continue
            val head = if (topic in started) "" else "﻿"
            File(dir, "$topic.jsonStream").appendText(head + lines.joinToString("\r\n") + "\r\n", Charsets.UTF_8)
            started.add(topic)
            lines.clear()
        }
    }

    fun close() {
        timer.cancel()
        flush()
    }
}
