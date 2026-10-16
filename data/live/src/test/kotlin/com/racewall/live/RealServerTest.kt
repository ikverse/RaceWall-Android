package com.racewall.live

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Connects to F1's real live timing server, which between sessions answers with the last session's
 * state. Run with RACEWALL_NETWORK_TESTS=1; skipped otherwise, so the normal tests stay offline.
 */
class RealServerTest {
    @Test
    fun `connects to livetiming formula1 com and receives the last session's state`() {
        assumeTrue(System.getenv("RACEWALL_NETWORK_TESTS") == "1")
        val snapshot = ConcurrentHashMap<String, Any?>()
        val statuses = CopyOnWriteArrayList<String>()
        val client = LiveClient(object : LiveHandlers {
            override fun snapshot(topic: String, data: Any?) {
                snapshot[topic] = data
            }
            override fun message(topic: String, data: Any?, utc: Long) {}
            override fun status(status: LiveStatus, detail: String) {
                statuses.add("$status $detail".trim())
            }
        })
        runBlocking { client.start() }
        val until = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < until && !snapshot.containsKey("SessionInfo")) Thread.sleep(200)
        client.stop()
        println("statuses: $statuses")
        println("snapshot topics: ${snapshot.keys.sorted()}")
        @Suppress("UNCHECKED_CAST")
        val info = snapshot["SessionInfo"] as? Map<String, Any?>
        println("session: ${(info?.get("Meeting") as? Map<String, Any?>)?.get("Name")} ${info?.get("Name")} ${info?.get("Path")}")
        assertTrue("CONNECTED" in statuses, "$statuses")
        assertTrue(snapshot.containsKey("SessionInfo") && snapshot.containsKey("TimingData") && snapshot.containsKey("DriverList"), "${snapshot.keys}")
    }
}
