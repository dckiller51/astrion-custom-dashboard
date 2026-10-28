package com.custom.astrion.tvhelper

import android.content.Context
import java.security.SecureRandom

/**
 * The helper's code: 6 characters made on first start and kept, shown on its screen on the TV
 * and typed once on the remote's web page. Letters and digits that can't be confused (no O/0, I/1).
 */
object HelperCode {
    private const val PREFS = "helper"
    private const val KEY = "code"
    private const val LENGTH = 6
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.takeIf { it.length == LENGTH }?.let { return it }
        val code = generate()
        prefs.edit().putString(KEY, code).apply()
        return code
    }

    fun generate(random: SecureRandom = SecureRandom()): String = String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })
}
