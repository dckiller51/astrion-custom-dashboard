package com.custom.astrion

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The app's screen as a PNG (1.5.4), for the web page's screenshots: its window copied with
 * [PixelCopy] (no permission needed; the system bars and other apps aren't in it). A pop-up is a
 * window of its own: each one open ([TrackForScreenshots], 1.5.6) is copied too and laid over the
 * page, dimmed as on the screen.
 */
object ScreenCapture {
    private const val TIMEOUT_MS = 3_000L
    private const val OPAQUE = 255

    private val dialogs = CopyOnWriteArrayList<Window>()

    /** Where PixelCopy answers: not the main thread, which posts the requests while we wait. */
    private val answers: Handler by lazy { Handler(HandlerThread("screen-capture").apply { start() }.looper) }

    private class Layer(val window: Window, val width: Int, val height: Int, val x: Int, val y: Int, val dim: Float)

    /** Puts the pop-up this is called in into the screenshots while it's open. */
    @Composable
    fun TrackForScreenshots() {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.let { dialogs += it }
            onDispose { window?.let { dialogs -= it } }
        }
    }

    /** Blocks until done: call it off the main thread. Null when the window can't be copied. */
    fun png(activity: Activity): ByteArray? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val main = Handler(Looper.getMainLooper())
        val layers = onMain(main) { layersOf(activity) }.orEmpty()
        val base = layers.firstOrNull()?.let { copy(main, it) } ?: return null
        val canvas = Canvas(base)
        layers.drop(1).forEach { layer ->
            if (layer.dim > 0f) canvas.drawColor(Color.argb((layer.dim * OPAQUE).toInt(), 0, 0, 0))
            copy(main, layer)?.let {
                canvas.drawBitmap(it, layer.x.toFloat(), layer.y.toFloat(), null)
                it.recycle()
            }
        }
        val png = ByteArrayOutputStream().also { base.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        base.recycle()
        return png
    }

    /** The activity's window, then the pop-ups shown over it, placed relative to it. */
    private fun layersOf(activity: Activity): List<Layer>? {
        val window = activity.window ?: return null
        val root = window.decorView
        if (root.width <= 0 || root.height <= 0) return null
        val origin = IntArray(2).also { root.getLocationOnScreen(it) }
        val pops =
            dialogs.mapNotNull { w ->
                val v = w.peekDecorView()?.takeIf { it.isShown && it.width > 0 && it.height > 0 } ?: return@mapNotNull null
                val at = IntArray(2).also { v.getLocationOnScreen(it) }
                val dims = w.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
                Layer(w, v.width, v.height, at[0] - origin[0], at[1] - origin[1], if (dims) w.attributes.dimAmount else 0f)
            }
        return listOf(Layer(window, root.width, root.height, 0, 0, 0f)) + pops
    }

    private fun copy(main: Handler, layer: Layer): Bitmap? {
        val bitmap = Bitmap.createBitmap(layer.width, layer.height, Bitmap.Config.ARGB_8888)
        val ok = AtomicReference(false)
        val done = CountDownLatch(1)
        main.post {
            runCatching {
                PixelCopy.request(layer.window, bitmap, { code ->
                    ok.set(code == PixelCopy.SUCCESS)
                    done.countDown()
                }, answers)
            }.onFailure { done.countDown() }
        }
        val answered = done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (ok.get()) return bitmap
        // Not recycled while a late copy may still write into it.
        if (answered) bitmap.recycle()
        return null
    }

    private fun <T> onMain(main: Handler, block: () -> T): T? {
        val result = AtomicReference<T?>()
        val done = CountDownLatch(1)
        main.post {
            result.set(runCatching(block).getOrNull())
            done.countDown()
        }
        done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result.get()
    }
}
