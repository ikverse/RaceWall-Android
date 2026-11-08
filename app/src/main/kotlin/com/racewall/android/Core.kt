package com.racewall.android

import com.racewall.analysis.Battles
import com.racewall.analysis.PitWindow
import com.racewall.analysis.RaceTopics
import com.racewall.archive.Archive
import com.racewall.archive.ArchiveMeeting
import com.racewall.archive.OutlineSource
import com.racewall.archive.ArchiveSignInException
import com.racewall.archive.BackfillLap
import com.racewall.archive.lapsFromFiles
import com.racewall.archive.parseUtc
import com.racewall.archive.OutlineText
import com.racewall.archive.SOURCE as ARCHIVE
import com.racewall.archive.RECORDING_META
import com.racewall.archive.STREAMS
import com.racewall.archive.SessionFiles
import com.racewall.archive.SessionModel
import com.racewall.archive.buildRecording
import com.racewall.archive.buildSession
import com.racewall.archive.endUtc
import com.racewall.archive.stripBom
import com.racewall.bus.Bus
import com.racewall.bus.FeedJson
import com.racewall.bus.TopicProvider
import com.racewall.clock.Clock
import com.racewall.clock.ClockState
import com.racewall.clock.SPEEDS
import com.racewall.jolpica.Jolpica
import com.racewall.live.LiveClient
import com.racewall.live.LiveHandlers
import com.racewall.live.LiveStatus
import com.racewall.live.Recorder
import com.racewall.openf1.OpenF1
import com.racewall.topics.CircuitOutline
import com.racewall.topics.DriverList
import com.racewall.topics.LapHistory
import com.racewall.topics.Laps
import com.racewall.topics.Positions
import com.racewall.topics.RaceControlMessages
import com.racewall.topics.SessionInfo
import com.racewall.topics.SessionStatus
import com.racewall.topics.Stints
import com.racewall.topics.Topic
import com.racewall.topics.TrackFlag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

/** How often the window is sent the moment's data, as the desktop's FRAME_MS. */
const val FRAME_MS = 100L

/** A replay opens this long before the session started, so the start is not missed. */
private const val LEAD_IN_MS = 60_000.0

/** What the battles and pit-window analyses publish, as on the desktop. */
const val BATTLES_TOPIC = "racewall.battles.list"
const val PIT_WINDOW_TOPIC = "racewall.pit-window.estimate"

/** How often a live session asks the archive again for the laps before RaceWall joined. */
private const val BACKFILL_RETRY_MS = 5 * 60_000L

/** The source name of live timing on the bus. */
const val LIVE = "f1-live"

/** A recorded path that points at a local recording. */
const val RECORDING = "recording:"

/**
 * An F1 TV player that stops telling the clock where it is, or is stuck waiting for video, for this
 * long no longer drives it: the data goes on at the live edge until the picture moves again.
 */
private const val VIDEO_STALE_MS = 2000L

/**
 * Live timing's state, for the transport bar. `delayMs` is the delay in use: none while an F1 TV
 * player in RaceWall drives the clock, which `video` says.
 */
data class LiveInfo(val status: LiveStatus, val detail: String, val following: Boolean, val delayMs: Long, val video: Boolean = false)

/** What the loaded session is, as the desktop's SessionMeta. */
data class SessionMeta(
    val info: SessionInfo,
    val start: Double,
    val end: Double,
    val startedAt: Double?,
    /** Loaded on its own at start-up, at its end: the window shows it as the last result. */
    val lastResult: Boolean,
    /** The archive path, a "recording:" path, or null while live. */
    val path: String?,
    /** Set while following live timing. */
    val live: LiveInfo? = null,
)

/** A session RaceWall recorded live. */
data class Recording(val path: String, val meeting: String, val name: String, val startDate: String)

/** A scheduled session that is on now, which live timing can follow. */
data class OnNow(val meeting: String, val name: String)

/** The parts of the live client the core uses, so tests can pass a fake one. */
interface LiveConnection {
    suspend fun start()
    fun stop()
    val lastMessage: Long
    fun reconnectNow() {}
}

/** Opens a live connection with these handlers and the F1 TV token, if signed in. */
typealias LiveConnect = (LiveHandlers, String?) -> LiveConnection

/** The moment's data for the widgets: what the desktop sends the window ten times a second. */
data class Frame(
    val clock: ClockState = ClockState(0.0, false, 1.0, 0.0, 0.0),
    /** UTC milliseconds at the playhead, or null when the session has no UTC anchor. */
    val utc: Double? = null,
    /** The subscribed topics that have a value now. */
    val topics: Map<String, Any> = emptyMap(),
    /** The data sources that answered, in the user's order. */
    val sources: List<String> = emptyList(),
    /** Car positions and telemetry: none, loading, loaded or failed. */
    val cars: String = "none",
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> topic(name: String): T? = topics[name] as T?

    val info: SessionInfo? get() = topic(Topic.SESSION_INFO)
    val status: SessionStatus? get() = topic(Topic.SESSION_STATUS)
    val drivers: DriverList get() = topic(Topic.DRIVERS_LIST) ?: emptyMap()
    val positions: Positions get() = topic(Topic.TIMING_POSITIONS) ?: emptyList()
    val laps: Laps get() = topic(Topic.TIMING_LAPS) ?: emptyMap()
    val stints: Stints get() = topic(Topic.TIMING_STINTS) ?: emptyMap()

    /** Only these topics, for a widget that reads them, so it redraws only when they change. */
    fun only(names: Collection<String>): Frame = copy(topics = topics.filterKeys { it in names })
}

