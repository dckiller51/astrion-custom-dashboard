package com.custom.astrion

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * This app's own recent log lines (logcat, filtered to its process), with a header naming the
 * version and the device — what the config server's `GET /logs` downloads, so a tester can send a
 * log without adb. Only this process's lines: an app may always read those, and nothing from
 * other apps leaks into the file.
 *
 * 1.3.8: the header also gives the network as the remote sees it (Wi-Fi signal and speed, band,
 * IP, gateway, DNS, internet reachable or not), and [save] writes the same text to a file from the
 * remote's own settings — for when the web page is the thing that doesn't answer.
 */
object AppLogs {
    private const val MAX_LINES = 5000

    /** Saved logs kept in [dir]: the oldest go beyond this. */
    private const val KEEP_FILES = 5

    /** `/sdcard/astrion/logs`, next to dashboard.json: reachable over USB or with a file manager. */
    val dir: File
        get() = File(Environment.getExternalStorageDirectory(), "astrion/logs")

    fun dump(context: Context? = null): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val header =
            "Astrion ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — " +
                "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} — dumped $now\n"
        val network = context?.let { runCatching { networkSummary(it) }.getOrElse { e -> "Network: unknown ($e)" } }
        val body =
            runCatching {
                val process =
                    ProcessBuilder("logcat", "-d", "-v", "time", "-t", MAX_LINES.toString(), "--pid=${Process.myPid()}")
                        .redirectErrorStream(true)
                        .start()
                process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
            }.getOrElse { "logcat failed: $it" }
        return header + (network?.let { "$it\n" } ?: "") + "\n" + body
    }

    /** Writes [dump] to `astrion/logs/astrion-log-….txt` and keeps the last few; the file, or null when storage refused. */
    fun save(context: Context): File? = runCatching {
        val folder = dir.apply { mkdirs() }
        val file = File(folder, fileName())
        file.writeText(dump(context))
        folder.listFiles { f -> f.name.startsWith("astrion-log-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_FILES)
            ?.forEach { it.delete() }
        file
    }.getOrNull()

    /** "astrion-log-20261012-0934.txt" */
    fun fileName(): String = "astrion-log-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + ".txt"

    /**
     * Two lines: Wi-Fi (signal, speed, band) and the network (IP, gateway, DNS, internet).
     * "internet: no" is what Android shows as "connected, no internet".
     */
    @Suppress("DEPRECATION") // WifiManager.connectionInfo: the only way on Android 8, still answers on newer ones.
    private fun networkSummary(context: Context): String {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val info = wifi?.connectionInfo
        val wifiLine =
            if (info == null || info.networkId == -1) {
                "Wi-Fi: not connected"
            } else {
                val band = if (info.frequency >= FIVE_GHZ_FROM) "5 GHz" else "2.4 GHz"
                "Wi-Fi: signal ${info.rssi} dBm, ${info.linkSpeed} Mbps, $band (${info.frequency} MHz)"
            }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork
        val links = active?.let { cm.getLinkProperties(it) }
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val ip = links?.linkAddresses?.map { it.address.hostAddress }?.filter { '.' in it.orEmpty() }?.joinToString().orEmpty()
        val gateway = links?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress ?: "?"
        val dns = links?.dnsServers?.joinToString { it.hostAddress.orEmpty() }?.ifBlank { null } ?: "none"
        val internet = if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) "yes" else "no"
        val privateDns =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && links?.isPrivateDnsActive == true) {
                ", private DNS ${links?.privateDnsServerName ?: "automatic"}"
            } else {
                ""
            }
        return "$wifiLine\nNetwork: IP ${ip.ifBlank { "?" }}, gateway $gateway, DNS $dns$privateDns, internet $internet"
    }

    private const val FIVE_GHZ_FROM = 4900
}
