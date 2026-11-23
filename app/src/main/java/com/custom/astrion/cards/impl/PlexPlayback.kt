package com.custom.astrion.cards.impl

import android.content.Context
import android.util.Log
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall

private const val TAG = "PlexPlayback"
private const val MRMC_PORT = 8080
private const val MAX_PORT = 65535

/**
 * Where a Plex item plays, as a card sets it (1.6.2, shared by the Plex card and the media remote
 * card): the player that opens Plex ([mediaEntity], by its source [source]), a Plex client that
 * can play the item itself ([playEntity]), MrMC or Rivulet on an Apple TV.
 */
internal data class PlexPlayTarget(
    val mediaEntity: String,
    val source: String,
    val playEntity: String?,
    val playContentType: String,
    val mrmc: MrmcTarget?,
    val rivulet: Boolean
)

internal object PlexPlayback {
    /**
     * A card's settings: `play_entity`, `play_content_type`, `source`, `play_with`, `mrmc_*` —
     * each after [prefix] (the media remote card's are `plex_play_entity`…).
     */
    fun targetOf(config: CardConfig, mediaEntity: String, prefix: String = ""): PlexPlayTarget {
        fun opt(name: String) = config.string(prefix + name)?.trim()?.takeIf { it.isNotEmpty() }
        val playWith = opt("play_with")
        val mrmc =
            if (playWith == "mrmc") {
                opt("mrmc_host")?.let { host ->
                    MrmcTarget(
                        host = host,
                        port = config.int(prefix + "mrmc_port", default = MRMC_PORT).coerceIn(1, MAX_PORT),
                        mediaEntity = mediaEntity,
                        app = opt("mrmc_app") ?: "MrMC"
                    )
                }
            } else {
                null
            }
        return PlexPlayTarget(
            mediaEntity = mediaEntity,
            source = opt("source") ?: "Plex",
            playEntity = opt("play_entity"),
            // "video" for a client of Home Assistant's Plex integration; its Apple TV integration needs "url".
            playContentType = opt("play_content_type") ?: "video",
            mrmc = mrmc,
            rivulet = playWith == "rivulet"
        )
    }

    /** Plays [ratingKey] on [target]: MrMC, Rivulet, the Plex client, else opens the Plex app. */
    fun play(context: Context, client: HaClient, server: PlexServer, target: PlexPlayTarget, machineId: String?, ratingKey: String) {
        val mrmc = target.mrmc
        when {
            mrmc != null -> {
                Log.i(TAG, "play $ratingKey with MrMC on ${mrmc.host}")
                MrmcPlayer.play(context, client, server, mrmc, ratingKey)
            }
            target.rivulet -> playWithRivulet(client, target, ratingKey)
            else -> playOnClient(client, target, machineId, ratingKey)
        }
    }

    /** Opens the Plex app on the player, the fallback when the item itself can't be played. */
    fun openApp(client: HaClient, target: PlexPlayTarget) {
        client.callService(ServiceCall.of("media_player", "select_source", target.mediaEntity, "source" to target.source))
    }

    private fun playOnClient(client: HaClient, target: PlexPlayTarget, machineId: String?, ratingKey: String) {
        // The Plex client plays the item only when it's connected (the TV's Plex app open); an
        // Apple TV added in Astrion only opens the app: Plex on tvOS ignores the item in its links.
        if (target.playEntity != null && machineId != null) {
            val link = "plex://preplay/?metadataKey=$ratingKey&server=$machineId"
            Log.i(TAG, "play on ${target.playEntity}: $link")
            client.callService(
                ServiceCall.of(
                    "media_player",
                    "play_media",
                    target.playEntity,
                    "media_content_type" to target.playContentType,
                    "media_content_id" to link
                )
            )
        } else {
            Log.i(TAG, "open the Plex app on ${target.mediaEntity} (${if (machineId == null) "no server id" else "no play_entity"})")
            openApp(client, target)
        }
    }

    /** Rivulet on the Apple TV opens its own link: the item starts where it was left. */
    private fun playWithRivulet(client: HaClient, target: PlexPlayTarget, ratingKey: String) {
        val link = PlexRivulet.link(ratingKey) ?: return openApp(client, target)
        Log.i(TAG, "play with Rivulet on ${target.mediaEntity}: $link")
        client.callService(
            ServiceCall.of("media_player", "play_media", target.mediaEntity, "media_content_type" to "url", "media_content_id" to link)
        )
    }
}
