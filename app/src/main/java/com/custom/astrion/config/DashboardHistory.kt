package com.custom.astrion.config

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/**
 * Version history of dashboard.json (1.3.0): every save from the web page / builder is kept in
 * `/sdcard/astrion/history/<id>.json`, so a change that turns out badly can be undone by
 * restoring an earlier version — like the timeline of a CAD tool.
 *
 * - [recordSave] is called with the content about to be written. When the file on disk differs
 *   from the newest version kept (a first save after updating the app, or a change made outside
 *   the web page — a setting from the remote, a backup copied by hand), that content is kept first,
 *   so nothing is ever lost.
 * - [restore] writes a kept version back (recorded as a new version itself, so a restore can be
 *   undone too).
 * - Versions can carry a note and be pinned; the oldest unpinned ones beyond [MAX_KEPT] are deleted.
 *
 * `index.json` next to the versions holds the notes, pins and reasons; the version files are plain
 * dashboard.json copies, so they can also be downloaded and re-uploaded as backups.
 */
object DashboardHistory {
    private const val TAG = "DashboardHistory"
    const val MAX_KEPT = 30
    private val ID_PATTERN = Regex("^[0-9]{8}-[0-9]{6}(-[0-9]+)?$")

    const val REASON_SAVE = "save"
    const val REASON_RESTORE = "restore"
    const val REASON_OUTSIDE = "outside"

    private val dir: File
        get() = File(DashboardLoader.configFile.parentFile, "history")

    private val indexFile: File
        get() = File(dir, "index.json")

    fun isValidId(id: String?): Boolean = id != null && ID_PATTERN.matches(id)

    /** Keeps [newContent] as the newest version (after keeping what's on disk when it isn't kept yet). */
    @Synchronized
    fun recordSave(newContent: String, note: String?) {
        runCatching {
            keepCurrentIfUnrecorded()
            add(newContent, REASON_SAVE, note, restoredFrom = null)
        }.onFailure { Log.w(TAG, "Couldn't record a version", it) }
    }

    /** Writes version [id] back to dashboard.json. False when it doesn't exist. */
    @Synchronized
    fun restore(id: String): Boolean {
        val content = read(id) ?: return false
        runCatching {
            keepCurrentIfUnrecorded()
            add(content, REASON_RESTORE, note = null, restoredFrom = id)
        }.onFailure { Log.w(TAG, "Couldn't record the restore", it) }
        DashboardLoader.configFile.writeText(content)
        return true
    }

    fun read(id: String): String? {
        if (!isValidId(id)) return null
        val f = File(dir, "$id.json")
        return if (f.isFile) f.readText() else null
    }

    /** Newest first: id, time (ms), note, pinned, reason, restoredFrom, size, pages, cards. */
    @Synchronized
    fun list(): JSONArray {
        val index = loadIndex()
        val out = JSONArray()
        entries(index).sortedByDescending { it.optString("id") }.forEach { e ->
            val id = e.optString("id")
            val f = File(dir, "$id.json")
            if (!f.isFile) return@forEach
            val stats = stats(f)
            out.put(
                JSONObject()
                    .put("id", id)
                    .put("time", e.optLong("time", f.lastModified()))
                    .put("note", e.optString("note"))
                    .put("pinned", e.optBoolean("pinned"))
                    .put("reason", e.optString("reason", REASON_SAVE))
                    .put("restoredFrom", e.optString("restoredFrom"))
                    .put("size", f.length())
                    .put("pages", stats.first)
                    .put("cards", stats.second)
            )
        }
        return out
    }

    @Synchronized
    fun setPinned(id: String, pinned: Boolean): Boolean = update(id) { it.put("pinned", pinned) }

    @Synchronized
    fun setNote(id: String, note: String): Boolean = update(id) { it.put("note", note.take(MAX_NOTE)) }

    @Synchronized
    fun delete(id: String): Boolean {
        if (!isValidId(id)) return false
        val index = loadIndex()
        val list = entries(index).filterNot { it.optString("id") == id }
        File(dir, "$id.json").delete()
        saveIndex(list)
        return true
    }

    // ---- internals ------------------------------------------------------------------------------

    private const val MAX_NOTE = 200

    private fun keepCurrentIfUnrecorded() {
        val current = DashboardLoader.configFile
        if (!current.isFile) return
        val text = current.readText()
        if (text.isBlank()) return
        val newest = entries(loadIndex()).maxByOrNull { it.optString("id") }?.optString("id")
        val newestText = newest?.let { read(it) }
        if (newestText == null || !sameJson(newestText, text)) add(text, REASON_OUTSIDE, note = null, restoredFrom = null)
    }

    private fun add(content: String, reason: String, note: String?, restoredFrom: String?) {
        dir.mkdirs()
        val now = System.currentTimeMillis()
        var id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        var n = 1
        while (File(dir, "$id.json").exists()) id = baseId(id) + "-" + n++
        File(dir, "$id.json").writeText(content)
        val list = entries(loadIndex()).toMutableList()
        list.add(
            JSONObject().put("id", id).put("time", now).put("reason", reason).put("pinned", false).apply {
                if (!note.isNullOrBlank()) put("note", note.trim().take(MAX_NOTE))
                if (restoredFrom != null) put("restoredFrom", restoredFrom)
            }
        )
        saveIndex(prune(list))
    }

    private fun baseId(id: String): String = id.split("-").take(2).joinToString("-")

    /** Drops the oldest unpinned versions beyond [MAX_KEPT] (and their files). */
    private fun prune(list: List<JSONObject>): List<JSONObject> {
        val unpinned = list.filterNot { it.optBoolean("pinned") }.sortedByDescending { it.optString("id") }
        val drop = unpinned.drop(MAX_KEPT).map { it.optString("id") }.toSet()
        drop.forEach { File(dir, "$it.json").delete() }
        return list.filterNot { it.optString("id") in drop }
    }

    private fun update(id: String, change: (JSONObject) -> Unit): Boolean {
        if (!isValidId(id)) return false
        val list = entries(loadIndex())
        val e = list.firstOrNull { it.optString("id") == id } ?: return false
        change(e)
        saveIndex(list)
        return true
    }

    private fun loadIndex(): JSONObject =
        runCatching { if (indexFile.isFile) JSONObject(indexFile.readText()) else null }.getOrNull() ?: JSONObject()

    private fun entries(index: JSONObject): List<JSONObject> {
        val arr = index.optJSONArray("versions") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { isValidId(it.optString("id")) }
    }

    private fun saveIndex(list: List<JSONObject>) {
        dir.mkdirs()
        indexFile.writeText(JSONObject().put("versions", JSONArray(list)).toString(2))
    }

    /** Pages and cards in a version, for the list (0/0 when the file isn't valid JSON). */
    private fun stats(f: File): Pair<Int, Int> = runCatching {
        val pages = JSONObject(f.readText()).optJSONArray("pages") ?: JSONArray()
        var cards = 0
        for (i in 0 until pages.length()) cards += pages.optJSONObject(i)?.optJSONArray("cards")?.length() ?: 0
        pages.length() to cards
    }.getOrDefault(0 to 0)

    /** Same content once parsed — the builder and the app don't format the file the same way. */
    private fun sameJson(a: String, b: String): Boolean =
        runCatching { Json.parseToJsonElement(a) == Json.parseToJsonElement(b) }.getOrDefault(a == b)
}
