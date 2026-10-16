package com.racewall.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.racewall.android.state.Layouts
import com.racewall.android.state.Settings
import com.racewall.android.state.defaultLayouts
import com.racewall.android.ui.AppActions
import com.racewall.android.ui.RaceWallApp
import com.racewall.android.ui.RaceWallTheme
import com.racewall.android.ui.Screen
import com.racewall.android.widgets.WIDGETS
import com.racewall.archive.ArchiveMeeting
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The whole window with real race data, as images: on a 2560 x 1600 tablet (1280 x 800 dp), and at
 * the desktop captures' own 1600 x 900 window size so the two can be laid side by side.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private var layouts by mutableStateOf(defaultLayouts())

    private val actions = object : AppActions {
        override suspend fun signedIn(token: String) {}
        override fun signOut() {}
        override fun toggle() {}
        override fun seek(t: Double) {}
        override fun setSpeed(speed: Double) {}
        override suspend fun listSeason(year: Int): List<ArchiveMeeting> = BritishGp.meetings
        override suspend fun replay(path: String) {}
        override fun setLayouts(layouts: Layouts) {
            this@ScreenshotTest.layouts = layouts
        }
        override fun range(topic: String, from: Double, to: Double, filter: List<String>?): Any? = BritishGp.core.range(topic, from, to, filter)
    }

    private fun show(layout: String, t: Double = BritishGp.CARS, mode: Mode = Mode.REPLAY, focus: String? = null, settings: Settings = Settings(fadeChrome = false)) {
        layouts = defaultLayouts().copy(active = layout)
        val frame = BritishGp.frameAt(t)
        compose.setContent {
            RaceWallTheme { RaceWallApp(Screen(frame, UiState(focusDriver = focus), mode, settings, layouts), actions) }
        }
        compose.waitForIdle()
    }

    private fun shoot(name: String) = compose.onRoot().captureRoboImage("screenshots/$name.png")

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun tabletRace() {
        show("Race", focus = "81")
        shoot("tablet-race")
    }

    @Test
    @Config(qualifiers = "w1600dp-h900dp-land-mdpi")
    fun desktopSizeRace() {
        show("Race")
        shoot("desktop-size-race")
    }

    @Test
    @Config(qualifiers = "w1600dp-h900dp-land-mdpi")
    fun desktopSizeData() {
        show("Data", focus = "1")
        shoot("desktop-size-data")
    }

    @Test
    @Config(qualifiers = "w1600dp-h900dp-land-mdpi")
    fun desktopSizeBetweenSessions() {
        show("Between sessions", mode = Mode.IDLE)
        shoot("desktop-size-between-sessions")
    }

    @Test
    @Config(qualifiers = "w1600dp-h900dp-land-mdpi")
    fun desktopSizeEditMode() {
        show("Race")
        compose.onNodeWithContentDescription("Edit layout").performClick()
        shoot("desktop-size-edit-mode")
    }

    @Test
    @Config(qualifiers = "w1600dp-h900dp-land-mdpi")
    fun desktopSizeSettings() {
        show("Race")
        compose.onNodeWithContentDescription("Settings").performClick()
        shoot("desktop-size-settings")
    }

    @Test
    @Config(qualifiers = "w1600dp-h900dp-land-mdpi")
    fun desktopSizeCommandBar() {
        show("Race")
        compose.onNodeWithContentDescription("Command bar").performClick()
        shoot("desktop-size-command-bar")
    }

    @Test
    @Config(qualifiers = "w1600dp-h900dp-land-mdpi")
    fun desktopSizePanelSettings() {
        show("Race")
        for (title in listOf("Timing", "Track", "Race control", "Tyre strategy", "Weather")) {
            // The gear and its popover share the label, as on the desktop: the gear is the first.
            compose.onAllNodesWithContentDescription("Settings for $title")[0].performClick()
            compose.waitForIdle()
            captureScreenRoboImage("screenshots/desktop-size-pop-$title.png")
            androidx.test.espresso.Espresso.pressBack()
            compose.waitForIdle()
        }
    }

    /** The track map with a whole race of car data, flat and 3D, beside the desktop's Singapore captures. */
    @Test
    @Config(qualifiers = "w1600dp-h900dp-land-mdpi")
    fun desktopSizeSingaporeTrack() {
        org.junit.Assume.assumeTrue("the desktop's cached Singapore race", Singapore.available)
        val frame = Singapore.frame()
        var view by mutableStateOf("flat")
        compose.setContent {
            val l = defaultLayouts().withPanels { list -> list.map { if (it.id == "track-map-1") it.copy(settings = mapOf("view" to kotlinx.serialization.json.JsonPrimitive(view), "sectors" to kotlinx.serialization.json.JsonPrimitive(true))) else it } }
            RaceWallTheme { RaceWallApp(Screen(frame, UiState(focusDriver = "1"), Mode.REPLAY, Settings(fadeChrome = false), l), actions) }
        }
        compose.waitForIdle()
        shoot("desktop-size-singapore-race")
        compose.onNodeWithTag("panel-track-map-1").captureRoboImage("screenshots/desktop-size-singapore-track-flat.png")
        view = "3d"
        compose.waitForIdle()
        compose.onNodeWithTag("panel-track-map-1").captureRoboImage("screenshots/desktop-size-singapore-track-3d.png")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-port-xhdpi")
    fun tabletUpright() {
        show("Race")
        shoot("tablet-upright-race")
    }

    @Suppress("unused")
    private val all = WIDGETS.size
}
