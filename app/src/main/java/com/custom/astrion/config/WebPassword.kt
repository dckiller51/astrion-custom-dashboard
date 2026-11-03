package com.custom.astrion.config

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The web page's password (1.4.6), set from the remote's Settings › Web configuration. Once set,
 * every page and endpoint of the configurator on :8080 asks for it (HTTP Basic, any user name).
 *
 * Only a salted PBKDF2 hash is kept, in the same preferences as the other settings, so a full
 * backup carries it to a new remote.
 */
object WebPassword {
    /** Shortest password accepted. */
    const val MIN_LENGTH = 4

    private const val PREFS = "astrion_settings"
    private const val KEY_SALT = "web_password_salt"
    private const val KEY_HASH = "web_password_hash"
    private const val ITERATIONS = 10_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    @Volatile
    private var cache: Pair<ByteArray, ByteArray>? = null

    @Volatile
    private var cacheLoaded = false

    /** Changes whenever the password is set, removed or reloaded, so a cached check is dropped. */
    @Volatile
    var generation = 0
        private set

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSet(context: Context): Boolean = stored(context) != null

    /** Sets (or replaces) the password; false when it's shorter than [MIN_LENGTH]. */
    fun set(context: Context, password: String): Boolean {
        if (password.length < MIN_LENGTH) return false
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = derive(password, salt)
        prefs(context).edit {
            putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
        }
        cache = salt to hash
        cacheLoaded = true
        generation++
        return true
    }

    fun clear(context: Context) {
        prefs(context).edit {
            remove(KEY_SALT)
            remove(KEY_HASH)
        }
        cache = null
        cacheLoaded = true
        generation++
    }

    /** True when [password] is the one set (always false when none is set). */
    fun check(context: Context, password: String): Boolean {
        val (salt, hash) = stored(context) ?: return false
        return MessageDigest.isEqual(derive(password, salt), hash)
    }

    /** Forgets what was read, e.g. after a backup restored other settings. */
    fun reload() {
        cacheLoaded = false
        generation++
    }

    private fun stored(context: Context): Pair<ByteArray, ByteArray>? {
        if (!cacheLoaded) {
            val p = prefs(context)
            val salt = p.getString(KEY_SALT, null)
            val hash = p.getString(KEY_HASH, null)
            cache =
                if (salt.isNullOrBlank() || hash.isNullOrBlank()) {
                    null
                } else {
                    runCatching { Base64.decode(salt, Base64.NO_WRAP) to Base64.decode(hash, Base64.NO_WRAP) }.getOrNull()
                }
            cacheLoaded = true
        }
        return cache
    }

    private fun derive(password: String, salt: ByteArray): ByteArray = SecretKeyFactory
        .getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS))
        .encoded
}
