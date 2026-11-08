package com.custom.astrion

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The app's screen as a PNG (1.5.4), for the web page's screenshots: its window copied with
 * [PixelCopy] (no permission needed, the app's own window only: a pop-up drawn in a window of
 * its own, the system bars and other apps aren't in it).
 */
object ScreenCapture {
    private const val TIMEOUT_MS = 3_000L

    /** Blocks until done: call it off the main thread. Null when the window can't be copied. */
    fun png(activity: Activity): ByteArray? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val main = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        val result = AtomicReference<ByteArray?>()
        main.post {
            val window = activity.window
            val view = window?.decorView
            if (view == null || view.width <= 0 || view.height <= 0) {
                done.countDown()
                return@post
            }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val copied = { code: Int ->
                // Encoded off the main thread.
                Thread {
                    if (code == PixelCopy.SUCCESS) {
                        result.set(ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray())
                    }
                    bitmap.recycle()
                    done.countDown()
                }.start()
            }
            runCatching { PixelCopy.request(window, bitmap, { copied(it) }, main) }.onFailure {
                bitmap.recycle()
                done.countDown()
            }
        }
        done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result.get()
    }
}
