package com.custom.astrion.androidtv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** An Android TV found on the network. */
data class DiscoveredAndroidTv(val serviceName: String, val host: String)

/**
 * mDNS discovery of Android TVs (`_androidtvremote2._tcp`, advertised by every device that the
 * Google TV app can control). Same approach as the Apple TV discovery: a few short browse
 * windows with a multicast lock, then one resolve at a time. Blocking: call off the main thread.
 */
@Suppress("DEPRECATION") // resolveService(…, ResolveListener) is the only variant on API 26+.
class AndroidTvDiscovery(context: Context) {
    private val nsd: NsdManager? =
        runCatching { context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull()
    private val multicastLock: WifiManager.MulticastLock? =
        runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("astrion-androidtv-mdns")
                ?.apply { setReferenceCounted(true) }
        }.getOrNull()
    private val resolveLock = Any()

    fun scan(): List<DiscoveredAndroidTv> {
        val service = nsd ?: return emptyList()
        runCatching { multicastLock?.acquire() }
        try {
            val names = LinkedHashSet<String>()
            repeat(SCAN_BURSTS) { burst ->
                names.addAll(scanOnce(service))
                if (burst < SCAN_BURSTS - 1) Thread.sleep(SCAN_BURST_GAP_MS)
            }
            return names.mapNotNull { name -> resolve(name)?.let { DiscoveredAndroidTv(name, it) } }
        } finally {
            runCatching { multicastLock?.release() }
        }
    }

    private fun scanOnce(service: NsdManager): Set<String> {
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
                    Log.w(TAG, "start discovery failed: $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
        try {
            service.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "discoverServices failed", e)
            return emptySet()
        }
        Thread.sleep(SCAN_BURST_MS)
        runCatching { service.stopServiceDiscovery(listener) }
        return synchronized(names) { names.toSet() }
    }

    /** The IPv4 address of the TV advertising [serviceName], or null. */
    fun resolve(serviceName: String): String? = synchronized(resolveLock) {
        val service = nsd ?: return@synchronized null
        val request =
            NsdServiceInfo().apply {
                this.serviceName = serviceName
                serviceType = SERVICE_TYPE
            }
        var result: NsdServiceInfo? = null
        val latch = CountDownLatch(1)
        try {
            service.resolveService(
                request,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = latch.countDown()

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        result = serviceInfo
                        latch.countDown()
                    }
                }
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "resolveService failed", e)
            return@synchronized null
        }
        latch.await(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val info = result ?: return@synchronized null
        val address =
            if (Build.VERSION.SDK_INT >= ANDROID_14) {
                info.hostAddresses.let { list -> list.firstOrNull { it is Inet4Address } ?: list.firstOrNull() }
            } else {
                info.host
            }
        address?.hostAddress
    }

    companion object {
        private const val TAG = "AndroidTvDiscovery"
        private const val SERVICE_TYPE = "_androidtvremote2._tcp."
        private const val SCAN_BURSTS = 3
        private const val SCAN_BURST_MS = 3000L
        private const val SCAN_BURST_GAP_MS = 300L
        private const val RESOLVE_TIMEOUT_MS = 3000L
        private const val ANDROID_14 = 34
    }
}
