package com.racewall.android.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.racewall.android.ui.Rw
import com.racewall.topics.Topic

/** Tyre stints per car, as the desktop's plugins/tyre-strategy. */
val TyreStrategyWidget = WidgetSpec(
    id = "racewall.tyre-strategy",
    title = "Tyre strategy",
    min = 12 to 6,
    size = 18 to 10,
    reads = setOf(Topic.TIMING_STINTS, Topic.TIMING_POSITIONS, Topic.DRIVERS_LIST, Topic.SESSION_STATUS),
    fields = listOf(
        Field.Select("order", "Order", listOf("position" to "Running order", "stops" to "Most stops first"), "position"),
        Field.Toggle("letters", "Compound letter in the bars", false),
        Field.Toggle("stops", "Pit-stop count", false),
        Field.Toggle("nowLine", "Current lap line", true),
    ),
    meta = { s ->
        val (total, used) = layoutOf(s)
        listOf(if (s.frame.status?.totalLaps.let { it != null && it != 0 }) "$total laps" else "$total laps so far", if (used) "used sets are dimmer" else "")
            .filter { it.isNotEmpty() }.joinToString(" · ")
    },
) { s ->
    val stints = s.frame.stints
    val rows = s.frame.positions
    val running = rows.map { it.number }
    fun stopsOf(n: String) = (stints[n]?.size ?: 1) - 1
    // The sort is stable, so cars with the same number of stops stay in running order.
    val order = if (s.values.str("order") == "stops") running.sortedByDescending { stopsOf(it) } else running
    if (order.isEmpty()) {
        Empty("No tyre data yet.")
        return@WidgetSpec
    }
    val carLaps = rows.associate { it.number to (it.laps ?: 0) }
    val lap = s.frame.status?.lap ?: 0
    val (total, _) = layoutOf(s)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp).testTag("tyre-strategy"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (n in order) {
            val list = stints[n] ?: emptyList()
            Row(
                Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { s.onFocus(n) }.padding(horizontal = 10.dp).testTag("stints-$n"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText(code(s.frame.drivers, n), style = Rw.text(11f, if (n == s.focus) Rw.t1 else Rw.t2), modifier = Modifier.width(30.dp), maxLines = 1)
                if (s.values.bool("stops")) BasicText("${stopsOf(n)}", style = Rw.text(11f, Rw.t3), modifier = Modifier.width(18.dp))
                BoxWithConstraints(Modifier.weight(1f).height(9.dp)) {
                    val wpx = maxWidth
                    list.forEachIndexed { i, st ->
                        val end = if (i + 1 < list.size) list[i + 1].startLap - 1 else maxOf(carLaps[n]?.takeIf { it != 0 } ?: lap, st.startLap)
                        val left = (st.startLap - 1).toFloat() / total
                        val width = maxOf(1, end - st.startLap + 1).toFloat() / total
                        val (letter, colour) = TYRE.getValue(st.compound)
                        val showLetter = s.values.bool("letters") && width * 100 >= 8
                        Box(
                            Modifier
                                .offset(x = wpx * left)
                                .width((wpx * width - 2.dp).coerceAtLeast(0.dp))
                                .height(9.dp)
                                .alpha(if (!st.isNew) 0.55f else 1f)
                                .background(colour, RoundedCornerShape(1.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (showLetter) BasicText(letter, style = Rw.text(9f, Color.Black, FontWeight.SemiBold).copy(lineHeight = Rw.text(9f).fontSize, textAlign = TextAlign.Center))
                        }
                    }
                    if (s.values.bool("nowLine") && lap != 0) {
                        Box(Modifier.offset(x = wpx * (lap.toFloat() / total), y = (-3).dp).width(1.dp).height(15.dp).background(Rw.t1))
                    }
                }
            }
        }
    }
}

/** The race distance the bars are scaled to, and whether any car is on a used set. */
private fun layoutOf(s: WidgetScope): Pair<Int, Boolean> {
    val status = s.frame.status
    val lap = status?.lap ?: 0
    val most = s.frame.positions.maxOfOrNull { it.laps ?: 0 } ?: 0
    // Practice and qualifying have no race distance: scale to the most laps any car has done.
    val total = maxOf(status?.totalLaps ?: 0, lap, most, 1)
    val used = s.frame.positions.any { r -> s.frame.stints[r.number]?.any { !it.isNew } == true }
    return total to used
}
