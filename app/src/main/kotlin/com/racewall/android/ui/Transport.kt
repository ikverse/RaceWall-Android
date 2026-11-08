package com.racewall.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.racewall.topics.TrackFlag
import kotlinx.coroutines.delay
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.racewall.android.Format
import com.racewall.android.Frame
import com.racewall.android.R
import com.racewall.android.Recording
import com.racewall.android.UiState
import com.racewall.android.state.PER_PLAYER
import com.racewall.android.state.VIDEO_QUALITY
import com.racewall.archive.ArchiveMeeting
import com.racewall.clock.SPEEDS
import com.racewall.live.LiveStatus
import java.time.Instant
import java.time.ZoneOffset

/** What the bottom bar can ask of the core. */
interface TransportActions {
    fun toggle()
    fun seek(t: Double)
    fun setSpeed(speed: Double)
    suspend fun listSeason(year: Int): List<ArchiveMeeting>
    /**
     * Loads a session and starts playing it, in the app's own scope: the picker closes as it asks,
     * and closing it must not cancel the download.
     */
    fun replay(path: String)
    suspend fun listRecordings(): List<Recording> = emptyList()
    /** Connects to live timing, or jumps to the live moment when already connected. */
    fun goLive() {}
    fun syncDelay() {}
    /** Stops following live timing and goes back to the last result. */
    fun leaveLive() {}
}

/** The "year" that stands for the user's recordings. */
const val RECORDED = 0

private val SOURCE_NAMES = mapOf("f1-live" to "F1 live timing", "f1-archive" to "F1 archive", "openf1" to "OpenF1", "jolpica" to "Jolpica")

private val LIVE_STATUS = mapOf(
    LiveStatus.CONNECTING to "Connecting…",
    LiveStatus.RECONNECTING to "Reconnecting…",
    LiveStatus.CLOSED to "Disconnected",
    LiveStatus.ERROR to "Connection problem",
)

/** The live dot: filled while connected, an outline otherwise. */
@Composable
private fun Dot(on: Boolean, color: Color) {
    Box(Modifier.size(7.dp).let { if (on) it.background(color, CircleShape) else it.border(1.dp, color, CircleShape) })
}

/** A tap area of at least 48 dp, for a button on a touch screen. */
private val Touch = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)

/** "4x" as the desktop writes speeds: 0.5×, 1×, 16×. */
private fun speedText(s: Double) = "${if (s % 1.0 == 0.0) s.toInt().toString() else s.toString()}×"

/**
 * The transport bar, as the desktop's Transport.svelte, in three zones. Left: what is on and how it
 * is connected; it opens the picker. Centre, always in the middle: what can be done now, which
 * changes with the mode (replay: position and speed; live and following: Live, −30 s and pause;
 * behind: position and Go live). Right: the lap and the flag, and the ⋯ menu for video quality, the
 * TV delay, the time of day, the sources and leaving live. Messages float above the bar.
 */
