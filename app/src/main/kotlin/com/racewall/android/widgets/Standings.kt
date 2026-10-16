package com.racewall.android.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.racewall.android.ui.Rw
import com.racewall.android.ui.RwButton
import com.racewall.topics.DriverStanding
import com.racewall.topics.Schedule
import com.racewall.topics.Standings
import com.racewall.topics.TeamStanding
import com.racewall.topics.Topic
import kotlinx.serialization.json.JsonPrimitive

/** Points for winning a race, and for winning a sprint; a team can score two cars' worth. */
private val RACE = mapOf("drivers" to 25, "teams" to 25 + 18)
private val SPRINT = mapOf("drivers" to 8, "teams" to 8 + 7)

private fun pts(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

/** Drivers' or teams' championship, as the desktop's plugins/standings. */
val StandingsWidget = WidgetSpec(
    id = "racewall.standings",
    title = "Championship",
    min = 10 to 8,
    size = 14 to 22,
    reads = setOf(Topic.STANDINGS_DRIVERS, Topic.STANDINGS_TEAMS, Topic.DRIVERS_LIST, Topic.SCHEDULE),
    fields = listOf(
        Field.Select("view", "Show", listOf("drivers" to "Drivers", "teams" to "Teams"), "drivers"),
        Field.Select("points", "Points column", listOf("points" to "Points", "toLeader" to "Gap to the leader", "toAbove" to "Gap to the row above"), "points"),
        Field.Toggle("wins", "Wins column", false),
        Field.Toggle("top10", "Top ten only", false),
    ),
    meta = { s ->
        val teams = s.values.str("view") == "teams"
        val st: Standings<*>? = if (teams) s.frame.topic<Standings<TeamStanding>>(Topic.STANDINGS_TEAMS) else s.frame.topic<Standings<DriverStanding>>(Topic.STANDINGS_DRIVERS)
        st?.let { "${it.season}, after round ${it.round}" } ?: ""
    },
) { s ->
    val view = if (s.values.str("view") == "teams") "teams" else "drivers"
    val drivers = s.frame.topic<Standings<DriverStanding>>(Topic.STANDINGS_DRIVERS)
    val teams = s.frame.topic<Standings<TeamStanding>>(Topic.STANDINGS_TEAMS)
    Column(Modifier.fillMaxSize().testTag("standings")) {
        Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for ((v, label) in listOf("drivers" to "Drivers", "teams" to "Teams")) {
                RwButton(onClick = { s.setSetting("view", JsonPrimitive(v)) }, on = view == v, label = label, modifier = Modifier.height(24.dp)) { c ->
                    BasicText(label, style = Rw.text(13f, if (view == v) Rw.t1 else Rw.t3))
                }
            }
        }
        val rows: List<Triple<Int, Double, Int>> = if (view == "teams") teams?.rows?.map { Triple(it.position, it.points, it.wins) } ?: emptyList()
        else drivers?.rows?.map { Triple(it.position, it.points, it.wins) } ?: emptyList()
        val st: Standings<*>? = if (view == "teams") teams else drivers
        if (st == null) {
            Empty("No standings yet.")
            return@Column
        }
        // Jolpica and the timing feed name teams differently ("Red Bull" and "Red Bull Racing"), so
        // match on the first word too.
        fun first(name: String) = name.lowercase().split(Regex("\\s+"))[0]
        fun colour(team: String): Color = s.frame.drivers.values.firstOrNull { it.team == team || first(it.team) == first(team) }?.teamColour?.let(::hex) ?: Color.Transparent
        // Points still to be won in the rounds after these standings, and who could still catch the leader.
        val schedule = s.frame.topic<Schedule>(Topic.SCHEDULE) ?: emptyList()
        val later = schedule.filter { it.round > st.round }.map { it.round }.toSet()
        val sprints = schedule.filter { it.round > st.round && it.name == "Sprint" }.map { it.round }.toSet()
        val available = later.size * RACE.getValue(view) + sprints.size * SPRINT.getValue(view)
        val lead = rows.firstOrNull()?.second ?: 0.0
        fun alive(points: Double) = schedule.isEmpty() || points + available >= lead
        val contenders = rows.count { alive(it.second) }
        fun gap(points: Double, i: Int) = when (s.values.str("points")) {
            "toLeader" -> if (i == 0) pts(points) else "−${pts(lead - points)}"
            "toAbove" -> if (i == 0) pts(points) else "−${pts(rows[i - 1].second - points)}"
            else -> pts(points)
        }
        val shown = if (s.values.bool("top10")) rows.take(10) else rows
        val tt = rememberTableText()
        val wins = s.values.bool("wins")
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            AutoTable(
                headers = null,
                rows = shown.mapIndexed { i, (position, points, w) ->
                    val nameColour = if (alive(points)) Rw.t1 else Rw.t3
                    val cells = if (view == "teams") {
                        listOf(tt.text("$position", Rw.t3, left = true), tt.text(teams!!.rows[i].name, nameColour, left = true), tt.text(gap(points, i)))
                    } else {
                        val d = drivers!!.rows[i]
                        listOf(
                            tt.text("$position", Rw.t3, left = true),
                            Cell(10f + tt.width(d.name), left = true) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(3.dp, 13.dp).background(colour(d.team)))
                                    Box(Modifier.width(7.dp))
                                    BasicText(d.name, style = Rw.text(13f, nameColour), maxLines = 1)
                                }
                            },
                            tt.text(d.team, Rw.t2, left = true),
                            tt.text(gap(points, i)),
                        )
                    }
                    TableRow("$position", null, if (wins) cells + tt.text("$w", Rw.t2) else cells)
                },
                focus = null,
                onFocus = null,
                padding = 8f,
                tt = tt,
            )
            if (schedule.isNotEmpty()) Note(if (contenders <= 1) "Season decided" else "$available points still available · $contenders $view can still win")
        }
    }
}
