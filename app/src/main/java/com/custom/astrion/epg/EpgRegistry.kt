package com.custom.astrion.epg

import android.content.Context
import android.os.Process
import android.util.Log
import android.util.Xml
import com.custom.astrion.AppLanguage
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.tmdb.TmdbInfo
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/** Android's XmlPullParser as [XmlTokens]. */
private class PullTokens(private val p: XmlPullParser, private val stopped: () -> Boolean) : XmlTokens {
    override fun next(): XmlTokens.Token {
        // The guide was stopped (its settings saved again): this reading is no longer wanted.
        if (stopped()) throw InterruptedIOException("stopped")
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> return XmlTokens.Token.START
                XmlPullParser.END_TAG -> return XmlTokens.Token.END
                XmlPullParser.TEXT -> return XmlTokens.Token.TEXT
                XmlPullParser.END_DOCUMENT -> return XmlTokens.Token.DONE
            }
        }
    }

    override val name: String get() = p.name.orEmpty()

    override fun attr(name: String): String? = p.getAttributeValue(null, name)

    override val text: String get() = p.text.orEmpty()
}

/**
 * The TV guide (1.6.0): an XMLTV source set on the web page (xmltvfr.fr's TNT by default there,
 * any other one), downloaded once a day and read as it comes, of which the remote keeps the
 * channels chosen over the next day and a half ([EpgGuide], saved on the remote). Each channel is
 * an entity (`sensor.epg_<channel>`, see [EpgEntities]), updated every minute; [infoFor] gives
 * what's playing live its synopsis (see tmdb/TmdbRegistry).
 */
