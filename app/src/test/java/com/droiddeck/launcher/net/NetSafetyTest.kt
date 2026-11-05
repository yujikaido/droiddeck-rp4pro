package com.droiddeck.launcher.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetSafetyTest {
    @Test fun plainNamesAreSafe() {
        listOf("Ys Origin", "appmanifest_207350.acf", "data.na", "Tales of Eternia Remastered", "日本語", ".bashrc", "a..b")
            .forEach { assertTrue(it, SmbLink.safeName(it)) }
    }

    @Test fun namesThatLeaveTheFolderAreNot() {
        listOf("", ".", "..", "../x", "..\\x", "a/b", "a\\b", "/abs", "x\u0000y", "line\nbreak", "tab\there", "del\u007f")
            .forEach { assertFalse(it, SmbLink.safeName(it)) }
    }

    @Test fun buildIdIsRead() {
        val acf = "\"AppState\"\n{\n\t\"appid\"\t\t\"207350\"\n\t\"buildid\"\t\t\"13122302\"\n\t\"TargetBuildID\"\t\t\"1\"\n}"
        assertEquals(13122302L, NetSteam.buildOf(acf))
        assertEquals(0L, NetSteam.buildOf("\"AppState\" { }"))
    }
}
