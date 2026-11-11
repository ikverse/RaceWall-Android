package com.racewall.android.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.racewall.android.R
import com.racewall.android.home.HomeLogic.WeekendView
import com.racewall.topics.ScheduledSession
import kotlin.math.roundToInt

/**
 * The widgets' pictures, drawn at the widget's own size so they keep Selawik and the design lock's
 * colours: a launcher draws a widget's text in the system font, so text is drawn here instead.
 * Every size is in dp; the canvas is scaled to the screen's density. Black, a 1 dp border, dim
 * static labels and bright changing data, as the app's panels. Each widget's [Look] may fade the
 * black, use the Dim panels fill, drop the border or the corners, and make everything smaller or larger.
 */
class Pen(context: Context) {
    val regular: Typeface = ResourcesCompat.getFont(context, R.font.selawik_regular) ?: Typeface.DEFAULT
    val semibold: Typeface = ResourcesCompat.getFont(context, R.font.selawik_semibold) ?: Typeface.DEFAULT_BOLD
    /** Light only for the countdown, as the app's next-session countdown. */
    val light: Typeface = ResourcesCompat.getFont(context, R.font.selawik_light) ?: Typeface.DEFAULT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun set(size: Float, colour: Long, face: Typeface, spacing: Float) = paint.apply {
        textSize = size
        color = colour.toInt()
        typeface = face
        letterSpacing = spacing
        textAlign = Paint.Align.LEFT
    }

    fun width(s: String, size: Float, face: Typeface = regular, spacing: Float = 0f): Float = set(size, 0, face, spacing).measureText(s)

    /** The text cut to fit `max`, with an ellipsis. */
    fun fit(s: String, size: Float, face: Typeface, max: Float, spacing: Float = 0f): String {
        if (max <= 0f) return ""
        if (width(s, size, face, spacing) <= max) return s
        var n = s.length
        while (n > 0 && width(s.take(n).trimEnd() + "…", size, face, spacing) > max) n--
        return if (n == 0) "" else s.take(n).trimEnd() + "…"
    }

    /** One line of text in a box `size * 1.35` high whose top is `top`, as the app's `Rw.text`. */
    fun text(
        c: Canvas, s: String, x: Float, top: Float, size: Float, colour: Long,
        face: Typeface = regular, right: Boolean = false, max: Float = Float.MAX_VALUE, spacing: Float = 0f,
    ): Float {
        val shown = fit(s, size, face, max, spacing)
        val p = set(size, colour, face, spacing)
        val fm = p.fontMetrics
        val line = size * 1.35f
        val baseline = top + line / 2f - (fm.ascent + fm.descent) / 2f
        val w = p.measureText(shown)
        c.drawText(shown, if (right) x - w else x, baseline, p)
        return w
    }

    /** A static label: small, dim, in capitals. */
    fun label(c: Canvas, s: String, x: Float, top: Float, max: Float = Float.MAX_VALUE, right: Boolean = false, colour: Long = T3) =
        text(c, s.uppercase(), x, top, 10.5f, colour, semibold, right, max, 0.06f)

    fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, colour: Long) {
        fill.style = Paint.Style.FILL
        fill.color = colour.toInt()
        c.drawRect(l, t, r, b, fill)
    }

    fun circle(c: Canvas, x: Float, y: Float, radius: Float, colour: Long, stroke: Boolean = false) {
        fill.style = if (stroke) Paint.Style.STROKE else Paint.Style.FILL
        fill.strokeWidth = 1f
        fill.color = colour.toInt()
        c.drawCircle(x, y, radius, fill)
    }

    /**
     * The panel: black (or the Dim panels fill) at the widget's opacity, rounded as the launcher's
     * widgets or square, with the app's 1 dp border unless it is turned off.
     */
    fun panel(c: Canvas, env: FaceEnv) {
        val look = env.look
        val r = if (look.square) 0f else env.radius
        val alpha = (look.opacity.coerceIn(0f, 1f) * 255f).roundToInt()
        if (alpha > 0) {
            fill.style = Paint.Style.FILL
            fill.color = (alpha shl 24) or ((if (look.dim) DIM else BG).toInt() and 0xFFFFFF)
            c.drawRoundRect(RectF(0f, 0f, env.w, env.h), r, r, fill)
        }
        if (look.border) {
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = 1f
            fill.color = BORDER.toInt()
            c.drawRoundRect(RectF(0.5f, 0.5f, env.w - 0.5f, env.h - 0.5f), r, r, fill)
        }
    }

    companion object {
        // design/DESIGN-LOCK.md, as the app's Rw.
        const val BG = 0xFF000000
        /** The Dim panels fill, for LCD screens. */
        const val DIM = 0xFF0B0B0B
        const val BORDER = 0xFF1F1F1F
        const val BORDER2 = 0xFF2C2C2C
        const val T1 = 0xFFD0D0D0
        const val T2 = 0xFF8A8A8A
        const val T3 = 0xFF5A5A5A
        /** Only for the selected-row line: here, the followed driver or team and the next session. */
        const val BRAND = 0xFFA0151E
    }
}

