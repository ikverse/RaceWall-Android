package com.racewall.android.widgets

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.racewall.android.Mode
import com.racewall.android.R
import com.racewall.android.ui.Icon
import com.racewall.android.ui.Rw
import com.racewall.android.ui.RwButton
import com.racewall.topics.RadioClip
import com.racewall.topics.Topic
import kotlinx.coroutines.delay

/** One radio clip playing at a time, for the whole app, as the desktop widget's module-level audio. */
object RadioPlayer {
    private var player: ExoPlayer? = null
    var playing by mutableStateOf("")
        private set
    var position by mutableStateOf(0L)
        private set
    var duration by mutableStateOf(0L)
        private set
    /** The clip stopped where it was when the app left the screen; its button carries on from there. */
    var paused by mutableStateOf(false)
        private set

    fun play(context: Context, url: String, volume: Float) {
        stop()
        val p = ExoPlayer.Builder(context.applicationContext).build()
        player = p
        playing = url
        p.volume = volume
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) stop()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) = stop()
        })
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.play()
    }

    fun stop() {
        player?.release()
        player = null
        playing = ""
        paused = false
        position = 0
        duration = 0
    }

    fun pause() {
        val p = player ?: return
        p.pause()
        paused = true
    }

    fun resume() {
        val p = player ?: return
        p.play()
        paused = false
    }

    fun setVolume(v: Float) {
        player?.volume = v
    }

    /** Reads where the clip is, for the time and the progress bar. */
    fun poll() {
        val p = player ?: return
        position = p.currentPosition
        duration = p.duration.takeIf { it > 0 } ?: 0
    }
}

private fun mss(ms: Long): String {
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun clipsOf(s: WidgetScope): Pair<List<RadioClip>, List<RadioClip>> {
    val all = s.frame.topic<List<RadioClip>>(Topic.RADIO) ?: emptyList()
    val wanted = if (s.values.bool("onlyFocus") && s.focus != null) all.filter { it.number == s.focus } else all
    val keep = s.values.str("keep").toIntOrNull()?.takeIf { it != 0 } ?: 40
    return wanted to wanted.takeLast(keep)
}

/** Team radio clips, played on a tap, as the desktop's plugins/team-radio. */
val TeamRadioWidget = WidgetSpec(
    id = "racewall.team-radio",
    title = "Team radio",
    min = 8 to 6,
    size = 9 to 10,
    reads = setOf(Topic.RADIO, Topic.DRIVERS_LIST),
    fields = listOf(
        Field.Toggle("onlyFocus", "Only the focused driver", false),
        Field.Select("keep", "Clips kept", listOf("20" to "20", "40" to "40", "100" to "100"), "40"),
        Field.Number("volume", "Volume", 0.0, 1.0, 0.1, 1.0, note = "0 to 1"),
        Field.Toggle("autoplay", "Play new clips as they arrive, while live", false),
    ),
    meta = { s -> clipsOf(s).second.size.let { if (it > 0) "$it clips" else "" } },
) { s ->
    val context = LocalContext.current
    val all = s.frame.topic<List<RadioClip>>(Topic.RADIO) ?: emptyList()
    val (wanted, clips) = clipsOf(s)
    val volume = s.values.num("volume").let { if (it.isFinite()) it.coerceIn(0.0, 1.0) else 1.0 }.toFloat()
    RadioPlayer.setVolume(volume)
    LaunchedEffect(RadioPlayer.playing) {
        while (RadioPlayer.playing.isNotEmpty()) {
            RadioPlayer.poll()
            delay(250)
        }
    }
    /** How many clips there were the last time we drew, to spot new ones. -1 until there are some. */
    var seen by remember { mutableIntStateOf(-1) }
    LaunchedEffect(all.size) {
        // A new clip while live plays itself, if asked to and nothing else is playing.
        if (s.mode == Mode.LIVE && s.values.bool("autoplay") && seen >= 0 && all.size > seen && RadioPlayer.playing.isEmpty() && wanted.lastOrNull() == all.lastOrNull()) {
            RadioPlayer.play(context, all.last().url, volume)
        }
        seen = all.size
    }
    when {
        all.isEmpty() -> Empty("No team radio yet.")
        clips.isEmpty() -> Empty("No team radio from the focused driver yet.")
        else -> LazyColumn(Modifier.fillMaxSize().testTag("team-radio")) {
            items(clips.reversed(), key = { it.url }) { c ->
                val on = c.url == RadioPlayer.playing
                val time = if (c.utc != 0L) utcClock(c.utc) else ""
                val known = on && RadioPlayer.duration > 0
                val text = if (on) "$time · ${mss(RadioPlayer.position)}${if (known) " / ${mss(RadioPlayer.duration)}" else ""}" else time
                val name = code(s.frame.drivers, c.number)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind { drawLine(Rw.rowRule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val stoppable = on && !RadioPlayer.paused
                    RwButton(
                        onClick = {
                            when {
                                stoppable -> RadioPlayer.stop()
                                on -> RadioPlayer.resume()
                                else -> RadioPlayer.play(context, c.url, volume)
                            }
                        },
                        on = on,
                        label = "${if (stoppable) "Stop" else "Play"} $name radio",
                        horizontalPadding = 0f,
                        modifier = Modifier.width(26.dp).height(24.dp),
                    ) { colour -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(if (stoppable) R.drawable.ic_stop else R.drawable.ic_play, colour, 11f) } }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(3.dp, 13.dp).background(teamColour(s.frame.drivers, c.number), RoundedCornerShape(1.dp)))
                            Box(Modifier.width(7.dp))
                            BasicText(name, style = Rw.body)
                        }
                        BasicText(text, style = Rw.text(11f, Rw.t3))
                        if (known) {
                            val f = (RadioPlayer.position.toFloat() / RadioPlayer.duration).coerceIn(0f, 1f)
                            Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(2.dp).background(Rw.segmentEmpty, RoundedCornerShape(1.dp))) {
                                Box(Modifier.fillMaxWidth(f).height(2.dp).background(Rw.t2))
                            }
                        }
                    }
                }
            }
        }
    }
}
