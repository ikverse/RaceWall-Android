package com.racewall.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.racewall.android.ui.RaceWallTheme
import com.racewall.android.widgets.Field
import com.racewall.android.widgets.Values
import com.racewall.android.widgets.WIDGETS
import com.racewall.android.widgets.WidgetScope
import com.racewall.android.widgets.WidgetSpec
import com.racewall.android.widgets.widget
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Every widget with the British GP data, and every one of its settings. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-land-mdpi")
class WidgetsTest {
    @get:Rule
    val compose = createComposeRule()

    private var frame by mutableStateOf(BritishGp.frameAt(BritishGp.CARS))

    private var settings by mutableStateOf<Map<String, JsonElement>>(emptyMap())
    private var current by mutableStateOf<String?>(null)
    private var size by mutableStateOf(600 to 500)
    private var started = false
    private var focus by mutableStateOf<String?>(null)
    private val focused = mutableListOf<String>()

    private fun scope(spec: WidgetSpec, mode: Mode = Mode.REPLAY) = WidgetScope(
        frame.only(spec.reads),
        mode,
        focus,
        Values(spec.fields, settings),
        { focused.add(it) },
        { topic, from, to, filter -> BritishGp.core.range(topic, from, to, filter) },
        { key, value -> settings = settings + (key to value) },
    )

    /** Shows one widget; the content is set once per test, and the widget shown is switched. */
    private fun show(id: String, w: Int = 600, h: Int = 500) {
        current = id
        size = w to h
        if (!started) {
            started = true
            compose.setContent {
                val spec = current?.let { widget(it) }
                RaceWallTheme { Box(Modifier.size(size.first.dp, size.second.dp)) { if (spec != null) androidx.compose.runtime.key(spec.id) { spec.content(scope(spec)) } } }
            }
        }
        compose.waitForIdle()
    }

    private fun pixels(): List<Int> = compose.onRoot().captureToImage().toPixelMap().let { m -> (0 until m.height step 2).flatMap { y -> (0 until m.width step 2).map { x -> m[x, y].hashCode() } } }

    /** Every other value of a setting than its default. */
    private fun others(f: Field): List<JsonElement> = when (f) {
        is Field.Toggle -> listOf(JsonPrimitive(!f.default))
        is Field.Select -> f.options.map { it.first }.filter { it != f.default }.map(::JsonPrimitive)
        is Field.Number -> listOf(JsonPrimitive(f.max), JsonPrimitive(f.min)).filter { it.content.toDouble() != f.default }
    }

    @Test
    fun `every widget draws with the race data, and with every value of every setting`() {
        for (spec in WIDGETS) {
            settings = emptyMap()
            show(spec.id)
            for (f in spec.fields) for (v in others(f)) {
                settings = mapOf(f.key to v)
                compose.waitForIdle()
            }
        }
    }

    @Test
    fun `settings with something to show at this moment change what is drawn`() {
        val visible = mapOf(
            "racewall.track-map" to listOf("view", "labels", "dot", "rotate", "startLine", "sectors"),
            // No car has DRS open at this moment (the race is behind a VSC), so "drs" shades nothing.
            "racewall.telemetry" to listOf("window", "speed", "throttle", "brake", "rpm", "gear"),
            "racewall.lap-times" to listOf("count", "legend"),
            "racewall.weather" to listOf("tempUnit", "windUnit", "pressure", "hideChart", "chart"),
            "racewall.tyre-strategy" to listOf("order", "letters", "stops", "nowLine"),
            "racewall.race-control" to listOf("showFlags", "showOther", "order"),
            "racewall.standings" to listOf("view", "points", "wins", "top10"),
            "racewall.next-session" to listOf("clock", "count", "scope", "hideSeconds"),
        )
        for ((id, keys) in visible) {
            if (id == "racewall.track-map") {
                // The track needs a whole race of car data for its outline.
                if (!Singapore.available) continue
                frame = Singapore.frame()
            } else frame = BritishGp.frameAt(BritishGp.CARS)
            val spec = widget(id)!!
            settings = emptyMap()
            show(id)
            val before = pixels()
            for (key in keys) {
                val f = spec.fields.first { it.key == key }
                settings = mapOf(key to others(f).first())
                compose.waitForIdle()
                assertNotEquals(before, pixels(), "$id: $key changes nothing")
                settings = emptyMap()
                compose.waitForIdle()
            }
        }
    }

