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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.racewall.android.Format
import com.racewall.android.Frame
import com.racewall.android.R
import com.racewall.android.UiState
import com.racewall.archive.ArchiveMeeting
import com.racewall.clock.SPEEDS
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
}

private val SOURCE_NAMES = mapOf("f1-live" to "F1 live timing", "f1-archive" to "F1 archive", "openf1" to "OpenF1", "jolpica" to "Jolpica")

/**
 * The transport bar, as the desktop's Transport.svelte: the session picker, play and pause, the
 * position in the session, the speed, the lap and the clock, the track flag, and what is loading.
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
) {
    val info = frame.info
    val clock = frame.clock
    val flag = frame.status?.let { Format.flag(it.flag) }
    val sourceText = frame.sources.joinToString(", ") { SOURCE_NAMES[it] ?: it }

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
                    if (info != null && info.meeting.isNotEmpty()) "${info.meeting} · ${info.name} ${Format.year(info.startDate)}" else "Pick a session",
                    style = Rw.text(12f, c),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
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
                label = "Replay position",
                modifier = Modifier.weight(1f).widthIn(max = 520.dp),
            )
            RwSelect(
                value = clock.speed,
                options = SPEEDS.map { it to "${if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()}x" },
                onChange = actions::setSpeed,
                label = "Speed",
            )
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
        if (pickerOpen) SessionPicker(actions, onClose = { setPicker(false) })
    }
}

/**
 * The session picker: season, then event, then session, then Replay. It lists the chosen season
 * when it opens, and picks the season's last event and that event's last session.
 */
@Composable
private fun SessionPicker(actions: TransportActions, onClose: () -> Unit) {
    val thisYear = remember { Instant.now().atZone(ZoneOffset.UTC).year }
    val years = remember { (thisYear downTo 2018).toList() }
    var year by remember { mutableStateOf(thisYear) }
    var meetings by remember { mutableStateOf<List<ArchiveMeeting>>(emptyList()) }
    var meetingKey by remember { mutableStateOf<Int?>(null) }
    var sessionPath by remember { mutableStateOf("") }
    var listError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(year) {
        listError = ""
        meetings = emptyList()
        meetingKey = null
        sessionPath = ""
        try {
            meetings = actions.listSeason(year)
            val last = meetings.lastOrNull()
            meetingKey = last?.key
            sessionPath = last?.sessions?.lastOrNull()?.path ?: ""
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
        offset = with(androidx.compose.ui.platform.LocalDensity.current) { IntOffset(8.dp.roundToPx(), -40.dp.roundToPx()) },
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true),
    ) {
        Box {
            FlowRow(
                Modifier
                    .background(Rw.bg, RoundedCornerShape(6.dp))
                    .border(1.dp, Rw.border2, RoundedCornerShape(6.dp))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                RwSelect(year, years.map { it to "$it" }, { year = it }, "Season", fontSize = 13f, openUpwards = false)
                RwSelect(meetingKey, meetings.map { it.key as Int? to it.name }, { key ->
                    meetingKey = key
                    sessionPath = meetings.firstOrNull { it.key == key }?.sessions?.lastOrNull()?.path ?: ""
                }, "Event", fontSize = 13f, openUpwards = false)
                RwSelect(sessionPath, meeting?.sessions?.map { it.path to it.name } ?: emptyList(), { sessionPath = it }, "Session", fontSize = 13f, openUpwards = false)
                RwButton(
                    onClick = {
                        val path = sessionPath
                        if (path.isEmpty()) return@RwButton
                        onClose()
                        scope.launch { actions.replay(path) }
                    },
                    on = true,
                    enabled = sessionPath.isNotEmpty(),
                    label = "Replay",
                ) { c -> BasicText("Replay", style = Rw.text(13f, c)) }
                if (listError.isNotEmpty()) BasicText("Could not reach the F1 archive: $listError", style = Rw.text(13f, Rw.red))
            }
        }
    }
}
