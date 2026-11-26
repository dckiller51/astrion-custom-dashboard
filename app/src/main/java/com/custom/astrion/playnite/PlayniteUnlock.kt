package com.custom.astrion.playnite

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What an unlock ESP says of itself: its name, a PIN saved, the PC's USB port ("active", "suspended", "off"). */
data class UnlockInfo(val name: String, val pin: Boolean, val usb: String)

/** How an unlock went. */
enum class UnlockResult { DONE, PC_NOT_ON, CODE_REFUSED, FAILED }

/**
 * Astrion PC Unlock (1.6.3): an ESP32-S3 plugged into the PC as a USB keyboard, that types the
 * Windows PIN kept on it when asked, with its 6-digit code. The PIN never leaves the ESP.
 * The answers are read here, pure, so they are unit-tested.
 */
object PlayniteUnlock {
    const val PORT = 80
    const val CODE_HEADER = "X-Astrion-Code"

    private val json = Json { ignoreUnknownKeys = true }

    /** GET /info or POST /check: null when it isn't an unlock ESP. */
    fun info(text: String): UnlockInfo? {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        if (str("app") != null && str("app") != "astrion-unlock") return null
        if (str("app") == null && (o["ok"] as? JsonPrimitive)?.booleanOrNull != true) return null
        return UnlockInfo(str("name").orEmpty(), (o["pin"] as? JsonPrimitive)?.booleanOrNull == true, str("usb").orEmpty())
    }

    /** POST /unlock's HTTP status as a result. */
    fun result(status: Int): UnlockResult = when (status) {
        HTTP_OK -> UnlockResult.DONE
        HTTP_UNAUTHORIZED -> UnlockResult.CODE_REFUSED
        HTTP_CONFLICT -> UnlockResult.PC_NOT_ON
        else -> UnlockResult.FAILED
    }

    private const val HTTP_OK = 200
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_CONFLICT = 409
}

/** One unlock ESP on the network. Blocking: callers run it off the main thread. */
class PlayniteUnlockClient(private val host: String, private val code: String) {
    class CodeRefused : IOException("unlock code refused")

    private fun url(path: String) = "http://$host:${PlayniteUnlock.PORT}$path"

    private fun post(path: String): Pair<Int, String> {
        val request =
            Request
                .Builder()
                .url(url(path))
                .header(PlayniteUnlock.CODE_HEADER, code)
                .post(ByteArray(0).toRequestBody(null))
                .build()
        return http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
    }

    /** The code is right (nothing typed): what the ESP says. Throws [CodeRefused], or when it doesn't answer. */
    fun check(): UnlockInfo {
        val (status, text) = post("/check")
        if (PlayniteUnlock.result(status) == UnlockResult.CODE_REFUSED) throw CodeRefused()
        return PlayniteUnlock.info(text)?.takeIf { status == HTTP_OK } ?: throw IOException("HTTP $status: not an Astrion PC Unlock")
    }

    /** Asks the PC to wake from sleep through USB (when it allows it). Never throws. */
    fun wake(): Boolean = runCatching { post("/wake").first == HTTP_OK }.getOrDefault(false)

    /** Shows the sign-in field and types the PIN (waking the PC first when asleep). Never throws. */
    fun unlock(): UnlockResult = runCatching { PlayniteUnlock.result(post("/unlock").first) }.getOrDefault(UnlockResult.FAILED)

    companion object {
        private const val HTTP_OK = 200

        private val http: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                // An unlock waits for the PC to wake (up to ~12 s) before typing.
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
