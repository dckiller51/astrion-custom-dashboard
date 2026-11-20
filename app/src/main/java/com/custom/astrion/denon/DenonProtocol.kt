package com.custom.astrion.denon

import java.util.Locale

/**
 * Denon and Marantz AV receivers' local control (1.5.4), over their web API (port 8080 on
 * AVR-X models from 2016, else 80), as their own apps use it:
 *  - state: `GET /goform/formMainZone_MainZoneXmlStatus.xml` (power, input, volume, mute,
 *    sound mode, the inputs' names and which ones are hidden);
 *  - the model: `GET /goform/Deviceinfo.xml`;
 *  - commands: `GET /goform/formiPhoneAppDirect.xml?<command>`, the receiver's own command set
 *    (`ZMON`, `MVUP`, `MV455`, `SIGAME`, `MSMOVIE`, `MNCUP`…); power is the main zone's (`ZM`),
 *    `PW` would switch every zone;
 *  - recent models (AVC-X4800H…) answer 403 on the status page: their state then comes from
 *    `POST /goform/AppCommand.xml` (`GetAllZonePowerStatus`, `GetAllZoneSource`…) and their inputs
 *    from `Deviceinfo.xml` plus `GetRenameSource` / `GetDeletedSource`, as denonavr does.
 *
 * HTTP rather than telnet: a receiver takes a single telnet connection, often Home Assistant's.
 * URLs, tags and sound modes as documented by the denonavr library (MIT), the one Home
 * Assistant's Denon AVR integration uses. Pure, so tested.
 */
object DenonProtocol {
    val PORTS = listOf(8080, 80)

    const val STATUS = "/goform/formMainZone_MainZoneXmlStatus.xml"

    /** The lighter status pages, for models with neither the full one nor AppCommand.xml. */
    val LITE_STATUS_PATHS = listOf("/goform/formMainZone_MainZoneXmlStatusLite.xml", "/goform/formMainZone_MainZoneXml.xml")

    // ---- AppCommand.xml (recent models) -----------------------------------------------------------

    const val APP_COMMAND = "/goform/AppCommand.xml"

    /** The main zone's state, in one call (five commands: the receiver takes no more per `<tx>`). */
    val STATE_COMMANDS =
        listOf("GetAllZonePowerStatus", "GetAllZoneSource", "GetAllZoneVolume", "GetAllZoneMuteStatus", "GetSurroundModeStatus")

    /** The inputs' names and which ones are hidden. */
    val SOURCE_COMMANDS = listOf("GetRenameSource", "GetDeletedSource")

    /** The POST body for [commands] (at most five per `<tx>`, as the receiver wants). */
    fun appCommandBody(commands: List<String>): String = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        commands.chunked(MAX_PER_TX).joinToString("") { chunk -> "<tx>" + chunk.joinToString("") { "<cmd id=\"1\">$it</cmd>" } + "</tx>" }

    private const val MAX_PER_TX = 5

