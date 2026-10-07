package com.custom.astrion.appletv

import com.custom.astrion.appletv.mrp.MrpPlayState
import com.custom.astrion.appletv.mrp.MrpPlaying

/** Where a discovered/stored Apple TV can currently be reached. */
data class AppleTvEndpoint(val host: String, val port: Int)

/**
 * Which service now-playing info is actually paired against. [AirPlayTunnel] — MRP tunnelled
 * through an AirPlay 2 remote-control session — is what every tvOS >= 15 Apple TV requires, since
 * Apple removed the old standalone MRP service in tvOS 15. [Classic] (plain MRP-over-TCP) only
 * still works on an older Apple TV; kept for that case rather than dropped outright.
 */
enum class MrpTransport { Classic, AirPlayTunnel }

/**
 * An Apple TV's separate MRP (Media Remote Protocol) pairing — optional, and independent of the
 * Companion one: it's what gives title/artist/artwork/position, not remote-control keys. A device
 * with [AppleTvSpec.mrp] absent still works fully for control, just without now-playing info.
 */
data class MrpSpec(
    val host: String,
    val port: Int,
    val serviceName: String,
    val credentials: AppleTvCredentials,
    val transport: MrpTransport = MrpTransport.AirPlayTunnel
)

/** Everything one [AppleTvDevice] needs to know about the Apple TV it drives. */
data class AppleTvSpec(
    val localId: String,
    /** Home-Assistant-style entity id this device is published under, e.g. `media_player.appletv_salon`. */
    val entityId: String,
    val name: String,
    val host: String,
    val port: Int,
    /** mDNS instance name, used to re-find the device when its dynamic port or IP changes. */
    val serviceName: String,
    val credentials: AppleTvCredentials,
    val mrp: MrpSpec? = null
)

/** Live, connection-level state of one Apple TV. */
data class AppleTvSnapshot(
    val connected: Boolean = false,
    val attention: AttentionState = AttentionState.Unknown,
    val mediaFlags: Int = 0,
    val volume: Double? = null,
    val apps: Map<String, String> = emptyMap(),
    val mrpConnected: Boolean = false,
    val playing: MrpPlaying? = null,
    /** Set once artwork for the current item has actually been fetched; paired with [artworkVersion] to bust caches when it changes. */
    val artworkJpeg: ByteArray? = null,
    val artworkVersion: Int = 0
) {
    // A data class with a ByteArray property needs these written by hand, or two snapshots that
    // only differ by artwork bytes would incorrectly compare equal (breaking the "publish only on
    // change" checks both AppleTvDevice and MrpPlayerStateManager rely on) — or, as likely here,
    // *always* compare unequal because ByteArray uses identity equality, which is nearly as bad
    // (a publish on every unrelated change once artwork is set). [artworkVersion] is the real
    // identity for equality purposes; the bytes are carried alongside it.
    override fun equals(other: Any?): Boolean = other is AppleTvSnapshot &&
        connected == other.connected &&
        attention == other.attention &&
        mediaFlags == other.mediaFlags &&
        volume == other.volume &&
        apps == other.apps &&
        mrpConnected == other.mrpConnected &&
        playing == other.playing &&
        artworkVersion == other.artworkVersion

    override fun hashCode(): Int = listOf(connected, attention, mediaFlags, volume, apps, mrpConnected, playing, artworkVersion).hashCode()
}

/** A Home-Assistant-shaped view of an Apple TV: an entity state string plus its attributes. */
data class AppleTvEntityData(val entityId: String, val state: String, val attributes: Map<String, Any?>)

/**
 * Maps an [AppleTvSnapshot] to the same `media_player` shape Home Assistant exposes for an Apple
 * TV (state names, `source_list`, `supported_features`, `volume_level`, and — once MRP is paired
 * — `media_title`/`media_artist`/`media_album_name`/`media_duration`/`media_position`/
 * `media_position_updated_at`/`app_id`/`app_name`/`entity_picture`), so every existing
 * `media_player`-aware card and hotkey can drive an Apple TV directly, with or without Home
 * Assistant involved.
 *
 * Companion alone (no MRP paired) still gives power state, which playback controls are currently
 * offered (=> a coarse playing/paused/idle), volume, and the installed apps — MRP only adds to
 * that, it's never required.
 */
object AppleTvEntityMapper {
    // Home Assistant MediaPlayerEntityFeature bits.
    private const val PAUSE = 1
    private const val VOLUME_SET = 4
    private const val PREVIOUS_TRACK = 16
    private const val NEXT_TRACK = 32
    private const val TURN_ON = 128
    private const val TURN_OFF = 256
    private const val PLAY_MEDIA = 512
    private const val VOLUME_STEP = 1024
    private const val SELECT_SOURCE = 2048
    private const val PLAY = 16384

