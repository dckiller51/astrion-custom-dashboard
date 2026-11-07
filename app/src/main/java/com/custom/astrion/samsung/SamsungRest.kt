package com.custom.astrion.samsung

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A Samsung TV's plain HTTP services (1.5.3): its REST API on port 8001 (model, power, apps' state)
 * and its UPnP volume control on 9197. Blocking: called from the registry's threads.
 */
class SamsungRest(private val host: String) {
    fun info(): SamsungProtocol.Info? = get(SamsungProtocol.infoUrl(host))?.let { SamsungProtocol.info(SamsungProtocol.parse(it)) }

    /** Whether app [appId] is the one on screen; null when the TV doesn't say. */
    fun appVisible(appId: String): Boolean? =
        get(SamsungProtocol.appUrl(host, appId))?.let { SamsungProtocol.appVisible(SamsungProtocol.parse(it)) }

    /** App [appId] when it's installed (see [SamsungProtocol.restApp]). */
    fun app(appId: String, fallbackName: String): SamsungProtocol.App? =
        get(SamsungProtocol.appUrl(host, appId))?.let { SamsungProtocol.restApp(SamsungProtocol.parse(it), appId, fallbackName) }

    /** Opens app [appId] over REST: what recent TVs answer best; false when the TV refused. */
    fun launch(appId: String): Boolean = call(
        Request
            .Builder()
            .url(SamsungProtocol.appUrl(host, appId))
            .post("".toRequestBody("application/json".toMediaType()))
            .build()
    ) != null

    fun volume(): Int? = soap("GetVolume")?.let { SamsungProtocol.soapValue(it, "CurrentVolume")?.toIntOrNull() }

    fun muted(): Boolean? = soap("GetMute")?.let { SamsungProtocol.soapValue(it, "CurrentMute")?.let { v -> v == "1" || v == "true" } }

    fun setVolume(level: Int): Boolean = soap("SetVolume", SamsungProtocol.setVolumeBody(level)) != null

    fun setMute(muted: Boolean): Boolean =
        soap("SetMute", SamsungProtocol.soapBody("SetMute", "<DesiredMute>${if (muted) 1 else 0}</DesiredMute>")) != null

    private fun soap(method: String, body: String = SamsungProtocol.soapBody(method)): String? = call(
        Request
            .Builder()
            .url(SamsungProtocol.upnpUrl(host))
            .header("SOAPACTION", SamsungProtocol.soapAction(method))
            .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
    )

    private fun get(url: String): String? = call(Request.Builder().url(url).build())

    private fun call(request: Request): String? = runCatching {
        http.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    private companion object {
        const val TIMEOUT_S = 3L
        val http: OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .build()
    }
}
