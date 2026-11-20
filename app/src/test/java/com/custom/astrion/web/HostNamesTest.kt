package com.custom.astrion.web

import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostNamesTest {
    @Test
    fun addressesAreNeverResolved() {
        assertTrue(HostNames.isAddress("192.168.20.7"))
        assertTrue(HostNames.isAddress("fe80::1"))
        assertFalse(HostNames.isAddress("skyq.home.lan"))
        assertNull(HostNames.unresolved("192.168.20.7") { error("not asked") })
    }

    @Test
    fun aNameThatDoesntResolveIsSaidSo() {
        val why = HostNames.unresolved("skyq.home.lan") { throw UnknownHostException(it) }
        assertEquals(HostNames.message("skyq.home.lan"), why)
        assertTrue(why!!.contains("IP address"))
    }

    @Test
    fun aNameThatResolvesIsFine() {
        assertNull(HostNames.unresolved("skyq.home.lan") { })
    }
}
