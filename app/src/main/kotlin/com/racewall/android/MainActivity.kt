package com.racewall.android

import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.racewall.android.account.Account
import com.racewall.android.state.JsonStore
import com.racewall.android.state.LayoutOps
import com.racewall.android.state.Layouts
import com.racewall.android.state.Settings
import com.racewall.android.state.defaultLayouts
import com.racewall.android.ui.AppActions
import com.racewall.android.ui.RaceWallApp
import com.racewall.android.ui.RaceWallTheme
import com.racewall.android.ui.Rw
import com.racewall.android.ui.Screen
import com.racewall.android.widgets.SHELL_TOPICS
import com.racewall.android.widgets.widget
import com.racewall.archive.Archive
import com.racewall.archive.ArchiveMeeting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Holds the core, the settings and the layouts for as long as the app runs, across rotation and window changes. */
class CoreViewModel(app: Application) : AndroidViewModel(app), AppActions {
    val account = Account(app.filesDir).apply { load() }

    // The archive answers 401 without an F1 TV sign-in, so every archive request carries the token.
    val core = Core(Archive(cacheDir = File(app.filesDir, "archive"), token = { account.current() }), viewModelScope)

    private val settingsStore = JsonStore(File(app.filesDir, "settings.json"))
    private val layoutsStore = JsonStore(File(app.filesDir, "layouts.json"))
    private val _settings = MutableStateFlow(settingsStore.read(Settings()))
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _layouts = MutableStateFlow(LayoutOps.migrate(layoutsStore.read(defaultLayouts())))
    val layouts: StateFlow<Layouts> = _layouts.asStateFlow()

    /** Whether the system bars are hidden. */
    val fullscreen = MutableStateFlow(false)

    init {
        core.setOrder(_settings.value.sourceOrder)
        subscribe()
        core.start()
        // As the desktop does at start: the most recent race or sprint, at its end.
        viewModelScope.launch { core.loadLastResult() }
    }

    /** Asks the core for exactly what the visible widgets read. */
    private fun subscribe() {
        val topics = SHELL_TOPICS.toMutableSet()
        for (panel in _layouts.value.current().panels) if (panel.collapsed != true) widget(panel.widget)?.let { topics += it.reads }
        core.subscribe(topics)
    }

    override fun toggle() = core.toggle()
    override fun seek(t: Double) = core.seek(t)
    override fun seekBy(ms: Double) = core.seekBy(ms)
    override fun setSpeed(speed: Double) = core.setSpeed(speed)
    override suspend fun listSeason(year: Int): List<ArchiveMeeting> = core.listSeason(year)
    override suspend fun replay(path: String) {
        core.loadSession(path)
        core.play()
        setSettings(_settings.value.copy(lastSession = path))
    }

    override fun showLastResult() {
        viewModelScope.launch { core.loadLastResult() }
    }

    override fun focus(number: String?) = core.setFocus(number)
    override fun range(topic: String, from: Double, to: Double, filter: List<String>?): Any? = core.range(topic, from, to, filter)

    override fun setLayouts(layouts: Layouts) {
        _layouts.value = layouts
        subscribe()
        viewModelScope.launch(Dispatchers.IO) { layoutsStore.write(layouts) }
    }

    override fun setSettings(settings: Settings) {
        val order = _settings.value.sourceOrder
        _settings.value = settings
        if (settings.sourceOrder != order) core.setOrder(settings.sourceOrder)
        viewModelScope.launch(Dispatchers.IO) { settingsStore.write(settings) }
    }

    override fun toggleFullscreen() {
        fullscreen.value = !fullscreen.value
    }

    override suspend fun signedIn(token: String) {
        if (account.accept(token).signedIn) core.afterSignIn()
    }

    override fun signOut() = account.signOut()

    override fun onCleared() = core.stop()
}

class MainActivity : ComponentActivity() {
    private val model: CoreViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val frame by model.core.frame.collectAsStateWithLifecycle()
            val ui by model.core.ui.collectAsStateWithLifecycle()
            val account by model.account.status.collectAsStateWithLifecycle()
            val settings by model.settings.collectAsStateWithLifecycle()
            val layouts by model.layouts.collectAsStateWithLifecycle()
            val fullscreen by model.fullscreen.collectAsStateWithLifecycle()
            // The screen stays on while a session plays, as a TV would.
            LaunchedEffect(frame.clock.playing) {
                if (frame.clock.playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            LaunchedEffect(fullscreen) {
                val bars = WindowCompat.getInsetsController(window, window.decorView)
                bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (fullscreen) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())
            }
            RaceWallTheme {
                Box(Modifier.fillMaxSize().background(Rw.bg).safeDrawingPadding()) {
                    RaceWallApp(Screen(frame, ui, model.core.mode(), settings, layouts, account), model)
                }
            }
        }
    }
}
