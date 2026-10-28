package com.custom.astrion.config

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The remote's full backup (1.3.9): settings keep their types, files come back, nothing escapes the folder. */
class BackupArchiveTest {
    private val settings =
        mapOf(
            "ha_url" to "http://192.168.1.15:8123",
            "config_server_enabled" to true,
            "count" to 3,
            "installed_at" to 1_761_000_000_000L,
            "scale" to 1.5f,
            "tags" to setOf("b", "a")
        )

    @Test
    fun `settings keep their type both ways`() {
        assertEquals(settings, BackupArchive.prefsFromJson(BackupArchive.prefsToJson(settings)))
    }

    @Test
    fun `a whole backup comes back, logs left out`() {
        val root = Files.createTempDirectory("astrion").toFile()
        File(root, "dashboard.json").writeText("""{"pages":[]}""")
        File(root, "icons").mkdirs()
        File(root, "icons/tv.png").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "logs").mkdirs()
        File(root, "logs/astrion-log-1.txt").writeText("log")
        val zip = File.createTempFile("backup", ".zip")
        val manifest = buildJsonObject {
            put("format", BackupArchive.FORMAT)
            put("version", BackupArchive.VERSION)
        }
        zip.outputStream().use { BackupArchive.write(it, manifest, mapOf("astrion_settings" to BackupArchive.prefsToJson(settings)), root) }

        val contents = BackupArchive.open(zip)
        assertEquals(2, contents.fileCount)
        assertEquals(settings, contents.prefs["astrion_settings"])

        val target = Files.createTempDirectory("restored").toFile()
        assertEquals(2, BackupArchive.restoreFiles(zip, target))
        assertEquals("""{"pages":[]}""", File(target, "dashboard.json").readText())
        assertTrue(File(target, "icons/tv.png").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertFalse(File(target, "logs").exists())
    }

    @Test
    fun `not a backup, or a newer one, is refused`() {
        val notOne = File.createTempFile("notabackup", ".zip").apply { writeText("hello") }
        try {
            BackupArchive.open(notOne)
            fail()
        } catch (_: BackupException) {
        }
        val root = Files.createTempDirectory("astrion").toFile()
        val newer = File.createTempFile("backup", ".zip")
        val manifest = buildJsonObject {
            put("format", BackupArchive.FORMAT)
            put("version", JsonPrimitive(BackupArchive.VERSION + 1))
        }
        newer.outputStream().use { BackupArchive.write(it, manifest, emptyMap(), root) }
        try {
            BackupArchive.open(newer)
            fail()
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("newer"))
        }
    }

    @Test
    fun `no file can land outside the folder`() {
        val root = Files.createTempDirectory("astrion").toFile()
        assertNull(BackupArchive.safeTarget(root, "../evil.txt"))
        assertNull(BackupArchive.safeTarget(root, "icons/../../evil.txt"))
        assertNull(BackupArchive.safeTarget(root, "/etc/passwd"))
        assertEquals(File(root.canonicalFile, "icons/a.png"), BackupArchive.safeTarget(root, "icons/a.png"))
    }
}
