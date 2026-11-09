package com.droiddeck.launcher.session

import android.content.Context
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ComponentImportMessageTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "components").deleteRecursively()
    }

    private fun refusal(file: File): String {
        try { ComponentsManager.importPackage(context, file, file.name) } catch (e: Exception) { return ComponentsManager.importMessage(context, e) }
        fail("imported ${file.name}")
        return ""
    }

    private fun wcp(name: String, profile: JSONObject?): File {
        val out = File(context.cacheDir, name)
        TarArchiveOutputStream(ZstdOutputStream(BufferedOutputStream(FileOutputStream(out)))).use { tar ->
            val body = (profile ?: JSONObject()).toString().toByteArray()
            tar.putArchiveEntry(TarArchiveEntry(if (profile != null) "profile.json" else "readme.txt").apply { size = body.size.toLong() })
            tar.write(body)
            tar.closeArchiveEntry()
        }
        return out
    }

    @Test fun aFileThatIsNoPackageSaysWhatIsNeeded() {
        val msg = refusal(File(context.cacheDir, "junk.zip").apply { writeBytes(ByteArray(64) { 0x50 }) })
        assertTrue(msg, msg.contains("Linux .wcp or .tar.zst") && msg.contains("download one from the list"))
        assertTrue(msg, !msg.contains("starts with"))
    }

    @Test fun aPackageWithoutAProfileSaysWhatIsNeeded() {
        val msg = refusal(wcp("noprofile.wcp", null))
        assertTrue(msg, msg.contains("Linux .wcp or .tar.zst") && msg.contains("download one from the list"))
    }

    @Test fun aWindowsStylePackageNamesItsTypeAndWhatIsNeeded() {
        val msg = refusal(wcp("win.wcp", JSONObject().put("type", "Wine").put("versionName", "1")))
        assertTrue(msg, msg.contains("Wine") && msg.contains("Linux .wcp or .tar.zst") && msg.contains("download one from the list"))
    }

    @Test fun otherFailuresKeepTheirOwnMessage() {
        assertEquals("disk full", ComponentsManager.importMessage(context, java.io.IOException("disk full")))
    }
}
