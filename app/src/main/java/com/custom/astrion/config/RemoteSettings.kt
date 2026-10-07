package com.custom.astrion.config

import android.content.Context
import androidx.core.content.edit
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A single Astrion IR Extender (see the astrion-ir-extender project) the
 * app can send Pronto codes to over the LAN. [localId] is derived from the
 * extender's MAC address (`ext_<mac>`) rather than random, so the same
 * physical unit always maps back to the same id even if it's removed and
 * re-added later — the earlier Harmony hub `localId` bug (random per-add,
 * silently orphaning every reference on re-add) is deliberately not
 * repeated here. Referenced from [IrTarget.Extender.extenderId].
 */
data class ExtenderConfig(
    val localId: String,
    val name: String,
    /** IP or hostname, no scheme/port — ExtenderClient builds the full
     * `http://<host>/pronto` URL. */
    val host: String,
    /** Normalized to 12 lowercase hex digits, no separators. [localId] is
     * always `ext_<mac>`; this field keeps the value around so the edit
     * form can show it back to the user. */
    val mac: String = ""
)

/**
 * A single Apple TV controlled directly over its Companion link — no Home
 * Assistant involved. Created by the web configurator's pairing flow (the
 * Apple TV shows a PIN, [credentials] is what comes out of it).
 *
 * [entityId] is the Home-Assistant-style id (`media_player.appletv_<slug>`)
 * this device is published under, so any media_player-aware card, hotkey or
 * button can target it exactly like an HA entity. It is generated once when
 * the device is added and then kept, so renaming the device never breaks
 * cards that reference it. [host]/[port] are only the last known address:
 * the Companion port is dynamic, so the app re-finds the device by its mDNS
 * [serviceName] on every connect and updates these when they change.
 */
data class AppleTvConfig(
    val localId: String,
    val name: String,
    val entityId: String,
    val host: String,
    val port: Int,
    val serviceName: String,
    /** `ltpk:ltsk:atv_id:client_id` in hex — see AppleTvCredentials. */
    val credentials: String,
    /**
     * The separate MRP pairing that gives title/artist/artwork/position — entirely optional
     * (null until the person also pairs it), and independent of the Companion fields above: a
     * device with no MRP pairing still works fully for control, just without now-playing info.
     */
    val mrpHost: String = "",
    val mrpPort: Int = 0,
    val mrpServiceName: String = "",
    val mrpCredentials: String = "",
    /** "airplay" (the default — MRP tunnelled through AirPlay 2, required by tvOS >= 15) or
     * "classic" (plain MRP-over-TCP, only still usable on an older Apple TV). Decided once, from
     * which [com.custom.astrion.appletv.AppleTvServiceKind] the person's scan result paired
     * against, and then kept so reconnects use the same client. */
    val mrpTransport: String = "airplay"
)

/**
 * A single Harmony Hub the app can talk to directly, bypassing Home
 * Assistant. [localId] is a stable app-generated key (independent of the
 * hub's own numeric [hubId]) used to reference this hub from dashboard.json
 * (`hub` field on hotkeys / scene actions) — so renaming a hub or fixing a
 * typo'd IP later doesn't break existing references.
 */
data class HarmonyHubConfig(
    val localId: String,
    val name: String,
    val ip: String,
    /** Mutable: HarmonyHubRegistry.connectAll() fills this in via auto-discovery
     * when it's blank, updating this same instance in place. */
    var hubId: String
)

/**
 * Runtime-editable connection settings (Home Assistant + Harmony Hub(s)),
 * stored in SharedPreferences. Deliberately has NO compile-time fallback —
 * release builds published for the community must never bundle anyone's
 * personal Home Assistant URL or access token. Every install starts
 * unconfigured and is set up afterward through the local web configurator
 * on port 8080 (see ConfigServer / SettingsMenu, which shows the address).
 */
object RemoteSettings {
    private const val PREFS = "astrion_settings"
    private const val KEY_HA_URL = "ha_url"
    private const val KEY_HA_TOKEN = "ha_token"
    private const val KEY_HA_WEBHOOK_ID = "ha_webhook_id"
    private const val KEY_HARMONY_HUBS = "harmony_hubs" // JSON array, see HarmonyHubConfig
    private const val KEY_EXTENDERS = "ir_extenders" // JSON array, see ExtenderConfig
    private const val KEY_APPLE_TVS = "apple_tvs" // JSON array, see AppleTvConfig

    // Legacy single-hub keys (pre-multi-hub). Read once for migration, never written again.
    private const val LEGACY_KEY_HARMONY_IP = "harmony_hub_ip"
    private const val LEGACY_KEY_HARMONY_ID = "harmony_hub_id"

    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun haUrl(context: Context): String = prefs(context).getString(KEY_HA_URL, "") ?: ""

    fun haToken(context: Context): String = prefs(context).getString(KEY_HA_TOKEN, "") ?: ""

    /**
     * Optional HA webhook id (just the id — `POST <ha_url>/api/webhook/<id>`),
     * for instant push (current page, active Activity per room) instead of
     * making the companion HA integration poll ConfigServer's /activities/active
     * on a timer. Webhooks need no auth token (the id itself is the secret),
     * so this is intentionally separate from [haToken]. Blank = push disabled;
     * everything keeps working via polling either way.
     */
    fun haWebhookId(context: Context): String = prefs(context).getString(KEY_HA_WEBHOOK_ID, "") ?: ""

    /** True once a Home Assistant URL and token have been entered via the web configurator. */
    @Suppress("unused")
    fun isConfigured(context: Context): Boolean = haUrl(context).isNotBlank() && haToken(context).isNotBlank()

