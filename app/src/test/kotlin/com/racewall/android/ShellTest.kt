package com.racewall.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import com.racewall.android.state.Layouts
import com.racewall.android.state.Settings
import com.racewall.android.state.defaultLayouts
import com.racewall.android.ui.AppActions
import com.racewall.android.ui.HOLD_MS
import com.racewall.android.ui.RaceWallApp
import com.racewall.android.ui.RaceWallTheme
import com.racewall.android.ui.Screen
import com.racewall.archive.ArchiveMeeting
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The window around the widgets: layouts, edit mode, panel settings, the command bar and Settings. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1600dp-h900dp-land-mdpi")
class ShellTest {
    @get:Rule
    val compose = createComposeRule()

    private var layouts by mutableStateOf(defaultLayouts())
    private var settings by mutableStateOf(Settings())
    private var focus by mutableStateOf<String?>(null)
    private var toggles = 0
    private var seeks = mutableListOf<Double>()
    private var saves = 0
    private val previews = mutableListOf<Layouts>()

    private val actions = object : AppActions {
        override suspend fun signedIn(token: String) {}
        override fun signOut() {}
        override fun toggle() {
            toggles++
        }
        override fun seek(t: Double) {}
        override fun seekBy(ms: Double) {
            seeks.add(ms)
        }
        override fun setSpeed(speed: Double) {}
        override suspend fun listSeason(year: Int): List<ArchiveMeeting> = BritishGp.meetings
        override fun replay(path: String) {}
        override fun setLayouts(layouts: Layouts) {
            saves++
            this@ShellTest.layouts = layouts
        }
        override fun previewLayouts(layouts: Layouts) {
            previews.add(layouts)
            this@ShellTest.layouts = layouts
        }
        override fun setSettings(settings: Settings) {
            this@ShellTest.settings = settings
        }
        override fun focus(number: String?) {
            focus = number
        }
    }

    private val frame = BritishGp.frameAt(BritishGp.CARS)

    private fun show() {
        compose.setContent {
            RaceWallTheme { RaceWallApp(Screen(frame, UiState(focusDriver = focus), Mode.REPLAY, settings, layouts), actions) }
        }
    }

    @Test
    fun `shows the Race layout's five panels and switches layouts from the tabs`() {
        show()
        for (id in listOf("timing-1", "track-map-1", "race-control-1", "tyre-strategy-1", "weather-1")) compose.onNodeWithTag("panel-$id").assertExists()
        compose.onNodeWithTag("tab-Data").performClick()
        assertEquals("Data", layouts.active)
        compose.onNodeWithTag("panel-telemetry-1").assertExists()
    }

    @Test
    fun `each panel header shows its widget's text`() {
        show()
        compose.onNodeWithTag("meta-timing-1").assertTextEquals("Lap 5 / 52")
        compose.onNodeWithTag("meta-weather-1").assertTextEquals("At the track")
        compose.onNodeWithTag("meta-track-map-1").assertTextEquals("Silverstone · Yellow flag")
    }

    @Test
    fun `edit mode adds a widget, removes a panel and adds a layout`() {
        show()
        compose.onNodeWithContentDescription("Edit layout").performClick()
        compose.onNodeWithContentDescription("Remove Weather").performClick()
        assertFalse(layouts.current().panels.any { it.id == "weather-1" })
        compose.onNodeWithContentDescription("Add widget").performClick()
        compose.onNodeWithContentDescription("Battles").performClick()
        assertTrue(layouts.current().panels.any { it.widget == "racewall.battles-widget" })
        compose.onNodeWithContentDescription("New layout").performClick()
        assertEquals("Layout 1", layouts.active)
    }

    @Test
    fun `a layout the user made is renamed by a double tap, and deleted after asking`() {
        layouts = com.racewall.android.state.LayoutOps.newLayout(defaultLayouts()).first
        show()
        compose.onNodeWithContentDescription("Edit layout").performClick()
        compose.onNodeWithTag("tab-Layout 1").performTouchInput { click(); advanceEventTime(100); click() }
        compose.onNodeWithTag("rename").performTextInput("Quali")
        compose.onNodeWithTag("rename").performImeAction()
        assertTrue(layouts.list.any { it.name.contains("Quali") })
        val name = layouts.list.last().name
        compose.onNodeWithContentDescription("Delete layout $name").performClick()
        compose.onNodeWithContentDescription("Delete").performClick()
        assertEquals(4, layouts.list.size)
    }

    @Test
    fun `after a layout is deleted, tapping the tab now in its place opens that tab's layout`() {
        layouts = com.racewall.android.state.LayoutOps.newLayout(com.racewall.android.state.LayoutOps.newLayout(defaultLayouts()).first).first.copy(active = "Race")
        show()
        // Touching "Layout 1" first ties the fifth place in the strip to it.
        compose.onNodeWithTag("tab-Layout 1").performClick()
        assertEquals("Layout 1", layouts.active)
        compose.onNodeWithContentDescription("Edit layout").performClick()
        compose.onNodeWithContentDescription("Delete layout Layout 1").performClick()
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithTag("tab-Race").performClick()
        compose.onNodeWithTag("tab-Layout 2").performClick()
        assertEquals("Layout 2", layouts.active)
    }

