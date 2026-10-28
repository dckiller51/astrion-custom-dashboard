package com.custom.astrion.web

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.core.content.edit
import com.custom.astrion.BuildConfig
import com.custom.astrion.config.BackupArchive
import com.custom.astrion.config.BackupException
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject

/**
 * The web page's full backup (1.3.9), kept out of [ConfigServer]:
 *
 *  GET  /backup    the whole remote as one zip (see [BackupArchive]): connections, pairings,
 *                  name and settings, dashboard.json, icons, IR database
 *  POST /restore   puts such a zip back (field `file`), then [onRestored] restarts the app
 *
 * After a reflash or on a new remote, restoring brings everything back as it was.
 */
class BackupRoutes(private val context: Context, private val onRestored: () -> Unit) {
    private val root: File
        get() = File(Environment.getExternalStorageDirectory(), "astrion")

    /** The response for a backup route, or null when [uri] isn't one. */
    fun serve(uri: String, session: IHTTPSession): Response? = when (uri) {
        "/backup" -> if (session.method == Method.GET) backup() else methodNotAllowed()
        "/restore" -> if (session.method == Method.POST) restore(session) else methodNotAllowed()
        else -> null
    }

    private fun backup(): Response {
        val zip = File(context.cacheDir, "astrion-backup.zip")
        return try {
            val manifest =
                buildJsonObject {
                    put("format", BackupArchive.FORMAT)
                    put("version", BackupArchive.VERSION)
                    put("app", BuildConfig.VERSION_NAME)
                    put("created", stamp("yyyy-MM-dd'T'HH:mm:ss"))
                }
            val prefs =
                BackupArchive.PREFS_FILES.associateWith { name ->
                    BackupArchive.prefsToJson(context.getSharedPreferences(name, Context.MODE_PRIVATE).all)
                }
            zip.outputStream().use { BackupArchive.write(it, manifest, prefs, root) }
            val response = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/zip", zip.inputStream(), zip.length())
            response.addHeader("Content-Disposition", "attachment; filename=\"astrion-backup-${stamp("yyyyMMdd-HHmm")}.zip\"")
            response
        } catch (e: Exception) {
            Log.w(TAG, "backup failed", e)
            error(Response.Status.INTERNAL_ERROR, "backup failed: ${e.message}")
        }
    }

    private fun restore(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val upload = files["file"]?.let(::File) ?: return error(Response.Status.BAD_REQUEST, "missing file")
        return try {
            val contents = BackupArchive.open(upload)
            contents.prefs.forEach { (name, values) ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) {
                    clear()
                    values.forEach { (key, value) -> putValue(key, value) }
                }
            }
            val restored = BackupArchive.restoreFiles(upload, root)
            Log.i(TAG, "backup restored: ${contents.prefs.keys.joinToString()} and $restored file(s); restarting")
            onRestored()
            json(Response.Status.OK, JSONObject().put("ok", true).put("files", restored))
        } catch (e: BackupException) {
            error(Response.Status.BAD_REQUEST, e.message ?: "not a backup")
        } catch (e: Exception) {
            Log.w(TAG, "restore failed", e)
            error(Response.Status.INTERNAL_ERROR, "restore failed: ${e.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun android.content.SharedPreferences.Editor.putValue(key: String, value: Any) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value as Set<String>)
        }
    }

    private fun stamp(pattern: String) = SimpleDateFormat(pattern, Locale.ROOT).format(Date())

    private fun json(status: Response.Status, body: JSONObject): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", body.toString())

    private fun error(status: Response.Status, message: String) = json(status, JSONObject().put("error", message))

    private fun methodNotAllowed() = error(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")

    private companion object {
        const val TAG = "Backup"
    }
}
