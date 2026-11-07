package com.custom.astrion.web

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The icons' folders (1.5.2): one per direct device, paths kept inside the icons folder. */
class IconFoldersTest {
    private val root: File = Files.createTempDirectory("icons").toFile()

    @Test
    fun `a device's folder is listed after the top icons, and paths can't leave the icons folder`() {
        File(root, "tv.png").writeBytes(byteArrayOf(1))
        File(root, "LG OLED 65B8").mkdirs()
        File(root, "LG OLED 65B8/Netflix.png").writeBytes(byteArrayOf(1))
        File(root, "LG OLED 65B8/Disney+.png").writeBytes(byteArrayOf(1))
        assertEquals(listOf("tv.png", "LG OLED 65B8/Disney+.png", "LG OLED 65B8/Netflix.png"), IconFolders.list(root))
        assertEquals(
            File(root, "LG OLED 65B8/Netflix.png").canonicalPath,
            IconFolders.resolve(root, "LG OLED 65B8/Netflix.png")!!.canonicalPath
        )
        assertNull(IconFolders.resolve(root, "../secret.png"))
        assertNull(IconFolders.resolve(root, "a/b/c.png"))
        assertNull(IconFolders.resolve(root, ""))
        assertEquals("Canal_ _ S_ries", IconFolders.safe("Canal/ : Séries"))
    }
}
