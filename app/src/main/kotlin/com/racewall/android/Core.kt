package com.racewall.android

import com.racewall.analysis.Battles
import com.racewall.analysis.PitWindow
import com.racewall.analysis.RaceTopics
import com.racewall.archive.Archive
import com.racewall.archive.ArchiveMeeting
import com.racewall.archive.SOURCE as ARCHIVE
import com.racewall.archive.SessionModel
import com.racewall.archive.buildSession
import com.racewall.archive.endUtc
import com.racewall.bus.Bus
import com.racewall.bus.TopicProvider
import com.racewall.clock.Clock
import com.racewall.clock.ClockState
import com.racewall.clock.SPEEDS
import com.racewall.jolpica.Jolpica
import com.racewall.openf1.OpenF1
import com.racewall.topics.DriverList
import com.racewall.topics.LapHistory
import com.racewall.topics.Laps
import com.racewall.topics.Positions
import com.racewall.topics.RaceControlMessages
import com.racewall.topics.SessionInfo
import com.racewall.topics.SessionStatus
import com.racewall.topics.Stints
import com.racewall.topics.Topic
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
import java.time.Instant
import java.time.ZoneOffset

/** How often the window is sent the moment's data, as the desktop's FRAME_MS. */
const val FRAME_MS = 100L

/** A replay opens this long before the session started, so the start is not missed. */
private const val LEAD_IN_MS = 60_000.0

/** What the battles and pit-window analyses publish, as on the desktop. */
const val BATTLES_TOPIC = "racewall.battles.list"
const val PIT_WINDOW_TOPIC = "racewall.pit-window.estimate"

/** What the loaded session is, as the desktop's SessionMeta. */
data class SessionMeta(
    val info: SessionInfo,
    val start: Double,
    val end: Double,
    val startedAt: Double?,
    /** Loaded on its own at start-up, at its end: the window shows it as the last result. */
    val lastResult: Boolean,
    val path: String,
)

/** The moment's data for the widgets: what the desktop sends the window ten times a second. */
data class Frame(
    val clock: ClockState = ClockState(0.0, false, 1.0, 0.0, 0.0),
    /** UTC milliseconds at the playhead, or null when the session has no UTC anchor. */
    val utc: Double? = null,
    /** The subscribed topics that have a value now. */
    val topics: Map<String, Any> = emptyMap(),
    /** The data sources that answered, in the user's order. */
    val sources: List<String> = emptyList(),
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
)

/** The widgets' mode, as the desktop's `mode()`. */
enum class Mode { IDLE, REPLAY, LIVE }

/**
 * The app's core, as the desktop's main-process `Core` and the window state together: the bus, the
 * clock, the loaded session, the data sources, and the frames the widgets draw from.
 */
