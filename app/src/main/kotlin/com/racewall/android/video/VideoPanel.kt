package com.racewall.android.video

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.racewall.android.Frame
import com.racewall.android.R
import com.racewall.android.SessionMeta
import com.racewall.android.ui.Icon
import com.racewall.android.ui.Rw
import com.racewall.android.ui.RwButton
import com.racewall.android.ui.RwSelect
import com.racewall.android.ui.RwSlider
import com.racewall.android.widgets.WidgetScope
import com.racewall.android.widgets.code
import com.racewall.f1tv.F1Tv
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/** What the F1 TV players need of the app. */
class VideoEnv(
    val hub: VideoHub,
    val session: SessionMeta?,
    val frame: Frame,
    /** The F1 TV subscription token, if signed in. */
    val token: () -> String?,
    val videosForSession: suspend (Int) -> List<F1Tv.Video>,
    val liveVideos: suspend () -> List<F1Tv.Video>,
    val details: suspend (Long) -> F1Tv.Details,
    val play: suspend (Long, Long?) -> F1Tv.PlayInfo,
    /** Session time minus video time, per F1 TV content id, set once with "Mark the start". */
    val offsets: Map<String, Double>,
    val setOffset: (Long, Double) -> Unit,
    val clockPlay: () -> Unit,
    val clockPause: () -> Unit,
    val clockSeek: (Double) -> Unit,
)

val LocalVideoEnv = staticCompositionLocalOf<VideoEnv?> { null }

/** "1:02:15" or "4:05". */
private fun clockText(seconds: Double): String {
    val s = maxOf(0L, Math.floor(seconds).toLong())
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
}

/**
 * Which stream a panel should be playing: null until both the video and its channel list are
 * known. The main feed has no channel id, so the channel's id alone cannot tell "no channel yet"
 * from "the main feed".
 */
internal fun streamRequest(contentId: Long?, channel: F1Tv.Channel?): String? =
    if (contentId == null || channel == null) null else "$contentId|${channel.channelId ?: "main"}"

/** Debug builds only: what the panel does, never tokens or full stream addresses. */
private fun debugLog(text: String) {
    if (com.racewall.android.BuildConfig.DEBUG) android.util.Log.d("RaceWallVideo", text)
}

