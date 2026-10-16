package com.racewall.android.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.racewall.android.ui.Rw
import com.racewall.topics.LapHistory
import com.racewall.topics.Topic

private fun shownOf(s: WidgetScope): Pair<Int, List<String>> {
    val count = s.values.str("count").toIntOrNull()?.takeIf { it != 0 } ?: 5
    val history = s.frame.topic<LapHistory>(Topic.LAP_HISTORY) ?: emptyMap()
    val order = s.frame.positions.map { it.number }
    val shown = (order.take(count) + listOfNotNull(s.focus)).distinct().filter { history[it]?.isNotEmpty() == true }
    return count to shown
}

/** Lap times of the leading drivers and the focused one, as the desktop's plugins/lap-times. */
val LapTimesWidget = WidgetSpec(
    id = "racewall.lap-times",
    title = "Lap times",
    min = 12 to 6,
    size = 18 to 10,
    reads = setOf(Topic.LAP_HISTORY, Topic.TIMING_POSITIONS, Topic.DRIVERS_LIST, Topic.SESSION_STATUS),
    fields = listOf(
        Field.Select("count", "Drivers from the top", listOf("3" to "3", "5" to "5", "8" to "8", "10" to "10"), "5"),
        Field.Toggle("slowLaps", "Include pit and safety-car laps", false),
        Field.Toggle("nowLine", "Current lap line", true),
        Field.Toggle("legend", "Driver names above the chart", true),
    ),
    meta = { s -> shownOf(s).let { (count, shown) -> if (shown.isNotEmpty()) "Top $count and the focused driver" else "" } },
) { s ->
    val (_, shown) = shownOf(s)
    val history = s.frame.topic<LapHistory>(Topic.LAP_HISTORY) ?: emptyMap()
    val drivers = s.frame.drivers
    val measurer = rememberTextMeasurer()
    /** Where the finger is over the chart, in pixels from its left edge; null when it is elsewhere. */
    var hoverX by remember { mutableStateOf<Float?>(null) }
    Canvas(
        Modifier
            .fillMaxSize()
            .testTag("lap-times")
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    hoverX = down.position.x
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        hoverX = c.position.x
                    }
                    hoverX = null
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val px = 1.dp.toPx()
        if (shown.isEmpty()) {
            fillText(measurer, "Lap times appear after the first timed lap.", w / 2, h / 2, Rw.t3, Align.CENTER)
            return@Canvas
        }
        val all = shown.flatMap { n -> history.getValue(n).mapNotNull { it.ms } }.sorted()
        val lo = all.first() - 500.0
        // Clip slow laps (pit stops, safety car) so the racing laps stay readable, unless asked to show them.
        val hi = maxOf(lo + 2000, if (s.values.bool("slowLaps")) all.last().toDouble() else all[(all.size * 0.9).toInt()].toDouble())
        val maxLap = maxOf(s.frame.status?.totalLaps ?: 0, shown.maxOf { history.getValue(it).last().lap }, 2)
        val l = 46 * px
        val r = 10 * px
        val t = 22 * px
        val b = 18 * px
        fun x(lap: Int) = l + (lap - 1).toFloat() / (maxLap - 1) * (w - l - r)
        fun y(ms: Double) = (t + (1 - (minOf(ms, hi) - lo) / (hi - lo)) * (h - t - b)).toFloat()
        for (k in 0..3) {
            val v = lo + (hi - lo) * k / 3
            drawLine(Color(0xFF121212), Offset(l, y(v)), Offset(w - r, y(v)), px)
            fillText(measurer, shortLap(v), l - 5 * px, y(v) + 3 * px, Rw.t3, Align.RIGHT)
        }
        for (lap in listOf(1, jsRound(maxLap / 4.0).toInt(), jsRound(maxLap / 2.0).toInt(), jsRound(maxLap * 3 / 4.0).toInt(), maxLap)) {
            fillText(measurer, "$lap", x(lap), h - 5 * px, Rw.t3, Align.CENTER)
        }
        val seen = HashSet<Color>()
        var lx = l + 4 * px
        for (n in shown) {
            val colour = teamColour(drivers, n)
            val dashed = !seen.add(colour)
            val path = Path()
            history.getValue(n).forEachIndexed { i, lap ->
                val ms = lap.ms ?: return@forEachIndexed
                if (i == 0) path.moveTo(x(lap.lap), y(ms.toDouble())) else path.lineTo(x(lap.lap), y(ms.toDouble()))
            }
            drawPath(path, colour, style = Stroke((if (n == s.focus) 2.2f else 1.4f) * px, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4 * px, 3 * px)) else null))
            if (s.values.bool("legend")) {
                val label = code(drivers, n) + if (dashed) " (dashed)" else ""
                lx += fillText(measurer, label, lx, 12 * px, colour) + 14 * px
            }
        }
        val lap = s.frame.status?.lap
        if (lap != null && lap != 0 && s.values.bool("nowLine")) drawLine(Rw.t1, Offset(x(lap), t), Offset(x(lap), h - b), px)

        // The lap under the finger, and each shown driver's time on it.
        val hx = hoverX
        if (hx != null && hx >= l && hx <= w - r) {
            val at = jsRound(1 + (hx - l) / (w - l - r).toDouble() * (maxLap - 1)).toInt().coerceIn(1, maxLap)
            val xx = x(at)
            drawLine(Rw.border2, Offset(xx, t), Offset(xx, h - b), px)
            val right = xx > w - 110 * px
            val align = if (right) Align.RIGHT else Align.LEFT
            val tx = if (right) xx - 8 * px else xx + 8 * px
            var ty = t + 12 * px
            fillText(measurer, "Lap $at", tx, ty, Rw.t2, align)
            for (n in shown) {
                val found = history.getValue(n).firstOrNull { it.lap == at && it.ms != null } ?: continue
                ty += 13 * px
                fillText(measurer, "${code(drivers, n)} ${found.time}", tx, ty, teamColour(drivers, n), align)
            }
        }
    }
}
