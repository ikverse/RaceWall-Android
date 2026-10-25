package com.racewall.android.widgets

import com.racewall.android.Format
import com.racewall.android.Mode
import com.racewall.android.state.F1TV_WIDGET
import com.racewall.android.state.ONBOARD_WIDGET
import com.racewall.android.state.VIDEO_QUALITY
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

/** The players' coordination, for the F1 TV panels' header text. Set by the app. */
var videoHub: com.racewall.android.video.VideoHub? = null

/** A player's own video quality, which the bottom bar's can override. */
private val QUALITY_FIELD = Field.Select("quality", "Video quality", VIDEO_QUALITY, "auto", note = "Lower for a slower connection")

/**
 * The F1 TV player. Part of the app rather than a plugin, as on the desktop, because it needs the
 * sign-in and protected-video playback; it is listed with the widgets all the same.
 */
val F1TvWidget = WidgetSpec(
    id = F1TV_WIDGET,
    title = "F1 TV",
    min = 10 to 7,
    size = 26 to 18,
    reads = setOf(Topic.SESSION_INFO, Topic.DRIVERS_LIST),
    fields = listOf(QUALITY_FIELD),
    meta = { s -> videoHub?.metas?.get(s.panelId) ?: "" },
) { s -> com.racewall.android.video.VideoPanel(s) }

/**
 * The focused driver's onboard camera: an F1 TV player that follows the others and never drives the
 * clock, and shows nothing while no driver is focused.
 */
val OnboardWidget = WidgetSpec(
    id = ONBOARD_WIDGET,
    title = "Onboard",
    min = 8 to 6,
    size = 16 to 10,
    reads = setOf(Topic.SESSION_INFO, Topic.DRIVERS_LIST),
    fields = listOf(QUALITY_FIELD),
    meta = { s -> videoHub?.metas?.get(s.panelId) ?: "" },
) { s -> com.racewall.android.video.VideoPanel(s, onboard = true) }

/** Every built-in widget, in the order the Add widget menu lists them. */
val WIDGETS: List<WidgetSpec> = listOf(
    F1TvWidget,
    OnboardWidget,
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

/** The F1 TV players, F1 TV and Onboard, are listed with the widgets, as on the desktop. */
fun isVideo(id: String) = id == F1TV_WIDGET || id == ONBOARD_WIDGET
