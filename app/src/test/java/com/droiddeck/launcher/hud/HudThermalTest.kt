package com.droiddeck.launcher.hud

import android.content.Context
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.droiddeck.launcher.R

/**
 * The HUD shows Android's thermal state ("thermal: severe") on the battery row, as the v0.5.4 HUD did
 * (PerfHud, c2e3aef79). FusionHUD replaced that HUD and dropped it; these tests keep it from being lost again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HudThermalTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val power get() = shadowOf(app.getSystemService(Context.POWER_SERVICE) as PowerManager)

    private fun withThermal(status: Int?): HudMetrics.Snapshot {
        val b = BASE
        return HudMetrics.Snapshot(b.cpuPercent, b.gpuPercent, b.cpuClockMhz, b.gpuClockMhz, b.cpuTempC, b.gpuTempC,
            b.ramUsedBytes, b.ramTotalBytes, b.ramPercent, b.vramUsedBytes, b.battery, b.perCorePercent, b.perCoreClockMhz,
            b.swapUsedBytes, b.swapTotalBytes, b.netDownBps, b.netUpBps, status)
    }

    private fun lines(size: DroidDeckHudSize, status: Int?, vararg settings: Pair<String, String>): List<String> =
        hud(size, status, *settings).linesForTest()

    private fun hud(size: DroidDeckHudSize, status: Int?, vararg settings: Pair<String, String>): DroidDeckHudView {
        val view = DroidDeckHudView(app)
        view.applyConfig(DroidDeckHudConfig.encode(DroidDeckHudConfig.DEFAULTS + ("hudSize" to size.token) + ("hudColor" to "vivid") + settings))
        view.showForTest(withThermal(status), "DXVK", 0L)
        return view
    }

    private val sizesWithBatteryRow = listOf(DroidDeckHudSize.FULL, DroidDeckHudSize.TILES, DroidDeckHudSize.PILL, DroidDeckHudSize.MEGA)

    @Test fun snapshotCarriesTheAndroidThermalStatus() {
        val metrics = HudMetrics(app)
        for (status in 0..5) {
            power.setCurrentThermalStatus(status)
            assertEquals("status $status", status, metrics.snapshot().thermalStatus)
        }
    }

    @Test fun theListenerKeepsTheCachedStatusCurrentAndStopsWhenTheHudStops() {
        val metrics = HudMetrics(app)
        power.setCurrentThermalStatus(1)
        metrics.startThermal()
        assertEquals(1, power.thermalStatusListeners.size)
        assertEquals(1, metrics.snapshot().thermalStatus)
        power.setCurrentThermalStatus(4) // delivered to the listener, not read again
        assertEquals(4, metrics.snapshot().thermalStatus)
        metrics.stopThermal()
        assertEquals(0, power.thermalStatusListeners.size)
        power.setCurrentThermalStatus(2)
        assertEquals("falls back to a plain read", 2, metrics.snapshot().thermalStatus)
    }

    @Test fun everySizeWithABatteryRowSaysTheThermalState() {
        val names = listOf("none", "light", "moderate", "severe", "critical", "emergency")
        for (size in sizesWithBatteryRow) for ((status, name) in names.withIndex()) {
            val text = lines(size, status).joinToString("\n")
            assertTrue("$size $status: $text", "thermal: $name" in text)
        }
    }

    @Test fun theThermalPartSharesTheBatteryRowAndHasItsOwnSwitch() {
        val pill = lines(DroidDeckHudSize.PILL, 3, "showBattery" to "1")
        assertTrue(pill.toString(), pill.any { "BAT 79% · 34°C · 16.0W · thermal: severe" in it })
        val full = lines(DroidDeckHudSize.FULL, 3, "showBattery" to "1")
        assertTrue(full.toString(), full.any { "BAT" in it && "79%" in it && "thermal: severe" in it })

        for (size in sizesWithBatteryRow) {
            assertTrue("$size off", lines(size, 3, "showThermal" to "0").none { "thermal" in it })
            assertTrue("$size no reading", lines(size, null).none { "thermal" in it })
        }
        // Its own switch: still shown with every other battery part off.
        val only = lines(DroidDeckHudSize.PILL, 2, "showBattery" to "0", "showBatteryTemp" to "0", "showPower" to "0")
        assertTrue(only.toString(), only.any { "BAT thermal: moderate" in it })
        assertEquals("on by default", "1", DroidDeckHudConfig.DEFAULTS["showThermal"])
    }

    @Test fun theColourFollowsTheSeverity() {
        val dim = 0xFF9AA4B2.toInt()
        for (size in sizesWithBatteryRow) {
            fun colour(status: Int) = hud(size, status).glyphColorForTest("thermal:")
            assertEquals("$size none", dim, colour(0))
            assertEquals("$size light", HudMetrics.TEMP_COLOR_AMBER, colour(1))
            assertEquals("$size moderate", HudMetrics.TEMP_COLOR_AMBER, colour(2))
            for (s in 3..5) assertEquals("$size $s", HudMetrics.TEMP_COLOR_RED, colour(s))
        }
    }

    @Test fun theChipIsOfferedWhereTheBatteryRowIs() {
        for (size in sizesWithBatteryRow) assertTrue("$size", "Thermal" in size.supportedChips())
        assertTrue("Thermal" !in DroidDeckHudSize.MINIMAL.supportedChips())
    }

    @Test fun theLabelsAreTranslated() {
        val ids = listOf(R.string.hud_thermal_none, R.string.hud_thermal_light, R.string.hud_thermal_moderate,
            R.string.hud_thermal_severe, R.string.hud_thermal_critical, R.string.hud_thermal_emergency, R.string.hud_metric_thermal)
        val english = ids.map { app.getString(it) }
        assertEquals("thermal: severe", app.getString(R.string.hud_thermal, "severe"))
        for (locale in listOf("zh-rCN", "zh-rTW", "zh-rHK", "ko")) {
            RuntimeEnvironment.setQualifiers("+$locale")
            val res = RuntimeEnvironment.getApplication().resources
            val shown = ids.map { res.getString(it) }
            assertTrue("$locale: $shown", shown.zip(english).all { (a, b) -> a != b })
            assertTrue(locale, res.getString(R.string.hud_thermal, "X").contains("X"))
        }
    }

    private companion object {
        val BASE = HudMetrics.Snapshot(64, 52, 3302, 1050, 89, 79, 10_800_000_000L, 16_000_000_000L, 67f,
            1_800_000_000L, HudMetrics.Battery(16f, false, 79, 34, null),
            intArrayOf(91, 91, 48, 50, 48, 19, 17, 73), intArrayOf(2265, 2265, 3148, 3148, 3148, 2956, 2956, 3302),
            2_700_000_000L, 6_400_000_000L, 31_000L, 9_000L)
    }
}
