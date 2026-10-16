package com.racewall.archive

import com.racewall.bus.FeedJson
import com.racewall.bus.jsString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

const val ARCHIVE_BASE = "https://livetiming.formula1.com/static/"

data class ArchiveSession(
    val key: Int,
    val name: String,
    val type: String,
    val startDate: String,
    val endDate: String,
    val gmtOffset: String,
    val path: String,
)

data class ArchiveMeeting(
    val key: Int,
    val name: String,
    val location: String,
    val country: String,
    val sessions: List<ArchiveSession>,
)

/** An HTTP answer: the status and, when the request worked, the body as text. */
class HttpResult(val status: Int, val body: String?) {
    val ok: Boolean get() = status in 200..299
}

/** Fetches a URL, with these headers. The app uses OkHttp; tests pass a fake. */
fun interface Fetcher {
    suspend fun get(url: String, headers: Map<String, String>): HttpResult
}

/** The archive turned the request down: it needs an F1 TV sign-in. */
class ArchiveSignInException : IOException("F1's timing archive now needs an F1 TV sign-in. Sign in under Settings → F1 account.")

/**
 * Fetches with OkHttp, always closing the response so its connection is released. With
 * `answerErrors` the body of a failed request is kept too, for services that explain a refusal.
 */
class OkHttpFetcher(private val client: OkHttpClient = OkHttpClient(), private val answerErrors: Boolean = false) : Fetcher {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { for ((k, v) in headers) header(k, v) }.build()
        client.newCall(request).execute().use { res ->
            HttpResult(res.code, if (res.isSuccessful || answerErrors) res.body.string() else null)
        }
    }
}

/**
 * Downloads from F1's public timing archive (livetiming.formula1.com/static), with a disk cache so
 * a session that has been watched once replays offline.
 */
class Archive(
    private val fetcher: Fetcher = OkHttpFetcher(),
    /** Folder for cached files. Without one, nothing is cached. */
    private val cacheDir: File? = null,
    private val base: String = ARCHIVE_BASE,
    private val now: () -> Long = System::currentTimeMillis,
    /** The F1 TV subscription token, if signed in. Since October 2026 the archive answers 401 without one. */
    private val token: () -> String? = { null },
) {
    private suspend fun download(path: String, cacheable: Boolean): String? {
        val file = cacheDir?.let { File(it, path.split('/').joinToString(File.separator)) }
        if (file != null && cacheable) {
            val cached = withContext(Dispatchers.IO) { if (file.isFile) file.readText() else null }
            if (cached != null) return cached
        }
        val headers = token()?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
        val res = fetcher.get(base + path, headers)
        if (!res.ok) {
            if (res.status == 401) throw ArchiveSignInException()
            if (res.status == 404 || res.status == 403) return null
            throw IOException("F1 archive: ${res.status} for $path")
        }
        val text = res.body ?: ""
        if (file != null && cacheable) withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            // Written to a temporary file first, so a crash never leaves half a file in the cache.
            val tmp = File(file.parentFile, file.name + ".part")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
        return text
    }

    /** Every meeting of a season that has at least one archived session. */
    @Suppress("UNCHECKED_CAST")
    suspend fun fetchSeason(year: Int): List<ArchiveMeeting> {
        val past = year < Instant.ofEpochMilli(now()).atZone(ZoneOffset.UTC).year
        val text = download("$year/Index.json", past) ?: return emptyList()
        val index = FeedJson.parse(stripBom(text)) as? Map<String, Any?> ?: return emptyList()
        val meetings = index["Meetings"] as? List<Any?> ?: emptyList()
        return meetings.mapNotNull { it as? Map<String, Any?> }
            .map { m ->
                ArchiveMeeting(
                    key = num(m["Key"]),
                    name = text(m["Name"]),
                    location = text(m["Location"]),
                    country = text((m["Country"] as? Map<String, Any?>)?.get("Name")),
                    sessions = (m["Sessions"] as? List<Any?> ?: emptyList()).mapNotNull { it as? Map<String, Any?> }
                        .filter { (it["Path"] as? String).orEmpty().isNotEmpty() }
                        .map { s ->
                            ArchiveSession(
                                key = num(s["Key"]),
                                name = text(s["Name"]),
                                type = text(s["Type"]),
                                startDate = text(s["StartDate"]),
                                endDate = text(s["EndDate"]),
                                gmtOffset = text(s["GmtOffset"]),
                                path = s["Path"] as String,
                            )
                        },
                )
            }
            .filter { it.sessions.isNotEmpty() }
    }

    /**
     * Downloads the files RaceWall needs for one session. Files are cached only once the archive
     * marks the session complete, so a session still being recorded is fetched fresh each time.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun fetchSessionFiles(path: String): SessionFiles = coroutineScope {
        val dir = if (path.endsWith("/")) path else "$path/"
        val infoText = download("${dir}SessionInfo.json", false) ?: throw IOException("F1 archive has no session at $dir")
        val info = FeedJson.parse(stripBom(infoText)) as? Map<String, Any?>
        val complete = (info?.get("ArchiveStatus") as? Map<String, Any?>)?.get("Status") == "Complete"
        val streams = STREAMS.map { name -> async { name to (download("$dir$name.jsonStream", complete) ?: "") } }.awaitAll().toMap()
        SessionFiles(infoText, streams)
    }

    /**
     * Downloads the compressed car position and telemetry feeds: about 15 MB for a race, so only
     * when a widget needs them. Cached like the other files once the session is complete.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun fetchCarFiles(path: String): CarFiles = coroutineScope {
        val dir = if (path.endsWith("/")) path else "$path/"
        val infoText = download("${dir}SessionInfo.json", false)
        val info = infoText?.let { FeedJson.parse(stripBom(it)) as? Map<String, Any?> }
        val complete = (info?.get("ArchiveStatus") as? Map<String, Any?>)?.get("Status") == "Complete"
        CAR_STREAMS.map { name -> async { name to (download("$dir$name.jsonStream", complete) ?: "") } }.awaitAll().toMap()
    }

    private fun text(v: Any?): String = if (v == null) "" else jsString(v)

    private fun num(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is String -> v.toDoubleOrNull()?.toInt() ?: 0
        else -> 0
    }
}

/**
 * When a session ends, in UTC milliseconds. The archive gives the circuit's local time and its
 * offset from UTC ("-04:00:00").
 */
fun endUtc(local: String, offset: String): Long {
    val m = Regex("""^(-)?(\d+):(\d+)""").find(offset)
    val minutes = if (m == null) 0 else (m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()) * (if (m.groupValues[1].isNotEmpty()) -1 else 1)
    val utc = try {
        LocalDateTime.parse(local).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (_: Exception) {
        return Long.MAX_VALUE
    }
    return utc - minutes * 60_000L
}
