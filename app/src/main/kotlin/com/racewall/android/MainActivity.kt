package com.racewall.android

import android.Manifest
import androidx.compose.runtime.CompositionLocalProvider
import com.racewall.android.video.LocalVideoEnv
import com.racewall.android.video.VideoEnv
import com.racewall.android.video.VideoHub
import com.racewall.android.widgets.videoHub
import com.racewall.f1tv.F1Tv
import com.racewall.android.update.Updater
import android.app.Application
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import com.racewall.android.live.Alerts
import com.racewall.android.live.LiveService
import com.racewall.android.live.Notify
import com.racewall.topics.TrackFlag
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
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
import com.racewall.android.widgets.RadioPlayer
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
    val core = Core(
        Archive(cacheDir = File(app.filesDir, "archive"), token = { account.current() }),
        viewModelScope,
        recordings = File(app.filesDir, "recordings"),
        onFlag = { flag, info -> flagNotice(flag, info) },
        circuits = File(app.filesDir, "circuits"),
    )

    private val settingsStore = JsonStore(File(app.filesDir, "settings.json"))
    private val layoutsStore = JsonStore(File(app.filesDir, "layouts.json"))
    private val _settings = MutableStateFlow(settingsStore.read(Settings()))
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _layouts = MutableStateFlow(LayoutOps.migrate(layoutsStore.read(defaultLayouts())))
    val layouts: StateFlow<Layouts> = _layouts.asStateFlow()

    /** Whether the system bars are hidden. */
    val fullscreen = MutableStateFlow(false)

    val updater = Updater(BuildConfig.VERSION_NAME, File(app.cacheDir, "updates"))

    /** The F1 TV players' coordination. */
    val hub = VideoHub({ core.ui.value.session?.info?.key }, { core.frame.value.clock.time }).also { videoHub = it }

    fun setVideoOffset(contentId: Long, ms: Double) {
        val s = _settings.value
        setSettings(s.copy(videoOffsets = s.videoOffsets + (contentId.toString() to ms)))
    }

    suspend fun videoPlay(contentId: Long, channelId: Long?): F1Tv.PlayInfo {
        val token = account.current() ?: throw java.io.IOException("Sign in with your F1 account (Settings) to watch F1 TV.")
        return F1Tv.play(contentId, channelId, token)
    }

    init {
        Notify.channels(app)
        core.setOrder(_settings.value.sourceOrder)
        core.setDelay(_settings.value.delay())
        subscribe()
        core.start()
        // The schedule saved last time: what is on now is known at once, before anything loads.
        scheduleTick()
        // As the desktop does at start: the most recent race or sprint, at its end.
        viewModelScope.launch { core.loadLastResult() }
        // A fresh schedule now and every 6 hours; every 30 seconds, announce sessions and leave a live one that is over.
        viewModelScope.launch {
            while (true) {
                Alerts.refresh(app)
                scheduleTick()
                repeat(6 * 60 * 2) {
                    delay(30_000)
                    scheduleTick()
                }
            }
        }
        // A minute after start, if wanted, as the desktop does; never in a debug build.
        viewModelScope.launch {
            delay(60_000)
            if (_settings.value.autoUpdate && !BuildConfig.DEBUG) updater.check()
        }
        // Keep the live service running exactly while live timing is followed.
        viewModelScope.launch {
            var running = false
            core.ui.collect { ui ->
                val live = ui.session?.live != null
                if (live && !running) LiveService.start(app, ui.session?.info?.meeting?.ifEmpty { null } ?: "Live timing")
                if (!live && running) LiveService.stop(app)
                running = live
            }
        }
        // A network that comes back, or a switch from Wi-Fi to mobile data, reconnects live timing at once.
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = core.networkChanged()
        })
    }

    /** Announces sessions and says what is on now; RaceWall never goes live by itself. */
    private fun scheduleTick() {
        val now = System.currentTimeMillis()
        val on = Alerts.check(getApplication(), _settings.value, core.isLive(), now)
        core.setOnNow(on?.let { OnNow(it.meeting, it.name) })
        if (core.isLive()) {
            val ended = core.liveEnded()
            val quiet = now - core.lastMessage() > 20 * 60_000
            if ((ended != null && now - ended > 15 * 60_000) || (on == null && quiet)) leaveLive()
        }
    }

    /** "Watch live" from a notification: live timing, or back to the live moment. From the activity, so the live service may start. */
    fun watchLive() {
        Notify.clearLive(getApplication())
        core.goLive(account.current())
    }

    /** The red flag and safety car notifications, while following a live session. */
    private fun flagNotice(flag: TrackFlag, info: com.racewall.topics.SessionInfo) {
        val s = _settings.value
        val text = when (flag) {
            TrackFlag.RED -> if (s.notifyRed) "Red flag" else null
            TrackFlag.SC -> if (s.notifySafetyCar) "Safety car" else null
            TrackFlag.VSC -> if (s.notifySafetyCar) "Virtual safety car" else null
            else -> null
        } ?: return
        Notify.alert(getApplication(), text, "${info.meeting}, ${info.name}")
    }

    /** Jumps to the live moment: the leading player goes to the live edge, and the clock follows it. */
    override fun goLive() {
        val lead = if (hub.driving) hub.leader() else null
        if (lead != null && lead.live) lead.currentTime = lead.seekRange().endInclusive else core.goLive(account.current())
    }

    override fun syncDelay() {
        val ms = core.syncDelay()
        if (ms == null) core.say("No line crossing yet to sync to. Try again just after the leader crosses the line.")
        else {
            core.say("Delay set to ${Math.round(ms / 1000.0)} s")
            val s = _settings.value
            setSettings(s.copy(delays = s.delays + (s.viewing to ms)))
        }
    }

    /** Back to the last result: the session just watched, from its recording while the archive is still making it. */
    override fun leaveLive() {
        val watched = core.livePath()
        core.stopLive()
        viewModelScope.launch { core.loadLastResult(watched) }
    }

    override suspend fun listRecordings(): List<Recording> = withContext(Dispatchers.IO) { core.listRecordings() }

    /** Asks the core for exactly what the visible widgets read. */
    private fun subscribe() {
        val topics = SHELL_TOPICS.toMutableSet()
        for (panel in _layouts.value.current().panels) if (panel.collapsed != true) widget(panel.widget)?.let { topics += it.reads }
        core.subscribe(topics)
    }

    // While an F1 TV player drives the clock, play, pause and seek act on the video.
    override fun toggle() {
        if (!hub.control(VideoHub.Command.Toggle)) core.toggle()
    }
    override fun seek(t: Double) {
        if (!hub.control(VideoHub.Command.Seek(t))) core.seek(t)
    }
    override fun seekBy(ms: Double) {
        if (!hub.control(VideoHub.Command.SeekBy(ms))) core.seekBy(ms)
    }
    override fun setSpeed(speed: Double) = core.setSpeed(speed)
    override suspend fun listSeason(year: Int): List<ArchiveMeeting> = core.listSeason(year)
    override fun replay(path: String) {
        viewModelScope.launch {
            core.loadSession(path)
            core.play()
            setSettings(_settings.value.copy(lastSession = path))
        }
    }

    override fun showLastResult() {
        viewModelScope.launch { core.loadLastResult() }
    }

    override fun focus(number: String?) = core.setFocus(number)
    override fun range(topic: String, from: Double, to: Double, filter: List<String>?): Any? = core.range(topic, from, to, filter)

    override fun previewLayouts(layouts: Layouts) {
        _layouts.value = layouts
    }

    override fun setLayouts(layouts: Layouts) {
        _layouts.value = layouts
        subscribe()
        viewModelScope.launch(Dispatchers.IO) { layoutsStore.write(layouts) }
    }

    override fun setSettings(settings: Settings) {
        val old = _settings.value
        _settings.value = settings
        if (settings.sourceOrder != old.sourceOrder) core.setOrder(settings.sourceOrder)
        if (settings.delay() != old.delay()) core.setDelay(settings.delay())
        viewModelScope.launch(Dispatchers.IO) { settingsStore.write(settings) }
    }

    override fun toggleFullscreen() {
        fullscreen.value = !fullscreen.value
    }

    override suspend fun signedIn(token: String) {
        if (account.accept(token).signedIn) {
            core.afterSignIn()
            // A sign-in during live timing reconnects with the full feed.
            if (core.isLive()) {
                core.stopLive()
                core.goLive(account.current())
            }
        }
    }

    override fun signOut() = account.signOut()

    override fun checkForUpdates() {
        viewModelScope.launch { updater.check() }
    }

    override fun downloadUpdate() {
        viewModelScope.launch { updater.download() }
    }

    override fun installUpdate() = updater.install(getApplication())

    override fun onCleared() = core.stop()

    /**
     * The app left the screen (Home, another app, the screen off): the players, a team radio clip
     * and the replay clock stop where they are, and nothing starts again by itself. Live timing
     * stays connected for its notifications, and the bar offers to go back to the live moment.
     */
    fun appHidden() {
        hub.pauseAll()
        RadioPlayer.pause()
        if (core.frame.value.clock.playing) core.pause()
    }
}