/** What a face needs besides its data. */
data class FaceEnv(val w: Float, val h: Float, val now: Long, val h24: Boolean, val radius: Float = 16f, val look: Look = Look()) {
    /**
     * The same widget in drawing units: a larger text size draws everything on a canvas scaled up,
     * so the face lays itself out for a smaller widget. Its look's scale is then 1.
     */
    fun inner(): FaceEnv = if (look.scale == 1f) this else copy(w = w / look.scale, h = h / look.scale, radius = radius / look.scale, look = look.copy(scale = 1f))
}

/** The size of the ticking countdown Android draws over the race weekend picture, and where. */
object Tick {
    fun size(w: Float, h: Float): Float = if (w < 200f || h < 120f) 30f else 40f
    /** Its box: the text size and a little for the font's own ascent and descent. */
    fun height(w: Float, h: Float): Float = size(w, h) * 1.2f
    const val PAD = 14f
    /** Its distance from the left edge, as in res/layout/home_widget.xml. */
    const val START = 13f
}

object Faces {
    private const val PAD = 14f
    private const val T1 = Pen.T1
    private const val T2 = Pen.T2
    private const val T3 = Pen.T3
    /** A row in the extra blocks a larger widget shows. */
    private const val ROW = 22f
    private const val LABEL = 17f

