package com.racewall.bus

/**
 * A time-ordered list of changes, and the state they add up to at any moment.
 *
 * Changes are stored, not full states, so a whole race fits in memory. Every `keyframeEvery`
 * changes a full copy of the state is kept, so jumping anywhere in the session replays at most
 * that many changes. Moving forward a little from the last answer replays only the changes in
 * between, which is the common case while a session plays.
 */
class Timeline<S, D>(
    private val initial: () -> S,
    /** Applies one change; may change `state` in place and must return the new state. */
    private val apply: (S, D) -> S,
    private val keyframeEvery: Int = 200,
    /** A full copy of a state. */
    private val clone: (S) -> S = { @Suppress("UNCHECKED_CAST") (deepClone(it) as S) },
    /**
     * How a keyframe is kept, and how a fresh state comes back from it. By default a copy; a
     * timeline of feed JSON can keep compact text instead, which takes far less memory.
     */
    private val pack: (S) -> Any? = clone,
    @Suppress("UNCHECKED_CAST")
    private val unpack: (Any?) -> S = { clone(it as S) },
) {
    private class Frame<S>(val index: Int, val state: S)

    private val times = ArrayList<Double>()
    private val deltas = ArrayList<D>()
    /** packed state after applying deltas[0..index] */
    private val keyframes = ArrayList<Frame<Any?>>()
    private var head: S = initial()
    private var cache: Frame<S>? = null

    val length: Int get() = times.size

    /** Time of the first change, or null when empty. */
    val start: Double? get() = times.firstOrNull()

    /** Time of the last change, or null when empty. */
    val end: Double? get() = times.lastOrNull()

    /** Adds a change. Times must not go backwards. */
    @Synchronized
    fun append(t: Double, delta: D) {
        val last = end
        require(last == null || t >= last) { "Timeline: time $t is before $last" }
        times.add(t)
        deltas.add(delta)
        head = apply(head, delta)
        val index = times.size - 1
        if (index % keyframeEvery == 0) keyframes.add(Frame(index, pack(head)))
    }

    /** Index of the last change at or before `t`, or -1. */
    @Synchronized
    fun indexAt(t: Double): Int {
        var lo = 0
        var hi = times.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) {
                found = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        return found
    }

    /** Time of every change between `from` (exclusive) and `to` (inclusive). */
    @Synchronized
    fun timesBetween(from: Double, to: Double): List<Double> {
        val a = indexAt(from) + 1
        val b = indexAt(to)
        return if (b >= a) times.subList(a, b + 1).toList() else emptyList()
    }

    /**
     * The state at time `t`. The returned object belongs to the timeline: read it, do not change
     * it, and do not keep it past the next call.
     */
    @Synchronized
    fun stateAt(t: Double): S {
        val index = indexAt(t)
        if (index < 0) return initial()
        cache?.let { if (it.index == index) return it.state }

        val nearest = keyframes[index / keyframeEvery]
        val c = cache
        val from = if (c != null && c.index < index && c.index >= nearest.index) c else Frame(nearest.index, unpack(nearest.state))
        var state = from.state
        for (i in from.index + 1..index) state = apply(state, deltas[i])
        cache = Frame(index, state)
        return state
    }
}
