package com.racewall.live

import com.racewall.archive.parseUtc
import com.racewall.bus.FeedJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException

const val LIVE_URL = "https://livetiming.formula1.com/signalrcore"

val LIVE_TOPICS = listOf(
    "Heartbeat",
    "SessionInfo",
    "SessionStatus",
    "TrackStatus",
    "LapCount",
    "DriverList",
    "TimingData",
    "TimingAppData",
    "TimingStats",
    "RaceControlMessages",
    "WeatherData",
    "TeamRadio",
    "ExtrapolatedClock",
    "Position.z",
    "CarData.z",
)

private const val RS = '\u001e'
private const val PING_MS = 15_000L

enum class LiveStatus { CONNECTING, CONNECTED, RECONNECTING, CLOSED, ERROR }

interface LiveHandlers {
    /** The current state of each feed, sent once after subscribing (and again after a reconnect). */
    fun snapshot(topic: String, data: Any?)

    /** One change to a feed, with the server's UTC time in milliseconds. */
    fun message(topic: String, data: Any?, utc: Long)

    fun status(status: LiveStatus, detail: String = "")
}

/** The parts of a WebSocket the client uses, so tests can pass a fake one. */
interface SocketLike {
    fun send(text: String)
    fun close()
}

/** What a socket tells the client. */
interface SocketEvents {
    fun open()
    fun message(text: String)
    fun closed()
    fun failed(message: String)
}

/** An HTTP answer for negotiation: its status, `set-cookie` header and body. */
class Negotiated(val status: Int, val setCookie: String?, val body: String?)

/** The network the client uses; the app uses OkHttp, tests pass fakes. */
interface LiveNetwork {
    suspend fun request(method: String, url: String, headers: Map<String, String>): Negotiated
    fun socket(url: String, headers: Map<String, String>, events: SocketEvents): SocketLike
}

/** OkHttp: plain requests for negotiation, and its WebSocket. */
class OkHttpLiveNetwork(private val client: OkHttpClient = OkHttpClient()) : LiveNetwork {
    override suspend fun request(method: String, url: String, headers: Map<String, String>): Negotiated = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val body = if (method == "POST") ByteArray(0).toRequestBody(null) else null
        val req = Request.Builder().url(url).method(method, body).apply { for ((k, v) in headers) header(k, v) }.build()
        client.newCall(req).execute().use { res -> Negotiated(res.code, res.headers("set-cookie").joinToString("; "), if (res.isSuccessful) res.body.string() else null) }
    }

    override fun socket(url: String, headers: Map<String, String>, events: SocketEvents): SocketLike {
        val req = Request.Builder().url(url).apply { for ((k, v) in headers) header(k, v) }.build()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = events.open()
            override fun onMessage(webSocket: WebSocket, text: String) = events.message(text)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = events.closed()
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = events.failed(t.message ?: t.toString())
        })
        return object : SocketLike {
            override fun send(text: String) {
                ws.send(text)
            }

            override fun close() {
                ws.close(1000, null)
            }
        }
    }
}

/**
 * A client for F1's live timing feed: SignalR Core over a WebSocket, as the desktop's
 * packages/f1-live and FastF1 use it.
 *
 *  1. OPTIONS the negotiate URL to get the load balancer's AWSALBCORS cookie.
 *  2. POST negotiate for a connection token, with the F1 TV subscription token when signed in.
 *  3. Open the WebSocket, send the JSON-protocol handshake, then call "Subscribe" with the feeds.
 *  4. The reply holds every feed's current state; after that, "feed" messages bring each change.
 */
