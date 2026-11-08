package com.racewall.android.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.racewall.android.Mode
import com.racewall.android.ui.Rw
import com.racewall.clock.ClockState
import com.racewall.topics.Bounds
import com.racewall.topics.CarPosition
import com.racewall.topics.CarPositions
import com.racewall.topics.CircuitOutline
import com.racewall.topics.Topic
import com.racewall.topics.TrackFlag
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private class P3(val x: Double, val y: Double, val k: Double)

/** Turns points by an angle in degrees; the labels are drawn afterwards, so they stay upright. */
private fun turner(deg: Double): (Double, Double) -> Pair<Double, Double> {
    val r = deg * PI / 180
    val c = cos(r)
    val s = sin(r)
    return { x, y -> (x * c - y * s) to (x * s + y * c) }
}

/** The outline rotated, with its bounds and lowest point. */
private class Turned(val points: List<Triple<Double, Double, Double>>, val minX: Double, val maxX: Double, val minY: Double, val maxY: Double, val minZ: Double)

private fun turn(outline: CircuitOutline, deg: Double): Turned {
    val rot = turner(deg)
    val points = outline.points.map { (x, y, z) -> rot(x, y).let { Triple(it.first, it.second, z) } }
    return Turned(points, points.minOf { it.first }, points.maxOf { it.first }, points.minOf { it.second }, points.maxOf { it.second }, points.minOf { it.third })
}

/** How high above the ground the 3D camera sits, as an angle, and how much hills are exaggerated. */
private const val CAMERA_ANGLE = 34 * PI / 180
private const val EXAGGERATION = 3.0

/**
 * Projects a rotated point on the circuit to a picture: x across, y up, and a depth factor that is
 * 1 everywhere in the flat view and larger the nearer the camera a point is in 3D. The 3D camera
 * looks at the middle of the circuit from the bottom edge of the flat map, tilted down.
 */
private fun projector(three: Boolean, t: Turned): (Double, Double, Double) -> P3 {
    if (!three) return { x, y, _ -> P3(x, y, 1.0) }
    val cx = (t.minX + t.maxX) / 2
    val cy = (t.minY + t.maxY) / 2
    val distance = maxOf(t.maxX - t.minX, t.maxY - t.minY, 1.0) * 2.5
    val s = sin(CAMERA_ANGLE)
    val c = cos(CAMERA_ANGLE)
    return { x, y, z ->
        val dx = x - cx
        val dy = y - cy
        val dz = (z - t.minZ) * EXAGGERATION
        val k = distance / (distance + dy * c - dz * s)
        P3(dx * k, (dy * s + dz * c) * k, k)
    }
}

/** A colour darkened towards black: 1 leaves it as it is. */
private fun dim(c: Color, f: Double): Color = Color(red = (c.red * f).toFloat(), green = (c.green * f).toFloat(), blue = (c.blue * f).toFloat())

private val TRACK_COLOUR = mapOf(TrackFlag.YELLOW to Rw.yellow, TrackFlag.SC to Rw.sc, TrackFlag.VSC to Rw.sc, TrackFlag.VSC_ENDING to Rw.sc, TrackFlag.RED to Rw.red)

private val TRACK_FLAG = mapOf(
    TrackFlag.YELLOW to "Yellow flag",
    TrackFlag.SC to "Safety car",
    TrackFlag.VSC to "Virtual safety car",
    TrackFlag.VSC_ENDING to "VSC ending",
    TrackFlag.RED to "Red flag",
)

private val RADIUS = mapOf("small" to 3.5, "normal" to 4.5, "large" to 6.0)

/** How far behind the newest frame the cars are drawn, in real milliseconds. */
internal const val LAG_MS = 250.0
/** A jump in session time bigger than this is a seek: the cars start again rather than slide. */
private const val JUMP_MS = 2000.0
/** The map is drawn at most this often: about 30 times a second. */
private const val MIN_FRAME_NANOS = 30_000_000L
/** How long the map drawn from the cars takes to ease into the outline's fit. */
private const val EASE_NANOS = 500_000_000L

internal class Sample(val t: Double, val x: Double, val y: Double, val z: Double)

