package com.custom.astrion.zidoo

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Talks to one Zidoo over its control API — plain HTTP GETs on port 9529, no pairing. Blocking:
 * called from the registry's own threads, never the UI's.
 */
class ZidooClient(private val host: () -> String) {
    private val cookies = HashMap<String, List<Cookie>>()

    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            // The player sets a session cookie on its answers; its own app sends it back.
            .cookieJar(
                object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                        synchronized(this@ZidooClient.cookies) { this@ZidooClient.cookies[url.host] = cookies }
                    }

                    override fun loadForRequest(url: HttpUrl): List<Cookie> =
                        synchronized(this@ZidooClient.cookies) { this@ZidooClient.cookies[url.host].orEmpty() }
                }
            ).build()

    /**
     * For the answers a player takes its time over (1.5.0): the app list (an Eversolo's never came
     * within 3 s, so its card found no apps) and the library's lists (a season of 99 episodes).
     */
    private val slowHttp = http.newBuilder().readTimeout(SLOW_TIMEOUT_S, TimeUnit.SECONDS).build()

    fun base(): String = "http://${host()}:$PORT/"

    /** GET [path] (with its query) → the JSON answer, or null when the player doesn't answer. */
    fun get(path: String, slow: Boolean = false): JsonObject? = text(path, slow)?.let(ZidooParse::parse)

    /** The same for an answer that may be a bare list (an Eversolo's playlists). */
    fun getAny(path: String, slow: Boolean = false): JsonElement? = text(path, slow)?.let(ZidooParse::parseAny)

    private fun text(path: String, slow: Boolean): String? = runCatching {
        val request = Request.Builder().url(base() + path.trimStart('/')).header("Cache-Control", "no-cache").build()
        (if (slow) slowHttp else http).newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            // Bytes read as UTF-8 (1.4.8): the player says another charset, and a path with an
            // accent ("Séries") came back garbled, so its episodes were never found on the poster wall.
            resp.body?.bytes()?.toString(Charsets.UTF_8).orEmpty()
        }
    }.getOrNull()

    fun model(): ZidooModel? = ZidooParse.model(get("ZidooControlCenter/getModel"))

    fun sendKey(key: String): Boolean = ZidooParse.ok(get("ZidooControlCenter/RemoteControl/sendkey?key=${enc(key)}"))

    fun video(): ZidooPlayback? = ZidooParse.video(get("ZidooVideoPlay/getPlayStatus"))

    fun music(): ZidooPlayback? = ZidooParse.music(get("ZidooMusicControl/getPlayStatus"))

    /** An Eversolo's input, as its last `getState` said (1.5.0); null on a Zidoo. */
    @Volatile var lastInput: EversoloInput? = null
        private set

    /** Its mute, as its last `getState` said (1.5.0); null when not said. */
    @Volatile var lastMuted: Boolean? = null
        private set

    fun musicV2(): ZidooPlayback? {
        val o = get("ZidooMusicControl/v2/getState")
        if (o != null) {
            lastInput = ZidooParse.input(o)
            lastMuted = ZidooParse.muted(o)
        }
        return ZidooParse.musicV2(o)
    }

    /** What plays: the video player, else the music player (v2 first, 1.4.8: an Eversolo says there
     * what a streaming app plays, with its cover), else nothing. */
    fun playback(): ZidooPlayback? = video() ?: musicV2() ?: music()

    fun apps(): Map<String, String> = ZidooParse.apps(get("ZidooControlCenter/Apps/getApps", slow = true))

    fun openApp(packageName: String): Boolean = ZidooParse.ok(get("ZidooControlCenter/Apps/openApp?packageName=${enc(packageName)}"))

    fun movieOf(path: String): ZidooMovie? = ZidooParse.movie(get("ZidooPoster/v2/getAggregationOfFile?path=${enc(path)}"))

    fun backdropUrl(movieId: Long): String = base() + "ZidooPoster/v2/getBackdrop?id=$movieId&w=$ART_W&h=$ART_H"

    fun posterUrl(movieId: Long, w: Int = POSTER_W, h: Int = POSTER_H): String = base() + "ZidooPoster/v2/getPoster?id=$movieId&w=$w&h=$h"

    // ---- The library (1.5.0), see ZidooLibrary ----

    /** One page of a poster-wall list ([ZidooLibrary.ALL], [ZidooLibrary.SERIES]…). */
    fun libraryPage(list: Int, start: Int, count: Int): List<ZidooLibraryItem>? =
        ZidooLibrary.page(get("ZidooPoster/v2/getAggregations?type=$list&start=$start&count=$count", slow = true))

    fun librarySearch(query: String, count: Int): List<ZidooLibraryItem>? =
        ZidooLibrary.search(get("ZidooPoster/v2/searchAggregation?q=${enc(query)}&type=0&start=0&count=$count", slow = true))

    /** A saga's films, a series' seasons, or (through [ZidooLibrary.fileOf]) a film's video file. */
    fun collection(id: Long): JsonObject? = get("ZidooPoster/getCollection?id=$id", slow = true)

    fun season(id: Long): ZidooLibraryList? = ZidooLibrary.season(get("ZidooPoster/v2/getDetail?id=$id", slow = true))

    // ---- An Eversolo's music library (1.5.0), see ZidooMusicLibrary ----

    fun musicAlbums(start: Int, count: Int): List<MusicAlbum>? =
        ZidooMusicLibrary.albums(get("MusicControl/v2/getAlbums?start=$start&count=$count", slow = true))

    fun musicArtists(start: Int, count: Int): List<MusicArtist>? =
        ZidooMusicLibrary.artists(get("MusicControl/v2/getArtists?start=$start&count=$count", slow = true))

    fun musicPlaylists(): List<MusicPlaylist>? =
        ZidooMusicLibrary.playlists(getAny("MusicControl/v2/getSongLists?start=0&count=$MUSIC_LIST_MAX", slow = true))

    fun musicTracks(source: MusicSource, id: Long): List<MusicTrack>? {
        val call =
            when (source) {
                MusicSource.ALBUM -> "getAlbumMusics"
                MusicSource.ARTIST -> "getArtistMusics"
                MusicSource.PLAYLIST -> "getSongListMusics"
            }
        return ZidooMusicLibrary.tracks(get("MusicControl/v2/$call?id=$id&start=0&count=$MUSIC_LIST_MAX", slow = true), source)
    }

    fun musicSearchAlbums(query: String, count: Int): List<MusicAlbum>? =
        ZidooMusicLibrary.albums(get("MusicControl/v2/searchAlbum?key=${enc(query)}&start=0&count=$count", slow = true))

    fun musicSearchArtists(query: String, count: Int): List<MusicArtist>? =
        ZidooMusicLibrary.artists(get("MusicControl/v2/searchArtist?key=${enc(query)}&start=0&count=$count", slow = true))

    /** Plays [source] [id] from its [index]-th track; true when the player answered. */
    fun playMusic(source: MusicSource, id: Long, track: MusicTrack, index: Int): Boolean =
        get("MusicControl/v2/playMusic?" + ZidooMusicLibrary.playQuery(source, id, track, index)) != null

    fun trackArtUrl(track: MusicTrack): String = musicArtUrl(track.id, track.type)

    /** Album id → its cover, the first track's (the player has no album cover of its own); "" for none. */
    private val albumCovers = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /**
     * An album's cover for the album grid: its first track's, asked once per album and kept in
     * memory. Null when the player doesn't answer (asked again next time).
     */
    fun albumCoverUrl(albumId: Long): String? {
        albumCovers[albumId]?.let { return it.ifEmpty { null } }
        val first =
            ZidooMusicLibrary.tracks(get("MusicControl/v2/getAlbumMusics?id=$albumId&start=0&count=1", slow = true), MusicSource.ALBUM)
                ?: return null
        val url = first.firstOrNull()?.let(::trackArtUrl).orEmpty()
        albumCovers[albumId] = url
        return url.ifEmpty { null }
    }

    /** Poster-wall id → when it was last watched (0: never), kept while the library is open. */
    private val lastWatched = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** A new look at "In progress": what was watched since is read again. */
    fun forgetLastWatched() = lastWatched.clear()

    /**
     * When [item] was last watched (ms, 0 when never or not known), for "In progress" (1.5.0): a
     * film's file, a season's latest episode, a saga's or a series' latest film or season (its
     * first [FOLDER_MAX] ones). Asked once per item, then kept.
     */
    fun lastWatched(item: ZidooLibraryItem): Long {
        lastWatched[item.id]?.let { return it }
        val time =
            when (item.kind) {
                ZidooKind.MOVIE -> ZidooLibrary.lastWatchedFilm(collection(item.id))
                ZidooKind.SEASON -> ZidooLibrary.lastWatchedSeason(get("ZidooPoster/v2/getDetail?id=${item.id}", slow = true))
                ZidooKind.COLLECTION, ZidooKind.SERIES -> {
                    // Only its films / seasons that were started or watched (a season's episode
                    // list is a large answer): the few most likely, not all of them.
                    val children = ZidooLibrary.collection(collection(item.id))?.items.orEmpty()
                    val seen = children.filter { it.positionMs != null || it.watched }.ifEmpty { children }
                    seen.take(FOLDER_MAX).maxOfOrNull { lastWatched(it) }
                }
                else -> null
            } ?: 0L
        lastWatched[item.id] = time
        return time
    }

    /** Plays a video file of the poster wall (its `aggregationId`, not the film's id: that one answers 804). */
    fun playVideo(fileId: Long): Boolean = ZidooParse.ok(get("ZidooPoster/PlayVideo?id=$fileId&type=-1"))

    // The Zidoo / Eversolo card's own calls (1.5.1), see ZidooControls.

    fun subtitles(): List<ZidooTrack> = ZidooControls.tracks(get(ZidooControls.SUBTITLES_PATH))

    fun setSubtitle(index: Int): Boolean = ZidooParse.ok(get(ZidooControls.setSubtitlePath(index)))

    fun audioTracks(): List<ZidooTrack> = ZidooControls.tracks(get(ZidooControls.AUDIO_TRACKS_PATH))

    fun setAudio(index: Int): Boolean = ZidooParse.ok(get(ZidooControls.setAudioPath(index)))

    /** The video player's current subtitle and audio track indexes (`getPlayStatus`). */
    fun currentTracks(): Pair<Int?, Int?> {
        val o = get("ZidooVideoPlay/getPlayStatus")
        return ZidooControls.currentTrack(o, "subtitle")?.first to ZidooControls.currentTrack(o, "audio")?.first
    }

    fun chapter(next: Boolean): Boolean = ZidooParse.ok(get(ZidooControls.chapterPath(next)))

    fun playQueue(): List<ZidooQueueItem> = ZidooControls.queue(getAny(ZidooControls.QUEUE_PATH, slow = true))

    fun playQueueItem(item: ZidooQueueItem, index: Int): Boolean = get(ZidooControls.playQueuePath(item, index)) != null

    /**
     * Moves [by] tracks through the queue (1.5.6): next or previous that many times, for an app
     * whose queue can't be picked from (Apple's Apple Music). Going back starts from the track's
     * beginning, or the first "previous" would only restart it.
     */
    fun skipTracks(by: Int) {
        if (by < 0) get("MusicControl/v2/seekTo?time=0")
        repeat(kotlin.math.abs(by)) { i ->
            if (i > 0 || by < 0) Thread.sleep(SKIP_GAP_MS)
            get(if (by > 0) "MusicControl/v2/playNext" else "MusicControl/v2/playLast")
        }
    }

    fun queueArtUrl(item: ZidooQueueItem): String? = item.artUrl ?: if (item.local) musicArtUrl(item.id, item.type) else null

    fun outputs(): ZidooOutputs = ZidooControls.outputs(get(ZidooControls.OUTPUTS_PATH, slow = true))

    fun setOutput(tag: String): Boolean = ZidooParse.ok(get(ZidooControls.setOutputPath(tag)))

    /**
     * Whether [url] answers with an image (1.4.9): an episode often has no backdrop of its own, so
     * the registry tries the series', then the season's, and keeps the first one that is there.
     */
    fun isImage(url: String): Boolean = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: return@use false
            // A missing image is a JSON error with HTTP 200 ({"status":803,…}); the image may come
            // with no image/* type, so its first byte decides.
            val type = resp.header("Content-Type").orEmpty()
            resp.isSuccessful && bytes.isNotEmpty() && (type.startsWith("image/") || bytes[0] != '{'.code.toByte())
        }
    }.getOrDefault(false)

    fun musicArtUrl(musicId: Long, musicType: Int): String =
        base() + "ZidooMusicControl/v2/getImage?id=$musicId&music_type=$musicType&type=4&target=16"

    private fun enc(value: String) = ZidooParse.query(value)

    companion object {
        const val PORT = 9529
        private const val SKIP_GAP_MS = 400L
        private const val TIMEOUT_S = 3L
        private const val SLOW_TIMEOUT_S = 12L
        private const val MUSIC_LIST_MAX = 500
        private const val FOLDER_MAX = 4
        private const val ART_W = 1080
        private const val ART_H = 720
        private const val POSTER_W = 480
        private const val POSTER_H = 720
        private const val WOL_PORT = 9
        private const val MAC_BYTES = 6
        private const val WOL_REPEATS = 16
        private const val HEX = 16

        /** Sends a Wake-on-LAN magic packet for [mac] to the local network's broadcast address. */
        fun wake(mac: String): Boolean = runCatching {
            val bytes = mac.split(':', '-').map { it.toInt(HEX).toByte() }
            require(bytes.size == MAC_BYTES)
            val packet = ByteArray(MAC_BYTES) { 0xFF.toByte() } + List(WOL_REPEATS) { bytes }.flatten()
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName("255.255.255.255"), WOL_PORT))
            }
            true
        }.getOrDefault(false)
    }
}
