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
    const val LAP_HISTORY = "timing.lapHistory"
    const val RACE_CONTROL = "race_control.messages"
    const val WEATHER = "weather.current"
    const val WEATHER_HISTORY = "weather.history"
    const val RADIO = "radio.clips"
    const val CAR_POSITION = "car.position"
    const val CAR_TELEMETRY = "car.telemetry"
    const val CIRCUIT = "circuit.outline"
    const val STANDINGS_DRIVERS = "standings.drivers"
    const val STANDINGS_TEAMS = "standings.teams"
    const val SCHEDULE = "schedule.season"

    /** Topics that need a large download (car positions and telemetry); loaded only when a widget reads them. */
    val HEAVY = setOf(CAR_POSITION, CAR_TELEMETRY, CIRCUIT)
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

data class LapTime(
    val lap: Int,
    /** As the feed writes it, "1:28.123". */
    val time: String,
    /** The same in milliseconds, or null when it could not be read. */
    val ms: Long?,
)

/** Every completed lap so far, per driver. */
typealias LapHistory = Map<String, List<LapTime>>

data class RaceControlMessage(
    /** UTC milliseconds. */
    val utc: Long,
    val lap: Int?,
    val category: String,
    val flag: String?,
    val message: String,
)

/** All messages so far, oldest first. */
typealias RaceControlMessages = List<RaceControlMessage>

data class Weather(
    val airTemp: Double,
    val trackTemp: Double,
    val humidity: Double,
    val pressure: Double,
    val rainfall: Boolean,
    /** Metres per second. */
    val windSpeed: Double,
    /** Degrees. */
    val windDirection: Double,
)

data class WeatherSample(
    /** Session time in ms. */
    val t: Double,
    val airTemp: Double,
    val trackTemp: Double,
    val rainfall: Boolean,
)

data class RadioClip(
    /** UTC milliseconds. */
    val utc: Long,
    val number: String,
    val url: String,
)

/** All clips so far, oldest first. */
typealias RadioClips = List<RadioClip>

/** Where a car is, in the feed's units (tenths of a metre). */
data class CarPosition(val x: Int, val y: Int, val z: Int, val onTrack: Boolean)

/** Keyed by racing number. */
typealias CarPositions = Map<String, CarPosition>

data class CarTelemetry(
    val speed: Int,
    val rpm: Int,
    val gear: Int,
    /** 0 to 100. */
    val throttle: Int,
    /** 0 or 100 in the feed. */
    val brake: Int,
    val drs: Int,
)

/** The latest telemetry sample of each car, keyed by racing number. */
typealias CarTelemetryMap = Map<String, CarTelemetry>

/** One telemetry sample in a range query: session time first. */
data class TelemetrySample(val t: Double, val speed: Int, val rpm: Int, val gear: Int, val throttle: Int, val brake: Int, val drs: Int)

data class Bounds(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double)

data class CircuitOutline(
    /** One lap of the racing line as x, y, z, in the same units as car positions. */
    val points: List<Triple<Double, Double, Double>>,
    val bounds: Bounds,
    /** The point where the start/finish line crosses, once enough cars have crossed it. */
    val start: Int? = null,
    /** The points where sector 1 and sector 2 end. */
    val sectors: Pair<Int, Int>? = null,
)

data class DriverStanding(val position: Int, val points: Double, val wins: Int, val code: String, val name: String, val team: String)

data class TeamStanding(val position: Int, val points: Double, val wins: Int, val name: String)

data class Standings<T>(
    val season: Int,
    /** The round the standings are after. */
    val round: Int,
    val rows: List<T>,
)

data class ScheduledSession(
    val meeting: String,
    val location: String,
    val country: String,
    val round: Int,
    val name: String,
    /** UTC milliseconds, or null when the time is not announced yet. */
    val start: Long?,
)

/** Every session of the season, in order. */
typealias Schedule = List<ScheduledSession>