/**
 * Smooth movement, as the desktop's plugins/track-map/src/motion.ts. Frames arrive about ten times
 * a second, so each car's last second of positions is kept with the session time it was for, and
 * the cars are drawn a little behind the newest frame, between the two positions around that
 * moment. Live, the clock is nudged back to the live edge by a few hundred milliseconds now and
 * then; that keeps the history (dropping only what now lies ahead of the clock), so the cars do not
 * snap.
 */
internal class Motion {
    val history = HashMap<String, ArrayDeque<Sample>>()
    private var lastCars: CarPositions? = null
    private var lastSampleT = Double.NEGATIVE_INFINITY
    private var lastClockTime = Double.NaN
    private var lastClockAt = 0L
    var trail = ArrayList<Sample>()
    var trailFor: String? = null

    fun clear() {
        history.clear()
        lastCars = null
        lastSampleT = Double.NEGATIVE_INFINITY
    }

    fun remember(cars: CarPositions, t: Double) {
        if (cars === lastCars) return
        lastCars = cars
        if (kotlin.math.abs(t - lastSampleT) > JUMP_MS) history.clear()
        else if (t < lastSampleT) for (h in history.values) while (h.isNotEmpty() && h.last().t > t) h.removeLast()
        lastSampleT = t
        for ((n, c) in cars) {
            val h = history.getOrPut(n) { ArrayDeque() }
            if (h.lastOrNull()?.t == t) continue
            h.addLast(Sample(t, c.x.toDouble(), c.y.toDouble(), c.z.toDouble()))
            while (h.isNotEmpty() && t - h.first().t > 1000) h.removeFirst()
        }
        history.keys.retainAll(cars.keys)
    }

    /** The session time to draw the cars at: the clock, carried forward since its last frame, less the lag. */
    fun displayTime(clock: ClockState, nowNanos: Long): Double {
        if (clock.time != lastClockTime) {
            lastClockTime = clock.time
            lastClockAt = nowNanos
        }
        if (!clock.playing) return clock.time
        return clock.time + ((nowNanos - lastClockAt) / 1e6 - LAG_MS) * clock.speed
    }

    fun positionAt(n: String, t: Double, fallback: CarPosition): Sample {
        val h = history[n]
        if (h.isNullOrEmpty()) return Sample(t, fallback.x.toDouble(), fallback.y.toDouble(), fallback.z.toDouble())
        val last = h.last()
        if (t >= last.t) return last
        if (t <= h.first().t) return h.first()
        var i = h.size - 2
        while (i > 0 && h[i].t > t) i--
        val a = h[i]
        val b = h[i + 1]
        val k = (t - a.t) / (b.t - a.t)
        return Sample(t, a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k, a.z + (b.z - a.z) * k)
    }
}

/** How often a car's place is added to the trace, and how much of it is kept, in session time. */
private const val TRACE_STEP_MS = 500.0
private const val TRACE_KEEP_MS = 90_000.0

/**
 * Before the circuit outline is known, where the cars have been: a faint trace of each car's last
 * minute and a half, and the area they have covered, which only grows so the picture holds still.
 */
internal class Trace {
    val cars = HashMap<String, ArrayList<Sample>>()
    var bounds: Bounds? = null
        private set
    private var key: String? = null

    /** Starts again for another session. */
    fun reset(key: String) {
        if (key == this.key) return
        this.key = key
        cars.clear()
        bounds = null
    }

    fun add(positions: CarPositions, t: Double) {
        for ((n, c) in positions) {
            if (!c.onTrack || (c.x == 0 && c.y == 0)) continue
            val list = cars.getOrPut(n) { ArrayList() }
            val last = list.lastOrNull()
            // A jump back in time (a seek) starts that car's trace again.
            if (last != null && (t < last.t || t - last.t > TRACE_KEEP_MS)) list.clear()
            else if (last != null && t - last.t < TRACE_STEP_MS) continue
            val x = c.x.toDouble()
            val y = c.y.toDouble()
            list.add(Sample(t, x, y, c.z.toDouble()))
            while (list.isNotEmpty() && t - list.first().t > TRACE_KEEP_MS) list.removeAt(0)
            val b = bounds
            bounds = if (b == null) Bounds(x, x, y, y) else Bounds(minOf(b.minX, x), maxOf(b.maxX, x), minOf(b.minY, y), maxOf(b.maxY, y))
        }
    }
}

