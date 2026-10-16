package com.racewall.bus

/**
 * Values a source publishes for a topic, kept in time order. A source may publish history late
 * (one that downloads a whole session at once), so values can arrive out of order; each is slotted
 * into place.
 */
class ValueStore<T> {
    private val times = ArrayList<Double>()
    private val values = ArrayList<T>()

    val size: Int @Synchronized get() = times.size

    @Synchronized
    fun put(t: Double, value: T) {
        var lo = 0
        var hi = times.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) lo = mid + 1 else hi = mid
        }
        // A second value at the same moment replaces the first.
        if (lo > 0 && times[lo - 1] == t) {
            values[lo - 1] = value
            return
        }
        times.add(lo, t)
        values.add(lo, value)
    }

    /** The latest value at or before `t`. */
    @Synchronized
    fun at(t: Double): T? {
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
        return if (found < 0) null else values[found]
    }

    @Synchronized
    fun clear() {
        times.clear()
        values.clear()
    }
}
