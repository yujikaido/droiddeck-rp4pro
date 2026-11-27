package com.droiddeck.launcher.frontend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameAntiAliasingTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ysOrigin = 207350L
    private val ini = "IniVersion=0x100\r\n\r\n[kernel]\r\nMultiSampleType=9\r\n\r\n[graphics]\r\nBackBufferWidth=1280\r\n" +
        "MultiSampleType=4\r\nTextureFilter=2\r\n\r\n[sound]\r\nDevice=0\r\n"

    private fun prefix(text: String?): File {
        val pfx = tmp.newFolder("pfx")
        if (text != null) {
            val f = File(pfx, GameAntiAliasing.known.getValue(ysOrigin).file)
            f.parentFile!!.mkdirs()
            f.writeBytes(text.toByteArray(Charsets.ISO_8859_1))
        }
        return pfx
    }

    private fun read(pfx: File) = File(pfx, GameAntiAliasing.known.getValue(ysOrigin).file).readText(Charsets.ISO_8859_1)

    @Test
    fun readsTheGraphicsSectionsKeyOnly() {
        val pfx = prefix(ini)
        assertEquals(true, GameAntiAliasing.state(ysOrigin, pfx))
    }

    @Test
    fun offAndOnChangeOnlyTheValue() {
        val pfx = prefix(ini)
        assertEquals(false, GameAntiAliasing.set(ysOrigin, pfx, false))
        assertEquals(ini.replace("MultiSampleType=4\r\nTexture", "MultiSampleType=0\r\nTexture"), read(pfx))
        assertEquals(true, GameAntiAliasing.set(ysOrigin, pfx, true))
        assertEquals(ini, read(pfx))
    }

    @Test
    fun keepsACopyOfTheFirstVersion() {
        val pfx = prefix(ini)
        GameAntiAliasing.set(ysOrigin, pfx, false)
        GameAntiAliasing.set(ysOrigin, pfx, true)
        GameAntiAliasing.set(ysOrigin, pfx, false)
        val f = File(pfx, GameAntiAliasing.known.getValue(ysOrigin).file)
        assertArrayEquals(ini.toByteArray(Charsets.ISO_8859_1), File(f.parentFile, f.name + ".droiddeck-aa").readBytes())
        assertFalse(File(f.parentFile, f.name + ".droiddeck-tmp").exists())
    }

    @Test
    fun spacesAroundTheValueAndCaseInNamesAreFine() {
        val pfx = prefix("[Graphics]\nmultisampletype = 4 \nX=1\n")
        assertEquals(true, GameAntiAliasing.state(ysOrigin, pfx))
        assertEquals(false, GameAntiAliasing.set(ysOrigin, pfx, false))
        assertEquals("[Graphics]\nmultisampletype = 0 \nX=1\n", read(pfx))
    }

    @Test
    fun nothingWithoutTheFileTheKeyOrAKnownGame() {
        assertNull(GameAntiAliasing.state(ysOrigin, prefix(null)))
        tmp.delete(); tmp.create()
        val noKey = prefix("[graphics]\nTextureFilter=2\n")
        assertNull(GameAntiAliasing.state(ysOrigin, noKey))
        assertNull(GameAntiAliasing.set(ysOrigin, noKey, false))
        assertEquals("[graphics]\nTextureFilter=2\n", read(noKey))
        assertNull(GameAntiAliasing.state(1L, noKey))
        assertNull(GameAntiAliasing.state(ysOrigin, null))
        assertTrue(GameAntiAliasing.known.isNotEmpty())
    }
}
