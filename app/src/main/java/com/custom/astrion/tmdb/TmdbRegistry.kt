package com.custom.astrion.tmdb

import android.content.Context
import android.util.Log
import com.custom.astrion.AppLanguage
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * What media players play, in Astrion (1.6.0, Zavie's idea): a player that plays a film or a
 * series without saying what it is about gets its synopsis, year, rating and poster as attributes
 * (`astrion_overview`, `astrion_year`, `astrion_rating`, `astrion_poster`, and where they come from
 * in `astrion_meta_source`), for every card. From the Plex server set on the web page when it plays
 * that title ([plex]: exact, in the library's language), else from TMDB ([tmdb]: a search on the
 * title); [MediaInfoSource] picks one or both. Live TV: the TV guide's programme on air under that
 * title, when a guide is set ([epg]). One lookup per title, kept in memory; the tokens
 * never leave the remote but for their own server. A player that already says them (a Zidoo added
 * in Astrion) is left alone.
 */
class TmdbRegistry(
    private val tmdb: TmdbClient?,
    private val plex: PlexSessionsClient?,
    private val language: String,
    private val log: (String) -> Unit = { Log.d(TAG, it) },
    /** The TV guide (1.6.0): what's on air under a title (the player's, then the cleaned one), see epg/EpgRegistry. */
    private val epg: ((String, String?) -> TmdbInfo?)? = null
) {
    private val cache =
        object : LinkedHashMap<String, TmdbInfo?>(CACHE, LOAD, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TmdbInfo?>?) = size > CACHE
        }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** When a lookup found nothing (1.6.2): asked again after [MISS_AGAIN_MS], a Plex server listing what plays late. */
    private val missedAt = ConcurrentHashMap<String, Long>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "Tmdb").apply { isDaemon = true } }

    @Volatile private var ha: HaClient? = null

    /** TMDB refused the token: no more TMDB searches until it's changed (Plex goes on). */
    @Volatile private var refused = false

    fun start(haClient: HaClient) {
        ha = haClient
        haClient.entityEnricher = ::enrich
        haClient.republish()
    }

    fun stop() {
        executor.shutdownNow()
        ha?.let {
            it.entityEnricher = null
            it.republish()
        }
        ha = null
    }

    /** [e] with what's known of what it plays; a lookup started when not yet asked. */
    fun enrich(entity: EntityState): EntityState {
        if (entity.domain != "media_player" || entity.state !in ACTIVE || entity.attributes.containsKey(OVERVIEW)) return entity
        val e = withEpisode(entity)
        val q = TmdbMatch.queryOf(e::attrString) ?: return e
        live(e, q)?.let { return with(e, it) }
        val plexPlayer = plexPlays(e)
        // A local library's player (Plex, Kodi, a Zidoo…, 1.6.0, Zavie): its server knows what it
        // plays, TMDB isn't asked. Plex set on the web page still answers for Plex.
        val local = TmdbMatch.isLocalPlayer(e.attrString("app_name"), e.entityId)
        if (local && !plexPlayer) return e
        val key = q.key + if (local) "|local" else ""
        synchronized(cache) {
            if (cache.containsKey(key)) {
                cache[key]?.let { return with(e, it) }
                if (System.currentTimeMillis() - (missedAt[key] ?: 0L) < MISS_AGAIN_MS) return e
                cache.remove(key)
            }
        }
        ask(q, key, plexPlayer, !local)
        return e
    }

    /** A lookup for [q], unless one is under way or nothing is left to ask. */
    private fun ask(q: TmdbQuery, key: String, plexPlayer: Boolean, tmdbToo: Boolean) {
        if (plex == null && (tmdb == null || refused)) return
        if (inFlight.add(key)) runCatching { executor.execute { lookup(q, key, plexPlayer, tmdbToo) } }.onFailure { inFlight.remove(key) }
    }

    /**
     * An Apple TV or CANAL+ episode says "Saison 2, ép. 5" as its artist (1.6.0, Zavie): its season
     * and episode as Home Assistant's attributes, so the cards show "S2 · E5".
     */
    private fun withEpisode(e: EntityState): EntityState {
        if (e.attributes.containsKey("media_season")) return e
        val (season, episode) = TmdbMatch.episodeOf(e.attrString("media_artist")) ?: return e
        val extra = mapOf("media_season" to JsonPrimitive(season), "media_episode" to JsonPrimitive(episode))
        return e.copy(attributes = JsonObject(e.attributes + extra))
    }

    /**
     * Live TV: the guide's programme on air under [e]'s title, exactly and for today (not cached:
     * tomorrow's news has another synopsis). Plex first when the player plays it.
     */
    private fun live(e: EntityState, q: TmdbQuery): TmdbInfo? {
        val guide = epg ?: return null
        if (plexPlays(e)) return null
        return guide(e.attrString("media_title") ?: q.title, q.title)
    }

    /** The player says it plays Plex (its app, or Home Assistant's Plex player itself). */
    private fun plexPlays(e: EntityState): Boolean {
        if (plex == null) return false
        return TmdbMatch.isPlexClient(e.attrString("app_name"), e.entityId)
    }

    private fun lookup(q: TmdbQuery, key: String, plexPlayer: Boolean, tmdbToo: Boolean) {
        try {
            val info = plexInfo(q, plexPlayer, tmdbToo) ?: if (tmdbToo) tmdbInfo(q) else null
            synchronized(cache) { cache[key] = info }
            if (info == null) missedAt[key] = System.currentTimeMillis() else missedAt.remove(key)
            val found = info?.let { "${it.title} (${it.year ?: "?"}), from ${it.source}" } ?: "nothing found"
            log("\"${q.title}\": $found")
            ha?.republish()
        } catch (e: TmdbRefused) {
            refused = true
            log("TMDB refused the token (HTTP ${e.code}): check it on the web page")
        } catch (e: IOException) {
            // Not cached: asked again with the next update.
            log("\"${q.title}\": no answer (${e.message})")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            inFlight.remove(key)
        }
    }

    /** What the Plex server plays under [q]'s title; asked again a moment later for a Plex player (its session may not be listed yet). */
    private fun plexInfo(q: TmdbQuery, plexPlayer: Boolean, tmdbToo: Boolean): TmdbInfo? {
        val client = plex ?: return null
        return try {
            val found =
                PlexNowPlaying.match(client.sessions(), q, plexPlayer)
                    ?: if (plexPlayer) {
                        Thread.sleep(PLEX_AGAIN_MS)
                        PlexNowPlaying.match(client.sessions(), q, true)
                    } else {
                        null
                    }
            found?.let { PlexNowPlaying.infoOf(it, ::plexArtUrl) }
        } catch (e: IOException) {
            // With TMDB as well, TMDB answers instead; Plex alone: asked again with the next update.
            if (!tmdbToo || tmdb == null || refused) throw e
            log("Plex didn't answer (${e.message}): TMDB instead")
            null
        }
    }

    private fun tmdbInfo(q: TmdbQuery): TmdbInfo? = tmdb?.takeIf { !refused }?.search(q, language)

    private fun with(e: EntityState, info: TmdbInfo): EntityState {
        val extra =
            buildMap {
                info.overview?.let { put(OVERVIEW, JsonPrimitive(it)) }
                info.year?.let { put("astrion_year", JsonPrimitive(it)) }
                info.rating?.let { put("astrion_rating", JsonPrimitive(it)) }
                info.posterUrl?.let { put("astrion_poster", JsonPrimitive(it)) }
                put("astrion_tmdb_title", JsonPrimitive(info.title))
                put("astrion_meta_source", JsonPrimitive(info.source))
            }
        return e.copy(attributes = JsonObject(e.attributes + extra))
    }

    companion object {
        private const val TAG = "Tmdb"
        private const val OVERVIEW = "astrion_overview"
        private const val CACHE = 128
        private const val LOAD = 0.75f
        private const val PLEX_AGAIN_MS = 3_000L
        private const val MISS_AGAIN_MS = 60_000L
        private val ACTIVE = setOf("playing", "paused", "buffering")
    }
}

