package com.custom.astrion.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a composed Activity sends, for the cases that broke or could break:
 * Home Assistant devices, toggle-only IR devices, switching Activities in a
 * room, stopping. Pure JVM: no Android, no network.
 */
class ActivityPlannerTest {
    // ---- Helpers --------------------------------------------------------------------------------

    private fun ha(id: String, input: String? = null, powerOnFirst: Boolean = true, delayAfterMs: Int = 0) =
        ActivityDeviceConfig(deviceId = id, source = "ha", inputCommand = input, powerOnFirst = powerOnFirst, delayAfterMs = delayAfterMs)

    private fun ir(id: String, input: String? = null, on: String? = null, off: String? = null, offOnExit: Boolean = true) =
        ActivityDeviceConfig(
            deviceId = id,
            source = "ir",
            inputCommand = input,
            powerOnCommand = on,
            powerOffCommand = off,
            powerOffOnExit = offOnExit
        )

    private fun activity(id: String, vararg devices: ActivityDeviceConfig, room: String = "Living Room") =
        ActivityConfig(id = id, name = id, room = room, devices = devices.toList())

    private fun planner(
        states: Map<String, DeviceState> = emptyMap(),
        profiles: Map<String, IrDeviceProfile> = emptyMap(),
        ha: Map<String, LiveHaState> = emptyMap(),
        keepOn: Set<String> = emptySet()
    ) = ActivityPlanner(profileOf = { profiles[it.deviceId] }, states = DeviceStateStore(states), haState = { ha[it] }, keepOn = keepOn)

    private fun ActivityPlanner.start(
        incoming: ActivityConfig,
        others: List<ActivityConfig> = emptyList(),
        outgoing: ActivityConfig? = null
    ) = planStart(
        incoming = incoming,
        roomDevices = (others + incoming).flatMap { it.devices },
        outgoingDevices = outgoing?.devices?.map { it.deviceId }?.toSet().orEmpty(),
        outgoingConfigs = outgoing?.devices.orEmpty()
    )

    /** "turn_on tv", "source tv HDMI 2", "cmd amp PowerOn": readable plans in assertions. */
    private fun ActivityPlan.describe(): List<String> = actions.map { a ->
        when (a) {
            is PlannedAction.HaPower -> "${if (a.on) "turn_on" else "turn_off"} ${a.device.deviceId}"
            is PlannedAction.HaSource -> "source ${a.device.deviceId} ${a.source}"
            is PlannedAction.Command -> "cmd ${a.device.deviceId} ${a.command}"
        }
    }

    private val toggleProfile = profile(PowerProfile(type = PowerType.TOGGLE, toggle = send("PowerToggle")))
    private val discreteProfile = profile(PowerProfile(type = PowerType.DISCRETE, on = send("PowerOn"), off = send("PowerOff")))

    private fun send(command: String) = listOf(IrMacroStep.Send(command))

    private fun profile(power: PowerProfile) = IrDeviceProfile(power = power)

    // ---- Home Assistant devices -----------------------------------------------------------------

    @Test
    fun `HA devices are turned on then switched to their input`() {
        val watchTv = activity("watch_tv", ha("media_player.tv", "HDMI 2"), ha("media_player.amp", "TV"))
        val plan = planner().start(watchTv)
        assertEquals(
            listOf("turn_on media_player.tv", "source media_player.tv HDMI 2", "turn_on media_player.amp", "source media_player.amp TV"),
            plan.describe()
        )
    }

    /** The 1.3.0–1.3.3 bug: TV and amp switched off outside Astrion were still believed on, so nothing was sent. */
    @Test
    fun `HA devices believed on still get turn_on and select_source`() {
        val believedOn =
            mapOf(
                "media_player.tv" to DeviceState(on = true, input = "HDMI 2"),
                "media_player.amp" to DeviceState(on = true, input = "TV")
            )
        val watchTv = activity("watch_tv", ha("media_player.tv", "HDMI 2"), ha("media_player.amp", "TV"))
        val plan = planner(believedOn).start(watchTv)
        assertEquals(
            listOf("turn_on media_player.tv", "source media_player.tv HDMI 2", "turn_on media_player.amp", "source media_player.amp TV"),
            plan.describe()
        )
    }

    @Test
    fun `HA device with powerOnFirst off only gets its input`() {
        val plan = planner().start(activity("a", ha("media_player.amp", "TV", powerOnFirst = false)))
        assertEquals(listOf("source media_player.amp TV"), plan.describe())
    }

