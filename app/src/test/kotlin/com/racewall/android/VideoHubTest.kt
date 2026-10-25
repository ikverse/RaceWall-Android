package com.racewall.android

import com.racewall.android.video.PlayerHandle
import com.racewall.android.video.VideoHub
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from the desktop's app/ui/src/video.test.ts, with the bottom bar's control of the players. */
class VideoHubTest {
    private var session = 9
    private val hub = VideoHub({ session }, { 5_000.0 })

    private class Fake(paused: Boolean = false, override val live: Boolean = false, time: Double = 100.0, val end: Double = 1000.0, override val loaded: Boolean = true) : PlayerHandle {
        override var paused = paused
        override val ended = false
        override var currentTime = time
        var sought: Double? = null
        override fun play() {
            paused = false
        }
        override fun pause() {
            paused = true
        }
        override val contentId: Long = 1
        override fun sessionTime(): Double? = null
        override fun seekSession(t: Double): Boolean {
            sought = t
            return true
        }
        override fun seekRange() = 0.0..end
    }

    @Test
    fun `carries the leader's state and the sound to the players of the new layout`() {
        hub.register("a", Fake())
        hub.register("b", Fake())
        hub.audible = "a"
        hub.unregister("a")
        hub.unregister("b")
        assertNull(hub.main)
        assertNull(hub.audible)
        hub.register("c", Fake())
        hub.register("d", Fake())
        assertEquals("c", hub.main)
        // A follower reads the handoff; the sound goes to the main player.
        val seen = hub.takeHandoff("d")!!
        assertTrue(seen.playing)
        assertNull(seen.behind)
        assertEquals("c", hub.audible)
        // The leader takes it, and it is gone after that.
        assertTrue(hub.takeHandoff("c")!!.playing)
        assertNull(hub.takeHandoff("c"))
        assertNull(hub.takeHandoff("d"))
    }

    @Test
    fun `remembers whether the leader was paused, and how far behind live`() {
        hub.register("a", Fake(paused = true, live = true, time = 940.0, end = 1000.0))
        hub.unregister("a")
        hub.register("c", Fake())
        val h = hub.takeHandoff("c")!!
        assertFalse(h.playing)
        assertEquals(60.0, h.behind)
    }

    @Test
    fun `is not for a newcomer while a player from before is still here`() {
        hub.register("a", Fake())
        hub.register("b", Fake())
        hub.unregister("a")
        hub.register("c", Fake())
        assertNull(hub.takeHandoff("c"))
        assertNull(hub.takeHandoff("b"))
    }

    @Test
    fun `is for a newcomer that registered before the old players left, while it was still loading`() {
        hub.register("a", Fake())
        hub.register("c", Fake(loaded = false))
        hub.unregister("a")
        assertTrue(hub.takeHandoff("c")!!.playing)
    }

    @Test
    fun `is dropped when the session changes`() {
        hub.register("a", Fake())
        hub.unregister("a")
        session = 10
        hub.register("c", Fake())
        assertNull(hub.takeHandoff("c"))
    }

    @Test
    fun `carries the sound from a follower that had it`() {
        hub.register("a", Fake())
        hub.register("b", Fake())
        hub.audible = "b"
        hub.unregister("b")
        hub.unregister("a")
        hub.register("c", Fake())
        val h = hub.takeHandoff("c")!!
        assertTrue(h.playing)
        assertFalse(h.audible)
        assertEquals("c", hub.audible)
    }

    @Test
    fun `records nothing when a follower without sound leaves`() {
        hub.register("a", Fake())
        hub.register("b", Fake())
        hub.unregister("b")
        hub.unregister("a")
        hub.register("c", Fake())
        assertTrue(hub.takeHandoff("c")!!.playing)
    }

    @Test
    fun `a player moved on its own stops following until play or pause below brings it back to the clock`() {
        val a = Fake(paused = true)
        hub.register("a", a)
        hub.driving = true
        hub.goAlone("a")
        assertNull(hub.leaderId())
        assertFalse(hub.driving)
        assertTrue(hub.control(VideoHub.Command.Toggle))
        assertEquals(emptyList(), hub.alone)
        assertEquals(5_000.0, a.sought, "the rejoining player moves to where the bottom bar is")
        assertFalse(a.paused)
    }

    @Test
    fun `the bottom bar acts on the clock when no player drives it`() {
        hub.register("a", Fake())
        assertFalse(hub.control(VideoHub.Command.SeekBy(5_000.0)))
        hub.driving = true
        assertTrue(hub.control(VideoHub.Command.SeekBy(5_000.0)))
    }

    @Test
    fun `an onboard player follows and never leads, even alone or when the main player leaves`() {
        hub.register("onboard", Fake(), canLead = false)
        assertNull(hub.main)
        assertNull(hub.leaderId())
        hub.register("tv", Fake())
        assertEquals("tv", hub.main)
        assertEquals("tv", hub.leaderId())
        hub.unregister("tv")
        assertNull(hub.main)
        assertNull(hub.leaderId())
    }

    @Test
    fun `leaving the app pauses every player, and nothing plays again by itself`() {
        val a = Fake()
        val b = Fake()
        val c = Fake(paused = true)
        hub.register("a", a)
        hub.register("b", b)
        hub.register("onboard", c, canLead = false)
        hub.goAlone("b")
        hub.pauseAll()
        assertTrue(a.paused && b.paused && c.paused)
    }

    @Test
    fun `the bottom bar's video quality overrides each player's own`() {
        assertEquals("720", com.racewall.android.state.videoQuality("player", "720"))
        assertEquals("360", com.racewall.android.state.videoQuality("360", "720"))
        assertEquals("auto", com.racewall.android.state.videoQuality("auto", "540"))
        // Something unknown in the settings file leaves the players to their own.
        assertEquals("540", com.racewall.android.state.videoQuality("4k", "540"))
    }
}
