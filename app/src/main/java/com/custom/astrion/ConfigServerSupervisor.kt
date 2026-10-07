package com.custom.astrion

import android.os.SystemClock
import android.util.Log
import com.custom.astrion.web.ConfigServer
import fi.iki.elonen.NanoHTTPD
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class ConfigServerSupervisor(
    private val scope: CoroutineScope,
    private val serverProvider: () -> ConfigServer,
    private val isEnabled: () -> Boolean
) {
    private companion object {
        const val SERVER_TAG = "ConfigServer"
        const val WATCHDOG_TAG = "ConfigServerWatchdog"
        const val ENDPOINT = "http://127.0.0.1:8080/current-page"
        const val WATCHDOG_INTERVAL_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 1500
        const val READ_TIMEOUT_MS = 2500
        const val FAILURE_THRESHOLD = 3
        const val RESTART_COOLDOWN_MS = 120_000L
        const val RESTART_DELAY_MS = 500L
    }

    private var watchdogJob: Job? = null
    private var failures = 0
    private var lastRestartMs = 0L

    fun start() {
        startServer()
        startWatchdog()
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        failures = 0
        runCatching { serverProvider().stop() }
            .onFailure { Log.w(SERVER_TAG, "stop failed", it) }
    }

    private fun startServer() {
        runCatching { serverProvider().start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            .onSuccess { Log.i(SERVER_TAG, "started on :8080") }
            .onFailure {
                Log.e(SERVER_TAG, "failed to start on :8080", it)
                scope.launch {
                    delay(RESTART_DELAY_MS)
                    if (isActive && isEnabled()) {
                        runCatching { serverProvider().start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                            .onSuccess { Log.i(SERVER_TAG, "retry: started on :8080") }
                            .onFailure { error -> Log.e(SERVER_TAG, "retry failed on :8080", error) }
                    }
                }
            }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        failures = 0
        watchdogJob =
            scope.launch(Dispatchers.IO) {
                delay(WATCHDOG_INTERVAL_MS)
                while (isActive && isEnabled()) {
                    if (probe()) {
                        if (failures > 0) Log.i(WATCHDOG_TAG, "recovered after $failures failed probe(s)")
                        failures = 0
                    } else {
                        handleFailure()
                    }
                    delay(WATCHDOG_INTERVAL_MS)
                }
            }
    }

    private fun probe(): Boolean {
        return runCatching {
            var connection: HttpURLConnection? = null
            try {
                connection =
                    (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        useCaches = false
                    }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    false
                } else {
                    val payload = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    payload.has("index") && payload.has("name")
                }
            } finally {
                connection?.disconnect()
            }
        }.onFailure {
            Log.w(WATCHDOG_TAG, "loopback probe failed: " + it.message)
        }.getOrDefault(false)
    }

    private suspend fun handleFailure() {
        failures++
        Log.w(WATCHDOG_TAG, "probe failed $failures/$FAILURE_THRESHOLD")
        if (failures < FAILURE_THRESHOLD) return

        val now = SystemClock.elapsedRealtime()
        if (lastRestartMs != 0L && now - lastRestartMs < RESTART_COOLDOWN_MS) {
            Log.w(WATCHDOG_TAG, "restart suppressed by cooldown")
            failures = 0
            return
        }

        lastRestartMs = now
        failures = 0
        Log.e(WATCHDOG_TAG, "restarting stalled ConfigServer after repeated loopback failures")
        runCatching { serverProvider().stop() }
            .onFailure { Log.w(WATCHDOG_TAG, "stop before restart failed", it) }
        delay(RESTART_DELAY_MS)
        if (isEnabled()) startServer()
    }
}
