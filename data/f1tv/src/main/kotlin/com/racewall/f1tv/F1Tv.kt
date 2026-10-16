package com.racewall.f1tv

import com.racewall.archive.Fetcher
import com.racewall.archive.OkHttpFetcher
import com.racewall.bus.FeedJson
import com.racewall.bus.jsString
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

/**
 * F1 TV's catalogue and playback requests, as the desktop's packages/f1tv. The catalogue is public;
 * playing anything needs the user's own subscription token, sent the way F1 TV's apps send it (the
 * "ascendontoken" header). F1 TV decides what the plan allows. This module only asks.
 */
object F1Tv {
    const val BASE = "https://f1tv.formula1.com"

    /** The web player's stream format: DASH, which carries Widevine protection. */
    const val FORMAT = "WEB_DASH"

    data class Video(
        val contentId: Long,
        val title: String,
        /** "REPLAY", "LIVE", "RACE HIGHLIGHTS", … */
        val kind: String,
        /** "Access", "Pro", "Premium": the plan needed to play it. */
        val entitlement: String,
        /** F1's session key: the same number RaceWall's timing uses. */
        val sessionKey: String?,
        val meeting: String,
        /** Scheduled session start and end, UTC ms. */
        val start: Long?,
        val end: Long?,
    )

    data class Channel(
        /** Null for the main feed. */
        val channelId: Long?,
        /** "main", "obc" (onboard), or "additional" (F1 Live, tracker, data). */
        val type: String,
        val name: String,
        /** For onboards: the driver's racing number. */
        val racingNumber: String?,
        /** For onboards: "#RRGGBB". */
        val colour: String?,
    )

    data class Details(val video: Video, /** Length in seconds. */ val duration: Long, val channels: List<Channel>)

    data class PlayInfo(
        /** The manifest to play. */
        val url: String,
        val streamType: String,
        /** Sent to the licence server for protected streams. */
        val entitlementToken: String?,
        /** The licence server, when F1 TV names one. */
        val licenseUrl: String?,
        val drmType: String?,
    )

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()
    private fun arr(v: Any?): List<Map<String, Any?>> = (v as? List<*>)?.map(::obj) ?: emptyList()
    private fun text(v: Any?): String = if (v == null) "" else jsString(v)
    private fun num(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull() ?: Double.NaN
        else -> Double.NaN
    }
    private fun msOrNull(v: Any?): Long? = num(v).takeIf { it.isFinite() && it > 0 }?.toLong()

    fun parseVideo(metadata: Any?): Video? {
        val m = obj(metadata)
        if (m["contentType"] != "VIDEO" || m["contentId"] == null || m["contentId"] == "" || m["contentId"] == 0L) return null
        val emf = obj(m["emfAttributes"])
        return Video(
            contentId = num(m["contentId"]).toLong(),
            title = text(m["title"]),
            kind = text(m["contentSubtype"]),
            entitlement = text(m["entitlement"]),
            sessionKey = emf["MeetingSessionKey"]?.takeIf { it != "" && it != 0L }?.let(::jsString),
            meeting = text(emf["Meeting_Name"] ?: emf["Global_Meeting_Name"]),
            start = msOrNull(emf["sessionStartDate"]),
            end = msOrNull(emf["sessionEndDate"]),
        )
    }

    /** Every video in a search or page response, wherever it is nested. */
    fun videosIn(json: Any?): List<Video> {
        val out = ArrayList<Video>()
        val seen = HashSet<Long>()
        fun visit(node: Map<String, Any?>) {
            val v = node["metadata"]?.let(::parseVideo)
            if (v != null && seen.add(v.contentId)) out.add(v)
            for (c in arr(node["containers"])) visit(c)
            for (c in arr(obj(obj(node["retrieveItems"])["resultObj"])["containers"])) visit(c)
        }
        for (c in arr(obj(obj(json)["resultObj"])["containers"])) visit(c)
        return out
    }

    /** "F1 LIVE" to "F1 Live", "INTERNATIONAL" to "International". */
    private fun titleCase(s: String) = Regex("""\b([A-Z])([A-Z]+)\b""").replace(s) { it.groupValues[1] + it.groupValues[2].lowercase() }

    private val HEX = Regex("^#[0-9a-fA-F]{6}$")

