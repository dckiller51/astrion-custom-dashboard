package com.custom.astrion

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import com.custom.astrion.config.RemoteSettings
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Who this remote is, for homes with several of them: a stable [id] and a friendly [name]
 * ("Living room"), shown on `GET /info`, in Settings › About and on the web page, and sent with
 * every webhook push and Home Assistant event so automations know which remote did what.
 *
 * The id is derived from Android's ANDROID_ID — it survives app updates, reinstalls and IP
 * changes, and only changes after a factory reset (a new remote for Home Assistant, which can
 * then be given the old one's identity from the integration). It's stored once computed, so it
 * also stays put if ANDROID_ID were ever unavailable later.
 */
object RemoteIdentity {
    private const val PREFS = "astrion_settings"
    private const val KEY_ID = "remote_id"
    private const val KEY_NAME = "remote_name"
    private const val MAX_NAME_LENGTH = 40
    private const val SHORT_ID_LENGTH = 4

    /** Android's well-known bogus ANDROID_ID on some early builds — never use it as an identity. */
    private const val BROKEN_ANDROID_ID = "9774d56d682e549c"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** "astrion-<16 hex>", stable for the life of the device. */
    fun id(context: Context): String {
        prefs(context).getString(KEY_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = "astrion-" + (androidId(context) ?: UUID.randomUUID().toString().replace("-", "").take(16))
        prefs(context).edit { putString(KEY_ID, id) }
        return id
    }

    @SuppressLint("HardwareIds") // the point: one stable id per physical remote, never sent off the LAN
    private fun androidId(context: Context): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() && it != BROKEN_ANDROID_ID }

    /** The friendly name, or "Astrion 1A2B" (end of the id) until one is given. */
    fun name(context: Context): String = prefs(context).getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultName(context)

    fun defaultName(context: Context): String = "Astrion " + id(context).takeLast(SHORT_ID_LENGTH).uppercase(Locale.ROOT)

    /** Saves [name] (trimmed, at most 40 characters); blank goes back to the default. */
    fun setName(context: Context, name: String) {
        val clean = name.trim().take(MAX_NAME_LENGTH)
        prefs(context).edit { if (clean.isEmpty()) remove(KEY_NAME) else putString(KEY_NAME, clean) }
    }

    /** `{ "id", "name" }` — what goes into webhook pushes and HA events. */
    fun json(context: Context): JsonObject = buildJsonObject {
        put("id", id(context))
        put("name", name(context))
    }

    /** Everything `GET /info` answers. */
    fun info(context: Context): JsonObject = buildJsonObject {
        put("id", id(context))
        put("name", name(context))
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("android", Build.VERSION.RELEASE)
        put("version", BuildConfig.VERSION_NAME)
        put("versionCode", BuildConfig.VERSION_CODE)
        // Lets the HA integration trust pushes from the start and poll rarely.
        put("webhook", RemoteSettings.haWebhookId(context).isNotBlank())
    }
}