class EpgRegistry(
    private val context: Context,
    private val url: String,
    private val chosen: List<String>,
    private val log: (String) -> Unit = { Log.d(TAG, it) }
) {
    // Background priority (1.6.0): reading a big guide must never slow the remote's keys or screen.
    private val executor =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "Epg").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    private val dir = File(context.filesDir, "epg")
    private val file = File(dir, "guide.json")
    private val meta = context.getSharedPreferences(META, Context.MODE_PRIVATE)

    @Volatile private var ha: HaClient? = null

    @Volatile private var stopped = false

    @Volatile var guide: EpgGuide? = null
        private set

    @Volatile var fetching = false
        private set

    @Volatile var lastError: String? = null
        private set

    @Volatile var bytes: Long = meta.getLong(KEY_BYTES, 0L)
        private set

    private var published = emptySet<String>()
    private var tick: ScheduledFuture<*>? = null

    fun start(haClient: HaClient) {
        ha = haClient
        executor.execute {
            // The saved guide, when it's the same source and the same channels.
            val sameSource = meta.getString(KEY_SOURCE, null) == sourceKey()
            guide = file.takeIf { sameSource && it.isFile }?.let { runCatching { EpgGuide.fromJson(it.readText()) }.getOrNull() }
            publish()
            val age = System.currentTimeMillis() - (guide?.fetched ?: 0L)
            if (guide == null || age > REFRESH_MS) fetch()
        }
        executor.scheduleWithFixedDelay({
            if (System.currentTimeMillis() - (guide?.fetched ?: 0L) > REFRESH_MS) fetch()
        }, CHECK_MS, CHECK_MS, TimeUnit.MILLISECONDS)
        tick = executor.scheduleWithFixedDelay({ publish() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        stopped = true
        tick?.cancel(false)
        executor.shutdownNow()
        ha?.setLocalEntities(emptyList(), published)
        published = emptySet()
        ha = null
    }

    /** Downloads the guide again now (the web page's button). */
    fun refresh() {
        runCatching { executor.execute { fetch() } }
    }

    /** What's on air under [title] (or [alsoTitle]) right now, as the cards show it. */
    fun infoFor(title: String, alsoTitle: String?): TmdbInfo? = guide?.infoFor(title, System.currentTimeMillis(), alsoTitle)

    private fun sourceKey() = url + "|" + chosen.joinToString(",")

    private fun fetch() {
        if (url.isBlank() || stopped) return
        fetching = true
        val began = System.currentTimeMillis()
        log("guide: downloading $url")
        try {
            val now = System.currentTimeMillis()
            val req = Request.Builder().url(url).header("Accept-Encoding", "identity").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("empty answer")
                val counted = CountingStream(body.byteStream())
                val next = read(unpacked(BufferedInputStream(counted, BUF)), now)
                if (stopped) return
                bytes = counted.count
                guide = next
                dir.mkdirs()
                file.writeText(next.toJson())
                meta.edit().putString(KEY_SOURCE, sourceKey()).putLong(KEY_BYTES, bytes).apply()
                lastError = null
                val took = (System.currentTimeMillis() - began) / MS
                val counts = "${next.channels.size} channels kept of ${next.available.size}, ${next.showCount} programmes"
                log("guide: $counts, ${bytes / KB} KB in $took s")
            }
        } catch (e: IOException) {
            // A timeout is an InterruptedIOException too: only "stopped" is a stop.
            if (stopped) {
                log("guide: reading stopped")
                return
            }
            lastError = e.message ?: e.javaClass.simpleName
            log("guide not downloaded: $lastError")
        } catch (e: XmlPullParserException) {
            lastError = "not an XMLTV guide (${e.message})"
            log("guide not read: $lastError")
        } catch (e: RuntimeException) {
            lastError = e.message ?: e.javaClass.simpleName
            log("guide not read: $lastError")
        } finally {
            fetching = false
        }
        publish()
    }

    /** Gzip or plain XML, told by the first two bytes. */
    private fun unpacked(input: BufferedInputStream): InputStream {
        input.mark(2)
        val gzip = input.read() == GZ1 && input.read() == GZ2
        input.reset()
        return if (gzip) BufferedInputStream(GZIPInputStream(input, BUF), BUF) else input
    }

    private fun read(input: InputStream, now: Long): EpgGuide {
        val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
        parser.setInput(input, null)
        val available = ArrayList<EpgChannel>()
        val shows = ArrayList<EpgShow>()
        val wanted = chosen.toSet()
        // Nothing chosen yet: every channel of a small guide (the TNT), none of a big one.
        var all: Boolean? = null
        val keep: (String) -> Boolean = { id ->
            if (wanted.isNotEmpty()) {
                id in wanted
            } else {
                (all ?: (available.size <= DEFAULT_ALL).also { all = it })
            }
        }
        val language = AppLanguage.get(context).ifBlank { Locale.getDefault().language }
        EpgParse.read(PullTokens(parser) { stopped }, keep, now - BEFORE_MS, now + AHEAD_MS, language, available::add, shows::add)
        val kept =
            if (wanted.isNotEmpty()) {
                chosen.mapNotNull { id -> available.firstOrNull { it.id == id } }
            } else {
                available.filter { keep(it.id) }
            }
        return EpgGuide(kept, shows, available, now)
    }

    /** Every channel's entity, as of now; those no longer kept removed. All in one republish. */
    private fun publish() {
        val c = ha ?: return
        val g = guide
        val now = System.currentTimeMillis()
        val states = LinkedHashMap<String, EntityState>()
        g?.channels?.forEach { ch ->
            val id = EpgEntities.entityId(ch.id)
            if (id in states) return@forEach
            val (state, attrs) = EpgEntities.of(ch, g, now)
            states[id] =
                EntityState(
                    entityId = id,
                    state = state,
                    attributes = JsonObject(attrs.mapValues { json(it.value) }),
                    lastChanged = null,
                    lastUpdated = now.toString()
                )
        }
        c.setLocalEntities(states.values, published - states.keys)
        published = states.keys.toSet()
    }

    private fun json(v: Any?): JsonPrimitive = when (v) {
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v?.toString())
    }

    /** Counts what's downloaded, for the web page. */
    private class CountingStream(private val inner: InputStream) : InputStream() {
        var count = 0L

        override fun read(): Int = inner.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }

        override fun close() = inner.close()
    }

    companion object {
        private const val TAG = "Epg"
        private const val META = "astrion_epg"
        private const val KEY_SOURCE = "source"
        private const val KEY_BYTES = "bytes"
        private const val TIMEOUT_S = 60L
        private const val BUF = 64 * 1024
        private const val KB = 1024
        private const val MS = 1000
        private const val GZ1 = 0x1f
        private const val GZ2 = 0x8b
        private const val DEFAULT_ALL = 60
        private const val BEFORE_MS = 3 * 3_600_000L
        private const val AHEAD_MS = 48 * 3_600_000L
        private const val REFRESH_MS = 20 * 3_600_000L
        private const val CHECK_MS = 3_600_000L
        private const val TICK_MS = 60_000L
    }
}

/** The most channels a guide keeps (1.6.0): each is an entity updated every minute, with its programmes in memory. */
const val MAX_CHANNELS = 150

/** Removes the guide kept on the remote: no source any more (1.6.0). */
fun clearEpg(context: Context) {
    File(context.filesDir, "epg").deleteRecursively()
    context.getSharedPreferences("astrion_epg", Context.MODE_PRIVATE).edit().clear().apply()
}

/** Starts the TV guide when a source is set (1.6.0); null otherwise. */
fun installEpg(context: Context, haClient: HaClient, log: (String) -> Unit = {}): EpgRegistry? {
    val url = RemoteSettings.epgUrl(context).takeIf { it.isNotBlank() } ?: return null
    // At most MAX_CHANNELS, a list saved before the limit (1.6.0) included: the remote's memory.
    val channels = RemoteSettings.epgChannels(context).take(MAX_CHANNELS)
    return EpgRegistry(context, url, channels, log).also { it.start(haClient) }
}
