package com.racewall.android.widgets

import com.racewall.android.Format
import com.racewall.android.Mode
import com.racewall.android.state.F1TV_WIDGET
import com.racewall.android.ui.TimingSettings
import com.racewall.android.ui.TimingTower
import com.racewall.topics.Topic

/** The timing tower, as the desktop's plugins/timing. */
val TimingWidget = WidgetSpec(
    id = "racewall.timing",
    title = "Timing",
    min = 9 to 8,
    size = 14 to 30,
    reads = setOf(Topic.SESSION_INFO, Topic.SESSION_STATUS, Topic.DRIVERS_LIST, Topic.TIMING_POSITIONS, Topic.TIMING_LAPS, Topic.TIMING_STINTS),
    fields = listOf(
        Field.Select("names", "Driver names", listOf("code" to "Three-letter code", "surname" to "Surname"), "code"),
        Field.Toggle("showInterval", "Interval column", true),
        Field.Toggle("showLast", "Last or best lap", true),
        Field.Toggle("showSectors", "Sector times", true),
        Field.Toggle("sectorBars", "Sector segments", true),
        Field.Toggle("showTyre", "Tyre column", true),
        Field.Toggle("compact", "Compact rows", false),
    ),
    meta = { s ->
        val info = s.frame.info
        if (info == null) "" else if (s.mode == Mode.IDLE) "Last result · ${info.meeting}, ${info.name}" else Format.lapText(s.frame.status).ifEmpty { "${info.meeting}, ${info.name}" }
    },
) { s ->
    val v = s.values
    TimingTower(
        s.frame,
        TimingSettings(v.str("names"), v.bool("showInterval"), v.bool("showLast"), v.bool("showSectors"), v.bool("sectorBars"), v.bool("showTyre"), v.bool("compact")),
        s.focus,
        s.onFocus,
    )
}

/** Every built-in widget, in the order the Add widget menu lists them. */
val WIDGETS: List<WidgetSpec> = listOf(
    TimingWidget,
    TrackMapWidget,
    RaceControlWidget,
    TyreStrategyWidget,
    WeatherWidget,
    TelemetryWidget,
    LapTimesWidget,
    TeamRadioWidget,
    StandingsWidget,
    NextSessionWidget,
    BattlesWidget,
    PitWindowWidget,
)

private val BY_ID: Map<String, WidgetSpec> by lazy { WIDGETS.associateBy { it.id } }

fun widget(id: String): WidgetSpec? = BY_ID[id]

/** What the window always shows, whatever the layout. */
val SHELL_TOPICS = setOf(Topic.SESSION_INFO, Topic.SESSION_STATUS, Topic.DRIVERS_LIST)

/** The F1 TV player is listed with the widgets, as on the desktop. */
fun isVideo(id: String) = id == F1TV_WIDGET
