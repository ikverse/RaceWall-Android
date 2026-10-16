package com.racewall.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.racewall.android.ui.RaceWallTheme
import com.racewall.android.ui.TimingSettings
import com.racewall.android.ui.TimingTower
import com.racewall.android.ui.timingMeta
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
class TimingTowerTest {
    @get:Rule
    val compose = createComposeRule()

    private val frame = BritishGp.frameAt(BritishGp.LAP_4)

    private fun show(settings: TimingSettings = TimingSettings(), width: Int = 600, focus: String? = null, onFocus: (String) -> Unit = {}) {
        compose.setContent {
            RaceWallTheme {
                Box(Modifier.width(width.dp).height(700.dp)) { TimingTower(frame, settings, focus, onFocus) }
            }
        }
    }

    @Test
    fun `shows the running order OpenF1 gives for lap 4, with every column`() {
        show()
        for (h in listOf("P", "Driver", "Gap", "Int", "Last", "S1", "S2", "S3", "Tyre")) compose.onNodeWithText(h).assertExists()
        // The first three cars in OpenF1's order at 14:11:40 UTC.
        for ((i, code) in listOf("VER", "PIA", "NOR").withIndex()) {
            compose.onNodeWithTag("row-${frame.positions[i].number}").assertExists()
            compose.onNodeWithText(code).assertExists()
        }
        compose.onNodeWithText("Leader").assertExists()
        compose.onNodeWithText(frame.positions[1].gap).assertExists()
    }

    @Test
    fun `surnames instead of codes`() {
        show(TimingSettings(names = "surname"))
        compose.onNodeWithText("Verstappen").assertExists()
        compose.onAllNodesWithText("VER").assertCountEquals(0)
    }

    @Test
    fun `the interval column can be hidden`() {
        show(TimingSettings(showInterval = false))
        compose.onAllNodesWithText("Int").assertCountEquals(0)
    }

    @Test
    fun `the last lap column can be hidden`() {
        show(TimingSettings(showLast = false))
        compose.onAllNodesWithText("Last").assertCountEquals(0)
    }

    @Test
    fun `the sector times can be hidden`() {
        show(TimingSettings(showSectors = false))
        for (h in listOf("S1", "S2", "S3")) compose.onAllNodesWithText(h).assertCountEquals(0)
    }

    @Test
    fun `the tyre column can be hidden`() {
        show(TimingSettings(showTyre = false))
        compose.onAllNodesWithText("Tyre").assertCountEquals(0)
    }

    @Test
    fun `compact rows are 19 high, normal rows 23`() {
        show(TimingSettings(compact = true))
        compose.onNodeWithTag("row-1").assertHeightIsEqualTo(19.dp)
    }

    @Test
    fun `normal rows are 23 high`() {
        show()
        compose.onNodeWithTag("row-1").assertHeightIsEqualTo(23.dp)
    }

    @Test
    fun `a narrow panel drops sectors, a very narrow one also interval and last lap`() {
        show(width = 420)
        compose.onAllNodesWithText("S1").assertCountEquals(0)
        compose.onNodeWithText("Int").assertExists()
    }

    @Test
    fun `a very narrow panel also drops interval and last lap`() {
        show(width = 320)
        compose.onAllNodesWithText("Int").assertCountEquals(0)
        compose.onAllNodesWithText("Last").assertCountEquals(0)
    }

    @Test
    fun `tapping a driver focuses them`() {
        var focused: String? = null
        show(onFocus = { focused = it })
        compose.onNodeWithTag("row-81").performClick()
        assertEquals("81", focused)
    }

    @Test
    fun `says so when there is no timing yet`() {
        compose.setContent { RaceWallTheme { TimingTower(Frame(), TimingSettings(), null, {}) } }
        compose.onNodeWithText("No timing yet.").assertExists()
    }

    @Test
    fun `the header meta says the lap in a replay and the last result when idle`() {
        assertEquals("Lap 4 / 52", timingMeta(frame, Mode.REPLAY))
        assertEquals("Last result · British Grand Prix, Race", timingMeta(frame, Mode.IDLE))
    }
}