    fun saveHaConnection(context: Context, haUrl: String, haToken: String, haWebhookId: String = "") {
        prefs(context).edit {
            putString(KEY_HA_URL, haUrl)
            putString(KEY_HA_TOKEN, haToken)
            putString(KEY_HA_WEBHOOK_ID, haWebhookId)
        }
    }

    /**
     * All configured Harmony hubs, in the order they were added. Transparently
     * migrates the old single-hub `harmony_hub_ip`/`harmony_hub_id` prefs into
     * a one-item list the first time this is called, so existing installations
     * keep working without any user action.
     */
    fun harmonyHubs(context: Context): List<HarmonyHubConfig> {
        val raw = prefs(context).getString(KEY_HARMONY_HUBS, null)
        if (raw != null) return parseHubs(raw)

        // Nothing saved yet under the new key — migrate the legacy single hub, if any.
        val legacyIp = prefs(context).getString(LEGACY_KEY_HARMONY_IP, "") ?: ""
        val legacyId = prefs(context).getString(LEGACY_KEY_HARMONY_ID, "") ?: ""
        if (legacyIp.isBlank() && legacyId.isBlank()) return emptyList()

        val migrated =
            listOf(
                HarmonyHubConfig(localId = UUID.randomUUID().toString(), name = "Harmony Hub", ip = legacyIp, hubId = legacyId)
            )
        saveHarmonyHubs(context, migrated)
        return migrated
    }

    fun saveHarmonyHubs(context: Context, hubs: List<HarmonyHubConfig>) {
        val array =
            buildJsonArray {
                hubs.forEach { hub ->
                    addJsonObject {
                        put("localId", hub.localId)
                        put("name", hub.name)
                        put("ip", hub.ip)
                        put("hubId", hub.hubId)
                    }
                }
            }

        prefs(context).edit {
            putString(KEY_HARMONY_HUBS, array.toString())
        }
    }

    /** Convenience lookup used to resolve a `hub` field from dashboard.json. */
    @Suppress("unused")
    fun harmonyHub(context: Context, localId: String?): HarmonyHubConfig? {
        val hubs = harmonyHubs(context)
        if (hubs.isEmpty()) return null
        return hubs.firstOrNull { it.localId == localId } ?: hubs.first()
    }

    private fun parseHubs(raw: String): List<HarmonyHubConfig> = try {
        json.parseToJsonElement(raw).jsonArray.map { el ->
            val obj = el.jsonObject
            HarmonyHubConfig(
                localId = obj["localId"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(),
                name = obj["name"]?.jsonPrimitive?.content ?: "Harmony Hub",
                ip = obj["ip"]?.jsonPrimitive?.content ?: "",
                hubId = obj["hubId"]?.jsonPrimitive?.content ?: ""
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** All configured IR Extenders, in the order they were added. */
    fun extenders(context: Context): List<ExtenderConfig> {
        val raw = prefs(context).getString(KEY_EXTENDERS, null) ?: return emptyList()
        return parseExtenders(raw)
    }

    fun saveExtenders(context: Context, extenders: List<ExtenderConfig>) {
        val array =
            buildJsonArray {
                extenders.forEach { ext ->
                    addJsonObject {
                        put("localId", ext.localId)
                        put("name", ext.name)
                        put("host", ext.host)
                        put("mac", ext.mac)
                    }
                }
            }

        prefs(context).edit {
            putString(KEY_EXTENDERS, array.toString())
        }
    }

    /** Convenience lookup used to resolve [IrTarget.Extender.extenderId]. */
    fun extender(context: Context, localId: String): ExtenderConfig? = extenders(context).firstOrNull { it.localId == localId }

    private fun parseExtenders(raw: String): List<ExtenderConfig> = try {
        json.parseToJsonElement(raw).jsonArray.map { el ->
            val obj = el.jsonObject
            ExtenderConfig(
                localId = obj["localId"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(),
                name = obj["name"]?.jsonPrimitive?.content ?: "IR Extender",
                host = obj["host"]?.jsonPrimitive?.content ?: "",
                mac = obj["mac"]?.jsonPrimitive?.content ?: ""
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** All configured Apple TVs, in the order they were added. */
    fun appleTvs(context: Context): List<AppleTvConfig> {
        val raw = prefs(context).getString(KEY_APPLE_TVS, null) ?: return emptyList()
        return try {
            json.parseToJsonElement(raw).jsonArray.mapNotNull(::jsonToAppleTvConfig)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Builds one [AppleTvConfig] from its stored JSON object, or null when a required field is missing. */
    private fun jsonToAppleTvConfig(el: JsonElement): AppleTvConfig? {
        val obj = el.jsonObject
        fun str(key: String, default: String) = obj[key]?.jsonPrimitive?.content ?: default
        val localId = obj["localId"]?.jsonPrimitive?.content ?: return null
        val entityId = obj["entityId"]?.jsonPrimitive?.content ?: return null
        return AppleTvConfig(
            localId = localId,
            name = str("name", "Apple TV"),
            entityId = entityId,
            host = str("host", ""),
            port = obj["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            serviceName = str("serviceName", ""),
            credentials = str("credentials", ""),
            mrpHost = str("mrpHost", ""),
            mrpPort = obj["mrpPort"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            mrpServiceName = str("mrpServiceName", ""),
            mrpCredentials = str("mrpCredentials", ""),
            mrpTransport = str("mrpTransport", "airplay")
        )
    }

    fun saveAppleTvs(context: Context, appleTvs: List<AppleTvConfig>) {
        val array =
            buildJsonArray {
                appleTvs.forEach { tv ->
                    addJsonObject {
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
                    }
                }
            }
        prefs(context).edit { putString(KEY_APPLE_TVS, array.toString()) }
    }
}
