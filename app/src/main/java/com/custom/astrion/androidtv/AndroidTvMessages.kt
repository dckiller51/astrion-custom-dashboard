package com.custom.astrion.androidtv

/**
 * Pairing messages (`polo.proto`, port 6467): every one is an `OuterMessage` with
 * protocol_version 2 and status 200 around the actual step.
 */
internal object PoloMessages {
    const val STATUS_OK = 200
    const val FIELD_STATUS = 2
    const val FIELD_PAIRING_REQUEST_ACK = 11
    const val FIELD_OPTIONS = 20
    const val FIELD_CONFIGURATION_ACK = 31
    const val FIELD_SECRET_ACK = 41

    private const val PROTOCOL_VERSION = 2
    private const val ENCODING_HEXADECIMAL = 3
    private const val SYMBOL_LENGTH = 6
    private const val ROLE_INPUT = 1

    private fun outer(build: ProtoWriter.() -> Unit): ByteArray = ProtoWriter()
        .uint(1, PROTOCOL_VERSION)
        .uint(FIELD_STATUS, STATUS_OK)
        .apply(build)
        .toByteArray()

    private fun ProtoWriter.hexEncoding(field: Int) = message(field) {
        uint(1, ENCODING_HEXADECIMAL)
        uint(2, SYMBOL_LENGTH)
    }

    /** Step 1: who we are (the TV shows [clientName] while pairing). */
    fun pairingRequest(clientName: String): ByteArray = outer {
        message(10) {
            string(1, "atvremote")
            string(2, clientName)
        }
    }

    /** Step 2: we type a 6-character hexadecimal code. */
    fun options(): ByteArray = outer {
        message(FIELD_OPTIONS) {
            hexEncoding(1)
            uint(3, ROLE_INPUT)
        }
    }

    /** Step 3: same choice, confirmed — the TV then shows the code. */
    fun configuration(): ByteArray = outer {
        message(30) {
            hexEncoding(1)
            uint(2, ROLE_INPUT)
        }
    }

    /** Step 4: the secret derived from the code and both certificates (see [AndroidTvPairing.secret]). */
    fun secret(secret: ByteArray): ByteArray = outer { message(40) { bytes(1, secret) } }
}

/** Control messages (`remotemessage.proto`, port 6466): one `RemoteMessage` with one field set. */
internal object RemoteMessages {
    const val CONFIGURE = 1
    const val SET_ACTIVE = 2
    const val ERROR = 3
    const val PING_REQUEST = 8
    const val IME_KEY_INJECT = 20
    const val START = 40
    const val SET_VOLUME_LEVEL = 50

    /** Feature bits, both what the TV supports (its configure) and what we ask for. */
    const val FEATURE_PING = 1
    const val FEATURE_KEY = 2
    const val FEATURE_IME = 4 // needed to be told the app in the foreground
    const val FEATURE_POWER = 32
    const val FEATURE_VOLUME = 64
    const val FEATURE_APP_LINK = 512
    const val WANTED_FEATURES = FEATURE_PING or FEATURE_KEY or FEATURE_IME or FEATURE_POWER or FEATURE_VOLUME or FEATURE_APP_LINK

    const val DIRECTION_START_LONG = 1
    const val DIRECTION_END_LONG = 2
    const val DIRECTION_SHORT = 3

    // Field numbers inside the incoming messages.
    const val APP_INFO = 1
    const val APP_PACKAGE = 12
    const val VOLUME_MAX = 6
    const val VOLUME_LEVEL = 7
    const val VOLUME_MUTED = 8
    const val DEVICE_INFO = 2
    const val DEVICE_MODEL = 1
    const val DEVICE_VENDOR = 2