    /** Draws a face: `draw` gets the canvas and the widget in drawing units ([FaceEnv.inner]). */
    fun bitmap(density: Float, env: FaceEnv, draw: (Canvas, FaceEnv) -> Unit): Bitmap {
        // A widget's pictures share a memory limit: very large widgets are drawn at a lower density.
        val scale = minOf(density, kotlin.math.sqrt(MAX_PIXELS / (env.w * env.h)))
        val b = Bitmap.createBitmap(maxOf(1, (env.w * scale).toInt()), maxOf(1, (env.h * scale).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val s = env.look.scale
        c.scale(scale * s, scale * s)
        draw(c, env.inner())
        return b
    }

    private const val MAX_PIXELS = 1_600_000f

    // ─── Race weekend ───────────────────────────────────────────────────────────────

    /** `ticking`: the countdown is under a day and Android draws it in the space left at the bottom. */
    fun weekend(c: Canvas, pen: Pen, env: FaceEnv, view: WeekendView, schedule: List<ScheduledSession>, snapshot: HomeSnapshot, followed: String, ticking: Boolean) {
        val (w, h) = env.w to env.h
        pen.panel(c, env)
        val wide = w >= 300f
        val compact = w < 200f || h < 120f
        val short = h < 120f
        val leftW = if (wide) w * 0.56f else w
        val textW = leftW - 2 * PAD
        val titleSize = if (compact) 15f else 18f
        val title = env.look.title
        fun round(s: ScheduledSession) = schedule.filter { it.round == s.round && it.start != null }
        run weekendBody@{ when (view) {
            is WeekendView.Upcoming -> {
                val s = view.session
                var y = PAD
                if (short) {
                    // One line, then the countdown: all a widget one row high has room for.
                    pen.text(c, "${HomeLogic.short(s.name)} · ${s.meeting}", PAD, y, 13f, T1, pen.semibold, max = textW)
                } else {
                    if (title) {
                        pen.label(c, "Round ${s.round} · ${s.location}", PAD, y, textW)
                        y += LABEL
                    }
                    pen.text(c, s.meeting, PAD, y, titleSize, T1, pen.semibold, max = textW)
                    y += titleSize * 1.35f + 1f
                    pen.text(c, "${s.name} · ${HomeLogic.dayTime(s.start!!, env.h24)}", PAD, y, if (compact) 12f else 13f, T2, max = textW)
                    y += (if (compact) 12f else 13f) * 1.35f
                }
                val countdownTop = h - Tick.PAD - Tick.height(w, h)
                if (!ticking) {
                    val days = HomeLogic.daysLeft(env.now, s.start!!)
                    val big = Tick.size(w, h)
                    val n = days.toString()
                    val nw = pen.text(c, n, PAD - 1f, countdownTop - big * 0.07f, big, T1, pen.light)
                    val unit = if (days == 1L) "day" else "days"
                    val uw = pen.label(c, unit, PAD + nw + 6f, countdownTop + big * 0.62f, colour = T2)
                    if (short) pen.label(c, HomeLogic.dayTime(s.start!!, env.h24), PAD + nw + 6f + uw + 10f, countdownTop + big * 0.62f, textW - nw - uw - 16f)
                } else if (!short) {
                    pen.label(c, "Starts in", PAD, countdownTop - 15f)
                }
                if (!short) {
                    // A taller widget fills the space above the countdown: the weekend, then the championship.
                    val bottom = countdownTop - (if (ticking) 15f else 0f) - 10f
                    more(c, pen, env, if (wide) emptyList() else round(s), snapshot, followed, PAD, y + 12f, textW, bottom)
                }
            }
            is WeekendView.Live -> {
                val s = view.session
                var y = PAD
                if (title) {
                    pen.label(c, "On now · Round ${s.round}", PAD, y, textW)
                    y += LABEL
                }
                pen.text(c, s.name, PAD, y, titleSize, T1, pen.semibold, max = textW)
                y += titleSize * 1.35f + 1f
                pen.text(c, s.meeting, PAD, y, if (compact) 12f else 13f, T2, max = textW)
                y += (if (compact) 12f else 13f) * 1.35f
                if (!short) {
                    val footer = h - PAD - 13f * 1.35f
                    pen.text(c, "Tap to follow live", PAD, footer, 12f, T2, max = textW)
                    more(c, pen, env, if (wide) emptyList() else round(s), snapshot, followed, PAD, y + 12f, textW, footer - 8f, podium = false)
                }
            }
            is WeekendView.Result -> {
                val p = view.podium
                var y = PAD
                if (title || short) {
                    pen.label(c, if (short) "${p.meeting} · result" else "Race result · Round ${p.round}", PAD, y, textW)
                    y += LABEL
                }
                if (!short) {
                    pen.text(c, p.meeting, PAD, y, if (compact) 14f else 16f, T1, pen.semibold, max = textW)
                    y += (if (compact) 14f else 16f) * 1.35f + 4f
                }
                val footer = view.next?.let { "Next: ${HomeLogic.short(it.name)} · ${HomeLogic.dayTime(it.start!!, env.h24)}" }
                val bottom = if (footer != null && h >= 130f) h - PAD - 12f * 1.35f - 4f else h - PAD
                if (short) {
                    // The top three on one line, as Deskglow's minimal layout.
                    val top = y + 6f
                    val each = textW / 3f
                    p.places.forEachIndexed { i, place ->
                        var x = PAD + i * each + if (i > 0) 8f else 0f
                        if (place.code == followed) pen.rect(c, x - 7f, top + 3f, x - 5f, top + 18f, Pen.BRAND)
                        x += pen.text(c, place.position.toString(), x, top, 14f, T2) + 5f
                        if (env.look.bars) {
                            pen.rect(c, x, top + 4f, x + 3f, top + 17f, HomeLogic.teamColour(place.team))
                            x += 8f
                        }
                        pen.text(c, place.code, x, top, 14f, T1, pen.semibold, max = PAD + (i + 1) * each - x)
                    }
                    return@weekendBody
                }
                val row = ((bottom - y) / 3f).coerceIn(16f, 26f)
                for (place in p.places) {
                    if (y + row > bottom + 1f) break
                    placeRow(c, pen, env, PAD, y, textW, row, place.position, place.code, if (compact || w < 240f) null else place.name, place.team, place.code == followed)
                    y += row
                }
                if (footer != null && h >= 130f) pen.text(c, footer, PAD, h - PAD - 12f * 1.35f, 12f, T2, max = textW)
                // Under the podium of a tall widget: where the followed driver stands now.
                more(c, pen, env, emptyList(), snapshot, followed, PAD, y + 12f, textW, bottom - 4f, podium = false)
            }
            WeekendView.Empty -> empty(c, pen, env, "No schedule yet", "Open RaceWall once to load the season.")
        } }
        if (wide && view !is WeekendView.Empty) {
            val x = leftW
            pen.rect(c, x, PAD, x + 1f, h - PAD, Pen.BORDER)
            val sessions = when (view) {
                is WeekendView.Result -> HomeLogic.weekend(schedule, env.now)
                is WeekendView.Live -> round(view.session)
                is WeekendView.Upcoming -> round(view.session)
                WeekendView.Empty -> emptyList()
            }
            sessionList(c, pen, env, sessions, x + PAD, PAD, w - x - 2 * PAD, h - 2 * PAD, compactRows = true)
        }
    }

    /**
     * What a larger widget adds in the space from `top` to `bottom`, each block only when it fits
     * whole: the weekend's sessions, the followed driver's championship place, and the last race's
     * top three. Returns where the next thing could go.
     */
    private fun more(
        c: Canvas, pen: Pen, env: FaceEnv, sessions: List<ScheduledSession>, snapshot: HomeSnapshot, driver: String,
        x: Float, top: Float, width: Float, bottom: Float, podium: Boolean = true,
    ): Float {
        var y = top
        if (sessions.size >= 2 && y + LABEL + sessions.size * 17f <= bottom) {
            pen.label(c, "This weekend", x, y, width)
            y += LABEL
            val height = minOf(sessions.size * ROW, bottom - y)
            sessionList(c, pen, env, sessions, x, y, width, height, compactRows = width < 200f)
            y += height + 10f
        }
        val d = snapshot.drivers.firstOrNull { driver.isNotEmpty() && it.code == driver }
        if (d != null && y + LABEL + ROW <= bottom) {
            val after = when {
                snapshot.round <= 0 -> ""
                width < 240f -> " · R${snapshot.round}"
                else -> " · after round ${snapshot.round}"
            }
            pen.label(c, "Championship$after", x, y, width)
            y += LABEL
            placeRow(c, pen, env, x, y, width, ROW, d.position, d.code, if (width >= 200f) d.name else null, d.team, true, value = HomeLogic.valueText(d.points, d.points, false) + " pts")
            y += ROW + 10f
        }
        val p = snapshot.podium
        if (podium && p != null && p.places.isNotEmpty() && y + LABEL + p.places.size * ROW <= bottom) {
            pen.label(c, "Last race · ${p.meeting}", x, y, width)
            y += LABEL
            for (place in p.places) {
                placeRow(c, pen, env, x, y, width, ROW, place.position, place.code, if (width >= 200f) place.name else null, place.team, place.code == driver)
                y += ROW
            }
            y += 10f
        }
        return y
    }

    private fun placeRow(
        c: Canvas, pen: Pen, env: FaceEnv, x: Float, top: Float, width: Float, row: Float, position: Int, code: String, name: String?, team: String,
        followed: Boolean, colour: Long? = null, value: String? = null,
    ) {
        val size = if (row >= 22f) 14f else 12.5f
        val ty = top + (row - size * 1.35f) / 2f
        if (followed) pen.rect(c, x - 8f, top + 3f, x - 6f, top + row - 3f, Pen.BRAND)
        pen.text(c, position.toString(), x + 14f, ty, size, T2, right = true)
        if (env.look.bars) pen.rect(c, x + 20f, top + row / 2f - size * 0.45f, x + 23f, top + row / 2f + size * 0.45f, colour ?: HomeLogic.teamColour(team))
        val vw = value?.let { pen.text(c, it, x + width, ty, size, T1, right = true) + 8f } ?: 0f
        val cw = pen.text(c, code, x + 30f, ty, size, T1, pen.semibold, max = width - 30f - vw)
        if (name != null) pen.text(c, name, x + 30f + cw + 8f, ty, size, if (followed) T1 else T2, max = width - 38f - cw - vw)
    }

    // ─── Schedule ───────────────────────────────────────────────────────────────────

    fun schedule(c: Canvas, pen: Pen, env: FaceEnv, schedule: List<ScheduledSession>) {
        val (w, h) = env.w to env.h
        pen.panel(c, env)
        val sessions = HomeLogic.weekend(schedule, env.now)
        if (sessions.isEmpty()) return empty(c, pen, env, "No schedule yet", "Open RaceWall once to load the season.")
        val first = sessions.first()
        val textW = w - 2 * PAD
        val days = w >= 300f && h < 200f
        var y = PAD
        if (env.look.title) {
            pen.label(c, "Round ${first.round} · ${first.location}", PAD, y, textW)
            y += LABEL
        }
        val nameSize = if (days) 14f else 15f
        pen.text(c, first.meeting, PAD, y, nameSize, T1, pen.semibold, max = textW)
        y += nameSize * 1.35f + 6f
        // A very tall widget also shows the round after this one, at the bottom.
        val after = schedule.filter { it.start != null && it.round > first.round }.let { rest -> rest.filter { it.round == rest.firstOrNull()?.round } }
        val nextH = LABEL + 15f * 1.35f + 13f * 1.35f
        val showNext = !days && after.isNotEmpty() && h >= 330f
        val bottom = if (showNext) h - PAD - nextH - 12f else h - PAD
        when {
            days -> dayColumns(c, pen, env, sessions, PAD, y, textW, h - PAD - y)
            w >= 220f && h >= 200f -> {
                timeline(c, pen, sessions, env.now, PAD, y + 2f, textW)
                y += 40f
                sessionList(c, pen, env, sessions, PAD, y, textW, minOf(bottom - y, sessions.size * 26f), compactRows = w < 260f)
            }
            else -> sessionList(c, pen, env, sessions, PAD, y, textW, bottom - y, compactRows = w < 200f)
        }
        if (showNext) {
            val n = after.first()
            var ny = h - PAD - nextH
            pen.rect(c, PAD, ny - 7f, w - PAD, ny - 6f, Pen.BORDER)
            pen.label(c, "Next · Round ${n.round} · ${n.location}", PAD, ny, textW)
            ny += LABEL
            pen.text(c, n.meeting, PAD, ny, 15f, T1, pen.semibold, max = textW)
            ny += 15f * 1.35f
            pen.text(c, "${n.name} · ${HomeLogic.dayTime(n.start!!, env.h24)}", PAD, ny, 13f, T2, max = textW)
        }
    }

    /** The weekend's sessions, one a row: day, name and local time; the next one marked, finished ones dim. */
    private fun sessionList(c: Canvas, pen: Pen, env: FaceEnv, sessions: List<ScheduledSession>, x: Float, top: Float, width: Float, height: Float, compactRows: Boolean) {
        if (sessions.isEmpty() || height <= 0f) return
        val size = if (compactRows) 12f else 13f
        val row = (height / sessions.size).coerceIn(15f, 24f)
        val fit = (height / row).toInt().coerceAtLeast(1)
        // When they do not all fit, the finished ones go first.
        val firstShown = (sessions.indexOfFirst { !HomeLogic.isPast(it, env.now) }.takeIf { it >= 0 } ?: 0).coerceAtMost(maxOf(0, sessions.size - fit))
        val shown = sessions.drop(if (sessions.size > fit) firstShown else 0).take(fit)
        val mark = sessions.firstOrNull { !HomeLogic.isPast(it, env.now) }
        val dayW = pen.width("Wed", size, pen.regular) + 8f
        var y = top
        for (s in shown) {
            val past = HomeLogic.isPast(s, env.now)
            val on = HomeLogic.isOn(s, env.now)
            val ty = y + (row - size * 1.35f) / 2f
            if (s == mark) pen.rect(c, x - 8f, y + 3f, x - 6f, y + row - 3f, Pen.BRAND)
            pen.text(c, HomeLogic.day(s.start!!), x, ty, size, T3)
            val right = if (on) "On now" else HomeLogic.time(s.start!!, env.h24)
            val rw = pen.text(c, right, x + width, ty, size, if (past) T3 else if (on) T1 else T2, right = true)
            val name = if (compactRows) HomeLogic.short(s.name) else s.name
            pen.text(c, name, x + dayW, ty, size, if (past) T3 else T1, if (s == mark) pen.semibold else pen.regular, max = width - dayW - rw - 8f)
            y += row
        }
    }

    /** One column a day, for a wide and short widget. */
    private fun dayColumns(c: Canvas, pen: Pen, env: FaceEnv, sessions: List<ScheduledSession>, x: Float, top: Float, width: Float, height: Float) {
        val byDay = sessions.groupBy { HomeLogic.day(it.start!!) }.entries.toList()
        val colW = width / byDay.size
        val mark = sessions.firstOrNull { !HomeLogic.isPast(it, env.now) }
        byDay.forEachIndexed { i, (day, list) ->
            val cx = x + i * colW
            if (i > 0) pen.rect(c, cx - 6f, top, cx - 5f, top + height, Pen.BORDER)
            pen.label(c, day, cx, top, colW - 10f)
            var y = top + LABEL
            val each = ((height - LABEL) / list.size).coerceAtMost(34f)
            for (s in list) {
                if (y + 28f > top + height + 1f) break
                val past = HomeLogic.isPast(s, env.now)
                val on = HomeLogic.isOn(s, env.now)
                if (s == mark) pen.rect(c, cx - 4f, y + 2f, cx - 2f, y + 28f, Pen.BRAND)
                val ix = cx + if (s == mark) 2f else 0f
                pen.text(c, HomeLogic.short(s.name), ix, y, 13f, if (past) T3 else T1, if (s == mark) pen.semibold else pen.regular, max = colW - 12f)
                pen.text(c, if (on) "On now" else HomeLogic.time(s.start!!, env.h24), ix, y + 14f, 12f, if (past) T3 else if (on) T1 else T2, max = colW - 12f)
                y += each
            }
        }
    }

    /** The weekend as a line: a dot a session, filled up to now. */
    private fun timeline(c: Canvas, pen: Pen, sessions: List<ScheduledSession>, now: Long, x: Float, top: Float, width: Float) {
        val n = sessions.size
        if (n < 2) return
        val step = (width - 20f) / (n - 1)
        val cy = top + 6f
        fun at(i: Float) = x + 10f + i * step
        pen.rect(c, at(0f), cy - 0.5f, at((n - 1).toFloat()), cy + 0.5f, Pen.BORDER2)
        val progress = HomeLogic.timelineProgress(sessions.map { it.start!! }, now)
        if (progress > 0f) pen.rect(c, at(0f), cy - 0.5f, at(progress.coerceAtMost((n - 1).toFloat())), cy + 0.5f, T2)
        sessions.forEachIndexed { i, s ->
            val past = HomeLogic.isPast(s, now)
            val on = HomeLogic.isOn(s, now)
            when {
                on -> pen.circle(c, at(i.toFloat()), cy, 4f, T1)
                past -> pen.circle(c, at(i.toFloat()), cy, 3f, T2)
                else -> {
                    pen.circle(c, at(i.toFloat()), cy, 3f, Pen.BG)
                    pen.circle(c, at(i.toFloat()), cy, 3f, T3, stroke = true)
                }
            }
            val label = when (s.name) {
                "Sprint Qualifying" -> "SQ"
                "Qualifying" -> "Q"
                "Sprint" -> "S"
                "Race" -> "R"
                else -> HomeLogic.short(s.name)
            }
            val lw = pen.width(label, 10.5f)
            pen.text(c, label, at(i.toFloat()) - lw / 2f, cy + 8f, 10.5f, if (on) T1 else T3)
        }
    }

    // ─── Standings ──────────────────────────────────────────────────────────────────

    data class StandingRow(val position: Int, val code: String, val name: String, val team: String, val points: Double, val followed: Boolean)

    /** The points column's width when a wide widget shows points and the gap side by side. */
    private const val VALUE_COL = 54f

    fun standings(c: Canvas, pen: Pen, env: FaceEnv, snapshot: HomeSnapshot, w0: HomeWidgetSettings, driver: String, team: String) {
        val (w, h) = env.w to env.h
        pen.panel(c, env)
        val teams = w0.table == HomeWidgetSettings.CONSTRUCTORS
        val rows = if (teams) snapshot.teams.map { StandingRow(it.position, "", it.name, it.name, it.points, team.isNotEmpty() && it.name == team) }
        else snapshot.drivers.map { StandingRow(it.position, it.code, it.name, it.team, it.points, driver.isNotEmpty() && it.code == driver) }
        if (rows.isEmpty()) return empty(c, pen, env, "No standings yet", "They load with the season schedule.")
        val gap = w0.value == HomeWidgetSettings.GAP
        val leader = rows.first().points
        val textW = w - 2 * PAD
        val top = if (env.look.title) PAD + 20f else PAD
        val height = h - PAD - top
        val row = if (h < 160f) 19f else 22f
        val perCol = (height / row).toInt().coerceAtLeast(1)
        val two = w >= 340f && perCol < 11 && rows.size > perCol
        val cols = if (two) 2 else 1
        val colW = (textW - (cols - 1) * 30f) / cols
        // A wide single column shows points and the gap to the leader side by side.
        val both = cols == 1 && colW >= 380f
        if (env.look.title) {
            val after = if (snapshot.round <= 0) "" else if (w < 200f) " · R${snapshot.round}" else " · Round ${snapshot.round}"
            pen.label(c, (if (teams) "Constructors" else "Drivers") + after, PAD, PAD, textW - if (both) 40f + VALUE_COL else 40f)
            if (both) {
                pen.label(c, "Pts", w - PAD - VALUE_COL, PAD, right = true)
                pen.label(c, "Gap", w - PAD, PAD, right = true)
            } else {
                pen.label(c, if (gap) "Gap" else "Pts", w - PAD, PAD, right = true)
            }
        }
        val followedIndex = rows.indexOfFirst { it.followed }
        val shown = HomeLogic.visibleRows(rows, perCol * cols, followedIndex)
        shown.forEachIndexed { i, r ->
            val col = i / perCol
            val x = PAD + col * (colW + 30f)
            val y = top + (i % perCol) * row
            val jumped = r.followed && i == shown.lastIndex && followedIndex >= perCol * cols - 1 && followedIndex != i
            if (jumped) pen.rect(c, x, y, x + colW, y + 1f, Pen.BORDER2)
            val size = if (row >= 22f) 13.5f else 12.5f
            val ty = y + (row - size * 1.35f) / 2f
            if (r.followed) pen.rect(c, x - 8f, y + 3f, x - 6f, y + row - 3f, Pen.BRAND)
            pen.text(c, r.position.toString(), x + 16f, ty, size, T2, right = true)
            if (env.look.bars) pen.rect(c, x + 22f, y + row / 2f - size * 0.42f, x + 25f, y + row / 2f + size * 0.42f, HomeLogic.teamColour(r.team))
            val face = if (r.followed) pen.semibold else pen.regular
            val vw = if (both) {
                pen.text(c, HomeLogic.valueText(r.points, leader, true), x + colW, ty, size, T2, right = true)
                VALUE_COL + pen.text(c, HomeLogic.valueText(r.points, leader, false), x + colW - VALUE_COL, ty, size, T1, face, right = true)
            } else {
                pen.text(c, HomeLogic.valueText(r.points, leader, gap), x + colW, ty, size, T1, face, right = true)
            }
            val nameX = x + 32f
            val room = colW - 32f - vw - 8f
            if (teams) pen.text(c, r.name, nameX, ty, size, T1, face, max = room)
            else if (colW >= 300f) {
                // Wide: the full name, and the team after it.
                val nw = pen.text(c, r.name, nameX, ty, size, T1, face, max = room)
                if (room - nw - 10f > 40f) pen.text(c, r.team, nameX + nw + 10f, ty, size - 1f, T3, max = room - nw - 10f)
            } else {
                val full = pen.width(r.name, size) + 4f + pen.width(r.code, size, pen.semibold) <= room && colW >= 200f
                if (full) {
                    pen.text(c, r.name, nameX, ty, size, T1, face, max = room)
                } else {
                    val cw = pen.text(c, r.code, nameX, ty, size, T1, pen.semibold)
                    if (colW >= 150f) pen.text(c, r.team, nameX + cw + 8f, ty, size - 1f, T3, max = room - cw - 8f)
                }
            }
        }
    }

    // ─── Live ───────────────────────────────────────────────────────────────────────

    fun live(c: Canvas, pen: Pen, env: FaceEnv, live: HomeLive?, schedule: List<ScheduledSession>, snapshot: HomeSnapshot, driver: String) {
        val (w, h) = env.w to env.h
        pen.panel(c, env)
        val title = env.look.title
        if (live == null) {
            val on = HomeLogic.onNow(schedule, env.now)
            val next = on ?: HomeLogic.next(schedule, env.now)
            if (next == null) return empty(c, pen, env, "Live timing", "Open RaceWall once to load the season.")
            // A wide widget puts the weekend in a column on the right, as the race weekend widget.
            val wide = w >= 340f && h >= 150f
            val leftW = if (wide) w * 0.56f else w
            val textW = leftW - 2 * PAD
            var y = PAD
            if (title) {
                pen.label(c, if (on != null) "Live timing · on now" else "Live timing · next", PAD, y, textW)
                y += LABEL
            }
            pen.text(c, next.name, PAD, y, 17f, T1, pen.semibold, max = textW)
            y += 17f * 1.35f + 1f
            pen.text(c, next.meeting, PAD, y, 13f, T2, max = textW)
            y += 13f * 1.35f
            if (on == null && h >= 110f) {
                pen.text(c, HomeLogic.dayTime(next.start!!, env.h24), PAD, y, 13f, T2, max = textW)
                y += 13f * 1.35f
            }
            val footer = h - PAD - 12f * 1.35f
            pen.text(c, if (on != null) "Tap to follow live" else "Tap to follow live when it starts", PAD, footer, 12f, if (on != null) T1 else T3, max = textW)
            val sessions = schedule.filter { it.round == next.round && it.start != null }
            if (wide) {
                pen.rect(c, leftW, PAD, leftW + 1f, h - PAD, Pen.BORDER)
                sessionList(c, pen, env, sessions, leftW + PAD, PAD, w - leftW - 2 * PAD, h - 2 * PAD, compactRows = true)
            }
            more(c, pen, env, if (wide) emptyList() else sessions, snapshot, driver, PAD, y + 12f, textW, footer - 8f)
            return
        }
        val textW = w - 2 * PAD
        val status = HomeLogic.status(live)
        val lap = live.lap?.takeIf { live.race && it > 0 }?.let { l -> if (live.totalLaps != null && live.totalLaps > 0) "Lap $l / ${live.totalLaps}" else "Lap $l" }
        var y = PAD
        if (title) {
            val lw = lap?.let { pen.text(c, it, w - PAD, y - 1f, 12.5f, T1, right = true) } ?: 0f
            pen.label(c, if (w < 200f) live.session else "${live.meeting} · ${live.session}", PAD, y, textW - lw - 8f)
            y += LABEL
            pen.text(c, status.label, PAD, y, 13f, status.colour, pen.semibold, max = textW)
        } else {
            // Without the heading, the lap goes on the status line.
            val lw = lap?.let { pen.text(c, it, w - PAD, y, 12.5f, T1, right = true) } ?: 0f
            pen.text(c, status.label, PAD, y, 13f, status.colour, pen.semibold, max = textW - lw - 8f)
        }
        y += 13f * 1.35f + 4f
        val news = live.news?.takeIf { h >= 150f }
        val bottom = h - PAD - if (news != null) 12f * 1.35f + 6f else 0f
        val row = if (h < 160f) 19f else 21f
        val perCol = ((bottom - y) / row).toInt().coerceAtLeast(0)
        // A wide and short widget runs the order on in a second column.
        val cols = if (w >= 340f && perCol < 8) 2 else 1
        val colW = (textW - (cols - 1) * 30f) / cols
        val fit = perCol * cols
        val followedIndex = live.rows.indexOfFirst { driver.isNotEmpty() && it.code == driver }
        val shown = HomeLogic.visibleRows(live.rows, fit, followedIndex)
        val top = y
        for ((i, r) in shown.withIndex()) {
            val x = PAD + (i / maxOf(1, perCol)) * (colW + 30f)
            val ry = top + (i % maxOf(1, perCol)) * row
            val followed = driver.isNotEmpty() && r.code == driver
            if (followed && i == shown.lastIndex && followedIndex >= fit) pen.rect(c, x, ry, x + colW, ry + 1f, Pen.BORDER2)
            val size = 13f
            val ty = ry + (row - size * 1.35f) / 2f
            if (followed) pen.rect(c, x - 8f, ry + 3f, x - 6f, ry + row - 3f, Pen.BRAND)
            pen.text(c, r.position.toString(), x + 16f, ty, size, T2, right = true)
            if (env.look.bars) pen.rect(c, x + 22f, ry + row / 2f - 5.5f, x + 25f, ry + row / 2f + 5.5f, HomeLogic.colourOf(r.colour, r.team))
            val cw = pen.text(c, r.code, x + 32f, ty, size, T1, pen.semibold)
            val gap = when {
                r.out -> "Out"
                r.inPit -> "Pit"
                else -> r.gap
            }
            val gw = pen.text(c, gap, x + colW, ty, size, if (r.out || r.inPit) T3 else T2, right = true)
            if (colW >= 200f) pen.text(c, r.team, x + 32f + cw + 8f, ty, size - 1f, T3, max = colW - 40f - cw - gw - 8f)
        }
        if (news != null) pen.text(c, news, PAD, h - PAD - 12f * 1.35f, 12f, T2, max = textW)
    }

    private fun empty(c: Canvas, pen: Pen, env: FaceEnv, title: String, note: String) {
        pen.text(c, title, PAD, PAD, 15f, T1, pen.semibold, max = env.w - 2 * PAD)
        pen.text(c, note, PAD, PAD + 22f, 12f, T3, max = env.w - 2 * PAD)
    }
}
