package com.racewall.topics

/**
 * The standard RaceWall topics. A data source publishes these; a widget reads them. Values are
 * kept as the official feed writes them (gaps like "+1.234" or "1L", times like "1:28.123"), so a
 * widget shows exactly what the timing screens show.
 */
object Topic {
    const val SESSION_INFO = "session.info"
    const val SESSION_STATUS = "session.status"
    const val DRIVERS_LIST = "drivers.list"
    const val TIMING_POSITIONS = "timing.positions"
    const val TIMING_LAPS = "timing.laps"
    const val TIMING_STINTS = "timing.stints"
}

data class SessionInfo(
    /** The session's key in F1's systems; OpenF1 uses the same number. */
    val key: Int,
    /** The round of the championship, 0 for testing. */
    val round: Int,
    val year: Int,
    val meeting: String,
    /** "Race", "Qualifying", "Practice 1", "Sprint", ... */
    val name: String,
    val type: String,
    val circuit: String,
    val country: String,
    /** Scheduled start, ISO 8601 with the circuit's offset. */
    val startDate: String,
    val gmtOffset: String,
    /** The archive path, "2025/2025-07-06_British_Grand_Prix/2025-07-06_Race/". */
    val path: String,
)

enum class TrackFlag(val wire: String) { GREEN("green"), YELLOW("yellow"), SC("sc"), RED("red"), VSC("vsc"), VSC_ENDING("vsc-ending"), UNKNOWN("unknown") }

enum class SessionState(val wire: String) { INACTIVE("inactive"), STARTED("started"), ABORTED("aborted"), FINISHED("finished"), FINALISED("finalised"), ENDED("ended") }

data class SessionStatus(
    val state: SessionState,
    val flag: TrackFlag,
    /** Present in races and sprints only. */
    val lap: Int? = null,
    val totalLaps: Int? = null,
)

data class Driver(
    val number: String,
    val code: String,
    val firstName: String,
    val lastName: String,
    val team: String,
    /** "#RRGGBB", or null when the feed gives none. */
    val teamColour: String?,
)

/** Keyed by racing number. */
typealias DriverList = Map<String, Driver>

data class PositionRow(
    val number: String,
    val position: Int,
    /** Gap to the leader as the feed writes it; the leader's own row says "LAP 21" in a race. */
    val gap: String,
    /** Gap to the car ahead. */
    val interval: String,
    val inPit: Boolean,
    val pitOut: Boolean,
    val retired: Boolean,
    val stopped: Boolean,
    val knockedOut: Boolean,
    val laps: Int?,
)

/** Running order, first row is the leader. */
typealias Positions = List<PositionRow>

data class TimedValue(
    val value: String,
    /** Fastest of anyone in the session so far: purple on timing screens. */
    val overallFastest: Boolean,
    /** This driver's own best so far: green. */
    val personalFastest: Boolean,
)

data class LapRow(
    val number: String,
    val lastLap: TimedValue,
    val bestLap: String,
    /** The current lap's sectors; a sector reads "" until it is completed. */
    val sectors: List<TimedValue>,
)

typealias Laps = Map<String, LapRow>

enum class Compound { SOFT, MEDIUM, HARD, INTERMEDIATE, WET, UNKNOWN }

data class Stint(
    val compound: Compound,
    val isNew: Boolean,
    /** Laps on this set of tyres, including laps run before this stint. */
    val tyreAge: Int,
    val startLap: Int,
)

typealias Stints = Map<String, List<Stint>>
