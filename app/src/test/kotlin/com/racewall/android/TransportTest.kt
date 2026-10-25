package com.racewall.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.racewall.android.ui.RaceWallTheme
import com.racewall.android.ui.Transport
import com.racewall.android.ui.TransportActions
import com.racewall.archive.ArchiveMeeting
import com.racewall.topics.SessionState
import com.racewall.topics.SessionStatus
import com.racewall.topics.TrackFlag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
class TransportTest {
    @get:Rule
    val compose = createComposeRule()

    private class Fake : TransportActions {
        var toggles = 0
        var seeks = mutableListOf<Double>()
        var speed: Double? = null
        var replayed: String? = null
        override fun toggle() {
            toggles++
        }
        override fun seek(t: Double) {
            seeks.add(t)
        }
        override fun setSpeed(speed: Double) {
            this.speed = speed
        }
        override suspend fun listSeason(year: Int): List<ArchiveMeeting> = BritishGp.meetings
        override fun replay(path: String) {
            replayed = path
        }
    }

    private val frame = BritishGp.frameAt(BritishGp.LAP_4)

    private fun show(f: Frame = frame, ui: UiState = UiState(), actions: Fake = Fake()): Fake {
        compose.setContent {
            var picker by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            RaceWallTheme { Transport(f, ui, actions, picker, { picker = it }) }
        }
        return actions
    }

    @Test
    fun `names the session, the lap, the UTC time and the source`() {
        show()
        compose.onNodeWithText("British Grand Prix · Race 2025").assertExists()
        compose.onNodeWithText("Lap 4 / 52 · 14:11:40 UTC").assertExists()
        compose.onNodeWithText("Sources: F1 archive", substring = true).assertExists()
    }

    @Test
    fun `play and pause`() {
        val a = show()
        compose.onNodeWithContentDescription("Play").performClick()
        assertEquals(1, a.toggles)
    }

    @Test
    fun `shows pause while playing`() {
        show(frame.copy(clock = frame.clock.copy(playing = true)))
        compose.onNodeWithContentDescription("Pause").assertExists()
    }

    @Test
    fun `the speed list sets the speed`() {
        val a = show()
        compose.onNodeWithTag("Speed").performClick()
        compose.onNodeWithText("4x").performClick()
        assertEquals(4.0, a.speed)
    }

    @Test
    fun `tapping the bar seeks, in whole seconds`() {
        val a = show()
        compose.onNodeWithTag("Replay position").performTouchInput { click(center) }
        assertTrue(a.seeks.isNotEmpty())
        assertEquals(0.0, a.seeks.last() % 1000.0)
    }

    @Test
    fun `shows the track flag in its colour`() {
        show(frame.copy(topics = frame.topics + (com.racewall.topics.Topic.SESSION_STATUS to SessionStatus(SessionState.STARTED, TrackFlag.SC, 20, 52))))
        compose.onNodeWithText("Safety car").assertExists()
    }

    @Test
    fun `shows what is loading, or the error`() {
        show(ui = UiState(loading = "Loading session…"))
        compose.onNodeWithText("Loading session…").assertExists()
    }

    @Test
    fun `shows an error`() {
        show(ui = UiState(error = "F1 archive: 500 for x"))
        compose.onNodeWithText("F1 archive: 500 for x").assertExists()
    }

    @Test
    fun `the picker lists the season and replays the chosen session`() {
        val a = show()
        compose.onNodeWithContentDescription("Pick a past session").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("British Grand Prix")).fetchSemanticsNodes().isNotEmpty() }
        // The event's last session is chosen first, as on the desktop.
        compose.onNodeWithTag("Session").assertTextContains("Race")
        compose.onNodeWithTag("Session").performClick()
        compose.onNodeWithText("Qualifying").performClick()
        compose.onNodeWithContentDescription("Replay").performClick()
        compose.waitUntil(5_000) { a.replayed != null }
        assertEquals("2025/2025-07-06_British_Grand_Prix/2025-07-05_Qualifying/", a.replayed)
        // The picker has closed; the load is the app's, so closing it cannot stop the load.
        compose.onNodeWithContentDescription("Replay").assertDoesNotExist()
    }
}
