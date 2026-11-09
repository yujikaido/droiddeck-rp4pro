package com.droiddeck.launcher.hud

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * On GPUs whose load, clock and temperature the app can't read (most Malis) the HUD showed a GPU row of
 * dashes; it now leaves the row out. A GPU with any reading keeps it. The CPU row reads load, clock, then
 * temperature.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HudUnreadableGpuTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun snapshot(gpuPercent: Int?, gpuClock: Int?, gpuTemp: Int?) = HudMetrics.Snapshot(64, gpuPercent, 3302, gpuClock,
        89, gpuTemp, 10_800_000_000L, 16_000_000_000L, 67f, null, HudMetrics.Battery(16f, false, 79, 34, null),
        intArrayOf(91, 91, 48, 50, 48, 19, 17, 73), intArrayOf(2265, 2265, 3148, 3148, 3148, 2956, 2956, 3302),
        2_700_000_000L, 6_400_000_000L, 31_000L, 9_000L, null)

    private fun lines(size: DroidDeckHudSize, s: HudMetrics.Snapshot): List<String> {
        val view = DroidDeckHudView(app)
        view.applyConfig(DroidDeckHudConfig.encode(DroidDeckHudConfig.DEFAULTS + ("hudSize" to size.token) +
            ("hudColor" to "vivid") + ("showGpuModel" to "0")))
        view.showForTest(s, "DXVK", 0L)
        return view.linesForTest()
    }

    private val sizes = listOf(DroidDeckHudSize.FULL, DroidDeckHudSize.TILES, DroidDeckHudSize.PILL, DroidDeckHudSize.MEGA)

    @Test fun noGpuReadingMeansNoGpuRow() {
        for (size in sizes) {
            val shown = lines(size, snapshot(null, null, null))
            assertTrue("$size: $shown", shown.none { "GPU" in it })
            assertTrue("$size still has the CPU: $shown", shown.any { "CPU" in it })
        }
    }

    @Test fun anyGpuReadingKeepsTheRow() {
        for (size in sizes) {
            val shown = lines(size, snapshot(52, null, null))
            // Tiles draw the heading and the value on separate lines.
            assertTrue("$size load: $shown", shown.any { "GPU" in it } && shown.any { "52" in it })
        }
        for (size in listOf(DroidDeckHudSize.FULL, DroidDeckHudSize.TILES, DroidDeckHudSize.MEGA)) {
            assertTrue("$size clock only", lines(size, snapshot(null, 1050, null)).any { "GPU" in it })
        }
    }

    @Test fun theCpuClockComesBeforeItsTemperature() {
        for (size in listOf(DroidDeckHudSize.FULL, DroidDeckHudSize.MEGA)) {
            val cpu = lines(size, snapshot(52, 1050, 79)).first { it.contains("CPU") && it.contains("3302") }
            val tail = cpu.substring(cpu.indexOf("CPU"))
            assertTrue("$size: $cpu", tail.indexOf("3302") in 0 until tail.indexOf("89°"))
        }
        val tiles = lines(DroidDeckHudSize.TILES, snapshot(52, 1050, 79))
        val sub = tiles.firstOrNull { "3302MHz" in it } ?: ""
        assertTrue(tiles.toString(), sub.indexOf("3302MHz") in 0 until sub.indexOf("89°"))
    }
}
