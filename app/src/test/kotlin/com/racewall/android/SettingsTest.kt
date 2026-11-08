package com.racewall.android

import com.racewall.android.state.DEFAULT_DELAYS
import com.racewall.android.state.SETTINGS_VERSION
import com.racewall.android.state.Settings
import org.junit.Test
import kotlin.test.assertEquals

/** Ported from the desktop's app/shared/api.test.ts: saved settings from an older RaceWall. */
class SettingsTest {
    @Test
    fun `get the in-app delay set back to 0, once, and keep everything else`() {
        val old = Settings(theme = "mica", delays = DEFAULT_DELAYS + mapOf("in-app" to 35_829L, "stream" to 41_000L))
        val s = old.migrate()
        assertEquals(SETTINGS_VERSION, s.settingsVersion)
        assertEquals(0L, s.delays["in-app"])
        assertEquals(41_000L, s.delays["stream"])
        assertEquals("mica", s.theme)
        // Sync later sets an in-app delay on purpose: it is not reset again.
        assertEquals(2000L, s.copy(delays = s.delays + ("in-app" to 2000L)).migrate().delays["in-app"])
    }
}