@Composable
fun Transport(
    frame: Frame,
    ui: UiState,
    actions: TransportActions,
    pickerOpen: Boolean,
    setPicker: (Boolean) -> Unit,
    chromeAlpha: Float = 1f,
    modifier: Modifier = Modifier,
    /** True while an F1 TV player drives the session clock. */
    videoDriving: Boolean = false,
    /** The video quality for every player, or null to leave it out when the layout has no player. */
    videoQuality: String? = null,
    setVideoQuality: (String) -> Unit = {},
) {
    val info = frame.info
    val clock = frame.clock
    val status = frame.status
    val flag = status?.let { Format.flag(it.flag) ?: if (it.flag == TrackFlag.GREEN) "Green" to Rw.green else null }
    val sourceText = frame.sources.joinToString(", ") { SOURCE_NAMES[it] ?: it }
    val live = ui.session?.live
    val onNow = ui.onNow
    val connected = live?.status == LiveStatus.CONNECTED
    val behind = if (live != null) maxOf(0, Math.round((clock.end - clock.time) / 1000).toInt()) else 0
    var menu by remember { mutableStateOf<String?>(null) }

    // Notices that are not progress ("…") fade after a while; an error stays until closed or replaced.
    var fadedNotice by remember { mutableStateOf("") }
    LaunchedEffect(ui.notice) {
        if (ui.notice.isNotEmpty() && !ui.notice.endsWith("…")) {
            delay(5000)
            fadedNotice = ui.notice
        }
    }
    var closedError by remember { mutableStateOf("") }

    BoxWithConstraints(modifier.fillMaxWidth().height(56.dp).alpha(chromeAlpha)) {
        // Narrow (an upright tablet): the sides take only their own width and the labels shorten.
        val narrow = maxWidth < 1000.dp
        val text = Rw.text(14f, Rw.t2)
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .drawBehind { drawLine(Rw.border, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Left: the session, Live or Replay, and the connection.
            Row(if (narrow) Modifier else Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                RwButton(onClick = { setPicker(!pickerOpen) }, label = "Pick a past session", modifier = Touch.widthIn(max = if (narrow) 240.dp else 380.dp)) { c ->
                    Icon(R.drawable.ic_folder, c, 16f)
                    val name = when {
                        info != null && info.meeting.isNotEmpty() -> {
                            val meeting = info.meeting.removeSuffix(" Grand Prix") + if (narrow) "" else " GP".takeIf { info.meeting.endsWith(" Grand Prix") }.orEmpty()
                            if (narrow) "$meeting · ${info.name}" else "$meeting · ${info.name} ${Format.year(info.startDate)}"
                        }
                        live != null -> "Live timing"
                        else -> "Pick a session"
                    }
                    if (!(narrow && live != null && !connected)) {
                        BasicText(name, style = Rw.text(14f, c), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                    when {
                        live != null && connected -> BasicText("LIVE", style = Rw.text(11f, Rw.t1, FontWeight.SemiBold).copy(letterSpacing = 0.9.sp), maxLines = 1, modifier = Modifier.testTag("live-status"))
                        live != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("live-status")) {
                            Dot(false, Rw.t2)
                            BasicText(LIVE_STATUS[live.status] ?: "", style = Rw.text(14f, Rw.t2), maxLines = 1)
                        }
                        info != null && info.meeting.isNotEmpty() -> BasicText("REPLAY", style = Rw.text(11f, Rw.t3, FontWeight.SemiBold).copy(letterSpacing = 0.9.sp), maxLines = 1)
                    }
                }
            }

            // Centre: what can be done now.
            Row(
                Modifier.weight(if (narrow) 1f else 2f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                val playPause: @Composable () -> Unit = {
                    RwButton(onClick = actions::toggle, label = if (clock.playing) "Pause" else "Play", modifier = Touch) { c ->
                        Icon(if (clock.playing) R.drawable.ic_pause else R.drawable.ic_play, c, 16f)
                    }
                }
                if (live != null && live.following) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Dot(connected, if (connected) Rw.t1 else Rw.t2)
                        BasicText("Live", style = Rw.text(14f, if (connected) Rw.t1 else Rw.t2, if (connected) FontWeight.SemiBold else FontWeight.Normal), maxLines = 1)
                        if (videoDriving) BasicText("· follows video", style = Rw.text(14f, Rw.t3), maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    RwButton(onClick = { actions.seek(maxOf(clock.start, clock.time - 30_000)) }, label = "Back 30 seconds", modifier = Touch) { c ->
                        BasicText("−30 s", style = Rw.text(14f, c), maxLines = 1)
                    }
                    playPause()
                } else {
                    playPause()
                    if (live == null) BasicText(Format.elapsed(clock.time), style = Rw.text(14f, Rw.t1), maxLines = 1)
                    RwSlider(
                        value = clock.time,
                        min = clock.start,
                        max = clock.end,
                        step = 1000.0,
                        onChange = actions::seek,
                        label = if (live != null) "Position in the live session" else "Replay position",
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(horizontal = 8.dp),
                    )
                    if (live != null) {
                        RwButton(onClick = actions::goLive, on = true, label = "Jump to the live moment", modifier = Touch) { c ->
                            val words = when {
                                behind == 0 -> if (narrow) "Live" else "Go live"
                                narrow -> "−$behind s"
                                else -> "−$behind s · Go live"
                            }
                            BasicText(words, style = Rw.text(14f, c), maxLines = 1)
                        }
                    } else {
                        if (!narrow) BasicText(Format.elapsed(clock.end), style = Rw.text(14f, Rw.t3), maxLines = 1)
                        Box {
                            RwButton(onClick = { menu = if (menu == "speed") null else "speed" }, on = menu == "speed", label = "Speed", modifier = Touch.testTag("Speed")) { c ->
                                BasicText(speedText(clock.speed), style = Rw.text(14f, c), maxLines = 1)
                                Icon(R.drawable.ic_chevron_down, c, 12f)
                            }
                            if (menu == "speed") {
                                Popup(
                                    alignment = Alignment.BottomCenter,
                                    offset = with(LocalDensity.current) { IntOffset(0, -52.dp.roundToPx()) },
                                    onDismissRequest = { menu = null },
                                    properties = PopupProperties(focusable = true),
                                ) {
                                    Column(Modifier.background(Rw.bg, RoundedCornerShape(6.dp)).border(1.dp, Rw.border2, RoundedCornerShape(6.dp)).padding(4.dp)) {
                                        for (s in SPEEDS) {
                                            val chosen = s == clock.speed
                                            Box(
                                                Modifier
                                                    .size(width = 80.dp, height = 48.dp)
                                                    .drawBehind { if (chosen) drawLine(Rw.brand, Offset(0.5f, 8.dp.toPx()), Offset(0.5f, size.height - 8.dp.toPx()), 1.dp.toPx()) }
                                                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                                                        menu = null
                                                        actions.setSpeed(s)
                                                    }
                                                    .padding(horizontal = 14.dp),
                                                contentAlignment = Alignment.CenterEnd,
                                            ) { BasicText(speedText(s), style = Rw.text(14f, if (chosen) Rw.t1 else Rw.t2)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Right: the lap and the flag, and the ⋯ menu.
            Row(
                if (narrow) Modifier else Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            ) {
                val lap = status?.lap ?: 0
                if (lap > 0) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        if (!narrow) BasicText("Lap ", style = Rw.text(14f, Rw.t3), maxLines = 1)
                        BasicText("$lap", style = Rw.text(14f, Rw.t1), maxLines = 1)
                        val total = status?.totalLaps ?: 0
                        if (total > 0) BasicText("/$total", style = Rw.text(14f, Rw.t3), maxLines = 1)
                    }
                }
                if (flag != null) {
                    if (lap > 0) BasicText("·", style = Rw.text(14f, Rw.t3))
                    BasicText(flag.first, style = Rw.text(14f, flag.second), maxLines = 1)
                }
                Box {
                    RwButton(onClick = { menu = if (menu == "more") null else "more" }, on = menu == "more", label = "More", modifier = Touch) { c ->
                        BasicText("···", style = Rw.text(16f, c, FontWeight.SemiBold), maxLines = 1)
                    }
                    if (menu == "more") {
                        Popup(
                            alignment = Alignment.BottomEnd,
                            offset = with(LocalDensity.current) { IntOffset(0, -52.dp.roundToPx()) },
                            onDismissRequest = { menu = null },
                            properties = PopupProperties(focusable = true),
                        ) {
                            Column(Modifier.widthIn(min = 340.dp, max = 460.dp).background(Rw.bg, RoundedCornerShape(6.dp)).border(1.dp, Rw.border2, RoundedCornerShape(6.dp)).padding(vertical = 4.dp)) {
                                @Composable
                                fun MenuRow(key: String, content: @Composable RowScope.() -> Unit) = Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    BasicText(key, style = Rw.text(14f, Rw.t3), modifier = Modifier.width(110.dp))
                                    content()
                                }
                                if (videoQuality != null) MenuRow("Video quality") {
                                    RwSelect(
                                        value = videoQuality,
                                        options = listOf(PER_PLAYER to "Each player") + VIDEO_QUALITY.map { (k, name) -> k to name },
                                        onChange = setVideoQuality,
                                        label = "Video quality",
                                        fontSize = 14f,
                                        maxWidth = 200f,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    )
                                }
                                if (live != null && !videoDriving) MenuRow("TV delay") {
                                    BasicText("${Math.round(live.delayMs / 1000.0)} s", style = Rw.text(14f, Rw.t1), maxLines = 1)
                                    BasicText("for TV or satellite", style = Rw.text(14f, Rw.t3), maxLines = 1, modifier = Modifier.weight(1f))
                                    RwButton(onClick = { menu = null; actions.syncDelay() }, on = true, label = "Sync", modifier = Touch) { c -> BasicText("Sync", style = Rw.text(14f, c)) }
                                }
                                frame.utc?.let { utc -> MenuRow("Time of day") { BasicText("${Format.utcClock(utc)} UTC", style = Rw.text(14f, Rw.t1), maxLines = 1) } }
                                if (sourceText.isNotEmpty()) MenuRow("Sources") { BasicText(sourceText, style = Rw.text(14f, Rw.t2)) }
                                if (live != null) {
                                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Rw.border))
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                                                menu = null
                                                actions.leaveLive()
                                            }
                                            .semantics { contentDescription = "Leave live timing" }
                                            .padding(horizontal = 14.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) { BasicText("Leave live timing", style = Rw.text(14f, Rw.t2)) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // The message floats above the right end of the bar, so nothing in the bar moves.
        val error = ui.error.takeIf { it.isNotEmpty() && it != closedError }
        val message = error ?: ui.loading.ifEmpty { ui.notice.takeIf { it != fadedNotice } ?: "" }
        if (message.isNotEmpty()) {
            Popup(
                alignment = Alignment.BottomEnd,
                offset = with(LocalDensity.current) { IntOffset(-8.dp.roundToPx(), -64.dp.roundToPx()) },
                properties = PopupProperties(focusable = false),
            ) {
                Row(
                    Modifier
                        .widthIn(max = if (narrow) 420.dp else 560.dp)
                        .heightIn(min = 48.dp)
                        .background(Rw.bg, RoundedCornerShape(6.dp))
                        .border(1.dp, Rw.border2, RoundedCornerShape(6.dp))
                        .padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BasicText(message, style = Rw.text(14f, if (error != null) Rw.red else Rw.t1), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(vertical = 8.dp))
                    if (error != null) {
                        RwButton(onClick = { closedError = error }, label = "Close message", modifier = Touch) { c -> Icon(R.drawable.ic_dismiss, c, 14f) }
                    } else {
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }
        }

        if (pickerOpen) SessionPicker(actions, onNow?.let { "${it.meeting} · ${it.name}" }, live != null, onClose = { setPicker(false) })
    }
}

/**
 * The session picker: season (or your recordings), then event, then session, then Replay. It lists
 * the chosen season when it opens, and picks the season's last event and that event's last session.
 * While a session is on, it offers to go live first.
 */
@Composable
private fun SessionPicker(actions: TransportActions, onNow: String?, isLive: Boolean, onClose: () -> Unit) {
    val thisYear = remember { Instant.now().atZone(ZoneOffset.UTC).year }
    val years = remember { listOf(RECORDED to "Your recordings") + (thisYear downTo 2018).map { it to "$it" } }
    var year by remember { mutableStateOf(thisYear) }
    var meetings by remember { mutableStateOf<List<ArchiveMeeting>>(emptyList()) }
    var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var meetingKey by remember { mutableStateOf<Int?>(null) }
    var sessionPath by remember { mutableStateOf("") }
    var listError by remember { mutableStateOf("") }

    LaunchedEffect(year) {
        listError = ""
        meetings = emptyList()
        recordings = emptyList()
        meetingKey = null
        sessionPath = ""
        try {
            if (year == RECORDED) {
                recordings = actions.listRecordings()
                sessionPath = recordings.firstOrNull()?.path ?: ""
            } else {
                meetings = actions.listSeason(year)
                val last = meetings.lastOrNull()
                meetingKey = last?.key
                sessionPath = last?.sessions?.lastOrNull()?.path ?: ""
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            listError = e.message ?: e.toString()
        }
    }
    val meeting = meetings.firstOrNull { it.key == meetingKey }

    Popup(
        alignment = Alignment.BottomStart,
        // The desktop's picker sits 40 px above the bar's bottom, 8 px in.
        offset = with(LocalDensity.current) { IntOffset(8.dp.roundToPx(), -40.dp.roundToPx()) },
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true),
    ) {
        FlowRow(
            Modifier
                .widthIn(max = 1100.dp)
                .background(Rw.bg, RoundedCornerShape(6.dp))
                .border(1.dp, Rw.border2, RoundedCornerShape(6.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (onNow != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind { drawLine(Rw.border, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Dot(true, Rw.t1)
                    BasicText("Live now: $onNow", style = Rw.body, modifier = Modifier.weight(1f))
                    RwButton(onClick = { onClose(); actions.goLive() }, on = true, label = if (isLive) "Follow live" else "Go live") { c ->
                        BasicText(if (isLive) "Follow live" else "Go live", style = Rw.text(13f, c))
                    }
                }
            }
            RwSelect(year, years, { year = it }, "Season", fontSize = 13f, openUpwards = false)
            if (year == RECORDED) {
                val options = recordings.map { it.path to "${it.meeting} · ${it.name} · ${it.startDate.take(10)}" }.ifEmpty { listOf("" to "No recordings yet") }
                RwSelect(sessionPath, options, { sessionPath = it }, "Recording", fontSize = 13f, openUpwards = false)
            } else {
                RwSelect(meetingKey, meetings.map { it.key as Int? to it.name }, { key ->
                    meetingKey = key
                    sessionPath = meetings.firstOrNull { it.key == key }?.sessions?.lastOrNull()?.path ?: ""
                }, "Event", fontSize = 13f, openUpwards = false)
                RwSelect(sessionPath, meeting?.sessions?.map { it.path to it.name } ?: emptyList(), { sessionPath = it }, "Session", fontSize = 13f, openUpwards = false)
            }
            RwButton(
                onClick = {
                    val path = sessionPath
                    if (path.isEmpty()) return@RwButton
                    onClose()
                    actions.replay(path)
                },
                on = onNow == null,
                enabled = sessionPath.isNotEmpty(),
                label = "Replay",
            ) { c -> BasicText("Replay", style = Rw.text(13f, c)) }
            if (listError.isNotEmpty()) BasicText("Could not reach the F1 archive: $listError", style = Rw.text(13f, Rw.red))
        }
    }
}
