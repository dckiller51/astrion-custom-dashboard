package com.custom.astrion

import android.os.Build
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * This app's own recent log lines (logcat, filtered to its process), with a one-line header naming
 * the version and the device — what the config server's `GET /logs` downloads, so a tester can
 * send a log without adb. Only this process's lines: an app may always read those, and nothing
 * from other apps leaks into the file.
 */
object AppLogs {
    private const val MAX_LINES = 5000

    fun dump(): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val header =
            "Astrion ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — " +
                "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} — dumped $now\n\n"
        val body =
            runCatching {
                val process =
                    ProcessBuilder("logcat", "-d", "-v", "time", "-t", MAX_LINES.toString(), "--pid=${Process.myPid()}")
                        .redirectErrorStream(true)
                        .start()
                process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
            }.getOrElse { "logcat failed: $it" }
        return header + body
    }

    /** "astrion-log-20261012-0934.txt" */
    fun fileName(): String = "astrion-log-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + ".txt"
}
