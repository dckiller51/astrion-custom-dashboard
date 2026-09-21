package com.custom.astrion.ha

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Minimal, dependency-light Home Assistant WebSocket client.
 *
 * This speaks the *standard* HA websocket API — exactly the same handshake and
 * commands the stock HaRemote app uses (confirmed by decompiling it):
 *
 *   1. Server sends        { type: "auth_required" }
 *   2. We send             { type: "auth", access_token: "<long-lived token>" }
 *   3. Server sends        { type: "auth_ok" }  (or "auth_invalid")
 *   4. We call             get_states  to seed the entity cache
 *   5. We                  subscribe_events (state_changed) for live updates
 *   6. Heartbeat via       { type: "ping" } / { type: "pong" }
 *
 * Because it's the stock protocol, this app needs nothing from Sanytron's
 * cloud or their custom integration to function — only a reachable HA instance
 * and a long-lived access token.
 *
 * Deliberately small: one socket, a StateFlow of the entity map, a StateFlow of
 * connection status, and callService().
 */
@Suppress("SpellCheckingInspection")
class HaClient(
    // e.g. "http://10.0.1.10:8123" or "https://ha.example.com"
    private val baseUrl: String,
    // long-lived access token
    private val token: String
) {
    companion object {
        private const val TAG = "HaClient"
        private val PING_INTERVAL = 30.seconds
        private val PUBLISH_INTERVAL = 120.milliseconds
        private val RECONNECT_DELAY = 3.seconds
        private val RESPONSE_TIMEOUT = 8.seconds

        // media_player/browse_media specifically can legitimately take far
        // longer than any other request this client makes: some
        // integrations (confirmed for Home Assistant's own Squeezebox/Lyrion
        // "Apps"/"Radios" listing, which can run into the hundreds of
        // entries, each carrying its own icon URL) return a response large
        // enough — and slow enough for the LMS/Lyrion server itself to
        // assemble — that it blows straight past the general 8s timeout,
        // even though a small local-library folder (an album's tracks, say)
        // loads comfortably within it. Using RESPONSE_TIMEOUT here made
        // Astrion silently time out and show "Couldn't load media" for
        // exactly these large folders, while Home Assistant's own frontend
        // (with no client-side timeout of its own) just waited it out.
        private val MEDIA_BROWSE_TIMEOUT = 25.seconds
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idCounter = AtomicInteger(1)

    private val http =
        OkHttpClient
            .Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    // Separate, sanely-timed client for one-shot HTTP GETs (album art).
    private val imageHttp =
        OkHttpClient
            .Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

    // Client for long-lived streaming GETs (MJPEG camera_proxy_stream): no call
    // or read timeout so the never-ending multipart response isn't torn down,
    // but a real connect timeout so a dead HA doesn't hang the reader forever.
    private val streamHttp =
        OkHttpClient
            .Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null

    /** Outstanding request/response commands (e.g. browse_media), keyed by id. */
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _entities = MutableStateFlow<EntityMap>(emptyMap())

    /** Live map of every entity's current state. Cards observe this. */
    val entities: StateFlow<EntityMap> = _entities.asStateFlow()

    // Working store updated on every event; published to _entities at most once
    // per PUBLISH_INTERVAL so a chatty sensor (e.g. mmWave radar at several
    // Hz) can't force the whole UI to repaint faster than the SoC can handle.
    private val entityStore = ConcurrentHashMap<String, EntityState>()

    @Volatile private var entitiesDirty = false

    @Volatile private var publisherStarted = false

    // ---- public API ---------------------------------------------------------

    fun connect() {
        if (baseUrl.isBlank()) {
            Log.w(TAG, "connect() called with no base URL — not configured yet")
            _connection.value = ConnectionState.DISCONNECTED
            return
        }
        _connection.value = ConnectionState.CONNECTING
        val wsUrl = toWebSocketUrl(baseUrl)
        Log.i(TAG, "Connecting to $wsUrl")
        val req = Request.Builder().url(wsUrl).build()
        socket = http.newWebSocket(req, listener)
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        socket?.close(1000, "client closing")
        socket = null
        _connection.value = ConnectionState.DISCONNECTED
    }

    /** Fire an HA service call, e.g. light.toggle on light.kitchen. */
    fun callService(call: ServiceCall) {
        val target =
            buildJsonObject {
                call.entityId?.let { put("entity_id", it) }
            }
        val msg =
            buildJsonObject {
                put("id", idCounter.getAndIncrement())
                put("type", "call_service")
                put("domain", call.domain)
                put("service", call.service)
                if (call.data.isNotEmpty()) {
                    put("service_data", JsonObject(call.data))
                }
                put("target", target)
            }
        Log.d(TAG, "callService: ${call.domain}.${call.service} entity=${call.entityId} data=${call.data}")
        send(msg)
    }

    /** Convenience helper mirroring the common toggle pattern. */
    fun toggle(entityId: String) {
        val domain = entityId.substringBefore('.')
        callService(ServiceCall(domain = domain, service = "toggle", entityId = entityId))
    }

    /**
     * Fetch an image (e.g. a media_player `entity_picture`) as an ImageBitmap.
     * `path` may be absolute or an HA-relative path like /api/media_player_proxy/…;
     * the bearer token is attached so proxied/authenticated art loads too.
     */
    suspend fun fetchBitmap(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            val url = if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path
            val req =
                Request
                    .Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .build()
            imageHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bytes = resp.body?.bytes() ?: return@withContext null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchBitmap failed for $path", e)
            null
        }
    }

    /**
     * Fetch a URL/HA-relative path as raw bytes, with the bearer token attached.
     * Same auth/one-shot semantics as [fetchBitmap] but returns the undecoded
     * body — used to proxy a single camera frame through the config server so
     * the editor preview can show a real still without the HA token.
     */
    suspend fun fetchBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val url = if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path
            val req =
                Request
                    .Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .build()
            imageHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchBytes failed for $path", e)
            null
        }
    }

    /**
     * Open an authenticated streaming GET (e.g. HA's MJPEG
     * `/api/camera_proxy_stream/<entity>?token=…`). Returns the raw OkHttp
     * [Response]; the caller OWNS it and MUST close it (use `resp.use { … }`)
     * to release the connection. Uses the no-timeout [streamHttp] client so the
     * never-ending multipart body isn't cut off. Blocking network call — invoke
     * off the main thread. Returns null if the request can't even be dispatched.
     */
    fun openStream(path: String): Response? {
        if (baseUrl.isBlank()) return null
        return try {
            val url = if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path
            val req =
                Request
                    .Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .build()
            streamHttp.newCall(req).execute()
        } catch (e: Exception) {
            Log.w(TAG, "openStream failed for $path", e)
            null
        }
    }

    /**
     * Fire-and-forget POST to a Home Assistant webhook (`<baseUrl>/api/webhook/<webhookId>`)
     * — for pushing state (current page, active Activity per room) to a companion HA
     * integration the instant it changes, instead of that integration having to poll
     * ConfigServer on a timer. Webhooks need no `Authorization` header (the id itself
     * is the secret, same as any other HA webhook trigger). Silently no-ops on a blank
     * id/baseUrl or any network failure — this is a nice-to-have push, never something
     * worth surfacing an error for or retrying; the polling endpoints keep working
     * regardless of whether this succeeds.
     */
    fun pushWebhook(webhookId: String, payload: JsonObject) {
        if (webhookId.isBlank() || baseUrl.isBlank()) {
            // Previously silent — made it explicit because a blank webhookId/baseUrl at
            // this exact call site is indistinguishable, from the outside, from the
            // request having actually been sent and simply lost somewhere, which made
            // this near-impossible to diagnose from logcat alone.
            Log.d(TAG, "pushWebhook skipped: webhookId blank=${webhookId.isBlank()}, baseUrl blank=${baseUrl.isBlank()}")
            return
        }
        scope.launch {
            try {
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url("${baseUrl.trimEnd('/')}/api/webhook/$webhookId").post(body).build()
                imageHttp.newCall(req).execute().use { resp ->
                    Log.d(TAG, "pushWebhook($webhookId) -> HTTP ${resp.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "pushWebhook($webhookId) failed", e)
            }
        }
    }

    /**
     * Browse a media_player's library via the standard `media_player/browse_media`
     * command. Returns the `result` object (title + children), or null on timeout.
     * Pass a null contentId/type to browse the root.
     */
    suspend fun browseMedia(entityId: String, contentId: String? = null, contentType: String? = null): JsonObject? {
        val id = idCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val msg =
            buildJsonObject {
                put("id", id)
                put("type", "media_player/browse_media")
                put("entity_id", entityId)
                contentId?.let { put("media_content_id", it) }
                contentType?.let { put("media_content_type", it) }
            }
        send(msg)
        val reply = withTimeoutOrNull(MEDIA_BROWSE_TIMEOUT) { deferred.await() }
        pending.remove(id)
        return reply?.get("result")?.jsonObject
    }

    /**
     * Fetch a weather forecast via `weather.get_forecasts` (modern HA no longer
     * exposes `forecast` as an attribute). Returns the forecast array
     * (each item: datetime, condition, temperature, templow), or null.
     */
    suspend fun getForecast(entityId: String, forecastType: String = "daily"): JsonArray? {
        val id = idCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val msg =
            buildJsonObject {
                put("id", id)
                put("type", "call_service")
                put("domain", "weather")
                put("service", "get_forecasts")
                put("service_data", buildJsonObject { put("type", forecastType) })
                put("target", buildJsonObject { put("entity_id", entityId) })
                put("return_response", true)
            }
        send(msg)
        val reply = withTimeoutOrNull(RESPONSE_TIMEOUT) { deferred.await() }
        pending.remove(id)
        val response =
            reply
                ?.get("result")
                ?.jsonObject
                ?.get("response")
                ?.jsonObject ?: return null
        return response[entityId]?.jsonObject?.get("forecast")?.jsonArray
    }

    /** Play a specific media item on a player. */
    /**
     * [enqueue] mirrors `media_player.play_media`'s own optional field
     * ("add", "next", "play", "replace" — see Home Assistant's docs); left
     * null (the default) for the existing immediate-replace behavior every
     * other call site already relies on. Only the Media Browser's long-press
     * "Add to queue" action passes `"add"` today.
     */
    fun playMedia(entityId: String, contentId: String, contentType: String, enqueue: String? = null) {
        val data =
            buildMap<String, JsonElement> {
                put("media_content_id", JsonPrimitive(contentId))
                put("media_content_type", JsonPrimitive(contentType))
                if (enqueue != null) put("enqueue", JsonPrimitive(enqueue))
            }
        callService(ServiceCall("media_player", "play_media", entityId, data))
    }

    // ---- internals ----------------------------------------------------------

    private val listener =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Socket open, waiting for auth_required")
                _connection.value = ConnectionState.AUTHENTICATING
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = json.parseToJsonElement(text).jsonObject
                    when (obj["type"]?.jsonPrimitive?.content) {
                        "auth_required" -> sendAuth()
                        "auth_ok" -> onAuthOk()
                        "auth_invalid" -> _connection.value = ConnectionState.AUTH_FAILED
                        "result" -> {
                            // Route replies to an awaiting command if one matches this
                            // id; otherwise treat it as the get_states seed.
                            val id = obj["id"]?.jsonPrimitive?.intOrNull
                            val waiter = id?.let { pending.remove(it) }
                            if (waiter != null) waiter.complete(obj) else onResult(obj)
                        }
                        "event" -> onEvent(obj)
                        "pong" -> { /* heartbeat ok */ }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "onMessage parse error", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Socket failure", t)
                _connection.value = ConnectionState.ERROR
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Socket closed $code $reason")
                if (_connection.value != ConnectionState.DISCONNECTED) scheduleReconnect()
            }
        }

    private fun sendAuth() {
        val msg =
            buildJsonObject {
                put("type", "auth")
                put("access_token", token)
            }
        // NOTE: auth message must NOT include an id (HA rejects it otherwise).
        socket?.send(msg.toString())
    }

    private fun onAuthOk() {
        Log.i(TAG, "Authenticated")
        _connection.value = ConnectionState.CONNECTED
        startPublisher()
        requestStates()
        subscribeStateChanges()
        startHeartbeat()
    }

    /** Coalesce entity updates: publish the store to the StateFlow at a bounded rate. */
    private fun startPublisher() {
        if (publisherStarted) return
        publisherStarted = true
        scope.launch {
            while (true) {
                delay(PUBLISH_INTERVAL)
                if (entitiesDirty) {
                    entitiesDirty = false
                    _entities.value = HashMap(entityStore)
                }
            }
        }
    }

    private fun requestStates() {
        val msg =
            buildJsonObject {
                put("id", idCounter.getAndIncrement())
                put("type", "get_states")
            }
        send(msg)
    }

    private fun subscribeStateChanges() {
        val msg =
            buildJsonObject {
                put("id", idCounter.getAndIncrement())
                put("type", "subscribe_events")
                put("event_type", "state_changed")
            }
        send(msg)
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob =
            scope.launch {
                while (_connection.value == ConnectionState.CONNECTED) {
                    delay(PING_INTERVAL)
                    val ping =
                        buildJsonObject {
                            put("id", idCounter.getAndIncrement())
                            put("type", "ping")
                        }
                    send(ping)
                }
            }
    }

    /** Result of get_states arrives as an array in the `result` field. */
    private fun onResult(obj: JsonObject) {
        // Service-call results have `result: null` or `result: {}` (not an
        // array) — nothing to seed from, just check success and return.
        val resultEl = obj["result"] ?: return
        if (resultEl !is JsonArray) {
            val success = obj["success"]?.jsonPrimitive?.content
            if (success == "false") {
                Log.w(TAG, "Service call failed: ${obj["error"]}")
            }
            return
        }
        val result = resultEl
        for (el in result) {
            val e = el.jsonObject
            val entityId = e["entity_id"]?.jsonPrimitive?.content ?: continue
            entityStore[entityId] =
                EntityState(
                    entityId = entityId,
                    state = e["state"]?.jsonPrimitive?.content ?: "unknown",
                    attributes = e["attributes"]?.jsonObject ?: JsonObject(emptyMap()),
                    lastChanged = e["last_changed"]?.jsonPrimitive?.content,
                    lastUpdated = e["last_updated"]?.jsonPrimitive?.content
                )
        }
        // Seed is important — publish immediately so the first frame has data.
        _entities.value = HashMap(entityStore)
    }

    /** state_changed events carry event.data.new_state. */
    private fun onEvent(obj: JsonObject) {
        val data = obj["event"]?.jsonObject?.get("data")?.jsonObject ?: return
        val newState = data["new_state"]?.jsonObject ?: return
        val entityId = newState["entity_id"]?.jsonPrimitive?.content ?: return
        entityStore[entityId] =
            EntityState(
                entityId = entityId,
                state = newState["state"]?.jsonPrimitive?.content ?: "unknown",
                attributes = newState["attributes"]?.jsonObject ?: JsonObject(emptyMap()),
                lastChanged = newState["last_changed"]?.jsonPrimitive?.content,
                lastUpdated = newState["last_updated"]?.jsonPrimitive?.content
            )
        entitiesDirty = true // published by the coalescing publisher loop
    }

    private fun send(msg: JsonObject) {
        socket?.send(msg.toString())
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(RECONNECT_DELAY)
            if (_connection.value == ConnectionState.ERROR) connect()
        }
    }

    private fun toWebSocketUrl(base: String): String {
        val trimmed = base.trimEnd('/')
        val ws =
            when {
                trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
                trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
                else -> "ws://$trimmed"
            }
        return "$ws/api/websocket"
    }
}
