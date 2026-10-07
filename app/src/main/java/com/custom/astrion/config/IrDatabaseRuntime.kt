package com.custom.astrion.config

import android.os.Environment
import android.util.Log
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "IrDatabaseRuntime"

/**
 * Decodes a "learned" Pronto Hex code (type 0000 — raw timing, not a
 * codebook lookup) into an [IrStepConfig] for `ConsumerIrManager.transmit()`.
 * Falls back to the repeat section if there's no "once" section (some
 * codes, e.g. a few Sony buttons, only carry a repeat burst).
 *
 * Ported from docs/js/ir.js's `prontoToPattern()` — keep both in sync if
 * this logic ever changes. The web builder's copy is still used when
 * hand-pasting a one-off Pronto code into an [IrDeviceSource.Inline]
 * device; everything sourced from ir-database/ resolves here instead, at
 * runtime, from the curated files under `/sdcard/astrion/ir-database/`.
 */
fun prontoToPattern(pronto: String): IrStepConfig {
    val words = pronto.trim().split(Regex("\\s+")).map { it.toInt(16) }
    require(words.size >= 4) { "Pronto code too short (need at least 4 words, got ${words.size})" }
    val type = words[0]
    val freqCode = words[1]
    val onceLen = words[2]
    val repeatLen = words[3]
    require(type == 0x0000) {
        "Only \"learned\" Pronto codes (type 0000) are supported, got type ${type.toString(16)}"
    }
    require(freqCode != 0) { "Pronto code has a zero frequency code" }
    val carrierHz = Math.round(4145146.0 / freqCode).toInt()
    val periodUs = 1_000_000.0 / carrierHz
    val rest = words.drop(4)
    val once = rest.take(onceLen * 2)
    val repeat = rest.drop(onceLen * 2).take(repeatLen * 2)
    val chosen = once.ifEmpty { repeat }
    require(chosen.isNotEmpty()) { "Pronto code has neither a \"once\" nor a \"repeat\" section" }
    return IrStepConfig(carrierHz, chosen.map { Math.round(it * periodUs).toInt() })
}

/** brand_name.lowercase() -> model_name.lowercase() -> commandId -> pronto */
private typealias CategoryIndex = Map<String, Map<String, Map<String, String>>>

/**
 * Resolves [IrDeviceSource.SdCardRef] devices against the curated JSON
 * files a person generates with the (separately hosted, not bundled with
 * this app) ir-database picker and copies onto the device by hand, at
 * `/sdcard/astrion/ir-database/<category>.json` — same
 * `{category, brands:[{brand_name, models:[{model_name, commands:{id:{pronto}}}]}]}`
 * shape as the source repo's own per-category files, just a smaller,
 * user-curated subset of it.
 *
 * Two caches, both populated lazily and kept for the process lifetime:
 * one raw-file-per-category (parsing a multi-hundred-command JSON file on
 * every single button press would be wasteful), one resolved-step-per-
 * command (so repeat presses of the same button skip both the file lookup
 * and the Pronto math). [invalidate] drops both — wired to a "Reload
 * ir-database" action in Settings for after copying updated files onto
 * the device without a full app restart.
 */
object IrDatabaseRuntime {

    private val baseDir: File
        get() = File(Environment.getExternalStorageDirectory(), "astrion/ir-database")

    private val categoryCache = mutableMapOf<String, CategoryIndex?>()
    private val resolvedCache = mutableMapOf<String, IrStepConfig?>()

    /** Clears both caches — call after copying updated files onto /sdcard/. */
    fun invalidate() {
        categoryCache.clear()
        resolvedCache.clear()
    }

    fun resolve(category: String, brand: String, model: String, commandId: String): IrStepConfig? {
        val cacheKey = "$category\u0000$brand\u0000$model\u0000$commandId"
        if (resolvedCache.containsKey(cacheKey)) return resolvedCache[cacheKey] // hit, incl. a cached failure

        val index = categoryCache.getOrPut(category) { loadCategory(category) }
        val pronto = index
            ?.get(brand.lowercase())
            ?.get(model.lowercase())
            ?.get(commandId)

        if (pronto == null) {
            Log.w(
                TAG,
                "resolve: no command \"$commandId\" for $brand/$model in $category.json " +
                    "(checked /sdcard/astrion/ir-database/)"
            )
            resolvedCache[cacheKey] = null
            return null
        }

        val step = runCatching { prontoToPattern(pronto) }
            .onFailure { Log.e(TAG, "resolve: bad Pronto code for $brand/$model/$commandId in $category.json", it) }
            .getOrNull()
            // prontoToPattern() only computes freq/pattern; the raw string
            // it was decoded from is attached here so IrTarget.Extender
            // routing has it later without recomputing anything.
            ?.copy(pronto = pronto)
        resolvedCache[cacheKey] = step
        return step
    }

