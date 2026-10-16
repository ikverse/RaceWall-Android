package com.racewall.android.widgets

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.racewall.android.Frame
import com.racewall.android.Mode
import com.racewall.android.ui.Rw
import com.racewall.topics.Compound
import com.racewall.topics.DriverList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One setting a widget offers, as the desktop manifests' `widget.settings`. */
sealed class Field(val key: String, val label: String, val note: String) {
    class Toggle(key: String, label: String, val default: Boolean, note: String = "") : Field(key, label, note)
    class Select(key: String, label: String, val options: List<Pair<String, String>>, val default: String, note: String = "") : Field(key, label, note)
    class Number(key: String, label: String, val min: Double, val max: Double, val step: Double, val default: Double, note: String = "") : Field(key, label, note)
}

/** A panel's settings with each widget's defaults filled in. */
class Values(private val fields: List<Field>, private val raw: Map<String, JsonElement>?) {
    private fun field(key: String) = fields.firstOrNull { it.key == key }
    private fun prim(key: String) = raw?.get(key) as? JsonPrimitive

    fun bool(key: String): Boolean = prim(key)?.takeIf { !it.isString }?.booleanOrNull ?: (field(key) as? Field.Toggle)?.default ?: false

    fun str(key: String): String = prim(key)?.contentOrNull ?: (field(key) as? Field.Select)?.default ?: ""

    fun num(key: String): Double = prim(key)?.doubleOrNull ?: (field(key) as? Field.Number)?.default ?: 0.0

    /** The value as stored, for the settings popover. */
    fun json(key: String): JsonElement = raw?.get(key) ?: when (val f = field(key)) {
        is Field.Toggle -> JsonPrimitive(f.default)
        is Field.Select -> JsonPrimitive(f.default)
        is Field.Number -> JsonPrimitive(f.default)
        null -> JsonPrimitive("")
    }
}

/** What a widget is given each frame. */
class WidgetScope(
    /** Only the topics this widget reads, so it redraws when they change. */
    val frame: Frame,
    val mode: Mode,
    val focus: String?,
    val values: Values,
    val onFocus: (String) -> Unit,
    /** Range queries, such as telemetry over the last minute. */
    val range: (topic: String, from: Double, to: Double, filter: List<String>?) -> Any?,
    /** Saves one of the panel's own settings, as the track map does when it is turned. */
    val setSetting: (key: String, value: JsonElement) -> Unit,
)

/**
 * A widget: its manifest (title, sizes, topics read, settings) and how it draws. The desktop loads
 * these as plugins; here each is built in.
 */
class WidgetSpec(
    val id: String,
    val title: String,
    val min: Pair<Int, Int>,
    val size: Pair<Int, Int>,
    val reads: Set<String>,
    val fields: List<Field> = emptyList(),
    /** The panel header's meta text, as the desktop widget's `ctx.setMeta`. */
    val meta: (WidgetScope) -> String = { "" },
    val content: @Composable (WidgetScope) -> Unit,
)

/** The `.empty` message: dim, padded. */
@Composable
fun Empty(text: String) {
    BasicText(text, style = Rw.text(13f, Rw.t3), modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp))
}

/** The tyre letters and colours of plugins/_shared/ui.ts. */
val TYRE: Map<Compound, Pair<String, Color>> = mapOf(
    Compound.SOFT to ("S" to Rw.soft),
    Compound.MEDIUM to ("M" to Rw.medium),
    Compound.HARD to ("H" to Rw.hard),
    Compound.INTERMEDIATE to ("I" to Rw.inter),
    Compound.WET to ("W" to Rw.wet),
    Compound.UNKNOWN to ("?" to Rw.t3),
)

/** Race control flag colours, as plugins/_shared/ui.ts. */
val FLAG_COLOUR: Map<String, Color> = mapOf(
    "YELLOW" to Rw.yellow,
    "DOUBLE YELLOW" to Rw.yellow,
    "RED" to Rw.red,
    "GREEN" to Rw.green,
    "CLEAR" to Rw.green,
    "BLUE" to Rw.wet,
    "CHEQUERED" to Rw.t1,
)

fun teamColour(drivers: DriverList, number: String): Color = drivers[number]?.teamColour?.let(::hex) ?: Rw.t3

fun code(drivers: DriverList, number: String): String = drivers[number]?.code?.ifEmpty { null } ?: number

/** "#RRGGBB" to a colour. */
fun hex(text: String): Color = Color(0xFF000000 or text.removePrefix("#").toLong(16))

private val UTC_CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)

/** "14:47:12" in UTC. */
fun utcClock(ms: Long): String = UTC_CLOCK.format(Instant.ofEpochMilli(ms))

/** JavaScript's `toFixed`: always a dot, rounded half up. */
fun Double.fixed(digits: Int): String = java.math.BigDecimal(this).setScale(digits, java.math.RoundingMode.HALF_UP).toPlainString()

/** JavaScript's Math.round. */
fun jsRound(v: Double): Long = Math.floor(v + 0.5).toLong()