    @Test
    fun `dragging a tab in edit mode moves the layout`() {
        show()
        compose.onNodeWithContentDescription("Edit layout").performClick()
        compose.onNodeWithTag("tab-Race").performTouchInput {
            down(center)
            repeat(20) { moveBy(Offset(10f, 0f)) }
            up()
        }
        compose.waitForIdle()
        assertNotEquals("Race", layouts.list.first().name, layouts.list.map { it.name }.toString())
    }

    @Test
    fun `holding a header still for three seconds, then dragging, enters edit mode and moves the panel`() {
        layouts = defaultLayouts().withPanels { list -> list.filter { it.id != "track-map-1" } }
        show()
        val before = layouts.current().panels.first { it.id == "race-control-1" }
        compose.onNodeWithTag("panel-race-control-1").performTouchInput {
            down(Offset(60f, 15f))
            advanceEventTime(HOLD_MS + 100)
            moveBy(Offset(-200f, 0f))
            moveBy(Offset(-10f, 0f))
            up()
        }
        compose.waitForIdle()
        val after = layouts.current().panels.first { it.id == "race-control-1" }
        assertNotEquals(before.x, after.x)
        compose.onNodeWithText("Done").assertExists()
    }

    /** The Race layout with only the weather panel, at 10,5 and 15 x 13, with room around it. */
    private fun weatherAlone() = defaultLayouts().withPanels { list ->
        list.filter { it.id == "weather-1" }.map { it.copy(x = 10, y = 5, w = 15, h = 13) }
    }

