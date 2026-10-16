package com.racewall.android.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The app's settings, with the desktop's own field names and defaults (app/shared/api.ts), so a
 * settings.json moves between the two. `theme` and `tray` are kept for that reason only: Android
 * has the OLED theme alone and no tray.
 */
@Serializable
data class Settings(
    val theme: String = "oled",
    val dimPanels: Boolean = false,
    val pixelShift: Boolean = true,
    val fadeChrome: Boolean = true,
    val idleDim: Boolean = true,
    /** Archive path of the last session the user loaded. */
    val lastSession: String? = null,
    /** Data sources, first asked first. */
    val sourceOrder: List<String> = DEFAULT_SOURCE_ORDER,
    /** Connect to live timing on its own when a session is on. */
    val autoLive: Boolean = true,
    /** Switch to the Race layout when a session goes live, and Between sessions after. */
    val autoLayout: Boolean = true,
    /** How you watch, which sets the broadcast delay. */
    val viewing: String = "in-app",
    /** The broadcast delay for each way of watching, in ms; Sync sets the current one. */
    val delays: Map<String, Long> = DEFAULT_DELAYS,
    val notifySoon: Boolean = true,
    val notifyRed: Boolean = true,
    val notifySafetyCar: Boolean = true,
    val tray: Boolean = true,
    /** Look for a new release a minute after start; RaceWall always asks before downloading. */
    val autoUpdate: Boolean = true,
    /** For each F1 TV replay, session time minus video time in ms, set once with "Mark the start". */
    val videoOffsets: Map<String, Double> = emptyMap(),
) {
    fun delay(): Long = delays[viewing] ?: DEFAULT_DELAYS[viewing] ?: 0
}

/** How you watch, and its name. */
val VIEWING = listOf(
    "in-app" to "F1 TV in RaceWall",
    "f1tv-tv" to "F1 TV app on a TV",
    "satellite" to "Satellite TV",
    "cable" to "Cable TV",
    "stream" to "Another online stream",
)

/**
 * Starting points only, not measurements: delays vary by country, provider and device. Press Sync
 * once during a session to set yours exactly.
 */
val DEFAULT_DELAYS = mapOf("in-app" to 0L, "f1tv-tv" to 30_000L, "satellite" to 8_000L, "cable" to 10_000L, "stream" to 40_000L)

val DEFAULT_SOURCE_ORDER = listOf("f1-live", "f1-archive", "openf1", "jolpica")

/** Reads and writes JSON files the way the desktop's JsonStore does: to a temporary file, then renamed. */
class JsonStore(private val file: File) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    inline fun <reified T> read(default: T): T = try {
        if (fileExists()) json.decodeFromString<T>(text()) else default
    } catch (_: Exception) {
        default
    }

    inline fun <reified T> write(value: T) = save(json.encodeToString(value))

    fun fileExists() = file.isFile
    fun text() = file.readText()

    fun save(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
