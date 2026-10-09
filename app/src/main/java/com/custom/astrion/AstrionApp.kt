package com.custom.astrion

import android.app.Application
import androidx.core.content.edit
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.cards.impl.AppleTvRemoteCard
import com.custom.astrion.cards.impl.ButtonGridCard
import com.custom.astrion.cards.impl.CameraCard
import com.custom.astrion.cards.impl.ClimateCard
import com.custom.astrion.cards.impl.ClockWeatherCard
import com.custom.astrion.cards.impl.CoverCard
import com.custom.astrion.cards.impl.FanCard
import com.custom.astrion.cards.impl.LightCard
import com.custom.astrion.cards.impl.MediaPlayerCard
import com.custom.astrion.cards.impl.MonitorCard
import com.custom.astrion.cards.impl.PictureElementsCard
import com.custom.astrion.cards.impl.PlexCard
import com.custom.astrion.cards.impl.RowCard
import com.custom.astrion.cards.impl.SceneGridCard
import com.custom.astrion.cards.impl.SelectCard
import com.custom.astrion.cards.impl.SourceSelectCard
import com.custom.astrion.cards.impl.SpeakerGroupCard
import com.custom.astrion.cards.impl.SwitchCard
import com.custom.astrion.cards.impl.TitleCard
import com.custom.astrion.cards.impl.TvRemoteCard
import com.custom.astrion.cards.impl.VacuumCard
import com.custom.astrion.config.ActiveActivityStore
import com.custom.astrion.config.DeviceStateStore
import com.custom.astrion.ha.HaLabels
import com.custom.astrion.update.UpdateChecker

/**
 * App entry point. Register all card types here once at startup.
 *
 * To add a brand-new native card type:
 *   1. Create a class implementing CardRenderer (see cards/impl/ for examples).
 *   2. Add one line below.
 *   3. Reference it in DashboardConfig with its `type` string.
 *
 * That's the whole extension model — no re-patching anyone's APK, no fixed
 * taxonomy of 11 types.
 */
@Suppress("SpellCheckingInspection")
class AstrionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HaLabels.init(this)
        installDeviceStateStore()
        // Off the main thread: a cache listing is cheap, but startup is not
        // the place for any disk I/O that can wait.
        Thread { UpdateChecker.cleanupStaleDownloads(this) }.start()
        CardRegistry.register(
            AppleTvRemoteCard(),
            ButtonGridCard(),
            CameraCard(),
            ClimateCard(),
            ClockWeatherCard(),
            CoverCard(),
            SelectCard(),
            FanCard(),
            LightCard(),
            MediaPlayerCard(),
            MonitorCard(),
            PictureElementsCard(),
            PlexCard(),
            RowCard(),
            SceneGridCard(),
            SourceSelectCard(),
            SpeakerGroupCard(),
            SwitchCard(),
            TitleCard(),
            TvRemoteCard(),
            VacuumCard()
            // ← Register your own card types here
        )
    }

    /** Persisted per-device on/off + input memory, and the active Activity per room, for Harmony-style
     * Activities — see DeviceStateStore and ActiveActivityStore. */
    private fun installDeviceStateStore() {
        val prefs = getSharedPreferences(DEVICE_STATE_PREFS, MODE_PRIVATE)
        DeviceStateStore.shared =
            DeviceStateStore(DeviceStateStore.deserialize(prefs.getString(DEVICE_STATE_KEY, null))) { json ->
                prefs.edit { putString(DEVICE_STATE_KEY, json) }
            }
        // Which Activity is active per room — same prefs file, see ActiveActivityStore.
        ActiveActivityStore.shared =
            ActiveActivityStore(ActiveActivityStore.deserialize(prefs.getString(ACTIVE_ACTIVITIES_KEY, null))) { json ->
                prefs.edit { putString(ACTIVE_ACTIVITIES_KEY, json) }
            }
    }

    private companion object {
        const val DEVICE_STATE_PREFS = "astrion_device_states"
        const val DEVICE_STATE_KEY = "states"
        const val ACTIVE_ACTIVITIES_KEY = "active_activities"
    }
}
