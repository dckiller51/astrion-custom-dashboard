package com.custom.astrion.web

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * NanoHTTPD without its reverse DNS lookup (1.3.8).
 *
 * NanoHTTPD 2.3.1 asks the DNS for the *name* of every client that isn't the remote itself
 * (`InetAddress.getHostName()` when a connection opens), before reading the request. When the
 * remote has no internet (blocked in the router, or an outage) or a DNS that never answers for
 * local addresses, that question waits for the DNS timeout on every connection: Home Assistant's
 * integration gives up after 5 s ("Broken pipe" in the log), the web page crawls or never loads,
 * while the remote's own check (on 127.0.0.1, no lookup) keeps saying all is well.
 *
 * Nothing here uses that name, so each connection gets its address with the name already set to
 * the IP: no lookup at all. Otherwise the same as NanoHTTPD's own `ClientHandler`.
 */
abstract class LanHttpServer(port: Int) : NanoHTTPD(port) {
    override fun createClientHandler(finalAccept: Socket, inputStream: InputStream): ClientHandler =
        NoLookupClientHandler(inputStream, finalAccept)

    private inner class NoLookupClientHandler(private val input: InputStream, private val socket: Socket) : ClientHandler(input, socket) {
        override fun run() {
            var output: OutputStream? = null
            try {
                output = socket.getOutputStream()
                val session = HTTPSession(tempFileManagerFactory.create(), input, output, withoutLookup(socket.inetAddress))
                while (!socket.isClosed) session.execute()
            } catch (e: SocketException) {
                // The client went away, or the server is stopping: nothing to report.
                if (e.message != SHUTDOWN) Log.d(TAG, "client closed: ${e.message}")
            } catch (_: SocketTimeoutException) {
                // An idle keep-alive connection timing out: normal.
            } catch (e: Exception) {
                Log.w(TAG, "communication with the client broken", e)
            } finally {
                runCatching { output?.close() }
                runCatching { input.close() }
                runCatching { socket.close() }
                asyncRunner.closed(this)
            }
        }
    }

    private companion object {
        const val TAG = "LanHttpServer"

        /** NanoHTTPD's own message when it closes its sockets on stop. */
        const val SHUTDOWN = "NanoHttpd Shutdown"

        /** The same address, its name already set to the IP, so asking for its name never queries the DNS. */
        fun withoutLookup(address: InetAddress): InetAddress = InetAddress.getByAddress(address.hostAddress, address.address)
    }
}
