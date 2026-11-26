package com.custom.astrion.playnite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * A PC's game covers kept on the remote (1.6.3), made small, for the sources pop-up's cover view:
 * shown at once, and while the PC is off. In `<root>/playnite_covers/<localId>/<game id>.jpg`.
 * Called from the PC's own thread only.
 */
class PlayniteCovers(root: File?, localId: String) {
    private val dir = root?.let { File(it, "playnite_covers/" + safe(localId)) }

    /** The covers kept, by game id. */
    @Volatile var paths: Map<String, String> = load()
        private set

    /** Covers that couldn't be read this session: not asked again until the remote restarts. */
    private val failed = HashSet<String>()

    private fun load(): Map<String, String> =
        dir?.listFiles().orEmpty().filter { it.isFile && it.extension == "jpg" }.associate { it.nameWithoutExtension to it.absolutePath }

    /**
     * Fetches up to [max] missing covers of [games] (those Playnite has one for) and drops those
     * of games no longer offered. True when the covers kept changed.
     */
    fun refresh(games: List<PlayniteGame>, max: Int = BATCH, fetch: (String) -> ByteArray?): Boolean {
        val d = dir ?: return false
        val wanted = games.filter { it.cover }.map { it.id }.filter { safe(it) == it }.toSet()
        var changed = false
        paths.keys.filter { it !in wanted }.forEach {
            File(d, "$it.jpg").delete()
            changed = true
        }
        val missing = wanted.filter { it !in paths && it !in failed }.take(max)
        if (missing.isNotEmpty()) d.mkdirs()
        for (id in missing) {
            val bytes = runCatching { fetch(id) }.getOrNull()
            if (bytes != null && shrink(bytes, File(d, "$id.jpg"))) changed = true else failed.add(id)
        }
        if (changed) paths = load()
        return changed
    }

    /** [bytes] decoded, made [WIDTH] px wide at most, saved as a JPEG; false when it isn't a picture. */
    private fun shrink(bytes: ByteArray, to: File): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= WIDTH) sample *= 2
        val decoded =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return false
        val bitmap =
            if (decoded.width > WIDTH) {
                Bitmap.createScaledBitmap(decoded, WIDTH, (decoded.height.toLong() * WIDTH / decoded.width).toInt().coerceAtLeast(1), true)
            } else {
                decoded
            }
        val tmp = File(to.parentFile, to.name + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        if (bitmap !== decoded) bitmap.recycle()
        decoded.recycle()
        tmp.renameTo(to)
    }.getOrDefault(false)

    companion object {
        private const val WIDTH = 300
        private const val QUALITY = 85
        private const val BATCH = 15

        private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}
