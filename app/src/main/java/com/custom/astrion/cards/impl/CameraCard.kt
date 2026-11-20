package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.custom.astrion.ScreenCapture
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.decodeByteArraySampled
import com.custom.astrion.ui.icons.MdiIcons
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Response

/**
 * Live camera card.
 *
 * Renders a Home Assistant `camera.*` entity as a live picture. Two modes,
 * chosen with the `mode` option (default `stream`):
 *
 *  - `stream`   — pulls HA's MJPEG feed (`/api/camera_proxy_stream/…`) and
 *                 decodes each JPEG frame as it arrives, giving real motion.
 *                 If the stream can't start or drops, it falls back to periodic
 *                 still snapshots for a few cycles, then retries the stream.
 *  - `snapshot` — just re-fetches a single still (`entity_picture`) every
 *                 `snapshot_interval` seconds. Lowest CPU; good for a weak SoC
 *                 or a camera whose live stream is too laggy.
 *
 * Both the stream URL and the still are derived from the entity's live
 * `entity_picture` attribute (which already carries the rotating access token),
 * read fresh via [rememberUpdatedState] so token rotation never tears the
 * stream down.
 *
 * Options:
 *   - `entity_id`         (required) e.g. `camera.front_door`
 *   - `name`              optional label (defaults to the friendly name)
 *   - `mode`              `stream` (default) | `snapshot`
 *   - `snapshot_interval` seconds between stills in snapshot mode / fallback (default 2)
 *   - `aspect`            width/height ratio of the card (default 1.777 = 16:9)
 *   - `fit`               `cover` (default, fills & crops) | `contain` (letterboxed)
 *
 * Tap the card to open a fullscreen viewer. In the viewer, pinch to zoom
 * (1x–8x) and drag to pan; the hardware BACK key or the on-screen close
 * button dismisses it. The live stream keeps flowing while the viewer is
 * open, so the zoomed view stays live.
 */
class CameraCard : CardRenderer {
    override val type = "camera"

    private companion object {
        const val PROXY = "/api/camera_proxy/"
        const val PROXY_STREAM = "/api/camera_proxy_stream/"
        const val DEFAULT_INTERVAL_S = 2
        const val FALLBACK_POLLS = 5 // snapshot cycles after a stream drop before retrying the stream
        const val MAX_JPEG_BYTES = 6 * 1024 * 1024 // guard against a corrupt stream with no EOI marker
    }

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id")
        val mode = if (config.string("mode") == "snapshot") "snapshot" else "stream"
        val intervalMs = (config.int("snapshot_interval", DEFAULT_INTERVAL_S).coerceAtLeast(1)) * 1000L
        val aspect = ((config.options["aspect"] as? Number)?.toFloat() ?: (16f / 9f)).coerceAtLeast(0.2f)
        val contentScale = if (config.string("fit") == "contain") ContentScale.Fit else ContentScale.Crop

        val entity = entityId?.let { ctx.entities[it] }
        val label = config.string("name") ?: entity?.friendlyName ?: entityId ?: "Camera"
        val entityPicture = entity?.attrString("entity_picture")
        val currentPicture = rememberUpdatedState(entityPicture)

        var frame by remember(entityId, mode) { mutableStateOf<ImageBitmap?>(null) }
        var error by remember(entityId, mode) { mutableStateOf(false) }
        var live by remember(entityId, mode) { mutableStateOf(false) }
        var showMaximized by remember { mutableStateOf(false) }

        // Target decode size: the card is fillMaxWidth, so the screen width
        // in px is a close match. Downsample 720p/1080p MJPEG frames to this
        // instead of decoding at full source resolution every frame.
        val targetPx = with(LocalDensity.current) {
            LocalConfiguration.current.screenWidthDp.dp.toPx()
        }.toInt()