class Core(
    private val archive: Archive,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** Downloads from OpenF1 and Jolpica; tests pass fakes, or null to leave them out. */
    private val openF1: (suspend (Bus, List<String>, Int, (Double) -> Double) -> List<String>)? = { bus, topics, key, utcToT -> OpenF1.provide(bus, topics, key, utcToT) },
    private val jolpica: (suspend (Bus, Int, Int?, Int) -> List<String>)? = { bus, season, round, schedule -> Jolpica.provide(bus, season, round, schedule) },
) {
    val bus = Bus()
    private val clock = Clock()
    private var session: SessionModel? = null
    private var order: List<String> = listOf("f1-live", "f1-archive", "openf1", "jolpica")
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

    fun mode(): Mode = _ui.value.session.let { if (it == null || it.lastResult) Mode.IDLE else Mode.REPLAY }

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
        scope.launch { ensureBackups() }
        tick()
    }

    /** Builds the frame for the clock's moment now. */
    fun tick() {
        val state = clock.state()
        val t = state.time
        val sources = LinkedHashSet<String>()
        val topics = LinkedHashMap<String, Any>()
        for (name in subscribed) {
            val a = bus.answer(name, t) ?: continue
            sources.add(a.source)
            topics[name] = a.value
        }
        val frame = Frame(state, session?.utcAt(t), topics, order.filter { it in sources } + sources.filter { it !in order })
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

    /** The desktop runs these as analysis plugins; here they are worked out when asked. */
    private fun registerAnalysis() {
        bus.provide(BATTLES_TOPIC, TopicProvider("racewall.battles", { t -> if (session == null) null else Battles.find(raceTopics(t)) }))
        bus.provide(PIT_WINDOW_TOPIC, TopicProvider("racewall.pit-window", { t -> if (session == null) null else PitWindow.estimate(raceTopics(t)) }))
    }

    // ---------------------------------------------------------------- clock

    private fun command(block: () -> Unit) {
        block()
        _ui.update { u -> u.copy(session = u.session?.copy(lastResult = false)) }
        tick()
    }

    fun toggle() = command { clock.toggle() }
    fun play() = command { clock.play() }
    fun pause() = command { clock.pause() }
    fun seek(t: Double) = command { clock.seek(t) }
    fun seekBy(ms: Double) = command { clock.seekBy(ms) }
    fun setSpeed(speed: Double) = command { if (speed in SPEEDS) clock.setSpeed(speed) }

    fun focusDriver(number: String) = _ui.update { it.copy(focusDriver = if (it.focusDriver == number) null else number) }
    fun setFocus(number: String?) = _ui.update { it.copy(focusDriver = number) }

    /** Range queries, such as telemetry over the last minute. */
    fun range(topic: String, from: Double, to: Double, filter: List<String>? = null): Any? = bus.range(topic, from, to, filter)

    private fun say(text: String) = _ui.update { it.copy(notice = text) }

    // ---------------------------------------------------------------- replay

    suspend fun listSeason(year: Int): List<ArchiveMeeting> = archive.fetchSeason(year)

    private suspend fun load(path: String, lastResult: Boolean): SessionMeta {
        val files = archive.fetchSessionFiles(path)
        val meta = withContext(Dispatchers.Default) {
            val s = buildSession(files, bus)
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
        bus.removeSource(OpenF1.SOURCE)
        cars = "none"
        backups.clear()
        bus.setOrder(order)
        scope.launch { loadJolpica(s.info) }
    }

    private suspend fun loadJolpica(info: SessionInfo) {
        val provide = jolpica ?: return
        val year = Instant.ofEpochMilli(now()).atZone(ZoneOffset.UTC).year
        runCatching { provide(bus, if (info.year != 0) info.year else year, info.round.takeIf { it != 0 }, year) }
        tick()
    }

    /** Downloads car positions and telemetry the first time a widget needs them. */
    private suspend fun ensureCars() {
        val s = session ?: return
        val path = _ui.value.session?.path ?: return
        if (cars != "none" || subscribed.none { it in Topic.HEAVY }) return
        cars = "loading"
        say("Loading car positions and telemetry…")
        try {
            val files = archive.fetchCarFiles(path)
            if (session !== s) return
            withContext(Dispatchers.Default) { s.attachCars(files) }
            cars = "loaded"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            cars = "failed"
        } finally {
            say("")
            tick()
        }
    }

    /**
     * Asks OpenF1 for topics the archive could not give this session, or for all its topics when the
     * user put OpenF1 ahead of the archive.
     */
    private suspend fun ensureBackups() {
        val s = session ?: return
        val provide = openF1 ?: return
        if (s.info.key == 0) return
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

    /** The most recent race or sprint in the archive that has finished, loaded at its end. */
    private suspend fun lastResult(): SessionMeta? {
        val time = now()
        val year = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC).year
        for (y in listOf(year, year - 1)) {
            val sessions = listSeason(y)
                .flatMap { it.sessions }
                .filter { (it.type == "Race" || it.name == "Sprint") && endUtc(it.endDate, it.gmtOffset) < time - 30 * 60_000 }
                .sortedByDescending { endUtc(it.endDate, it.gmtOffset) }
            if (sessions.isNotEmpty()) return load(sessions[0].path, true)
        }
        return null
    }

    private suspend fun run(label: String, task: suspend () -> SessionMeta?) {
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

    suspend fun loadLastResult() = run("Loading the last result…") { lastResult() }

    /** Nothing could load before, because the archive wanted a sign-in: try the last result again. */
    suspend fun afterSignIn() {
        val ui = _ui.value
        if (ui.session == null && ui.error.contains("needs an F1 TV sign-in")) loadLastResult()
    }
}