    /** Resolves one command against a single already-looked-up device — the
     * direct path for a call site that already has its [IrDeviceConfig]
     * (e.g. via a `Map<String, IrDeviceConfig>` lookup), no need to search
     * a list by id again. [resolveFrom] below is the same logic for a call
     * site that only has the raw device list and an id to search for. */
    fun resolve(device: IrDeviceConfig, command: String): IrStepConfig? = when (val source = device.source) {
        is IrDeviceSource.Inline -> source.commands[command]
        is IrDeviceSource.SdCardRef -> resolve(source.category, source.brand, source.model, command)
    }

    /**
     * Resolves one command against a device list, regardless of whether
     * the matched device is [IrDeviceSource.Inline] or an ir-database
     * [IrDeviceSource.SdCardRef]. The single place that knows how to read
     * an [IrDeviceConfig] end to end — every call site that fires an IR
     * command (Dashboard.kt's sendIrCommand, MainActivity.kt's
     * hardware-hotkey handler) goes through this or [resolve] above
     * instead of re-deriving the same when-branch locally, so there's one
     * spot to update if this logic ever changes again, not N places to
     * remember to keep in sync.
     */
    fun resolveFrom(devices: List<IrDeviceConfig>, deviceId: String, command: String): IrStepConfig? {
        val device = devices.firstOrNull { it.id == deviceId } ?: return null
        return resolve(device, command)
    }

    private fun loadCategory(category: String): CategoryIndex? {
        // Case-insensitive on purpose: this file's name is typed by hand
        // once (either directly on-device, or via the picker's download,
        // which does get it right, but a manual copy/rename is an easy
        // place to end up with e.g. "TV.json") and Android's filesystem is
        // case-sensitive, so a strict match here would silently resolve
        // nothing for every command in that category — no crash, just
        // every IR button in it doing nothing, which is a much harder
        // thing to notice and debug than a slightly loose filename match.
        val file = baseDir.listFiles()?.firstOrNull { it.name.equals("$category.json", ignoreCase = true) }
        if (file == null) {
            Log.w(
                TAG,
                "loadCategory: no $category.json (any case) in ${baseDir.absolutePath} " +
                    "— copy it there from the ir-database picker"
            )
            return null
        }
        return try {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            parseBrands(root).associate { (brandName, models) -> brandName.lowercase() to models }
        } catch (e: Exception) {
            Log.e(TAG, "loadCategory: failed to parse ${file.absolutePath}", e)
            null
        }
    }

    /**
     * [{brand_name, models:[{model_name, commands:{id:{pronto,...}}}]}]
     * -> [(brand_name, model_name.lowercase() -> commandId -> pronto)]
     */
    private fun parseBrands(root: JsonObject): List<Pair<String, Map<String, Map<String, String>>>> {
        val brandsArray = root["brands"] as? JsonArray ?: error("missing or malformed \"brands\" array")
        return brandsArray.map { brandEl ->
            val brandObj = brandEl.jsonObject
            val brandName = brandObj["brand_name"]!!.jsonPrimitive.content
            val modelsArray = brandObj["models"] as? JsonArray
                ?: error("brand \"$brandName\" missing \"models\" array")
            val models = modelsArray.associate { modelEl ->
                val modelObj = modelEl.jsonObject
                val modelName = modelObj["model_name"]!!.jsonPrimitive.content
                val commands = modelObj["commands"]?.jsonObject
                    ?.entries
                    ?.associate { (cmdId, cmdEl) -> cmdId to cmdEl.jsonObject["pronto"]!!.jsonPrimitive.content }
                    .orEmpty()
                modelName.lowercase() to commands
            }
            brandName to models
        }
    }
}
