package com.custom.astrion.tvhelper

import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * The wake channel (1.1.2, port [PORT]): a connection the remote opens while the TV is awake and
 * keeps through its sleep. Some boxes (Xiaomi Mi Box S) suspend so hard that Wake-on-LAN only
 * brings them up for about a second: too short to accept a new connection and answer a request,
 * but enough for a line already on its way on an open one (TCP resends it) to be read at once.
 *
 * Plain lines: the remote sends the helper's code, the helper answers `OK` (or `DENIED` and
 * closes); then `WAKE` → `ON` / `OFF` (whether the screen is on afterwards), `PING` → `PONG`.
 */
class WakeChannel(private val code: String, private val wake: () -> Boolean, private val port: Int = PORT) {
    private val clients = AtomicInteger()

    fun start() {
        val server = ServerSocket(port)
        Thread({ acceptLoop(server) }, "helper-wake-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "wake channel on port $port")
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = accept(server) ?: continue
            if (clients.incrementAndGet() > MAX_CLIENTS) {
                clients.decrementAndGet()
                runCatching { client.close() }
            } else {
                Thread({ serve(client) }, "helper-wake").apply { isDaemon = true }.start()
            }
        }
    }

    private fun accept(server: ServerSocket): Socket? = try {
        server.accept()
    } catch (e: IOException) {
        Log.w(TAG, "wake channel accept failed: ${e.message}")
        null
    }

    private fun serve(client: Socket) {
        try {
            client.use { s ->
                s.tcpNoDelay = true
                converse(s.getInputStream().bufferedReader(), s.getOutputStream(), code, wake) { Log.i(TAG, it) }
            }
        } catch (e: IOException) {
            Log.d(TAG, "wake channel closed: ${e.message}")
        } finally {
            clients.decrementAndGet()
        }
    }

    companion object {
        const val PORT = 6471
        private const val TAG = "AstrionHelper"
        private const val MAX_CLIENTS = 4

        /** One remote's channel, until it closes; the protocol in the class's description. [log]: Android's log, none in tests. */
        fun converse(input: BufferedReader, output: OutputStream, code: String, wake: () -> Boolean, log: (String) -> Unit = {}) {
            fun reply(line: String) {
                output.write("$line\n".toByteArray())
                output.flush()
            }
            if (input.readLine()?.trim() != code) {
                reply("DENIED")
                return
            }
            reply("OK")
            while (true) {
                when (input.readLine()?.trim() ?: return) {
                    "WAKE" -> {
                        val on = wake()
                        log("wake channel: screen ${if (on) "on" else "still off"}")
                        reply(if (on) "ON" else "OFF")
                    }
                    "PING" -> reply("PONG")
                    else -> reply("?")
                }
            }
        }
    }
}
