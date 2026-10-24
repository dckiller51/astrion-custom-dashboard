package com.custom.astrion.tvhelper

import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Follows the TV's media sessions — the "now playing" card every app fills for Android, the
 * Google Assistant and Bluetooth remotes. Android only lets an app read other apps' sessions
 * when it's a notification listener the person allowed; notifications themselves are ignored.
 * The current session goes to [HelperApp.store] for the web server.
 */
class MediaListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var controllers: List<MediaController> = emptyList()

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> follow(list.orEmpty()) }

    private val callback =
        object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = refresh(coverChanged = false)

            override fun onMetadataChanged(metadata: MediaMetadata?) = refresh(coverChanged = true)

            override fun onSessionDestroyed() = refresh(coverChanged = true)
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val m = getSystemService(MediaSessionManager::class.java) ?: return
        manager = m
        val me = ComponentName(this, MediaListener::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessionsChanged, me, handler)
            follow(m.getActiveSessions(me))
            instance = this
            Log.i(TAG, "following media sessions")
        } catch (e: SecurityException) {
            Log.w(TAG, "no access to media sessions: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        instance = null
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        follow(emptyList())
        super.onListenerDisconnected()
    }

    private fun follow(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = list
        list.forEach { it.registerCallback(callback, handler) }
        refresh(coverChanged = true)
    }

    /** The session to show and control: the one playing, else the most recent one (Android lists them by priority). */
    private fun current(): MediaController? = controllers.firstOrNull {
        it.playbackState?.state == PlaybackState.STATE_PLAYING
    } ?: controllers.firstOrNull()

    private fun refresh(coverChanged: Boolean) {
        val c = current()
        val snapshot = c?.let { snapshotOf(it) } ?: NowPlaying()
        HelperApp.store.update(snapshot, c?.metadata?.let { coverOf(it) }, artChanged = coverChanged)
    }

    private fun snapshotOf(c: MediaController): NowPlaying {
        val md = c.metadata
        val ps = c.playbackState
        fun text(vararg keys: String) = keys.firstNotNullOfOrNull { k -> md?.getString(k)?.takeIf { it.isNotBlank() } }
        val position = ps?.position?.takeIf { it >= 0 }
        return NowPlaying(
            packageName = c.packageName,
            appName = labelOf(c.packageName),
            state = stateOf(ps?.state),
            title = text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE),
            subtitle = text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            artist = text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = text(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            positionMs = position,
            // The session times positions on the uptime clock; the remote needs wall-clock time.
            positionUpdatedAt = ps?.lastPositionUpdateTime?.takeIf { position != null && it > 0 }
                ?.let { System.currentTimeMillis() - (SystemClock.elapsedRealtime() - it) },
            speed = ps?.playbackSpeed ?: 1f,
            artUrl = text(
                MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
            )
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        )
    }

    private fun labelOf(pkg: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        Log.d(TAG, "no label for $pkg: ${e.message}")
        null
    }

    /** The session's cover as a JPEG, at most [COVER_PX] wide or high. */
    private fun coverOf(md: MediaMetadata): ByteArray? {
        val bitmap =
            md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: return null
        val scale = minOf(1f, COVER_PX.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled =
            if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "AstrionHelper"
        private const val COVER_PX = 512
        private const val JPEG_QUALITY = 85

        @Volatile private var instance: MediaListener? = null

        /** True while Android lets the helper follow media sessions. */
        val connected: Boolean get() = instance != null

        fun stateOf(state: Int?): String = when (state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> NowPlaying.STATE_PLAYING
            PlaybackState.STATE_PAUSED -> NowPlaying.STATE_PAUSED
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> NowPlaying.STATE_BUFFERING
            null -> NowPlaying.STATE_NONE
            else -> NowPlaying.STATE_IDLE
        }

        /** Runs [action] on the current session; false when there is none. */
        fun control(action: String, positionMs: Long?): Boolean {
            val listener = instance ?: return false
            val c = listener.current() ?: return false
            val controls = c.transportControls
            listener.handler.post {
                when (action) {
                    "play" -> controls.play()
                    "pause" -> controls.pause()
                    "play_pause" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
                    "stop" -> controls.stop()
                    "next" -> controls.skipToNext()
                    "previous" -> controls.skipToPrevious()
                    "seek" -> positionMs?.let { controls.seekTo(it) }
                }
            }
            return true
        }
    }
}
