package com.racewall.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.racewall.android.Format
import com.racewall.android.Frame
import com.racewall.android.Mode
import com.racewall.topics.Compound
import com.racewall.topics.TimedValue

/** The timing tower's settings, from plugins/timing/racewall-plugin.json, with its defaults. */
data class TimingSettings(
    /** "code" (three-letter code) or "surname". */
    val names: String = "code",
    val showInterval: Boolean = true,
    val showLast: Boolean = true,
    val showSectors: Boolean = true,
    val sectorBars: Boolean = true,
    val showTyre: Boolean = true,
    val compact: Boolean = false,
)

/** The tyre letters and colours of plugins/_shared/ui.ts. */
val TYRE: Map<Compound, Pair<String, Color>> = mapOf(
    Compound.SOFT to ("S" to Rw.soft),
    Compound.MEDIUM to ("M" to Rw.medium),
    Compound.HARD to ("H" to Rw.hard),
    Compound.INTERMEDIATE to ("I" to Rw.inter),
    Compound.WET to ("W" to Rw.wet),
    Compound.UNKNOWN to ("?" to Rw.t3),
)

/** The text colour of a timed value: purple, green, or the dim grey. */
private fun colour(t: TimedValue?): Color = when {
    t == null || t.value.isEmpty() -> Rw.t2
    t.overallFastest -> Rw.purple
    t.personalFastest -> Rw.green
    else -> Rw.t2
}

/** The sector's segment colour: purple, green, grey once set, near-black while still to come. */
private fun segment(t: TimedValue?): Color = when {
    t == null || t.value.isEmpty() -> Rw.segmentEmpty
    t.overallFastest -> Rw.purple
    t.personalFastest -> Rw.green
    else -> Rw.t2
}

/** The panel header's meta text, as the desktop widget's `ctx.setMeta`. */
fun timingMeta(frame: Frame, mode: Mode): String {
    val info = frame.info ?: return ""
    return if (mode == Mode.IDLE) "Last result · ${info.meeting}, ${info.name}" else Format.lapText(frame.status).ifEmpty { "${info.meeting}, ${info.name}" }
}

private enum class Col { P, DRIVER, MINI, GAP, INT, LAST, S1, S2, S3, TYRE }

/** One cell's text, colour, and how wide its content is beyond the text. */
private class Cell(val text: String, val color: Color, val extra: Float = 0f)

/**
 * Running order with gaps, last lap, sectors and tyres; tapping a driver focuses them everywhere.
 * Laid out like the desktop's HTML table: each column as wide as its widest cell, the space left
 * shared out in proportion, and columns dropped on narrow panels as the desktop's container
 * queries do.
 */
