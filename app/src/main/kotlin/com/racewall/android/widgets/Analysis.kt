package com.racewall.android.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.racewall.analysis.Battles
import com.racewall.analysis.PitWindow
import com.racewall.android.BATTLES_TOPIC
import com.racewall.android.PIT_WINDOW_TOPIC
import com.racewall.android.ui.Rw
import com.racewall.topics.Topic
import kotlin.math.abs

private val NEUTRAL = mapOf(
    "sc" to "Safety car: the field is bunched up, so no battles are shown.",
    "vsc" to "Virtual safety car: no battles are shown until it ends.",
    "red" to "Red flag: the session is stopped.",
)

private fun shownBattles(s: WidgetScope): List<Battles.Battle> {
    val result = s.frame.topic<Battles.Result>(BATTLES_TOPIC) ?: return emptyList()
    val min = s.values.str("minLaps").toIntOrNull()?.takeIf { it != 0 } ?: 1
    val focus = s.focus
    val list = result.battles.filter { it.laps >= min && (!s.values.bool("onlyFocus") || focus == null || it.number == focus || it.ahead == focus) }
    return when (s.values.str("sort")) {
        "gap" -> list.sortedBy { it.interval }
        "laps" -> list.sortedByDescending { it.laps }
        else -> list
    }
}

/** Every car within a second of the one ahead, as the desktop's plugins/battles-widget. */
val BattlesWidget = WidgetSpec(
    id = "racewall.battles-widget",
    title = "Battles",
    min = 10 to 5,
    size = 14 to 9,
    reads = setOf(BATTLES_TOPIC, Topic.DRIVERS_LIST, Topic.TIMING_STINTS),
    fields = listOf(
        Field.Select("sort", "Order", listOf("position" to "Running order", "gap" to "Smallest gap first", "laps" to "Longest battle first"), "position"),
        Field.Toggle("onlyFocus", "Only the focused driver's battles", false),
        Field.Select("minLaps", "Hide battles younger than", listOf("1" to "1 lap", "2" to "2 laps", "3" to "3 laps"), "1", note = "Laps in a row"),
    ),
    meta = { s -> shownBattles(s).size.let { if (it > 0) "$it within a second" else "Within a second" } },
) { s ->
    val result = s.frame.topic<Battles.Result>(BATTLES_TOPIC)
    val shown = shownBattles(s)
    when {
        result == null -> Empty("Waiting for the Gaps and battles analysis.")
        !result.race -> Empty("Battles are worked out in races and sprints.")
        result.neutral != null -> Empty(NEUTRAL.getValue(result.neutral!!))
        result.battles.isEmpty() -> Empty("No car is within a second of the car ahead.")
        shown.isEmpty() -> Empty("No battle matches the settings.")
        else -> {
            val tt = rememberTableText()
            val drivers = s.frame.drivers
            val stints = s.frame.stints
            fun tyre(n: String) = stints[n]?.lastOrNull().let { tt.tyre(it?.compound, it?.tyreAge) }
            fun trend(t: Double?) = when {
                t == null -> tt.text("–", Rw.t3)
                t <= -0.05 -> tt.text("closing ${(-t).fixed(2)} s", Rw.green)
                t >= 0.05 -> tt.text("dropping ${t.fixed(2)} s", Rw.t3)
                else -> tt.text("holding", Rw.t2)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("battles")) {
                AutoTable(
                    listOf(Header("", fixed = 24f), Header("Chasing", true), Header("Tyre", true), Header("Gap"), Header("Car ahead", true), Header("Tyre", true), Header("Laps"), Header("Per lap")),
                    shown.map { b ->
                        TableRow(
                            b.number,
                            b.number,
                            listOf(
                                tt.text("${b.position}", Rw.t3, left = true),
                                tt.driver(drivers, b.number),
                                tyre(b.number),
                                tt.text(b.interval.fixed(3)),
                                tt.driver(drivers, b.ahead, Rw.t2),
                                tyre(b.ahead),
                                tt.text("${b.laps}"),
                                trend(b.trend),
                            ),
                        )
                    },
                    s.focus,
                    s.onFocus,
                    tt = tt,
                )
            }
        }
    }
}

