package com.racewall.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.racewall.android.ui.RaceWallApp
import com.racewall.android.ui.RaceWallTheme
import com.racewall.android.ui.TimingSettings
import com.racewall.android.ui.AppActions
import com.racewall.archive.ArchiveMeeting
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The whole window with real race data, as images: on a 2560 x 1600 tablet (1280 x 800 dp), and
 * at the desktop capture's own 1600 x 900 window size so the two can be laid side by side.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val none = object : AppActions {
        override suspend fun signedIn(token: String) {}
        override fun signOut() {}
        override fun toggle() {}
        override fun seek(t: Double) {}
        override fun setSpeed(speed: Double) {}
        override suspend fun listSeason(year: Int): List<ArchiveMeeting> = emptyList()
        override suspend fun replay(path: String) {}
    }

    private fun shoot(name: String, t: Double, mode: Mode, focus: String? = null) {
        val frame = BritishGp.frameAt(t)
        compose.setContent {
            RaceWallTheme { RaceWallApp(frame, UiState(focusDriver = focus), mode, TimingSettings(), {}, none) }
        }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun tabletLap4() = shoot("tablet-lap4", BritishGp.LAP_4, Mode.REPLAY, focus = "81")

    @Test
    @Config(qualifiers = "w1600dp-h900dp-land-mdpi")
    fun desktopSizeLap4() = shoot("desktop-size-lap4", BritishGp.LAP_4, Mode.REPLAY)
}