/** What the window shows around the data: what is loading, what went wrong. */
data class UiState(
    val session: SessionMeta? = null,
    val loading: String = "",
    val notice: String = "",
    val error: String = "",
    /** The driver every widget highlights, or null. */
    val focusDriver: String? = null,
    /** A scheduled session that is on now, which live timing can follow. */
    val onNow: OnNow? = null,
)

/** The widgets' mode, as the desktop's `mode()`. */
enum class Mode { IDLE, REPLAY, LIVE }

/** Live timing through the real client. */
private val liveClient: LiveConnect = { handlers, token ->
    val c = LiveClient(handlers, token)
    object : LiveConnection {
        override suspend fun start() = c.start()
        override fun stop() = c.stop()
        override val lastMessage get() = c.lastMessage
        override fun reconnectNow() = c.reconnectNow()
    }
}

/**
 * The app's core, as the desktop's main-process `Core` and the window state together: the bus, the
 * clock, the loaded session (a replay or live timing), the data sources, and the frames the
 * widgets draw from.
 */
class Core(
    private val archive: Archive,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** Downloads from OpenF1 and Jolpica; tests pass fakes, or null to leave them out. */
    private val openF1: (suspend (Bus, List<String>, Int, (Double) -> Double) -> List<String>)? = { bus, topics, key, utcToT -> OpenF1.provide(bus, topics, key, utcToT) },
    private val jolpica: (suspend (Bus, Int, Int?, Int) -> List<String>)? = { bus, season, round, schedule -> Jolpica.provide(bus, season, round, schedule) },
    /** Where live sessions are recorded; null records nothing. */
    private val recordings: File? = null,
    private val connect: LiveConnect = liveClient,
    /** Told when the track flag changes while live, for the red-flag and safety-car notifications. */
    private val onFlag: (TrackFlag, SessionInfo) -> Unit = { _, _ -> },
    /** Where circuit outlines are saved, to show at once next time; null saves none. */
    private val circuits: File? = null,
    /** The replay clock's own steady milliseconds; tests pass the same fake as `now`. */
    clockNow: () -> Double = { System.nanoTime() / 1e6 },
    /** The outline that ships with the app for a circuit key (assets/circuits), or null. */
    private val bundled: (Int) -> String? = { null },
) {
    /** What the F1 TV player that drives the clock last said, and when (wall ms). */
    private class VideoReport(val t: Double, val playing: Boolean, val at: Long, val stalledSince: Long?)

    private class LiveState(
        var model: SessionModel,
        /**
         * UTC ms of session time 0, in the server's time: the Heartbeat of the first snapshot, else
         * the first stamped message. Until either comes, the device's clock at connecting.
         */
        var base: Long,
        /** Whether `base` is the server's own time yet. */
        var serverBase: Boolean = false,
        /** Whether a stamped message has come: from then on a (reconnect's) snapshot goes at the edge, not at 0. */
        var stamped: Boolean = false,
        /** The server's clock minus the device's, as the stamped messages show it (less their travel time). */
        var skew: Long = 0,
        var video: VideoReport? = null,
        /** True while an F1 TV player drives the clock: then no delay holds the data back. */
        var videoDriving: Boolean = false,
        var client: LiveConnection? = null,
        /** The archive path of the session the feed is on, once it said. */
        var path: String? = null,
        var recorder: Recorder? = null,
        var status: LiveStatus = LiveStatus.CONNECTING,
        var detail: String = "",
        var following: Boolean = true,
        var lastFlag: TrackFlag? = null,
        /** Wall time the feed last said the session ended, if it did. */
        var endedAt: Long? = null,
        /** Whether this session's own outline has been saved for the next session at the circuit. */
        @Volatile var outlineSaved: Boolean = false,
        /** Wall time saving the outline was last tried: it is worked out at most every few seconds. */
        var outlineSaveTried: Long = 0,
        /** Filling in the laps driven before RaceWall joined: see `backfill`. */
        var backfill: Backfill = Backfill(),
    )

    private class Backfill(
        /** Wall time of the next try. */
        var next: Long = 0,
        @Volatile var running: Boolean = false,
        /** The archive gave the laps, or there is nothing more to try. */
        var done: Boolean = false,
        /** The archive needs a sign-in RaceWall does not have. */
        var archiveOff: Boolean = false,
        var recordingTried: Boolean = false,
    )

    /** What a topic last answered and under which key, so an unchanged topic is not worked out again. */
    private class Cached(val key: String, val answer: com.racewall.bus.Answer)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    val bus = Bus()
    private val clock = Clock(clockNow)
    private var session: SessionModel? = null
    @Volatile private var live: LiveState? = null
    private var delayMs = 0L
    private var order: List<String> = listOf(LIVE, "f1-archive", "openf1", "jolpica")
    private var subscribed: Set<String> = emptySet()
    private var cars = "none" // none, loading, loaded, failed
    private val backups = HashSet<String>()

    private val _frame = MutableStateFlow(Frame())
    val frame: StateFlow<Frame> = _frame.asStateFlow()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var ticker: Job? = null

    init {
        bus.setOrder(order)
        registerAnalysis()
    }

    fun mode(): Mode = _ui.value.session.let {
        when {
            it?.live != null -> Mode.LIVE
            it == null || it.lastResult -> Mode.IDLE
            else -> Mode.REPLAY
        }
    }

    /** Starts sending frames. */
    fun start() {
        if (ticker?.isActive == true) return
        ticker = scope.launch(Dispatchers.Default) {
            while (isActive) {
                tick()
                delay(FRAME_MS)
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        stopLive()
    }

    /** Asks for exactly what the visible widgets read. */
    fun subscribe(topics: Set<String>) {
        if (topics == subscribed) return
        subscribed = topics
        scope.launch { ensureCars(); ensureBackups() }
        tick()
    }

    /** Sources earlier in the list are asked first. */
    fun setOrder(sources: List<String>) {
        order = sources
        bus.setOrder(order)
        cache.clear()
        scope.launch { ensureBackups() }
        tick()
    }

    /** Builds the frame for the clock's moment now. */
    fun tick() {
        live?.let { l ->
            // An F1 TV player in RaceWall drives the clock while it reports and is not stuck. Otherwise
            // the live edge is now, held back by the broadcast delay so data matches the TV picture.
            val wall = now()
            val v = l.video
            val driving = v != null && wall - v.at < VIDEO_STALE_MS && (v.stalledSince == null || wall - v.stalledSince < VIDEO_STALE_MS)
            l.videoDriving = driving
            val edge = maxOf(0.0, liveNow(l) - if (driving) 0.0 else delayMs.toDouble())
            if (driving && v != null) {
                val picture = v.t + if (v.playing && v.stalledSince == null) (wall - v.at).toDouble() else 0.0
                clock.extend(maxOf(edge, picture))
            } else {
                clock.extend(edge)
                if (l.following && !clock.playing) clock.play()
                if (l.following && abs(clock.time - edge) > 250) clock.seek(edge)
            }
            updateMeta()
            // Live works its outline out after a clean lap; once it has, keep it for the next session here.
            if (!l.outlineSaved && now() - l.outlineSaveTried >= 10_000) {
                l.outlineSaveTried = now()
                val model = l.model
                scope.launch(Dispatchers.IO) { if (saveOutline(model, live = true) && l.model === model) l.outlineSaved = true }
            }
            val bf = l.backfill
            if (l.path != null && !bf.done && !bf.running && now() >= bf.next) {
                bf.running = true
                scope.launch(Dispatchers.IO) { backfill(l) }
            }
            val status = bus.typed<SessionStatus>(Topic.SESSION_STATUS, clock.time)
            if (status != null && status.flag != l.lastFlag) {
                if (l.lastFlag != null) onFlag(status.flag, l.model.info)
                l.lastFlag = status.flag
            }
        }
        val state = clock.state()
        val t = state.time
        val sources = LinkedHashSet<String>()
        val topics = LinkedHashMap<String, Any>()
        for (name in subscribed) {
            // A topic whose source keys it is worked out again only when the key changed; the same
            // value object is passed on, so the frame compares equal at once.
            val key = bus.version(name, t)
            val hit = key?.let { k -> cache[name]?.takeIf { it.key == k } }
            val a = hit?.answer ?: bus.answer(name, t) ?: continue
            if (key != null && hit == null) cache[name] = Cached(key, a)
            sources.add(a.source)
            topics[name] = a.value
        }
        val frame = Frame(state, session?.utcAt(t), topics, order.filter { it in sources } + sources.filter { it !in order }, cars)
        if (frame != _frame.value) _frame.value = frame
    }

    /** What the battles and pit-window analyses read, from the bus at `t`. */
    private fun raceTopics(t: Double) = RaceTopics(
        info = bus.typed(Topic.SESSION_INFO, t),
        status = bus.typed(Topic.SESSION_STATUS, t),
        positions = bus.typed<Positions>(Topic.TIMING_POSITIONS, t) ?: emptyList(),
        lapHistory = bus.typed<LapHistory>(Topic.LAP_HISTORY, t),
        stints = bus.typed<Stints>(Topic.TIMING_STINTS, t),
        messages = bus.typed<RaceControlMessages>(Topic.RACE_CONTROL, t),
    )

    /** The keys of everything the analyses read at `t`, or null when one of them keeps none. */
    private fun raceKey(t: Double): String? {
        val keys = ArrayList<String>()
        for (topic in listOf(Topic.SESSION_INFO, Topic.SESSION_STATUS, Topic.TIMING_POSITIONS, Topic.LAP_HISTORY, Topic.TIMING_STINTS, Topic.RACE_CONTROL)) {
            if (!bus.has(topic)) {
                keys.add("-")
                continue
            }
            keys.add(bus.version(topic, t) ?: if (bus.answer(topic, t) == null) "-" else return null)
        }
        return keys.joinToString(";")
    }

    /** One analysis, worked out again only when what it reads has changed. */
    private class Memo(private val compute: (Double) -> Any?) {
        @Volatile private var last: Pair<String, Any?>? = null

        fun at(t: Double, key: String?): Any? {
            if (key != null) last?.let { if (it.first == key) return it.second }
            val value = compute(t)
            if (key != null) last = key to value
            return value
        }

        fun clear() {
            last = null
        }
    }

    private val battles = Memo { t -> Battles.find(raceTopics(t)) }
    private val pitWindow = Memo { t -> PitWindow.estimate(raceTopics(t)) }

    /** The desktop runs these as analysis plugins; here they are worked out when asked. */
    private fun registerAnalysis() {
        bus.provide(BATTLES_TOPIC, TopicProvider("racewall.battles", { t -> if (session == null) null else battles.at(t, raceKey(t)) }))
        bus.provide(PIT_WINDOW_TOPIC, TopicProvider("racewall.pit-window", { t -> if (session == null) null else pitWindow.at(t, raceKey(t)) }))
    }

    // ---------------------------------------------------------------- clock

    private fun command(block: () -> Unit) {
        block()
        _ui.update { u -> u.copy(session = u.session?.copy(lastResult = false)) }
        updateMeta()
        tick()
    }

    /**
     * The F1 TV player that drives the clock says where its picture is, a few times a second. The
     * clock runs while it plays and stops with it, at its exact moment. Live, the clock may go up
     * to the picture's time even where the live edge (held back by a delay) has not, and following
     * the live edge is left as it was: a buffering stall must not leave the data behind for good.
     */
    fun videoReport(t: Double, playing: Boolean, stalled: Boolean) {
        val was = clock.playing
        val wall = now()
        val l = live
        var drive = true
        if (l != null) {
            val stalledSince = if (stalled) (l.video?.stalledSince ?: wall) else null
            l.video = VideoReport(t, playing, wall, stalledSince)
            // Stuck for a while: the live edge has the data until the picture moves again.
            if (stalledSince != null && wall - stalledSince >= VIDEO_STALE_MS) drive = false
            else {
                l.videoDriving = true
                clock.extend(t)
            }
        }
        if (drive) {
            val running = playing && !stalled
            if (running) clock.play() else clock.pause()
            if (abs(clock.time - t) > (if (running) 400.0 else 1.0)) clock.seek(t)
        }
        if (_ui.value.session?.lastResult == true) _ui.update { u -> u.copy(session = u.session?.copy(lastResult = false)) }
        // Only a change of play or pause needs a frame before the next tick.
        if (clock.playing != was) {
            updateMeta()
            tick()
        }
    }

    fun toggle() = command {
        clock.toggle()
        if (!clock.playing) live?.following = false
    }

    fun play() = command { clock.play() }

    fun pause() = command {
        clock.pause()
        live?.following = false
    }

    fun seek(t: Double) = command {
        clock.seek(t)
        live?.following = false
    }

    fun seekBy(ms: Double) = command {
        clock.seekBy(ms)
        live?.let { it.following = ms > 0 && it.following }
    }

    fun setSpeed(speed: Double) = command { if (speed in SPEEDS) clock.setSpeed(speed) }

    /** Back to the live moment, at normal speed. */
    fun followLive() = command {
        live?.let {
            it.following = true
            clock.setSpeed(1.0)
            clock.play()
        }
    }

    fun focusDriver(number: String) = _ui.update { it.copy(focusDriver = if (it.focusDriver == number) null else number) }
    fun setFocus(number: String?) = _ui.update { it.copy(focusDriver = number) }

    /** Range queries, such as telemetry over the last minute. */
    fun range(topic: String, from: Double, to: Double, filter: List<String>? = null): Any? = bus.range(topic, from, to, filter)

    fun say(text: String) = _ui.update { it.copy(notice = text) }

    // ---------------------------------------------------------------- replay

    suspend fun listSeason(year: Int): List<ArchiveMeeting> = archive.fetchSeason(year)

    private suspend fun load(path: String, lastResult: Boolean): SessionMeta {
        val recording = path.startsWith(RECORDING)
        val files = if (recording) withContext(Dispatchers.IO) { readRecording(path) } else archive.fetchSessionFiles(path)
        val recordingMeta = if (recording) withContext(Dispatchers.IO) { readFile(recordingDir(path), RECORDING_META) } else ""
        stopLive()
        val meta = withContext(Dispatchers.Default) {
            val s = if (recording) buildRecording(files, recordingMeta, bus) else buildSession(files, bus)
            useSession(s)
            val at = if (lastResult) s.end else maxOf(s.start, (s.startedAt ?: s.start) - LEAD_IN_MS)
            clock.load(s.start, s.end, at)
            SessionMeta(s.info, s.start, s.end, s.startedAt, lastResult, path)
        }
        _ui.update { it.copy(session = meta) }
        scope.launch { ensureCars(); ensureBackups() }
        return meta
    }

    private fun useSession(s: SessionModel) {
        session = s
        // Keys are only unique within one session.
        cache.clear()
        battles.clear()
        pitWindow.clear()
        bus.removeSource(OpenF1.SOURCE)
        cars = if (live != null) "loaded" else "none"
        backups.clear()
        bus.setOrder(order)
        // A replay shows the circuit saved from an earlier session there while its own cars load.
        if (live == null) useSavedOutline(s)
        scope.launch { loadJolpica(s.info) }
    }

    /** Where the outline of this session's circuit is saved: one file per circuit, by F1's circuit key. */
    private fun outlineFile(info: SessionInfo): File? {
        val dir = circuits ?: return null
        return if (info.circuitKey == 0) null else File(dir, "${info.circuitKey}.json")
    }

    private fun readText(f: File): String = runCatching { if (f.isFile) f.readText() else "" }.getOrDefault("")

    /**
     * Outlines saved before circuit keys were used, one per season and circuit name
     * ("2026-Interlagos.json"), up to this session's season, the newest first.
     */
    private fun legacyOutlines(info: SessionInfo): List<Pair<Int, String>> {
        val dir = circuits ?: return emptyList()
        if (info.year == 0 || info.circuit.isEmpty()) return emptyList()
        val name = "-${info.circuit.replace(Regex("[^A-Za-z0-9]+"), "_")}.json"
        return dir.listFiles().orEmpty()
            .mapNotNull { f -> f.name.takeIf { it.endsWith(name) }?.removeSuffix(name)?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt() }
            .filter { it <= info.year }
            .sortedDescending()
            .map { it to readText(File(dir, "$it$name")) }
            .filter { OutlineText.read(it.second) != null }
    }

    private class SavedFile(val date: String, val text: String)

    /**
     * The outline saved at this circuit, newest first: the user's own file (by circuit key) or an
     * older-style one (by season and name), whichever is from the later session. The date is the
     * session's scheduled start; an older-style file only says its season. Null when none is saved.
     */
    private fun savedFile(info: SessionInfo): SavedFile? {
        val own = outlineFile(info)?.let(::readText).orEmpty()
        val keyed = if (OutlineText.read(own) != null) SavedFile(OutlineText.source(own)?.date ?: "", own) else null
        val old = legacyOutlines(info).firstOrNull()?.let { SavedFile(it.first.toString(), it.second) }
        return if (keyed != null && old != null) (if (keyed.date >= old.date) keyed else old) else keyed ?: old
    }

    /**
     * The outline shown until this session has its own: the one saved at this circuit or, failing
     * that, the one that ships with the app.
     */
    private fun savedOutline(info: SessionInfo): CircuitOutline? {
        savedFile(info)?.let { return OutlineText.read(it.text) }
        if (info.circuitKey == 0) return null
        return runCatching { bundled(info.circuitKey) }.getOrNull()?.let(OutlineText::read)
    }

    /**
     * Keeps this session's own outline, to show at once next time at the same circuit. Live always
     * saves, as it is the newest; a replay only when nothing is saved there or it is from a later
     * session than what is. True once done, or once there is nothing more to do.
     */
    private fun saveOutline(s: SessionModel, live: Boolean): Boolean {
        val info = s.info
        // Live, the circuit is known only once the feed has said which session it is.
        val file = outlineFile(info) ?: return !live || circuits == null
        val date = info.startDate.ifEmpty { info.year.toString() }
        if (!live) {
            val saved = savedFile(info)
            if (saved != null && date <= saved.date) return true
        }
        val o = s.outlineNow() ?: return false
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(OutlineText.write(o, OutlineSource(info.circuitKey, info.circuit, info.year, info.path, date)))
        }
        return true
    }

    private fun useSavedOutline(s: SessionModel) {
        savedOutline(s.info)?.let(s::useSavedOutline)
    }

    private suspend fun loadJolpica(info: SessionInfo) {
        val provide = jolpica ?: return
        val year = Instant.ofEpochMilli(now()).atZone(ZoneOffset.UTC).year
        runCatching { provide(bus, if (info.year != 0) info.year else year, info.round.takeIf { it != 0 }, year) }
        tick()
    }

    /**
     * Downloads car positions and telemetry the first time a widget needs them (replays only), or
     * again after they failed. The positions come first, and with them the circuit, so the track map
     * draws while the larger telemetry is still on its way.
     */
    private suspend fun ensureCars() {
        val s = session ?: return
        val path = _ui.value.session?.path ?: return
        if ((cars != "none" && cars != "failed") || subscribed.none { it in Topic.HEAVY }) return
        cars = "loading"
        tick()
        suspend fun file(name: String) =
            if (path.startsWith(RECORDING)) withContext(Dispatchers.IO) { readFile(recordingDir(path), "$name.jsonStream") }
            else archive.fetchCarFile(path, name)
        try {
            say("Loading car positions…")
            val positions = file("Position.z")
            if (session !== s) return
            withContext(Dispatchers.Default) { s.attachPositions(positions) }
            withContext(Dispatchers.IO) { saveOutline(s, live = false) }
            tick()
            say("Loading telemetry…")
            val telemetry = file("CarData.z")
            if (session !== s) return
            withContext(Dispatchers.Default) { s.attachTelemetry(telemetry) }
            cars = "loaded"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            if (session === s) cars = "failed"
        } finally {
            say("")
            tick()
        }
    }

    /**
     * Asks OpenF1 for topics the archive could not give this session, or for all its topics when the
     * user put OpenF1 ahead of the archive. Not used live: OpenF1's history lags the live feed.
     */
    private suspend fun ensureBackups() {
        val s = session ?: return
        val provide = openF1 ?: return
        if (live != null || s.info.key == 0) return
        val preferred = order.indexOf(OpenF1.SOURCE) < order.indexOf(ARCHIVE)
        val wanted = OpenF1.BACKUP_TOPICS.filter { topic ->
            if (topic !in subscribed || topic in backups) return@filter false
            if (preferred) return@filter true
            if (topic in Topic.HEAVY && (cars == "loading" || cars == "none")) return@filter false
            bus.answer(topic, s.end) == null
        }
        if (wanted.isEmpty()) return
        backups.addAll(wanted)
        val base = s.utcAt(0.0) ?: return
        say("Loading backup data from OpenF1…")
        try {
            provide(bus, wanted, s.info.key) { it - base }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // The archive's own data stays; nothing else to do.
        } finally {
            say("")
            tick()
        }
    }

    /**
     * The most recent race or sprint in the archive that has finished, loaded at its end. `watched`
     * is the archive path of the live session just left: while the archive is still making that one
     * (not "Complete"), RaceWall's own recording of it is shown instead. The same goes for the newest
     * race or sprint when it was recorded here and the archive is not done with it.
     */
    private suspend fun lastResult(watched: String?): SessionMeta? {
        if (watched != null) recorded(watched)?.let { return load(it, true) }
        val time = now()
        val year = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC).year
        for (y in listOf(year, year - 1)) {
            val sessions = listSeason(y)
                .flatMap { it.sessions }
                .filter { (it.type == "Race" || it.name == "Sprint") && endUtc(it.endDate, it.gmtOffset) < time - 30 * 60_000 }
                .sortedByDescending { endUtc(it.endDate, it.gmtOffset) }
            if (sessions.isNotEmpty()) return load(recorded(sessions[0].path) ?: sessions[0].path, true)
        }
        return null
    }

    /** RaceWall's own recording of an archive session, as a "recording:" path, while the archive is not done with it. */
    private suspend fun recorded(archivePath: String): String? {
        val root = recordings ?: return null
        val parts = archivePath.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || ".." in parts) return null
        if (!File(root, parts.joinToString(File.separator) + File.separator + "SessionInfo.json").isFile) return null
        val done = try {
            archive.isComplete(parts.joinToString("/") + "/")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        return if (done) null else RECORDING + parts.joinToString("/")
    }

    private suspend fun run(label: String, task: suspend () -> Unit) {
        _ui.update { it.copy(loading = label, error = "") }
        try {
            task()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(error = e.message ?: e.toString()) }
        } finally {
            _ui.update { it.copy(loading = "") }
            tick()
        }
    }

    suspend fun loadSession(path: String) = run("Loading session…") { load(path, false) }

    suspend fun loadLastResult(watched: String? = null) = run("Loading the last result…") { lastResult(watched) }

    /** Nothing could load before, because the archive wanted a sign-in: try the last result again. */
    suspend fun afterSignIn() {
        val ui = _ui.value
        if (ui.session == null && ui.error.contains("needs an F1 TV sign-in")) loadLastResult()
        // Car positions that could not load before the sign-in are tried again.
        else if (cars == "failed") ensureCars()
    }

    // ---------------------------------------------------------------- live

    fun isLive() = live != null

    /** The archive path of the session live timing is on, once the feed said. */
    fun livePath(): String? = live?.path

    /** Wall time the live feed last said the session ended, if it did. */
    fun liveEnded(): Long? = live?.endedAt

    /** Wall time of the last live message. */
    fun lastMessage(): Long = live?.client?.lastMessage ?: 0

    fun setOnNow(on: OnNow?) {
        if (on != _ui.value.onNow) _ui.update { it.copy(onNow = on) }
    }

    fun setDelay(ms: Long) {
        delayMs = ms.coerceIn(0, 10 * 60_000)
        updateMeta()
    }

    /** The network came back or changed: reconnect at once. */
    fun networkChanged() {
        live?.client?.reconnectNow()
    }

    /** Connects to F1's live timing feed. `token` is the F1 TV subscription token, if signed in. */
    fun goLive(token: String?) {
        if (live != null) return followLive()
        val base = now()
        bus.removeSource(ARCHIVE)
        bus.removeSource(OpenF1.SOURCE)
        val state = LiveState(SessionModel(bus, LIVE, emptyMap<String, Any?>(), base.toDouble()), base)
        state.model.register()
        live = state
        useSession(state.model)
        clock.load(0.0, 0.0, 0.0, live = true)
        clock.play()
        _ui.update { it.copy(error = "") }
        updateMeta()
        openClient(state, token)
    }

    /**
     * After a sign-in during live timing: a new connection with the full feed, in place of the old
     * one. The session, its lap history and its outline stay, and so does the recording.
     */
    fun reconnectLive(token: String?) {
        val l = live ?: return
        l.client?.stop()
        openClient(l, token)
    }

    private fun openClient(state: LiveState, token: String?) {
        // The connection these handlers belong to: an old one's late messages are not heard.
        val ref = arrayOfNulls<LiveConnection>(1)
        fun mine() = live === state && state.client === ref[0]
        val client = connect(object : LiveHandlers {
            override fun snapshot(topic: String, data: Any?) {
                if (mine()) liveRecord(state, topic, data, null)
            }

            override fun message(topic: String, data: Any?, utc: Long) {
                if (mine()) liveRecord(state, topic, data, utc)
            }

            override fun status(status: LiveStatus, detail: String) {
                if (state.client !== ref[0] && state.client != null) return
                state.status = status
                state.detail = detail
                updateMeta()
            }
        }, token)
        ref[0] = client
        state.client = client
        scope.launch { client.start() }
    }

    /** Session time of the live edge: the server's time now. */
    private fun liveNow(l: LiveState): Double = (now() + l.skew - l.base).toDouble()

    private fun setLiveBase(state: LiveState, utc: Long) {
        state.base = utc
        state.serverBase = true
        state.model.setBase(utc.toDouble())
    }

    /**
     * One record of the live feed: `utc` is the server's time for a message, null for a snapshot.
     * The first snapshot is the state at time 0, so the widgets fill at once; a reconnect's snapshot
     * goes at the live edge.
     */
    private fun liveRecord(state: LiveState, topic: String, data: Any?, utc: Long?) = synchronized(state) {
        if (utc != null) {
            // The highest of (server time - arrival time) is the clocks' difference less the shortest
            // travel time; it is let down slowly, in case the device's clock is moved.
            val sample = utc - now()
            state.skew = if (state.stamped) maxOf(state.skew - 1, sample) else sample
            if (!state.stamped && !state.serverBase) setLiveBase(state, utc)
            state.stamped = true
        } else if (topic == "Heartbeat" && !state.stamped && !state.serverBase) {
            val hb = ((data as? Map<*, *>)?.get("Utc") as? String)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            if (hb != null) setLiveBase(state, hb)
        }
        if (topic == "SessionInfo") {
            val path = ((data as? Map<*, *>)?.get("Path") as? String)?.ifEmpty { null }
            // The feed moved on to the next session: nothing of the last one may leak into it.
            if (path != null && state.path != null && path != state.path) freshLive(state, utc ?: (now() + state.skew))
            if (path != null) state.path = path
        }
        val t = when {
            utc != null -> (utc - state.base).toDouble()
            state.stamped -> liveNow(state)
            else -> 0.0
        }
        if (topic == "SessionInfo") {
            state.model.add("SessionInfo", t, data)
            // Live, the circuit is known only now: show the one saved from an earlier session there.
            useSavedOutline(state.model)
            startRecording(state, data)
            return@synchronized
        }
        state.model.add(topic, t, data)
        state.recorder?.add(topic, state.base + t, data)
        if (topic == "SessionStatus") {
            when ((data as? Map<*, *>)?.get("Status")) {
                "Ends", "Finalised" -> if (state.endedAt == null) state.endedAt = now()
                "Started", "Inactive" -> state.endedAt = null
            }
        }
    }

    /** Starts an empty live session, with time 0 at `utc` and a new recording, on the same connection. */
    private fun freshLive(state: LiveState, utc: Long) {
        state.recorder?.close()
        state.recorder = null
        state.model = SessionModel(bus, LIVE, emptyMap<String, Any?>(), utc.toDouble())
        setLiveBase(state, utc)
        state.model.register()
        state.following = true
        state.lastFlag = null
        state.endedAt = null
        state.outlineSaved = false
        state.video = null
        state.videoDriving = false
        state.outlineSaveTried = 0
        state.backfill = Backfill()
        useSession(state.model)
        clock.load(0.0, 0.0, 0.0, live = true)
        clock.play()
        updateMeta()
    }

    /**
     * Fills in the laps driven before RaceWall joined a live session, so lap history (and the
     * battles, lap times and pit window built on it) covers the whole race. The archive is asked
     * first, again every few minutes, as it may publish a session while it is still on; otherwise
     * RaceWall's own recording of the session from an earlier start is read once.
     */
    private suspend fun backfill(state: LiveState) {
        val bf = state.backfill
        val model = state.model
        val path = state.path
        try {
            if (path == null) return
            var laps: List<BackfillLap> = emptyList()
            if (!bf.archiveOff) {
                try {
                    val files = archive.fetchSessionFiles(path)
                    laps = lapsFromFiles(files.streams["TimingData"] ?: "", files.streams["RaceControlMessages"] ?: "", null)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: ArchiveSignInException) {
                    bf.archiveOff = true
                } catch (_: Exception) {
                    // Not there yet, most likely: the archive keeps a session's files back while it is on.
                }
                if (laps.isNotEmpty()) bf.done = true
            }
            if (laps.isEmpty() && !bf.recordingTried) {
                bf.recordingTried = true
                laps = recordedLaps(path)
            }
            if (bf.archiveOff && bf.recordingTried) bf.done = true
            if (live !== state || state.model !== model) return
            model.backfillLaps(laps)
        } finally {
            bf.running = false
            bf.next = now() + BACKFILL_RETRY_MS
        }
        tick()
    }

    /** Laps in RaceWall's own recording of a session, with their UTC times. */
    private fun recordedLaps(path: String): List<BackfillLap> {
        val root = recordings ?: return emptyList()
        val dir = File(root, path.split('/').filter { it.isNotEmpty() }.joinToString(File.separator))
        val base = runCatching {
            @Suppress("UNCHECKED_CAST")
            (FeedJson.parse(readFile(dir, "recording.json")) as? Map<String, Any?>)?.get("baseUtc")?.toString()?.let(::parseUtc)?.toDouble()
        }.getOrNull()
        return lapsFromFiles(readFile(dir, "TimingData.jsonStream"), readFile(dir, "RaceControlMessages.jsonStream"), base)
    }

    fun stopLive() {
        val l = live ?: return
        live = null
        l.client?.stop()
        l.recorder?.close()
        bus.removeSource(LIVE)
        session = null
        _ui.update { u -> u.copy(session = u.session?.takeIf { it.live == null }) }
    }

    /**
     * Sets the broadcast delay from one press: the user presses Sync when they see the leader cross
     * the line on TV. The feed saw that moment earlier; the difference is the delay.
     */
    fun syncDelay(): Long? {
        val l = live ?: return null
        val edge = liveNow(l)
        val crossing = l.model.lastChange("LapCount", edge) ?: return null
        if (edge - crossing > 5 * 60_000) return null
        setDelay((edge - crossing).toLong())
        l.following = true
        return delayMs
    }

    private fun updateMeta() {
        val l = live ?: return
        val s = l.model
        val info = LiveInfo(l.status, l.detail, l.following, if (l.videoDriving) 0 else delayMs, l.videoDriving)
        val meta = SessionMeta(s.info, if (s.start.isFinite()) s.start else 0.0, liveNow(l), s.startedAt, false, null, info)
        _ui.update { u -> if (u.session == meta) u else u.copy(session = meta) }
    }

    private fun startRecording(state: LiveState, info: Any?) {
        val root = recordings ?: return
        state.recorder?.let {
            it.writeInfo(info)
            return
        }
        val path = ((info as? Map<*, *>)?.get("Path") as? String)?.ifEmpty { null }
            ?: "live/${Instant.ofEpochMilli(state.base).toString().take(16).replace(Regex("[:T]"), "-")}"
        try {
            state.recorder = Recorder(File(root, path.split('/').filter { it.isNotEmpty() }.joinToString(File.separator)), state.base.toDouble()).also { it.writeInfo(info) }
        } catch (_: Exception) {
            // Not recording is better than not following.
        }
    }

    /** Sessions RaceWall recorded live, newest first. */
    fun listRecordings(): List<Recording> {
        val root = recordings ?: return emptyList()
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<Recording>()
        fun walk(dir: File, depth: Int) {
            if (depth > 4) return
            for (d in dir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
                val info = File(d, "SessionInfo.json")
                if (info.isFile) {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val raw = FeedJson.parse(stripBom(info.readText())) as Map<String, Any?>
                        @Suppress("UNCHECKED_CAST")
                        val meeting = ((raw["Meeting"] as? Map<String, Any?>)?.get("Name") as? String) ?: ""
                        out.add(Recording(RECORDING + d.relativeTo(root).invariantSeparatorsPath, meeting, (raw["Name"] as? String) ?: "", (raw["StartDate"] as? String) ?: ""))
                    } catch (_: Exception) {
                        // an unreadable recording is skipped
                    }
                } else walk(d, depth + 1)
            }
        }
        walk(root, 0)
        return out.sortedByDescending { it.startDate }
    }

    private fun recordingDir(path: String): File {
        val root = recordings ?: throw IOException("No recordings here.")
        val dir = File(root, path.removePrefix(RECORDING)).canonicalFile
        if (!dir.path.startsWith(root.canonicalPath + File.separator)) throw IOException("That recording is outside the recordings folder.")
        return dir
    }

    private fun readFile(dir: File, name: String): String = File(dir, name).takeIf { it.isFile }?.readText() ?: ""

    private fun readRecording(path: String): SessionFiles {
        val dir = recordingDir(path)
        return SessionFiles(readFile(dir, "SessionInfo.json"), STREAMS.associateWith { readFile(dir, "$it.jsonStream") })
    }
}
