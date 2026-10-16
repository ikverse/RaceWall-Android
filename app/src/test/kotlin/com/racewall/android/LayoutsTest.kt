package com.racewall.android

import com.racewall.android.state.COLS
import com.racewall.android.state.DEFAULT_LAYOUTS
import com.racewall.android.state.Grid
import com.racewall.android.state.JsonStore
import com.racewall.android.state.Layout
import com.racewall.android.state.LayoutOps
import com.racewall.android.state.Layouts
import com.racewall.android.state.Panel
import com.racewall.android.state.ROWS
import com.racewall.android.state.Rect
import com.racewall.android.state.Settings
import com.racewall.android.ui.Command
import com.racewall.android.ui.addWidget
import com.racewall.android.ui.burnIn
import com.racewall.android.ui.matching
import com.racewall.topics.SessionState
import com.racewall.topics.SessionStatus
import com.racewall.topics.TrackFlag
import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from the desktop's app/ui/src/ui.test.ts, with the window's other rules. */
class GridTest {
    private fun panel(id: String, x: Int, y: Int, w: Int, h: Int) = Panel(id, "racewall.timing", x, y, w, h)

    @Test
    fun `rejects overlaps and anything outside the grid`() {
        val panels = listOf(panel("a", 0, 0, 10, 10))
        assertFalse(Grid.fits(Rect(9, 9, 5, 5), panels))
        assertTrue(Grid.fits(Rect(10, 0, 5, 5), panels))
        assertFalse(Grid.fits(Rect(COLS - 2, 0, 5, 5), panels))
        assertTrue(Grid.fits(Rect(0, 0, 10, 10), panels, "a"))
    }

    @Test
    fun `finds the first free spot, falling back to the minimum size`() {
        val full = listOf(panel("a", 0, 0, 40, ROWS))
        assertNull(Grid.findSpot(full, listOf(12 to 10)))
        assertEquals(Rect(40, 0, 8, 6), Grid.findSpot(full, listOf(12 to 10, 8 to 6)))
    }

    @Test
    fun `keeps moves inside the grid and resizes above the minimum`() {
        val start = Rect(40, 20, 8, 10)
        assertEquals(Rect(40, 20, 8, 10), Grid.moved(start, 10, 10, false, 4 to 4))
        assertEquals(Rect(0, 0, 8, 10), Grid.moved(start, -50, -50, false, 4 to 4))
        assertEquals(Rect(40, 20, 4, 4), Grid.moved(start, -20, -20, true, 4 to 4))
    }

    @Test
    fun `formats session time and the lap`() {
        assertEquals("01:04:09", Format.elapsed(3_849_003.0))
        assertEquals("Lap 21 / 52", Format.lapText(SessionStatus(SessionState.STARTED, TrackFlag.GREEN, 21, 52)))
        assertEquals("", Format.lapText(SessionStatus(SessionState.STARTED, TrackFlag.GREEN)))
    }
}

class LayoutOpsTest {
    private fun start() = Layouts("Race", listOf("Race", "Data", "Watch", "Between sessions").map { Layout(it, emptyList()) })
    private fun names(l: Layouts) = l.list.map { it.name }

    @Test
    fun `adds empty layouts with the next free number and makes them active`() {
        var l = start()
        val (a, n1) = LayoutOps.newLayout(l)
        assertEquals("Layout 1", n1)
        val (b, n2) = LayoutOps.newLayout(a)
        assertEquals("Layout 2", n2)
        assertEquals("Layout 2", b.active)
        assertEquals(emptyList(), b.list.last().panels)
        l = LayoutOps.rename(b, "Layout 1", "Quali").first
        assertEquals("Layout 1", LayoutOps.newLayout(l).second)
    }

    @Test
    fun `renames layouts the user made, never built-in ones, and keeps names unique`() {
        var l = LayoutOps.newLayout(start()).first
        val (r, problem) = LayoutOps.rename(l, "Layout 1", "  Quali  ")
        assertEquals("", problem)
        l = r
        assertTrue("Quali" in names(l))
        assertEquals("Quali", l.active)
        assertNotEquals("", LayoutOps.rename(l, "Quali", "   ").second)
        assertNotEquals("", LayoutOps.rename(l, "Quali", "Data").second)
        assertNotEquals("", LayoutOps.rename(l, "Race", "Sunday").second)
        assertNotEquals("", LayoutOps.rename(l, "Watch", "TV").second)
        assertEquals(listOf("Race", "Data", "Watch", "Between sessions", "Quali"), names(l))
    }