/**
 * An F1 TV player, as the desktop's VideoPanel.svelte. Plays the video of whatever session RaceWall
 * has loaded, on the channel the panel is set to, with the user's own subscription. Tap the picture
 * for its controls; they hide again on their own.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPanel(s: WidgetScope) {
    val env = LocalVideoEnv.current ?: return
    val hub = env.hub
    val panelId = s.panelId
    val context = LocalContext.current
    val player = remember { F1TvPlayer(context, env.token) }
    var videos by remember { mutableStateOf<List<F1Tv.Video>>(emptyList()) }
    var details by remember { mutableStateOf<F1Tv.Details?>(null) }
    var problem by remember { mutableStateOf("") }
    var fullscreen by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(false) }
    var touched by remember { mutableLongStateOf(0L) }
    /** What the control bar shows, read four times a second from the player. */
    var view by remember { mutableStateOf(View()) }
    val envNow by rememberUpdatedState(env)
    val isLive = env.session?.live != null
    val isMain = hub.main == panelId
    val alone = panelId in hub.alone
    val setting = s.values.str("channel").ifEmpty { if (hub.main == panelId || hub.main == null) "main" else "focus" }
    val volume = s.values.numOr("volume", 1.0).coerceIn(0.0, 1.0)
    val contentId = videos.firstOrNull()?.contentId
    val offset = contentId?.let { env.offsets[it.toString()] }

    /** Which channel to ask for, from the panel setting and the focused driver. */
    val channel = details?.let { d ->
        fun obc(n: String?) = d.channels.firstOrNull { it.type == "obc" && it.racingNumber == n }
        when {
            setting == "focus" -> obc(s.focus) ?: d.channels[0]
            setting.startsWith("obc:") -> obc(setting.removePrefix("obc:")) ?: d.channels[0]
            setting.startsWith("ch:") -> d.channels.firstOrNull { it.channelId?.toString() == setting.removePrefix("ch:") } ?: d.channels[0]
            else -> d.channels[0]
        }
    }

    val handle = remember {
        object : PlayerHandle {
            override val paused get() = !player.exo.playWhenReady || player.exo.playbackState == Player.STATE_ENDED
            override val ended get() = player.exo.playbackState == Player.STATE_ENDED
            override var currentTime: Double
                get() = player.exo.currentPosition / 1000.0
                set(v) = player.exo.seekTo((v * 1000).toLong())
            override fun play() = player.exo.play()
            override fun pause() = player.exo.pause()
            override val contentId get() = videos.firstOrNull()?.contentId
            override fun sessionTime(): Double? {
                val e = envNow
                if (e.session?.live != null) {
                    val utc = player.playheadUtc() ?: return null
                    val base = e.frame.utc?.let { it - e.frame.clock.time } ?: return null
                    return utc - base
                }
                val off = videos.firstOrNull()?.contentId?.let { e.offsets[it.toString()] } ?: return null
                return currentTime * 1000 + off
            }
            override fun seekSession(t: Double): Boolean {
                val e = envNow
                if (e.session?.live != null) {
                    // The playhead's real time and the clock's tie session time to the video; the move
                    // is kept within what F1 TV holds.
                    val utc = player.playheadUtc() ?: return false
                    val base = e.frame.utc?.let { it - e.frame.clock.time } ?: return false
                    val r = seekRange()
                    currentTime = (currentTime + (base + t - utc) / 1000).coerceIn(r.start, r.endInclusive)
                    return true
                }
                val off = videos.firstOrNull()?.contentId?.let { e.offsets[it.toString()] } ?: return false
                currentTime = maxOf(0.0, (t - off) / 1000)
                return true
            }
            override fun seekRange() = player.seekRange()
            override val live get() = envNow.session?.live != null
            override val loaded get() = player.loaded
        }
    }
    DisposableEffect(panelId) {
        hub.register(panelId, handle)
        onDispose {
            hub.unregister(panelId)
            player.release()
        }
    }

    // Find the videos for the loaded session (or what is live).
    val key = env.session?.info?.key
    LaunchedEffect(key, isLive) {
        videos = emptyList()
        details = null
        problem = ""
        if (key == null || key == 0) return@LaunchedEffect
        try {
            var list = env.videosForSession(key)
            if (isLive) list = env.liveVideos().filter { it.sessionKey == key.toString() } + list
            list = list.filter { it.kind == "REPLAY" || it.kind == "LIVE" }
            if (list.isEmpty()) {
                problem = "F1 TV has no video of this session."
                return@LaunchedEffect
            }
            videos = list
            debugLog("panel $panelId: ${list.size} videos for session $key, first ${list[0].contentId}")
            details = env.details(list[0].contentId)
            debugLog("panel $panelId: details with ${details?.channels?.size} channels")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            debugLog("panel $panelId: finding videos failed: $e")
            problem = e.message ?: e.toString()
        }
    }

    // Load the stream whenever the video or channel changes.
    var streamKey by remember { mutableStateOf("") }
    LaunchedEffect(streamRequest(contentId, channel)) {
        val id = contentId ?: return@LaunchedEffect
        val ch = channel ?: return@LaunchedEffect
        val k = streamRequest(id, ch) ?: return@LaunchedEffect
        if (k == streamKey) return@LaunchedEffect
        val first = streamKey.isEmpty()
        streamKey = k
        val resumeAt = player.exo.currentPosition / 1000.0
        try {
            problem = ""
            if (env.token() == null) {
                problem = "Sign in with your F1 account (Settings) to watch F1 TV."
                return@LaunchedEffect
            }
            debugLog("panel $panelId: asking for stream $id channel ${ch.channelId}")
            val info = env.play(id, ch.channelId)
            debugLog("panel $panelId: stream ${info.streamType}, licence server ${if (info.licenseUrl != null) "named" else "none"}, drm ${info.drmType}, host ${android.net.Uri.parse(info.url).host}")
            // A player opening after a layout switch carries on from the players that left: a synced
            // replay starts where the session clock has got to meanwhile, a live player the same
            // distance behind live, and it plays or stays paused as they did.
            val h = if (first) hub.takeHandoff(panelId) else null
            var start: Double? = resumeAt.takeIf { it > 0 }
            if (h != null && !isLive && offset != null) start = maxOf(0.0, (env.frame.clock.time - offset) / 1000)
            player.load(info, start, isLive)
            if (h != null && isLive && h.behind != null && h.behind > 5) {
                delay(500)
                val r = player.seekRange()
                handle.currentTime = maxOf(r.start, r.endInclusive - h.behind)
            }
            player.exo.volume = if (hub.audible == panelId) volume.toFloat() else 0f
            if (h == null || h.playing) player.exo.play()
            debugLog("panel $panelId: loaded, start $start, playing ${h == null || h.playing}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            debugLog("panel $panelId: stream failed: $e")
            problem = e.message ?: e.toString()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                debugLog("panel $panelId: player error ${error.errorCodeName}: ${error.cause}")
                problem = if (error.errorCode in 6000..6999) "This video is copy-protected and could not play (error ${error.errorCodeName})." else "The video could not play (${error.errorCodeName})."
            }
        }
        player.exo.addListener(listener)
        onDispose { player.exo.removeListener(listener) }
    }

    // Sound: only the chosen panel, at this panel's own volume.
    player.exo.volume = if (hub.audible == panelId) volume.toFloat() else 0f

    /** The leader drives the clock, and the players still following follow it. */
    var lastSync by remember { mutableLongStateOf(0L) }
    fun onTime() {
        // Until a stream has loaded there is nothing to follow.
        if (!player.loaded || hub.leaderId() != panelId) return
        for (f in hub.followers()) {
            if (abs(f.currentTime - handle.currentTime) > 0.6) f.currentTime = handle.currentTime
            if (f.paused != handle.paused) if (handle.paused) f.pause() else f.play()
        }
        val t = handle.sessionTime()
        hub.driving = t != null
        if (t == null) return
        // The session clock follows the main player: it runs while the picture plays, and stops with
        // it, set to the picture's exact moment.
        val running = !handle.paused && player.ready
        val e = envNow
        if (running != e.frame.clock.playing) {
            if (running) e.clockPlay() else {
                e.clockPause()
                e.clockSeek(t)
            }
        }
        // Drift between the two is corrected at most four times a second.
        val now = System.currentTimeMillis()
        if (now - lastSync < 250) return
        lastSync = now
        if (abs(e.frame.clock.time - t) > 400) e.clockSeek(t)
    }
    LaunchedEffect(Unit) {
        while (true) {
            onTime()
            val r = player.seekRange()
            view = View(handle.paused, handle.currentTime, r.start, r.endInclusive, envNow.session?.live != null)
            if (controls && System.currentTimeMillis() - touched > 4000) controls = false
            delay(250)
        }
    }

    // Header text.
    val synced = if (isLive) "clock follows this" else if (offset != null) "synced" else "not synced yet"
    val following = if (isMain) synced else "follows the timing"
    hub.metas[panelId] = listOf(
        channel?.name ?: "",
        if (alone) "${if (isMain && !isLive) "$synced · " else ""}on its own · press play below to rejoin" else following,
    ).filter { it.isNotEmpty() }.joinToString(" · ")

    fun markStart() {
        val started = env.session?.startedAt
        val id = contentId
        if (id == null || started == null) {
            problem = "This session has no recorded start to line up with."
            return
        }
        env.setOffset(id, Math.round(started - handle.currentTime * 1000).toDouble())
        problem = ""
        // Pausing this player to mark the start took it off the timing; lead again from here.
        hub.rejoin(panelId)
        lastSync = 0
    }

    val ui: @Composable BoxScope.() -> Unit = {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = { it.player = player.exo },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        controls = !controls
                        touched = System.currentTimeMillis()
                    },
                    onDoubleTap = { fullscreen = !fullscreen },
                )
            },
        )
        val message = when {
            problem.isNotEmpty() -> problem
            details == null -> if (env.session != null) "Finding the F1 TV video…" else "Pick a session to watch."
            else -> ""
        }
        if (message.isNotEmpty()) {
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                BasicText(message, style = Rw.text(13f, Rw.t3).copy(textAlign = TextAlign.Center))
            }
        }
        if ((controls || (isMain && view.paused && details != null)) && details != null) {
            Controls(
                view = view,
                setting = setting,
                details = details!!,
                drivers = { code(s.frame.drivers, it) },
                audible = hub.audible == panelId,
                isMain = isMain,
                isLive = isLive,
                volume = volume,
                fullscreen = fullscreen,
                onTouch = { touched = System.currentTimeMillis() },
                onToggle = {
                    hub.goAlone(panelId)
                    if (handle.paused) handle.play() else handle.pause()
                },
                onSeek = { secs ->
                    hub.goAlone(panelId)
                    val r = player.seekRange()
                    handle.currentTime = secs.coerceIn(r.start, r.endInclusive)
                },
                onChannel = { s.setSetting("channel", JsonPrimitive(it)) },
                onSound = { hub.audible = if (hub.audible == panelId) null else panelId },
                onMain = { hub.main = panelId },
                onMarkStart = ::markStart,
                onVolume = { s.setSetting("volume", JsonPrimitive(it)) },
                onFullscreen = { fullscreen = !fullscreen },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("video-$panelId"), content = ui)
    if (fullscreen) {
        Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black), content = ui)
        }
    }
}