        LaunchedEffect(entityId, mode, ctx.screenOn) {
            if (entityId.isNullOrBlank()) {
                error = true
                return@LaunchedEffect
            }

            // Screen off: don't stream or poll — this card has no viewer to
            // show frames to, and both modes would otherwise happily burn CPU
            // (JPEG decode) and keep the WiFi radio out of deep sleep all
            // night on whatever page was left on screen (this is exactly what
            // drained a full charge overnight before this check existed).
            // The last decoded `frame` is left in place so the card resumes
            // showing something immediately once the screen — and this
            // LaunchedEffect, re-keyed on ctx.screenOn flipping back to true —
            // comes back.
            if (!ctx.screenOn) return@LaunchedEffect

            // A blocking input.read() on a silent MJPEG stream won't notice
            // coroutine cancellation on its own, so when this effect is disposed
            // (card scrolled off / entity or mode changed) we close the live
            // response from here — that makes the blocked read throw and unwind
            // promptly instead of leaking a thread + socket.
            val liveResponse = arrayOfNulls<Response>(1)
            coroutineContext.job.invokeOnCompletion { runCatching { liveResponse[0]?.close() } }

            suspend fun pollSnapshot() {
                val pic = currentPicture.value
                if (pic.isNullOrBlank()) {
                    if (frame == null) error = true
                    return
                }
                val bmp = ctx.client.fetchBitmap(pic, targetPx)
                if (bmp != null) {
                    frame = bmp
                    error = false
                } else if (frame == null) {
                    error = true
                }
            }

            while (isActive) {
                if (mode == "snapshot") {
                    live = false
                    pollSnapshot()
                    delay(intervalMs)
                    continue
                }

                // stream mode ------------------------------------------------
                val pic = currentPicture.value
                if (pic.isNullOrBlank()) {
                    if (frame == null) error = true
                    delay(1500)
                    continue
                }
                val streamPath =
                    if (pic.contains(PROXY)) pic.replace(PROXY, PROXY_STREAM) else pic

                val streamed =
                    runCatching {
                        val resp =
                            withContext(Dispatchers.IO) { ctx.client.openStream(streamPath) }
                                ?: return@runCatching false
                        liveResponse[0] = resp
                        try {
                            resp.use { r ->
                                if (!r.isSuccessful || r.body == null) return@runCatching false
                                readMjpeg(r, targetPx) { bmp ->
                                    frame = bmp
                                    error = false
                                    live = true
                                }
                            }
                        } finally {
                            liveResponse[0] = null
                        }
                        true
                    }.getOrDefault(false)

                live = false
                if (!isActive) break

                // Stream ended or never started — keep the picture fresh with a
                // few still polls, then loop back up and retry the live stream.
                if (!streamed && frame == null) pollSnapshot()
                repeat(FALLBACK_POLLS) {
                    if (!isActive) return@LaunchedEffect
                    pollSnapshot()
                    delay(intervalMs)
                }
            }
        }