class MainActivity : ComponentActivity() {
    private val model: CoreViewModel by viewModels()

    /** Opened from "Watch live": go straight into live timing, once. */
    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(Notify.EXTRA_WATCH_LIVE, false) != true) return
        intent.removeExtra(Notify.EXTRA_WATCH_LIVE)
        model.watchLive()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onStop() {
        super.onStop()
        // Not while folding, unfolding or turning: the window comes straight back.
        if (!isChangingConfigurations) model.appHidden()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark bars only: older Androids otherwise draw a light navigation bar under the buttons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        super.onCreate(savedInstanceState)
        // Notifications for sessions starting soon, red flags and the safety car need Android's yes.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handle(intent)
        setContent {
            val frame by model.core.frame.collectAsStateWithLifecycle()
            val ui by model.core.ui.collectAsStateWithLifecycle()
            val account by model.account.status.collectAsStateWithLifecycle()
            val settings by model.settings.collectAsStateWithLifecycle()
            val layouts by model.layouts.collectAsStateWithLifecycle()
            val fullscreen by model.fullscreen.collectAsStateWithLifecycle()
            val update by model.updater.status.collectAsStateWithLifecycle()
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
            val env = VideoEnv(
                hub = model.hub,
                session = ui.session,
                frame = frame,
                token = { model.account.current() },
                videosForSession = { F1Tv.videosForSession(it) },
                liveVideos = { F1Tv.liveVideos() },
                details = { F1Tv.videoDetails(it) },
                play = { id, ch -> model.videoPlay(id, ch) },
                offsets = settings.videoOffsets,
                setOffset = model::setVideoOffset,
                clockPlay = { model.core.play() },
                clockPause = { model.core.pause() },
                clockSeek = { model.core.seek(it) },
                quality = settings.videoQuality,
            )
            RaceWallTheme {
                CompositionLocalProvider(LocalVideoEnv provides env) {
                    Box(Modifier.fillMaxSize().background(Rw.bg).safeDrawingPadding()) {
                        RaceWallApp(Screen(frame, ui, model.core.mode(), settings, layouts, account, update), model, videoDriving = model.hub.driving)
                    }
                }
            }
        }
    }
}