    // Now that AppleTvRegistry answers media_player/browse_media locally for a directly-paired
    // device (see HaClient.localBrowseMediaHandler), this entity can genuinely browse/launch
    // installed apps through MediaBrowser — advertise it so cards gated on BROWSE_MEDIA (the
    // "Browse" button in MediaPlayerDetailDialog and MediaPlayerCard's full variant) show up for
    // it exactly as they would for a real Home Assistant media_player.
    private const val BROWSE_MEDIA = 131072

    fun map(spec: AppleTvSpec, snapshot: AppleTvSnapshot): AppleTvEntityData {
        val state = stateOf(snapshot)
        val attributes = linkedMapOf<String, Any?>("friendly_name" to spec.name, "device_class" to "tv")
        if (snapshot.connected) {
            attributes["source_list"] = snapshot.apps.values.sortedWith(String.CASE_INSENSITIVE_ORDER)
            attributes["supported_features"] = featuresOf(snapshot)
            if (snapshot.mediaFlags and MediaControlFlags.VOLUME != 0 && snapshot.volume != null) {
                attributes["volume_level"] = snapshot.volume
            }
        }
        // Keyed on `playing` itself, not `mrpConnected`: the MRP tunnel cycles its connection every
        // ~30s on real hardware (tvOS's own doing — see CHANGELOG), and AppleTvDevice deliberately
        // keeps the last known `playing` across that gap instead of nulling it, so this still
        // publishes the last known title/artist/artwork through a brief reconnect instead of
        // flickering to "no now-playing info" and back every cycle.
        if (snapshot.playing != null) addNowPlayingAttributes(attributes, spec, snapshot)
        return AppleTvEntityData(spec.entityId, state, attributes)
    }

    private fun addNowPlayingAttributes(attributes: MutableMap<String, Any?>, spec: AppleTvSpec, snapshot: AppleTvSnapshot) {
        val playing = snapshot.playing ?: return
        val np = playing.nowPlaying
        attributes["media_content_type"] = "music"
        np?.title?.let { attributes["media_title"] = it }
        np?.artist?.let { attributes["media_artist"] = it }
        np?.album?.let { attributes["media_album_name"] = it }
        np?.genre?.let { attributes["media_genre"] = it }
        np?.durationSeconds?.let { attributes["media_duration"] = it.toInt() }
        np?.elapsedTimeSeconds?.let { attributes["media_position"] = it.toInt() }
        np?.positionUpdatedAtUnixMs?.let { attributes["media_position_updated_at"] = isoInstant(it) }
        playing.appBundleId?.let { bundleId ->
            attributes["app_id"] = bundleId
            val name = playing.appName?.takeIf { it.isNotBlank() } ?: snapshot.apps[bundleId]
            if (name != null) attributes["app_name"] = name
        }
        if (snapshot.artworkJpeg != null) {
            // Absolute (not HA-relative): this device's own local web server, not Home
            // Assistant's — HaClient.fetchBitmap() treats an "http..." path as external and
            // fetches it as-is, with no HA base URL or bearer token attached, which is exactly
            // right here and (unlike a relative path) keeps this working with no Home Assistant
            // configured at all. Requires the local web server (Settings) to stay enabled.
            attributes["entity_picture"] = "http://127.0.0.1:8080/appletv-artwork/${spec.localId}?v=${snapshot.artworkVersion}"
        }
    }

    private fun isoInstant(unixMs: Long): String {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US)
        formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return formatter.format(java.util.Date(unixMs))
    }

    fun stateOf(s: AppleTvSnapshot): String {
        if (!s.connected) return "unavailable"
        if (s.attention == AttentionState.Asleep) return "off"
        // MRP's playback state is precise (it's what the app itself reports); prefer it whenever we
        // have one — including the last one held across a brief MRP reconnect (see above), not
        // only while `mrpConnected` is literally true at this instant.
        if (s.playing != null) {
            return when (s.playing?.state) {
                MrpPlayState.Playing -> "playing"
                MrpPlayState.Paused -> "paused"
                MrpPlayState.Loading -> "buffering"
                MrpPlayState.Idle, null -> "idle"
            }
        }
        return when {
            s.mediaFlags and MediaControlFlags.PAUSE != 0 -> "playing"
            s.mediaFlags and MediaControlFlags.PLAY != 0 -> "paused"
            else -> "idle"
        }
    }

    fun featuresOf(s: AppleTvSnapshot): Int {
        var f = PAUSE or PLAY or PREVIOUS_TRACK or NEXT_TRACK or TURN_ON or TURN_OFF or SELECT_SOURCE or PLAY_MEDIA or BROWSE_MEDIA
        if (s.mediaFlags and MediaControlFlags.VOLUME != 0) f = f or VOLUME_SET or VOLUME_STEP
        return f
    }
}
