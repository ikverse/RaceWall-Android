package com.racewall.android.widgets

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.racewall.android.ui.Rw
import com.racewall.topics.Schedule
import com.racewall.topics.ScheduledSession
import com.racewall.topics.Topic
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** How long a session counts as on after it starts. */
private const val ON_NOW_MS = 3 * 3600 * 1000L

/** "Sun 26 Oct, 14:00", in the viewer's way of writing dates, as `toLocaleString` with those parts. */
private fun stamp(ms: Long, utc: Boolean): String {
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMMHHmm")
    return SimpleDateFormat(pattern, Locale.getDefault()).apply { if (utc) timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}

private fun p(n: Long) = n.toString().padStart(2, '0')

private class Next(val current: ScheduledSession?, val next: ScheduledSession?, val upcoming: List<ScheduledSession>)

private fun nextOf(s: WidgetScope, now: Long): Next {
    val schedule = s.frame.topic<Schedule>(Topic.SCHEDULE) ?: emptyList()
    val current = schedule.firstOrNull { it.start != null && it.start!! <= now && now < it.start!! + ON_NOW_MS }
    var upcoming = schedule.filter { it.start != null && it.start!! > now }
    val next = upcoming.firstOrNull()
    if (s.values.str("scope") == "weekend" && next != null) upcoming = upcoming.filter { it.round == next.round }
    return Next(current, next, upcoming)
}

@Composable
private fun Kv(key: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(Rw.rowRule, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        BasicText(key, style = Rw.text(13f, Rw.t3), modifier = Modifier.weight(1f), maxLines = 1)
        BasicText(value, style = Rw.body, maxLines = 1)
    }
}

/** A countdown to the next session and the sessions after it, as the desktop's plugins/next-session. */
val NextSessionWidget = WidgetSpec(
    id = "racewall.next-session",
    title = "Next session",
    min = 10 to 7,
    size = 16 to 11,
    reads = setOf(Topic.SCHEDULE),
    fields = listOf(
        Field.Select("clock", "Times shown in", listOf("local" to "Your time", "utc" to "UTC"), "local"),
        Field.Select("count", "Sessions listed", listOf("3" to "3", "5" to "5", "10" to "10"), "5"),
        Field.Select("scope", "List", listOf("season" to "Rest of the season", "weekend" to "This weekend only"), "season"),
        Field.Toggle("hideSeconds", "Hide seconds in the countdown", false),
    ),
    meta = { s ->
        val n = nextOf(s, System.currentTimeMillis())
        when {
            n.current != null -> "Round ${n.current.round} · on now"
            n.next == null -> ""
            else -> "Round ${n.next.round}"
        }
    },
) { s ->
    // The countdown runs on the wall clock, not the replay clock.
    var now by androidx.compose.runtime.remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    val utc = s.values.str("clock") == "utc"
    val count = s.values.str("count").toIntOrNull()?.takeIf { it != 0 } ?: 5
    val schedule = s.frame.topic<Schedule>(Topic.SCHEDULE) ?: emptyList()
    val n = nextOf(s, now)
    @Composable
    fun list(from: Int) {
        for (x in n.upcoming.drop(from).take(count)) Kv("${x.location} · ${x.name}", x.start?.let { stamp(it, utc) } ?: "")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("next-session")) {
        val current = n.current
        val next = n.next
        when {
            current?.start != null -> {
                Column(Modifier.padding(12.dp)) {
                    BasicText("${current.meeting} · ${current.name}", style = Rw.text(13f, Rw.t2))
                    BasicText("On now", style = Rw.text(28f, Rw.t1, FontWeight.Light).copy(lineHeight = (28 * 1.2).sp, letterSpacing = 1.sp))
                    BasicText("Started ${stamp(current.start!!, utc)}", style = Rw.text(13f, Rw.t3))
                }
                list(0)
            }
            next?.start == null -> Empty(if (schedule.isNotEmpty()) "No more sessions this season." else "Loading the schedule…")
            else -> {
                val secs = (next.start!! - now) / 1000
                val days = secs / 86400
                val clock = "${p((secs % 86400) / 3600)}:${p((secs % 3600) / 60)}${if (s.values.bool("hideSeconds")) "" else ":${p(secs % 60)}"}"
                Column(Modifier.padding(12.dp)) {
                    BasicText("${next.meeting} · ${next.name}", style = Rw.text(13f, Rw.t2))
                    BasicText("${if (days != 0L) "${days}d " else ""}$clock", style = Rw.text(40f, Rw.t1, FontWeight.Light).copy(lineHeight = (40 * 1.2).sp, letterSpacing = 1.sp), modifier = Modifier.testTag("countdown"))
                    BasicText("${stamp(next.start!!, utc)}, ${if (utc) "UTC" else "your time"}", style = Rw.text(13f, Rw.t3))
                }
                list(1)
            }
        }
    }
}