    @Test
    fun `moves layouts to a new position`() {
        var l = LayoutOps.newLayout(start()).first
        l = LayoutOps.move(l, 4, 0)
        assertEquals(listOf("Layout 1", "Race", "Data", "Watch", "Between sessions"), names(l))
        l = LayoutOps.move(l, 0, 2)
        assertEquals(listOf("Race", "Data", "Layout 1", "Watch", "Between sessions"), names(l))
        l = LayoutOps.move(l, 0, 9)
        assertEquals(listOf("Race", "Data", "Layout 1", "Watch", "Between sessions"), names(l))
    }

    @Test
    fun `deletes layouts the user made and picks the one that takes its place`() {
        var l = LayoutOps.newLayout(LayoutOps.newLayout(start()).first).first
        l = LayoutOps.move(l, 4, 1)
        assertEquals(l, LayoutOps.delete(l, "Race"))
        assertEquals(l, LayoutOps.delete(l, "Watch"))
        l = LayoutOps.delete(l, "Layout 1")
        assertEquals("Layout 2", l.active)
        l = LayoutOps.delete(l, "Layout 2")
        assertEquals("Between sessions", l.active)
        assertEquals(listOf("Race", "Data", "Watch", "Between sessions"), names(l))
    }

    @Test
    fun `adds a widget in the first free spot, and says when there is no room`() {
        val empty = Layouts("Mine", listOf(Layout("Mine", emptyList())))
        val (added, problem) = addWidget(empty, "racewall.weather")
        assertEquals("", problem)
        assertEquals(Panel("weather-1", "racewall.weather", 0, 0, 9, 12), added.current().panels.single())
        assertEquals("weather-2", addWidget(added, "racewall.weather").first.current().panels.last().id)
        val full = Layouts("Race", DEFAULT_LAYOUTS)
        assertEquals("No room. Shrink or remove a panel first.", addWidget(full, "racewall.timing").second)
        assertEquals("That widget is not available.", addWidget(empty, "racewall.nope").second)
    }

    @Test
    fun `puts back built-in layouts and names old widgets by their full id`() {
        val old = Layouts("Mine", listOf(Layout("Mine", listOf(Panel("t", "timing", 0, 0, 10, 10)))))
        val m = LayoutOps.migrate(old)
        assertEquals(listOf("Mine", "Race", "Data", "Watch", "Between sessions"), names(m))
        assertEquals("racewall.timing", m.list[0].panels[0].widget)
    }

    @Test
    fun `resets a built-in layout to how it ships`() {
        val changed = Layouts("Race", DEFAULT_LAYOUTS).withPanels { emptyList() }
        assertEquals(DEFAULT_LAYOUTS[0], LayoutOps.reset(changed, "Race").list[0])
    }

    @Test
    fun `saves settings and layouts in the desktop's own shapes`() {
        val dir = Files.createTempDirectory("racewall-store").toFile()
        val store = JsonStore(dir.resolve("layouts.json"))
        val layouts = Layouts("Race", DEFAULT_LAYOUTS)
        store.write(layouts)
        val text = dir.resolve("layouts.json").readText()
        assertTrue("\"active\": \"Race\"" in text)
        assertTrue("\"widget\": \"racewall.f1tv\"" in text && "\"channel\": \"main\"" in text)
        assertEquals(layouts, store.read(Layouts("x", emptyList())))
        val s = JsonStore(dir.resolve("settings.json"))
        s.save("""{"theme":"mica","pixelShift":false,"viewing":"cable","delays":{"cable":12000},"unknownDesktopField":1}""")
        val read = s.read(Settings())
        assertFalse(read.pixelShift)
        assertEquals(12_000L, read.delay())
        dir.deleteRecursively()
    }
}

class WindowRulesTest {
    @Test
    fun `the command bar matches every word typed and lists the first nine`() {
        val commands = (1..20).map { Command("Focus D$it", "Driver") {} } + Command("Play or pause", "Space") {}
        assertEquals(listOf("Play or pause"), matching(commands, "pla spa").map { it.label })
        assertEquals(9, matching(commands, "focus").size)
        assertEquals(emptyList(), matching(commands, "nothing like it").map { it.label })
    }

    @Test
    fun `chrome fades after 3 seconds and the stage dims after 5 minutes paused, never while busy`() {
        val s = Settings()
        assertEquals(false to false, burnIn(2_900, busy = false, playing = false, s))
        assertEquals(true to false, burnIn(3_100, busy = false, playing = false, s))
        assertEquals(true to true, burnIn(5 * 60_000 + 1, busy = false, playing = false, s))
        assertEquals(true to false, burnIn(5 * 60_000 + 1, busy = false, playing = true, s))
        assertEquals(false to false, burnIn(10 * 60_000, busy = true, playing = false, s))
        assertEquals(true to false, burnIn(10 * 60_000, busy = false, playing = false, s.copy(idleDim = false)))
    }
}
