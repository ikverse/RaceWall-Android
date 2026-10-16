package com.racewall.android.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** The layout grid: 48 columns by 30 rows. Panels snap to cells and never overlap. */
const val COLS = 48
const val ROWS = 30

/** A panel's place and its own settings, with the desktop's field names (app/shared/api.ts). */
@Serializable
data class Panel(
    val id: String,
    val widget: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val settings: Map<String, JsonElement>? = null,
    val collapsed: Boolean? = null,
) {
    val rect: Rect get() = Rect(x, y, w, h)
    fun at(r: Rect) = copy(x = r.x, y = r.y, w = r.w, h = r.h)
}

@Serializable
data class Layout(val name: String, val panels: List<Panel>)

@Serializable
data class Layouts(val active: String, val list: List<Layout>) {
    fun current(): Layout = list.firstOrNull { it.name == active } ?: list.first()

    /** This layout set with the active layout's panels changed. */
    fun withPanels(change: (List<Panel>) -> List<Panel>): Layouts {
        val name = current().name
        return copy(list = list.map { if (it.name == name) it.copy(panels = change(it.panels)) else it })
    }
}

data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

/** Grid arithmetic, as the desktop's app/ui/src/grid.ts. */
object Grid {
    fun overlaps(a: Rect, b: Rect): Boolean = a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y

    /** True when `r` lies inside the grid and clear of every panel except `ignoreId`. */
    fun fits(r: Rect, panels: List<Panel>, ignoreId: String? = null): Boolean {
        if (r.x < 0 || r.y < 0 || r.x + r.w > COLS || r.y + r.h > ROWS) return false
        return panels.none { it.id != ignoreId && overlaps(r, it.rect) }
    }

    /** First free spot, top to bottom then left to right, for the preferred size and then the minimum. */
    fun findSpot(panels: List<Panel>, sizes: List<Pair<Int, Int>>): Rect? {
        for ((w, h) in sizes) for (y in 0..ROWS - h) for (x in 0..COLS - w) {
            val r = Rect(x, y, w, h)
            if (fits(r, panels)) return r
        }
        return null
    }

    /** A moved or resized rectangle, kept inside the grid and no smaller than `min`. */
    fun moved(start: Rect, dx: Int, dy: Int, resize: Boolean, min: Pair<Int, Int>): Rect = if (!resize) {
        Rect(minOf(maxOf(0, start.x + dx), COLS - start.w), minOf(maxOf(0, start.y + dy), ROWS - start.h), start.w, start.h)
    } else {
        Rect(start.x, start.y, minOf(maxOf(min.first, start.w + dx), COLS - start.x), minOf(maxOf(min.second, start.h + dy), ROWS - start.y))
    }
}

/** The F1 TV player is part of the app, not a plugin; it is listed with the widgets all the same. */
const val F1TV_WIDGET = "racewall.f1tv"

private fun p(widget: String, x: Int, y: Int, w: Int, h: Int, id: String = "${widget.substringAfterLast('.')}-1", settings: Map<String, JsonElement>? = null) =
    Panel(id, widget, x, y, w, h, settings)

