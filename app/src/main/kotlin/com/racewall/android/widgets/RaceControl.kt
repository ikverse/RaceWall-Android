package com.racewall.android.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.racewall.android.Mode
import com.racewall.android.ui.Rw
import com.racewall.topics.RaceControlMessage
import com.racewall.topics.Topic

/** Which setting shows which category of message; anything not listed counts as Other. */
private val CATEGORY_SETTING = mapOf("Flag" to "showFlags", "SafetyCar" to "showSafetyCar", "Drs" to "showDrs", "CarEvent" to "showCarEvents")

private fun wanted(s: WidgetScope): List<RaceControlMessage> {
    val all = s.frame.topic<List<RaceControlMessage>>(Topic.RACE_CONTROL) ?: emptyList()
    val list = all.filter { s.values.bool(CATEGORY_SETTING[it.category] ?: "showOther") }
    val keep = if (s.values.str("keep") == "all") list.size else s.values.str("keep").toIntOrNull()?.takeIf { it != 0 } ?: 60
    return list.takeLast(keep)
}

/** Race control messages, as the desktop's plugins/race-control. */
val RaceControlWidget = WidgetSpec(
    id = "racewall.race-control",
    title = "Race control",
    min = 8 to 6,
    size = 10 to 16,
    reads = setOf(Topic.RACE_CONTROL, Topic.DRIVERS_LIST),
    fields = listOf(
        Field.Toggle("showFlags", "Flags", true),
        Field.Toggle("showSafetyCar", "Safety car", true),
        Field.Toggle("showDrs", "DRS", true),
        Field.Toggle("showCarEvents", "Penalties and investigations", true),
        Field.Toggle("showOther", "Other", true),
        Field.Select("order", "Order", listOf("newest" to "Newest first", "oldest" to "Oldest first"), "newest"),
        Field.Select("keep", "Messages kept", listOf("30" to "30", "60" to "60", "120" to "120", "all" to "All"), "60"),
    ),
    meta = { s -> if (s.mode == Mode.IDLE) "Last session" else wanted(s).size.let { if (it > 0) "$it messages" else "" } },
) { s ->
    val all = s.frame.topic<List<RaceControlMessage>>(Topic.RACE_CONTROL) ?: emptyList()
    val list = wanted(s)
    when {
        all.isEmpty() -> Empty("No messages yet.")
        list.isEmpty() -> Empty("No messages match the settings.")
        else -> {
            val shown = if (s.values.str("order") != "oldest") list.reversed() else list
            // The focused driver's messages stay bright and the rest dim. The feed writes "CAR 1 (VER)".
            val focus = s.focus
            val tag = focus?.let { "(${s.frame.drivers[it]?.code ?: it})" } ?: ""
            LazyColumn(Modifier.fillMaxSize().testTag("race-control")) {
                items(shown) { m ->
                    val colour = m.flag?.let { FLAG_COLOUR[it] } ?: if (m.category == "SafetyCar") Rw.sc else Color.Transparent
                    val time = listOfNotNull(m.lap?.takeIf { it != 0 }?.let { "Lap $it" }, m.utc.takeIf { it != 0L }?.let(::utcClock)).joinToString(" · ")
                    val text = when {
                        focus == null -> Rw.t1
                        m.message.contains("CAR $focus ") || m.message.contains(tag) -> Rw.t1
                        else -> Rw.t2
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .drawBehind {
                                drawRect(colour, size = Size(2.dp.toPx(), size.height))
                                drawLine(Rw.rowRule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
                            }
                            .padding(start = 11.dp, end = 10.dp, top = 6.dp, bottom = 7.dp),
                    ) {
                        BasicText(time, style = Rw.text(11f, Rw.t3))
                        BasicText(m.message, style = Rw.text(12f, text))
                    }
                }
            }
        }
    }
}
