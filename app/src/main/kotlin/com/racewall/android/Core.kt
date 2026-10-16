package com.racewall.android

import com.racewall.archive.Archive
import com.racewall.archive.ArchiveMeeting
import com.racewall.archive.SessionModel
import com.racewall.archive.buildSession
import com.racewall.archive.endUtc
import com.racewall.bus.Bus
import com.racewall.clock.Clock
import com.racewall.clock.ClockState
import com.racewall.topics.DriverList
import com.racewall.topics.Laps
import com.racewall.topics.Positions
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
    val info: SessionInfo? = null,
    val status: SessionStatus? = null,
    val drivers: DriverList = emptyMap(),
    val positions: Positions = emptyList(),
    val laps: Laps = emptyMap(),
    val stints: Stints = emptyMap(),
    /** The data sources that answered, in the user's order. */
    val sources: List<String> = emptyList(),
)

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
enum class Mode { IDLE, REPLAY }

/**
 * The app's core, as the desktop's main-process `Core` and the window state together: the bus, the
 * clock, the loaded session, and the frames the widgets draw from.
 */
class Core(
    private val archive: Archive,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val bus = Bus()
    private val clock = Clock()
    private var session: SessionModel? = null

    private val _frame = MutableStateFlow(Frame())
    val frame: StateFlow<Frame> = _frame.asStateFlow()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var ticker: Job? = null

    init {
        bus.setOrder(listOf("f1-live", "f1-archive", "openf1", "jolpica"))
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

    /** Builds the frame for the clock's moment now. */
    fun tick() {
        val state = clock.state()
        val t = state.time
        val sources = LinkedHashSet<String>()
        fun <T> read(topic: String): T? {
            val a = bus.answer(topic, t) ?: return null
            sources.add(a.source)
            @Suppress("UNCHECKED_CAST")
            return a.value as T
        }
        val frame = Frame(
            clock = state,
            utc = session?.utcAt(t),
            info = read(Topic.SESSION_INFO),
            status = read(Topic.SESSION_STATUS),
            drivers = read(Topic.DRIVERS_LIST) ?: emptyMap(),
            positions = read(Topic.TIMING_POSITIONS) ?: emptyList(),
            laps = read(Topic.TIMING_LAPS) ?: emptyMap(),
            stints = read(Topic.TIMING_STINTS) ?: emptyMap(),
            sources = sources.toList(),
        )
        if (frame != _frame.value) _frame.value = frame
    }

    // ---------------------------------------------------------------- clock

    fun toggle() = clock.toggle().also { tick() }
    fun play() = clock.play().also { tick() }
    fun seek(t: Double) = clock.seek(t).also { tick() }
    fun seekBy(ms: Double) = clock.seekBy(ms).also { tick() }
    fun setSpeed(speed: Double) = clock.setSpeed(speed).also { tick() }

    fun focusDriver(number: String) = _ui.update { it.copy(focusDriver = if (it.focusDriver == number) null else number) }

    // ---------------------------------------------------------------- replay

    suspend fun listSeason(year: Int): List<ArchiveMeeting> = archive.fetchSeason(year)

    private suspend fun load(path: String, lastResult: Boolean): SessionMeta {
        val files = archive.fetchSessionFiles(path)
        return withContext(Dispatchers.Default) {
            val s = buildSession(files, bus)
            session = s
            val at = if (lastResult) s.end else maxOf(s.start, (s.startedAt ?: s.start) - LEAD_IN_MS)
            clock.load(s.start, s.end, at)
            SessionMeta(s.info, s.start, s.end, s.startedAt, lastResult, path)
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
            val meta = task()
            _ui.update { it.copy(session = meta ?: it.session) }
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
