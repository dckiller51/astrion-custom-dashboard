package com.custom.astrion.appletv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Which of the Apple TV's two independently-paired services a discovery/resolve targets. */
enum class AppleTvServiceKind(val mdnsType: String) {
    Companion("_companion-link._tcp."),

    /** The old standalone MRP service — removed by Apple on tvOS >= 15, so this only ever
     * resolves anything on an older Apple TV. Kept for that case; see [AirPlay] for the modern one. */
    Mrp("_mediaremotetv._tcp."),

    /** What now-playing info actually pairs against on tvOS >= 15: MRP tunnelled through an
     * AirPlay 2 remote-control session, reusing the device's ordinary AirPlay service. */
    AirPlay("_airplay._tcp.")
}

/** An Apple TV found on the network by [AppleTvDiscovery.scan]. */
data class DiscoveredAppleTv(
    val kind: AppleTvServiceKind,
    /** mDNS instance name — kept so the device can be re-found later if its port/IP change. */
    val serviceName: String,
    val host: String,
    val port: Int,
    /** e.g. `AppleTV11,1`, or blank if the service didn't advertise it (never set on the MRP service). */
    val model: String
)

/**
 * mDNS (Bonjour) discovery of Apple TVs through Android's [NsdManager].
 *
 * Every Apple TV advertises a `_companion-link._tcp` service whose port is
 * dynamic, so this is used both for the "add device" scan and — through
 * [AppleTvResolver] — to re-find a paired Apple TV each time we reconnect.
 * All methods block and must be called off the main thread.
 */
// resolveService(NsdServiceInfo, ResolveListener) is deprecated on API 34, but it's the only
// variant that exists on the API 26+ this app supports.
@Suppress("DEPRECATION")
class AppleTvDiscovery(context: Context) : AppleTvResolver {
    companion object {
        private const val TAG = "AppleTvDiscovery"
        private const val RESOLVE_TIMEOUT_MS = 3000L

        // A single mDNS browse window often misses a response: Android's Wi-Fi radio can be
        // duty-cycled (sleeping between beacons to save power, especially on embedded/kiosk
        // hardware), so the Apple TV's periodic mDNS announcement can land while the radio
        // isn't listening. Several shorter bursts, spread over a few seconds, catch a response
        // that a single longer window could still miss, since each burst gives the radio a
        // fresh chance to be awake when something arrives.
        private const val SCAN_BURSTS = 3
        private const val SCAN_BURST_MS = 3000L
        private const val SCAN_BURST_GAP_MS = 300L
    }

    // Null when this device's Android build has no NSD (mDNS) system service — seen on some
    // embedded/custom Android boxes. Every entry point below degrades to "nothing found"
    // instead of crashing when that's the case, so a device without NSD simply can't
    // discover or reconnect to Apple TVs, rather than taking the rest of the app down with it.
    private val nsd: NsdManager? = runCatching { context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager }
        .onFailure { Log.w(TAG, "NSD service unavailable on this device: ${it.message}") }
        .getOrNull()

    // Android drops incoming multicast packets by default to save power, which is exactly the
    // transport mDNS announcements and queries use — NsdManager is *supposed* to handle this
    // internally, but on some devices (seen alongside the missing-NSD-service case above, on
    // this project's own hardware) that internal handling is incomplete, and discovery that
    // "sometimes" or "partially" works is the typical symptom. Holding this app-level lock across
    // every scan/resolve is a well-established extra safety net for exactly that failure mode.
    private val multicastLock: WifiManager.MulticastLock? = runCatching {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("astrion-appletv-mdns")
            ?.apply { setReferenceCounted(true) }
    }.onFailure { Log.w(TAG, "Wi-Fi multicast lock unavailable: ${it.message}") }.getOrNull()

    private fun <T> withMulticastLock(block: () -> T): T {
        runCatching { multicastLock?.acquire() }
        try {
            return block()
        } finally {
            runCatching { multicastLock?.release() }
        }
    }

    // Before API 34 NsdManager allows only one resolve at a time.
    private val resolveLock = Any()

