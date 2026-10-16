package com.racewall.f1tv

import com.racewall.archive.Fetcher
import com.racewall.archive.HttpResult
import com.racewall.bus.FeedJson
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ported from the desktop's packages/f1tv/test/f1tv.test.ts, with its real responses saved 2026-10-11. */
class F1TvTest {
    private fun text(f: String) = F1TvTest::class.java.getResource("/$f")!!.readText()
    private fun fixture(f: String) = FeedJson.parse(text(f))

    @Test
    fun `finds the full race replay for a session key`() {
        val videos = F1Tv.videosIn(fixture("search-9947.json"))
        val replay = videos.first { it.kind == "REPLAY" }
        assertEquals(1000009065L, replay.contentId)
        assertEquals("2025 British Grand Prix", replay.title)
        assertEquals("9947", replay.sessionKey)
        assertEquals("Access", replay.entitlement)
        assertEquals(8, videos.size)
    }

    @Test
    fun `puts the replay first when searching by session`() = runTest {
        val videos = F1Tv.videosForSession(9947, Fetcher { _, _ -> HttpResult(200, text("search-9947.json")) })
        assertEquals(1000009065L, videos[0].contentId)
    }

    @Test
    fun `lists every channel - main feed, extra feeds, and an onboard per driver`() {
        val d = F1Tv.parseDetails(fixture("details-1000009065.json"))!!
        assertEquals(7804L, d.duration)
        assertEquals(F1Tv.Channel(null, "main", "Main feed", null, null), d.channels[0])
        assertEquals(F1Tv.Channel(1016, "obc", "Lando Norris", "4", "#F47600"), d.channels.first { it.racingNumber == "4" })
        assertEquals(20, d.channels.count { it.type == "obc" })
        assertTrue(d.channels.any { it.type == "additional" && it.name == "F1 Live" })
    }

    @Test
    fun `sends the subscription token and reads the stream and licence details`() = runTest {
        var asked = ""
        var sent = emptyMap<String, String>()
        val fetch = Fetcher { url, headers ->
            asked = url
            sent = headers
            HttpResult(200, """{"resultCode":"OK","resultObj":{"url":"https://cdn.example/m.mpd","streamType":"DASHWV","entitlementToken":"ent","laURL":"https://lic.example/wv","drmType":"widevine"}}""")
        }
        val info = F1Tv.play(1000009065, 1016, "jwt", fetch)
        assertEquals("https://f1tv.formula1.com/2.0/R/ENG/WEB_DASH/ALL/CONTENT/PLAY?contentId=1000009065&channelId=1016", asked)
        assertEquals(mapOf("ascendontoken" to "jwt"), sent)
        assertEquals(F1Tv.PlayInfo("https://cdn.example/m.mpd", "DASHWV", "ent", "https://lic.example/wv", "widevine"), info)
    }

    @Test
    fun `explains F1 TV's refusal`() {
        val e = assertFailsWith<IOException> { F1Tv.parsePlay(FeedJson.parse("""{"resultCode":"KO","message":"Entitlement not found"}""")) }
        assertEquals("Entitlement not found", e.message)
    }

    @Test
    fun `only F1 TV's own licence address may receive the token`() {
        assertTrue(F1Tv.isLicenseUrl("https://f1tv.formula1.com/2.0/R/ENG/WEB_DASH/ALL/CONTENT/LA/widevine?x=1"))
        assertFalse(F1Tv.isLicenseUrl("https://evil.example/2.0/R/ENG/WEB_DASH/ALL/CONTENT/LA/widevine"))
        assertFalse(F1Tv.isLicenseUrl("http://f1tv.formula1.com/2.0/CONTENT/LA/"))
        assertFalse(F1Tv.isLicenseUrl("https://f1tv.formula1.com/2.0/CONTENT/PLAY"))
    }
}
