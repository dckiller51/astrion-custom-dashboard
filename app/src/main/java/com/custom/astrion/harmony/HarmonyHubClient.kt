package com.custom.astrion.harmony

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/** One remote-control command exposed by a [HarmonyDevice] (e.g. "VolumeUp"). */
data class HarmonyCommand(val name: String, val label: String)

/** One device known to the hub (TV, receiver, etc.), with its available commands. */
data class HarmonyDevice(val id: String, val label: String, val commands: List<HarmonyCommand>)

/** One Activity known to the hub (e.g. "Watch Apple TV"). id "-1" is always PowerOff. */
data class HarmonyActivity(val id: String, val label: String)

/** Full hub config as returned by [HarmonyHubClient.getConfig] — devices + activities. */
data class HarmonyConfig(val devices: List<HarmonyDevice>, val activities: List<HarmonyActivity>)

/**
 * The hub's own live state for its currently-running Activity, mirroring the
 * "starting" vs "started" distinction Home Assistant's official integration
 * exposes (see data.py: new_activity_starting / new_activity). "-1" always
 * means PowerOff/idle.
 */
sealed class HarmonyActivityState {
    abstract val activityId: String

    data class Starting(override val activityId: String) : HarmonyActivityState()

    data class Active(override val activityId: String) : HarmonyActivityState()
}

/**
 * Direct WebSocket client for a Logitech Harmony Hub, bypassing Home Assistant.
 * Replicates the behavior of the original Harmony Elite remote control.
 *
 * Protocol details (community reverse-engineered, non-official):
 *   ws://<HUB_IP>:8088/?domain=svcs.myharmony.com&hubId=<HUB_ID>
 *   Required Header: Origin: http://sl.dhg.myharmony.com
 *
 * NOTE: This local WebSocket API is not officially documented by Logitech.
 */