/** TMDB said no to the token (401 / 403). */
class TmdbRefused(val code: Int) : IOException("HTTP $code")

/** TMDB's search API, with the user's token: a read access token, or an older v3 key. Blocking. */
class TmdbClient(private val token: String) {
    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()

    /** What TMDB found for [q], else null; a film with a year or a series not found is asked once more as anything. */
    fun search(q: TmdbQuery, language: String): TmdbInfo? {
        TmdbMatch.best(get(TmdbMatch.searchPath(q, language)), q)?.let { return it }
        if (q.year == null && !q.tv) return null
        val any = TmdbQuery(q.title, null, false)
        return TmdbMatch.best(get(TmdbMatch.searchPath(any, language)), any)
    }

    private fun get(path: String): String {
        val v3 = V3_KEY.matches(token)
        val url = BASE + path + if (v3) "&api_key=$token" else ""
        val b = Request.Builder().url(url).header("Accept", "application/json")
        if (!v3) b.header("Authorization", "Bearer " + token.removePrefix("Bearer ").trim())
        http.newCall(b.build()).execute().use { resp ->
            val body = resp.body?.string()
            if (resp.isSuccessful && body != null) return body
            throw if (resp.code == HTTP_UNAUTHORIZED || resp.code == HTTP_FORBIDDEN) {
                TmdbRefused(
                    resp.code
                )
            } else {
                IOException("HTTP ${resp.code}")
            }
        }
    }

