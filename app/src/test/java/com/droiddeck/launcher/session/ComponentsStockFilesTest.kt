package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** A DXVK package without d3d8.dll must not leave the Proton without its own: Proton's steampipe fixups list it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ComponentsStockFilesTest {
    private lateinit var context: Context
    private lateinit var proton: File
    private val dxvk = "files/lib/wine/dxvk"
    private val arches = listOf("aarch64-windows", "i386-windows")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "components").deleteRecursively()
        LinuxRuntime.rootDir(context).deleteRecursively()
        proton = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/steamapps/common/Proton Experimental (ARM64)")
        File(proton, "files/lib/wine").mkdirs()
        File(proton, "proton").writeText("#!/bin/sh\n")
        File(proton, "version").writeText("1790245028 experimental-11.0-20260924-arm64\n")
        for (arch in arches) for (dll in listOf("d3d8.dll", "d3d9.dll", "d3d11.dll", "dxgi.dll"))
            File(proton, "$dxvk/$arch/$dll").apply { parentFile!!.mkdirs(); writeText("stock $arch/$dll") }
    }

    @Test fun aPackageWithoutD3d8KeepsTheProtonsOwnAndTheLaunchTablePutsItBack() {
        wcp("dxvk-1.10.3.wcp", listOf("d3d9.dll", "d3d11.dll", "dxgi.dll", "d3d10core.dll"))

        ComponentsManager.swap(context, id(), "dxvk-1.10.3.wcp")

        for (arch in arches) {
            assertEquals("stock $arch/d3d8.dll", File(proton, "$dxvk/$arch/d3d8.dll").readText())
            assertEquals("dxvk-1.10.3.wcp $arch/d3d11.dll", File(proton, "$dxvk/$arch/d3d11.dll").readText())
        }
        assertTrue(File(context.filesDir, "components/originals/${id()}/experimental-11.0-20260924-arm64/dxvk.wcp").isFile)
        for (arch in arches) {
            val row = row("$dxvk/$arch/d3d8.dll")
            assertNotEquals("-", row[6])
            val source = File(LinuxRuntime.rootDir(context), row[6].removePrefix("/"))
            assertEquals("stock $arch/d3d8.dll", source.readText())
            assertEquals(Hashes.sha256(source), row[5])
        }
        assertEquals(listOf("-", "-"), row("$dxvk/aarch64-windows/d3d10.dll").subList(5, 7))
    }

    @Test fun swappingToAPackageWithLessPutsStockFilesBackAndDropsOnesTheProtonNeverHad() {
        wcp("dxvk-2.7.wcp", listOf("d3d8.dll", "d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll"))
        wcp("dxvk-1.10.3.wcp", listOf("d3d9.dll", "d3d11.dll", "dxgi.dll"))

        ComponentsManager.swap(context, id(), "dxvk-2.7.wcp")
        assertEquals("dxvk-2.7.wcp aarch64-windows/d3d8.dll", File(proton, "$dxvk/aarch64-windows/d3d8.dll").readText())
        ComponentsManager.swap(context, id(), "dxvk-1.10.3.wcp")

        for (arch in arches) {
            assertEquals("stock $arch/d3d8.dll", File(proton, "$dxvk/$arch/d3d8.dll").readText())
            assertFalse(File(proton, "$dxvk/$arch/d3d10core.dll").exists())
            assertEquals("dxvk-1.10.3.wcp $arch/d3d9.dll", File(proton, "$dxvk/$arch/d3d9.dll").readText())
        }

        ComponentsManager.restore(context, id(), "dxvk", "experimental-11.0-20260924-arm64")
        for (arch in arches) for (dll in listOf("d3d8.dll", "d3d9.dll", "d3d11.dll", "dxgi.dll"))
            assertEquals("stock $arch/$dll", File(proton, "$dxvk/$arch/$dll").readText())
    }

    @Test fun withNoSavedOriginalAFileThePackageLacksStays() {
        val target = File(proton, "$dxvk/aarch64-windows")
        wcp("dxvk-1.10.3.wcp", listOf("d3d9.dll"))
        val hashes = ComponentsManager.installFiles(context, proton, "dxvk",
            File(context.filesDir, "components/packages/dxvk-1.10.3.wcp"), stock = null)
        assertEquals("stock aarch64-windows/d3d8.dll", File(target, "d3d8.dll").readText())
        assertEquals("dxvk-1.10.3.wcp aarch64-windows/d3d9.dll", File(target, "d3d9.dll").readText())
        assertTrue(hashes.keys.none { it.endsWith("d3d8.dll") })
    }

    @Test fun aLaunchTableFromBeforeTheFixIsWrittenAgainOnce() {
        wcp("dxvk-1.10.3.wcp", listOf("d3d9.dll", "d3d11.dll", "dxgi.dll"))
        ComponentsManager.swap(context, id(), "dxvk-1.10.3.wcp")
        val table = File(LinuxRuntime.rootDir(context), "root/.local/share/droiddeck-components/desired.tsv")
        val rel = "$dxvk/aarch64-windows/d3d8.dll"
        val old = table.readLines().joinToString("") { line ->
            val f = line.split('\t')
            (if (f[4] == rel) f.take(5) + listOf("-", "-") else f).joinToString("\t") + "\n"
        }
        table.writeText(old)

        ComponentsManager.republishOnce(context)
        assertNotEquals("-", row(rel)[6])

        table.writeText(old)
        ComponentsManager.republishOnce(context)
        assertEquals(old, table.readText())
    }

    private fun id() =ComponentsManager.safeName(proton.name)

    private fun row(rel: String): List<String> =
        File(LinuxRuntime.rootDir(context), "root/.local/share/droiddeck-components/desired.tsv").readLines()
            .map { it.split('\t') }.single { it[0] == "*" && it[3] == "dxvk" && it[4] == rel }

    private fun wcp(name: String, dlls: List<String>) {
        val file = File(context.filesDir, "components/packages/$name").apply { parentFile!!.mkdirs() }
        TarArchiveOutputStream(ZstdOutputStream(file.outputStream())).use { tar ->
            fun put(path: String, bytes: ByteArray) {
                tar.putArchiveEntry(TarArchiveEntry(path).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
            put("./profile.json", JSONObject().put("type", "DXVK-Linux").put("versionName", name).toString().toByteArray())
            for (arch in arches) for (dll in dlls) put("./$dxvk/$arch/$dll", "$name $arch/$dll".toByteArray())
        }
    }
}