/** What the control bar shows. */
private data class View(val paused: Boolean = true, val current: Double = 0.0, val start: Double = 0.0, val end: Double = 0.0, val live: Boolean = false)

/** The control bar over the picture: position, play, ±10 s, Live, channel, sound, main or start mark, volume, full screen. */
@Composable
private fun Controls(
    view: View,
    setting: String,
    details: F1Tv.Details,
    drivers: (String) -> String,
    audible: Boolean,
    isMain: Boolean,
    isLive: Boolean,
    volume: Double,
    fullscreen: Boolean,
    onTouch: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Double) -> Unit,
    onChannel: (String) -> Unit,
    onSound: () -> Unit,
    onMain: () -> Unit,
    onMarkStart: () -> Unit,
    onVolume: (Double) -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier,
) {
    val behind = maxOf(0.0, view.end - view.current)
    val time = when {
        view.live -> if (behind > 5) "${clockText(behind)} behind live" else "Live"
        view.end > 0 -> "${clockText(view.current - view.start)} / ${clockText(view.end - view.start)}"
        else -> ""
    }
    val options = buildList {
        add("main" to "Main feed")
        add("focus" to "Onboard of the focused driver")
        for (c in details.channels.filter { it.type == "additional" }) add("ch:${c.channelId}" to c.name)
        for (c in details.channels.filter { it.type == "obc" }) add("obc:${c.racingNumber}" to "${drivers(c.racingNumber ?: "")} onboard")
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xB3000000))
            .pointerInput(Unit) { detectTapGestures { onTouch() } }
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 6.dp)
            .testTag("video-controls"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RwSlider(view.current, view.start, maxOf(view.end, view.start + 1), 0.1, { onTouch(); onSeek(it) }, "Video position", Modifier.weight(1f))
            BasicText(time, style = Rw.text(12f, Rw.t2), maxLines = 1)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            RwButton(onClick = { onTouch(); onToggle() }, label = if (view.paused) "Play" else "Pause", modifier = Modifier.height(26.dp)) { c -> Icon(if (view.paused) R.drawable.ic_play else R.drawable.ic_pause, c) }
            RwButton(onClick = { onTouch(); onSeek(view.current - 10) }, label = "Back 10 seconds", modifier = Modifier.height(26.dp)) { c -> BasicText("−10 s", style = Rw.text(13f, c)) }
            RwButton(onClick = { onTouch(); onSeek(view.current + 10) }, label = "Forward 10 seconds", modifier = Modifier.height(26.dp)) { c -> BasicText("+10 s", style = Rw.text(13f, c)) }
            if (view.live && behind > 5) RwButton(onClick = { onTouch(); onSeek(view.end) }, label = "Live", modifier = Modifier.height(26.dp)) { _ -> BasicText("Live", style = Rw.text(13f, Rw.t1)) }
            RwSelect(setting, options, { onTouch(); onChannel(it) }, "Channel", fontSize = 13f, maxWidth = 200f)
            RwButton(onClick = { onTouch(); onSound() }, label = if (audible) "Mute" else "Unmute", modifier = Modifier.height(26.dp)) { c -> Icon(if (audible) R.drawable.ic_speaker else R.drawable.ic_mute, c) }
            if (!isMain) RwButton(onClick = { onTouch(); onMain() }, label = "Make main", modifier = Modifier.height(26.dp)) { c -> BasicText("Make main", style = Rw.text(13f, c)) }
            else if (!isLive) RwButton(onClick = { onTouch(); onMarkStart() }, label = "Mark the start", modifier = Modifier.height(26.dp)) { c -> BasicText("Mark the start", style = Rw.text(13f, c)) }
            Spacer(Modifier.width(8.dp))
            RwSlider(volume, 0.0, 1.0, 0.05, { onTouch(); onVolume(it) }, "Volume of this player", Modifier.width(80.dp))
            RwButton(onClick = { onTouch(); onFullscreen() }, label = if (fullscreen) "Leave full screen" else "Full screen", modifier = Modifier.height(26.dp)) { c ->
                Icon(if (fullscreen) R.drawable.ic_exit_full_screen else R.drawable.ic_full_screen, c)
            }
        }
    }
}
