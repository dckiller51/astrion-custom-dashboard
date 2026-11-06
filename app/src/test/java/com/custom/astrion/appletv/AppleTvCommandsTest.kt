package com.custom.astrion.appletv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The Android TV key names the TV remote card and hotkeys send to remote.appletv_… (1.4.9). */
class AppleTvCommandsTest {
    @Test
    fun `Android TV key names become the Apple TV's own`() {
        assertEquals("Up", AppleTvCommands.fromAndroidName("DPAD_UP"))
        assertEquals("Select", AppleTvCommands.fromAndroidName("KEYCODE_DPAD_CENTER"))
        assertEquals("Menu", AppleTvCommands.fromAndroidName("back"))
        assertEquals("PlayPause", AppleTvCommands.fromAndroidName("MEDIA_PLAY_PAUSE"))
        assertEquals("SeekForward", AppleTvCommands.fromAndroidName("MEDIA_FAST_FORWARD"))
        // An Apple TV name, or an unknown one, is kept as is.
        assertEquals("Siri", AppleTvCommands.fromAndroidName("Siri"))
        assertEquals("Wake", AppleTvCommands.fromAndroidName(" Wake "))
    }

    @Test
    fun `every translated name is a command the Apple TV takes`() {
        listOf(
            "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER", "ENTER", "BACK", "ESCAPE", "MENU",
            "HOME", "MEDIA_PLAY_PAUSE", "MEDIA_PLAY", "MEDIA_PAUSE", "MEDIA_STOP", "MEDIA_NEXT", "MEDIA_PREVIOUS",
            "MEDIA_REWIND", "MEDIA_FAST_FORWARD", "VOLUME_UP", "VOLUME_DOWN", "CHANNEL_UP", "CHANNEL_DOWN",
            "GUIDE", "SEARCH", "ASSIST"
        ).forEach { name ->
            assertNotNull(name, AppleTvCommands.forCommand(AppleTvCommands.fromAndroidName(name)))
        }
    }
}
