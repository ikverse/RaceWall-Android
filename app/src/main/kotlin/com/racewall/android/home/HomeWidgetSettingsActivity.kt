package com.racewall.android.home

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.racewall.android.ui.Option
import com.racewall.android.ui.RaceWallTheme
import com.racewall.android.ui.Rw
import com.racewall.android.ui.RwButton
import com.racewall.android.ui.RwSelect
import com.racewall.android.ui.Section

/**
 * A home-screen widget's settings: opened when the widget is added, and again from the launcher's
 * "Reconfigure" on Android 12 and later. The driver and team are set once for every widget, as in
 * Deskglow, and each widget may follow another.
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
        val snapshot = HomeStore.read(this)
        if (snapshot.drivers.isEmpty()) HomeWidgets.refreshNow(applicationContext)
        val start = HomeSettingsStore.read(this)
        setContent {
            RaceWallTheme {
                var settings by remember { mutableStateOf(start) }
                var own by remember { mutableStateOf(start.of(id, kind)) }
                WidgetSettings(kind, snapshot, settings, own, { settings = it }, { own = it }) {
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

@Composable
private fun WidgetSettings(
    kind: HomeKind,
    snapshot: HomeSnapshot,
    settings: HomeSettings,
    own: HomeWidgetSettings,
    setSettings: (HomeSettings) -> Unit,
    setOwn: (HomeWidgetSettings) -> Unit,
    done: () -> Unit,
) {
    val drivers: List<Pair<String, String>> = listOf("" to "None") + snapshot.drivers.map { it.code to it.name }
    val teams: List<Pair<String, String>> = listOf("" to "None") + snapshot.teams.map { it.name to it.name }
    fun nameOf(list: List<Pair<String, String>>, key: String) = list.firstOrNull { it.first == key }?.second ?: "None"
    Column(Modifier.fillMaxSize().background(Rw.bg).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            BasicText("${kind.label} widget", style = Rw.text(14f, Rw.t1, FontWeight.SemiBold), modifier = Modifier.weight(1f))
            RwButton(done, on = true, label = "Done") { BasicText("Done", style = Rw.text(13f, it)) }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            if (kind == HomeKind.STANDINGS) {
                Section("This widget")
                Option("Table", "") {
                    RwSelect(own.table, listOf(HomeWidgetSettings.DRIVERS to "Drivers", HomeWidgetSettings.CONSTRUCTORS to "Constructors"), { setOwn(own.copy(table = it)) }, "Table", openUpwards = false)
                }
                Option("Show", "") {
                    RwSelect(own.value, listOf(HomeWidgetSettings.POINTS to "Points", HomeWidgetSettings.GAP to "Gap to the leader"), { setOwn(own.copy(value = it)) }, "Show", openUpwards = false)
                }
            }
            if (kind != HomeKind.SCHEDULE) {
                if (kind != HomeKind.STANDINGS) Section("This widget")
                val same = "same"
                Option("Driver", "Marked with a line wherever it shows") {
                    RwSelect(own.driver ?: same, listOf(same to "As every widget (${nameOf(drivers, settings.driver)})") + drivers, { setOwn(own.copy(driver = it.takeIf { v -> v != same })) }, "Driver", openUpwards = false)
                }
                if (kind == HomeKind.STANDINGS) {
                    Option("Team", "In the constructors' table") {
                        RwSelect(own.team ?: same, listOf(same to "As every widget (${nameOf(teams, settings.team)})") + teams, { setOwn(own.copy(team = it.takeIf { v -> v != same })) }, "Team", openUpwards = false)
                    }
                }
            }
            Section("Every widget")
            Option("Driver you follow", if (snapshot.drivers.isEmpty()) "The list loads with the standings: open this again in a minute." else "") {
                RwSelect(settings.driver, drivers, { setSettings(settings.copy(driver = it)) }, "Driver you follow", openUpwards = false)
            }
            Option("Team you follow", "") {
                RwSelect(settings.team, teams, { setSettings(settings.copy(team = it)) }, "Team you follow", openUpwards = false)
            }
            Option("Time", "") {
                RwSelect(settings.clock, listOf(HomeSettings.PHONE to "As the phone", "24" to "24-hour", "12" to "12-hour"), { setSettings(settings.copy(clock = it)) }, "Time", openUpwards = false)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
