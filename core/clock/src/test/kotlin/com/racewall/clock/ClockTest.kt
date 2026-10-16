package com.racewall.clock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ported from the desktop's packages/clock/test/clock.test.ts. */
class ClockTest {
    private class Fake {
        var wall = 1000.0
        val clock = Clock { wall }
        fun advance(ms: Double) {
            wall += ms
        }
    }

    @Test
    fun `starts paused at the start of the range`() {
        val f = Fake()
        f.clock.load(100.0, 1000.0)
        f.advance(500.0)
        assertEquals(100.0, f.clock.time)
        assertFalse(f.clock.playing)
    }

    @Test
    fun `moves with the wall clock times the speed while playing`() {
        val f = Fake()
        f.clock.load(0.0, 100_000.0)
        f.clock.play()
        f.advance(1000.0)
        assertEquals(1000.0, f.clock.time)
        f.clock.setSpeed(4.0)
        f.advance(1000.0)
        assertEquals(5000.0, f.clock.time)
        f.clock.setSpeed(0.5)
        f.advance(1000.0)
        assertEquals(5500.0, f.clock.time)
    }

    @Test
    fun `holds still while paused, and resumes from where it stopped`() {
        val f = Fake()
        f.clock.load(0.0, 100_000.0)
        f.clock.play()
        f.advance(2000.0)
        f.clock.pause()
        f.advance(5000.0)
        assertEquals(2000.0, f.clock.time)
        f.clock.play()
        f.advance(1000.0)
        assertEquals(3000.0, f.clock.time)
    }

    @Test
    fun `keeps seeks inside the range, and keeps playing after a seek`() {
        val f = Fake()
        f.clock.load(1000.0, 9000.0)
        f.clock.seek(-50.0)
        assertEquals(1000.0, f.clock.time)
        f.clock.seek(99_999.0)
        assertEquals(9000.0, f.clock.time)
        f.clock.seek(5000.0)
        f.clock.play()
        f.advance(100.0)
        f.clock.seekBy(-1000.0)
        f.advance(100.0)
        assertEquals(4200.0, f.clock.time)
        assertTrue(f.clock.playing)
    }

    @Test
    fun `stops at the end, and play from the end starts again from the start`() {
        val f = Fake()
        f.clock.load(0.0, 1000.0)
        f.clock.play()
        f.advance(5000.0)
        assertEquals(1000.0, f.clock.time)
        assertFalse(f.clock.playing)
        f.clock.play()
        f.advance(10.0)
        assertEquals(10.0, f.clock.time)
    }

    @Test
    fun `keeps playing when a live session's end moves later`() {
        val f = Fake()
        f.clock.load(0.0, 1000.0)
        f.clock.play()
        f.advance(800.0)
        f.clock.extend(5000.0)
        f.advance(1000.0)
        assertEquals(1800.0, f.clock.time)
        assertTrue(f.clock.playing)
        f.clock.extend(10.0)
        assertEquals(5000.0, f.clock.state().end)
    }
}