/**
 * How the picture sits on the canvas, flat: a point turned by the rotation, less (minX, minY),
 * times `s`, plus (ox, oy), with y pointing up. Kept so the map can ease from one fit to another.
 */
private class Fit(val deg: Double, val s: Double, val ox: Double, val oy: Double, val minX: Double, val minY: Double)

private fun fitTo(minX: Double, maxX: Double, minY: Double, maxY: Double, w: Double, h: Double, pad: Double, deg: Double): Fit {
    val s = minOf((w - pad * 2) / (if (maxX - minX != 0.0) maxX - minX else 1.0), (h - pad * 2) / (if (maxY - minY != 0.0) maxY - minY else 1.0))
    return Fit(deg, s, (w - (maxX - minX) * s) / 2, (h - (maxY - minY) * s) / 2, minX, minY)
}

/** Where a car goes on the canvas, and its colour with distance in 3D. */
private class Placement(val at: (Double, Double, Double) -> P3, val shade: (Color, Double, Double) -> Color, val three: Boolean)

/** The circuit itself, drawn once into an image, with where cars go on it. */
private class Layer(val place: Placement, val fit: Fit?, val image: ImageBitmap)

private class Ease(val from: Fit, val start: Long)

/** What the map keeps between frames. */
private class MapState {
    val motion = Motion()
    val trace = Trace()
    var layer: Layer? = null
    var provisional: Fit? = null
    var ease by mutableStateOf<Ease?>(null)
}

/**
 * The transform, applied on top of the outline's fit, that puts the picture partway (k from 0 to 1)
 * between the fit of the map drawn from the cars and the outline's fit. Both are flat and turned
 * the same way, so one is the other scaled and moved.
 */
private fun easing(from: Fit, to: Fit, h: Double, k: Double): Triple<Float, Float, Float> {
    val a = from.s / to.s
    val bx = from.ox + (to.minX - from.minX) * from.s - to.ox * a
    val by = h - from.oy - (h - to.oy) * a - (to.minY - from.minY) * from.s
    return Triple((a + (1 - a) * k).toFloat(), (bx * (1 - k)).toFloat(), (by * (1 - k)).toFloat())
}

/**
 * Draws the circuit into an image the size of the map: the track, in 3D its ground line, and the
 * start line and sector marks. Done again only when the outline, size, rotation, view, track colour
 * or marks change.
 */
