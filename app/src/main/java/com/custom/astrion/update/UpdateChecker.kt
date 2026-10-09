package com.custom.astrion.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.custom.astrion.BuildConfig
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Checks this project's GitHub Releases for a build newer than the one
 * currently installed, and can download + launch the system installer for
 * it. There is no silent auto-apply: Android always requires the user to
 * approve the installation through the system package installer UI (and, once,
 * to allow "install unknown apps" for this app) — this only automates
 * finding and downloading the right APK.
 */
object UpdateChecker {
    // Public repo hosting signed release APKs — not a secret, just where
    // prebuilt community releases get published. MUST be your own
    // "owner/repo" — the placeholder below will always report "no update".
    private const val REPO = "dckiller51/astrion-custom-dashboard"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val BETA_API_URL = "https://api.github.com/repos/$REPO/releases/tags/dev-latest"

    data class UpdateInfo(val version: String, val notes: String, val apkUrl: String)

    /**
     * Distinguishes "genuinely up to date" from "the check itself failed" —
     * collapsing both into a nullable result made real failures (wrong repo
     * name, draft/pre-release, no network...) silently look like "up to
     * date", which is exactly the bug this type exists to prevent.
     */
    sealed class CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult()

        data object UpToDate : CheckResult()

        data class Failed(val reason: String) : CheckResult()
    }

    private val http = OkHttpClient()

    /** Blocking network call — invoke off the main thread. */
    fun checkForUpdate(): CheckResult {
        val release =
            when (val fetch = fetchRelease(API_URL)) {
                is FetchResult.Err -> return fetch.failed
                is FetchResult.Ok -> fetch.release
            }
        val tag = release.tag.removePrefix("v")
        if (!isNewer(tag)) return CheckResult.UpToDate
        return releaseToResult(release, tag)
    }

    /**
     * Same idea as [checkForUpdate], but against the rolling `dev-latest`
     * pre-release instead of the official `/releases/latest` — always
     * reports it as installable regardless of [isNewer], since a beta build
     * has no meaningful "already up to date" comparison against a tag that
     * gets overwritten on every push to dev.
     */
    fun checkBetaUpdate(): CheckResult {
        val release =
            when (val fetch = fetchRelease(BETA_API_URL)) {
                is FetchResult.Err -> return fetch.failed
                is FetchResult.Ok -> fetch.release
            }
        return releaseToResult(release, release.tag)
    }

    private data class RawRelease(val tag: String, val body: String, val apkUrl: String?)

    private sealed class FetchResult {
        data class Ok(val release: RawRelease) : FetchResult()

        data class Err(val failed: CheckResult.Failed) : FetchResult()
    }

    private fun fetchRelease(url: String): FetchResult {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .build()

        val response =
            try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                return FetchResult.Err(CheckResult.Failed("Network error: ${e.message}"))
            }

        response.use { resp ->
            if (!resp.isSuccessful) {
                return FetchResult.Err(
                    CheckResult.Failed(
                        "GitHub API returned HTTP ${resp.code} for $url — check the REPO constant " +
                            "in UpdateChecker.kt, and that the release isn't a draft " +
                            "(the /latest endpoint additionally ignores pre-releases)."
                    )
                )
            }
            val bodyText =
                resp.body?.string()
                    ?: return FetchResult.Err(CheckResult.Failed("Empty response from GitHub"))

            val root =
                runCatching { Json.parseToJsonElement(bodyText).jsonObject }
                    .getOrElse {
                        return FetchResult.Err(CheckResult.Failed("Could not parse GitHub response: ${it.message}"))
                    }

            val tag =
                root["tag_name"]?.jsonPrimitive?.content
                    ?: return FetchResult.Err(CheckResult.Failed("Release has no tag_name"))
            val apkUrl =
                root["assets"]
                    ?.jsonArray
                    ?.map { it.jsonObject }
                    ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                    ?.get("browser_download_url")
                    ?.jsonPrimitive
                    ?.content
            val body = root["body"]?.jsonPrimitive?.content.orEmpty()
            return FetchResult.Ok(RawRelease(tag = tag, body = body, apkUrl = apkUrl))
        }
    }

    private fun releaseToResult(release: RawRelease, displayVersion: String): CheckResult {
        val apkUrl =
            release.apkUrl
                ?: return CheckResult.Failed("Release ${release.tag} has no .apk file attached as an asset")
        return CheckResult.Available(UpdateInfo(version = displayVersion, notes = release.body, apkUrl = apkUrl))
    }

    /** Minimal x.y.z comparison against current BuildConfig.VERSION_NAME — good enough for plain semver-ish tags like "1.4.0". */
    private fun isNewer(remote: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        // substringBefore("-") strips any suffix (e.g. the debug build's
        // "-beta" from versionNameSuffix) so the numeric comparison below
        // isn't silently truncated — see CHANGELOG 1.0.3 for the bug this fixes.
        val l = BuildConfig.VERSION_NAME.substringBefore("-").split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    /** Downloads the APK into the app cache dir. Blocking — invoke off the main thread.
     * Streamed straight to the file: reading the whole APK into memory first
     * (as this used to) briefly held tens of MB of RAM on a small device. */
    fun download(context: Context, apkUrl: String): File? {
        val request = Request.Builder().url(apkUrl).build()
        val file = File(context.cacheDir, UPDATE_APK_NAME)
        val ok =
            http.newCall(request).execute().use { resp ->
                val body = resp.body
                if (resp.isSuccessful && body != null) {
                    file.outputStream().use { out -> body.byteStream().copyTo(out) }
                    true
                } else {
                    false
                }
            }
        return if (ok) file else null
    }

    /**
     * Frees storage the app no longer needs — called once at startup:
     *  - the update APK downloaded by [download]: once the new version runs,
     *    it's a dead copy of the whole app that used to stay in the cache
     *    forever;
     *  - upload temp files the local web server (NanoHTTPD) left behind when
     *    a request was interrupted.
     * Only files older than [STALE_AFTER_MS] go, so an install still on
     * screen keeps its APK.
     */
    fun cleanupStaleDownloads(context: Context) {
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        context.cacheDir
            .listFiles()
            ?.filter { it.isFile && isDisposable(it.name) && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    private fun isDisposable(name: String): Boolean = name == UPDATE_APK_NAME || name.startsWith("NanoHTTPD-")

    private const val UPDATE_APK_NAME = "astrion-update.apk"
    private const val STALE_AFTER_MS = 10 * 60 * 1000L

    /**
     * Launches the system installer UI for the given APK file.
     *
     * Deliberately synchronous, on the calling thread — so that if the OS
     * refuses (e.g. "install unknown apps" not granted for this app), the
     * resulting exception propagates straight back to the caller instead of
     * surfacing later, uncaught, on the main thread (which would crash the
     * whole app — and with it, the local web server used to trigger this).
     */
    fun promptInstall(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(intent)
    }
}