    @Test
    fun `starting records the devices as on and on their input`() {
        val plan = planner().start(activity("a", ha("media_player.tv", "HDMI 2")))
        assertEquals(mapOf("media_player.tv" to true), plan.powerChanges)
        assertEquals(mapOf("media_player.tv" to "HDMI 2"), plan.inputChanges)
    }

    // ---- Home Assistant live state ------------------------------------------------------------

    @Test
    fun `HA device on and on the right source gets nothing`() {
        val live = mapOf("media_player.tv" to LiveHaState(on = true, source = "HDMI 2"))
        val plan = planner(ha = live).start(activity("a", ha("media_player.tv", "HDMI 2")))
        assertTrue(plan.describe().isEmpty())
    }

    @Test
    fun `HA device on but on another source only gets its source`() {
        val live = mapOf("media_player.tv" to LiveHaState(on = true, source = "HDMI 1"))
        val plan = planner(ha = live).start(activity("a", ha("media_player.tv", "HDMI 2")))
        assertEquals(listOf("source media_player.tv HDMI 2"), plan.describe())
    }

    /** Switched off with its own remote while Astrion still remembers it on. */
    @Test
    fun `HA device reported off is turned on whatever Astrion remembers`() {
        val live = mapOf("media_player.tv" to LiveHaState(on = false, source = "HDMI 2"))
        val plan =
            planner(mapOf("media_player.tv" to DeviceState(on = true, input = "HDMI 2")), ha = live)
                .start(activity("a", ha("media_player.tv", "HDMI 2")))
        assertEquals(listOf("turn_on media_player.tv", "source media_player.tv HDMI 2"), plan.describe())
    }

    @Test
    fun `HA device in an unknown state is turned on anyway`() {
        val live = mapOf("media_player.tv" to LiveHaState.of("unavailable", null))
        val plan = planner(ha = live).start(activity("a", ha("media_player.tv", "HDMI 2")))
        assertEquals(listOf("turn_on media_player.tv", "source media_player.tv HDMI 2"), plan.describe())
    }

    @Test
    fun `HA states map to on and off`() {
        assertEquals(false, LiveHaState.of("off", null).on)
        assertEquals(false, LiveHaState.of("standby", null).on)
        assertEquals(true, LiveHaState.of("playing", null).on)
        assertEquals(true, LiveHaState.of("idle", null).on)
        assertEquals(null, LiveHaState.of("unknown", null).on)
    }

    @Test
    fun `switching Activity doesn't switch off an HA device Home Assistant reports off`() {
        val apps = activity("netflix", ha("media_player.android_tv", "com.netflix.ninja"))
        val watchTv = activity("watch_tv", ha("media_player.bravia", "HDMI 1"))
        val live = mapOf("media_player.android_tv" to LiveHaState(on = false))
        val plan =
            planner(mapOf("media_player.android_tv" to DeviceState(on = true)), ha = live)
                .start(watchTv, others = listOf(apps), outgoing = apps)
        assertTrue(plan.describe().none { it.contains("android_tv") })
    }

    @Test
    fun `stopping doesn't switch off an HA device Home Assistant reports off`() {
        val plan =
            planner(ha = mapOf("media_player.tv" to LiveHaState(on = false)))
                .planStop(activity("a", ha("media_player.tv")))
        assertTrue(plan.describe().isEmpty())
    }

    // ---- IR devices -----------------------------------------------------------------------------

    @Test
    fun `IR device without profile, state unknown, gets its power on command and input`() {
        val plan = planner().start(activity("a", ir("tv", input = "InputHdmi1", on = "PowerOn")))
        assertEquals(listOf("cmd tv PowerOn", "cmd tv InputHdmi1"), plan.describe())
    }

    @Test
    fun `toggle-only IR device known off is toggled once`() {
        val plan =
            planner(mapOf("tv" to DeviceState(on = false)), mapOf("tv" to toggleProfile))
                .start(activity("a", ir("tv")))
        assertEquals(listOf("cmd tv PowerToggle"), plan.describe())
    }

    /** The reason the memory exists: toggling a TV that is already on would turn it off. */
    @Test
    fun `toggle-only IR device known on is not toggled`() {
        val plan =
            planner(mapOf("tv" to DeviceState(on = true)), mapOf("tv" to toggleProfile))
                .start(activity("a", ir("tv")))
        assertTrue(plan.describe().none { it.contains("PowerToggle") })
    }

