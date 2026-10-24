package com.custom.astrion.tvhelper

import android.util.Log
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A tiny HTTP server for [HelperRoutes]: one short connection per request, a few at a time. */
class HelperServer(private val routes: HelperRoutes, private val port: Int = HelperRoutes.PORT) {
    private var server: ServerSocket? = null
    private val pool =
        ThreadPoolExecutor(0, MAX_CLIENTS, IDLE_S, TimeUnit.SECONDS, SynchronousQueue()) { r ->
            Thread(r, "helper-http").apply { isDaemon = true }
        }

    fun start() {
        if (server != null) return
        val s = ServerSocket(port)
        server = s
        Thread({ acceptLoop(s) }, "helper-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "listening on port $port")
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client =
                try {
                    s.accept()
                } catch (e: IOException) {
                    Log.w(TAG, "accept failed: ${e.message}")
                    continue
                }
            try {
                pool.execute { serve(client) }
            } catch (e: RejectedExecutionException) {
                Log.w(TAG, "too many requests, one dropped: ${e.message}")
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = READ_TIMEOUT_MS
                val response =
                    try {
                        routes.handle(HttpRequest.read(socket.getInputStream()))
                    } catch (e: IOException) {
                        HttpResponse.error(BAD_REQUEST, e.message ?: "bad request")
                    }
                response.write(socket.getOutputStream())
            } catch (e: IOException) {
                Log.d(TAG, "client gone: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "AstrionHelper"
        private const val MAX_CLIENTS = 8
        private const val IDLE_S = 30L
        private const val READ_TIMEOUT_MS = 10_000
        private const val BAD_REQUEST = 400
    }
}