    /** A drag in many small steps, as a finger moves. */
    private fun dragIn(tag: String, from: Offset, by: Offset, steps: Int = 40) {
        compose.onNodeWithTag(tag).performTouchInput {
            down(from)
            repeat(steps) { moveBy(Offset(by.x / steps, by.y / steps)) }
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun `resizing by the corner grows the panel steadily, by the cells dragged, and saves once`() {
        layouts = weatherAlone()
        show()
        compose.onNodeWithContentDescription("Edit layout").performClick()
        saves = 0
        // On this screen a cell is about 33 x 27; drag about 3 across and 2 down.
        dragIn("resize-weather-1", Offset(16f, 16f), Offset(100f, 55f))
        val after = layouts.current().panels.single()
        assertEquals(15 + 3, after.w)
        assertEquals(13 + 2, after.h)
        // Never smaller on the way: the corner moving with the panel does not pull it back.
        val sizes = previews.map { it.current().panels.single().let { p -> p.w to p.h } }
        assertTrue(sizes.zipWithNext().all { (a, b) -> b.first >= a.first && b.second >= a.second }, "$sizes")
        assertEquals(sizes.distinct(), sizes, "each preview is a new size")
        assertEquals(1, saves)
    }

    @Test
    fun `a drag starting just outside the corner still resizes the panel`() {
        layouts = weatherAlone()
        show()
        compose.onNodeWithContentDescription("Edit layout").performClick()
        // The resize mark's box is 32 x 32 at the corner: 40,40 is 8 beyond it, outside the panel.
        dragIn("resize-weather-1", Offset(40f, 40f), Offset(100f, 55f))
        val after = layouts.current().panels.single()
        assertEquals(15 + 3, after.w)
        assertEquals(13 + 2, after.h)
    }

    @Test
    fun `a neighbour's remove button next to a corner still removes the neighbour`() {
        // Race control sits right below the weather panel, their right edges in line.
        layouts = defaultLayouts().withPanels { list ->
            list.filter { it.id == "weather-1" || it.id == "race-control-1" }.map {
                if (it.id == "weather-1") it.copy(x = 10, y = 5, w = 15, h = 13) else it.copy(x = 10, y = 18, w = 15, h = 8)
            }
        }
        show()
        compose.onNodeWithContentDescription("Edit layout").performClick()
        compose.onNodeWithContentDescription("Remove Race control").performClick()
        assertEquals(listOf("weather-1"), layouts.current().panels.map { it.id })
    }

    @Test
    fun `moving a panel by its header in edit mode follows the finger and saves once`() {
        layouts = weatherAlone()
        show()
        compose.onNodeWithContentDescription("Edit layout").performClick()
        saves = 0
        dragIn("panel-weather-1", Offset(60f, 15f), Offset(-133f, 0f))
        val after = layouts.current().panels.single()
        assertEquals(10 - 4, after.x)
        assertEquals(5, after.y)
        val xs = previews.map { it.current().panels.single().x }
        assertEquals(listOf(9, 8, 7, 6), xs)
        assertEquals(1, saves)
    }

    @Test
    fun `after a panel before it is removed, a header still moves its own panel`() {
        // Race control comes first in the list, the weather panel second.
        layouts = defaultLayouts().withPanels { list ->
            list.filter { it.id == "weather-1" || it.id == "race-control-1" }.map {
                if (it.id == "weather-1") it.copy(x = 10, y = 5, w = 15, h = 13) else it.copy(x = 10, y = 18, w = 15, h = 8)
            }
        }
        show()
        compose.onNodeWithContentDescription("Edit layout").performClick()
        // Touching race control's header first ties the first place in the list to it.
        dragIn("panel-race-control-1", Offset(60f, 15f), Offset(33f, 0f))
        compose.onNodeWithContentDescription("Remove Race control").performClick()
        dragIn("panel-weather-1", Offset(60f, 15f), Offset(-133f, 0f))
        val after = layouts.current().panels.single()
        assertEquals("weather-1", after.id)
        assertEquals(10 - 4, after.x)
        assertEquals(15 to 13, after.w to after.h)
    }

    @Test
    fun `switching layouts leaves each header moving its own panel`() {
        show()
        compose.onNodeWithContentDescription("Edit layout").performClick()
        // Touch every header in the Race layout, then switch to a layout with other panels.
        for (id in listOf("timing-1", "track-map-1", "race-control-1", "tyre-strategy-1", "weather-1")) dragIn("panel-$id", Offset(60f, 15f), Offset(0f, 0f), steps = 1)
        compose.onNodeWithTag("tab-Between sessions").performClick()
        layouts = layouts.withPanels { list -> list.map { if (it.id == "race-control-1") it.copy(y = 4, h = 26) else it } }
        val before = layouts.current().panels.associateBy { it.id }
        dragIn("panel-race-control-1", Offset(60f, 15f), Offset(0f, -60f))
        val after = layouts.current().panels.associateBy { it.id }
        // A cell is about 27 high here: 60 up is two cells.
        assertEquals(2, after.getValue("race-control-1").y)
        assertEquals(26, after.getValue("race-control-1").h)
        for (id in before.keys - "race-control-1") assertEquals(before[id], after[id], id)
    }

    @Test
    fun `a quick drag on a header does not enter edit mode`() {
        show()
        compose.onNodeWithTag("panel-weather-1").performTouchInput {
            down(Offset(60f, 15f))
            moveBy(Offset(-100f, 0f))
            up()
        }
        compose.onNodeWithText("Edit layout").assertExists()
    }

    @Test
    fun `collapsing a panel keeps its place and shows only the header`() {
        show()
        compose.onNodeWithContentDescription("Collapse Weather").performClick()
        assertEquals(true, layouts.current().panels.first { it.id == "weather-1" }.collapsed)
        compose.onNodeWithContentDescription("Expand Weather").performClick()
        assertNull(layouts.current().panels.first { it.id == "weather-1" }.collapsed)
    }

    @Test
    fun `the gear opens the panel's settings, which are saved with the layout and reset`() {
        show()
        compose.onNodeWithContentDescription("Settings for Weather").performClick()
        compose.onNodeWithTag("switch-Air pressure").performClick()
        assertEquals(JsonPrimitive(true), layouts.current().panels.first { it.id == "weather-1" }.settings?.get("pressure"))
        compose.onNodeWithContentDescription("Reset").performClick()
        assertEquals(emptyMap(), layouts.current().panels.first { it.id == "weather-1" }.settings)
    }

    @Test
    fun `the command bar finds a command by a few letters and runs it`() {
        show()
        compose.onNodeWithContentDescription("Command bar").performClick()
        compose.onNodeWithTag("command-input").performTextInput("switch data")
        compose.onNodeWithTag("command-0").performClick()
        assertEquals("Data", layouts.active)
    }

    @Test
    fun `the command bar focuses a driver`() {
        show()
        compose.onNodeWithContentDescription("Command bar").performClick()
        compose.onNodeWithTag("command-input").performTextInput("focus pia")
        compose.onNodeWithTag("command-0").performClick()
        assertEquals("81", focus)
    }

    @Test
    fun `settings switches are saved`() {
        show()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("switch-Pixel shift").performClick()
        assertFalse(settings.pixelShift)
        compose.onNodeWithTag("switch-Dim panels").performClick()
        assertTrue(settings.dimPanels)
        compose.onNodeWithTag("How you watch").performScrollTo().performClick()
        compose.onNodeWithText("Cable TV").performClick()
        assertEquals("cable", settings.viewing)
        compose.onAllNodesWithContentDescription("Close settings").assertCountEquals(1)
    }

    @Test
    fun `tapping a driver in one widget focuses them everywhere`() {
        show()
        compose.onNodeWithTag("row-81").performClick()
        assertEquals("81", focus)
    }
}