        Box(
            modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF0E1116))
                .pointerInput(entityId) {
                    detectTapGestures(onTap = { showMaximized = true })
                }
        ) {
            val img = frame
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale
                )
            } else {
                // No frame yet: connecting spinner-free placeholder, or an error glyph.
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (error) MdiIcons.VideoOff else MdiIcons.Video,
                        contentDescription = null,
                        tint = if (error) Color(0xFF6E828A) else Color(0xFF9FB6BD),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            // Bottom gradient + name (and a live dot while a stream is flowing).
            Box(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xC0000000))
                        )
                    ).padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (live) {
                        Box(
                            modifier =
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE5484D))
                        )
                    }
                    Text(
                        text = if (error && frame == null) "$label · unavailable" else label,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                        Modifier
                            .padding(start = if (live) 7.dp else 0.dp)
                            .weight(1f, fill = false)
                    )
                }
            }
        }

        if (showMaximized) {
            Dialog(
                onDismissRequest = { showMaximized = false },
                properties =
                androidx.compose.ui.window
                    .DialogProperties(usePlatformDefaultWidth = false)
            ) {
                ScreenCapture.TrackForScreenshots()
                BoxWithConstraints(
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    val containerW = constraints.maxWidth.toFloat()
                    val containerH = constraints.maxHeight.toFloat()

                    var scale by remember { mutableFloatStateOf(1f) }
                    var offsetX by remember { mutableFloatStateOf(0f) }
                    var offsetY by remember { mutableFloatStateOf(0f) }

                    val img = frame
                    if (img != null && containerW > 0f && containerH > 0f) {
                        // Displayed bitmap size at scale 1 under ContentScale.Fit.
                        val imgAspect = img.width.toFloat() / img.height.toFloat()
                        val containerAspect = containerW / containerH
                        val baseW: Float
                        val baseH: Float
                        if (imgAspect > containerAspect) {
                            baseW = containerW
                            baseH = containerW / imgAspect
                        } else {
                            baseH = containerH
                            baseW = containerH * imgAspect
                        }
                        val zoomedW = baseW * scale
                        val zoomedH = baseH * scale
                        // Allow panning only while the zoomed image exceeds the
                        // viewport on that axis; clamp so an edge can't leave the
                        // screen (the image always covers the viewport when zoomed).
                        val maxPanX = max(0f, (zoomedW - containerW) / 2f)
                        val maxPanY = max(0f, (zoomedH - containerH) / 2f)
                        val clampedX = if (maxPanX > 0f) offsetX.coerceIn(-maxPanX, maxPanX) else 0f
                        val clampedY = if (maxPanY > 0f) offsetY.coerceIn(-maxPanY, maxPanY) else 0f

                        Image(
                            bitmap = img,
                            contentDescription = label,
                            modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = clampedX,
                                    translationY = clampedY
                                ),
                            contentScale = ContentScale.Fit
                        )
                    }

                    // Pinch-to-zoom + pan. Declared before the close button so
                    // the button (a later sibling) sits above it in the z-order
                    // and receives taps meant for it.
                    Box(
                        modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val newScale = (scale * zoom).coerceIn(1f, 8f)
                                    scale = newScale
                                    if (newScale > 1f) {
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    } else {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                }
                            }
                    )

                    // Close button — always reachable regardless of zoom state.
                    Box(
                        modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .pointerInput(Unit) {
                                detectTapGestures { showMaximized = false }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }

    /**
     * Reads a `multipart/x-mixed-replace` MJPEG body and hands each complete
     * JPEG frame to [onFrame] (on the main thread). Frames are located by
     * scanning for JPEG SOI (`FF D8`) / EOI (`FF D9`) markers rather than
     * parsing the multipart boundary headers — simpler and boundary-agnostic.
     * Decoding happens on the IO dispatcher; only the tiny state write hops to
     * Main. Returns when the stream ends or the coroutine is cancelled.
     */
    private suspend fun readMjpeg(resp: Response, targetPx: Int, onFrame: (ImageBitmap) -> Unit) {
        withContext(Dispatchers.IO) {
            val input = BufferedInputStream(resp.body!!.byteStream(), 64 * 1024)
            val jpeg = ByteArrayOutputStream(96 * 1024)
            val chunk = ByteArray(16 * 1024)
            var prev = -1
            var inFrame = false

            while (coroutineContext.isActive) {
                val n = input.read(chunk)
                if (n < 0) break
                var i = 0
                while (i < n) {
                    val b = chunk[i].toInt() and 0xFF
                    if (inFrame) {
                        jpeg.write(b)
                        if (prev == 0xFF && b == 0xD9) {
                            val bytes = jpeg.toByteArray()
                            jpeg.reset()
                            inFrame = false
                            val bmp = decodeByteArraySampled(bytes, targetPx)
                            if (bmp != null) withContext(Dispatchers.Main) { onFrame(bmp) }
                        }
                    } else if (prev == 0xFF && b == 0xD8) {
                        inFrame = true
                        jpeg.reset()
                        jpeg.write(0xFF)
                        jpeg.write(0xD8)
                    }
                    prev = b
                    i++
                }
                if (jpeg.size() > MAX_JPEG_BYTES) {
                    jpeg.reset()
                    inFrame = false
                    prev = -1
                }
            }
        }
    }
}
