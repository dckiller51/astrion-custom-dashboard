package com.custom.astrion.web

import java.net.InetAddress
import java.net.UnknownHostException

/**
 * A device added by its name (`skyq.home.lan`) that the remote can't resolve (1.6.0): said as such,
 * not as "no … answers there", since the device may well be on. The remote's own DNS may not know
 * the names a computer on the same network does (dvbit's Sky Q).
 */
object HostNames {
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** An IP address (v4, or v6 with its colons), nothing to resolve. */
    fun isAddress(host: String): Boolean = IPV4.matches(host) || host.contains(':')

    /** Why [host] can't be reached by its name, or null when it's an address or resolves. */
    fun unresolved(host: String, resolve: (String) -> Unit = { InetAddress.getByName(it) }): String? {
        if (host.isBlank() || isAddress(host)) return null
        return try {
            resolve(host)
            null
        } catch (_: UnknownHostException) {
            message(host)
        }
    }

    fun message(host: String) =
        "the remote can't find \"$host\": this name doesn't resolve on the remote (its network's DNS doesn't know it). " +
            "Add the device by its IP address instead."
}
