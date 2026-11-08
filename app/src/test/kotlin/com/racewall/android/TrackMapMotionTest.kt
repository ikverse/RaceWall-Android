package com.racewall.android

import com.racewall.android.widgets.LAG_MS
import com.racewall.android.widgets.Motion
import com.racewall.android.widgets.Trace
import com.racewall.clock.ClockState
import com.racewall.topics.Bounds
import com.racewall.topics.CarPosition
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Ported from the desktop's plugins/track-map/test/motion.test.ts. */
class TrackMapMotionTest {
    private fun car(x: Int, y: Int = 0) = CarPosition(x, y, 0, true)

    @Test
    fun `draws the cars a little behind the clock, between the frames around that moment`() {
        val m = Motion()
        m.remember(mapOf("1" to car(0)), 1000.0)
        m.remember(mapOf("1" to car(100)), 1100.0)
        val t = m.displayTime(ClockState(1100.0, true, 1.0, 0.0, 10_000.0), 5_000_000_000L)
        assertEquals(1100.0 - LAG_MS, t)
        assertEquals(50.0, m.positionAt("1", 1050.0, car(0)).x)
    }

    @Test
    fun `keeps the recent positions when live nudges the clock back a little, so the cars do not snap`() {
        val m = Motion()
        for (t in 1000..1500 step 100) m.remember(mapOf("1" to car(t - 1000)), t.toDouble())
        m.remember(mapOf("1" to car(320)), 1300.0)
        assertEquals(listOf(1000.0, 1100.0, 1200.0, 1300.0), m.history.getValue("1").map { it.t })
        assertEquals(150.0, m.positionAt("1", 1150.0, car(0)).x)
    }

    @Test
    fun `starts again after a real seek`() {
        val m = Motion()
        m.remember(mapOf("1" to car(0)), 10_000.0)
        m.remember(mapOf("1" to car(9)), 10_100.0)
        m.remember(mapOf("1" to car(500)), 60_000.0)
        assertEquals(listOf(60_000.0), m.history.getValue("1").map { it.t })
        m.remember(mapOf("1" to car(7)), 5000.0)
        assertEquals(listOf(5000.0), m.history.getValue("1").map { it.t })
    }

    @Test
    fun `keeps a sample every half second per car, and an area that only grows`() {
        val tr = Trace()
        tr.reset("a")
        for (t in 0..2000 step 100) tr.add(mapOf("1" to car(t + 10, -t - 10), "2" to CarPosition(0, 0, 0, false)), t.toDouble())
        assertEquals(listOf(0.0, 500.0, 1000.0, 1500.0, 2000.0), tr.cars.getValue("1").map { it.t })
        assertFalse(tr.cars.containsKey("2"))
        assertEquals(Bounds(10.0, 2010.0, -2010.0, -10.0), tr.bounds)
        tr.reset("a")
        assertNotNull(tr.bounds)
        tr.reset("b")
        assertNull(tr.bounds)
        assertEquals(0, tr.cars.size)
    }
}
