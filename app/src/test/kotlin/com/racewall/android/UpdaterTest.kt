package com.racewall.android

import com.racewall.android.update.Updater
import com.racewall.archive.Fetcher
import com.racewall.archive.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The update check: a newer Android build in the releases is offered, nothing else. */
class UpdaterTest {
    private val listing = """[
      {"tag_name":"v0.2.7","draft":false,"prerelease":false,"html_url":"https://github.com/ikverse/RaceWall/releases/tag/v0.2.7",
       "assets":[{"name":"RaceWall-Setup-0.2.7.exe","browser_download_url":"https://x/setup.exe"},{"name":"RaceWall-Android-0.2.0.apk","browser_download_url":"https://x/a-0.2.0.apk"}]},
      {"tag_name":"v0.3.0-beta","draft":false,"prerelease":true,"assets":[{"name":"RaceWall-Android-0.3.0.apk","browser_download_url":"https://x/a-0.3.0.apk"}]},
      {"tag_name":"v0.2.6","draft":false,"prerelease":false,"assets":[{"name":"RaceWall-Android-0.1.5.apk","browser_download_url":"https://x/a-0.1.5.apk"}]}
    ]"""

    private fun updater(current: String) = Updater(current, Files.createTempDirectory("rw-up").toFile(), Fetcher { _, _ -> HttpResult(200, listing) })

    @Test
    fun `compares versions number by number`() {
        assertTrue(Updater.newer("0.10.0", "0.9.9"))
        assertTrue(Updater.newer("0.2.0", "0.1.0-debug"))
        assertFalse(Updater.newer("0.1.0", "0.1.0"))
        assertFalse(Updater.newer("0.1.0", "0.2.0"))
    }

    @Test
    fun `offers the newest Android build that is not a pre-release`() = runTest {
        val s = updater("0.1.0").check()
        assertEquals("available", s.state)
        assertEquals("0.2.0", s.version)
    }

    @Test
    fun `says it is up to date when nothing newer is out`() = runTest {
        assertEquals("current", updater("0.2.0").check().state)
    }

    @Test
    fun `says what went wrong when GitHub cannot be asked`() = runTest {
        val s = Updater("0.1.0", Files.createTempDirectory("rw-up").toFile(), Fetcher { _, _ -> HttpResult(403, "{}") }).check()
        assertEquals("error", s.state)
        assertTrue("403" in s.message)
    }
}