    /** Browses [kind] in [SCAN_BURSTS] short windows (more reliable than one longer one — see
     * [SCAN_BURSTS]'s doc); only Apple TVs (by advertised model, when the service advertises
     * one) are returned. */
    fun scan(kind: AppleTvServiceKind): List<DiscoveredAppleTv> = withMulticastLock {
        val service = nsd ?: return@withMulticastLock emptyList()
        val names = LinkedHashSet<String>()
        repeat(SCAN_BURSTS) { burst ->
            names.addAll(scanOnce(service, kind))
            if (burst < SCAN_BURSTS - 1) Thread.sleep(SCAN_BURST_GAP_MS)
        }
        names.toList().mapNotNull { name -> resolveInfo(kind, name)?.let { toDiscovered(kind, it) } }
            .filter { isAppleTv(kind, it.model) }
    }

    private fun scanOnce(service: NsdManager, kind: AppleTvServiceKind): Set<String> {
        val names = LinkedHashSet<String>()
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    synchronized(names) { names.add(serviceInfo.serviceName) }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "start discovery for $serviceType failed: $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
        try {
            service.discoverServices(kind.mdnsType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "discoverServices failed", e)
            return emptySet()
        }
        Thread.sleep(SCAN_BURST_MS)
        runCatching { service.stopServiceDiscovery(listener) }
        return synchronized(names) { names.toSet() }
    }

    override fun resolve(serviceName: String, kind: AppleTvServiceKind, timeoutMs: Long): AppleTvEndpoint? = withMulticastLock {
        val info = resolveInfo(kind, serviceName, timeoutMs) ?: return@withMulticastLock null
        val found = toDiscovered(kind, info) ?: return@withMulticastLock null
        AppleTvEndpoint(found.host, found.port)
    }

    // A device that advertises no model at all (true for the MRP service — only Companion's TXT
    // record carries `rpMd`) is assumed to be an Apple TV too, since by the time this runs the
    // caller already knows the target instance name from a Companion scan/pairing. `_airplay._tcp`
    // is the one exception: it's a generic AirPlay-receiver service lots of non-Apple-TV devices
    // (HomePods, AirPlay speakers, Macs, smart TVs) advertise too, so an *unknown* model there is
    // never assumed to be an Apple TV — only one that actually says "AppleTV..." is kept.
    private fun isAppleTv(kind: AppleTvServiceKind, model: String): Boolean {
        if (kind == AppleTvServiceKind.AirPlay) return model.startsWith("AppleTV", ignoreCase = true)
        return model.isBlank() || model.startsWith("AppleTV", ignoreCase = true)
    }

    private fun resolveInfo(kind: AppleTvServiceKind, serviceName: String, timeoutMs: Long = RESOLVE_TIMEOUT_MS): NsdServiceInfo? =
        synchronized(resolveLock) {
            val service = nsd ?: return@synchronized null
            val request =
                NsdServiceInfo().apply {
                    this.serviceName = serviceName
                    serviceType = kind.mdnsType
                }
            var result: NsdServiceInfo? = null
            val latch = CountDownLatch(1)
            try {
                service.resolveService(
                    request,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.d(TAG, "resolve '$serviceName' failed: $errorCode")
                            latch.countDown()
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            result = serviceInfo
                            latch.countDown()
                        }
                    }
                )
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "resolveService failed", e)
                return null
            }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) Log.d(TAG, "resolve '$serviceName' timed out")
            result
        }

    private fun toDiscovered(kind: AppleTvServiceKind, info: NsdServiceInfo): DiscoveredAppleTv? {
        val address =
            if (Build.VERSION.SDK_INT >= 34) {
                info.hostAddresses.let { list -> list.firstOrNull { it is Inet4Address } ?: list.firstOrNull() }
            } else {
                info.host
            }
        val host = address?.hostAddress ?: return null
        // Companion's TXT record carries the model under `rpMd`; AirPlay's own carries it under
        // the plain `model` key (e.g. `model=AppleTV11,1`). The MRP service advertises neither.
        val modelKey = if (kind == AppleTvServiceKind.AirPlay) "model" else "rpMd"
        val model = info.attributes[modelKey]?.toString(Charsets.UTF_8).orEmpty()
        return DiscoveredAppleTv(kind = kind, serviceName = info.serviceName, host = host, port = info.port, model = model)
    }
}
