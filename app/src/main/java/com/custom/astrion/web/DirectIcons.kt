package com.custom.astrion.web

import com.custom.astrion.appletv.AppleTvRegistry
import com.custom.astrion.lgtv.LgTvRegistry
import com.custom.astrion.samsung.SamsungRegistry
import com.custom.astrion.sky.SkyRegistry
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * The web page's icons in folders (1.5.2): `/sdcard/astrion/icons/<file>` as before, and
 * `/sdcard/astrion/icons/<folder>/<file>` — one folder per direct device whose app icons were
 * imported. Paths are at most two parts, each reduced to safe characters, and must stay inside
 * the icons folder.
 */
object IconFolders {
    private val UNSAFE = Regex("[^A-Za-z0-9._ +-]")

    /** A file or folder name the icons folder accepts ("Prime Video.png", "LG OLED 65B8"). */
    fun safe(name: String): String = name.replace(UNSAFE, "_").trim().trim('.').take(MAX_NAME)

    /** [rel] ("Netflix.png" or "LG OLED 65B8/Netflix.png") inside [root]; null when it would leave it. */
    fun resolve(root: File, rel: String): File? {
        val parts = rel.trim('/').split('/').map { safe(it) }
        if (parts.isEmpty() || parts.size > 2 || parts.any { it.isEmpty() }) return null
        val file = File(root, parts.joinToString("/"))
        return file.takeIf { it.canonicalPath.startsWith(root.canonicalPath + File.separator) }
    }

    /** Every icon, the root's first, then each folder's as "folder/file". */
    fun list(root: File): List<String> {
        val entries = root.listFiles().orEmpty()
        val top = entries.filter { it.isFile }.map { it.name }.sorted()
        val nested =
            entries.filter { it.isDirectory }.sortedBy { it.name.lowercase() }.flatMap { dir ->
                dir.listFiles().orEmpty().filter { it.isFile }.map { "${dir.name}/${it.name}" }.sortedBy { it.lowercase() }
            }
        return top + nested
    }

    private const val MAX_NAME = 80
}

/** Apple's public App Store lookup: an app's icon by its bundle id. Pure, so tested. */
object AppStoreIcons {
    fun lookupUrl(bundleId: String, country: String): String =
        "https://itunes.apple.com/lookup?bundleId=" + URLEncoder.encode(bundleId, "UTF-8") +
            "&entity=tvSoftware,software&country=" + country

    /** The biggest icon of the first result, null when the App Store doesn't know the app. */
    fun artworkOf(json: String): String? = runCatching {
        val results = JSONObject(json).optJSONArray("results") ?: return null
        (0 until results.length()).asSequence().mapNotNull { results.optJSONObject(it) }.firstNotNullOfOrNull { r ->
            listOf(
                "artworkUrl512",
                "artworkUrl100",
                "artworkUrl60"
            ).firstNotNullOfOrNull { k -> r.optString(k).takeIf { it.startsWith("http") } }
        }
    }.getOrNull()
}

/**
 * POST /icons-import-direct: each direct device's app icons saved into its own folder of the
 * icons, to use on cards and in the Sources pop-up. An LG TV gives its apps' and inputs' icons
 * itself; an Apple TV's are looked up on the App Store by bundle id, a Sky Q's channel logos on
 * Sky's image service (1.5.3) (both need the internet). Zidoo,
 * Eversolo and Android TV don't give their apps' icons: they're reported as such.
 */
class DirectIconImport(
    private val root: File,
    private val lgTvs: LgTvRegistry?,
    private val appleTvs: AppleTvRegistry?,
    private val skys: SkyRegistry? = null,
    private val samsungs: SamsungRegistry? = null
) {
    private val http = OkHttpClient.Builder().connectTimeout(TIMEOUT_S, TimeUnit.SECONDS).readTimeout(TIMEOUT_S, TimeUnit.SECONDS).build()

    /** Runs the import: one report per device {name, kind, saved, missing}. Existing files are replaced. */
    fun run(): JSONArray {
        val report = JSONArray()
        lgTvs?.allIcons()?.forEach { (name, icons) ->
            val saved = icons.count { (label, bytes) -> save(name, label, bytes) }
            report.put(JSONObject().put("name", name).put("kind", "lgtv").put("saved", saved).put("missing", 0))
        }
        samsungs?.allIcons()?.forEach { (name, icons) ->
            val saved = icons.count { (label, bytes) -> save(name, label, bytes) }
            report.put(JSONObject().put("name", name).put("kind", "samsung").put("saved", saved).put("missing", 0))
        }
        appleTvs?.installedApps()?.forEach { (name, apps) -> report.put(appleTv(name, apps)) }
        skys?.channelLogos()?.forEach { (name, logos) ->
            val saved = logos.count { (label, url) -> get(url)?.let { save(name, label, it) } == true }
            report.put(JSONObject().put("name", name).put("kind", "sky").put("saved", saved).put("missing", logos.size - saved))
        }
        return report
    }

    private fun appleTv(name: String, apps: Map<String, String>): JSONObject {
        val saved = apps.count { (bundleId, label) -> appStoreIcon(bundleId)?.let { save(name, label, it) } == true }
        return JSONObject().put("name", name).put("kind", "appletv").put("saved", saved).put("missing", apps.size - saved)
    }

    private fun appStoreIcon(bundleId: String): ByteArray? {
        val country = Locale.getDefault().country.lowercase().ifBlank { "us" }
        val json = get(AppStoreIcons.lookupUrl(bundleId, country))?.toString(Charsets.UTF_8) ?: return null
        return AppStoreIcons.artworkOf(json)?.let { get(it) }
    }

    private fun get(url: String): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    }.getOrNull()

    /** [bytes] as `<device>/<label>.png|jpg`, named after the app so the Sources pop-up finds it. */
    private fun save(device: String, label: String, bytes: ByteArray): Boolean = runCatching {
        val ext = if (bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) "jpg" else "png"
        val file = IconFolders.resolve(root, IconFolders.safe(device) + "/" + IconFolders.safe(label) + "." + ext) ?: return false
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
    }.getOrDefault(false)

    private companion object {
        const val TIMEOUT_S = 8L
    }
}
