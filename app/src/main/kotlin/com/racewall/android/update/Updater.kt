package com.racewall.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.racewall.archive.Fetcher
import com.racewall.archive.OkHttpFetcher
import com.racewall.bus.FeedJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** Where the update check is: nothing is downloaded or installed without the user's yes. */
data class UpdateStatus(
    /** idle, checking, current, available, downloading, ready, error */
    val state: String = "idle",
    val message: String = "",
    val version: String = "",
)

/** A newer Android build in the releases. */
data class Release(val version: String, val url: String, val page: String)

/**
 * Updates from the RaceWall releases on GitHub, as the desktop's electron-updater: a release with a
 * `RaceWall-Android-<version>.apk` asset newer than this build is offered, downloaded only when asked,
 * and installed only when asked again, by Android's own installer.
 */
class Updater(
    private val current: String,
    private val dir: File,
    private val fetcher: Fetcher = OkHttpFetcher(answerErrors = true),
    // The Android builds are released with the Android app's own repository, which must be public:
    // GitHub answers 404 to anyone not signed in to a private one.
    private val releases: String = "https://api.github.com/repos/ikverse/RaceWall-Android/releases?per_page=20",
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val _status = MutableStateFlow(UpdateStatus())
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()
    private var found: Release? = null
    private var apk: File? = null

    companion object {
        private val ASSET = Regex("""^RaceWall-Android-(\d+\.\d+\.\d+)\.apk$""")

        /** True when `a` is a later version than `b`, by each number in turn. */
        fun newer(a: String, b: String): Boolean {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }

        /** The newest Android build in a releases listing. */
        @Suppress("UNCHECKED_CAST")
        fun latest(json: Any?): Release? = (json as? List<*>)?.mapNotNull { it as? Map<String, Any?> }
            ?.filter { it["draft"] != true && it["prerelease"] != true }
            ?.flatMap { r ->
                (r["assets"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> }?.mapNotNull { a ->
                    ASSET.matchEntire(a["name"] as? String ?: "")?.let { m -> Release(m.groupValues[1], a["browser_download_url"] as? String ?: "", r["html_url"] as? String ?: "") }
                } ?: emptyList()
            }
            ?.maxWithOrNull { a, b -> if (newer(a.version, b.version)) 1 else if (newer(b.version, a.version)) -1 else 0 }
    }

    suspend fun check(): UpdateStatus {
        _status.value = UpdateStatus("checking", "Checking for updates…")
        val res = try {
            fetcher.get(releases, mapOf("Accept" to "application/vnd.github+json"))
        } catch (e: Exception) {
            return set(UpdateStatus("error", "Could not check for updates: ${e.message}"))
        }
        if (res.status == 404) return set(UpdateStatus("error", "Could not check for updates: the RaceWall release page is not public."))
        if (!res.ok) return set(UpdateStatus("error", "Could not check for updates (GitHub answered ${res.status})."))
        val r = latest(runCatching { FeedJson.parse(res.body ?: "[]") }.getOrNull())
        if (r == null || !newer(r.version, current)) return set(UpdateStatus("current", "RaceWall is up to date."))
        found = r
        return set(UpdateStatus("available", "RaceWall ${r.version} is available.", r.version))
    }

    /** Downloads the offered build. Only when the user asked. */
    suspend fun download(): UpdateStatus {
        val r = found ?: return _status.value
        set(UpdateStatus("downloading", "Downloading RaceWall ${r.version}…", r.version))
        return try {
            val file = withContext(Dispatchers.IO) {
                dir.mkdirs()
                val out = File(dir, "RaceWall-Android-${r.version}.apk")
                client.newCall(Request.Builder().url(r.url).build()).execute().use { res ->
                    if (!res.isSuccessful) throw java.io.IOException("GitHub answered ${res.code}")
                    out.outputStream().use { res.body.byteStream().copyTo(it) }
                }
                out
            }
            apk = file
            set(UpdateStatus("ready", "RaceWall ${r.version} is ready to install.", r.version))
        } catch (e: Exception) {
            set(UpdateStatus("error", "Could not download the update: ${e.message}", r.version))
        }
    }

    /** Hands the downloaded build to Android's installer, which asks the user once more. */
    fun install(context: Context) {
        val file = apk ?: return
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            // Android asks once whether RaceWall may install updates; then the user presses Install again.
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun set(s: UpdateStatus): UpdateStatus {
        _status.value = s
        return s
    }
}