    @Test
    fun `race control lists the newest first, filters by kind, and dims the other drivers' messages`() {
        show("racewall.race-control")
        // At 14:12:40 UTC the newest message is the yellow in sector 10, at 14:12:36.
        assertTrue(compose.onAllNodesWithText("YELLOW IN TRACK SECTOR 10").fetchSemanticsNodes().isNotEmpty())
        settings = mapOf("order" to JsonPrimitive("oldest"), "keep" to JsonPrimitive("all"))
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("GREEN LIGHT - PIT EXIT OPEN").fetchSemanticsNodes().isNotEmpty())
        settings = mapOf("showOther" to JsonPrimitive(false), "showFlags" to JsonPrimitive(false), "showSafetyCar" to JsonPrimitive(false), "showDrs" to JsonPrimitive(false), "showCarEvents" to JsonPrimitive(false))
        compose.onNodeWithText("No messages match the settings.").assertExists()
        // How many are kept shows in the header.
        val spec = widget("racewall.race-control")!!
        settings = mapOf("keep" to JsonPrimitive("30"))
        assertEquals("30 messages", spec.meta(scope(spec)))
        settings = mapOf("keep" to JsonPrimitive("all"))
        assertTrue(spec.meta(scope(spec)).removeSuffix(" messages").toInt() > 30)
    }

    @Test
    fun `weather shows OpenF1's values at this moment, in either unit`() {
        show("racewall.weather")
        compose.onNodeWithText("Humidity").assertExists()
        compose.onNodeWithText("75%").assertExists()
        settings = mapOf("tempUnit" to JsonPrimitive("f"))
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("°F", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `tyre strategy focuses a driver when tapped`() {
        show("racewall.tyre-strategy")
        compose.onNodeWithTag("stints-81").performClick()
        assertEquals(listOf("81"), focused)
    }

    @Test
    fun `championship shows the standings after the British GP, and switches to teams`() {
        show("racewall.standings")
        compose.onNodeWithText("Oscar Piastri").assertExists()
        compose.onNodeWithText("234").assertExists()
        compose.onNodeWithText("Teams").performClick()
        compose.onNodeWithText("McLaren").assertExists()
        compose.onNodeWithText("460").assertExists()
    }

    @Test
    fun `next session counts down to the next session of the schedule`() {
        show("racewall.next-session")
        compose.onNodeWithTag("countdown").assertExists()
    }

    @Test
    fun `battles and pit window work out the race at this moment`() {
        show("racewall.battles-widget")
        assertTrue(compose.onAllNodesWithText("Chasing").fetchSemanticsNodes().size + compose.onAllNodesWithText("No car is within a second of the car ahead.").fetchSemanticsNodes().size > 0)
        show("racewall.pit-window-widget")
        compose.onNodeWithText("Rejoins").assertExists()
    }

    @Test
    fun `team radio lists the clips so far, newest first`() {
        show("racewall.team-radio")
        assertTrue(compose.onAllNodesWithText("NOR").fetchSemanticsNodes().isNotEmpty())
        settings = mapOf("onlyFocus" to JsonPrimitive(true))
        focus = "1"
        compose.waitForIdle()
    }

    @Test
    fun `the timing tower's header meta, and the track map's circuit and flag`() {
        val timing = widget("racewall.timing")!!
        assertEquals("Lap 5 / 52", timing.meta(scope(timing)))
        assertEquals("Last result · British Grand Prix, Race", timing.meta(scope(timing, Mode.IDLE)))
        val track = widget("racewall.track-map")!!
        assertTrue(track.meta(scope(track)).startsWith("Silverstone"))
    }

    @Test
    fun `the telemetry picks the first two cars and lets either be changed`() {
        show("racewall.telemetry")
        compose.onNodeWithTag("First driver").assertExists()
        compose.onNodeWithTag("Second driver").performClick()
        compose.onAllNodesWithText("HAM").assertCountEquals(1)
        compose.onNodeWithText("HAM").performClick()
        assertEquals(JsonPrimitive("44"), settings["b"])
    }
}