class LiveClient(
    private val handlers: LiveHandlers,
    private val token: String? = null,
    private val topics: List<String> = LIVE_TOPICS,
    private val network: LiveNetwork = OkHttpLiveNetwork(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var ws: SocketLike? = null
    @Volatile private var stopped = false
    private var attempt = 0
    private var ping: Job? = null
    private var retry: Job? = null
    private val json = Json

    /** Wall-clock time of the last message from the server. */
    @Volatile var lastMessage = 0L
        private set

    suspend fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        ping?.cancel()
        retry?.cancel()
        ws?.close()
        ws = null
        handlers.status(LiveStatus.CLOSED)
    }

    /** The network changed, as from Wi-Fi to mobile data: reconnect at once rather than wait. */
    fun reconnectNow() {
        if (stopped) return
        retry?.cancel()
        ping?.cancel()
        ws?.close()
        ws = null
        attempt = maxOf(attempt, 1)
        scope.launch { connect() }
    }

    private suspend fun connect() {
        handlers.status(if (attempt > 0) LiveStatus.RECONNECTING else LiveStatus.CONNECTING)
        try {
            val headers = LinkedHashMap<String, String>()
            val options = network.request("OPTIONS", "$LIVE_URL/negotiate?negotiateVersion=1", emptyMap())
            Regex("AWSALBCORS=[^;]+").find(options.setCookie ?: "")?.value?.let { headers["Cookie"] = it }
            if (!token.isNullOrEmpty()) headers["Authorization"] = "Bearer $token"
            val res = network.request("POST", "$LIVE_URL/negotiate?negotiateVersion=1", headers)
            if (res.status !in 200..299) throw IOException("negotiate answered ${res.status}")
            @Suppress("UNCHECKED_CAST")
            val neg = FeedJson.parse(res.body ?: "{}") as? Map<String, Any?> ?: emptyMap()
            val id = (neg["connectionToken"] ?: neg["connectionId"]) as? String ?: throw IOException("negotiate gave no connection id")
            open("${LIVE_URL.replaceFirst(Regex("^https"), "wss")}?id=${java.net.URLEncoder.encode(id, "UTF-8")}", headers)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
        }
    }

    private fun open(url: String, headers: Map<String, String>) {
        var handshook = false
        lateinit var socket: SocketLike
        socket = network.socket(url, headers, object : SocketEvents {
            override fun open() = socket.send("""{"protocol":"json","version":1}$RS""")

            override fun message(text: String) {
                lastMessage = now()
                for (part in text.split(RS)) {
                    if (part.isEmpty()) continue
                    @Suppress("UNCHECKED_CAST")
                    val msg = try {
                        FeedJson.parse(part) as? Map<String, Any?> ?: continue
                    } catch (_: Exception) {
                        continue
                    }
                    if (!handshook) {
                        handshook = true
                        msg["error"]?.let { return fail("handshake refused: $it") }
                        attempt = 0
                        handlers.status(LiveStatus.CONNECTED)
                        val subscribe = buildJsonObject {
                            put("type", 1)
                            put("invocationId", "0")
                            put("target", "Subscribe")
                            put("arguments", JsonArray(listOf(JsonArray(topics.map(::JsonPrimitive)))))
                        }
                        socket.send(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), subscribe) + RS)
                        ping?.cancel()
                        ping = scope.launch {
                            while (isActive) {
                                delay(PING_MS)
                                socket.send("""{"type":6}$RS""")
                            }
                        }
                        continue
                    }
                    when ((msg["type"] as? Number)?.toInt()) {
                        3 -> {
                            msg["error"]?.let { handlers.status(LiveStatus.ERROR, "subscribe failed: $it") }
                            @Suppress("UNCHECKED_CAST")
                            for ((topic, data) in (msg["result"] as? Map<String, Any?>) ?: emptyMap()) handlers.snapshot(topic, data)
                        }
                        1 -> if (msg["target"] == "feed") {
                            val args = msg["arguments"] as? List<*> ?: continue
                            val topic = args.getOrNull(0) as? String ?: continue
                            val utc = parseUtc(args.getOrNull(2)?.toString() ?: "") ?: now()
                            handlers.message(topic, args.getOrNull(1), utc)
                        }
                        7 -> handlers.status(LiveStatus.ERROR, "server closed the connection${msg["error"]?.let { ": $it" } ?: ""}")
                    }
                }
            }

            override fun closed() {
                ping?.cancel()
                if (!stopped) fail("connection closed")
            }

            // OkHttp reports a dropped connection as a failure, with no close after it.
            override fun failed(message: String) {
                ping?.cancel()
                if (!stopped) fail(message)
            }
        })
        ws = socket
    }

    /** Tries again with growing pauses: 2, 4, 8 … up to 60 seconds. */
    private fun fail(detail: String) {
        if (stopped) return
        handlers.status(LiveStatus.ERROR, detail)
        attempt++
        val wait = minOf(60_000L, 2000L * (1L shl (attempt - 1).coerceAtMost(10)))
        retry?.cancel()
        retry = scope.launch {
            delay(wait)
            connect()
        }
    }
}