    @Test
    fun `discrete IR device believed on gets its discrete on again`() {
        val plan =
            planner(mapOf("tv" to DeviceState(on = true)), mapOf("tv" to discreteProfile))
                .start(activity("a", ir("tv")))
        assertEquals(listOf("cmd tv PowerOn"), plan.describe())
    }

    @Test
    fun `IR input already selected on a device that stays on is not sent again`() {
        val plan =
            planner(mapOf("tv" to DeviceState(on = true, input = "HDMI 1")), mapOf("tv" to toggleProfile))
                .start(activity("a", ir("tv", input = "HDMI 1")))
        assertTrue(plan.describe().isEmpty())
    }

    @Test
    fun `input waits for the power-on delay of the device`() {
        val slowTv = discreteProfile.copy(timing = TimingProfile(powerOnDelayMs = 5000))
        val plan =
            planner(mapOf("tv" to DeviceState(on = false)), mapOf("tv" to slowTv))
                .start(activity("a", ir("tv", input = "InputHdmi1")))
        assertEquals(listOf("cmd tv PowerOn", "cmd tv InputHdmi1"), plan.describe())
        assertTrue(plan.actions[1].gapBeforeMs >= 5000)
    }

    // ---- Switching and stopping -----------------------------------------------------------------

    @Test
    fun `switching Activity powers off what the new one doesn't use and keeps shared devices`() {
        val watchTv = activity("watch_tv", ir("tv", on = "PowerOn", off = "PowerOff"), ir("amp", on = "AmpOn", off = "AmpOff"))
        val music = activity("music", ir("amp", on = "AmpOn", off = "AmpOff", input = "CD"))
        val states = mapOf("tv" to DeviceState(on = true), "amp" to DeviceState(on = true))
        val plan = planner(states).start(music, others = listOf(watchTv), outgoing = watchTv)
        val sent = plan.describe()
        assertTrue("tv is switched off: $sent", "cmd tv PowerOff" in sent)
        assertTrue("amp stays on: $sent", "cmd amp AmpOff" !in sent)
        assertTrue("amp goes to CD: $sent", "cmd amp CD" in sent)
    }

    @Test
    fun `a device set to stay on is not switched off by the next Activity`() {
        val watchTv = activity("watch_tv", ir("tv", on = "PowerOn", off = "PowerOff", offOnExit = false))
        val music = activity("music", ir("amp", on = "AmpOn"))
        val plan = planner(mapOf("tv" to DeviceState(on = true))).start(music, others = listOf(watchTv), outgoing = watchTv)
        assertTrue(plan.describe().none { it.startsWith("cmd tv") })
    }

    /** The reporter's setup: the Android TV entity (apps) is the same TV as the Bravia entity (HDMI). */
    @Test
    fun `a device kept on between Activities is not switched off by the next one`() {
        val netflix = activity("netflix", ha("media_player.bravia"), ha("media_player.android_tv", "com.netflix.ninja"))
        val watchTv = activity("watch_tv", ha("media_player.bravia", "HDMI 1"))
        val live =
            mapOf(
                "media_player.android_tv" to LiveHaState(on = true),
                "media_player.bravia" to LiveHaState(on = true, source = "HDMI 1")
            )
        val kept =
            planner(ha = live, keepOn = setOf("media_player.android_tv"))
                .start(watchTv, others = listOf(netflix), outgoing = netflix)
        assertTrue(kept.describe().none { it.contains("android_tv") })
        val notKept = planner(ha = live).start(watchTv, others = listOf(netflix), outgoing = netflix)
        assertTrue("without the setting it is switched off", "turn_off media_player.android_tv" in notKept.describe())
    }

    @Test
    fun `a device kept on between Activities is still switched off when its Activity is stopped`() {
        val plan = planner(keepOn = setOf("media_player.tv")).planStop(activity("a", ha("media_player.tv")))
        assertEquals(listOf("turn_off media_player.tv"), plan.describe())
    }

    @Test
    fun `stopping powers off the devices that may be on, not the ones known off`() {
        val watchTv = activity("watch_tv", ha("media_player.tv"), ir("amp", off = "AmpOff"), ir("box", off = "BoxOff"))
        val plan =
            planner(mapOf("media_player.tv" to DeviceState(on = true), "box" to DeviceState(on = false)))
                .planStop(watchTv)
        assertEquals(listOf("turn_off media_player.tv", "cmd amp AmpOff"), plan.describe())
        assertEquals(mapOf("media_player.tv" to false, "amp" to false), plan.powerChanges)
    }
}