/** The four built-in layouts, as the desktop's DEFAULT_LAYOUTS. */
val DEFAULT_LAYOUTS = listOf(
    Layout(
        "Race",
        listOf(
            p("racewall.timing", 0, 0, 13, 30),
            p("racewall.track-map", 13, 0, 20, 17),
            p("racewall.race-control", 33, 0, 15, 17),
            p("racewall.tyre-strategy", 13, 17, 20, 13),
            p("racewall.weather", 33, 17, 15, 13),
        ),
    ),
    Layout(
        "Data",
        listOf(
            p("racewall.timing", 0, 0, 13, 30),
            p("racewall.telemetry", 13, 0, 18, 16),
            p("racewall.lap-times", 31, 0, 17, 14),
            p("racewall.track-map", 13, 16, 18, 14),
            p("racewall.team-radio", 31, 14, 9, 16),
            p("racewall.weather", 40, 14, 8, 16),
        ),
    ),
    Layout(
        "Watch",
        listOf(
            p(F1TV_WIDGET, 0, 0, 32, 21, "f1tv-1", mapOf("channel" to JsonPrimitive("main"))),
            p("racewall.timing", 32, 0, 16, 30),
            p(F1TV_WIDGET, 0, 21, 11, 9, "f1tv-2", mapOf("channel" to JsonPrimitive("focus"))),
            p("racewall.track-map", 11, 21, 11, 9),
            p("racewall.race-control", 22, 21, 10, 9),
        ),
    ),
    Layout(
        "Between sessions",
        listOf(
            p("racewall.next-session", 0, 0, 18, 11),
            p("racewall.timing", 0, 11, 18, 19),
            p("racewall.standings", 18, 0, 16, 30),
            p("racewall.race-control", 34, 0, 14, 30),
        ),
    ),
)

/** Live mode switches to these by name and start-up puts them back, so they keep their names. */
val BUILT_IN_LAYOUTS = listOf("Race", "Data", "Watch", "Between sessions")

fun isBuiltIn(name: String) = name in BUILT_IN_LAYOUTS

fun defaultLayouts() = Layouts("Race", DEFAULT_LAYOUTS)

/** Making, renaming, ordering and deleting layouts, as the desktop's layouts.ts. Names stay unique. */
object LayoutOps {
    /** Adds an empty layout named "Layout N" at the end and makes it active. */
    fun newLayout(l: Layouts): Pair<Layouts, String> {
        var n = 1
        while (l.list.any { it.name == "Layout $n" }) n++
        val name = "Layout $n"
        return Layouts(name, l.list + Layout(name, emptyList())) to name
    }

    /** Renames a layout the user made. Returns the layouts and a problem, or "" when renamed. */
    fun rename(l: Layouts, from: String, to: String): Pair<Layouts, String> {
        val name = to.trim()
        if (l.list.none { it.name == from }) return l to "That layout no longer exists."
        if (isBuiltIn(from)) return l to "\"$from\" is built in and keeps its name."
        if (name.isEmpty()) return l to "A layout needs a name."
        if (name == from) return l to ""
        if (l.list.any { it.name == name }) return l to "There is already a layout called \"$name\"."
        return Layouts(if (l.active == from) name else l.active, l.list.map { if (it.name == from) it.copy(name = name) else it }) to ""
    }

    /** Moves the layout at one position to another. */
    fun move(l: Layouts, from: Int, to: Int): Layouts {
        val last = l.list.size - 1
        if (from < 0 || from > last || to < 0 || to > last || from == to) return l
        val list = l.list.toMutableList()
        list.add(to, list.removeAt(from))
        return l.copy(list = list)
    }

    /** Deletes a layout the user made. If it was active, the one that takes its place becomes active. */
    fun delete(l: Layouts, name: String): Layouts {
        val i = l.list.indexOfFirst { it.name == name }
        if (i < 0 || isBuiltIn(name)) return l
        val list = l.list.filterIndexed { j, _ -> j != i }
        return Layouts(if (l.active == name) list[minOf(i, list.size - 1)].name else l.active, list)
    }

    /** Puts the built-in layouts back when a saved set lacks one, and names widgets by their full id. */
    fun migrate(l: Layouts): Layouts {
        val list = l.list.map { layout -> layout.copy(panels = layout.panels.map { if ('.' in it.widget) it else it.copy(widget = "racewall.${it.widget}") }) }.toMutableList()
        for (base in DEFAULT_LAYOUTS) if (list.none { it.name == base.name }) list.add(base)
        return l.copy(list = list)
    }

    /** Resets a built-in layout to how it ships. */
    fun reset(l: Layouts, name: String): Layouts {
        val base = DEFAULT_LAYOUTS.firstOrNull { it.name == name } ?: return l
        return l.copy(list = l.list.map { if (it.name == name) base else it })
    }
}
