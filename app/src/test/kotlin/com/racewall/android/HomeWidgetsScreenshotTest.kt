package com.racewall.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.racewall.android.home.FaceEnv
import com.racewall.android.home.Faces
import com.racewall.android.home.HomeDriver
import com.racewall.android.home.HomeFetch
import com.racewall.android.home.HomeKind
import com.racewall.android.home.HomeLiveFeed
import com.racewall.android.home.HomeLogic
import com.racewall.android.home.HomeSettings
import com.racewall.android.home.HomeSnapshot
import com.racewall.android.home.HomeTeam
import com.racewall.android.home.HomeWidgetSettings
import com.racewall.android.home.HomeWidgets
import com.racewall.android.home.Pen
import com.racewall.bus.FeedJson
import com.racewall.jolpica.Jolpica
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.TimeZone

/**
 * Every home-screen widget at the sizes a phone and a tablet give it, with the 2025 British GP
 * weekend: Jolpica's schedule, standings after round 12 and race result, and the live race from
 * the archive fixture. Written to app/screenshots/home-*.png. The widget picker's preview images
 * are made here too, from the same data, into res/drawable-nodpi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
class HomeWidgetsScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun res(f: String) = HomeWidgetsScreenshotTest::class.java.getResource("/jolpica/$f")!!.readText()
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private val schedule by lazy { Jolpica.parseSchedule(FeedJson.parse(res("2025-schedule.json"))) }
    private val snapshot by lazy {
        val d = Jolpica.parseDriverStandings(FeedJson.parse(res("2025-r12-driverStandings.json")))!!
        val t = Jolpica.parseTeamStandings(FeedJson.parse(res("2025-r12-constructorStandings.json")))!!
        HomeSnapshot(
            season = d.season,
            round = d.round,
            drivers = d.rows.map { HomeDriver(it.position, it.code, it.name, it.team, it.points) },
            teams = t.rows.map { HomeTeam(it.position, it.name, it.points) },
            podium = HomeFetch.parsePodium(FeedJson.parse(res("2025-r12-results.json")), at("2025-07-06T16:00:00Z")),
        )
    }
    /** Lap 34 of the race, from the archive fixture. */
    private val live by lazy { synchronized(BritishGp.core) { HomeLiveFeed.of(BritishGp.core.bus, BritishGp.CARS) } }

    @Before
    fun zone() = TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))

    private fun save(name: String, b: Bitmap, dir: String = "screenshots") {
        val f = File(dir, "$name.png")
        f.parentFile?.mkdirs()
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val density get() = context.resources.displayMetrics.density

    /** The face as a launcher shows it: the RemoteViews applied, laid out at the size, drawn. */
    private fun shoot(name: String, kind: HomeKind, w: Float, h: Float, now: Long, settings: HomeSettings = HomeSettings(driver = "HAM", team = "Ferrari"), snap: HomeSnapshot = snapshot): Bitmap {
        val env = FaceEnv(w, h, now, h24 = true)
        val rv = HomeWidgets.face(context, kind, 1, Pen(context), env, snap, schedule, settings)
        val parent = FrameLayout(context)
        val view = rv.apply(context, parent)
        val wp = (w * density).toInt()
        val hp = (h * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(wp, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hp, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, wp, hp)
        // A wallpaper-grey surround, so the widget's own edge shows.
        val out = Bitmap.createBitmap(wp + 48, hp + 48, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF3A4048.toInt())
        c.translate(24f, 24f)
        view.draw(c)
        save("home-$name", out)
        return out
    }

    @Test
    fun weekend() {
        val before = at("2025-07-02T12:00:00Z")
        shoot("weekend-2x2-days", HomeKind.WEEKEND, 170f, 170f, before)
        shoot("weekend-4x2-days", HomeKind.WEEKEND, 360f, 170f, before)
        val ticking = at("2025-07-05T09:47:21Z")
        shoot("weekend-2x1-ticking", HomeKind.WEEKEND, 170f, 90f, ticking)
        shoot("weekend-4x1-days", HomeKind.WEEKEND, 360f, 90f, before)
        shoot("weekend-4x1-live", HomeKind.WEEKEND, 360f, 90f, at("2025-07-06T14:30:00Z"))
        shoot("weekend-4x1-result", HomeKind.WEEKEND, 360f, 90f, at("2025-07-06T18:00:00Z"), HomeSettings(driver = "PIA"))
        shoot("weekend-4x2-ticking", HomeKind.WEEKEND, 360f, 170f, ticking)
        shoot("weekend-4x2-live", HomeKind.WEEKEND, 360f, 170f, at("2025-07-06T14:30:00Z"))
        shoot("weekend-2x2-result", HomeKind.WEEKEND, 170f, 170f, at("2025-07-06T18:00:00Z"), HomeSettings(driver = "PIA"))
        shoot("weekend-4x2-result", HomeKind.WEEKEND, 360f, 170f, at("2025-07-06T18:00:00Z"), HomeSettings(driver = "PIA"))
        shoot("weekend-empty", HomeKind.WEEKEND, 250f, 110f, before, snap = HomeSnapshot())
    }

    @Test
    fun schedule() {
        val sat = at("2025-07-05T12:00:00Z")
        shoot("schedule-2x2", HomeKind.SCHEDULE, 170f, 170f, sat)
        shoot("schedule-4x2-days", HomeKind.SCHEDULE, 360f, 170f, sat)
        shoot("schedule-4x3-timeline", HomeKind.SCHEDULE, 360f, 260f, sat)
        shoot("schedule-3x2", HomeKind.SCHEDULE, 250f, 200f, at("2025-07-06T14:30:00Z"))
    }

    @Test
    fun standings() {
        val now = at("2025-07-08T12:00:00Z")
        shoot("standings-2x2", HomeKind.STANDINGS, 170f, 170f, now)
        shoot("standings-3x3", HomeKind.STANDINGS, 250f, 260f, now)
        shoot("standings-4x2-two-columns", HomeKind.STANDINGS, 360f, 170f, now)
        val gap = HomeSettings(team = "Ferrari", driver = "HAM", widgets = mapOf("1" to HomeWidgetSettings(HomeKind.STANDINGS, table = HomeWidgetSettings.CONSTRUCTORS, value = HomeWidgetSettings.GAP)))
        shoot("standings-3x3-constructors-gap", HomeKind.STANDINGS, 250f, 260f, now, gap)
    }

    @Test
    fun live() {
        val race = at("2025-07-06T14:30:00Z")
        val following = snapshot.copy(live = live)
        shoot("live-3x3", HomeKind.LIVE, 250f, 260f, race, HomeSettings(driver = "HAM"), following)
        shoot("live-4x2", HomeKind.LIVE, 360f, 170f, race, HomeSettings(driver = "HAM"), following)
        shoot("live-2x2", HomeKind.LIVE, 170f, 170f, race, HomeSettings(driver = "ALB"), following)
        shoot("live-not-following", HomeKind.LIVE, 250f, 170f, at("2025-07-05T12:00:00Z"))
        shoot("live-on-now", HomeKind.LIVE, 250f, 170f, race)
    }

    /** Larger widgets show more: the weekend, the followed driver's place, the last podium, the next round. */
    @Test
    fun larger() {
        val before = at("2025-07-02T12:00:00Z")
        val ham = HomeSettings(driver = "HAM", team = "Ferrari")
        shoot("larger-live-next-3x4", HomeKind.LIVE, 250f, 360f, before, ham)
        shoot("larger-live-next-5x3", HomeKind.LIVE, 420f, 260f, before, ham)
        shoot("larger-live-next-4x2", HomeKind.LIVE, 360f, 170f, before, ham)
        shoot("larger-live-race-3x3", HomeKind.LIVE, 220f, 260f, at("2025-07-06T14:30:00Z"), ham, snapshot.copy(live = live))
        shoot("larger-weekend-2x4", HomeKind.WEEKEND, 200f, 380f, before, ham)
        shoot("larger-weekend-4x4", HomeKind.WEEKEND, 360f, 380f, before, ham)
        shoot("larger-weekend-2x4-ticking", HomeKind.WEEKEND, 200f, 380f, at("2025-07-05T09:47:21Z"), ham)
        shoot("larger-weekend-2x4-result", HomeKind.WEEKEND, 200f, 380f, at("2025-07-06T18:00:00Z"), ham)
        shoot("larger-schedule-3x4", HomeKind.SCHEDULE, 250f, 380f, at("2025-07-05T12:00:00Z"))
        shoot("larger-standings-5x4", HomeKind.STANDINGS, 440f, 380f, at("2025-07-08T12:00:00Z"), ham)
    }

    /** Each look option: see-through, Dim, no border, square corners, small and large text, no heading or team colours. */
    @Test
    fun looks() {
        val now = at("2025-07-05T09:47:21Z")
        fun with(own: HomeWidgetSettings) = HomeSettings(driver = "HAM", team = "Ferrari", widgets = mapOf("1" to own))
        for (o in listOf(75, 50, 0)) shoot("look-opacity-$o", HomeKind.WEEKEND, 250f, 260f, now, with(HomeWidgetSettings(HomeKind.WEEKEND, opacity = o)))
        shoot("look-dim-no-border-square", HomeKind.STANDINGS, 250f, 260f, now, with(HomeWidgetSettings(HomeKind.STANDINGS, tone = HomeWidgetSettings.DIM, border = false, corners = HomeWidgetSettings.SQUARE)))
        shoot("look-text-small", HomeKind.WEEKEND, 250f, 260f, now, with(HomeWidgetSettings(HomeKind.WEEKEND, text = HomeWidgetSettings.SMALL)))
        shoot("look-text-large", HomeKind.WEEKEND, 250f, 260f, now, with(HomeWidgetSettings(HomeKind.WEEKEND, text = HomeWidgetSettings.LARGE)))
        shoot("look-text-large-standings", HomeKind.STANDINGS, 250f, 260f, now, with(HomeWidgetSettings(HomeKind.STANDINGS, text = HomeWidgetSettings.LARGE)))
        shoot("look-no-heading-no-bars", HomeKind.LIVE, 250f, 260f, at("2025-07-06T14:30:00Z"), with(HomeWidgetSettings(HomeKind.LIVE, title = false, teamBars = false)), snapshot.copy(live = live))
    }

    /** The widget picker's images: each widget at its usual size, drawn as above, at xxhdpi. */
    @Test
    fun previews() {
        val sizes = mapOf(
            HomeKind.WEEKEND to Triple(320f, 150f, at("2025-07-02T12:00:00Z")),
            HomeKind.SCHEDULE to Triple(250f, 200f, at("2025-07-05T12:00:00Z")),
            HomeKind.STANDINGS to Triple(250f, 260f, at("2025-07-08T12:00:00Z")),
            HomeKind.LIVE to Triple(250f, 260f, at("2025-07-06T14:30:00Z")),
        )
        for ((kind, s) in sizes) {
            val (w, h, now) = s
            val env = FaceEnv(w, h, now, h24 = true)
            val pen = Pen(context)
            val snap = if (kind == HomeKind.LIVE) snapshot.copy(live = live) else snapshot
            val own = HomeWidgetSettings(kind)
            val b = Faces.bitmap(density, env) { c, e ->
                when (kind) {
                    HomeKind.WEEKEND -> Faces.weekend(c, pen, e, HomeLogic.weekendView(schedule, snap.podium, now), schedule, snap, "", false)
                    HomeKind.SCHEDULE -> Faces.schedule(c, pen, e, schedule)
                    HomeKind.STANDINGS -> Faces.standings(c, pen, e, snap, own, "", "")
                    HomeKind.LIVE -> Faces.live(c, pen, e, snap.live, schedule, snap, "")
                }
            }
            save("home_widget_${kind.name.lowercase()}_preview", b, "src/main/res/drawable-nodpi")
        }
    }
}