@Composable
fun TimingTower(frame: Frame, settings: TimingSettings, focus: String?, onFocus: (String) -> Unit, modifier: Modifier = Modifier) {
    val rows = frame.positions
    if (rows.isEmpty()) {
        BasicText("No timing yet.", style = Rw.text(13f, Rw.t3), modifier = modifier.padding(horizontal = 12.dp, vertical = 14.dp))
        return
    }
    val race = Format.isRace(frame.info)
    val measurer = rememberTextMeasurer(cacheSize = 512)
    val density = LocalDensity.current
    val body = Rw.text(13f)
    val head = Rw.text(11f, Rw.t3)

    BoxWithConstraints(modifier.fillMaxSize().testTag("timing")) {
        val width = maxWidth.value
        val visible = Col.entries.filter {
            when (it) {
                Col.MINI -> settings.sectorBars
                Col.INT -> settings.showInterval && width > 330
                Col.LAST -> settings.showLast && width > 330
                Col.S1, Col.S2, Col.S3 -> settings.showSectors && width > 430
                Col.TYRE -> settings.showTyre
                else -> true
            }
        }

        val table = rows.mapIndexed { i, row ->
            val d = frame.drivers[row.number]
            val lap = frame.laps[row.number]
            val stint = frame.stints[row.number]?.lastOrNull()
            val out = row.retired || row.stopped
            fun c(text: String, color: Color, extra: Float = 0f) = Cell(text, if (out) Rw.t3 else color, extra)
            val name = (if (settings.names == "surname") d?.lastName?.ifEmpty { null } ?: d?.code else d?.code) ?: row.number
            mapOf(
                Col.P to Cell("${if (row.position != 0) row.position else i + 1}", Rw.t3),
                // Team bar 3 + gap 7, and the pit badge: margin 6, padding 3 + 3, border 1 + 1.
                Col.DRIVER to c(name.ifEmpty { row.number }, Rw.t1, 10f + if (row.inPit) 14f + textWidth(measurer, "Pit", Rw.text(10f), density) else 0f),
                Col.MINI to Cell("", Rw.t1, 31f),
                Col.GAP to c(Format.gapText(row, i, race), Rw.t1),
                Col.INT to c(Format.intervalText(row, i), Rw.t2),
                Col.LAST to (if (race) c(lap?.lastLap?.value ?: "", colour(lap?.lastLap)) else c(lap?.bestLap ?: "", Rw.t2)),
                Col.S1 to c(lap?.sectors?.getOrNull(0)?.value ?: "", colour(lap?.sectors?.getOrNull(0))),
                Col.S2 to c(lap?.sectors?.getOrNull(1)?.value ?: "", colour(lap?.sectors?.getOrNull(1))),
                Col.S3 to c(lap?.sectors?.getOrNull(2)?.value ?: "", colour(lap?.sectors?.getOrNull(2))),
                Col.TYRE to Cell(stint?.let { "${it.tyreAge}" } ?: "", Rw.t2, if (stint != null) 20f else 0f),
            )
        }
        val headers = mapOf(
            Col.P to "P", Col.DRIVER to "Driver", Col.MINI to "", Col.GAP to "Gap", Col.INT to "Int",
            Col.LAST to if (race) "Last" else "Best", Col.S1 to "S1", Col.S2 to "S2", Col.S3 to "S3", Col.TYRE to "Tyre",
        )
        // Each column's widest content, plus its 6 + 6 padding; P is set to 24 in the desktop CSS.
        val content = visible.associateWith { col ->
            val cells = table.map { r -> r.getValue(col).let { textWidth(measurer, it.text, body, density) + it.extra } }
            maxOf(textWidth(measurer, headers.getValue(col), head, density), cells.max()) + 12f
        }
        val fixed = mapOf(Col.P to maxOf(24f, content.getValue(Col.P)))
        val flexible = visible.filter { it !in fixed }
        val total = content.values.sum() - content.getValue(Col.P) + fixed.getValue(Col.P)
        val spare = (width - total).coerceAtLeast(0f)
        val flexSum = flexible.sumOf { content.getValue(it).toDouble() }.toFloat()
        val widths = visible.associateWith { col -> fixed[col] ?: (content.getValue(col) + spare * content.getValue(col) / flexSum) }

        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(20.dp).background(Rw.bg), verticalAlignment = Alignment.CenterVertically) {
                for (col in visible) {
                    val left = col == Col.P || col == Col.DRIVER || col == Col.TYRE || col == Col.MINI
                    BasicText(
                        headers.getValue(col),
                        style = head.copy(textAlign = if (left) TextAlign.Start else TextAlign.End),
                        modifier = Modifier.width(widths.getValue(col).dp).padding(horizontal = 6.dp),
                        maxLines = 1,
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(rows, key = { _, r -> r.number }) { i, row ->
                    val cells = table[i]
                    val d = frame.drivers[row.number]
                    val lap = frame.laps[row.number]
                    val stint = frame.stints[row.number]?.lastOrNull()
                    val selected = row.number == focus
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(if (settings.compact) 19.dp else 23.dp)
                            .drawBehind {
                                drawLine(Rw.rowRule, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
                                if (selected) drawRect(Rw.brand, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
                            }
                            .clickable(remember { MutableInteractionSource() }, indication = null) { onFocus(row.number) }
                            .semantics { contentDescription = "Focus ${d?.code ?: row.number}" }
                            .testTag("row-${row.number}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (col in visible) {
                            val w = widths.getValue(col).dp
                            val cell = cells.getValue(col)
                            when (col) {
                                Col.DRIVER -> Row(Modifier.width(w).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(3.dp, 13.dp).background(d?.teamColour?.let(::hex) ?: Rw.t3, RoundedCornerShape(1.dp)))
                                    Box(Modifier.width(7.dp))
                                    BasicText(cell.text, style = body.copy(color = cell.color), maxLines = 1)
                                    if (row.inPit) {
                                        Box(Modifier.width(6.dp))
                                        Box(Modifier.border(1.dp, Rw.border2, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp)) {
                                            BasicText("Pit", style = Rw.text(10f, Rw.t2))
                                        }
                                    }
                                }
                                Col.MINI -> Row(Modifier.width(w).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    for (k in 0..2) Box(Modifier.size(9.dp, 3.dp).background(segment(lap?.sectors?.getOrNull(k)), RoundedCornerShape(1.dp)))
                                }
                                Col.TYRE -> Row(Modifier.width(w).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (stint != null) {
                                        val (letter, tc) = TYRE.getValue(stint.compound)
                                        Box(Modifier.size(15.dp).border(2.dp, tc, CircleShape), contentAlignment = Alignment.Center) {
                                            BasicText(letter, style = Rw.text(8f, tc, FontWeight.SemiBold).copy(lineHeight = Rw.text(8f).fontSize))
                                        }
                                        Box(Modifier.width(5.dp))
                                        BasicText(cell.text, style = body.copy(color = Rw.t2), maxLines = 1)
                                    }
                                }
                                else -> BasicText(
                                    cell.text,
                                    style = body.copy(color = cell.color, textAlign = if (col == Col.P) TextAlign.Start else TextAlign.End),
                                    modifier = Modifier.width(w).padding(horizontal = 6.dp),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun textWidth(measurer: androidx.compose.ui.text.TextMeasurer, text: String, style: TextStyle, density: androidx.compose.ui.unit.Density): Float {
    if (text.isEmpty()) return 0f
    val px = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width
    return with(density) { px.toDp().value }
}

/** "#RRGGBB" to a colour. */
fun hex(text: String): Color = Color(0xFF000000 or text.removePrefix("#").toLong(16))

