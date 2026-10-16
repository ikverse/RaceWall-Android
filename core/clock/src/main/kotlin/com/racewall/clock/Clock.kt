package com.racewall.clock

/** The replay speeds offered. */
val SPEEDS = listOf(0.5, 1.0, 2.0, 4.0, 8.0, 16.0)

data class ClockState(
    val time: Double,
    val playing: Boolean,
    val speed: Double,
    val start: Double,
    val end: Double,
)

/**
 * The replay clock. Session time is in milliseconds from the start of the session's data. It
 * moves with the wall clock times the speed while playing, and stays put while paused or after
 * reaching the end.
 */
class Clock(
    /** Wall-clock milliseconds; tests pass a fake one. */
    private val now: () -> Double = { System.nanoTime() / 1e6 },
) {
    private var anchorTime = 0.0
    private var anchorWall = 0.0
    private var _playing = false
    private var _speed = 1.0
    private var _start = 0.0
    private var _end = 0.0

    /** Sets the playable range and puts the playhead at `at` (default: start), paused. */
    @Synchronized
    fun load(start: Double, end: Double, at: Double = start) {
        _start = start
        _end = maxOf(start, end)
        _playing = false
        anchor(clamp(at))
    }

    val time: Double
        @Synchronized get() {
            if (!_playing) return anchorTime
            val t = anchorTime + (now() - anchorWall) * _speed
            if (t >= _end) {
                _playing = false
                anchor(_end)
                return _end
            }
            return t
        }

    val playing: Boolean
        @Synchronized get() {
            time // reaching the end stops play
            return _playing
        }

    val speed: Double @Synchronized get() = _speed

    @Synchronized
    fun play() {
        if (_playing) return
        if (anchorTime >= _end) anchor(_start) else anchor(anchorTime)
        _playing = true
    }

    @Synchronized
    fun pause() {
        if (!_playing) return
        anchor(time)
        _playing = false
    }

    @Synchronized
    fun toggle() {
        if (playing) pause() else play()
    }

    @Synchronized
    fun seek(t: Double) = anchor(clamp(t))

    @Synchronized
    fun seekBy(ms: Double) = seek(time + ms)

    /**
     * Moves the end of the playable range later, as a live session grows. Keeps the playhead and
     * whether it is playing.
     */
    @Synchronized
    fun extend(end: Double) {
        if (end <= _end) return
        val t = time
        val wasPlaying = _playing
        _end = end
        anchor(t)
        _playing = wasPlaying
    }

    @Synchronized
    fun setSpeed(speed: Double) {
        anchor(time)
        _speed = speed
    }

    @Synchronized
    fun state(): ClockState {
        val time = time
        return ClockState(time, _playing, _speed, _start, _end)
    }

    private fun anchor(t: Double) {
        anchorTime = t
        anchorWall = now()
    }

    private fun clamp(t: Double): Double = minOf(_end, maxOf(_start, t))
}
