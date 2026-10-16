package com.racewall.android

import com.racewall.android.video.streamRequest
import com.racewall.f1tv.F1Tv
import org.junit.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** When a video panel asks for its stream: the video id comes before the channel list. */
class StreamRequestTest {
    private val main = F1Tv.Channel(null, "main", "Main feed", null, null)
    private val onboard = F1Tv.Channel(1001, "obc", "Max Verstappen", "1", "#3671C6")

    @Test
    fun `asks for nothing until the channel list is known`() {
        assertNull(streamRequest(null, null))
        assertNull(streamRequest(1000010398, null))
    }

    @Test
    fun `asks for the main feed once its channel arrives, though it has no channel id`() {
        val before = streamRequest(1000010398, null)
        val after = streamRequest(1000010398, main)
        assertNotNull(after)
        assertNotEquals(before, after)
    }

    @Test
    fun `asks again when the channel or the video changes`() {
        assertNotEquals(streamRequest(1000010398, main), streamRequest(1000010398, onboard))
        assertNotEquals(streamRequest(1000010398, main), streamRequest(1000010399, main))
    }
}
