package com.racewall.android.home

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.racewall.android.live.Alerts
import com.racewall.android.ui.Option
import com.racewall.android.ui.RaceWallTheme
import com.racewall.android.ui.Rw
import com.racewall.android.ui.RwButton
import com.racewall.android.ui.RwSelect
import com.racewall.android.ui.Section
import com.racewall.android.ui.Switch
import com.racewall.topics.ScheduledSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A home-screen widget's settings: opened when the widget is added, and again from the launcher's
 * "Reconfigure" on Android 12 and later. The driver and team are set once for every widget, as in
 * Deskglow, and each widget may follow another. Each widget has its own look. A preview at the top
 * draws the widget at its size on the home screen, with the settings as they are on this page.
 */
class HomeWidgetSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out leaves the widget off the home screen, as Android expects.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        val kind = HomeWidgets.kindOf(this, id)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || kind == null) return finish()
        val first = HomeStore.read(this)
        if (first.drivers.isEmpty()) HomeWidgets.refreshNow(applicationContext)
        val start = HomeSettingsStore.read(this)
        val schedule = Alerts.schedule(this)
        // A launcher gives a widget one size for each orientation: the preview shows the one for now.
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val size = runCatching {
            val sizes = HomeWidgets.sizes(this, AppWidgetManager.getInstance(this), id)
            if (landscape) sizes.maxBy { it.first / it.second } else sizes.minBy { it.first / it.second }
        }.getOrNull() ?: (250f to 170f)
        setContent {
            RaceWallTheme {
                var settings by remember { mutableStateOf(start) }
                var own by remember { mutableStateOf(start.of(id, kind)) }
                var snapshot by remember { mutableStateOf(first) }
                // A widget just added has nothing saved yet: the lists fill in when the first download lands.
                LaunchedEffect(Unit) {
                    var tries = 0
                    while (snapshot.drivers.isEmpty() && tries++ < 120) {
                        delay(1500)
                        snapshot = withContext(Dispatchers.IO) { HomeStore.read(applicationContext) }
                    }
                }
                WidgetSettings(id, kind, size, snapshot, schedule, settings, own, { settings = it }, { own = it }) {
                    HomeSettingsStore.write(this, settings.copy(widgets = settings.widgets + (id.toString() to own)))
                    HomeWidgets.redraw(applicationContext)
                    HomeWidgets.schedule(applicationContext)
                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    finish()
                }
            }
        }
    }
}