    /** The answer's `<cmd>` blocks, in the order asked (`<cmd>` or `<cmd id="…">`). */
    fun cmdBlocks(xml: String): List<String> =
        Regex("<cmd(?:\\s[^>]*)?>(.*?)</cmd>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }.toList()

    private fun tag(xml: String?, tag: String): String? =
        xml?.let { Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(it)?.groupValues?.get(1)?.let(::clean) }

    /** [STATE_COMMANDS]' answer, with the [inputs] already known; null when it isn't one. */
    fun appStatus(xml: String, inputs: List<Input>): Status? {
        val b = cmdBlocks(xml)
        if (b.size < STATE_COMMANDS.size) return null
        val source = tag(tag(b[1], "zone1"), "source")
        return Status(
            on = tag(b[0], "zone1")?.uppercase(Locale.ROOT) == "ON",
            input = source?.let { codeOf(it, inputs) },
            volumeDb = tag(tag(b[2], "zone1"), "volume")?.toDoubleOrNull(),
            muted = tag(b[3], "zone1")?.lowercase(Locale.ROOT) == "on",
            soundMode = tag(b[4], "surround")?.takeIf { it.isNotEmpty() }?.let(::soundModeOf),
            inputs = inputs,
            name = null
        )
    }

    /**
     * The main zone's inputs: `Deviceinfo.xml`'s list (zone 0), named as [sourcesXml] (the answer to
     * [SOURCE_COMMANDS]) renames them, minus the hidden ones (all of them if every one is hidden).
     */
    fun appInputs(deviceInfoXml: String, sourcesXml: String?): List<Input> {
        val zone =
            Regex("<DeviceZoneCapabilities>(.*?)</DeviceZoneCapabilities>", RegexOption.DOT_MATCHES_ALL).findAll(deviceInfoXml)
                .map { it.groupValues[1] }
                .firstOrNull { tag(tag(it, "Zone"), "No") == "0" } ?: return emptyList()
        val sources =
            Regex("<Source>(.*?)</Source>", RegexOption.DOT_MATCHES_ALL).findAll(tag(zone, "InputSource").orEmpty())
                .mapNotNull { m ->
                    tag(m.groupValues[1], "FuncName")?.takeIf {
                        it.isNotEmpty()
                    }?.let { it to (tag(m.groupValues[1], "DefaultName") ?: it) }
                }
                .toList()
        val blocks = sourcesXml?.let(::cmdBlocks).orEmpty()
        val renamed = lists(
            blocks.getOrNull(0),
            "functionrename"
        ).mapNotNull { l -> tag(l, "name")?.let { it to tag(l, "rename") } }.toMap()
        val hidden = lists(
            blocks.getOrNull(1),
            "functiondelete"
        ).filter { tag(it, "use") == "0" }.mapNotNull { tag(it, "FuncName") }.toSet()
        fun input(func: String, default: String) = Input(SOURCE_CODES[func] ?: func, renamed[func]?.takeIf { it.isNotEmpty() } ?: default)
        val shown = sources.filter { (func, _) -> func !in hidden }.map { (f, d) -> input(f, d) }
        return shown.ifEmpty { sources.map { (f, d) -> input(f, d) } }
    }

    /** The input [s] is (a code or a name the receiver gives), as `SI` takes it. */
    private fun codeOf(s: String, inputs: List<Input>): String =
        inputs.firstOrNull { it.code.equals(s, true) || it.name.equals(s, true) }?.code ?: SOURCE_CODES[s] ?: s

    private fun lists(block: String?, wrapper: String): List<String> =
        Regex("<list>(.*?)</list>", RegexOption.DOT_MATCHES_ALL).findAll(tag(block, wrapper).orEmpty()).map { it.groupValues[1] }.toList()
    const val DEVICE_INFO = "/goform/Deviceinfo.xml"

    /** Zone 2 or 3's status page (1.5.7). */
    fun zoneStatusPath(zone: Int) = "/goform/formZone${zone}_Zone${zone}XmlStatusLite.xml"

    /** A zone's power, input, volume and mute (1.5.7): what its Lite status page says; null when it says nothing. */
    data class ZoneStatus(val on: Boolean, val input: String?, val volumeDb: Double?, val muted: Boolean)

    fun zoneStatus(xml: String): ZoneStatus? {
        val power = value(xml, "Power") ?: return null
        return ZoneStatus(
            on = power.uppercase(Locale.ROOT) == "ON",
            input = value(xml, "InputFuncSelect"),
            volumeDb = value(xml, "MasterVolume")?.toDoubleOrNull(),
            muted = value(xml, "Mute")?.lowercase(Locale.ROOT) == "on"
        )
    }

    /** A 0–1 level as a zone's volume command: `Z2` + 00–98 (zones take whole steps). */
    fun zoneVolumeCommand(zone: Int, level: Double): String {
        val v = (level.coerceIn(0.0, MAX_LEVEL) * PERCENT).toInt()
        return "Z$zone" + String.format(Locale.ROOT, "%02d", v)
    }

    fun url(host: String, port: Int, path: String) = "http://$host:$port$path"

    /** A command as the receiver takes it on its web API (a space as %20). */
    fun commandPath(command: String) = "/goform/formiPhoneAppDirect.xml?" + command.trim().replace(" ", "%20")

    // ---- reading the XML ------------------------------------------------------------------------

    /** The `<value>`s of `<tag>` (one or a list), CDATA and spaces removed. */
    fun values(xml: String, tag: String): List<String> {
        val block = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: return emptyList()
        val inner = Regex("<value>(.*?)</value>", RegexOption.DOT_MATCHES_ALL).findAll(block).map { clean(it.groupValues[1]) }.toList()
        return inner.ifEmpty { listOf(clean(block)) }
    }

    fun value(xml: String, tag: String): String? = values(xml, tag).firstOrNull()?.takeIf { it.isNotEmpty() }

    private fun clean(s: String) = s.replace("<![CDATA[", "").replace("]]>", "").trim()
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")

    /** One input: its code (what `SI` takes) and the name the receiver shows. */
    data class Input(val code: String, val name: String)

    /**
     * The inputs as shown: [names] given in Astrion (by code) over the receiver's, minus [hidden];
     * all of them if every one is hidden. For receivers that don't say their renames (AVC-X4800H).
     */
    fun customInputs(inputs: List<Input>, names: Map<String, String>, hidden: Set<String>): List<Input> {
        val named = inputs.map { i -> names[i.code]?.trim()?.takeIf { it.isNotEmpty() }?.let { i.copy(name = it) } ?: i }
        return named.filter { it.code !in hidden }.ifEmpty { named }
    }

    /** What the main zone's status says. */
    data class Status(
        val on: Boolean,
        /** The input's code, when it's one of [inputs]; else what the receiver says. */
        val input: String?,
        /** dB, −80 to +18; null when it doesn't say ("--" in standby). */
        val volumeDb: Double?,
        val muted: Boolean,
        val soundMode: String?,
        val inputs: List<Input>,
        val name: String?
    )

    fun status(xml: String): Status {
        val inputs = inputs(xml)
        val selected = value(xml, "InputFuncSelect")
        val input = selected?.let { s -> inputs.firstOrNull { it.code.equals(s, true) || it.name.equals(s, true) }?.code ?: s }
        val surround = value(xml, "selectSurround") ?: value(xml, "SurrMode")
        return Status(
            // The main zone's power: `Power` is the whole receiver's, on while any zone is.
            on = (value(xml, "ZonePower") ?: value(xml, "Power"))?.uppercase(Locale.ROOT) == "ON",
            input = input,
            volumeDb = value(xml, "MasterVolume")?.toDoubleOrNull(),
            muted = value(xml, "Mute")?.lowercase(Locale.ROOT) == "on",
            soundMode = surround?.let(::soundModeOf),
            inputs = inputs,
            name = value(xml, "FriendlyName")
        )
    }

    /** The inputs in use: `InputFuncList` with their `RenameSource` names, minus the ones `SourceDelete` hides. */
    fun inputs(xml: String): List<Input> {
        val codes = values(xml, "InputFuncList")
        val names = values(xml, "RenameSource")
        val deleted = values(xml, "SourceDelete")
        val all = codes.mapIndexedNotNull { i, code ->
            if (code.isEmpty()) return@mapIndexedNotNull null
            if (deleted.getOrNull(i)?.uppercase(Locale.ROOT) == "DEL") return@mapIndexedNotNull null
            Input(SOURCE_CODES[code] ?: code, names.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: code)
        }
        // Some receivers mark every input hidden: show them all then, as Home Assistant's `show_all_sources`.
        return all.ifEmpty {
            codes.filter { it.isNotEmpty() }.mapIndexed { i, c ->
                Input(
                    SOURCE_CODES[c] ?: c,
                    names.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: c
                )
            }
        }
    }

    /** Names some receivers list inputs by, and the code `SI` takes (denonavr's SOURCE_MAPPING). */
    private val SOURCE_CODES =
        mapOf(
            "TV AUDIO" to "TV", "iPod/USB" to "USB/IPOD", "Bluetooth" to "BT", "Blu-ray" to "BD", "CBL/SAT" to "SAT/CBL",
            "NETWORK" to "NET", "Media Player" to "MPLAY", "AUX" to "AUX1", "Tuner" to "TUNER", "FM" to "TUNER",
            "Internet Radio" to "IRP", "Online Music" to "NET", "Media Server" to "SERVER", "Spotify" to "SPOTIFY",
            "Favorites" to "FAVORITES"
        )

    /** The model, from `Deviceinfo.xml`; null when it isn't a Denon / Marantz answer. */
    fun model(xml: String): String? = value(xml, "ModelName")?.takeIf { it.isNotEmpty() }

    // ---- volume -----------------------------------------------------------------------------------

    /** dB (−80…+18) as Home Assistant's 0–1 level, as denonavr does: (dB + 80) / 100. */
    fun level(db: Double): Double = ((db + DB_OFFSET) / PERCENT).coerceIn(0.0, 1.0)

    /** A 0–1 level as the `MV` command: the receiver's 0–98 scale, half steps written "455". */
    fun volumeCommand(level: Double): String {
        val v = (Math.round(level.coerceIn(0.0, MAX_LEVEL) * PERCENT * 2) / 2.0)
        val whole = v.toInt()
        val half = v - whole >= HALF
        return "MV" + String.format(Locale.ROOT, "%02d", whole) + if (half) "5" else ""
    }

    private const val DB_OFFSET = 80.0
    private const val PERCENT = 100.0
    private const val MAX_LEVEL = 0.98
    private const val HALF = 0.5

    // ---- sound modes ------------------------------------------------------------------------------

    /** The modes offered, as `MS` takes them; what the receiver reports is matched to one with [soundModeOf]. */
    val SOUND_MODES =
        listOf(
            "MOVIE", "MUSIC", "GAME", "DIRECT", "PURE DIRECT", "STEREO", "AUTO", "DOLBY DIGITAL", "DTS SURROUND", "MCH STEREO", "VIRTUAL"
        )

    /** Raw modes as the receiver reports them, for each mode offered (denonavr's SOUND_MODE_MAPPING). */
    private val RAW_MODES =
        linkedMapOf(
            "MUSIC" to listOf("PLII MUSIC", "DTS NEO:6 MUSIC", "DOLBY D +NEO:X M"),
            "MOVIE" to
                listOf("PLII MOVIE", "PLII CINEMA", "DTS NEO:X CINEMA", "DTS NEO:6 CINEMA", "DOLBY D +NEO:X C", "PLIIX CINEMA"),
            "GAME" to listOf("PLII GAME", "DOLBY D +NEO:X G"),
            "PURE DIRECT" to listOf("PURE_DIRECT", "PURE DIRECT"),
            "MCH STEREO" to listOf("MULTI CH STEREO", "MULTI_CH_STEREO", "MCH STEREO"),
            "DTS SURROUND" to
                listOf("DTS SURROUND", "DTS NEURAL:X", "STANDARD(DTS)", "DTS + NEURAL:X", "MULTI CH IN", "DTS-HD MSTR", "DTS VIRTUAL:X"),
            "DOLBY DIGITAL" to
                listOf(
                    "DOLBY DIGITAL", "DOLBY D + DOLBY SURROUND", "DOLBY DIGITAL +", "STANDARD(DOLBY)", "DOLBY SURROUND",
                    "DOLBY D + +DOLBY SURROUND", "NEURAL", "DOLBY HD", "MULTI IN + NEURAL:X", "MULTI IN + DOLBY SURROUND",
                    "DOLBY D + NEURAL:X", "DOLBY DIGITAL + NEURAL:X", "DOLBY DIGITAL + + NEURAL:X", "DOLBY ATMOS",
                    "DOLBY AUDIO - DOLBY SURROUND", "DOLBY TRUEHD"
                )
        )

    /** The mode offered that [raw] is (e.g. "DOLBY ATMOS" → "DOLBY DIGITAL"), else [raw] itself. */
    fun soundModeOf(raw: String): String {
        val r = raw.trim().uppercase(Locale.ROOT)
        RAW_MODES.entries.firstOrNull { (_, raws) -> r in raws }?.let { return it.key }
        return SOUND_MODES.firstOrNull { it == r } ?: r
    }

    // ---- keys ---------------------------------------------------------------------------------------

    /** The Android names cards, hotkeys and the keypad send → the receiver's menu, volume and player commands. */
    private val ANDROID =
        mapOf(
            "DPAD_UP" to "MNCUP", "DPAD_DOWN" to "MNCDN", "DPAD_LEFT" to "MNCLT", "DPAD_RIGHT" to "MNCRT",
            "DPAD_CENTER" to "MNENT", "OK" to "MNENT", "ENTER" to "MNENT", "SELECT" to "MNENT",
            "UP" to "MNCUP", "DOWN" to "MNCDN", "LEFT" to "MNCLT", "RIGHT" to "MNCRT",
            "BACK" to "MNRTN", "ESCAPE" to "MNRTN", "MENU" to "MNMEN ON", "SETTINGS" to "MNMEN ON", "HOME" to "MNMEN ON",
            "INFO" to "MNINF", "TOOLS" to "MNOPT", "OPTION" to "MNOPT",
            "VOLUME_UP" to "MVUP", "VOLUME_DOWN" to "MVDOWN",
            "MEDIA_PLAY" to "NS9A", "MEDIA_PAUSE" to "NS9B", "MEDIA_STOP" to "NS9C", "MEDIA_NEXT" to "NS9D", "MEDIA_PREVIOUS" to "NS9E"
        )

    /** A receiver command: one of its own (`MSMOVIE`, `SIGAME`, `PSDYNEQ ON`…) or an Android name; null when unknown. */
    fun commandOf(name: String): String? {
        val n = name.trim().uppercase(Locale.ROOT).removePrefix("KEYCODE_")
        return ANDROID[n] ?: name.trim().takeIf { NATIVE.matches(it.uppercase(Locale.ROOT)) }?.uppercase(Locale.ROOT)
    }

    /** What its own commands look like: two letters, then the rest (`PWON`, `MV45`, `SISAT/CBL`, `MSPURE DIRECT`). */
    private val NATIVE = Regex("^(PW|ZM|MV|MU|SI|MS|MN|NS|PS|CV|SD|DC|SV|SLP|ECO|TF|TP|TM|Z2|Z3|VS|SS|HD|BT)[A-Z0-9 :/+._\\-]*$")
}
