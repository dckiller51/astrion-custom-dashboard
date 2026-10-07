package com.custom.astrion.extender

import android.util.Log
import com.custom.astrion.config.ExtenderConfig

/**
 * All configured IR Extenders, each with its own live [ExtenderClient] —
 * the [com.custom.astrion.harmony.HarmonyHubRegistry] equivalent for IR
 * extenders. Resolves an [com.custom.astrion.config.IrTarget.Extender]'s
 * `extenderId` to the client that should actually send the command.
 */
class ExtenderRegistry(
    extenders: List<ExtenderConfig>,
    private val onError: (extenderName: String, message: String) -> Unit = { _, _ -> }
) {
    private companion object {
        const val TAG = "ExtenderRegistry"
    }

    /** localId -> live client. */
    private val clients: Map<String, ExtenderClient> =
        extenders.associate { ext ->
            ext.localId to
                ExtenderClient(
                    host = ext.host,
                    onError = { msg -> onError(ext.name, msg) }
                )
        }

    val configs: List<ExtenderConfig> = extenders

    /** Looks up a client by [com.custom.astrion.config.IrTarget.Extender.extenderId].
     * Null (not found — e.g. the referenced extender was deleted from the
     * registry after a dashboard.json was written against it) is logged
     * and treated the same as "nowhere to send this", matching how a
     * missing Harmony hub reference is already handled at the call sites. */
    fun client(extenderId: String): ExtenderClient? {
        val client = clients[extenderId]
        if (client == null) {
            Log.w(TAG, "No extender configured for id \"$extenderId\"")
        }
        return client
    }
}
