package com.racewall.android.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.racewall.archive.ArchiveMeeting
import com.racewall.clock.SPEEDS
import com.racewall.live.LiveStatus
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset

/** What the bottom bar can ask of the core. */
interface TransportActions {
    fun toggle()
    fun seek(t: Double)
    fun setSpeed(speed: Double)
    suspend fun listSeason(year: Int): List<ArchiveMeeting>
    /** Loads a session and starts playing it. */
    suspend fun replay(path: String)
    suspend fun listRecordings(): List<Recording> = emptyList()
    /** Connects to live timing, or jumps to the live moment when already connected. */
    fun goLive() {}
    fun syncDelay() {}
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

/**
 * The transport bar, as the desktop's Transport.svelte: the session picker, play and pause, the
 * position in the session, the speed or, while live, the delay and Sync, the lap and the clock, the
 * track flag, and what is loading.
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
) {
    val info = frame.info
    val clock = frame.clock
    val flag = frame.status?.let { Format.flag(it.flag) }
    val sourceText = frame.sources.joinToString(", ") { SOURCE_NAMES[it] ?: it }
    val live = ui.session?.live
    val onNow = ui.onNow
    val behind = if (live != null) maxOf(0, Math.round((clock.end - clock.time) / 1000).toInt()) else 0

    Box(modifier.fillMaxWidth().height(34.dp).alpha(chromeAlpha)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .drawBehind { drawLine(Rw.border, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RwButton(onClick = { setPicker(!pickerOpen) }, label = "Pick a past session", modifier = Modifier.widthIn(max = 340.dp)) { c ->
                Icon(R.drawable.ic_folder, c)
                BasicText(
                    when {
                        info != null && info.meeting.isNotEmpty() -> "${info.meeting} · ${info.name} ${Format.year(info.startDate)}"
                        live != null -> "Live timing"
                        else -> "Pick a session"
                    },
                    style = Rw.text(12f, c),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
            if (live == null && onNow != null) {
                RwButton(onClick = actions::goLive, on = true, label = "Go live") { c -> BasicText("Go live · ${onNow.name}", style = Rw.text(12f, c), maxLines = 1) }
            }
            if (live != null) {
                val connected = live.status == LiveStatus.CONNECTED
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("live-status")) {
                    Dot(connected, if (connected) Rw.t1 else Rw.t2)
                    BasicText(if (connected) "Live" else LIVE_STATUS[live.status] ?: "", style = Rw.text(12f, if (connected) Rw.t1 else Rw.t2), maxLines = 1)
                }
            }
            RwButton(onClick = actions::toggle, label = if (clock.playing) "Pause" else "Play") { c ->
                Icon(if (clock.playing) R.drawable.ic_pause else R.drawable.ic_play, c)
            }
            RwSlider(
                value = clock.time,
                min = clock.start,
                max = clock.end,
                step = 1000.0,
                onChange = actions::seek,
                label = if (live != null) "Position in the live session" else "Replay position",
                modifier = Modifier.weight(1f).widthIn(max = 520.dp),
            )
            if (live != null) {
                if (!live.following) {
                    RwButton(onClick = actions::goLive, on = true, label = "Jump to the live moment") { c ->
                        BasicText("Go live${if (behind > 0) " ($behind s behind)" else ""}", style = Rw.text(12f, c), maxLines = 1)
                    }
                }
                if (videoDriving) BasicText("Synced to video", style = Rw.text(12f, Rw.t2), maxLines = 1)
                else {
                    BasicText("Delay ${Math.round(live.delayMs / 1000.0)} s", style = Rw.text(12f, Rw.t2), maxLines = 1)
                    RwButton(onClick = actions::syncDelay, label = "Sync") { c -> BasicText("Sync", style = Rw.text(12f, c)) }
                }
            } else {
                RwSelect(
                    value = clock.speed,
                    options = SPEEDS.map { it to "${if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()}x" },
                    onChange = actions::setSpeed,
                    label = "Speed",
                )
            }
            val lap = Format.lapText(frame.status)
            val time = frame.utc?.let { "${Format.utcClock(it)} UTC" } ?: Format.elapsed(clock.time)
            BasicText(if (lap.isNotEmpty()) "$lap · $time" else time, style = Rw.text(12f, Rw.t2), modifier = Modifier.widthIn(min = 170.dp), maxLines = 1)
            if (flag != null) BasicText(flag.first, style = Rw.text(12f, flag.second), maxLines = 1)
            Spacer(Modifier.weight(1f, fill = true))
            when {
                ui.loading.isNotEmpty() -> BasicText(ui.loading, style = Rw.text(12f, Rw.t3), maxLines = 1)
                ui.notice.isNotEmpty() -> BasicText(ui.notice, style = Rw.text(12f, Rw.t3), maxLines = 1)
                ui.error.isNotEmpty() -> BasicText(ui.error, style = Rw.text(12f, Rw.red), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 420.dp))
            }
            if (sourceText.isNotEmpty()) BasicText("Sources: $sourceText", style = Rw.text(12f, Rw.t3), maxLines = 1)
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
    val scope = rememberCoroutineScope()

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
                    scope.launch { actions.replay(path) }
                },
                on = onNow == null,
                enabled = sessionPath.isNotEmpty(),
                label = "Replay",
            ) { c -> BasicText("Replay", style = Rw.text(13f, c)) }
            if (listError.isNotEmpty()) BasicText("Could not reach the F1 archive: $listError", style = Rw.text(13f, Rw.red))
        }
    }
}
