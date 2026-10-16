package com.racewall.android.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.racewall.android.ui.Rw
import com.racewall.android.ui.RwSelect
import com.racewall.topics.CarTelemetryMap
import com.racewall.topics.TelemetrySample
import com.racewall.topics.Topic
import kotlinx.serialization.json.JsonPrimitive

/** The window choices, as the manifest labels them. */
private val WINDOW_LABEL = mapOf(30_000 to "30 seconds", 60_000 to "60 seconds", 120_000 to "2 minutes", 300_000 to "5 minutes")

/** The traces that can be shown: setting, name, sample field, range, and share of the height. */
private class Track(val setting: String, val name: String, val field: (TelemetrySample) -> Int, val lo: Double, val hi: Double, val weight: Int)

private val TRACKS = listOf(
    Track("speed", "Speed", { it.speed }, 0.0, 350.0, 2),
    Track("throttle", "Throttle", { it.throttle }, 0.0, 100.0, 1),
    Track("brake", "Brake", { it.brake }, 0.0, 100.0, 1),
    Track("rpm", "RPM", { it.rpm }, 0.0, 13000.0, 1),
    Track("gear", "Gear", { it.gear }, 0.0, 8.0, 1),
)

/** DRS reads 10, 12 or 14 in the feed when the flap is open. */
private const val DRS_OPEN = 10

private fun windowMs(s: WidgetScope) = s.values.str("window").toIntOrNull()?.takeIf { it != 0 } ?: 60_000

/** Speed, throttle and brake of two drivers over the last minute, as the desktop's plugins/telemetry. */
val TelemetryWidget = WidgetSpec(
    id = "racewall.telemetry",
    title = "Telemetry",
    min = 12 to 8,
    size = 17 to 15,
    reads = setOf(Topic.CAR_TELEMETRY, Topic.DRIVERS_LIST, Topic.TIMING_POSITIONS),
    fields = listOf(
        Field.Select("window", "Time window", listOf("30000" to "30 seconds", "60000" to "60 seconds", "120000" to "2 minutes", "300000" to "5 minutes"), "60000"),
        Field.Toggle("speed", "Speed", true),
        Field.Toggle("throttle", "Throttle", true),
        Field.Toggle("brake", "Brake", true),
        Field.Toggle("rpm", "RPM", false),
        Field.Toggle("gear", "Gear", false),
        Field.Toggle("drs", "Shade where DRS is open", true),
        Field.Toggle("followFocus", "First driver follows the focused driver", false),
    ),
    meta = { s -> "Last ${WINDOW_LABEL[windowMs(s)] ?: "${windowMs(s) / 1000} seconds"}" },
) { s ->
    val drivers = s.frame.drivers
    val order = s.frame.positions.map { it.number }
    val window = windowMs(s)
    // With "follow the focused driver" on, the first driver is the focused one; the saved pick is left alone.
    val a = (if (s.values.bool("followFocus")) s.focus else null) ?: s.values.str("a").ifEmpty { null } ?: order.getOrNull(0) ?: ""
    val b = s.values.str("b").ifEmpty { null } ?: order.getOrNull(1) ?: ""
    val time = s.frame.clock.time
    // A fresh window of samples twice a second, or at once after a jump, a new pick or a new window.
    val bucket = Math.floor(time / 500).toLong()
    @Suppress("UNCHECKED_CAST")
    val samples = remember(a, b, window, bucket, s.frame.topics.containsKey(Topic.CAR_TELEMETRY)) {
        (s.range(Topic.CAR_TELEMETRY, time - window, time, listOf(a, b)) as? Map<String, List<TelemetrySample>>) ?: emptyMap()
    }
    val measurer = rememberTextMeasurer()
    Column(Modifier.fillMaxSize().testTag("telemetry")) {
        Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val options = order.map { it to code(drivers, it) }
            RwSelect(a, options, { s.setSetting("a", JsonPrimitive(it)) }, "First driver", fontSize = 13f, openUpwards = false)
            BasicText("vs", style = Rw.text(11f, Rw.t3))
            RwSelect(b, options, { s.setSetting("b", JsonPrimitive(it)) }, "Second driver", fontSize = 13f, openUpwards = false)
        }
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val w = size.width
            val h = size.height
            val px = 1.dp.toPx()
            if (samples[a].isNullOrEmpty() && samples[b].isNullOrEmpty()) {
                fillText(measurer, if (s.frame.topics.containsKey(Topic.CAR_TELEMETRY)) "No telemetry for this moment." else "Loading telemetry…", w / 2, h / 2, Rw.t3, Align.CENTER)
                return@Canvas
            }
            val t1 = time
            val t0 = t1 - window
            val l = 52 * px
            val r = 10 * px
            val top0 = 6 * px
            val bottom = 6 * px
            fun x(t: Double) = (l + (t - t0) / window * (w - l - r)).toFloat()
            val tracks = TRACKS.filter { s.values.bool(it.setting) }
            val totalWeight = tracks.sumOf { it.weight }
            val ca = teamColour(drivers, a)
            val cb = teamColour(drivers, b)
            var top = top0
            val total = h - top0 - bottom
            for (tr in tracks) {
                val frac = tr.weight.toFloat() / totalWeight
                val th = total * frac - 8 * px
                fillText(measurer, tr.name, l - 6 * px, top + 10 * px, Rw.t3, Align.RIGHT)
                drawLine(Color(0xFF121212), Offset(l, top + th), Offset(w - r, top + th), px)
                // Shade where each driver had the DRS open, behind the speed lines.
                if (tr.setting == "speed" && s.values.bool("drs")) {
                    for ((n, colour) in listOf(a to ca, b to cb)) {
                        var first: TelemetrySample? = null
                        var last: TelemetrySample? = null
                        fun close() {
                            val f = first
                            val e = last
                            if (f != null && e != null) {
                                val from = maxOf(f.t, t0)
                                val to = minOf(e.t, t1)
                                if (to > from) drawRect(colour.copy(alpha = 0.12f), Offset(x(from), top), Size(x(to) - x(from), th))
                            }
                            first = null
                            last = null
                        }
                        for (smp in samples[n] ?: emptyList()) {
                            if (smp.drs >= DRS_OPEN) {
                                if (first == null) first = smp
                                last = smp
                            } else close()
                        }
                        close()
                    }
                }
                for ((n, colour, dashed) in listOf(Triple(a, ca, false), Triple(b, cb, ca == cb))) {
                    val path = Path()
                    var started = false
                    for (smp in samples[n] ?: emptyList()) {
                        if (smp.t < t0 || smp.t > t1) continue
                        val v = tr.field(smp).toDouble().coerceIn(tr.lo, tr.hi)
                        val y = (top + th - (v - tr.lo) / (tr.hi - tr.lo) * th).toFloat()
                        if (started) path.lineTo(x(smp.t), y) else path.moveTo(x(smp.t), y)
                        started = true
                    }
                    drawPath(path, colour, style = Stroke(1.3f * px, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4 * px, 3 * px)) else null))
                }
                top += total * frac
            }
            if (tracks.isEmpty()) fillText(measurer, "Turn on a trace in the panel settings.", w / 2, h / 2, Rw.t3, Align.CENTER)
            val now = s.frame.topic<CarTelemetryMap>(Topic.CAR_TELEMETRY)
            val line = listOf(a, b).mapNotNull { n -> now?.get(n)?.let { "${code(drivers, n)} ${it.speed} km/h, gear ${it.gear}" } }.joinToString("   ")
            fillText(measurer, line, w - r, top0 + 10 * px, Rw.t1, Align.RIGHT)
        }
    }
}