private fun CacheDrawScope.buildLayer(turned: Turned, outline: CircuitOutline, deg: Double, three: Boolean, colour: Color, sectorsOn: Boolean, startOn: Boolean, measurer: TextMeasurer): Layer {
    val w = size.width.toDouble()
    val h = size.height.toDouble()
    val px = 1.dp.toPx()
    val rot = turner(deg)
    val project = projector(three, turned)
    // Fit the picture to the canvas: the track and, in 3D, its outline on the ground as well.
    val track = turned.points.map { (x, y, z) -> project(x, y, z) }
    val ground = if (three) turned.points.map { (x, y) -> project(x, y, turned.minZ) } else emptyList()
    var minX = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    var kMin = Double.POSITIVE_INFINITY
    var kMax = Double.NEGATIVE_INFINITY
    for (list in listOf(track, ground)) for (p in list) {
        minX = minOf(minX, p.x); maxX = maxOf(maxX, p.x)
        minY = minOf(minY, p.y); maxY = maxOf(maxY, p.y)
        kMin = minOf(kMin, p.k); kMax = maxOf(kMax, p.k)
    }
    val fit = fitTo(minX, maxX, minY, maxY, w, h, 22.0 * px, deg)
    fun screen(p: P3) = P3(fit.ox + (p.x - fit.minX) * fit.s, h - (fit.oy + (p.y - fit.minY) * fit.s), p.k)
    // Nearer things are brighter in 3D: 0 for the farthest point of the track, 1 for the nearest.
    fun near(k: Double) = if (kMax > kMin) (k - kMin) / (kMax - kMin) else 1.0
    val shade = { c: Color, k: Double, floor: Double -> if (three) dim(c, floor + (1 - floor) * near(k)) else c }
    val place = Placement({ x, y, z -> rot(x, y).let { (rx, ry) -> screen(project(rx, ry, z)) } }, shade, three)
    val onScreen = track.map(::screen)

    val image = ImageBitmap(maxOf(1, size.width.toInt()), maxOf(1, size.height.toInt()))
    CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(image), Size(size.width, size.height)) {
        if (three) {
            // The ground: where the track would be with no hills, as a faint line under it.
            val g = Path()
            ground.forEachIndexed { i, p -> screen(p).let { if (i == 0) g.moveTo(it.x.toFloat(), it.y.toFloat()) else g.lineTo(it.x.toFloat(), it.y.toFloat()) } }
            g.close()
            drawPath(g, Color(0xFF171717), style = Stroke(1.5f * px, cap = StrokeCap.Round, join = StrokeJoin.Round))
            // The track, far segments first so the near ones cover them, thinner and dimmer with distance.
            val segments = onScreen.indices.map { onScreen[it] to onScreen[(it + 1) % onScreen.size] }.sortedBy { it.first.k + it.second.k }
            for ((a, b) in segments) {
                val k = (a.k + b.k) / 2
                drawLine(shade(colour, k, 0.5), Offset(a.x.toFloat(), a.y.toFloat()), Offset(b.x.toFloat(), b.y.toFloat()), (7 * k * px).toFloat(), StrokeCap.Round)
            }
        } else {
            val p = Path()
            onScreen.forEachIndexed { i, q -> if (i == 0) p.moveTo(q.x.toFloat(), q.y.toFloat()) else p.lineTo(q.x.toFloat(), q.y.toFloat()) }
            p.close()
            drawPath(p, colour, style = Stroke(7 * px, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // Marks across the track: the start line and the sector boundaries, with a label per sector.
        val n = onScreen.size
        fun across(i: Int): DoubleArray {
            val p = onScreen[i]
            val a = onScreen[(i + n - 1) % n]
            val b = onScreen[(i + 1) % n]
            val len = hypot(b.x - a.x, b.y - a.y).takeIf { it != 0.0 } ?: 1.0
            return doubleArrayOf(p.x, p.y, -(b.y - a.y) / len, (b.x - a.x) / len)
        }
        fun tick(i: Int, half: Float, c: Color, width: Float) {
            val (qx, qy, nx, ny) = across(i)
            drawLine(c, Offset((qx - nx * half * px).toFloat(), (qy - ny * half * px).toFloat()), Offset((qx + nx * half * px).toFloat(), (qy + ny * half * px).toFloat()), width * px, StrokeCap.Round)
        }
        val start = outline.start
        val sectors = outline.sectors
        if (sectorsOn && sectors != null && start != null) {
            tick(sectors.first, 5f, Rw.t3, 1.5f)
            tick(sectors.second, 5f, Rw.t3, 1.5f)
            // Each sector's label sits halfway along it, on the side away from the middle of the picture.
            val ends = listOf(start, sectors.first, sectors.second, start)
            for (sector in 0..2) {
                val from = ends[sector]
                val span = (ends[sector + 1] - from + n) % n
                val mid = (from + span / 2) % n
                val (qx, qy, nx, ny) = across(mid)
                val out = if (hypot(qx + nx - w / 2, qy + ny - h / 2) > hypot(qx - nx - w / 2, qy - ny - h / 2)) 1 else -1
                val layout = measurer.measure("S${sector + 1}", CHART_FONT.copy(color = Rw.t3))
                drawText(layout, topLeft = Offset((qx + nx * 14 * px * out - layout.size.width / 2).toFloat(), (qy + ny * 14 * px * out - layout.size.height / 2).toFloat()))
            }
        }
        if (startOn && start != null) tick(start, 7f, Rw.t2, 2f)
    }
    return Layer(place, if (three) null else fit, image)
}

/** What the map says while it has no circuit and no cars to draw: what it is waiting for, or what went wrong. */
internal fun circuitMessage(s: WidgetScope): String = when {
    s.frame.info == null -> "Pick a session to see the circuit."
    s.frame.cars == "loading" -> "Loading car positions…"
    s.frame.cars == "failed" -> "Car positions could not be loaded."
    s.mode == Mode.LIVE -> "Waiting for the cars…"
    s.frame.cars == "loaded" -> "No circuit outline for this session."
    else -> "Loading the circuit…"
}

/** The small note under a map drawn from the cars' own tracks, before the circuit outline is known. */
internal const val PROVISIONAL_NOTE = "Full circuit after a clean lap"

/** The faint trace of where the cars have been, the panel border's grey. */
private val TRACE_COLOUR = Color(0xFF1F1F1F)

/** The circuit and every car on it, flat or in 3D, as the desktop's plugins/track-map. */
val TrackMapWidget = WidgetSpec(
    id = "racewall.track-map",
    title = "Track",
    min = 10 to 8,
    size = 16 to 14,
    reads = setOf(Topic.CAR_POSITION, Topic.CIRCUIT, Topic.DRIVERS_LIST, Topic.TIMING_POSITIONS, Topic.SESSION_INFO, Topic.SESSION_STATUS),
    fields = listOf(
        Field.Select("view", "View", listOf("flat" to "Flat", "3d" to "3D"), "flat"),
        Field.Select("labels", "Driver labels", listOf("focus" to "Leader and focused driver", "top3" to "Top 3 and focused driver", "none" to "None", "all" to "Every car"), "focus"),
        Field.Select("dot", "Car size", listOf("small" to "Small", "normal" to "Normal", "large" to "Large"), "normal"),
        Field.Number("rotate", "Rotate the map", 0.0, 359.0, 1.0, 0.0, note = "Degrees. You can also drag the map to turn it."),
        Field.Toggle("startLine", "Start/finish line", true),
        Field.Toggle("sectors", "Sector boundaries", false),
        Field.Toggle("hidePits", "Hide cars in the pits", false),
        Field.Toggle("hideRetired", "Hide retired and stopped cars", true),
        Field.Toggle("trail", "Trail behind the focused driver", false),
    ),
    meta = { s -> listOfNotNull(s.frame.info?.circuit?.ifEmpty { null }, s.frame.status?.flag?.let { TRACK_FLAG[it] }).joinToString(" · ") },
) { s ->
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val state = remember { MapState() }
    /** Turning the map by dragging: the angle while the finger is down, saved when it is let go. */
    var liveDeg by remember { mutableStateOf<Double?>(null) }
    val clock = s.frame.clock
    // Keep drawing between frames while the session plays (or the map eases into the outline), so
    // the cars move smoothly, at most about 30 times a second.
    var frameNanos by remember { mutableLongStateOf(System.nanoTime()) }
    val easing = state.ease != null
    LaunchedEffect(clock.playing, easing) {
        if (!clock.playing && !easing) return@LaunchedEffect
        while (true) withFrameNanos { if (it - frameNanos >= MIN_FRAME_NANOS) frameNanos = it }
    }
    val outline = s.frame.topic<CircuitOutline>(Topic.CIRCUIT)
    val deg = liveDeg ?: s.values.num("rotate")
    val turned = remember(outline, deg) { outline?.takeIf { it.points.isNotEmpty() }?.let { turn(it, deg) } }
    val three = s.values.str("view") == "3d"
    val colour = TRACK_COLOUR[s.frame.status?.flag ?: TrackFlag.UNKNOWN] ?: Color(0xFF262626)
    val sectorsOn = s.values.bool("sectors")
    val startOn = s.values.bool("startLine")
    // The same block while nothing it draws has changed, so the image is kept between frames.
    val buildCache = remember(outline, turned, deg, three, colour, sectorsOn, startOn, measurer) {
        val block: CacheDrawScope.() -> DrawResult = {
            state.layer = if (outline != null && turned != null && size.width > 0 && size.height > 0) buildLayer(turned, outline, deg, three, colour, sectorsOn, startOn, measurer) else null
            onDrawBehind {}
        }
        block
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .testTag("track-map")
            .pointerInput(Unit) {
                // The angle of the finger around the middle of the map, in degrees, clockwise on screen.
                fun angle(p: Offset) = atan2((p.y - size.height / 2).toDouble(), (p.x - size.width / 2).toDouble()) * 180 / PI
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startAngle = angle(down.position)
                    val startDeg = liveDeg ?: s.values.num("rotate")
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        // Turning clockwise on screen is turning the circuit the other way, as the map's y points up.
                        liveDeg = (((startDeg - (angle(c.position) - startAngle)) % 360) + 360) % 360
                        c.consume()
                    }
                    liveDeg?.let { s.setSetting("rotate", JsonPrimitive(jsRound(it) % 360)) }
                    liveDeg = null
                }
            }
            .drawWithCache(buildCache),
    ) {
        // Read here, so every frame of the loop above redraws.
        if (frameNanos == 0L) return@Canvas
        val w = size.width
        val h = size.height
        val cars = s.frame.topic<CarPositions>(Topic.CAR_POSITION)?.takeIf { s.mode != Mode.IDLE }
        state.trace.reset(s.frame.info?.path ?: "")
        val layer = state.layer
        if (layer != null) {
            // The map drawn from the cars eases into the outline's fit, rather than jumping to it.
            val from = state.provisional
            if (from != null && layer.fit != null && from.deg == deg) state.ease = Ease(from, frameNanos)
            state.provisional = null
            val ease = state.ease
            val fit = layer.fit
            val k = if (ease != null && fit != null) ((frameNanos - ease.start).toDouble() / EASE_NANOS).coerceIn(0.0, 1.0) else 1.0
            if (ease != null && fit != null && k < 1) {
                val (scale, tx, ty) = easing(ease.from, fit, h.toDouble(), k)
                withTransform({
                    translate(tx, ty)
                    scale(scale, scale, Offset.Zero)
                }) {
                    drawImage(layer.image)
                    drawCars(s, state.motion, layer.place, cars, clock, frameNanos, measurer)
                }
                return@Canvas
            }
            if (ease != null) state.ease = null
            drawImage(layer.image)
            drawCars(s, state.motion, layer.place, cars, clock, frameNanos, measurer)
            return@Canvas
        }
        if (cars != null) state.trace.add(cars, clock.time)
        val bounds = state.trace.bounds
        if (cars == null || bounds == null) {
            state.provisional = null
            drawCars(s, state.motion, null, null, clock, frameNanos, measurer)
            fillText(measurer, circuitMessage(s), w / 2, h / 2, Rw.t3, Align.CENTER)
            return@Canvas
        }
        val px = 1.dp.toPx()
        val rot = turner(deg)
        val corners = listOf(rot(bounds.minX, bounds.minY), rot(bounds.minX, bounds.maxY), rot(bounds.maxX, bounds.minY), rot(bounds.maxX, bounds.maxY))
        val fit = fitTo(corners.minOf { it.first }, corners.maxOf { it.first }, corners.minOf { it.second }, corners.maxOf { it.second }, w.toDouble(), h.toDouble(), 22.0 * px, deg)
        state.provisional = fit
        val place = Placement(
            { x, y, _ -> rot(x, y).let { (rx, ry) -> P3(fit.ox + (rx - fit.minX) * fit.s, h - (fit.oy + (ry - fit.minY) * fit.s), 1.0) } },
            { c, _, _ -> c },
            false,
        )
        // Where each car has been: faint, so the outline that follows is plainly the real thing.
        val path = Path()
        for (list in state.trace.cars.values) {
            list.forEachIndexed { i, p ->
                val q = place.at(p.x, p.y, p.z)
                // A gap in a car's samples (it stopped, or was off the track) is not drawn across.
                if (i > 0 && p.t - list[i - 1].t < 3000) path.lineTo(q.x.toFloat(), q.y.toFloat()) else path.moveTo(q.x.toFloat(), q.y.toFloat())
            }
        }
        drawPath(path, TRACE_COLOUR, style = Stroke(2 * px, cap = StrokeCap.Round, join = StrokeJoin.Round))
        fillText(measurer, PROVISIONAL_NOTE, 8 * px, h - 8 * px, Rw.t3)
        drawCars(s, state.motion, place, cars, clock, frameNanos, measurer)
    }
}

