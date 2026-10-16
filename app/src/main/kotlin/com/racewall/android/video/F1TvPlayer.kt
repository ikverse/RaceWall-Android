package com.racewall.android.video

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import androidx.media3.datasource.DataSpec
import com.racewall.f1tv.F1Tv
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/**
 * Licence requests for protected F1 TV video, as the desktop's `video:license`: the subscription
 * token is added here, and only F1 TV's own licence address may receive it.
 */
@OptIn(UnstableApi::class)
class F1TvLicense(
    private val licenseUrl: String,
    private val entitlementToken: String?,
    private val token: () -> String?,
    private val client: OkHttpClient = OkHttpClient(),
) : MediaDrmCallback {
    private val provisioning = HttpMediaDrmCallback(null, DefaultHttpDataSource.Factory())

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): MediaDrmCallback.Response = provisioning.executeProvisionRequest(uuid, request)

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response {
        val url = request.licenseServerUrl.ifEmpty { licenseUrl }
        val spec = DataSpec.Builder().setUri(url).build()
        fun fail(e: Throwable): Nothing = throw MediaDrmCallbackException(spec, android.net.Uri.parse(url), emptyMap(), 0, e)
        val t = token() ?: fail(IOException("Sign in with your F1 account to watch F1 TV."))
        if (!F1Tv.isLicenseUrl(url)) fail(IOException("Not an F1 TV licence address"))
        val req = Request.Builder()
            .url(url)
            .post(request.data.toRequestBody("application/octet-stream".toMediaType()))
            .header("ascendontoken", t)
            .apply { entitlementToken?.let { header("entitlementtoken", it) } }
            .build()
        try {
            client.newCall(req).execute().use { res ->
                val body = res.body.bytes()
                if (!res.isSuccessful) fail(IOException("F1 TV refused the video licence (${res.code})"))
                return MediaDrmCallback.Response(body)
            }
        } catch (e: MediaDrmCallbackException) {
            throw e
        } catch (e: IOException) {
            fail(e)
        }
    }
}

/** One ExoPlayer for an F1 TV stream: DASH, with Widevine when F1 TV names a licence server. */
@OptIn(UnstableApi::class)
class F1TvPlayer(context: Context, private val token: () -> String?) {
    val exo: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()
    var loaded = false
        private set

    fun load(info: F1Tv.PlayInfo, startSeconds: Double?, live: Boolean) {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val factory = DashMediaSource.Factory(http)
        info.licenseUrl?.let { url ->
            val drm = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(F1TvLicense(url, info.entitlementToken, token))
            factory.setDrmSessionManagerProvider { drm }
        }
        val item = MediaItem.Builder().setUri(info.url).apply {
            info.licenseUrl?.let { setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(it).build()) }
        }.build()
        exo.setMediaSource(factory.createMediaSource(item))
        exo.prepare()
        if (!live && startSeconds != null) exo.seekTo((startSeconds * 1000).toLong())
        loaded = true
    }

    /** The playhead's real time, for live streams, in UTC milliseconds. */
    fun playheadUtc(): Double? {
        val tl: Timeline = exo.currentTimeline
        if (tl.isEmpty) return null
        val w = Timeline.Window()
        tl.getWindow(exo.currentMediaItemIndex, w)
        if (w.windowStartTimeMs == C.TIME_UNSET) return null
        return (w.windowStartTimeMs + exo.currentPosition).toDouble()
    }

    /** What can be played, in seconds: the replay, or the part of a live broadcast F1 TV keeps. */
    fun seekRange(): ClosedFloatingPointRange<Double> {
        val d = exo.duration
        return 0.0..(if (d == C.TIME_UNSET || d < 0) 0.0 else d / 1000.0)
    }

    val ready: Boolean get() = exo.playbackState == Player.STATE_READY

    fun release() {
        exo.release()
        loaded = false
    }
}
