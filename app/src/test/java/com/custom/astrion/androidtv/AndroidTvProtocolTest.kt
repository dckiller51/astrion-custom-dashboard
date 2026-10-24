package com.custom.astrion.androidtv

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android TV Remote v2 wire format. Expected bytes come from the reference protobuf
 * definitions (androidtvremote2's polo.proto / remotemessage.proto, serialized by Google's
 * protobuf library), so a wrong field number or type here fails the test.
 */
class AndroidTvProtocolTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    // ---- Messages we send ---------------------------------------------------------------------

    @Test
    fun `pairing messages match the reference encoding`() {
        assertArrayEquals(
            hex("080210c801521b0a0961747672656d6f7465120e41737472696f6e20437573746f6d"),
            PoloMessages.pairingRequest("Astrion Custom")
        )
        assertArrayEquals(hex("080210c801a201080a04080310061801"), PoloMessages.options())
        assertArrayEquals(hex("080210c801f201080a04080310061001"), PoloMessages.configuration())
        assertArrayEquals(hex("080210c801c202050a03010203"), PoloMessages.secret(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `control messages match the reference encoding`() {
        assertArrayEquals(hex("0a1c08e604121718012201312a0961747672656d6f74653205312e302e30"), RemoteMessages.configure(614))
        assertArrayEquals(hex("120308e604"), RemoteMessages.setActive(614))
        assertArrayEquals(hex("4a020805"), RemoteMessages.pingResponse(5))
        assertArrayEquals(hex("520408131003"), RemoteMessages.keyInject(19))
        assertArrayEquals(
            hex("d205260a246d61726b65743a2f2f6c61756e63683f69643d636f6d2e6e6574666c69782e6e696e6a61"),
            RemoteMessages.appLinkLaunch("market://launch?id=com.netflix.ninja")
        )
    }

    // ---- Messages we receive ------------------------------------------------------------------

    @Test
    fun `volume and foreground app are read from the TV's messages`() {
        val volume = ProtoMessage.parse(hex("920306306438194001")).message(RemoteMessages.SET_VOLUME_LEVEL)
        assertEquals(100, volume.int(RemoteMessages.VOLUME_MAX))
        assertEquals(25, volume.int(RemoteMessages.VOLUME_LEVEL))
        assertTrue(volume.bool(RemoteMessages.VOLUME_MUTED))
        val ime = ProtoMessage.parse(hex("a201150a136211636f6d2e6e6574666c69782e6e696e6a61"))
        assertEquals(
            "com.netflix.ninja",
            ime.message(RemoteMessages.IME_KEY_INJECT).message(RemoteMessages.APP_INFO).string(RemoteMessages.APP_PACKAGE)
        )
    }

    @Test
    fun `length-prefixed framing round trips, including large varints`() {
        val big = ByteArray(300) { it.toByte() }
        val out = ByteArrayOutputStream()
        ProtoWriter.writeDelimited(out, big)
        ProtoWriter.writeDelimited(out, RemoteMessages.pingResponse(5))
        val input = ByteArrayInputStream(out.toByteArray())
        assertArrayEquals(big, ProtoMessage.readDelimited(input))
        assertEquals(5, ProtoMessage.parse(ProtoMessage.readDelimited(input)).message(9).int(1))
    }

    // ---- Keys -----------------------------------------------------------------------------------

    @Test
    fun `key names are accepted in every usual spelling`() {
        assertEquals(19, AndroidTvKeys.code("DPAD_UP"))
        assertEquals(19, AndroidTvKeys.code("KEYCODE_DPAD_UP"))
        assertEquals(19, AndroidTvKeys.code("dpad_up"))
        assertEquals(19, AndroidTvKeys.code("UP"))
        assertEquals(23, AndroidTvKeys.code("Select"))
        assertEquals(164, AndroidTvKeys.code("VOLUME_MUTE"))
        // The TV remote card's mute button: the sound, not KEYCODE_MUTE (microphone).
        assertEquals(164, AndroidTvKeys.code("MUTE"))
        assertEquals(91, AndroidTvKeys.code("91"))
        assertEquals(166, AndroidTvKeys.code("CHANNEL_UP"))
        assertEquals(26, AndroidTvKeys.code("26"))
        assertNull(AndroidTvKeys.code("NOT_A_KEY"))
    }

    // ---- Identity and pairing -------------------------------------------------------------------

    @Test
    fun `generated identity is a valid self-signed certificate that survives storage`() {
        val identity = AndroidTvIdentity.generate()
        identity.certificate.checkValidity()
        identity.certificate.verify(identity.certificate.publicKey)
        assertEquals("CN=atvremote", identity.certificate.subjectX500Principal.name)
        val restored = AndroidTvIdentity.parse(identity.serialize())
        assertNotNull(restored)
        assertEquals(identity.certificate, restored!!.certificate)
        assertArrayEquals(identity.privateKey.encoded, restored.privateKey.encoded)
    }

    @Test
    fun `pairing code is checked against the secret's first byte`() {
        val client = AndroidTvIdentity.generate().certificate
        val server = AndroidTvIdentity.generate().certificate
        // Find the code the "TV" would show for suffix 1A2B: its first byte is the hash's first byte.
        val probe = (0..255).firstNotNullOf { b ->
            val code = "%02X1A2B".format(b)
            AndroidTvPairing.secret(client, server, code)?.let { code to it }
        }
        val (code, secret) = probe
        assertEquals(32, secret.size)
        assertArrayEquals(secret, AndroidTvPairing.secret(client, server, code.lowercase()))
        val wrongFirst = "%02X".format((code.substring(0, 2).toInt(16) + 1) % 256) + code.substring(2)
        assertNull(AndroidTvPairing.secret(client, server, wrongFirst))
        assertNull(AndroidTvPairing.secret(client, server, "12345"))
        assertNull(AndroidTvPairing.secret(client, server, "12345G"))
    }

    @Test
    fun `TV name and MAC are read from its certificate`() {
        val shield = AndroidTvIdentity.generate("atvremote/darcy/darcy/SHIELD Android TV/AA:BB:CC:DD:EE:FF").certificate
        assertEquals("SHIELD Android TV" to "AA:BB:CC:DD:EE:FF", AndroidTvPairing.nameAndMac(shield))
        val plain = AndroidTvIdentity.generate("atvremote").certificate
        assertEquals("atvremote" to "", AndroidTvPairing.nameAndMac(plain))
    }
}
