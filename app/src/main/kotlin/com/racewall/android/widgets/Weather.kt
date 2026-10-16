package com.racewall.android.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.racewall.android.Mode
import com.racewall.android.ui.Rw
import com.racewall.topics.Topic
import com.racewall.topics.Weather
import com.racewall.topics.WeatherSample
import kotlin.math.abs

private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/** Ten minutes of session time, for the trend arrows. */
private const val TREND_MS = 600_000.0

@Composable
private fun Kv(key: String, value: AnnotatedString) {
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(Rw.rowRule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(key, style = Rw.text(13f, Rw.t3), modifier = Modifier.weight(1f))
        BasicText(value, style = Rw.body)
    }
}

/** Weather at the track, as the desktop's plugins/weather. */
val WeatherWidget = WidgetSpec(
    id = "racewall.weather",
    title = "Weather",
    min = 8 to 7,
    size = 9 to 12,
    reads = setOf(Topic.WEATHER, Topic.WEATHER_HISTORY),
    fields = listOf(
        Field.Select("tempUnit", "Temperature", listOf("c" to "°C", "f" to "°F"), "c"),
        Field.Select("windUnit", "Wind speed", listOf("ms" to "m/s", "kmh" to "km/h", "mph" to "mph"), "ms"),
        Field.Toggle("pressure", "Air pressure", false),
        Field.Toggle("hideChart", "Hide the chart", false),
        Field.Select("chart", "Chart shows", listOf("track" to "Track temperature", "air" to "Air temperature", "both" to "Both"), "track"),
    ),
    meta = { s -> if (s.mode == Mode.IDLE) "End of last session" else "At the track" },
) { s ->
    val w = s.frame.topic<Weather>(Topic.WEATHER)
    if (w == null) {
        Empty("No weather data yet.")
        return@WidgetSpec
    }
    val f = s.values.str("tempUnit") == "f"
    fun deg(c: Double) = if (f) c * 9 / 5 + 32 else c
    val unit = if (f) "°F" else "°C"
    val wind = when (s.values.str("windUnit")) {
        "kmh" -> "${(w.windSpeed * 3.6).fixed(1)} km/h"
        "mph" -> "${(w.windSpeed * 2.237).fixed(1)} mph"
        else -> "${w.windSpeed.fixed(1)} m/s"
    }
    val history = s.frame.topic<List<WeatherSample>>(Topic.WEATHER_HISTORY) ?: emptyList()
    val time = s.frame.clock.time
    // How far a temperature moved over the last ten minutes, once that is big enough to mean something.
    val before = history.lastOrNull { it.t <= time - TREND_MS }
    fun withTrend(now: Double, then: Double?): AnnotatedString = buildAnnotatedString {
        append("${deg(now).fixed(1)} $unit")
        if (then == null) return@buildAnnotatedString
        val d = deg(now) - deg(then)
        if (abs(d) < (if (f) 0.9 else 0.5)) return@buildAnnotatedString
        withStyle(SpanStyle(color = Rw.t3, fontSize = 11.sp)) { append("  ${if (d > 0) "↑ +" else "↓ −"}${abs(d).fixed(1)}") }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("weather")) {
        Kv("Air", withTrend(w.airTemp, before?.airTemp))
        Kv("Track", withTrend(w.trackTemp, before?.trackTemp))
        Kv("Humidity", AnnotatedString("${jsRound(w.humidity)}%"))
        if (s.values.bool("pressure")) Kv("Pressure", AnnotatedString("${w.pressure.fixed(0)} hPa"))
        Kv("Wind", AnnotatedString("$wind ${COMPASS[(jsRound(w.windDirection / 45) % 8).toInt()]}"))
        Kv("Rain", if (w.rainfall) buildAnnotatedString { withStyle(SpanStyle(color = Rw.wet)) { append("Yes") } } else AnnotatedString("No"))
        if (history.size > 1 && !s.values.bool("hideChart")) {
            val chart = s.values.str("chart").let { if (it == "air" || it == "both") it else "track" }
            val track = history.map { deg(it.trackTemp) }
            val air = history.map { deg(it.airTemp) }
            val shown = when (chart) {
                "track" -> track
                "air" -> air
                else -> track + air
            }
            val lo = shown.min()
            val hi = shown.max()
            val caption = when (chart) {
                "track" -> "Track temperature"
                "air" -> "Air temperature"
                else -> "Track and air temperature"
            }
            val rain = history.any { it.rainfall }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                BasicText("$caption, ${lo.fixed(0)} to ${hi.fixed(0)} $unit${if (rain) " · rain in blue" else ""}", style = Rw.text(11f, Rw.t3), modifier = Modifier.padding(bottom = 4.dp))
                val t0 = history.first().t
                val t1 = history.last().t
                Canvas(Modifier.fillMaxWidth().height(36.dp)) {
                    fun x(t: Double) = ((t - t0) / (if (t1 - t0 != 0.0) t1 - t0 else 1.0) * size.width).toFloat()
                    fun y(v: Double) = ((34 - (v - lo) / (if (hi - lo != 0.0) hi - lo else 1.0) * 30) / 36 * size.height).toFloat()
                    for (r in history) if (r.rainfall) drawRect(Color(0x592F7FFF), Offset(x(r.t), 0f), Size(size.width * 0.008f, size.height))
                    fun line(values: List<Double>, stroke: Color) {
                        val p = Path()
                        history.forEachIndexed { i, r -> if (i == 0) p.moveTo(x(r.t), y(values[i])) else p.lineTo(x(r.t), y(values[i])) }
                        drawPath(p, stroke, style = Stroke(1.dp.toPx()))
                    }
                    when (chart) {
                        "air" -> line(air, Rw.t3)
                        "both" -> {
                            line(air, Rw.t2)
                            line(track, Rw.t3)
                        }
                        else -> line(track, Rw.t3)
                    }
                    val now = x(time.coerceIn(t0, t1))
                    drawLine(Rw.t1, Offset(now, 0f), Offset(now, size.height), 1.dp.toPx())
                }
            }
        }
    }
}