    private companion object {
        const val BASE = "https://api.themoviedb.org/3/"
        const val TIMEOUT_S = 8L
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        val V3_KEY = Regex("^[0-9a-fA-F]{32}$")
    }
}

/** The Plex server's sessions, with the token set on the web page. Blocking. */
class PlexSessionsClient(private val host: String, private val token: String) {
    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()

    fun sessions(): List<PlexSession> {
        val req =
            Request
                .Builder()
                .url("${host.trimEnd('/')}/status/sessions")
                .header("Accept", "application/json")
                .header("X-Plex-Token", token)
                .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful || body == null) throw IOException("HTTP ${resp.code}")
            return PlexNowPlaying.parse(body)
        }
    }

    private companion object {
        const val TIMEOUT_S = 5L
    }
}

/** TMDB's language: the one set on the web page, else the remote's ("fr" → "fr-FR"). */
fun tmdbLanguageOf(context: Context): String {
    RemoteSettings.tmdbLanguage(context).takeIf { it.isNotBlank() }?.let { return it }
    return when (val app = AppLanguage.get(context).ifBlank { Locale.getDefault().language }) {
        "fr", "de", "it", "es", "nl" -> "$app-${app.uppercase()}"
        else -> "en-US"
    }
}

/**
 * Starts what's-playing lookups (1.6.0) from the web page's settings: the Plex server and TMDB,
 * each when set and allowed by [MediaInfoSource]; null when neither is.
 */
fun installTmdb(
    context: Context,
    haClient: HaClient,
    log: (String) -> Unit = {},
    epg: ((String, String?) -> TmdbInfo?)? = null
): TmdbRegistry? {
    val source = MediaInfoSource.of(RemoteSettings.mediaInfoSource(context))
    val tmdb =
        RemoteSettings
            .tmdbToken(context)
            .takeIf { it.isNotBlank() && source != MediaInfoSource.PLEX }
            ?.let(::TmdbClient)
    val host = RemoteSettings.plexHost(context)
    val token = RemoteSettings.plexToken(context)
    val plex = if (host.isNotBlank() && token.isNotBlank() && source != MediaInfoSource.TMDB) PlexSessionsClient(host, token) else null
    if (tmdb == null && plex == null && epg == null) return null
    log("what's playing: ${listOfNotNull(plex?.let { "Plex" }, epg?.let { "TV guide" }, tmdb?.let { "TMDB" }).joinToString(" then ")}")
    return TmdbRegistry(tmdb, plex, tmdbLanguageOf(context), log, epg).also { it.start(haClient) }
}
