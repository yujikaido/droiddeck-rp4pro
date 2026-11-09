package com.droiddeck.launcher

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.geometry.Rect
import com.droiddeck.launcher.frontend.AutoComponents
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.ProtonExtras
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.LoadingState
import com.droiddeck.launcher.ui.LaunchOrigin
import com.droiddeck.launcher.ui.Motion
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ProtonMenuAutoTest {
    private val online = AutoComponents.online
    private val constrained = AutoComponents.unfavourable
    private lateinit var activity: Activity
    private lateinit var menu: ProtonMenu
    private lateinit var root: File
    private val attempts = mutableListOf<String>()
    private var idle = true

    @Before fun setup() {
        // The update mechanics below are the unpinned ones; the pin has its own tests at the end.
        ProtonExtras.validatedOwn = null
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        root = LinuxRuntime.rootDir(activity)
        for (path in listOf("usr/bin/gamescope", "usr/local/bin/droiddeck-session", "opt/android-host/proot", "opt/android-host/loader")) {
            File(root, path).apply { parentFile!!.mkdirs(); writeText("") }
        }
        AutoComponents.online = { true }
        AutoComponents.unfavourable = { false }
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
        SessionState.stopRequested = false
        LaunchOrigin.take()
        LaunchOrigin.flooding = null
        val latest = JSONObject()
        for (tool in ProtonExtras.tools) latest.put(tool.id, JSONObject()
            .put("tag", "${tool.prefix}-11-12").put("at", System.currentTimeMillis()))
        File(activity.filesDir, "proton-latest.json").writeText(latest.toString())
        menu = ProtonMenu(activity, Handler(Looper.getMainLooper())) { idle }
        menu.protonBusyId = null
        menu.protonProblem = null
        menu.protonStage = null
        menu.protonPercent = -1
        menu.installer = { tool, _, progress ->
            progress("Installing ${tool.name}", 50)
            install("${tool.prefix}-11-12")
            null
        }
    }

    @After fun reset() {
        ProtonExtras.validatedOwn = ProtonExtras.VALIDATED_OWN
        menu.protonBusyId = null
        menu.protonProblem = null
        menu.protonStage = null
        menu.protonPercent = -1
        AutoComponents.online = online
        AutoComponents.unfavourable = constrained
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
        SessionState.stopRequested = false
        LaunchOrigin.take()
        LaunchOrigin.flooding = null
    }

    private fun install(name: String): File = File(root, "root/.local/share/Steam/compatibilitytools.d/$name").apply {
        mkdirs()
        File(this, "toolmanifest.vdf").writeText("manifest")
        // a build whose name Steam knows cannot be read counts as in use and is never removed (ProtonExtras.BuildUse)
        File(this, "compatibilitytool.vdf").writeText("\"compatibilitytools\" { \"compat_tools\" { \"${name.replace(' ', '-')}\" { } } }")
    }

    private fun waitUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
        fail("Proton work did not finish")
    }

    @Test fun aCachedCheckUpdatesOnlyInstalledFamiliesAndKeepsAnOlderDroidDeckProtonThatIsInUse() {
        val older = install("DroidDeck-Proton-11-11.1")
        val request = File(root, "root/.local/share/droiddeck-compat/request.json").apply {
            parentFile!!.mkdirs()
            writeText("{\"seq\":1,\"dir\":\"DroidDeck-Proton-11-11.1\",\"valve\":false}")
        }
        val before = request.readText()
        assertFalse(ProtonExtras.checkLatest(activity))
        menu.checkUpdates()
        waitUntil { menu.protonRows.singleOrNull { it.id == "droiddeck" }?.installed == "DroidDeck-Proton-11-12" && menu.protonBusyId == null }
        // the app's default is on the older build: it stays installed and the default is not moved
        assertTrue(older.isDirectory)
        assertEquals(listOf("DroidDeck-Proton-11-11.1", "DroidDeck-Proton-11-12"), ProtonExtras.installedAll(activity, ProtonExtras.tools.first { it.id == "droiddeck" }))
        assertEquals(before, request.readText())
        assertTrue(ProtonExtras.installedAll(activity, ProtonExtras.tools.first { it.id == "ge" }).isEmpty())
        assertTrue(ProtonExtras.installedAll(activity, ProtonExtras.tools.first { it.id == "cachyos" }).isEmpty())
    }

    @Test fun downloadsWaitForTheSessionNetworkPowerAndOtherInstallWork() {
        install("DroidDeck-Proton-11-11.1")
        menu.installer = { tool, _, _ -> attempts.add(tool.id); "failed" }
        SessionState.running = true
        menu.ensureAutoUpdates()
        SessionState.running = false
        AutoComponents.online = { false }
        menu.ensureAutoUpdates()
        AutoComponents.online = { true }
        AutoComponents.unfavourable = { true }
        menu.ensureAutoUpdates()
        AutoComponents.unfavourable = { false }
        idle = false
        menu.ensureAutoUpdates()
        assertNull(menu.protonBusyId)
        assertTrue(attempts.isEmpty())
        idle = true
        menu.ensureAutoUpdates()
        waitUntil { menu.protonBusyId == null }
        assertEquals(listOf("droiddeck"), attempts)
    }

    @Test fun aFailedUpdateDoesNotLoopOrBlockOtherInstalledFamilies() {
        val ge = install("GE-Proton11-11")
        install("DroidDeck-Proton-11-11.1")
        menu.installer = { tool, _, _ ->
            attempts.add(tool.id)
            if (tool.id == "ge") throw IllegalStateException("download failed")
            install("${tool.prefix}-11-12")
            null
        }
        menu.ensureAutoUpdates()
        waitUntil { menu.protonBusyId == null }
        menu.ensureAutoUpdates()
        waitUntil { menu.protonBusyId == null }
        assertEquals(listOf("ge", "droiddeck"), attempts)
        assertTrue(ge.isDirectory)
        assertEquals(listOf("DroidDeck-Proton-11-12"), ProtonExtras.installedAll(activity, ProtonExtras.tools.first { it.id == "droiddeck" }))
        assertNull(menu.protonStage)
        assertEquals(-1, menu.protonPercent)
    }

    @Test fun anUninstalledRuntimeOrCurrentProtonDoesNotStartADownload() {
        install("DroidDeck-Proton-11-12")
        menu.installer = { tool, _, _ -> attempts.add(tool.id); null }
        menu.ensureAutoUpdates()
        assertTrue(attempts.isEmpty())
        install("GE-Proton11-11")
        File(root, "usr/bin/gamescope").delete()
        menu.ensureAutoUpdates()
        assertTrue(attempts.isEmpty())
        assertNull(menu.protonBusyId)
    }

    @Test fun aFailedReleaseIsNotRetriedAfterRecreatingTheLauncher() {
        install("DroidDeck-Proton-11-11.1")
        menu.installer = { tool, _, _ -> attempts.add(tool.id); "failed" }
        menu.ensureAutoUpdates()
        waitUntil { menu.protonBusyId == null }
        val recreated = Robolectric.buildActivity(Activity::class.java).setup().get()
        val next = ProtonMenu(recreated, Handler(Looper.getMainLooper()))
        next.installer = { tool, _, _ -> attempts.add(tool.id); "failed" }
        next.ensureAutoUpdates()
        assertNull(next.protonBusyId)
        assertEquals(listOf("droiddeck"), attempts)
    }

    @Test fun automaticUpdatesWaitForEveryNonEndedSessionPhaseEvenWhenRunningIsFalse() {
        install("DroidDeck-Proton-11-11.1")
        for (phase in SessionPhase.entries.filter { it != SessionPhase.IDLE && it != SessionPhase.FAILED }) {
            SessionState.phase = phase
            menu.ensureAutoUpdates()
            assertNull("Must wait during $phase", menu.protonBusyId)
        }
        SessionState.phase = SessionPhase.FAILED
        menu.ensureAutoUpdates()
        waitUntil { menu.protonBusyId == null }
        assertEquals("DroidDeck-Proton-11-12", ProtonExtras.installed(activity, ProtonExtras.tools.first { it.id == "droiddeck" }))
    }

    @Test fun droidDeckProtonIsInstalledWhenMissingUnlessTheUserRemovedIt() {
        menu.installer = { tool, _, _ -> attempts.add(tool.id); install("${tool.prefix}-11-12"); null }
        ProtonExtras.setDeclined(activity, true)
        menu.ensureAutoUpdates()
        assertNull(menu.protonBusyId)
        assertTrue(attempts.isEmpty())
        ProtonExtras.setDeclined(activity, false)
        menu.ensureAutoUpdates()
        waitUntil { menu.protonBusyId == null }
        assertEquals(listOf("droiddeck"), attempts)
        assertEquals("DroidDeck-Proton-11-12", ProtonExtras.installed(activity, ProtonExtras.tools.first { it.id == "droiddeck" }))
        assertTrue(ProtonExtras.installedAll(activity, ProtonExtras.tools.first { it.id == "ge" }).isEmpty())
        menu.removeProton("droiddeck")
        waitUntil { menu.protonBusyId == null }
        assertTrue(ProtonExtras.declined(activity))
        menu.installer = { _, _, _ -> "failed" }
        menu.installProton("droiddeck")
        waitUntil { menu.protonBusyId == null }
        assertTrue(ProtonExtras.declined(activity))
        menu.installer = { tool, _, _ -> install("${tool.prefix}-11-12"); null }
        menu.installProton("droiddeck")
        waitUntil { menu.protonBusyId == null }
        assertFalse(ProtonExtras.declined(activity))
    }

    @Test fun aPendingAnimatedLaunchPreventsAnAutomaticInstall() {
        install("DroidDeck-Proton-11-11.1")
        val launcher = Robolectric.buildActivity(FrontEndActivity::class.java).get()
        val protons = ReflectionHelpers.getField<ProtonMenu>(launcher, "protons")
        protons.installer = { tool, _, _ -> attempts.add(tool.id); "failed" }
        Motion.refresh(launcher)
        LaunchOrigin.mark(Rect(0f, 0f, 100f, 100f), "game")
        launcher.startActivity(Intent(launcher, SessionActivity::class.java))
        assertNotNull(ReflectionHelpers.callInstanceMethod<Any?>(launcher, "getFlood"))
        assertNull(shadowOf(launcher).nextStartedActivity)
        protons.ensureAutoUpdates()
        assertNull(protons.protonBusyId)
        assertTrue(attempts.isEmpty())
    }

    @Test fun anAnimatedLaunchRechecksTheProtonInstallGuardBeforeStarting() {
        val launcher = Robolectric.buildActivity(FrontEndActivity::class.java).get()
        Motion.refresh(launcher)
        LaunchOrigin.mark(Rect(0f, 0f, 100f, 100f), "game")
        launcher.startActivity(Intent(launcher, SessionActivity::class.java))
        val pending = requireNotNull(ReflectionHelpers.callInstanceMethod<Any?>(launcher, "getFlood"))
        ReflectionHelpers.getField<ProtonMenu>(launcher, "protons").protonBusyId = "droiddeck"
        FrontEndActivity::class.java.getDeclaredMethod("launchFlooded", pending.javaClass)
            .apply { isAccessible = true }.invoke(launcher, pending)
        assertNull(shadowOf(launcher).nextStartedActivity)
        assertNull(ReflectionHelpers.callInstanceMethod<Any?>(launcher, "getFlood"))
        assertNull(LaunchOrigin.flooding)
    }

    // 11-04: DroidDeck-Proton 11-12.1 appeared upstream and v0.5.5.2 put every player's games on it. Under the pin a new upstream
    // build is never downloaded, a build a game is on is never removed, and a player with no validated build gets one NEXT to his.
    @Test fun aNewUpstreamDroidDeckProtonIsNotDownloadedAndNothingIsRemoved() {
        ProtonExtras.validatedOwn = listOf("DroidDeck-Proton-11-11.10", "DroidDeck-Proton-11-11.11")
        val mine = install("DroidDeck-Proton-11-11.11")
        File(root, "root/.local/share/Steam/config/config.vdf").apply { parentFile!!.mkdirs() }.writeText(
            "\"InstallConfigStore\" { \"Software\" { \"Valve\" { \"Steam\" { \"CompatToolMapping\" { \"42\" { \"name\" \"DroidDeck-Proton-11-11.11\" } } } } } }")
        val cache = File(activity.filesDir, "proton-latest.json")
        val latest = JSONObject(cache.readText()).put("droiddeck", JSONObject().put("tag", "DroidDeck-Proton-11-12.1").put("at", System.currentTimeMillis()))
        cache.writeText(latest.toString())  // what v0.5.5.2 cached
        menu.installer = { tool, _, _ -> attempts.add(tool.id); null }
        menu.checkUpdates()
        waitUntil { menu.protonBusyId == null }
        menu.ensureAutoUpdates()
        waitUntil { menu.protonBusyId == null }
        assertTrue(attempts.isEmpty())
        assertTrue(mine.isDirectory)
        assertNull(ProtonExtras.update(activity, ProtonExtras.tools.first { it.id == "droiddeck" }))
    }

    @Test fun aNewerValidatedBuildIsOfferedButNotInstalledOnItsOwn() {
        ProtonExtras.validatedOwn = listOf("DroidDeck-Proton-11-11.10", "DroidDeck-Proton-11-11.11")
        install("DroidDeck-Proton 11-11.10")
        menu.installer = { tool, _, _ -> attempts.add(tool.id); null }
        menu.ensureAutoUpdates()
        waitUntil { menu.protonBusyId == null }
        assertTrue(attempts.isEmpty())
        val own = ProtonExtras.tools.first { it.id == "droiddeck" }
        assertEquals("DroidDeck-Proton-11-11.11", ProtonExtras.update(activity, own))
        assertNull(ProtonExtras.autoUpdate(activity, own))
        // the player taps the offered update: that build is what gets installed (11-11.11 stays selectable)
        val wanted = mutableListOf<String?>()
        menu.installer = { _, want, _ -> wanted.add(want); null }
        menu.installProton("droiddeck")
        waitUntil { menu.protonBusyId == null && wanted.isNotEmpty() }
        assertEquals(listOf<String?>("DroidDeck-Proton-11-11.11"), wanted)
    }

    @Test fun aPlayerMovedTo1112GetsTheValidatedBuildNextToItAndKeepsBoth() {
        ProtonExtras.validatedOwn = listOf("DroidDeck-Proton-11-11.10", "DroidDeck-Proton-11-11.11")
        install("DroidDeck-Proton 11-12.1")
        File(root, "root/.local/share/Steam/config/config.vdf").apply { parentFile!!.mkdirs() }.writeText(
            "\"InstallConfigStore\" { \"Software\" { \"Valve\" { \"Steam\" { \"CompatToolMapping\" { \"42\" { \"name\" \"DroidDeck-Proton-11-12.1\" } } } } } }")
        val asked = mutableListOf<String?>()
        menu.installer = { tool, want, _ ->
            asked.add(want)
            assertEquals(want, ProtonExtras.update(activity, tool))
            install("DroidDeck-Proton 11-11.10")
            null
        }
        menu.ensureAutoUpdates()
        waitUntil { menu.protonBusyId == null && asked.isNotEmpty() }
        menu.ensureAutoUpdates()
        waitUntil { menu.protonBusyId == null }
        assertEquals(listOf<String?>("DroidDeck-Proton-11-11.10"), asked)
        assertEquals(listOf("DroidDeck-Proton 11-11.10", "DroidDeck-Proton 11-12.1"),
            ProtonExtras.installedAll(activity, ProtonExtras.tools.first { it.id == "droiddeck" }))
    }

    @Test fun aCancellationDuringTeardownCannotPublishIdleEarly() {
        val session = watchCancellation(SessionPhase.STOPPING)
        assertEquals(SessionPhase.STOPPING, SessionState.phase)
        assertTrue(SessionState.stopRequested)
        assertFalse(session.isFinishing)
    }

    @Test fun cancellationBeforeSessionStartupStillFinishesTheActivity() {
        val session = watchCancellation(SessionPhase.PREPARING)
        assertEquals(SessionPhase.IDLE, SessionState.phase)
        assertFalse(SessionState.stopRequested)
        assertTrue(session.isFinishing)
    }

    private fun watchCancellation(phase: SessionPhase): SessionActivity {
        val session = Robolectric.buildActivity(SessionActivity::class.java).get()
        ReflectionHelpers.setField(session, "loading", LoadingState(session).apply { visible = false })
        SessionState.phase = phase
        SessionState.stopRequested = true
        try {
            ReflectionHelpers.callInstanceMethod<Void>(session, "watchSession")
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            ReflectionHelpers.setField(session, "watching", false)
            shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        }
        return session
    }
}
