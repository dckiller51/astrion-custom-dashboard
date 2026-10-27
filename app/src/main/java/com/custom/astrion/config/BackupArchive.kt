package com.custom.astrion.config

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A backup that can't be restored: not one, a newer format, or unreadable. */
class BackupException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The remote's full backup (1.3.9), one zip:
 *  - `manifest.json`: format, version, app version, date;
 *  - `prefs/<name>.json`: the app's settings files, every value with its type — the Home
 *    Assistant connection, Harmony hubs, IR extenders, Apple TV and Android TV pairings (and the
 *    remote's own Android TV certificate), the remote's name, every Settings switch, the
 *    Activities' device states;
 *  - `files/…`: the `astrion/` folder — dashboard.json, icons, IR database, floor plan — but
 *    not its logs.
 *
 * Restoring puts all of it back: after a reflash, the remote is as it was, pairings included.
 * It holds the Home Assistant token and pairing keys: a private file.
 */
object BackupArchive {
    const val FORMAT = "astrion-backup"
    const val VERSION = 1

    /** The app's settings files ([android.content.SharedPreferences] names) a backup carries. */
    val PREFS_FILES = listOf("astrion_settings", "astrion_device_states")

    /** Folders of `astrion/` a backup leaves out. */
    private val SKIPPED_DIRS = setOf("logs")

    private const val MANIFEST = "manifest.json"
    private const val PREFS_DIR = "prefs/"
    private const val FILES_DIR = "files/"
    private val json = Json { prettyPrint = true }

    /** Settings values → `{key: {"t": type, "v": value}}`, the type kept so they come back as they were. */
    fun prefsToJson(values: Map<String, *>): JsonObject = buildJsonObject {
        values.toSortedMap().forEach { (key, value) ->
            val typed: Pair<String, JsonElement>? =
                when (value) {
                    is String -> "s" to JsonPrimitive(value)
                    is Boolean -> "b" to JsonPrimitive(value)
                    is Int -> "i" to JsonPrimitive(value)
                    is Long -> "l" to JsonPrimitive(value)
                    is Float -> "f" to JsonPrimitive(value)
                    is Set<*> -> "set" to JsonArray(value.filterIsInstance<String>().sorted().map(::JsonPrimitive))
                    else -> null
                }
            typed?.let { (t, v) ->
                put(
                    key,
                    buildJsonObject {
                        put("t", t)
                        put("v", v)
                    }
                )
            }
        }
    }

    /** The other way: typed values, ready to put back; an entry it can't read is left out. */
    fun prefsFromJson(o: JsonObject): Map<String, Any> = o.mapNotNull { (key, el) ->
        val entry = el as? JsonObject ?: return@mapNotNull null
        val v = entry["v"] ?: return@mapNotNull null
        val p = v as? JsonPrimitive
        val value: Any? =
            when ((entry["t"] as? JsonPrimitive)?.contentOrNull) {
                "s" -> p?.contentOrNull
                "b" -> p?.booleanOrNull
                "i" -> p?.intOrNull
                "l" -> p?.longOrNull
                "f" -> p?.floatOrNull
                "set" -> (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
                else -> null
            }
        value?.let { key to it }
    }.toMap()

    /** Writes the whole backup: [prefs] by settings file name, and every file of [root] but [SKIPPED_DIRS]. */
    fun write(out: OutputStream, manifest: JsonObject, prefs: Map<String, JsonObject>, root: File) {
        ZipOutputStream(out).use { zip ->
            zip.putText(MANIFEST, json.encodeToString(JsonObject.serializer(), manifest))
            prefs.forEach { (name, values) -> zip.putText("$PREFS_DIR$name.json", json.encodeToString(JsonObject.serializer(), values)) }
            root.walkTopDown()
                .onEnter { dir -> dir == root || dir.relativeTo(root).invariantSeparatorsPath.substringBefore('/') !in SKIPPED_DIRS }
                .filter { it.isFile }
                .forEach { file ->
                    zip.putNextEntry(ZipEntry(FILES_DIR + file.relativeTo(root).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    /** What a backup holds, read before anything is changed. */
    class Contents(val manifest: JsonObject, val prefs: Map<String, Map<String, Any>>, val fileCount: Int)

    /** Reads and checks [zip]: a backup of this format and a version this app knows; throws otherwise. */
    fun open(zip: File): Contents = try {
        ZipFile(zip).use(::readContents)
    } catch (e: BackupException) {
        throw e
    } catch (e: Exception) {
        throw BackupException("unreadable backup: ${e.message}", e)
    }

    private fun readContents(z: ZipFile): Contents {
        val manifest = z.readJson(MANIFEST)?.takeIf { (it["format"] as? JsonPrimitive)?.contentOrNull == FORMAT }
            ?: throw BackupException("not an Astrion backup")
        val version = (manifest["version"] as? JsonPrimitive)?.intOrNull ?: 0
        if (version > VERSION) throw BackupException("made by a newer Astrion (backup version $version)")
        val prefs = PREFS_FILES.mapNotNull { name -> z.readJson("$PREFS_DIR$name.json")?.let { name to prefsFromJson(it) } }.toMap()
        return Contents(manifest, prefs, z.fileEntries().count())
    }

    /** Puts [zip]'s files back under [root] (overwriting); returns how many. Names that would leave [root] are skipped. */
    fun restoreFiles(zip: File, root: File): Int = ZipFile(zip).use { z -> z.fileEntries().count { z.extract(it, root) } }

    private fun ZipFile.fileEntries(): Sequence<ZipEntry> =
        entries().asSequence().filter { !it.isDirectory && it.name.startsWith(FILES_DIR) }

    /** Writes one `files/…` entry under [root]; false when its name would leave [root]. */
    private fun ZipFile.extract(e: ZipEntry, root: File): Boolean {
        val target = safeTarget(root, e.name.removePrefix(FILES_DIR)) ?: return false
        target.parentFile?.mkdirs()
        getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
        return true
    }

    private fun ZipFile.readJson(name: String): JsonObject? {
        val entry = getEntry(name) ?: return null
        return getInputStream(entry).bufferedReader().use { Json.parseToJsonElement(it.readText()) } as? JsonObject
    }

    /** [root]/[relative], or null when it would end up outside [root] (`../`, an absolute path). */
    fun safeTarget(root: File, relative: String): File? {
        if (relative.isBlank() || relative.startsWith("/") || relative.startsWith("\\")) return null
        val base = root.canonicalFile
        val target = File(base, relative).canonicalFile
        return target.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
