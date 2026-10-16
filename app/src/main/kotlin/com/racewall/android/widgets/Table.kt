package com.racewall.android.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.racewall.android.ui.Rw
import com.racewall.topics.Compound
import com.racewall.topics.DriverList

/**
 * The desktop's widget tables (plugins/_shared/ui.ts TABLE_CSS), laid out as HTML lays out a table:
 * each column as wide as its widest cell, and the space left shared out in proportion.
 */
class Cell(
    /** How wide the content is, without the cell's padding. */
    val width: Float,
    val left: Boolean = false,
    /** Columns this cell covers, as `colspan`. */
    val span: Int = 1,
    val content: @Composable () -> Unit,
)

class TableRow(val key: String, val number: String?, val cells: List<Cell>)

class Header(val text: String, val left: Boolean = false, val fixed: Float? = null)

/** Measures and draws text in the table style: 13 px, or as given. */
class TableText(val measurer: TextMeasurer, val density: androidx.compose.ui.unit.Density) {
    fun width(text: String, size: Float = 13f): Float = with(density) { measurer.width(text, Rw.text(size)).toDp().value }

    fun text(text: String, color: Color = Rw.t1, left: Boolean = false, size: Float = 13f) = Cell(width(text, size), left) {
        BasicText(text, style = Rw.text(size, color).copy(textAlign = if (left) TextAlign.Start else TextAlign.End), maxLines = 1, modifier = Modifier.fillMaxWidth())
    }

    fun rich(text: AnnotatedString, left: Boolean = false) = Cell(width(text.text), left) {
        BasicText(text, style = Rw.body.copy(textAlign = if (left) TextAlign.Start else TextAlign.End), maxLines = 1, modifier = Modifier.fillMaxWidth())
    }

    /** A driver cell: team colour bar and three-letter code. */
    fun driver(drivers: DriverList, number: String, color: Color = Rw.t1) = Cell(10f + width(code(drivers, number)), left = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(3.dp, 13.dp).background(teamColour(drivers, number), RoundedCornerShape(1.dp)))
            Box(Modifier.width(7.dp))
            BasicText(code(drivers, number), style = Rw.text(13f, color), maxLines = 1)
        }
    }

    /** A tyre badge and its age in laps. */
    fun tyre(compound: Compound?, age: Int?) = Cell(if (compound == null) 0f else 20f + width("${age ?: 0}"), left = true) {
        if (compound != null) TyreBadge(compound, age ?: 0)
    }
}

@Composable
fun rememberTableText(): TableText {
    val m = rememberTextMeasurer(cacheSize = 256)
    val d = LocalDensity.current
    return remember(m, d) { TableText(m, d) }
}

/** The tyre letter in a ring of its colour, and its age. */
@Composable
fun TyreBadge(compound: Compound, age: Int) {
    val (letter, colour) = TYRE.getValue(compound)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(15.dp).border(2.dp, colour, CircleShape), contentAlignment = Alignment.Center) {
            BasicText(letter, style = Rw.text(8f, colour, FontWeight.SemiBold).copy(lineHeight = Rw.text(8f).fontSize))
        }
        Box(Modifier.width(5.dp))
        BasicText("$age", style = Rw.text(13f, Rw.t2), maxLines = 1)
    }
}

/**
 * The table: an optional header row (11 px, dim), then rows 23 high with a faint rule above each.
 * Tapping a row with a driver number focuses that driver; the focused row has the thin red line.
 */
@Composable
fun AutoTable(
    headers: List<Header>?,
    rows: List<TableRow>,
    focus: String?,
    onFocus: ((String) -> Unit)?,
    columns: Int = headers?.size ?: rows.firstOrNull()?.cells?.sumOf { it.span } ?: 0,
    padding: Float = 6f,
    tt: TableText = rememberTableText(),
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val total = maxWidth.value
        val content = FloatArray(columns)
        headers?.forEachIndexed { i, h -> content[i] = maxOf(content[i], tt.width(h.text, 11f)) }
        for (r in rows) {
            var c = 0
            for (cell in r.cells) {
                if (cell.span == 1) content[c] = maxOf(content[c], cell.width)
                c += cell.span
            }
        }
        val widths = FloatArray(columns) { i -> (headers?.getOrNull(i)?.fixed?.let { maxOf(it, content[i] + 2 * padding) }) ?: (content[i] + 2 * padding) }
        val flexible = (0 until columns).filter { headers?.getOrNull(it)?.fixed == null }
        val spare = (total - widths.sum()).coerceAtLeast(0f)
        val flexSum = flexible.sumOf { widths[it].toDouble() }.toFloat().takeIf { it > 0 } ?: 1f
        for (i in flexible) widths[i] += spare * widths[i] / flexSum
        Column(Modifier.fillMaxWidth()) {
            if (headers != null) {
                Row(Modifier.fillMaxWidth().height(20.dp).background(Rw.bg), verticalAlignment = Alignment.CenterVertically) {
                    headers.forEachIndexed { i, h ->
                        BasicText(
                            h.text,
                            style = Rw.text(11f, Rw.t3).copy(textAlign = if (h.left) TextAlign.Start else TextAlign.End),
                            modifier = Modifier.width(widths[i].dp).padding(horizontal = padding.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
            for (r in rows) {
                val selected = r.number != null && r.number == focus
                var mod = Modifier
                    .fillMaxWidth()
                    .height(23.dp)
                    .drawBehind {
                        drawLine(Rw.rowRule, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
                        if (selected) drawRect(Rw.brand, size = Size(2.dp.toPx(), size.height))
                    }
                    .testTag("row-${r.key}")
                if (r.number != null && onFocus != null) mod = mod.clickable(remember { MutableInteractionSource() }, null) { onFocus(r.number) }
                Row(mod, verticalAlignment = Alignment.CenterVertically) {
                    var c = 0
                    for (cell in r.cells) {
                        val w = (c until c + cell.span).sumOf { widths.getOrElse(it) { 0f }.toDouble() }.toFloat()
                        Box(Modifier.width(w.dp).padding(horizontal = padding.dp), contentAlignment = if (cell.left) Alignment.CenterStart else Alignment.CenterEnd) { cell.content() }
                        c += cell.span
                    }
                }
            }
        }
    }
}

/** The `.note` line: small and dim. */
@Composable
fun Note(text: String) {
    BasicText(text, style = Rw.text(11f, Rw.t3), modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
}
