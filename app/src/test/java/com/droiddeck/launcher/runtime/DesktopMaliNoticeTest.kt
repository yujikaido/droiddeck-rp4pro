package com.droiddeck.launcher.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopMaliNoticeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun maliWithOpenglMarkerGetsNotice() = assertTrue(DesktopMaliNotice.needed(true, "opengl\n"))
    @Test fun adrenoGetsNoNotice() {
        assertFalse(DesktopMaliNotice.needed(false, "opengl"))
        assertFalse(DesktopMaliNotice.needed(false, null))
        assertFalse(DesktopMaliNotice.needed(false, "opengl", 6 to 8))
    }
    @Test fun maliWithOldPackGetsNoNotice() {
        assertFalse(DesktopMaliNotice.needed(true, null))
        assertFalse(DesktopMaliNotice.needed(true, "software"))
        assertFalse(DesktopMaliNotice.needed(true, ""))
    }
    // Nothing installed yet (no KWin, no marker): the package that will be installed is DroidDeck's, whose KWin carries
    // patch 0002 and composites on the bridge's render node (t17c, Galaxy A35 Mali-G68: the dialog showed before the first
    // install, then KDE drew on the GPU, "Using explicitly selected nested render node"). No notice.
    // (Whatever the pack tag: the decision no longer takes it.)
    @Test fun maliBeforeTheDesktopIsInstalledGetsNoNotice() = assertFalse(DesktopMaliNotice.needed(true, null))
    // Installed (the package's marker is there) but no libkwin.so.X.Y found: the session would keep software, which
    // KWin 6.8 lacks, so the notice stays.
    @Test fun maliWithInstalledDesktopButUnreadableKwinGetsNotice() = assertTrue(DesktopMaliNotice.needed(true, "opengl"))
    @Test fun softwareMarkerGetsNoNotice() = assertFalse(DesktopMaliNotice.needed(true, "software"))

    // The Kubuntu package of the Ubuntu runtime: marked "opengl" (for Adreno) but KWin 6.6.6 keeps QPainter.
    @Test fun kubuntuKwin66OnMaliGetsNoNotice() =
        assertFalse(DesktopMaliNotice.needed(true, "opengl", 6 to 6))
    @Test fun kwin68AndLaterOnMaliGetNotice() {
        assertTrue(DesktopMaliNotice.needed(true, "opengl", 6 to 8))
        assertTrue(DesktopMaliNotice.needed(true, null, 6 to 9))
        assertTrue(DesktopMaliNotice.needed(true, "software", 7 to 0))
    }
    // DroidDeck's own KWin 6.8 build carries patch 0002 and composites on the bridge's render node: no notice.
    @Test fun kwin68WithTheExplicitNodePatchOnMaliGetsNoNotice() {
        assertFalse(DesktopMaliNotice.needed(true, "opengl", 6 to 8) { true })
        assertTrue(DesktopMaliNotice.needed(true, "opengl", 6 to 8) { false })
    }
    @Test fun thePatchIsOnlyLookedForWhenItMatters() {
        var asked = 0
        DesktopMaliNotice.needed(true, "opengl", 6 to 6) { asked++; true }
        DesktopMaliNotice.needed(false, "opengl", 6 to 8) { asked++; true }
        assertEquals(0, asked)
    }
    @Test fun thePatchIsFoundInTheLibraryEvenAcrossReads() {
        val needle = "explicitly selected nested render node"
        for (offset in listOf(0, 65_536 - 10, 65_536, 200_000)) {
            val root = tmp.newFolder()
            val bytes = ByteArray(offset) { 'x'.code.toByte() } + "Using $needle".toByteArray() + ByteArray(100)
            File(root, "usr/lib").apply { mkdirs() }.resolve("libkwin.so.6.8.2").writeBytes(bytes)
            assertTrue("offset $offset", DesktopMaliNotice.kwinTakesExplicitNode(root))
        }
        val plain = tmp.newFolder()
        File(plain, "usr/lib/aarch64-linux-gnu").apply { mkdirs() }.resolve("libkwin.so.6.6.6").writeBytes(ByteArray(300_000))
        assertFalse(DesktopMaliNotice.kwinTakesExplicitNode(plain))
        assertFalse(DesktopMaliNotice.kwinTakesExplicitNode(tmp.newFolder()))
    }
    @Test fun kwin67OnMaliGetsNoNotice() = assertFalse(DesktopMaliNotice.needed(true, "opengl", 6 to 7))

    private fun lib(root: File, dir: String, name: String) = File(root, dir).apply { mkdirs() }.resolve(name).writeText("")

    @Test fun kwinVersionFromUbuntuLibDir() {
        val root = tmp.newFolder()
        lib(root, "usr/lib/aarch64-linux-gnu", "libkwin.so.6.6.6")
        lib(root, "usr/lib/aarch64-linux-gnu", "libkwin.so.6")
        lib(root, "usr/lib/aarch64-linux-gnu", "libkwineffects.so.6.6.6")
        assertEquals(6 to 6, DesktopMaliNotice.kwinVersion(root))
    }
    @Test fun kwinVersionFromArchLibDir() {
        val root = tmp.newFolder()
        lib(root, "usr/lib", "libkwin.so.6.8.2")
        assertEquals(6 to 8, DesktopMaliNotice.kwinVersion(root))
    }
    @Test fun noKwinNoVersion() = assertNull(DesktopMaliNotice.kwinVersion(tmp.newFolder()))
}