/** Where each car would rejoin if it stopped now, as the desktop's plugins/pit-window-widget. */
val PitWindowWidget = WidgetSpec(
    id = "racewall.pit-window-widget",
    title = "Pit window",
    min = 12 to 6,
    size = 16 to 14,
    reads = setOf(PIT_WINDOW_TOPIC, Topic.DRIVERS_LIST),
    fields = listOf(
        Field.Toggle("onlyFocus", "Only the focused driver and the cars around them", false),
        Field.Select("sort", "Order", listOf("position" to "Running order", "rejoin" to "By rejoin position"), "position"),
        Field.Select("traffic", "Traffic if a car is within", listOf("1" to "1 s", "2" to "2 s", "3" to "3 s"), "2", note = "Seconds, at the rejoin point"),
        Field.Toggle("hideNote", "Hide the note above the table", false),
    ),
    meta = { s -> s.frame.topic<PitWindow.Estimate>(PIT_WINDOW_TOPIC)?.let { "a stop costs about ${it.loss.fixed(1)} s" } ?: "" },
) { s ->
    val result = s.frame.topic<PitWindow.Estimate>(PIT_WINDOW_TOPIC)
    when {
        result == null -> Empty("Waiting for the Pit window analysis.")
        !result.race -> Empty("The pit window is worked out in races and sprints.")
        else -> {
            val tt = rememberTableText()
            val drivers = s.frame.drivers
            val how = if (result.lossFrom == "session") "Measured from ${result.stops.size} green-flag stop${if (result.stops.size == 1) "" else "s"} in this race."
            else "A typical figure until this race has a green-flag stop to measure."
            val mine = s.focus?.let { f -> result.rows.firstOrNull { it.number == f } }
            var list = result.rows
            if (s.values.bool("onlyFocus") && mine != null) list = list.filter { abs(it.position - mine.position) <= 3 }
            // Cars that would not rejoin the race (not running, lapped) go last.
            if (s.values.str("sort") == "rejoin") list = list.sortedBy { it.rejoin ?: Int.MAX_VALUE }
            // A car with another within this many seconds of where it would rejoin is in traffic.
            val limit = s.values.str("traffic").toDoubleOrNull()?.takeIf { it != 0.0 } ?: 2.0
            fun near(n: String?, gap: Double?) = if (n == null) tt.text("–", Rw.t3, left = true)
            else tt.rich(buildAnnotatedString {
                append(code(drivers, n))
                withStyle(SpanStyle(color = Rw.t3)) { append(" ${gap?.fixed(1) ?: ""}") }
            }, left = true)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("pit-window")) {
                if (!s.values.bool("hideNote")) Note("If each car stopped now. A stop costs about ${result.loss.fixed(1)} s. $how Green rejoins have free air (no car within ${limit.fixed(0)} s).")
                AutoTable(
                    listOf(Header("", fixed = 24f), Header("Driver", true), Header("Rejoins"), Header("Behind", true), Header("Ahead of", true)),
                    list.map { r ->
                        val free = minOf(r.gapAhead ?: Double.POSITIVE_INFINITY, r.gapBehind ?: Double.POSITIVE_INFINITY) >= limit
                        val out = if (r.rejoin == null) listOf(Cell(tt.width(if (r.gap == null) "not running or lapped" else ""), left = true, span = 3) {
                            androidx.compose.foundation.text.BasicText(if (r.gap == null) "not running or lapped" else "", style = Rw.text(13f, Rw.t3), maxLines = 1)
                        })
                        else listOf(tt.text("P${r.rejoin}", if (free) Rw.green else Rw.t2), near(r.ahead, r.gapAhead), near(r.behind, r.gapBehind))
                        TableRow(r.number, r.number, listOf(tt.text("${r.position}", Rw.t3, left = true), tt.driver(drivers, r.number)) + out)
                    },
                    s.focus,
                    s.onFocus,
                    columns = 5,
                    tt = tt,
                )
            }
        }
    }
}
