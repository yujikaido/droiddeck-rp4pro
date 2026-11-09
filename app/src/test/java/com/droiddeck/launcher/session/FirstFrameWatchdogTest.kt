package com.droiddeck.launcher.session

import com.droiddeck.launcher.session.FirstFrameWatchdog.Compositor
import com.droiddeck.launcher.session.FirstFrameWatchdog.Stage
import com.droiddeck.launcher.session.FirstFrameWatchdog.Stuck
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FirstFrameWatchdogTest {
    private val everything = setOf("com.droiddeck.launcher", "proot", "gamescope", "Xwayland", "steam", "steamwebhelper")
    private val connected = Compositor(gamescope = 1, gpuFrames = 0, refused = 0, cpuCopies = 0, onScreen = 0)

    /** Galaxy A55, v0.5.4.1: Xwayland in vkGetSemaphoreFdKHR for 400 s, as the bridge's report says it. */
    @Test fun aCallTheDriverHasNotReturnedFromIsNamed() {
        val json = """
            {"malibridge_report": 1, "processes": [
              {"pid": 15355, "name": "gamescope", "alive": true, "stuck_call": null},
              {"pid": 15433, "name": "Xwayland", "alive": true,
               "stuck_call": {"call": "vkGetSemaphoreFdKHR", "s": 401.2, "gpu_wait": false}},
              {"pid": 15434, "name": "Xwayland", "alive": true,
               "stuck_call": {"call": "vkWaitSemaphores", "s": 12.0, "gpu_wait": true}},
              {"pid": 15382, "name": "droiddeck-vkbri", "alive": false,
               "stuck_call": {"call": "vkCreateDevice", "s": 99.0, "gpu_wait": false}},
              {"pid": 15854, "name": "steam", "alive": true}
            ]}
        """.trimIndent()
        val stuck = FirstFrameWatchdog.stuckCalls(json)
        assertEquals(
            listOf(Stuck("Xwayland", "vkGetSemaphoreFdKHR", 401, false), Stuck("Xwayland", "vkWaitSemaphores", 12, true)),
            stuck,
        )
        val v = FirstFrameWatchdog.diagnose(everything, true, connected, stuck)
        assertEquals(Stage.GPU_WAIT, v.stage)
        assertEquals(Stuck("Xwayland", "vkGetSemaphoreFdKHR", 401, false), v.stuck)
        assertEquals(
            "no first frame after 60 s: gpu_wait (Xwayland in vkGetSemaphoreFdKHR for 401 s)",
            FirstFrameWatchdog.logLine(v, 60),
        )
    }

    @Test fun aReportWithoutStuckCallsOrUnreadableNamesNone() {
        assertEquals(emptyList<Stuck>(), FirstFrameWatchdog.stuckCalls(null))
        assertEquals(emptyList<Stuck>(), FirstFrameWatchdog.stuckCalls(""))
        assertEquals(emptyList<Stuck>(), FirstFrameWatchdog.stuckCalls("{\"processes\": [ {\"name\": "))
        assertEquals(emptyList<Stuck>(), FirstFrameWatchdog.stuckCalls("{\"malibridge_report\": 1}"))
        // a report from an older bridge has no stuck_call at all
        assertEquals(emptyList<Stuck>(), FirstFrameWatchdog.stuckCalls("{\"processes\": [{\"name\": \"steam\", \"alive\": true}]}"))
    }

    /** DroidDeck #1655 (Galaxy Tab A11+): gamescope's frames came in, the compositor's driver refused every one. */
    @Test fun framesTheScreenRefusesAreSaidSo() {
        val refused = Compositor(gamescope = 1, gpuFrames = 412, refused = 412, cpuCopies = 0, onScreen = 0)
        assertEquals(Stage.SCREEN_REFUSED, FirstFrameWatchdog.diagnose(everything, true, refused, emptyList()).stage)
        // copied by the CPU instead: not refused (the first frame comes)
        val copied = refused.copy(refused = 0, cpuCopies = 412)
        assertEquals(Stage.STEAM_WINDOW, FirstFrameWatchdog.diagnose(everything, true, copied, emptyList()).stage)
    }

    @Test fun eachStageInTheOrderTheStartGoesThrough() {
        assertEquals(Stage.GAMESCOPE, FirstFrameWatchdog.diagnose(everything - "gamescope", true, connected, emptyList()).stage)
        assertEquals(Stage.GAMESCOPE, FirstFrameWatchdog.diagnose(everything, true, connected.copy(gamescope = 0), emptyList()).stage)
        assertEquals(Stage.XWAYLAND, FirstFrameWatchdog.diagnose(everything - "Xwayland", true, connected, emptyList()).stage)
        assertEquals(Stage.XWAYLAND, FirstFrameWatchdog.diagnose(everything, false, connected, emptyList()).stage)
        assertEquals(Stage.STEAM_PROCESS, FirstFrameWatchdog.diagnose(everything - "steam" - "steamwebhelper", true, connected, emptyList()).stage)
        assertEquals(Stage.STEAM_UI, FirstFrameWatchdog.diagnose(everything - "steamwebhelper", true, connected, emptyList()).stage)
        assertEquals(Stage.STEAM_WINDOW, FirstFrameWatchdog.diagnose(everything, true, connected, emptyList()).stage)
        // a stuck call comes first, whatever else is missing
        val stuck = listOf(Stuck("gamescope", "vkAllocateMemory", 30, false))
        assertEquals(Stage.GPU_WAIT, FirstFrameWatchdog.diagnose(everything - "steam", true, connected, stuck).stage)
        // gamescope's own name with a suffix (gamescope-wl) counts
        assertEquals(Stage.STEAM_WINDOW, FirstFrameWatchdog.diagnose(everything - "gamescope" + "gamescope-wl", true, connected, emptyList()).stage)
    }

    @Test fun whatCannotBeReadIsNotHeldAgainstTheStart() {
        // no /proc, no session log, no compositor counters: nothing points at a stage before Steam's window
        assertEquals(Stage.STEAM_WINDOW, FirstFrameWatchdog.diagnose(null, null, null, emptyList()).stage)
        assertEquals(Stage.XWAYLAND, FirstFrameWatchdog.diagnose(null, false, null, emptyList()).stage)
        assertEquals(Stage.GAMESCOPE, FirstFrameWatchdog.diagnose(null, null, connected.copy(gamescope = 0), emptyList()).stage)
        assertNull(Compositor.of(IntArray(8), 0))
        assertNull(Compositor.of(IntArray(4), 4))
        assertEquals(Compositor(1, 2, 3, 4, 5), Compositor.of(intArrayOf(1, 2, 3, 4, 5, 6), 6))
    }

    @Test fun processesAreTheirCommandsBaseNames() {
        val proc = Files.createTempDirectory("proc").toFile()
        try {
            fun process(pid: String, vararg argv: String) {
                File(proc, pid).mkdirs()
                File(proc, "$pid/cmdline").writeBytes(argv.joinToString("") { "$it\u0000" }.toByteArray())
            }
            process("1200", "/usr/bin/Xwayland", ":0", "-rootless")
            process("1300", "/root/.local/share/Steam/steamrtarm64/steam", "-gamepadui")
            process("1301", "/root/.local/share/Steam/ubuntu12_64/steamwebhelper")
            process("1400", "gamescope", "--")
            process("1500") // a kernel thread: empty command line
            File(proc, "self").mkdirs()
            assertEquals(setOf("Xwayland", "steam", "steamwebhelper", "gamescope"), FirstFrameWatchdog.processNames(proc))
            assertNull(FirstFrameWatchdog.processNames(File(proc, "1500")))
            assertNull(FirstFrameWatchdog.processNames(File(proc, "missing")))
        } finally {
            proc.deleteRecursively()
        }
    }

    @Test fun gamescopesWindowManagerReachingXwayland() {
        assertNull(FirstFrameWatchdog.xwmUp(null))
        assertEquals(false, FirstFrameWatchdog.xwmUp("[gamescope] [Info]  wlserver: Starting Xwayland on :0\n"))
        assertEquals(
            true,
            FirstFrameWatchdog.xwmUp("[gamescope] [\u001b[0;34mInfo\u001b[0m]  \u001b[0;37mxwm:\u001b[0m Embedded, no cursor set.\n"),
        )
    }

    @Test fun theLimitLeavesSteamTimeToStart() {
        assertTrue(FirstFrameWatchdog.LIMIT_S >= 45)
        assertTrue(FirstFrameWatchdog.EVERY_S in 1..10)
    }
}