    private fun message(field: Int, build: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().message(field, build).toByteArray()

    /** Answer to the TV's configure: the features we use (among the ones it supports), and who we are. */
    fun configure(features: Int): ByteArray = message(CONFIGURE) {
        uint(1, features)
        message(2) {
            uint(3, 1)
            string(4, "1")
            string(5, "atvremote")
            string(6, "1.0.0")
        }
    }

    fun setActive(features: Int): ByteArray = message(SET_ACTIVE) { uint(1, features) }

    fun pingResponse(value: Int): ByteArray = message(9) { uint(1, value) }

    fun keyInject(keyCode: Int, direction: Int = DIRECTION_SHORT): ByteArray = message(10) {
        uint(1, keyCode)
        uint(2, direction)
    }

    /** Opens [link] on the TV: an app link, or `market://launch?id=<package>` to start an app. */
    fun appLinkLaunch(link: String): ByteArray = message(90) { string(1, link) }
}

/**
 * Android key codes by name, the way Home Assistant's Android TV Remote integration names them
 * in `remote.send_command` ("DPAD_UP", "HOME", "KEYCODE_HOME"…). A number is also accepted.
 */
object AndroidTvKeys {
    private const val TABLE =
        "HOME=3,BACK=4,0=7,1=8,2=9,3=10,4=11,5=12,6=13,7=14,8=15,9=16,STAR=17,POUND=18," +
            "DPAD_UP=19,DPAD_DOWN=20,DPAD_LEFT=21,DPAD_RIGHT=22,DPAD_CENTER=23,VOLUME_UP=24,VOLUME_DOWN=25," +
            "POWER=26,CLEAR=28,TAB=61,SPACE=62,ENTER=66,DEL=67,MENU=82,NOTIFICATION=83,SEARCH=84," +
            "MEDIA_PLAY_PAUSE=85,MEDIA_STOP=86,MEDIA_NEXT=87,MEDIA_PREVIOUS=88,MEDIA_REWIND=89," +
            "MEDIA_FAST_FORWARD=90,MUTE=91,PAGE_UP=92,PAGE_DOWN=93,BUTTON_A=96,BUTTON_B=97,BUTTON_X=99," +
            "BUTTON_Y=100,BUTTON_SELECT=109,BUTTON_START=108,ESCAPE=111,FORWARD_DEL=112,MOVE_HOME=122," +
            "MOVE_END=123,FORWARD=125,MEDIA_PLAY=126,MEDIA_PAUSE=127,MEDIA_CLOSE=128,MEDIA_EJECT=129," +
            "MEDIA_RECORD=130,F1=131,F2=132,F3=133,F4=134,F5=135,F6=136,F7=137,F8=138,F9=139,F10=140," +
            "F11=141,F12=142,VOLUME_MUTE=164,INFO=165,CHANNEL_UP=166,CHANNEL_DOWN=167,ZOOM_IN=168," +
            "ZOOM_OUT=169,TV=170,WINDOW=171,GUIDE=172,DVR=173,BOOKMARK=174,CAPTIONS=175,SETTINGS=176," +
            "TV_POWER=177,TV_INPUT=178,STB_POWER=179,STB_INPUT=180,AVR_POWER=181,AVR_INPUT=182," +
            "PROG_RED=183,PROG_GREEN=184,PROG_YELLOW=185,PROG_BLUE=186,APP_SWITCH=187,LANGUAGE_SWITCH=204," +
            "3D_MODE=206,MUSIC=209,ASSIST=219,BRIGHTNESS_DOWN=220,BRIGHTNESS_UP=221,MEDIA_AUDIO_TRACK=222," +
            "SLEEP=223,WAKEUP=224,PAIRING=225,MEDIA_TOP_MENU=226,11=227,12=228,LAST_CHANNEL=229," +
            "TV_DATA_SERVICE=230,VOICE_ASSIST=231,TV_RADIO_SERVICE=232,TV_TELETEXT=233," +
            "TV_NUMBER_ENTRY=234,TV_TERRESTRIAL_ANALOG=235,TV_TERRESTRIAL_DIGITAL=236,TV_SATELLITE=237," +
            "TV_NETWORK=241,TV_ANTENNA_CABLE=242,TV_INPUT_HDMI_1=243,TV_INPUT_HDMI_2=244," +
            "TV_INPUT_HDMI_3=245,TV_INPUT_HDMI_4=246,TV_INPUT_COMPOSITE_1=247,TV_INPUT_COMPOSITE_2=248," +
            "TV_INPUT_COMPONENT_1=249,TV_INPUT_COMPONENT_2=250,TV_INPUT_VGA_1=251,TV_AUDIO_DESCRIPTION=252," +
            "TV_ZOOM_MODE=255,TV_CONTENTS_MENU=256,TV_MEDIA_CONTEXT_MENU=257,TV_TIMER_PROGRAMMING=258," +
            "HELP=259,NAVIGATE_PREVIOUS=260,NAVIGATE_NEXT=261,NAVIGATE_IN=262,NAVIGATE_OUT=263," +
            "DPAD_UP_LEFT=268,DPAD_DOWN_LEFT=269,DPAD_UP_RIGHT=270,DPAD_DOWN_RIGHT=271," +
            "MEDIA_SKIP_FORWARD=272,MEDIA_SKIP_BACKWARD=273,MEDIA_STEP_FORWARD=274," +
            "MEDIA_STEP_BACKWARD=275,SOFT_SLEEP=276,ALL_APPS=284,REFRESH=285,THUMBS_UP=286," +
            "THUMBS_DOWN=287,PROFILE_SWITCH=288,VIDEO_APP_1=289,VIDEO_APP_2=290,VIDEO_APP_3=291," +
            "VIDEO_APP_4=292,FEATURED_APP_1=297,FEATURED_APP_2=298,FEATURED_APP_3=299,FEATURED_APP_4=300"

    private val byName: Map<String, Int> =
        TABLE.split(',').associate { entry ->
            val (name, code) = entry.split('=')
            name to code.toInt()
        }

    /** Friendly names some cards and the Apple TV remote already use, mapped to their key. */
    private val aliases =
        mapOf(
            "UP" to "DPAD_UP",
            "DOWN" to "DPAD_DOWN",
            "LEFT" to "DPAD_LEFT",
            "RIGHT" to "DPAD_RIGHT",
            "SELECT" to "DPAD_CENTER",
            "CENTER" to "DPAD_CENTER",
            "OK" to "DPAD_CENTER",
            "PLAY_PAUSE" to "MEDIA_PLAY_PAUSE",
            "PLAY" to "MEDIA_PLAY",
            "PAUSE" to "MEDIA_PAUSE",
            "STOP" to "MEDIA_STOP",
            "NEXT" to "MEDIA_NEXT",
            "PREVIOUS" to "MEDIA_PREVIOUS",
            "REWIND" to "MEDIA_REWIND",
            "FAST_FORWARD" to "MEDIA_FAST_FORWARD"
        )

    /**
     * Names whose Android meaning isn't what a remote's button means: KEYCODE_MUTE (91) mutes the
     * microphone, while the TV remote card's "MUTE" button means the sound. "91" still reaches it.
     */
    private val overrides = mapOf("MUTE" to "VOLUME_MUTE")

    /** The key code for [name] ("DPAD_UP", "KEYCODE_DPAD_UP", "dpad_up", "19"), or null if unknown. */
    fun code(name: String): Int? {
        val n = name.trim().uppercase().removePrefix("KEYCODE_")
        n.toIntOrNull()?.let { return it.takeIf { code -> code > 0 } }
        overrides[n]?.let { return byName[it] }
        return byName[n] ?: aliases[n]?.let(byName::get)
    }
}
