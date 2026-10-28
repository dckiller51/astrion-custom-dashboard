package com.custom.astrion.weather

import android.util.Log
import com.custom.astrion.BuildConfig
import com.custom.astrion.config.WeatherConfig
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Built-in weather (1.3.2): the remote fetches its own forecast from Open-Meteo (free, no key,
 * Météo-France models available) and publishes it as [ENTITY_ID], shaped like a Home Assistant
 * weather entity. The clock & weather card and the dock clock read it like any HA weather entity,
 * so a remote without Home Assistant gets the weather too.
 *
 * Fetched only while the screen is on (the dock clock keeps it on), at most every
 * [REFRESH_MS]: no Wi-Fi wake-ups while the remote sleeps.
 */
class LocalWeather(private val client: () -> HaClient?, private val screenOn: () -> Boolean) {
    @Volatile var config: WeatherConfig? = null
        set(value) {
            if (field == value) return
            field = value
            lastFetchMs = 0L // fetch again for the new place, right away
            if (value == null) clear()
        }

    @Volatile private var lastFetchMs = 0L

    @Volatile private var entity: EntityState? = null

    @Volatile private var forecast: JsonArray? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job =
            scope.launch {
                while (isActive) {
                    val cfg = config
                    val due = System.currentTimeMillis() - lastFetchMs >= REFRESH_MS
                    if (cfg != null && due && screenOn()) refresh(cfg)
                    delay(CHECK_EVERY_MS)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Hooks a (new) client: answers its forecast requests for [ENTITY_ID] and republishes the entity. */
    fun attach(haClient: HaClient) {
        haClient.localForecastHandler = { entityId -> if (entityId == ENTITY_ID) forecast ?: JsonArray(emptyList()) else null }
        entity?.let { haClient.setLocalEntity(it) }
    }

    private suspend fun refresh(cfg: WeatherConfig) {
        val result = withContext(Dispatchers.IO) { runCatching { fetch(cfg) } }
        result
            .onSuccess { (state, days) ->
                lastFetchMs = System.currentTimeMillis()
                if (config != cfg) return // the place changed while fetching
                entity = state
                forecast = days
                client()?.setLocalEntity(state)
            }.onFailure {
                // Retry sooner than a full period, but don't hammer the API.
                lastFetchMs = System.currentTimeMillis() - REFRESH_MS + RETRY_MS
                Log.w(TAG, "Open-Meteo fetch failed", it)
            }
    }

    private fun clear() {
        entity = null
        forecast = null
        client()?.removeLocalEntity(ENTITY_ID)
    }

    private fun fetch(cfg: WeatherConfig): Pair<EntityState, JsonArray> {
        val fahrenheit = cfg.unit == WeatherConfig.UNIT_FAHRENHEIT
        val url =
            FORECAST_URL.toHttpUrl().newBuilder()
                .addQueryParameter("latitude", round4(cfg.latitude))
                .addQueryParameter("longitude", round4(cfg.longitude))
                .addQueryParameter("current", "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,is_day")
                .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum")
                .addQueryParameter("timezone", "auto")
                .addQueryParameter("forecast_days", if (cfg.model == WeatherConfig.MODEL_METEOFRANCE) "4" else "7")
                .apply {
                    if (cfg.model == WeatherConfig.MODEL_METEOFRANCE) addQueryParameter("models", "meteofrance_seamless")
                    if (fahrenheit) addQueryParameter("temperature_unit", "fahrenheit")
                }.build()
        val body = get(url.toString())
        val root = Json.parseToJsonElement(body).jsonObject
        val current = root["current"]?.jsonObject ?: error("no current weather")
        val isDay = current.num("is_day")?.toInt() != 0
        val condition = condition(current.num("weather_code")?.toInt(), isDay)
        val state =
            EntityState(
                entityId = ENTITY_ID,
                state = condition,
                lastUpdated = System.currentTimeMillis().toString(),
                attributes =
                buildJsonObject {
                    put("friendly_name", cfg.name.ifBlank { "Weather" })
                    current.num("temperature_2m")?.let { put("temperature", it) }
                    put("temperature_unit", if (fahrenheit) "°F" else "°C")
                    current.num("relative_humidity_2m")?.let { put("humidity", it) }
                    current.num("wind_speed_10m")?.let { put("wind_speed", it) }
                    put("wind_speed_unit", "km/h")
                    put("attribution", ATTRIBUTION)
                }
            )
        return state to dailyForecast(root["daily"] as? JsonObject)
    }

    /** Open-Meteo's column arrays → Home Assistant's `weather.get_forecasts` rows. */
    private fun dailyForecast(daily: JsonObject?): JsonArray {
        daily ?: return JsonArray(emptyList())
        fun col(key: String) = (daily[key] as? JsonArray).orEmpty()
        val times = col("time")
        val codes = col("weather_code")
        val max = col("temperature_2m_max")
        val min = col("temperature_2m_min")
        val rain = col("precipitation_sum")
        return buildJsonArray {
            times.forEachIndexed { i, t ->
                val day = (t as? JsonPrimitive)?.content ?: return@forEachIndexed
                add(
                    buildJsonObject {
                        put("datetime", day + "T00:00:00")
                        put("condition", condition((codes.getOrNull(i) as? JsonPrimitive)?.intOrNull, isDay = true))
                        (max.getOrNull(i) as? JsonPrimitive)?.doubleOrNull?.let { put("temperature", it) }
                        (min.getOrNull(i) as? JsonPrimitive)?.doubleOrNull?.let { put("templow", it) }
                        (rain.getOrNull(i) as? JsonPrimitive)?.doubleOrNull?.let { put("precipitation", it) }
                    }
                )
            }
        }
    }

    companion object {
        private const val TAG = "LocalWeather"

        /** The entity id the built-in weather is published under. */
        const val ENTITY_ID = "weather.astrion"
        const val ATTRIBUTION = "Weather data by Open-Meteo.com"

        private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
        private val REFRESH_MS = TimeUnit.MINUTES.toMillis(30)
        private val RETRY_MS = TimeUnit.MINUTES.toMillis(5)
        private val CHECK_EVERY_MS = TimeUnit.MINUTES.toMillis(1)
        private const val MAX_RESULTS = 8
        private const val TIMEOUT_S = 15L

        private val http by lazy {
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .build()
        }

        private val userAgent: String
            get() = "AstrionCustomDashboard/${BuildConfig.VERSION_NAME} (+https://github.com/dckiller51/astrion-custom-dashboard)"

        private fun get(url: String): String {
            val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
            http.newCall(request).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                return resp.body?.string() ?: error("empty body")
            }
        }

        /**
         * Place search for the web page (GET /weather-search): name or postcode → up to
         * [MAX_RESULTS] `{name, admin1, country, latitude, longitude}`. Blocking: call off the UI thread.
         */
        fun search(query: String, language: String): JsonArray {
            val url =
                GEOCODING_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("name", query)
                    .addQueryParameter("count", MAX_RESULTS.toString())
                    .addQueryParameter("language", language.ifBlank { "en" })
                    .addQueryParameter("format", "json")
                    .build()
            val results = (Json.parseToJsonElement(get(url.toString())).jsonObject["results"] as? JsonArray).orEmpty()
            return buildJsonArray {
                results.forEach { el ->
                    val o = el as? JsonObject ?: return@forEach
                    val lat = o.num("latitude") ?: return@forEach
                    val lon = o.num("longitude") ?: return@forEach
                    add(
                        buildJsonObject {
                            put("name", o.str("name"))
                            put("admin1", o.str("admin1"))
                            put("country", o.str("country"))
                            put("latitude", lat)
                            put("longitude", lon)
                        }
                    )
                }
            }
        }

        /** WMO weather code → Home Assistant weather condition. */
        @Suppress("CyclomaticComplexMethod", "MagicNumber")
        fun condition(code: Int?, isDay: Boolean): String = when (code) {
            0 -> if (isDay) "sunny" else "clear-night"
            1, 2 -> "partlycloudy"
            3 -> "cloudy"
            45, 48 -> "fog"
            51, 53, 55, 61, 63, 80, 81 -> "rainy"
            65, 82 -> "pouring"
            56, 57, 66, 67 -> "snowy-rainy"
            71, 73, 75, 77, 85, 86 -> "snowy"
            95, 97 -> "lightning-rainy"
            96, 99 -> "hail"
            else -> "unknown"
        }

        /** At most 4 decimals (~10 m): plenty for weather, and kinder to the API's cache. */
        private fun round4(v: Double): String = String.format(Locale.US, "%.4f", v)

        private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

        private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    }
}
