package com.racewall.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.racewall.android.home.HomeDriver
import com.racewall.android.home.HomeFetch
import com.racewall.android.home.HomeKind
import com.racewall.android.home.HomeSettings
import com.racewall.android.home.HomeSnapshot
import com.racewall.android.home.HomeTeam
import com.racewall.android.home.HomeWidgetSettings
import com.racewall.android.home.WidgetSettings
import com.racewall.android.ui.RaceWallTheme
import com.racewall.bus.FeedJson
import com.racewall.jolpica.Jolpica
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.TimeZone

/**
 * The home widget settings page with its preview, on a phone, with the 2025 schedule and the
 * standings after round 12. Written to app/screenshots/home-settings-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
class HomeWidgetSettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun res(f: String) = HomeWidgetSettingsScreenshotTest::class.java.getResource("/jolpica/$f")!!.readText()
    private val schedule by lazy { Jolpica.parseSchedule(FeedJson.parse(res("2025-schedule.json"))) }
    private val snapshot by lazy {
        val d = Jolpica.parseDriverStandings(FeedJson.parse(res("2025-r12-driverStandings.json")))!!
        val t = Jolpica.parseTeamStandings(FeedJson.parse(res("2025-r12-constructorStandings.json")))!!
        HomeSnapshot(
            season = d.season,
            round = d.round,
            drivers = d.rows.map { HomeDriver(it.position, it.code, it.name, it.team, it.points) },
            teams = t.rows.map { HomeTeam(it.position, it.name, it.points) },
            podium = HomeFetch.parsePodium(FeedJson.parse(res("2025-r12-results.json")), Instant.parse("2025-07-06T16:00:00Z").toEpochMilli()),
        )
    }

    private fun page(name: String, kind: HomeKind, size: Pair<Float, Float>, own: HomeWidgetSettings, snap: HomeSnapshot = snapshot) {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        compose.setContent {
            RaceWallTheme {
                var settings by remember { mutableStateOf(HomeSettings(driver = "HAM", team = "Ferrari", clock = "24")) }
                var mine by remember { mutableStateOf(own) }
                WidgetSettings(1, kind, size, snap, schedule, settings, mine, { settings = it }, { mine = it }) {}
            }
        }
        compose.onRoot().captureRoboImage("screenshots/home-settings-$name.png")
    }

    @Test
    fun standings() = page("standings", HomeKind.STANDINGS, 250f to 260f, HomeWidgetSettings(HomeKind.STANDINGS))

    @Test
    fun seeThroughLarge() = page("live-see-through-large", HomeKind.LIVE, 250f to 260f, HomeWidgetSettings(HomeKind.LIVE, opacity = 50, text = HomeWidgetSettings.LARGE))

    /** Just added: nothing downloaded yet, so the lists say they are loading. */
    @Test
    fun loading() = page("loading", HomeKind.WEEKEND, 320f to 150f, HomeWidgetSettings(HomeKind.WEEKEND), HomeSnapshot())
}
