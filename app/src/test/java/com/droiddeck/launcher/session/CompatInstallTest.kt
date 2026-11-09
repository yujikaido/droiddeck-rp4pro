package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.frontend.AutoComponents
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CompatInstallTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val own get() = ProtonExtras.tools.single { it.id == ProtonExtras.OWN }
    private val home get() = File(LinuxRuntime.rootDir(context), "root")
    private val requests get() = File(home, ".bl-proton-extra")
    private val tools get() = File(home, ".local/share/Steam/compatibilitytools.d")

    private val online = AutoComponents.online
    private val unfavourable = AutoComponents.unfavourable

    @Before
    fun clean() {
        tools.deleteRecursively()
        requests.delete()
        ProtonExtras.setDeclined(context, false)
        context.getSharedPreferences("compat-install", Context.MODE_PRIVATE).edit().clear().commit()
        AutoComponents.online = { true }
        AutoComponents.unfavourable = { false }
    }

    @After
    fun restore() {
        AutoComponents.online = online
        AutoComponents.unfavourable = unfavourable
    }

    // MaliBridge asks the session install for the default validated DroidDeck-Proton by its tag (the first of ProtonExtras.VALIDATED_OWN)
    private val pinned get() = "droiddeck --tag " + ProtonExtras.pinnedDefault()

    private fun lines() = if (requests.isFile) requests.readLines().filter { it.isNotBlank() } else emptyList()

    @Test
    fun missingAndNotDeclinedWritesTheRequestOnce() {
        assertEquals(CompatInstall.Outcome.QUEUED, CompatInstall.request(context, "failed", "234140"))
        assertEquals(listOf(pinned), lines())
        assertTrue(ProtonExtras.queued(context, own))
        assertEquals(CompatInstall.Outcome.ALREADY_QUEUED, CompatInstall.request(context, "new", "10"))
        assertEquals(listOf(pinned), lines())
    }

    @Test
    fun anExistingRequestForAnotherToolIsKept() {
        requests.parentFile!!.mkdirs()
        requests.writeText("ge\n")
        assertEquals(CompatInstall.Outcome.QUEUED, CompatInstall.request(context, "new", "10"))
        assertEquals(listOf("ge", pinned), lines())
    }

    @Test
    fun declinedOnlyPromptsAndNeverWritesARequest() {
        ProtonExtras.setDeclined(context, true)
        assertEquals(CompatInstall.Outcome.PROMPT, CompatInstall.request(context, "failed", "234140"))
        assertFalse(requests.exists())
        assertEquals(CompatInstall.Outcome.NOTHING, CompatInstall.request(context, "new", "10"))
        assertFalse(requests.exists())
        assertTrue(ProtonExtras.declined(context))
    }

    @Test
    fun notNowIsRememberedPerTitle() {
        ProtonExtras.setDeclined(context, true)
        CompatInstall.refuse(context, "234140")
        assertEquals(CompatInstall.Outcome.NOTHING, CompatInstall.request(context, "failed", "234140"))
        assertEquals(CompatInstall.Outcome.PROMPT, CompatInstall.request(context, "failed", "99"))
        assertFalse(requests.exists())
    }

    @Test
    fun installInThePromptLiftsTheDeclineAndAsksForTheDownload() {
        ProtonExtras.setDeclined(context, true)
        CompatInstall.accept(context)
        assertFalse(ProtonExtras.declined(context))
        assertEquals(listOf(pinned), lines())
        CompatInstall.accept(context)
        assertEquals(listOf(pinned), lines())
    }

    @Test
    fun offlineOrMeteredNetworksWriteNothing() {
        AutoComponents.online = { false }
        assertEquals(CompatInstall.Outcome.WAIT_NETWORK, CompatInstall.request(context, "failed", "234140"))
        AutoComponents.online = { true }
        AutoComponents.unfavourable = { true }
        assertEquals(CompatInstall.Outcome.WAIT_NETWORK, CompatInstall.request(context, "new", "10"))
        assertFalse(requests.exists())
        AutoComponents.unfavourable = { false }
        assertEquals(CompatInstall.Outcome.QUEUED, CompatInstall.request(context, "new", "10"))
    }

    @Test
    fun anInstalledToolNeedsNoRequestAndNoPrompt() {
        File(tools, "DroidDeck-Proton 11-11.4").apply {
            mkdirs()
            File(this, "toolmanifest.vdf").writeText("x")
        }
        assertEquals(CompatInstall.Outcome.INSTALLED, CompatInstall.request(context, "failed", "234140"))
        ProtonExtras.setDeclined(context, true)
        assertEquals(CompatInstall.Outcome.INSTALLED, CompatInstall.request(context, "failed", "234140"))
        assertFalse(requests.exists())
    }
}