    fun parseDetails(json: Any?): Details? {
        val top = arr(obj(obj(json)["resultObj"])["containers"]).firstOrNull() ?: return null
        val video = parseVideo(top["metadata"]) ?: return null
        val m = obj(top["metadata"])
        val channels = arrayListOf(Channel(null, "main", "Main feed", null, null))
        for (s in arr(m["additionalStreams"])) {
            val type = if (s["type"] == "obc") "obc" else "additional"
            val name = if (type == "obc") "${text(s["driverFirstName"])} ${text(s["driverLastName"])}".trim().ifEmpty { text(s["title"]) } else titleCase(text(s["title"]))
            channels.add(
                Channel(
                    channelId = num(s["channelId"]).takeIf { it.isFinite() && it != 0.0 }?.toLong(),
                    type = type,
                    name = name,
                    racingNumber = if (type == "obc" && s["racingNumber"] != null) jsString(s["racingNumber"]) else null,
                    colour = (s["hex"] as? String)?.takeIf { HEX.matches(it) }?.uppercase(),
                ),
            )
        }
        return Details(video, num(m["duration"]).takeIf { it.isFinite() }?.toLong() ?: 0, channels)
    }

    fun parsePlay(json: Any?): PlayInfo {
        val ro = obj(obj(json)["resultObj"])
        val url = text(ro["url"])
        if (url.isEmpty()) {
            val j = obj(json)
            throw IOException(text(j["message"] ?: j["errorDescription"] ?: "F1 TV gave no stream for this"))
        }
        val drm = if (ro["laURL"] != null) ro else obj(ro["drm"])
        return PlayInfo(
            url = url,
            streamType = text(ro["streamType"]),
            entitlementToken = ro["entitlementToken"]?.let(::text)?.ifEmpty { null },
            licenseUrl = text(ro["laURL"] ?: drm["laURL"] ?: drm["licenseUrl"]).ifEmpty { null },
            drmType = text(ro["drmType"] ?: drm["drmType"]).ifEmpty { null },
        )
    }

    private suspend fun json(url: String, fetcher: Fetcher, headers: Map<String, String> = emptyMap()): Any? {
        val res = fetcher.get(url, headers)
        val body = res.body ?: throw IOException("F1 TV answered ${res.status}")
        val parsed = try {
            FeedJson.parse(body)
        } catch (_: Exception) {
            throw IOException("F1 TV answered ${res.status} with something that is not JSON")
        }
        if (!res.ok) {
            val j = obj(parsed)
            throw IOException("F1 TV answered ${res.status}: ${text(j["message"] ?: j["errorDescription"])}".trim())
        }
        return parsed
    }

    /** Videos for one session (F1's session key), the full replay or live stream first. */
    suspend fun videosForSession(sessionKey: Int, fetcher: Fetcher = OkHttpFetcher(answerErrors = true)): List<Video> {
        val key = sessionKey.toString()
        val json = json("$BASE/2.0/R/ENG/$FORMAT/ALL/PAGE/SEARCH/VOD/F1_TV_Pro_Annual/14?filter_MeetingSessionKey=${URLEncoder.encode(key, "UTF-8")}", fetcher)
        fun rank(v: Video) = when (v.kind) {
            "LIVE" -> 0
            "REPLAY" -> 1
            else -> 2
        }
        return videosIn(json).filter { it.sessionKey == key }.sortedBy(::rank)
    }

    /** What is live on F1 TV right now, from the home page. */
    suspend fun liveVideos(fetcher: Fetcher = OkHttpFetcher(answerErrors = true)): List<Video> =
        videosIn(json("$BASE/2.0/R/ENG/$FORMAT/ALL/PAGE/395/F1_TV_Pro_Annual/14", fetcher)).filter { it.kind == "LIVE" }

    suspend fun videoDetails(contentId: Long, fetcher: Fetcher = OkHttpFetcher(answerErrors = true)): Details =
        parseDetails(json("$BASE/3.0/R/ENG/$FORMAT/ALL/CONTENT/VIDEO/$contentId/F1_TV_Pro_Annual/14", fetcher)) ?: throw IOException("F1 TV has no details for that video")

    /** Asks F1 TV for a stream of one channel, with the user's subscription token. */
    suspend fun play(contentId: Long, channelId: Long?, token: String, fetcher: Fetcher = OkHttpFetcher(answerErrors = true)): PlayInfo {
        val q = "contentId=$contentId" + (channelId?.let { "&channelId=$it" } ?: "")
        return parsePlay(json("$BASE/2.0/R/ENG/$FORMAT/ALL/CONTENT/PLAY?$q", fetcher, mapOf("ascendontoken" to token)))
    }

    /** Only F1 TV's own licence address may receive the token. */
    fun isLicenseUrl(url: String): Boolean = try {
        val u = URI(url)
        u.scheme == "https" && u.host == "f1tv.formula1.com" && (u.path ?: "").contains("/CONTENT/LA/")
    } catch (_: Exception) {
        false
    }
}