/** The page itself; `internal` for its screenshot test. */
@Composable
internal fun WidgetSettings(
    id: Int,
    kind: HomeKind,
    size: Pair<Float, Float>,
    snapshot: HomeSnapshot,
    schedule: List<ScheduledSession>,
    settings: HomeSettings,
    own: HomeWidgetSettings,
    setSettings: (HomeSettings) -> Unit,
    setOwn: (HomeWidgetSettings) -> Unit,
    done: () -> Unit,
) {
    // A choice saved earlier still shows while the names are loading.
    fun keep(list: List<Pair<String, String>>, saved: String) = if (saved.isEmpty() || list.any { it.first == saved }) list else list + (saved to saved)
    val drivers: List<Pair<String, String>> = keep(listOf("" to "None") + snapshot.drivers.map { it.code to it.name }, settings.driver)
    val teams: List<Pair<String, String>> = keep(listOf("" to "None") + snapshot.teams.map { it.name to it.name }, settings.team)
    val loading = if (snapshot.drivers.isEmpty()) "Loading the list…" else ""
    fun nameOf(list: List<Pair<String, String>>, key: String) = list.firstOrNull { it.first == key }?.second ?: "None"
    Column(Modifier.fillMaxSize().background(Rw.bg).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("${kind.label} widget", style = Rw.text(14f, Rw.t1, FontWeight.SemiBold), modifier = Modifier.weight(1f))
            RwButton(done, on = true, label = "Done") { BasicText("Done", style = Rw.text(13f, it)) }
        }
        Preview(id, kind, size, snapshot, schedule, settings.copy(widgets = settings.widgets + (id.toString() to own)))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Section("This widget")
            if (kind == HomeKind.STANDINGS) {
                Option("Table", "") {
                    RwSelect(own.table, listOf(HomeWidgetSettings.DRIVERS to "Drivers", HomeWidgetSettings.CONSTRUCTORS to "Constructors"), { setOwn(own.copy(table = it)) }, "Table", openUpwards = false)
                }
                Option("Show", "Wide widgets show both") {
                    RwSelect(own.value, listOf(HomeWidgetSettings.POINTS to "Points", HomeWidgetSettings.GAP to "Gap to the leader"), { setOwn(own.copy(value = it)) }, "Show", openUpwards = false)
                }
            }
            if (kind != HomeKind.SCHEDULE) {
                val same = "same"
                Option("Driver", "Marked with a line wherever it shows. Larger widgets add their championship place.") {
                    RwSelect(own.driver ?: same, listOf(same to "As every widget (${nameOf(drivers, settings.driver)})") + drivers, { setOwn(own.copy(driver = it.takeIf { v -> v != same })) }, "Driver", openUpwards = false)
                }
                if (kind == HomeKind.STANDINGS) {
                    Option("Team", "In the constructors' table") {
                        RwSelect(own.team ?: same, listOf(same to "As every widget (${nameOf(teams, settings.team)})") + teams, { setOwn(own.copy(team = it.takeIf { v -> v != same })) }, "Team", openUpwards = false)
                    }
                }
            }
            Section("Look")
            Option("Background", "How much of the black shows; the wallpaper shows through the rest") {
                RwSelect(own.opacity, HomeWidgetSettings.OPACITIES.map { it to "$it%" }, { setOwn(own.copy(opacity = it)) }, "Background", openUpwards = false)
            }
            Option("Fill", "Dim is a faint grey for LCD screens") {
                RwSelect(own.tone, listOf(HomeWidgetSettings.BLACK to "Black", HomeWidgetSettings.DIM to "Dim"), { setOwn(own.copy(tone = it)) }, "Fill", openUpwards = false)
            }
            Option("Corners", "") {
                RwSelect(own.corners, listOf(HomeWidgetSettings.LAUNCHER to "As the launcher", HomeWidgetSettings.SQUARE to "Square"), { setOwn(own.copy(corners = it)) }, "Corners", openUpwards = false)
            }
            Option("Text size", "Everything grows or shrinks with it") {
                RwSelect(own.text, listOf(HomeWidgetSettings.SMALL to "Small", HomeWidgetSettings.NORMAL to "Normal", HomeWidgetSettings.LARGE to "Large"), { setOwn(own.copy(text = it)) }, "Text size", openUpwards = false)
            }
            Option("Border", "") { Switch(own.border, "Border") { setOwn(own.copy(border = it)) } }
            Option("Heading", "The small line at the top") { Switch(own.title, "Heading") { setOwn(own.copy(title = it)) } }
            if (kind != HomeKind.SCHEDULE) {
                Option("Team colours", "The line beside each driver or team") { Switch(own.teamBars, "Team colours") { setOwn(own.copy(teamBars = it)) } }
            }
            Section("Every widget")
            Option("Driver you follow", loading) {
                RwSelect(settings.driver, drivers, { setSettings(settings.copy(driver = it)) }, "Driver you follow", openUpwards = false)
            }
            Option("Team you follow", loading) {
                RwSelect(settings.team, teams, { setSettings(settings.copy(team = it)) }, "Team you follow", openUpwards = false)
            }
            Option("Time", "") {
                RwSelect(settings.clock, listOf(HomeSettings.PHONE to "As the phone", "24" to "24-hour", "12" to "12-hour"), { setSettings(settings.copy(clock = it)) }, "Time", openUpwards = false)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A stand-in wallpaper behind the preview, so a see-through background shows as it would at home. */
private val WALLPAPER = Brush.linearGradient(listOf(Color(0xFF2E3A52), Color(0xFF1B2433), Color(0xFF3B3346)))

/**
 * The widget as the launcher shows it, with this page's settings: the same picture and countdown,
 * at its size on the home screen, made smaller only when the page is narrower than the widget.
 */
@Composable
private fun Preview(id: Int, kind: HomeKind, size: Pair<Float, Float>, snapshot: HomeSnapshot, schedule: List<ScheduledSession>, settings: HomeSettings) {
    val context = LocalContext.current
    val pen = remember { Pen(context) }
    val radius = remember { HomeWidgets.radius(context) }
    val (w, h) = size
    val views: RemoteViews = remember(snapshot, settings) {
        val now = System.currentTimeMillis()
        val h24 = settings.h24(android.text.format.DateFormat.is24HourFormat(context))
        HomeWidgets.face(context, kind, id, pen, FaceEnv(w, h, now, h24, radius), snapshot, schedule, settings)
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(WALLPAPER, RoundedCornerShape(8.dp))
            .border(1.dp, Rw.border, RoundedCornerShape(8.dp))
            .semantics { contentDescription = "Preview of the ${kind.label} widget" },
        contentAlignment = Alignment.Center,
    ) {
        val room = maxWidth.value - 32f
        // A tall widget is shown smaller too, so the settings stay in view.
        val scale = minOf(1f, room / w, 300f / h)
        Box(Modifier.padding(16.dp).size((w * scale).dp, (h * scale).dp), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { FrameLayout(it) },
                update = { frame -> show(frame, views, context) },
                modifier = Modifier.requiredSize(w.dp, h.dp).graphicsLayer(scaleX = scale, scaleY = scale),
            )
        }
    }
}

/** Puts the widget's views in the frame; a tap on the preview does nothing. */
private fun show(frame: FrameLayout, views: RemoteViews, context: Context) {
    frame.removeAllViews()
    val view = runCatching { views.apply(context, frame) }.getOrNull() ?: return
    view.setOnClickListener(null)
    view.isClickable = false
    frame.addView(view)
}
