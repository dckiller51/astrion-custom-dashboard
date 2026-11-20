package com.custom.astrion.androidtv

/**
 * Bluetooth wake through an ESP32 (1.6.0): some Xiaomi TVs and boxes turn their Wi-Fi off in deep
 * standby, so nothing on the network wakes them; their Bluetooth chip still listens for their
 * remote. An ESP32 flashed with esphome-xiaomi-ble-wake sends the remote's wake signal when its
 * "Wake the TV" button is pressed: ESPHome's web server presses it on a POST to [url].
 * Pure, so it is unit-tested.
 */
object BleWake {
    /** The ready-made firmware's button. */
    const val BUTTON = "Wake the TV"

    private val HOST = Regex("^[A-Za-z0-9.:-]{1,253}$")

    /** An ESP32's address as typed: an IP or a name, with a port maybe. */
    fun isHost(text: String): Boolean = HOST.matches(text.trim())

    /** ESPHome's web server: POST /button/<name>/press (2026.x matches the button by its name). */
    fun url(host: String, button: String = BUTTON): String {
        val h = host.trim().removePrefix("http://").trimEnd('/')
        return "http://$h/button/${encode(button)}/press"
    }

    /** A URL path segment: letters, digits and `-._~` kept, the rest percent-encoded (UTF-8). */
    private fun encode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-._~") append(c.toChar()) else append("%%%02X".format(c))
        }
    }
}