@Suppress("SpellCheckingInspection")
class HarmonyHubClient(
    private val hubIp: String,
    private var hubId: String,
    private val onConnected: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private companion object {
        const val TAG = "HarmonyHubClient"
        val RECONNECT_DELAY = 2000.milliseconds
        val PRESS_HOLD_DELAY = 120.milliseconds
        val CONFIG_TIMEOUT = 8000.milliseconds
    }

    private val client =
        OkHttpClient
            .Builder()
            .readTimeout(0.seconds.toJavaDuration())
            .callTimeout(10.seconds.toJavaDuration())
            // Sends a WS ping every 20s — most embedded WS servers (this hub
            // included) drop idle connections around 60s otherwise.
            .pingInterval(20.seconds.toJavaDuration())
            .build()

    private var webSocket: WebSocket? = null
    private val nextMsgId = AtomicInteger(1)

    /** Set by disconnect() so onFailure/onClosed know not to auto-reconnect. */
    private var intentionalDisconnect = false
    private var clientScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _connected = MutableStateFlow(false)

    /** Live connection state, for a status indicator on the settings page. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _currentActivityId = MutableStateFlow<String?>(null)

    /**
     * The hub's own idea of which Activity is currently running — "-1" means
     * PowerOff (everything off), null means unknown (not yet received a
     * notification or answered [getCurrentActivity] since connecting).
     * Populated from unsolicited "connect.stateDigest?notify" push frames in
     * [handleUnsolicited]. Confirmed against a real hub capture (2026-08-14):
     * `activityId` updates immediately when a transition starts (activityStatus
     * 1), well before `runningActivityList` catches up a couple frames later —
     * use `activityId`, not `runningActivityList`, for anything time-sensitive.
     */
    val currentActivityId: StateFlow<String?> = _currentActivityId.asStateFlow()

    private val _activityState = MutableStateFlow<HarmonyActivityState?>(null)

    /** Same info as [currentActivityId], plus the starting/active distinction. */
    val activityState: StateFlow<HarmonyActivityState?> = _activityState.asStateFlow()

    /** Outstanding request/response commands (currently just getConfig), keyed by the hbus msg id. */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    /** Updates the hubId dynamically if it was initially resolved from a blank value during discovery. */
    fun updateHubId(newHubId: String) {
        this.hubId = newHubId
    }

    fun connect() {
        if (hubIp.isBlank()) {
            Log.w(TAG, "connect() called with no hub IP — Harmony not configured, skipping")
            return
        }

        if (!clientScope.isActive) {
            clientScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }

        intentionalDisconnect = false
        val url = "ws://$hubIp:8088/?domain=svcs.myharmony.com&hubId=$hubId"
        Log.d(TAG, "connect() -> $url")
        val request =
            Request
                .Builder()
                .url(url)
                .addHeader("Origin", "http://sl.dhg.myharmony.com")
                .build()

        webSocket =
            client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.d(TAG, "onOpen: handshake HTTP ${response.code}")
                        _connected.value = true
                        onConnected()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        Log.d(TAG, "onMessage: $text")
                        routeMessage(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Log.w(TAG, "onClosing: code=$code reason=$reason")
                        _connected.value = false
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.w(TAG, "onClosed: code=$code reason=$reason")
                        _connected.value = false
                        scheduleReconnect()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val detail =
                            buildString {
                                append(t.javaClass.simpleName)
                                append(": ")
                                append(t.message ?: "(no message)")
                                if (response != null) {
                                    append(" | HTTP ${response.code} ${response.message}")
                                    runCatching { response.body?.string() }.getOrNull()?.let { body ->
                                        if (body.isNotBlank()) append(" | body: ${body.take(200)}")
                                    }
                                }
                            }
                        Log.e(TAG, "onFailure: $detail", t)
                        _connected.value = false
                        onError(detail)
                        scheduleReconnect()
                    }
                }
            )
    }

    /**
     * Routes an incoming frame to a waiting request/response caller
     * ([getConfig], [getCurrentActivity]) if its "id" matches an outstanding
     * request, or — for frames with no "id" — treats it as an unsolicited
     * push notification and looks for an Activity-change digest.
     */
    private fun routeMessage(text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        val id = obj.opt("id") as? String
        if (id != null) {
            pending.remove(id)?.complete(obj)
            return
        }
        handleUnsolicited(obj)
    }

    /**
     * Handles the hub's "stateDigest" push, sent whenever the running
     * Activity changes (and periodically as a heartbeat). Confirmed shape,
     * cross-checked across several independent captures:
     *
     *   {"type": "connect.stateDigest?notify",
     *    "data": {"activityId": "11553793", "activityStatus": 2,
     *              "runningActivityList": "11553793", ...many other fields}}
     *
     * `activityStatus`: 0 = none/off, 1 = starting (transition in progress),
     * 2 = running steady. Anything else is treated like "running" so a
     * status code this app doesn't recognize yet still updates
     * [currentActivityId] rather than silently doing nothing.
     */
    private fun handleUnsolicited(obj: JSONObject) {
        if (obj.optString("type") != "connect.stateDigest?notify") return
        val data = obj.optJSONObject("data") ?: return
        val activityId = data.optString("activityId").takeIf { it.isNotBlank() } ?: return
        Log.d(TAG, "stateDigest: activityId=$activityId activityStatus=${data.opt("activityStatus")}")
        _currentActivityId.value = activityId
        _activityState.value =
            when (data.optInt("activityStatus", 2)) {
                1 -> HarmonyActivityState.Starting(activityId)
                else -> HarmonyActivityState.Active(activityId)
            }
    }

    /**
     * One-shot query for the Activity running right now — needed right after
     * [connect] since the push notification in [handleUnsolicited] only
     * fires on the *next change*, not retroactively for whatever was already
     * running. Same request/response plumbing as [getConfig].
     *
     * Returns null on timeout, if not connected, or on a malformed response.
     */
    suspend fun getCurrentActivity(): String? {
        val socket =
            webSocket ?: run {
                Log.w(TAG, "getCurrentActivity() called while not connected")
                return null
            }
        val id = nextMsgId.getAndIncrement().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred

        val hbus =
            JSONObject().apply {
                put("cmd", "vnd.logitech.harmony/vnd.logitech.harmony.engine?getCurrentActivity")
                put("id", id)
                put("params", JSONObject().apply { put("verb", "get") })
            }
        val envelope =
            JSONObject().apply {
                put("hubId", hubId)
                put("timeout", 30)
                put("hbus", hbus)
            }

        Log.d(TAG, "getCurrentActivity(): $envelope")
        socket.send(envelope.toString())

        val reply =
            try {
                withTimeoutOrNull(CONFIG_TIMEOUT) { deferred.await() }
            } finally {
                pending.remove(id)
            }
        val activityId = reply?.optJSONObject("data")?.optString("result")?.takeIf { it.isNotBlank() }
        if (activityId == null) {
            Log.w(TAG, "getCurrentActivity() timed out or malformed reply")
        } else {
            _currentActivityId.value = activityId
            _activityState.value = HarmonyActivityState.Active(activityId)
        }
        return activityId
    }

    private fun scheduleReconnect() {
        if (intentionalDisconnect) return
        Log.d(TAG, "Reconnecting in ${RECONNECT_DELAY.inWholeMilliseconds}ms...")
        clientScope.launch {
            delay(RECONNECT_DELAY)
            connect()
        }
    }

    fun disconnect() {
        intentionalDisconnect = true
        _connected.value = false
        // Cancels any ongoing delayed actions or reconnect jobs when disconnected.
        clientScope.cancel()
        pending.values.forEach { it.cancel() }
        pending.clear()
        webSocket?.close(1000, "Normal closure")
        webSocket = null
    }

    /**
     * Starts a Harmony Activity (e.g. "Watch Apple TV"), NOT a simple IR command.
     * Uses a different mechanism on the hub side (runactivity vs holdAction).
     * `activityId` comes from harmony_config.json (data[0].activity[].id), or
     * from [getConfig] directly. PowerOff uses a special ID: "-1".
     *
     * JSON payload format confirmed by community implementations
     * (NovaGL/diy-harmonyhub, DavidPhillipOster/DIYHarmonyApp):
     *   {"cmd": "harmony.activityengine?runactivity", "params": {"activityId": "-1"}}
     */
    fun startActivity(activityId: String) {
        val params =
            JSONObject().apply {
                put("activityId", activityId)
            }

        val hbus =
            JSONObject().apply {
                put("cmd", "harmony.activityengine?runactivity")
                put("id", nextMsgId.getAndIncrement().toString())
                put("params", params)
            }

        val envelope =
            JSONObject().apply {
                put("hubId", hubId)
                put("timeout", 30)
                put("hbus", hbus)
            }

        val payload = envelope.toString()
        Log.d(TAG, "startActivity($activityId): $payload")
        val sent = webSocket?.send(payload)
        Log.d(TAG, "send() returned: $sent")
    }

    fun sendCommand(deviceId: String, command: String) {
        val timestamp = System.currentTimeMillis()
        val action =
            JSONObject().apply {
                put("type", "IRCommand")
                put("deviceId", deviceId)
                put("command", command)
            }

        sendHoldAction(status = "press", action = action, timestamp = timestamp)

        if (webSocket != null) {
            clientScope.launch {
                delay(PRESS_HOLD_DELAY)
                sendHoldAction(status = "release", action = action, timestamp = timestamp)
            }
        }
    }

    private fun sendHoldAction(status: String, action: JSONObject, timestamp: Long) {
        val params =
            JSONObject().apply {
                put("status", status)
                put("timestamp", timestamp.toString())
                put("verb", "render")
                put("action", action.toString())
            }

        val hbus =
            JSONObject().apply {
                put("cmd", "vnd.logitech.harmony/vnd.logitech.harmony.engine?holdAction")
                put("id", nextMsgId.getAndIncrement().toString())
                put("params", params)
            }

        val envelope =
            JSONObject().apply {
                put("hubId", hubId)
                put("timeout", 30)
                put("hbus", hbus)
            }

        val payload = envelope.toString()
        Log.d(TAG, "send: $payload")
        val sent = webSocket?.send(payload)
        Log.d(TAG, "send() returned: $sent (false = socket not open/queue full)")
    }

    /**
     * Fetches the hub's full config — every paired device with its available
     * IR commands, plus every Activity — the same data Home Assistant's
     * Harmony integration keeps in `harmony_<hubId>.conf`. Lets users manage
     * a Harmony hub from this app without ever needing Home Assistant.
     *
     * Returns null on timeout, if not connected, or on a malformed response.
     * Safe to call repeatedly (e.g. to refresh after re-pairing a device).
     */
    suspend fun getConfig(): HarmonyConfig? {
        val socket =
            webSocket ?: run {
                Log.w(TAG, "getConfig() called while not connected")
                return null
            }
        val id = nextMsgId.getAndIncrement().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred

        val params =
            JSONObject().apply {
                put("verb", "get")
                put("format", "json")
            }
        val hbus =
            JSONObject().apply {
                put("cmd", "vnd.logitech.harmony/vnd.logitech.harmony.engine?config")
                put("id", id)
                put("params", params)
            }
        val envelope =
            JSONObject().apply {
                put("hubId", hubId)
                put("timeout", 30)
                put("hbus", hbus)
            }

        Log.d(TAG, "getConfig(): $envelope")
        socket.send(envelope.toString())

        val reply =
            try {
                withTimeoutOrNull(CONFIG_TIMEOUT) { deferred.await() }
            } finally {
                pending.remove(id)
            }

        if (reply == null) {
            Log.w(TAG, "getConfig() timed out")
            return null
        }
        return runCatching { parseConfig(reply) }
            .onFailure { Log.e(TAG, "getConfig(): malformed response", it) }
            .getOrNull()
    }

    private fun parseConfig(reply: JSONObject): HarmonyConfig {
        val data = extractConfigData(reply)
        val devices = parseDevices(data.optJSONArray("device") ?: JSONArray())
        val activities = parseActivities(data.optJSONArray("activity") ?: JSONArray())
        return HarmonyConfig(devices = devices, activities = activities)
    }

    /**
     * The hub's "config" response nests its payload in a `data` field that,
     * depending on hub firmware, is either a JSON object directly or a JSON
     * *string* that itself needs parsing.
     */
    private fun extractConfigData(reply: JSONObject): JSONObject = when (val rawData = reply.opt("data")) {
        is JSONObject -> rawData
        is String -> JSONObject(rawData)
        else -> error("no \"data\" field in config response")
    }

    private fun parseDevices(arr: JSONArray): List<HarmonyDevice> = (0 until arr.length()).map { i -> parseDevice(arr.getJSONObject(i)) }

    private fun parseDevice(d: JSONObject): HarmonyDevice {
        val groups = d.optJSONArray("controlGroup") ?: JSONArray()
        val commands = (0 until groups.length()).flatMap { g ->
            parseFunctions(groups.getJSONObject(g).optJSONArray("function") ?: JSONArray())
        }
        return HarmonyDevice(
            id = d.optString("id"),
            label = d.optString("label", d.optString("name", d.optString("id"))),
            commands = commands
        )
    }

    private fun parseFunctions(functions: JSONArray): List<HarmonyCommand> =
        (0 until functions.length()).mapNotNull { f -> parseCommand(functions.getJSONObject(f)) }

    private fun parseCommand(fn: JSONObject): HarmonyCommand? {
        val displayName = fn.optString("name")
        val label = fn.optString("label", displayName)
        val irCommand = (extractIrCommand(fn) ?: displayName).takeIf { it.isNotBlank() } ?: return null
        return HarmonyCommand(name = irCommand, label = label.ifBlank { irCommand })
    }

    /**
     * The real IR command the hub expects lives in the nested "action" JSON
     * string (e.g. {"command":"OK",...}) — "name"/"label" are just UI display
     * labels and can differ from it (e.g. name="Select" but action.command="OK").
     * Returns null if "action" is missing, malformed, or has no "command".
     */
    private fun extractIrCommand(fn: JSONObject): String? = runCatching {
        fn.optString("action")
            .takeIf { it.isNotBlank() }
            ?.let { JSONObject(it).optString("command") }
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun parseActivities(arr: JSONArray): List<HarmonyActivity> = (0 until arr.length()).map { i ->
        val a = arr.getJSONObject(i)
        HarmonyActivity(
            id = a.optString("id"),
            label = a.optString("label", a.optString("name", a.optString("id")))
        )
    }
}
