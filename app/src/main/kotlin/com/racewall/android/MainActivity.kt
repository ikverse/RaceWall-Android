package com.racewall.android

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.racewall.android.ui.RaceWallApp
import com.racewall.android.ui.RaceWallTheme
import com.racewall.android.ui.Rw
import com.racewall.android.ui.TimingSettings
import com.racewall.android.ui.TransportActions
import com.racewall.archive.Archive
import com.racewall.archive.ArchiveMeeting
import kotlinx.coroutines.launch
import java.io.File

/** Holds the core for as long as the app runs, across rotation and window changes. */
class CoreViewModel(app: Application) : AndroidViewModel(app), TransportActions {
    val core = Core(Archive(cacheDir = File(app.filesDir, "archive")), viewModelScope)

    init {
        core.start()
        // As the desktop does at start: the most recent race or sprint, at its end.
        viewModelScope.launch { core.loadLastResult() }
    }

    override fun toggle() = core.toggle()
    override fun seek(t: Double) = core.seek(t)
    override fun setSpeed(speed: Double) = core.setSpeed(speed)
    override suspend fun listSeason(year: Int): List<ArchiveMeeting> = core.listSeason(year)
    override suspend fun replay(path: String) {
        core.loadSession(path)
        core.play()
    }

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
            RaceWallTheme {
                Box(Modifier.fillMaxSize().background(Rw.bg).safeDrawingPadding()) {
                    RaceWallApp(frame, ui, model.core.mode(), TimingSettings(), model.core::focusDriver, model)
                }
            }
        }
    }
}
