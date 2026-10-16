package com.racewall.bus

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The F1 feed's JSON as plain, changeable values, the way the desktop app holds it in JavaScript:
 * objects are `MutableMap<String, Any?>`, arrays `MutableList<Any?>`, numbers `Long` when whole and
 * `Double` otherwise, then `String`, `Boolean` and `null`.
 */
object FeedJson {
    private val json = Json { isLenient = false }

    /**
     * One copy of each key name: a race's feed repeats the same few hundred keys ("Sectors",
     * "Value", driver numbers) hundreds of thousands of times. Capped, in case a feed sends odd keys.
     */
    private val keys = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val MAX_KEYS = 20_000

    private fun key(k: String): String = keys[k] ?: k.also { if (keys.size < MAX_KEYS) keys.putIfAbsent(it, it) }

    /** Throws on text that is not JSON, like `JSON.parse`. */
    fun parse(text: String): Any? = plain(json.parseToJsonElement(text))

    /**
     * Compact JSON text for a feed value, which [parse] turns back into the same value: whole
     * numbers stay `Long` and the others `Double`.
     */
    fun write(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, k as String)
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(out, v)
                }
                out.append(']')
            }
            is String -> writeString(out, value)
            is Boolean -> out.append(value)
            // Kotlin writes a Double with a point or an exponent ("2.0", "1.0E10"), so it reads back as a Double.
            is Double -> if (value.isNaN() || value.isInfinite()) out.append("null") else out.append(value.toString())
            is Float -> write(out, value.toDouble())
            is Number -> out.append(value.toLong())
            else -> writeString(out, value.toString())
        }
    }

    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> out.append("\\u").append(String.format("%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private fun plain(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> LinkedHashMap<String, Any?>(e.size * 2).also { m -> for ((k, v) in e) m[key(k)] = plain(v) }
        is JsonArray -> ArrayList<Any?>(e.size).also { l -> for (v in e) l.add(plain(v)) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            else -> e.booleanOrNull ?: number(e.content)
        }
    }

    private fun number(text: String): Any {
        if (text.none { it == '.' || it == 'e' || it == 'E' }) text.toLongOrNull()?.let { return it }
        return text.toDouble()
    }
}

/** A deep copy: nothing in the result is shared with `value`. */
fun deepClone(value: Any?): Any? = when (value) {
    is Map<*, *> -> LinkedHashMap<String, Any?>(value.size * 2).also { m -> for ((k, v) in value) m[k as String] = deepClone(v) }
    is List<*> -> ArrayList<Any?>(value.size).also { l -> for (v in value) l.add(deepClone(v)) }
    else -> value
}

/** How JavaScript's `String(v)` writes a feed value: whole numbers without a decimal point. */
fun jsString(value: Any?): String = when (value) {
    null -> ""
    is Double -> if (value % 1.0 == 0.0 && !value.isInfinite()) value.toLong().toString() else value.toString()
    else -> value.toString()
}
