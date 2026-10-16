package com.racewall.android.widgets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import com.racewall.android.ui.Rw

enum class Align { LEFT, CENTER, RIGHT }

/** The chart text of plugins/_shared/ui.ts FONT: 11 px. */
val CHART_FONT: TextStyle = Rw.text(11f).copy(lineHeight = Rw.text(11f).fontSize)

/**
 * Canvas `fillText`: draws `text` with its baseline at `y`, left of, centred on or right of `x`.
 * Returns the width drawn, as `measureText` would.
 */
fun DrawScope.fillText(measurer: TextMeasurer, text: String, x: Float, y: Float, color: Color, align: Align = Align.LEFT, style: TextStyle = CHART_FONT): Float {
    if (text.isEmpty()) return 0f
    val layout = measurer.measure(text, style.copy(color = color), softWrap = false, maxLines = 1)
    val w = layout.size.width.toFloat()
    val left = when (align) {
        Align.LEFT -> x
        Align.CENTER -> x - w / 2
        Align.RIGHT -> x - w
    }
    drawText(layout, topLeft = Offset(left, y - layout.firstBaseline))
    return w
}

/** The width `text` would take, as canvas `measureText`. */
fun TextMeasurer.width(text: String, style: TextStyle = CHART_FONT): Float = if (text.isEmpty()) 0f else measure(text, style, softWrap = false, maxLines = 1).size.width.toFloat()

/** "1:28.1" for chart axes. */
fun shortLap(ms: Double): String {
    val s = ms / 1000
    val m = Math.floor(s / 60).toInt()
    return "$m:${(s - m * 60).fixed(1).padStart(4, '0')}"
}
