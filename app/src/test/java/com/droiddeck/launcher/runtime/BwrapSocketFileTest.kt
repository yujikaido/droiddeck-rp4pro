package com.droiddeck.launcher.runtime

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** droiddeck-bwrap reads this file when flatpak runs it with an empty environment. */
@RunWith(RobolectricTestRunner::class)
class BwrapSocketFileTest {
    private fun packaged(name: String) = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getPackageName() = name
    }

    @Test fun eachBuildWritesItsOwnSocketIntoTheRootfs() {
        val root = Files.createTempDirectory("rootfs").toFile()
        try {
            for (name in listOf("com.droiddeck.launcher", "com.droiddeck.launcher.mali", "com.tencent.ig.mali")) {
                assertTrue(BwrapSpawner.publishSocket(packaged(name), root))
                val file = File(root, "usr/local/share/droiddeck/bwrap-socket")
                assertEquals("$name.bwrap\n", file.readText())
                assertEquals(BwrapSpawner.socket(packaged(name)), file.readText().trim())
                assertFalse(File(file.parentFile, "bwrap-socket.staged").exists())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun replacesAStaleNameFromAnotherBuild() {
        val root = Files.createTempDirectory("rootfs").toFile()
        try {
            val file = File(root, BwrapSpawner.SOCKET_FILE).apply { parentFile!!.mkdirs(); writeText("com.droiddeck.launcher.bwrap\n") }
            assertTrue(BwrapSpawner.publishSocket(packaged("com.droiddeck.launcher.mali"), root))
            assertEquals("com.droiddeck.launcher.mali.bwrap\n", file.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