/** Every car, its label and the focused driver's trail, placed by `place`; without cars, nothing, and the motion is forgotten. */
private fun DrawScope.drawCars(s: WidgetScope, motion: Motion, place: Placement?, cars: CarPositions?, clock: ClockState, frameNanos: Long, measurer: TextMeasurer) {
    val focus = s.focus
    if (cars == null || place == null) {
        motion.trail.clear()
        motion.clear()
        return
    }
    val px = 1.dp.toPx()
    val at = place.at
    val shade = place.shade
    motion.remember(cars, clock.time)
    val now = motion.displayTime(clock, frameNanos)
    val drivers = s.frame.drivers
    val rows = s.frame.positions
    val order = rows.map { it.number }
    val row = rows.associateBy { it.number }
    fun shown(num: String): Boolean {
        val c = cars[num] ?: return false
        if (!c.onTrack && c.x == 0 && c.y == 0) return false
        if (s.values.bool("hidePits") && row[num]?.inPit == true) return false
        if (s.values.bool("hideRetired") && (row[num]?.retired == true || row[num]?.stopped == true)) return false
        return true
    }
    // Draw back to front, so the leader sits on top; in 3D the nearer car does instead.
    fun rank(num: String) = order.indexOf(num).let { if (it < 0) Int.MAX_VALUE.toLong() * 2 else it.toLong() }
    val numbers = cars.keys.filter(::shown).sortedByDescending(::rank)
    class Placed(val n: String, val p: Sample, val q: P3)
    var placed = numbers.map { num -> motion.positionAt(num, now, cars.getValue(num)).let { p -> Placed(num, p, at(p.x, p.y, p.z)) } }
    if (place.three) placed = placed.sortedBy { it.q.k }
    val where = placed.associateBy { it.n }

    // The trail: the focused driver's last eight seconds. A new driver or a jump in time starts it again.
    val mine = focus?.let { where[it] }
    if (s.values.bool("trail") && focus != null && mine != null) {
        val last = motion.trail.lastOrNull()
        if (motion.trailFor != focus || (last != null && kotlin.math.abs(now - last.t) > 2000)) motion.trail.clear()
        motion.trailFor = focus
        if (last?.t != now) motion.trail.add(Sample(now, mine.p.x, mine.p.y, mine.p.z))
        motion.trail.removeAll { now - it.t > 8000 }
        if (motion.trail.size > 1) {
            val p = Path()
            motion.trail.forEachIndexed { i, smp -> at(smp.x, smp.y, smp.z).let { q -> if (i == 0) p.moveTo(q.x.toFloat(), q.y.toFloat()) else p.lineTo(q.x.toFloat(), q.y.toFloat()) } }
            drawPath(p, teamColour(drivers, focus).copy(alpha = 0.5f), style = Stroke(1.5f * px, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    } else {
        motion.trail.clear()
        motion.trailFor = null
    }

    val radius = RADIUS[s.values.str("dot")] ?: 4.5
    for (c in placed) {
        val r = ((if (c.n == focus) radius + 1.5 else radius) * c.q.k * px).toFloat()
        val centre = Offset(c.q.x.toFloat(), c.q.y.toFloat())
        drawCircle(shade(teamColour(drivers, c.n), c.q.k, 0.7), r, centre)
        drawCircle(if (c.n == focus) Rw.t1 else Color.Black, r, centre, style = Stroke((if (c.n == focus) 2f else 1.5f) * px))
    }

    fun withFocus(list: List<String>): List<Pair<String, Color>> =
        list.filter { it.isNotEmpty() && it != focus }.map { it to Rw.t2 } + listOfNotNull(focus?.let { it to Rw.t1 })
    val labelled = when (s.values.str("labels")) {
        "none" -> emptyList()
        "all" -> withFocus(numbers)
        "top3" -> withFocus(order.take(3))
        else -> withFocus(order.take(1))
    }
    // The focused driver's label keeps its spot; any label that would cover one already placed
    // moves down a line at a time until it is clear.
    val line = 13 * px
    class Box(val x: Float, var y: Float, val w: Float, val h: Float)
    val boxes = ArrayList<Box>()
    fun clear(b: Box) = boxes.all { o -> b.x >= o.x + o.w || o.x >= b.x + b.w || b.y >= o.y + o.h || o.y >= b.y + b.h }
    for ((num, c) in labelled.sortedByDescending { it.first == focus }) {
        val pl = where[num] ?: continue
        val text = code(drivers, num)
        val box = Box((pl.q.x + 9 * px).toFloat(), (pl.q.y - 19 * px).toFloat(), measurer.width(text), line)
        var tries = 0
        while (tries < 4 && !clear(box)) {
            box.y += line
            tries++
        }
        boxes.add(box)
        fillText(measurer, text, box.x, box.y + 11 * px, c)
    }
}

private operator fun DoubleArray.component1() = this[0]
private operator fun DoubleArray.component2() = this[1]
private operator fun DoubleArray.component3() = this[2]
private operator fun DoubleArray.component4() = this[3]
