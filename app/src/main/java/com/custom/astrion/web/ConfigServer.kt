package com.custom.astrion.web

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import com.custom.astrion.BuildConfig
import com.custom.astrion.R
import com.custom.astrion.appletv.AppleTvCredentials
import com.custom.astrion.appletv.AppleTvRegistry
import com.custom.astrion.appletv.AppleTvServiceKind
import com.custom.astrion.config.ActivityRuntime
import com.custom.astrion.config.AppleTvConfig
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.ExtenderConfig
import com.custom.astrion.config.HarmonyHubConfig
import com.custom.astrion.config.IrDatabaseRuntime
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.ha.ConnectionState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.harmony.HarmonyHubDiscovery
import com.custom.astrion.harmony.HarmonyHubRegistry
import com.custom.astrion.update.UpdateChecker
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tiny local web server (http://<remote-ip>:8080) that lets anyone on the
 * same network configure this device without adb — the same pattern the
 * official panel firmware uses for its own local config page. Serves:
 *
 *  GET  /                the Devices page (bundled from assets/docs/devices.html):
 *                        Home Assistant, Harmony Hub(s), IR Devices — "+ Add
 *                        device", Home-Assistant-integrations style. Every
 *                        device is configured exactly once here; the dashboard
 *                        builder below only references them by name.
 *  GET  /builder         redirects to /builder/
 *  GET  /builder/        the dashboard/card editor UI (bundled from
 *                        assets/docs/index.html) — layout, cards, hotkeys,
 *                        Activities, theme. Has no device-editing UI of its
 *                        own; only exists served from this device, no
 *                        offline/standalone mode.
 *  GET  /<file>          any other file under assets/docs/ (styles.css,
 *                        every file under js/, images) served straight from
 *                        the root — shared by both / (devices.html) and
 *                        /builder/ (index.html, via /builder/<file>)
 *  POST /save-connection save HA_URL / HA_TOKEN / Harmony hub list — always
 *                        posted as a whole (see parseHubRows), so any single
 *                        device's form on / must resend every hub, not just
 *                        the one it's editing — then restarts the activity to
 *                        reconnect with them
 *  GET  /harmony-config  fetch a paired hub's devices/commands/activities as JSON
 *                        (?hub=<localId>, defaults to the first configured hub).
 *                        Cached to astrion/harmony_<hubId>.json next to
 *                        dashboard.json on every successful fetch, and served
 *                        from that cache if the hub is temporarily unreachable.
 *  GET  /harmony-hubs    list configured hubs (id + name only) — feeds the
 *                        read-only "Hub" dropdown inside the dashboard
 *                        builder's card/hotkey forms
 *  GET  /devices-config  full read-back of everything /save-connection accepts
 *                        (HA url/token/webhook + Harmony hubs incl. ip/hubId) —
 *                        lets the Devices page (/) pre-fill a device's edit form
 *  GET  /appletv-scan    browse the LAN (mDNS) for Apple TVs — used by the
 *                        Devices page's "Apple TV" section
 *  POST /appletv-pair-start   begin PIN pairing with one Apple TV
 *                        (host, port) — the TV shows a code; returns a sessionId
 *  POST /appletv-pair-finish  complete pairing (sessionId, pin) and return the
 *                        credentials, which the page then saves with the form
 *  GET  /appletv-mrp-scan     browse the LAN for the Apple TV's separate MRP service — step 2,
 *                        optional, only meaningful once Companion is already paired
 *  POST /appletv-mrp-pair-start   begin MRP PIN pairing (host, port) — returns a sessionId
 *  POST /appletv-mrp-pair-finish  complete MRP pairing (sessionId, pin) and return the credentials
 *  GET  /appletv-artwork/<localId>  the current artwork for a paired Apple TV, if MRP has fetched
 *                        one; this is what a device's `entity_picture` attribute points at
 *  GET  /harmony-discover resolve a hub's numeric hubId from its IP alone
 *                        (?ip=<address>) — used by the "Auto-detect ID" button
 *  GET  /camera-snapshot proxy a single still frame for a camera.* entity
 *                        (?entity=<id>) through this device's HA token, as
 *                        image/jpeg — lets the editor preview show a real
 *                        camera frame (the browser has no HA token of its own)
 *  GET  /ha-states       snapshot of every HA entity this device currently knows
 *                        ({entity_id: {state, friendly_name, attributes}}) plus
 *                        a `connected` flag — lets the dashboard editor preview
 *                        render with live HA data instead of the static mocks
 *  GET  /dashboard.json  download the current dashboard.json (backup) — also
 *                        how the Devices page (/) reads the "irDevices" array,
 *                        since IR devices are stored inside dashboard.json
 *  POST /dashboard.json  replace dashboard.json, then live-reload the dashboard
 *                        — also how the Devices page (/) saves IR device
 *                        add/edit/remove, re-posting the whole file with only
 *                        "irDevices" changed
 *  POST /icons           upload a PNG into /sdcard/astrion/icons/
 *  GET  /icons-list       list every uploaded icon's filename, as JSON — feeds
 *                        the dashboard editor's icon picker
 *  GET  /icons/<file>     serve an uploaded icon back out — lets the dashboard
 *                        editor's picker/preview (docs/js/cards.js) show a
 *                        card's real configured icon when opened from this
 *                        device (/builder/)
 *  GET  /check-update     check this project's GitHub Releases for a newer build
 *  POST /install-update   download the newer APK and open the system installer
 *  POST /install-beta-update  same as /install-update but against the rolling
 *                        dev-latest pre-release instead — always installs
 *                        whatever the tag currently points to, no separate
 *                        check step (used by the beta toggle in the builder,
 *                        docs/js/hotkeys.js's installBetaUpdate())
 *  GET  /pages            list this device's dashboard pages (id + name), in
 *                        pager order — lets a remote controller (e.g. the
 *                        Home Assistant "astrion" integration) discover what
 *                        it can navigate to without hardcoding page names
 *  GET  /current-page     the page currently visible on screen, as JSON
 *                        {"index":N,"name":"..."} — polled by HA to keep a
 *                        select entity's state in sync with on-device swipes
 *  POST /set-page         jump the dashboard to a page by name (form field
 *                        `page`, case-insensitive) — the remote-control
 *                        counterpart of a hardware shortcut button or a
 *                        card's navigateToPage(); same instant scrollToPage,
 *                        no visible scroll through intermediate pages
 *  GET  /version            `{"version","versionCode"}` — the installed
 *                        app's own version, static per build.
 *  GET  /battery         { level, charging } — this device's own battery
 *                        status, for the companion HA integration
 *  GET  /activities        every trackable Activity (`"track": true` tile,
 *                        hotkey, or composed AppConfig.activities entry),
 *                        as JSON `[{"id","name","room","icon"},...]` — lets
 *                        a remote controller build one picker per room
 *                        without parsing dashboard.json itself
 *  GET  /activities/active which Activity is active in each room right now,
 *                        as JSON `{"<room>": {"id","name"} | null, ...}` —
 *                        polled by HA to keep a per-room select/sensor in
 *                        sync with taps made on the device itself, another
 *                        remote, or the physical Harmony remote
 *  POST /activities/start start an Activity by id (form field `id`) — same
 *                        effect as tapping its tile, works for both a
 *                        composed Activity and a lightweight `track: true`
 *                        one with a Harmony activityId
 *  POST /activities/stop  stop whichever Activity is active in a room (form
 *                        field `room`) without starting another. For a
 *                        Harmony-backed Activity this sends PowerOff to
 *                        *that Activity's own hub only* — Harmony has no
 *                        per-Activity stop command, a hub always runs
 *                        exactly one Activity, so this is the narrowest
 *                        possible "stop" and never touches a different
 *                        room's hub. This is the piece a plain hardware
 *                        "turn everything off" button doesn't give you.
 *  POST /ring              "find my remote": plays this device's own
 *                        ringtone/alarm/notification sound on loop for a
 *                        few seconds, at a chosen volume, so a misplaced
 *                        tablet/remote can be located by ear — the same
 *                        idea as a phone's "find my device" ring. Form
 *                        fields, all optional: `volume` (1-100, percent of
 *                        the alarm stream's max, default 80), `sound`
 *                        (`ringtone` | `alarm` | `notification`, default
 *                        `ringtone`), `duration` (seconds, 1-60, default
 *                        15). Restores the device's original alarm volume
 *                        once done. A second call replaces any ring already
 *                        in progress rather than layering sounds.
 *  POST /ring/stop         cancel an in-progress /ring immediately and
 *                        restore the original alarm volume.
 *
 * Deliberately has no auth — this device is assumed to live on a trusted
 * home LAN, the same assumption Home Assistant itself makes for local
 * network access.
 */
class ConfigServer(
    private val context: Context,
    private val harmonyRegistry: HarmonyHubRegistry,
    private val haClient: HaClient,
    private val onConnectionSaved: () -> Unit,
    private val onDashboardUpdated: () -> Unit,
    /** Current dashboard's page names, in pager order — read fresh on every
     * request so /pages and /set-page always reflect whatever dashboard.json
     * is loaded right now, without ConfigServer holding its own stale copy. */
    private val getPageNames: () -> List<String>,
    /** Index of the page currently visible in the pager, or null if unknown
     * (e.g. before the first frame). Backs GET /current-page. */
    private val getCurrentPageIndex: () -> Int?,
    /** Requests a jump to the page at this index — same navTarget mechanism
     * MainActivity already uses for hardware shortcut buttons. Returns
     * nothing; the pager applies it on the main thread on its own schedule. */
    private val onSetPage: (Int) -> Unit,
    /** Read fresh on every request, same reasoning as [getPageNames] — null
     * for the brief window before Dashboard.kt's first composition hands
     * one up (see MainActivity's own doc comment on its `activityRuntime`
     * field). Backs /activities and /activities/active. */
    private val getActivityRuntime: () -> ActivityRuntime?,
    /** Starts an Activity by id — the remote-control counterpart of tapping
     * its tile. Backs POST /activities/start. */
    private val onStartActivity: (activityId: String) -> Unit,
    /** Stops whichever Activity is active in a room. Backs POST /activities/stop. */
    private val onStopActivity: (room: String) -> Unit
) : NanoHTTPD(8080) {
    /**
     * Backs the Devices page's Apple TV discovery/pairing endpoints. Assigned right after
     * construction (rather than passed to the constructor, which is already at the limit of
     * what's readable); until then those endpoints answer 503.
     */
    @Volatile
    var appleTvRegistry: AppleTvRegistry? = null

    @Volatile
    private var lastResult: UpdateChecker.CheckResult? = null

    // ---- ring ("find my remote") state ------------------------------------
    // All three only ever touched from NanoHTTPD's request-handling threads
    // and the single Handler callback that clears them, never concurrently
    // with UI code, so no extra synchronization beyond @Volatile is needed.
    @Volatile
    private var ringMediaPlayer: MediaPlayer? = null

    @Volatile
    private var ringStopHandler: Handler? = null

    @Volatile
    private var ringOriginalAlarmVolume: Int? = null

    private val iconsDir: File
        get() = File(Environment.getExternalStorageDirectory(), "astrion/icons").apply { mkdirs() }

    private val irDatabaseDir: File
        get() = File(Environment.getExternalStorageDirectory(), "astrion/ir-database").apply { mkdirs() }

    override fun serve(session: IHTTPSession): Response = try {
        val method = session.method
        when (val uri = session.uri) {
            "/" -> if (method == Method.GET) serveRootDocsAsset("/") else methodNotAllowed()
            "/dashboard.json" ->
                when (method) {
                    Method.GET -> serveDashboardJson()
                    Method.POST -> handleDashboardUpload(session)
                    else -> methodNotAllowed()
                }

            "/builder" -> if (method == Method.GET) redirectBuilder() else methodNotAllowed()
            "/harmony-config" -> if (method == Method.GET) serveHarmonyConfig(session) else methodNotAllowed()
            "/harmony-hubs" -> if (method == Method.GET) serveHarmonyHubs() else methodNotAllowed()
            "/devices-config" -> if (method == Method.GET) serveDevicesConfig() else methodNotAllowed()
            "/harmony-discover" -> if (method == Method.GET) serveHarmonyDiscover(session) else methodNotAllowed()
            "/appletv-scan" -> if (method == Method.GET) serveAppleTvScan() else methodNotAllowed()
            "/appletv-pair-start" -> if (method == Method.POST) handleAppleTvPairStart(session) else methodNotAllowed()
            "/appletv-pair-finish" -> if (method == Method.POST) handleAppleTvPairFinish(session) else methodNotAllowed()
            "/appletv-mrp-scan" -> if (method == Method.GET) serveAppleTvMrpScan() else methodNotAllowed()
            "/appletv-mrp-pair-start" -> if (method == Method.POST) handleAppleTvMrpPairStart(session) else methodNotAllowed()
            "/appletv-mrp-pair-finish" -> if (method == Method.POST) handleAppleTvMrpPairFinish(session) else methodNotAllowed()
            "/camera-snapshot" -> if (method == Method.GET) serveCameraSnapshot(session) else methodNotAllowed()
            "/ha-states" -> if (method == Method.GET) serveHaStates() else methodNotAllowed()
            "/icons-list" -> if (method == Method.GET) serveIconsList() else methodNotAllowed()
            "/check-update" -> if (method == Method.GET) handleCheckUpdate() else methodNotAllowed()
            "/save-connection" -> if (method == Method.POST) handleSaveConnection(session) else methodNotAllowed()
            "/icons" -> if (method == Method.POST) handleIconUpload(session) else methodNotAllowed()
            "/ir-database" ->
                when (method) {
                    Method.GET -> serveIrDatabaseList()
                    Method.POST -> handleIrDatabaseUpload(session)
                    else -> methodNotAllowed()
                }
            "/install-update" -> if (method == Method.POST) handleInstallUpdate() else methodNotAllowed()
            "/install-beta-update" -> if (method == Method.POST) handleInstallBetaUpdate() else methodNotAllowed()
            "/pages" -> if (method == Method.GET) servePages() else methodNotAllowed()
            "/current-page" -> if (method == Method.GET) serveCurrentPage() else methodNotAllowed()
            "/version" -> if (method == Method.GET) serveVersion() else methodNotAllowed()
            "/battery" -> if (method == Method.GET) serveBattery() else methodNotAllowed()
            "/set-page" -> if (method == Method.POST) handleSetPage(session) else methodNotAllowed()
            "/activities" -> if (method == Method.GET) serveActivities() else methodNotAllowed()
            "/activities/active" -> if (method == Method.GET) serveActiveActivities() else methodNotAllowed()
            "/activities/start" -> if (method == Method.POST) handleStartActivity(session) else methodNotAllowed()
            "/activities/stop" -> if (method == Method.POST) handleStopActivity(session) else methodNotAllowed()
            "/ring" -> if (method == Method.POST) handleRing(session) else methodNotAllowed()
            "/ring/stop" -> if (method == Method.POST) handleStopRing() else methodNotAllowed()
            else ->
                when {
                    uri.startsWith("/builder/") ->
                        if (method == Method.GET) serveBuilderAsset(uri) else methodNotAllowed()
                    uri.startsWith("/icons/") -> if (method == Method.GET) serveIcon(uri) else methodNotAllowed()
                    uri.startsWith("/ir-database/") ->
                        if (method == Method.GET) serveIrDatabaseFile(uri) else methodNotAllowed()
                    uri.startsWith("/appletv-artwork/") ->
                        if (method == Method.GET) serveAppleTvArtwork(uri) else methodNotAllowed()
                    // Any other GET falls through to a plain docs/ asset lookup —
                    // styles.css, every file under js/, images (the hero logo,
                    // favicons, future additions to assets/docs/...) all "just
                    // work" from the root without needing a route added per
                    // file, same as /builder/ already does for the dashboard
                    // editor's own assets. serveDocsAsset 404s cleanly if the
                    // file really doesn't exist.
                    method == Method.GET -> serveRootDocsAsset(uri)
                    else ->
                        newFixedLengthResponse(
                            Response.Status.NOT_FOUND,
                            "text/plain",
                            "Not found"
                        )
                }
        }
    } catch (e: Exception) {
        Log.e("ConfigServer", "request failed", e)
        newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR,
            "text/plain",
            "Error: ${e.message}"
        )
    }

    /** Also cleans up any in-progress /ring (and restores its volume change)
     * when the server itself is torn down, e.g. by reconnectWithNewSettings —
     * otherwise a ring started just before a settings save would keep
     * looping with no way left to reach /ring/stop. */
    override fun stop() {
        stopRingInternal()
        super.stop()
    }

    private fun methodNotAllowed(): Response = newFixedLengthResponse(
        Response.Status.METHOD_NOT_ALLOWED,
        "text/plain",
        "Method not allowed"
    )

    // ---- pages --------------------------------------------------------------

    private fun serveDashboardJson(): Response {
        val file = DashboardLoader.configFile
        if (!file.exists()) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "No dashboard.json yet"
            )
        }
        val content = runCatching { reconcileAppleTvHaDevices(file.readText()) }.getOrElse { file.readText() }
        return newFixedLengthResponse(Response.Status.OK, "application/json", content)
    }

    /**
     * Makes sure every paired Apple TV has a matching `haDevices` catalog entry — what the
     * media_player / source_select card pickers in the builder read from — so a paired Apple TV
     * is selectable there with no separate manual step. The builder's own JS does this too on
     * pair/rename/remove for an instant UI update, but that only runs if the Devices page happens
     * to load first; this is the authoritative, always-correct version, since it runs on every
     * dashboard.json read regardless of which page asked for it. Idempotent: leaves the file (and
     * the returned text) untouched when nothing needs to change.
     */
    private fun reconcileAppleTvHaDevices(rawJson: String): String {
        val appleTvs = RemoteSettings.appleTvs(context)
        if (appleTvs.isEmpty()) return rawJson
        val root = JSONObject(rawJson)
        val haDevices = root.optJSONArray("haDevices") ?: JSONArray()
        val byId = HashMap<String, JSONObject>()
        for (i in 0 until haDevices.length()) {
            val obj = haDevices.optJSONObject(i) ?: continue
            obj.optString("id").takeIf { it.isNotEmpty() }?.let { byId[it] = obj }
        }
        var changed = false
        for (tv in appleTvs) {
            val id = "appletv_${tv.localId}"
            val existing = byId[id]
            if (existing == null) {
                haDevices.put(JSONObject().put("id", id).put("domain", "media_player").put("entityId", tv.entityId).put("name", tv.name))
                changed = true
            } else if (existing.optString("entityId") != tv.entityId || existing.optString("name") != tv.name) {
                existing.put("entityId", tv.entityId)
                existing.put("name", tv.name)
                changed = true
            }
        }
        if (!changed) return rawJson
        root.put("haDevices", haDevices)
        val result = root.toString()
        runCatching { DashboardLoader.configFile.writeText(result) }
        return result
    }

    /**
     * Serves the dashboard/card builder straight from this device's own local
     * web server, bundled as assets/docs/ — so building a dashboard.json
     * doesn't require a separate computer or internet access, just this
     * device's own IP. No standalone/offline mode: this only ever runs
     * served from here. `/builder` -> assets/docs/index.html,
     * `/builder/js/x.js` -> assets/docs/js/x.js, etc.
     */
    private fun serveBuilderAsset(uri: String): Response {
        val relativePath = uri.removePrefix("/builder/").ifBlank { "index.html" }
        return serveDocsAsset(relativePath)
    }

    /** Root-level counterpart to [serveBuilderAsset]: serves the *devices*
     * page (assets/docs/devices.html, plus every file under its js/ folder,
     * and styles.css) at
     * the server root, so opening the device's own IP lands on "add a
     * device" first — /builder/ stays the dashboard/card editor, one level
     * down, exactly like clicking into a device from Home Assistant's
     * Settings screen doesn't also hand you the dashboard editor. */
    private fun serveRootDocsAsset(uri: String): Response {
        val relativePath = uri.removePrefix("/").ifBlank { "devices.html" }
        return serveDocsAsset(relativePath)
    }

    private fun serveDocsAsset(relativePath: String): Response {
        if (relativePath.contains("..")) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
        }
        val assetPath = "docs/$relativePath"
        val bytes =
            try {
                context.assets.open(assetPath).use { it.readBytes() }
            } catch (_: java.io.FileNotFoundException) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "Not found: $assetPath"
                )
            }
        val mime =
            when (relativePath.substringAfterLast('.', "")) {
                "html" -> "text/html; charset=utf-8"
                "js" -> "application/javascript; charset=utf-8"
                "css" -> "text/css; charset=utf-8"
                "json" -> "application/json; charset=utf-8"
                "svg" -> "image/svg+xml"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "ico" -> "image/x-icon"
                else -> "application/octet-stream"
            }
        val response =
            newFixedLengthResponse(
                Response.Status.OK,
                mime,
                bytes.inputStream(),
                bytes.size.toLong()
            )
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Expires", "0")
        return response
    }

    /** 302 redirect — used to send /builder to /builder/ so index.html's
     * relative asset paths (js/x.js, styles.css, remote.png) resolve
     * against the right base instead of the server root. */
    private fun redirectBuilder(): Response {
        val response = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "")
        response.addHeader("Location", "/builder/")
        return response
    }

    /**
     * Lists the configured Harmony hubs (id + name only — no IP/hubId, the
     * builder doesn't need those) so the dashboard editor's hotkey form can
     * offer a "Hub" dropdown before drilling into its devices/activities via
     * /harmony-config?hub=<localId>.
     */
    private fun serveHarmonyHubs(): Response {
        val json =
            JSONArray().apply {
                harmonyRegistry.configs.forEach { hub ->
                    put(
                        JSONObject().apply {
                            put("localId", hub.localId)
                            put("name", hub.name)
                        }
                    )
                }
            }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /**
     * Everything the Devices page (/, docs/js/devices-page.js) needs to
     * pre-fill its Home Assistant + Harmony Hub forms for *editing* — unlike
     * [serveHarmonyHubs] above (deliberately id+name only, for pickers), this
     * includes the HA token and each hub's ip/hubId. Same trust level as
     * /save-connection, which already accepts these back: local-only server,
     * same device, same secret either way.
     */
    private fun serveDevicesConfig(): Response {
        val json =
            JSONObject().apply {
                put(
                    "ha",
                    JSONObject().apply {
                        put("url", RemoteSettings.haUrl(context))
                        put("token", RemoteSettings.haToken(context))
                        put("webhookId", RemoteSettings.haWebhookId(context))
                    }
                )
                put(
                    "harmonyHubs",
                    JSONArray().apply {
                        RemoteSettings.harmonyHubs(context).forEach { hub ->
                            put(
                                JSONObject().apply {
                                    put("localId", hub.localId)
                                    put("name", hub.name)
                                    put("ip", hub.ip)
                                    put("hubId", hub.hubId)
                                }
                            )
                        }
                    }
                )
                put("appleTvs", appleTvsJson())
                put(
                    "extenders",
                    JSONArray().apply {
                        RemoteSettings.extenders(context).forEach { ext ->
                            put(
                                JSONObject().apply {
                                    put("localId", ext.localId)
                                    put("name", ext.name)
                                    put("host", ext.host)
                                    put("mac", ext.mac)
                                }
                            )
                        }
                    }
                )
            }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /**
     * Returns a hub's live config (devices + their commands, activities) as
     * JSON — the same data Home Assistant keeps in `harmony_<hubId>.conf`,
     * fetched straight from the hub. `?hub=<localId>` picks which configured
     * hub to query; omitted or unknown falls back to the first one. Meant to
     * be called from this page's "Fetch config" link, and later from an
     * in-app dashboard builder to autofill device/command pickers instead
     * of typing IDs by hand.
     */
    private fun serveHarmonyConfig(session: IHTTPSession): Response {
        val hubParam = session.parameters["hub"]?.firstOrNull()
        // Same fallback-to-first-hub resolution as HarmonyHubRegistry.client(),
        // but we need the HarmonyHubConfig itself (not just its client) to know
        // which cache file (by hubId) to read/write below.
        val hubConfig =
            harmonyRegistry.configs.firstOrNull { it.localId == hubParam }
                ?: harmonyRegistry.configs.firstOrNull()
                ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json",
                    """{"error":"no Harmony hub configured"}"""
                )
        val client = harmonyRegistry.client(hubConfig.localId)

        val config = client?.let { runBlocking { it.getConfig() } }
        if (config == null) {
            val cached = readHarmonyConfigCache(hubConfig)
            return if (cached != null) {
                Log.w(
                    "ConfigServer",
                    "Harmony hub '${hubConfig.name}' unreachable — serving last cached config"
                )
                newFixedLengthResponse(Response.Status.OK, "application/json", cached.toString())
            } else {
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"could not reach hub (not connected, or request timed out), and no cached config on disk yet"}"""
                )
            }
        }

        val json =
            JSONObject().apply {
                put(
                    "devices",
                    JSONArray().apply {
                        config.devices.forEach { d ->
                            put(
                                JSONObject().apply {
                                    put("id", d.id)
                                    put("label", d.label)
                                    put(
                                        "commands",
                                        JSONArray().apply {
                                            d.commands.forEach { c ->
                                                put(
                                                    JSONObject().apply {
                                                        put("name", c.name)
                                                        put("label", c.label)
                                                    }
                                                )
                                            }
                                        }
                                    )
                                }
                            )
                        }
                    }
                )
                put(
                    "activities",
                    JSONArray().apply {
                        config.activities.forEach { a ->
                            put(
                                JSONObject().apply {
                                    put("id", a.id)
                                    put("label", a.label)
                                }
                            )
                        }
                    }
                )
            }
        writeHarmonyConfigCache(hubConfig, json)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /** Cache file for a hub's config, next to dashboard.json — astrion/harmony_<hubId>.json
     * (or <localId> as a fallback if hubId is somehow blank). Named after the hub's own
     * numeric ID, same spirit as Home Assistant's harmony_<id>.conf. */
    private fun harmonyConfigCacheFile(hub: HarmonyHubConfig): File = File(
        DashboardLoader.configFile.parentFile,
        "harmony_${hub.hubId.ifBlank { hub.localId }}.json"
    )

    private fun writeHarmonyConfigCache(hub: HarmonyHubConfig, config: JSONObject) {
        try {
            val file = harmonyConfigCacheFile(hub)
            file.parentFile?.mkdirs()
            file.writeText(config.toString(2))
        } catch (e: Exception) {
            Log.e("ConfigServer", "failed to cache Harmony config for '${hub.name}'", e)
        }
    }

    private fun readHarmonyConfigCache(hub: HarmonyHubConfig): JSONObject? {
        val file = harmonyConfigCacheFile(hub)
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText()) }
            .onFailure {
                Log.e(
                    "ConfigServer",
                    "failed to read cached Harmony config for '${hub.name}'",
                    it
                )
            }.getOrNull()
    }

    /**
     * Resolves a hub's hubId from its IP alone (`?ip=<address>`), so the
     * "Auto-detect ID" button next to a hub row doesn't require the user to
     * already know (or mistakenly copy from another hub) its numeric ID —
     * see HarmonyHubDiscovery for the underlying protocol.
     */
    private fun serveHarmonyDiscover(session: IHTTPSession): Response {
        val ip = session.parameters["ip"]?.firstOrNull()?.trim()
        if (ip.isNullOrBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"error":"missing ip"}"""
            )
        }
        val hubId =
            runBlocking { HarmonyHubDiscovery.discoverHubId(ip) }
                ?: return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"could not reach hub at $ip, or unexpected response"}"""
                )
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"hubId":"$hubId"}"""
        )
    }

    // ---- remote page control ---------------------------------------------

    /**
     * Lists the current dashboard's pages (index + name), in pager order —
     * lets a remote controller (e.g. Home Assistant's "astrion" integration)
     * build a picker without hardcoding page names, and stay correct across
     * a dashboard.json reload.
     */
    private fun servePages(): Response {
        val json =
            JSONArray().apply {
                getPageNames().forEachIndexed { index, name ->
                    put(
                        JSONObject().apply {
                            put("index", index)
                            put("name", name)
                        }
                    )
                }
            }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /** The page currently visible on screen, so a remote select entity can
     * stay in sync with swipes/hardware nav that happen on the device itself. */
    private fun serveCurrentPage(): Response {
        val names = getPageNames()
        val index = getCurrentPageIndex()
        val json =
            JSONObject().apply {
                put("index", index)
                put("name", index?.let { names.getOrNull(it) })
            }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /**
     * Jumps the dashboard to a page by name (case-insensitive), the same
     * `PageConfig.name` used by hardware shortcut buttons and a card's
     * navigateToPage() — POST body/query field `page`. Applied asynchronously
     * on the main thread via onSetPage(); this only validates the name exists
     * and returns immediately, it doesn't wait for the pager to finish.
     */
    private fun handleSetPage(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files) // populates session.parameters for an urlencoded POST, same as handleSaveConnection
        val requested = session.parameters["page"]?.firstOrNull()?.trim()
        if (requested.isNullOrBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"error":"missing 'page'"}"""
            )
        }
        val names = getPageNames()
        val index = names.indexOfFirst { it.equals(requested, ignoreCase = true) }
        if (index < 0) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                """{"error":"no page named '$requested'","pages":${JSONArray(names)}}"""
            )
        }
        onSetPage(index)
        val json =
            JSONObject().apply {
                put("status", "ok")
                put("index", index)
                put("name", names[index])
            }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    // ---- version & battery --------------------------------------------------

    /** The installed app version — lets a remote controller (the Home
     * Assistant integration's `update.*` entity, primarily) know what's
     * currently running without needing adb/logcat access to the device.
     * Static per build, no dependency on the dashboard being composed yet
     * (unlike the /activities* routes), so this always answers. */
    private fun serveVersion(): Response {
        val json =
            JSONObject().apply {
                put("version", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
            }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /**
     * Battery status of the tablet running Astrion itself (not a
     * remote-controlled device) — lets the companion Home Assistant
     * integration surface the wall tablet's own battery level and charging
     * state, e.g. for an alert if a tablet that isn't permanently wired to
     * power starts running low.
     */
    private fun serveBattery(): Response {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else null
        val batteryStatus = status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging =
            batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                batteryStatus == BatteryManager.BATTERY_STATUS_FULL

        val json =
            JSONObject().apply {
                put("level", pct ?: JSONObject.NULL)
                put("charging", charging)
            }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    // ---- activities -------------------------------------------------------

    private fun activitiesUnavailable(): Response = newFixedLengthResponse(
        Response.Status.INTERNAL_ERROR,
        "application/json",
        """{"error":"dashboard not composed yet, try again shortly"}"""
    )

    /** Every trackable Activity, in [ActivityRuntime.all] order — same
     * shape regardless of whether it's a composed Activity or a lightweight
     * `track: true` tile, so a remote controller doesn't need to know which. */
    private fun serveActivities(): Response {
        val runtime = getActivityRuntime() ?: return activitiesUnavailable()
        val json =
            JSONArray().apply {
                runtime.all.forEach { a ->
                    put(
                        JSONObject().apply {
                            put("id", a.id)
                            put("name", a.name)
                            put("room", a.room)
                            put("icon", a.icon)
                        }
                    )
                }
            }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /** Which Activity is active in each room right now — `null` for a room
     * with nothing running. Every room that has at least one trackable
     * Activity gets a key here, even when currently off, so a poller can
     * discover the full room list from this one endpoint alone. */
    private fun serveActiveActivities(): Response {
        val runtime = getActivityRuntime() ?: return activitiesUnavailable()
        val rooms = runtime.all.map { it.room }.distinct()
        val json =
            JSONObject().apply {
                rooms.forEach { room ->
                    val active = runtime.activeActivity(room)
                    put(
                        room,
                        if (active == null) {
                            JSONObject.NULL
                        } else {
                            JSONObject().apply {
                                put("id", active.id)
                                put("name", active.name)
                            }
                        }
                    )
                }
            }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun handleStartActivity(session: IHTTPSession): Response {
        val runtime = getActivityRuntime() ?: return activitiesUnavailable()
        val files = HashMap<String, String>()
        session.parseBody(files)
        val id = session.parameters["id"]?.firstOrNull()?.trim()
        if (id.isNullOrBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"error":"missing 'id'"}"""
            )
        }
        if (runtime.all.none { it.id == id }) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                """{"error":"no activity with id '$id'"}"""
            )
        }
        onStartActivity(id)
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"status":"ok","id":"$id"}"""
        )
    }

    /**
     * Stops whichever Activity is active in `room` (form field `room`).
     * Unlike /activities/start, this doesn't 404 when the room is already
     * off — stopping an already-stopped room is a no-op, not an error, so
     * an HA select entity's "Off" option can be selected idempotently.
     */
    private fun handleStopActivity(session: IHTTPSession): Response {
        val runtime = getActivityRuntime() ?: return activitiesUnavailable()
        val files = HashMap<String, String>()
        session.parseBody(files)
        val room = session.parameters["room"]?.firstOrNull()?.trim()
        if (room.isNullOrBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"error":"missing 'room'"}"""
            )
        }
        if (runtime.all.none { it.room == room }) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                """{"error":"no such room '$room'"}"""
            )
        }
        onStopActivity(room)
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"status":"ok","room":"$room"}"""
        )
    }

    /**
     * Proxies a single still frame for a `camera.*` entity through this device's
     * stored HA token, returned as image/jpeg. The dashboard editor's camera
     * preview points an <img> at `/camera-snapshot?entity=camera.foo`; the
     * browser can't reach HA's token-authenticated camera_proxy endpoint itself,
     * so the app fetches it here. `no-store` so the preview shows a fresh frame
     * each time it's re-rendered.
     */
    private fun serveCameraSnapshot(session: IHTTPSession): Response {
        val entity =
            session.parameters["entity"]
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
        if (entity.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "text/plain",
                "Missing entity"
            )
        }
        val haUrl = RemoteSettings.haUrl(context)
        val haToken = RemoteSettings.haToken(context)
        if (haUrl.isBlank() || haToken.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "text/plain",
                "HA not configured"
            )
        }
        // The browser has no HA token of its own, so the app fetches the
        // token-authenticated camera_proxy frame here and re-serves it.
        val path = if (entity.startsWith("camera.")) "/api/camera_proxy/$entity" else null
        if (path == null) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "Not a camera entity: $entity"
            )
        }
        val req =
            Request
                .Builder()
                .url(haUrl.trimEnd('/') + path)
                .header("Authorization", "Bearer $haToken")
                .build()
        val bytes =
            try {
                OkHttpClient().newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return newFixedLengthResponse(
                            Response.Status.INTERNAL_ERROR,
                            "text/plain",
                            "HA returned ${resp.code}"
                        )
                    }
                    resp.body?.bytes()
                }
            } catch (e: Exception) {
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "Fetch failed: ${e.message}"
                )
            }
        if (bytes == null) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Empty response from HA"
            )
        }
        val resp =
            newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    /**
     * Snapshot of every HA entity the running app currently holds, as JSON the
     * dashboard editor (docs/js/preview.js) can render against instead of the
     * static `*_MOCK` examples. Shape:
     *   { "connected": true, "states": { "cover.x": { "state": "open",
     *       "friendly_name": "X", "attributes": { ... } }, ... } }
     *
     * `connected` reflects the WebSocket state at call time; the editor falls
     * back to the mocks when it's false (or before this endpoint has answered
     * yet on first load). Attributes are passed through
     * verbatim (the same kotlinx JsonObject the cards read), so each card's
     * preview can pull whatever domain-specific fields it needs.
     */
    private fun serveHaStates(): Response {
        val connected = haClient.connection.value == ConnectionState.CONNECTED
        val entities = haClient.entities.value
        val json =
            buildJsonObject {
                put("connected", connected)
                put(
                    "states",
                    buildJsonObject {
                        entities.forEach { (id, e) ->
                            put(
                                id,
                                buildJsonObject {
                                    put("state", e.state)
                                    put("friendly_name", e.friendlyName)
                                    put("attributes", e.attributes)
                                }
                            )
                        }
                    }
                )
            }
        val body = Json.encodeToString(JsonObject.serializer(), json)
        return newFixedLengthResponse(Response.Status.OK, "application/json", body)
    }

    // ---- actions --------------------------------------------------------------

    private fun handleSaveConnection(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files) // reads the request body ONCE; also populates session.parameters for an urlencoded POST
        val params = session.parameters

        RemoteSettings.saveHaConnection(
            context = context,
            haUrl = params["ha_url"]?.firstOrNull().orEmpty().trim(),
            haToken = params["ha_token"]?.firstOrNull().orEmpty().trim(),
            haWebhookId = params["ha_webhook_id"]?.firstOrNull().orEmpty().trim()
        )
        RemoteSettings.saveHarmonyHubs(context, parseHubRows(params))
        RemoteSettings.saveExtenders(context, parseExtenderRows(params))
        // Only touched when the form explicitly carries the Apple TV section, so a
        // client that predates it (or posts just HA/Harmony) can't wipe paired TVs.
        if (params["atv_present"]?.firstOrNull() == "1") {
            RemoteSettings.saveAppleTvs(context, parseAppleTvRows(params))
        }
        Handler(Looper.getMainLooper()).postDelayed({ onConnectionSaved() }, 500L)
        return redirectHome(context.getString(R.string.web_config_saved_reconnecting))
    }

    /** Reassembles the repeatable hub rows (hub_name[]/hub_ip[]/hub_hubid[]/hub_localid[]) posted by the form. */
    private fun parseHubRows(params: Map<String, List<String>>): List<HarmonyHubConfig> {
        val ids = params["hub_localid[]"].orEmpty()
        val names = params["hub_name[]"].orEmpty()
        val ips = params["hub_ip[]"].orEmpty()
        val hubIds = params["hub_hubid[]"].orEmpty()
        val rowCount = maxOf(ids.size, names.size, ips.size, hubIds.size)

        return (0 until rowCount).mapNotNull { i ->
            val name = names.getOrNull(i).orEmpty().trim()
            val ip = ips.getOrNull(i).orEmpty().trim()
            val hubId = hubIds.getOrNull(i).orEmpty().trim()
            if (name.isBlank() && ip.isBlank() && hubId.isBlank()) return@mapNotNull null // empty "+" row never filled in

            val existingLocalId = ids.getOrNull(i).orEmpty().trim()
            val localId = existingLocalId.ifBlank { UUID.randomUUID().toString() }
            HarmonyHubConfig(
                localId = localId,
                name = name.ifBlank { "Harmony Hub" },
                ip = ip,
                hubId = hubId
            )
        }
    }

    /**
     * Mirrors [parseHubRows] above, for the extenders section of the web
     * form — repeatable rows via `extender_localid[]`/`extender_name[]`/
     * `extender_host[]`/`extender_mac[]`.
     *
     * Unlike Harmony hubs, an extender's localId is *always* derived from
     * its MAC address (`ext_<12 lowercase hex digits>`), never random:
     * IR devices reference an extender by this id, so it has to survive a
     * rename, an IP change, or a remove-and-re-add of the same physical
     * unit. A random id here is exactly the bug already fixed once for
     * Harmony hubs (deleting and re-adding the same physical hub silently
     * orphaned every reference to its old localId) — and it was hit again
     * here in testing before the form required a MAC.
     *
     * The form enforces this client-side too; this is the server-side
     * guard for anything posting directly. A row whose MAC doesn't clean
     * up to 12 hex digits falls back to any localId the client already
     * sent (an existing extender being edited), and is skipped entirely
     * otherwise rather than silently persisted under an unstable id.
     */
    private fun parseExtenderRows(params: Map<String, List<String>>): List<ExtenderConfig> {
        val ids = params["extender_localid[]"].orEmpty()
        val names = params["extender_name[]"].orEmpty()
        val hosts = params["extender_host[]"].orEmpty()
        val macs = params["extender_mac[]"].orEmpty()
        val rowCount = maxOf(ids.size, names.size, hosts.size, macs.size)

        return (0 until rowCount).mapNotNull { i ->
            val name = names.getOrNull(i).orEmpty().trim()
            val host = hosts.getOrNull(i).orEmpty().trim()
            if (name.isBlank() && host.isBlank()) return@mapNotNull null // empty "+" row never filled in

            // 12 hex digits once separators are stripped -- accepts
            // 2C:B4:71:FF:C7:98, 2c-b4-71-ff-c7-98, 2cb471ffc798 alike.
            val mac = macs.getOrNull(i).orEmpty().filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.lowercase()
            val existingLocalId = ids.getOrNull(i).orEmpty().trim()
            val localId = when {
                mac.length == 12 -> "ext_$mac"
                existingLocalId.isNotBlank() -> existingLocalId
                else -> {
                    Log.w("ConfigServer", "Skipping extender row \"$name\": no usable MAC address and no existing id")
                    return@mapNotNull null
                }
            }
            ExtenderConfig(
                localId = localId,
                name = name.ifBlank { "IR Extender" },
                host = host,
                mac = mac
            )
        }
    }

    /**
     * Reassembles the repeatable Apple TV rows (`atv_localid[]`, `atv_name[]`,
     * `atv_entityid[]`, `atv_host[]`, `atv_port[]`, `atv_service[]`,
     * `atv_credentials[]`). Rows without valid pairing credentials are dropped.
     * The localId is always derived from the credentials (the accessory's own
     * identifier), and entity ids are forced into the `media_player.<slug>`
     * shape and made unique, since cards and hotkeys reference them.
     */
    private fun parseAppleTvRows(params: Map<String, List<String>>): List<AppleTvConfig> {
        val names = params["atv_name[]"].orEmpty()
        val entityIds = params["atv_entityid[]"].orEmpty()
        val hosts = params["atv_host[]"].orEmpty()
        val ports = params["atv_port[]"].orEmpty()
        val services = params["atv_service[]"].orEmpty()
        val credentials = params["atv_credentials[]"].orEmpty()
        val mrpHosts = params["atv_mrphost[]"].orEmpty()
        val mrpPorts = params["atv_mrpport[]"].orEmpty()
        val mrpServices = params["atv_mrpservice[]"].orEmpty()
        val mrpCredentials = params["atv_mrpcredentials[]"].orEmpty()
        val mrpTransports = params["atv_mrptransport[]"].orEmpty()
        val used = HashSet<String>()
        val entityPattern = Regex("^media_player\\.[a-z0-9_]+$")

        return credentials.indices.mapNotNull { i ->
            val parsed = AppleTvCredentials.parse(credentials[i]) ?: return@mapNotNull null
            val name = names.getOrNull(i).orEmpty().trim().ifBlank { "Apple TV" }
            val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "salon" }
            var entityId = entityIds.getOrNull(i).orEmpty().trim().takeIf { entityPattern.matches(it) } ?: "media_player.appletv_$slug"
            var suffix = 2
            while (!used.add(entityId)) entityId = "media_player.appletv_${slug}_${suffix++}"
            // The MRP credentials are only kept if they still parse and were paired with this same
            // accessory (its atvId matches) — otherwise a stale/foreign value is silently dropped
            // rather than saved, since AppleTvRegistry would reject it at connect time anyway.
            val mrpParsed = AppleTvCredentials.parse(mrpCredentials.getOrNull(i))?.takeIf { it.atvId.contentEquals(parsed.atvId) }
            AppleTvConfig(
                localId = parsed.deviceKey(),
                name = name,
                entityId = entityId,
                host = hosts.getOrNull(i).orEmpty().trim(),
                port = ports.getOrNull(i)?.trim()?.toIntOrNull() ?: 0,
                serviceName = services.getOrNull(i).orEmpty().trim(),
                credentials = parsed.serialize(),
                mrpHost = mrpHosts.getOrNull(i).orEmpty().trim(),
                mrpPort = mrpPorts.getOrNull(i)?.trim()?.toIntOrNull() ?: 0,
                mrpServiceName = mrpServices.getOrNull(i).orEmpty().trim(),
                mrpCredentials = mrpParsed?.serialize().orEmpty(),
                mrpTransport = mrpTransports.getOrNull(i)?.trim()?.ifBlank { null } ?: "airplay"
            )
        }
    }

    private fun jsonResponse(status: Response.Status, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", body.toString())

    private fun appleTvNotReady(): Response =
        jsonResponse(Response.Status.SERVICE_UNAVAILABLE, JSONObject().put("error", "Apple TV support is still starting"))

    private fun appleTvsJson(): JSONArray = JSONArray().apply {
        RemoteSettings.appleTvs(context).forEach { tv ->
            put(
                JSONObject().apply {
                    put("localId", tv.localId)
                    put("name", tv.name)
                    put("entityId", tv.entityId)
                    put("host", tv.host)
                    put("port", tv.port)
                    put("serviceName", tv.serviceName)
                    put("credentials", tv.credentials)
                    put("mrpHost", tv.mrpHost)
                    put("mrpPort", tv.mrpPort)
                    put("mrpServiceName", tv.mrpServiceName)
                    put("mrpCredentials", tv.mrpCredentials)
                    put("mrpTransport", tv.mrpTransport)
                    put("mrpPaired", tv.mrpCredentials.isNotBlank())
                }
            )
        }
    }

    private fun serveAppleTvScan(): Response {
        val found = runCatching { appleTvRegistry?.scan() }.getOrNull().orEmpty()
        val array = JSONArray()
        found.forEach { tv ->
            array.put(
                JSONObject().apply {
                    put("serviceName", tv.serviceName)
                    put("name", tv.serviceName)
                    put("host", tv.host)
                    put("port", tv.port)
                    put("model", tv.model)
                }
            )
        }
        return jsonResponse(Response.Status.OK, JSONObject().put("devices", array))
    }

    private fun handleAppleTvPairStart(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val host = session.parameters["host"]?.firstOrNull()?.trim().orEmpty()
        val port = session.parameters["port"]?.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        if (host.isBlank() || port <= 0) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "missing host or port"))
        }
        val registry = appleTvRegistry ?: return appleTvNotReady()
        return try {
            val id = registry.beginPairing(host, port)
            jsonResponse(Response.Status.OK, JSONObject().put("sessionId", id))
        } catch (e: Exception) {
            Log.w("ConfigServer", "Apple TV pairing start failed", e)
            jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "pairing failed"))
        }
    }

    private fun handleAppleTvPairFinish(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val sessionId = session.parameters["sessionId"]?.firstOrNull()?.trim().orEmpty()
        val pin = session.parameters["pin"]?.firstOrNull()?.trim().orEmpty()
        if (sessionId.isBlank() || pin.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "missing sessionId or pin"))
        }
        val registry = appleTvRegistry ?: return appleTvNotReady()
        return try {
            val credentials = registry.finishPairing(sessionId, pin)
            jsonResponse(
                Response.Status.OK,
                JSONObject().put("credentials", credentials.serialize()).put("localId", credentials.deviceKey())
            )
        } catch (e: Exception) {
            Log.w("ConfigServer", "Apple TV pairing finish failed", e)
            jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "pairing failed"))
        }
    }

    /** Serves a paired Apple TV's current artwork (see [AppleTvEntityMapper]'s `entity_picture`); 404 until MRP has fetched one. */
    private fun serveAppleTvArtwork(uri: String): Response {
        val localId = uri.removePrefix("/appletv-artwork/").substringBefore('?')
        val jpeg = appleTvRegistry?.artworkFor(localId)
        if (jpeg == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No artwork yet for $localId")
        }
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", java.io.ByteArrayInputStream(jpeg), jpeg.size.toLong())
    }

    private fun serveAppleTvMrpScan(): Response {
        val found = runCatching { appleTvRegistry?.scanMrp() }.getOrNull().orEmpty()
        val array = JSONArray()
        found.forEach { tv ->
            array.put(
                JSONObject().apply {
                    put("serviceName", tv.serviceName)
                    put("name", tv.serviceName)
                    put("host", tv.host)
                    put("port", tv.port)
                    put("transport", if (tv.kind == AppleTvServiceKind.AirPlay) "airplay" else "classic")
                }
            )
        }
        return jsonResponse(Response.Status.OK, JSONObject().put("devices", array))
    }

    private fun handleAppleTvMrpPairStart(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val host = session.parameters["host"]?.firstOrNull()?.trim().orEmpty()
        val port = session.parameters["port"]?.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        val transport = session.parameters["transport"]?.firstOrNull()?.trim().orEmpty().ifBlank { "airplay" }
        if (host.isBlank() || port <= 0) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "missing host or port"))
        }
        val registry = appleTvRegistry ?: return appleTvNotReady()
        return try {
            val id = registry.beginMrpPairing(host, port, transport)
            jsonResponse(Response.Status.OK, JSONObject().put("sessionId", id))
        } catch (e: Exception) {
            Log.w("ConfigServer", "Apple TV MRP pairing start failed", e)
            jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "pairing failed"))
        }
    }

    private fun handleAppleTvMrpPairFinish(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val sessionId = session.parameters["sessionId"]?.firstOrNull()?.trim().orEmpty()
        val pin = session.parameters["pin"]?.firstOrNull()?.trim().orEmpty()
        if (sessionId.isBlank() || pin.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "missing sessionId or pin"))
        }
        val registry = appleTvRegistry ?: return appleTvNotReady()
        return try {
            val credentials = registry.finishMrpPairing(sessionId, pin)
            jsonResponse(
                Response.Status.OK,
                JSONObject().put("credentials", credentials.serialize())
            )
        } catch (e: Exception) {
            Log.w("ConfigServer", "Apple TV MRP pairing finish failed", e)
            jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "pairing failed"))
        }
    }

    private fun handleDashboardUpload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files) // NanoHTTPD writes uploaded parts to temp files, keyed by form field name
        val tmpPath =
            files["file"]
                ?: return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "text/plain",
                    "Missing file"
                )
        File(tmpPath).copyTo(DashboardLoader.configFile, overwrite = true)
        onDashboardUpdated()
        return redirectHome(context.getString(R.string.web_config_dashboard_updated))
    }

    private fun handleIconUpload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val tmpPath =
            files["file"]
                ?: return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "text/plain",
                    "Missing file"
                )
        // For multipart file fields, NanoHTTPD puts the temp path in `files` and
        // the original filename as the parameter value for that same field name.
        val originalName =
            session.parameters["file"]?.firstOrNull() ?: "icon_${System.currentTimeMillis()}.png"
        File(tmpPath).copyTo(File(iconsDir, sanitize(originalName)), overwrite = true)
        return redirectHome(context.getString(R.string.web_config_icon_uploaded))
    }

    /**
     * Saves one curated ir-database category file (as produced by the
     * ir-database picker — a separate, externally-hosted static site, not
     * part of this app) straight to `/sdcard/astrion/ir-database/`,
     * creating that folder on first use. IrDatabaseRuntime.kt picks up the
     * change on the very next command send, no restart needed.
     *
     * Two callers, two response shapes needed on success — same endpoint
     * either way, since it's the same operation either way:
     *  - This device's own config page (`web_config_ir_database_upload_button`
     *    above): a plain browser `<form>` POST, wants the usual
     *    redirect-with-a-flash-message every other upload form on this
     *    page gets (see handleIconUpload).
     *  - The picker's own "Send to my remote" button: a cross-origin
     *    `fetch()` call (blocked by most browsers' mixed-content policy
     *    today, since this page is plain HTTP — see the picker's own
     *    comments), which sends `Accept: application/json` and wants a
     *    real JSON response body to show a status message from, not an
     *    HTML redirect it would just silently follow.
     * Picked apart into [validateIrDatabaseUpload] so this function itself
     * stays a short, flat happy-path plus the one on-success branch.
     */
    private fun handleIrDatabaseUpload(session: IHTTPSession): Response {
        val wantsJson = session.headers["accept"]?.contains("application/json") == true
        val (tmpPath, target) = validateIrDatabaseUpload(session) ?: return irDatabaseError(
            Response.Status.BAD_REQUEST,
            "Expected a single .json ir-database category file (with \"category\"+\"brands\" keys)"
        )

        File(tmpPath).copyTo(File(irDatabaseDir, target), overwrite = true)
        IrDatabaseRuntime.invalidate()

        return if (wantsJson) {
            jsonResponse(
                Response.Status.OK,
                buildJsonObject {
                    put("status", "ok")
                    put("file", target)
                }
            )
        } else {
            redirectHome(context.getString(R.string.web_config_ir_database_uploaded))
        }
    }

    /** Null means invalid — caller responds with one generic error either
     * way, so there's no need to thread a specific reason back out here. */
    private fun validateIrDatabaseUpload(session: IHTTPSession): Pair<String, String>? {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val tmpPath = files["file"] ?: return null
        val originalName = session.parameters["file"]?.firstOrNull() ?: return null
        if (!originalName.endsWith(".json", ignoreCase = true)) return null
        val parsed = runCatching { Json.parseToJsonElement(File(tmpPath).readText()).jsonObject }.getOrNull()
        if (parsed?.containsKey("category") != true || !parsed.containsKey("brands")) return null
        // Lowercased on write so files landing here stay consistent with
        // the category ids elsewhere (the picker itself already does
        // this) — IrDatabaseRuntime's own lookup is case-insensitive
        // regardless, for files that arrive some other way (manual copy).
        return tmpPath to sanitize(originalName).lowercase()
    }

    private fun jsonResponse(status: Response.Status, body: JsonObject): Response =
        newFixedLengthResponse(status, "application/json", body.toString())
            .apply { addHeader("Access-Control-Allow-Origin", "*") }

    private fun irDatabaseError(status: Response.Status, message: String): Response =
        jsonResponse(status, buildJsonObject { put("error", message) })

    /**
     * Lists the category ids actually present in [irDatabaseDir] — e.g.
     * `["ac","tv"]` for a folder containing `ac.json` and `TV.json` (case
     * doesn't matter, see [IrDatabaseRuntime]'s own lookup). Feeds the
     * dashboard builder's "Reference the ir-database" device form
     * (`docs/js/ir.js`): when this device actually has some files copied
     * over already, the builder can offer them directly instead of asking
     * for category/brand/model to be typed by hand. Only meaningful when
     * the builder is opened from this device (`/builder/`) — same
     * same-origin-only reasoning as `serveIconsList`.
     */
    private fun serveIrDatabaseList(): Response {
        val ids =
            irDatabaseDir
                .listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
                ?.map { it.name.removeSuffix(".json").removeSuffix(".JSON").lowercase() }
                ?.sorted() ?: emptyList()
        return newFixedLengthResponse(Response.Status.OK, "application/json", JSONArray(ids).toString())
    }

    /**
     * Serves one category file straight out of [irDatabaseDir] — raw
     * pass-through, same `{category, brands:[...]}` shape it was written
     * in. The other half of `serveIrDatabaseList`: the builder fetches
     * this once a category from that list is picked, to fill in
     * brand/model (and, unlike the picker, know the *exact* command ids
     * available — no more relying on hand-typed "known command ids" hints
     * for a device that's already on this device's own sdcard).
     */
    private fun serveIrDatabaseFile(uri: String): Response {
        val category = sanitize(uri.removePrefix("/ir-database/").removeSuffix(".json"))
        val file =
            irDatabaseDir.listFiles()?.firstOrNull { it.name.equals("$category.json", ignoreCase = true) }
        if (category.isBlank() || file == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found: $category")
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", file.readText())
    }

    /**
     * Lists every icon previously uploaded to [iconsDir], as a JSON array of
     * bare filenames — feeds the dashboard builder's icon picker (`docs/js/
     * cards.js`'s `openIconPicker()`), which shows them as clickable
     * thumbnails instead of making the person type a path by hand.
     */
    private fun serveIconsList(): Response {
        val names =
            iconsDir
                .listFiles()
                ?.filter { it.isFile }
                ?.map { it.name }
                ?.sorted() ?: emptyList()
        val json = JSONArray(names).toString()
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    /**
     * Serves a previously-uploaded icon back out of [iconsDir] — the other
     * half of `handleIconUpload`'s `POST /icons`. Used by the dashboard
     * builder (`docs/js/cards.js`'s `iconUrl()`) when it's opened from this
     * device (`/builder/`), to preview a `scene_grid`/`button_grid` card's
     * real configured icon instead of just its name. `sanitize()` (same one
     * upload uses) collapses the requested path down to a bare filename, so
     * `/icons/../../whatever` can't escape [iconsDir].
     */
    private fun serveIcon(uri: String): Response {
        val name = sanitize(uri.removePrefix("/icons/"))
        val file = File(iconsDir, name)
        if (name.isBlank() || !file.isFile) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "Not found: $name"
            )
        }
        val mime =
            when (name.substringAfterLast('.', "").lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "svg" -> "image/svg+xml"
                "webp" -> "image/webp"
                else -> "application/octet-stream"
            }
        val bytes = file.readBytes()
        return newFixedLengthResponse(
            Response.Status.OK,
            mime,
            bytes.inputStream(),
            bytes.size.toLong()
        )
    }

    /**
     * Same beta-detection as SettingsMenu.kt's LaunchedEffect: a beta/debug
     * build (`versionNameSuffix = "-beta"`, see build.gradle.kts) checks the
     * rolling `dev-latest` pre-release here too, instead of always comparing
     * against `/releases/latest` — otherwise this badge could never fire on
     * a beta install either.
     */
    private fun handleCheckUpdate(): Response {
        val isBeta = BuildConfig.VERSION_NAME.contains("-beta")
        val result = if (isBeta) UpdateChecker.checkBetaUpdate() else UpdateChecker.checkForUpdate()
        lastResult = result
        val message =
            when (result) {
                is UpdateChecker.CheckResult.Available ->
                    context.getString(R.string.web_config_update_found, result.info.version)

                is UpdateChecker.CheckResult.UpToDate ->
                    context.getString(R.string.web_config_update_none)

                is UpdateChecker.CheckResult.Failed ->
                    context.getString(R.string.web_config_update_failed, result.reason)
            }
        return redirectHome(message)
    }

    private fun handleInstallUpdate(): Response {
        val result =
            lastResult ?: run {
                val isBeta = BuildConfig.VERSION_NAME.contains("-beta")
                if (isBeta) UpdateChecker.checkBetaUpdate() else UpdateChecker.checkForUpdate()
            }
        val info =
            (result as? UpdateChecker.CheckResult.Available)?.info
                ?: return redirectHome(
                    when (result) {
                        is UpdateChecker.CheckResult.Failed ->
                            context.getString(
                                R.string.web_config_update_failed,
                                result.reason
                            )

                        else -> context.getString(R.string.web_config_update_none)
                    }
                )

        // Ask first instead of letting the installation intent fail: on Android 8+
        // this permission is granted per-app in Settings, not at install time.
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent =
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri()
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settingsIntent) }
            return redirectHome(context.getString(R.string.web_config_update_needs_permission))
        }

        val file =
            UpdateChecker.download(context, info.apkUrl)
                ?: return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "Download failed"
                )

        return try {
            UpdateChecker.promptInstall(context, file)
            redirectHome(context.getString(R.string.web_config_update_installing))
        } catch (e: Exception) {
            Log.e("ConfigServer", "install prompt failed", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Could not open installer: ${e.message}"
            )
        }
    }

    /**
     * Mirrors [handleInstallUpdate], but for the rolling `dev-latest`
     * pre-release, and called via `fetch()` from docs/js/hotkeys.js's
     * installBetaUpdate() rather than a `<form>` navigation — so this
     * returns plain HTTP status + text instead of [redirectHome]'s
     * meta-refresh HTML, which `fetch()` doesn't act on anyway and which
     * always reports 200 OK even for a failure.
     */
    private fun handleInstallBetaUpdate(): Response {
        val result = UpdateChecker.checkBetaUpdate()
        val info =
            (result as? UpdateChecker.CheckResult.Available)?.info
                ?: return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    when (result) {
                        is UpdateChecker.CheckResult.Failed -> result.reason
                        else -> "No beta build available"
                    }
                )

        // Ask first instead of letting the installation intent fail: on Android 8+
        // this permission is granted per-app in Settings, not at install time.
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent =
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri()
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settingsIntent) }
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                "text/plain",
                context.getString(R.string.web_config_update_needs_permission)
            )
        }

        val file =
            UpdateChecker.download(context, info.apkUrl)
                ?: return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "Download failed"
                )

        return try {
            UpdateChecker.promptInstall(context, file)
            newFixedLengthResponse(Response.Status.OK, "text/plain", "Installing ${info.version}")
        } catch (e: Exception) {
            Log.e("ConfigServer", "beta install prompt failed", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Could not open installer: ${e.message}"
            )
        }
    }

    // ---- ring ("find my remote") -------------------------------------------

    private val ringSounds =
        mapOf(
            "ringtone" to RingtoneManager.TYPE_RINGTONE,
            "alarm" to RingtoneManager.TYPE_ALARM,
            "notification" to RingtoneManager.TYPE_NOTIFICATION
        )

    /**
     * Plays this device's own ringtone/alarm/notification sound on loop at a
     * chosen volume, so a misplaced tablet can be found by ear. Uses
     * `AudioAttributes.USAGE_ALARM` (rather than nudging the whole device's
     * media/ringer volume, which would be audible far beyond this one call
     * and could be left changed if something goes wrong) so the sound plays
     * at a level we fully control and cleanly restore afterwards, and so it
     * has a decent chance of being heard even if the tablet is set to
     * silent/DND for notifications.
     */
    private fun handleRing(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val soundParam = session.parameters["sound"]?.firstOrNull()?.trim()?.lowercase() ?: "ringtone"
        val ringtoneType =
            ringSounds[soundParam]
                ?: return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"error":"unknown sound '$soundParam', expected one of ${ringSounds.keys}"}"""
                )
        val volume = (session.parameters["volume"]?.firstOrNull()?.trim()?.toIntOrNull() ?: 80).coerceIn(1, 100)
        val durationSeconds =
            (session.parameters["duration"]?.firstOrNull()?.trim()?.toIntOrNull() ?: 15).coerceIn(1, 60)

        val ringtoneUri =
            RingtoneManager.getActualDefaultRingtoneUri(context, ringtoneType)
                ?: RingtoneManager.getValidRingtoneUri(context)
                ?: return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"no ringtone available on this device"}"""
                )

        // A ring already in progress is replaced, not layered on top of.
        stopRingInternal()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxAlarmVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        ringOriginalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val targetAlarmVolume = ((volume / 100f) * maxAlarmVolume).toInt().coerceIn(1, maxAlarmVolume)
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetAlarmVolume, 0) }

        try {
            ringMediaPlayer =
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    isLooping = true
                    setDataSource(context, ringtoneUri)
                    prepare()
                    start()
                }
        } catch (e: Exception) {
            Log.e("ConfigServer", "ring: failed to start playback", e)
            stopRingInternal()
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":"could not play sound: ${e.message}"}"""
            )
        }

        val handler = Handler(Looper.getMainLooper())
        ringStopHandler = handler
        handler.postDelayed({ stopRingInternal() }, durationSeconds * 1000L)

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"status":"ringing","sound":"$soundParam","volume":$volume,"duration":$durationSeconds}"""
        )
    }

    private fun handleStopRing(): Response {
        val wasRinging = ringMediaPlayer != null
        stopRingInternal()
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"status":"stopped","was_ringing":$wasRinging}"""
        )
    }

    /** Stops any in-progress /ring playback, cancels its scheduled auto-stop,
     * and restores the alarm stream to whatever volume it was at before /ring
     * changed it. Safe to call when nothing is ringing. */
    private fun stopRingInternal() {
        ringStopHandler?.removeCallbacksAndMessages(null)
        ringStopHandler = null
        ringMediaPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        ringMediaPlayer = null
        ringOriginalAlarmVolume?.let { original ->
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, original, 0) }
        }
        ringOriginalAlarmVolume = null
    }

    // ---- helpers --------------------------------------------------------------

    private fun redirectHome(message: String): Response {
        val html = """<!doctype html><meta http-equiv="refresh" content="2;url=/">
            <body style="font-family:sans-serif;background:#0e2229;color:#e6e6e6;padding:24px">$message…</body>"""
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun sanitize(name: String) = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
}
