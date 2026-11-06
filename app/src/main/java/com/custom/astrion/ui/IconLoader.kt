package com.custom.astrion.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * Decode a PNG icon from [path], downsampling oversized source images so a
 * 2000+px-wide PNG displayed at ~28dp doesn't eat several MB of bitmap
 * memory plus a matching GPU texture — the cause of the scrolling lag
 * reported in upstream issue #36 on the HA100 hardware.
 *
 * Two-pass decode: first inspect the source dimensions with
 * [BitmapFactory.Options.inJustDecodeBounds] (allocates nothing — only
 * reads the header), compute an [BitmapFactory.Options.inSampleSize] that
 * brings the longer edge down to around [targetPx], then decode for real.
 * The result is an [ImageBitmap] close to the rendered size — at most ~2×
 * the target on each axis, the floor `inSampleSize` (power-of-two-only)
 * allows.
 *
 * Returns null on any I/O/decode failure or missing file, matching the
 * prior `BitmapFactory.decodeFile()?.asImageBitmap()` convention so call
 * sites keep their existing fallback (placeholder / blank spacer).
 */
fun decodeIconSampled(path: String, targetPx: Int, trim: Boolean = false): ImageBitmap? {
    val f = File(path)
    if (!f.exists()) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        val sample = inSampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return@runCatching null
        (if (trim) trimTransparent(bitmap) else bitmap).asImageBitmap()
    }.getOrNull()
}

/**
 * [bitmap] without its fully transparent margins (1.5.0, `icon_trim`): a channel logo saved as
 * 182 × 102 often has its drawing in a 160 × 38 band, which then showed small whatever the size.
 * Returned as is when it has no alpha or nothing to cut.
 */
fun trimTransparent(bitmap: Bitmap): Bitmap {
    if (!bitmap.hasAlpha()) return bitmap
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val box = opaqueBounds(pixels, w, h) ?: return bitmap
    val whole = intArrayOf(0, 0, w - 1, h - 1)
    if (box.contentEquals(whole)) return bitmap
    return Bitmap.createBitmap(bitmap, box[0], box[1], box[2] - box[0] + 1, box[3] - box[1] + 1)
}

/** left, top, right, bottom (inclusive) of the pixels whose alpha isn't 0; null when all are. */
internal fun opaqueBounds(pixels: IntArray, w: Int, h: Int): IntArray? {
    fun opaque(x: Int, y: Int) = pixels[y * w + x] ushr ALPHA_SHIFT != 0
    fun rowHas(y: Int) = (0 until w).any { opaque(it, y) }
    fun colHas(x: Int, top: Int, bottom: Int) = (top..bottom).any { opaque(x, it) }
    val top = (0 until h).firstOrNull(::rowHas) ?: return null
    val bottom = (h - 1 downTo top).first(::rowHas)
    val left = (0 until w).first { colHas(it, top, bottom) }
    val right = (w - 1 downTo left).first { colHas(it, top, bottom) }
    return intArrayOf(left, top, right, bottom)
}

private const val ALPHA_SHIFT = 24

/**
 * Decode a JPEG byte array (e.g. an MJPEG frame) with the same two-pass
 * downsample as [decodeIconSampled]. Camera streams are often 720p or
 * 1080p but displayed at the card's rendered width (~480px on the HA100),
 * so decoding every frame at full source resolution wastes CPU and
 * creates large transient bitmaps that drive GC churn at 10-30 fps.
 */
fun decodeByteArraySampled(bytes: ByteArray, targetPx: Int): ImageBitmap? {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = inSampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()
}

/**
 * [BitmapFactory.Options.inSampleSize] for a decode whose longer edge
 * should land at or above [targetPx]. `inSampleSize` is an int >= 1 that
 * the decoder rounds down to the nearest power of two, so we step it up
 * (halving the long edge each time) until the sampled long edge would be
 * <= target — yielding at most ~2× target on each axis, the smallest
 * sample that still satisfies the contract. Falls back to 1 (no sampling)
 * when the source is already smaller than the target or its dimensions
 * couldn't be read.
 */
private fun inSampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
    if (targetPx <= 0 || width <= 0 || height <= 0) return 1
    var sample = 1
    var longest = maxOf(width, height)
    while (longest / 2 >= targetPx) {
        longest /= 2
        sample *= 2
    }
    return sample
}
