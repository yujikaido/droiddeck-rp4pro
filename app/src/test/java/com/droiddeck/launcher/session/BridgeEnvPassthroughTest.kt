package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** droiddeck-env's VKBRIDGE_ lines reach the bridge server's override file (GTAV-STUTTER-20261104: a player could not run a BC arm). */
class BridgeEnvPassthroughTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun playerEnv(text: String) = File(tmp.root, "droiddeck-env").apply { writeText(text) }
    private val override get() = File(tmp.root, "cache/env")
    private val presets get() = File(tmp.root, "cache/presets")
    private fun run(player: File?) = BridgeEnvPassthrough.apply(player, override, presets)

    /** What the server makes of the file: server_pcache.c vkb_srv_env_reload + vkb_srv_env_override (the first line of a key wins). */
    private fun serverView(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.split('\n')) {
            val s = raw.trimStart(' ', '\t')
            if (s.isEmpty() || s.startsWith("#")) continue
            val line = s.trimEnd('\r')
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            out.putIfAbsent(line.substring(0, eq), line.substring(eq + 1))
        }
        return out
    }

    // ---- nothing to pass: nothing changes ----

    @Test fun noVkbridgeLinesAndNoFileLeavesNoFile() {
        val r = run(playerEnv("DD_PAD_KBM=off\n# VKBRIDGE_X=1\nBL_OOM_ADJ=0\n"))
        assertFalse(override.exists())
        assertFalse(r.changed)
        assertTrue(r.logLines.isEmpty())
    }

    @Test fun noPlayerFileAtAll() {
        assertTrue(run(null).logLines.isEmpty())
        assertFalse(override.exists())
        assertTrue(run(File(tmp.root, "missing")).logLines.isEmpty())
        assertFalse(override.exists())
    }

    @Test fun goldenOverrideFileIsByteIdenticalWithoutVkbridgeLines() {
        val original = "# tester's file\r\nVKBRIDGE_SPIRV_FIX=1\n\n  VKBRIDGE_EMU_OFF=bcn\r\nnot a line\nNOEQ\n=bad\nLAST=no newline"
        override.parentFile!!.mkdirs()
        override.writeBytes(original.toByteArray())
        val stamp = override.lastModified()
        val r = run(playerEnv("DD_PAD_KBM=off\nTU_DEBUG=noconform\n"))
        assertFalse(r.changed)
        assertTrue(r.logLines.isEmpty())
        assertEquals(original, override.readText())
        assertEquals(stamp, override.lastModified())
        assertFalse(File(override.path + ".staged").exists())
    }

    // ---- passing ----

    @Test fun linesAreCopiedAndLogged() {
        val r = run(playerEnv("VKBRIDGE_BC_TRANSCODE=0\nVKBRIDGE_BC_TX_BUDGET=32:4\nVKBRIDGE_HITCH_MS=100\nVKBRIDGE_WAIT_STATS=5\nDD_PAD_KBM=off\n"))
        assertTrue(r.changed)
        assertEquals(
            mapOf("VKBRIDGE_BC_TRANSCODE" to "0", "VKBRIDGE_BC_TX_BUDGET" to "32:4", "VKBRIDGE_HITCH_MS" to "100", "VKBRIDGE_WAIT_STATS" to "5"),
            serverView(override.readText()),
        )
        assertEquals(
            listOf("bridge settings from droiddeck-env: VKBRIDGE_BC_TRANSCODE=0, VKBRIDGE_BC_TX_BUDGET=32:4, VKBRIDGE_HITCH_MS=100, VKBRIDGE_WAIT_STATS=5"),
            r.logLines,
        )
        assertFalse("other keys of droiddeck-env stay out of the server file", override.readText().contains("DD_PAD_KBM"))
    }

    @Test fun twoSessionsInARowChangeNothingTheSecondTime() {
        val env = playerEnv("VKBRIDGE_BC_TRANSCODE=0\n")
        run(env)
        val first = override.readText()
        val r = run(env)
        assertFalse(r.changed)
        assertEquals(first, override.readText())
        assertEquals(1, Regex("VKBRIDGE_BC_TRANSCODE=0").findAll(first).count())
        assertEquals(1, r.logLines.size)
    }

    @Test fun windowsLineEndingsAndIndentWork() {
        run(playerEnv("  VKBRIDGE_HITCH_MS=100\r\n\tVKBRIDGE_WAIT_STATS=5  \r\n"))
        assertEquals(mapOf("VKBRIDGE_HITCH_MS" to "100", "VKBRIDGE_WAIT_STATS" to "5"), serverView(override.readText()))
        assertFalse(override.readText().contains('\r'))
    }

    // ---- precedence ----

    @Test fun oldEntriesOfOtherKeysStayAndPlayerWinsOnTheSameKey() {
        override.parentFile!!.mkdirs()
        val original = "# ours\nVKBRIDGE_BC_TX_BUDGET=16:2\nVKBRIDGE_SPIRV_FIX=1\n"
        override.writeText(original)
        val r = run(playerEnv("VKBRIDGE_BC_TX_BUDGET=32:4\nVKBRIDGE_HITCH_MS=100\n"))
        val view = serverView(override.readText())
        assertEquals("player's line wins", "32:4", view["VKBRIDGE_BC_TX_BUDGET"])
        assertEquals("1", view["VKBRIDGE_SPIRV_FIX"])
        assertEquals("100", view["VKBRIDGE_HITCH_MS"])
        assertTrue("the old line is still in the file", override.readText().contains(original))
        assertTrue(r.logLines.toString(), r.logLines.any { it.contains("VKBRIDGE_BC_TX_BUDGET=32:4 replaces VKBRIDGE_BC_TX_BUDGET=16:2") })
        assertTrue(r.logLines.first().contains("VKBRIDGE_BC_TX_BUDGET=32:4"))
    }

    @Test fun removingTheLineGivesTheOriginalFileBackByteForByte() {
        override.parentFile!!.mkdirs()
        val original = "# ours\r\nVKBRIDGE_BC_TX_BUDGET=16:2\nVKBRIDGE_SPIRV_FIX=1"
        override.writeBytes(original.toByteArray())
        val env = playerEnv("VKBRIDGE_BC_TX_BUDGET=32:4\n")
        run(env)
        assertEquals("32:4", serverView(override.readText())["VKBRIDGE_BC_TX_BUDGET"])
        env.writeText("DD_PAD_KBM=off\n")
        val r = run(env)
        assertTrue(r.changed)
        assertEquals(original, override.readText())
        assertEquals("16:2", serverView(override.readText())["VKBRIDGE_BC_TX_BUDGET"])
        assertTrue(r.logLines.isEmpty())
    }

    @Test fun aFileThatOnlyHeldOurBlockIsRemovedWhenTheLinesGo() {
        val env = playerEnv("VKBRIDGE_BC_TRANSCODE=0\n")
        run(env)
        assertTrue(override.isFile)
        env.delete()
        assertTrue(run(env).changed)
        assertFalse(override.exists())
    }

    @Test fun aChangedLineReplacesTheBlockNotAddsAnother() {
        val env = playerEnv("VKBRIDGE_BC_TX_BUDGET=32:4\n")
        run(env)
        env.writeText("VKBRIDGE_BC_TX_BUDGET=8:1\n")
        run(env)
        val text = override.readText()
        assertEquals(1, Regex(Regex.escape(BridgeEnvPassthrough.BEGIN)).findAll(text).count())
        assertEquals(mapOf("VKBRIDGE_BC_TX_BUDGET" to "8:1"), serverView(text))
    }

    @Test fun aPerGamePresetFileNamingTheSameKeyIsSaidInTheLog() {
        presets.mkdirs()
        File(presets, "271590.env").writeText("VKBRIDGE_BC_TX_BUDGET=16:2\nVKBRIDGE_OTHER=1\n")
        File(presets, "5.env").writeText("VKBRIDGE_UNRELATED=1\n")
        val r = run(playerEnv("VKBRIDGE_BC_TX_BUDGET=32:4\n"))
        assertTrue(r.logLines.toString(), r.logLines.any { it.contains("VKBRIDGE_BC_TX_BUDGET is also set by presets/271590.env") && it.contains("preset file wins") })
        assertEquals(2, r.logLines.size)
    }

    @Test fun anInterruptedBlockWithoutItsEndIsCleanedUp() {
        override.parentFile!!.mkdirs()
        override.writeText(BridgeEnvPassthrough.BEGIN + "\nVKBRIDGE_OLD=1\nKEEP=1\n")
        val withoutBlock = BridgeEnvPassthrough.withoutBlock(override.readText())
        assertEquals("VKBRIDGE_OLD=1\nKEEP=1\n", withoutBlock)
        assertNull(BridgeEnvPassthrough.withoutBlock("VKBRIDGE_OLD=1\n"))
    }

    // ---- bad lines and injection ----

    @Test fun malformedLinesAreIgnoredAndNamed() {
        val bad = listOf(
            "VKBRIDGE_=1", "VKBRIDGE_lower=1", "VKBRIDGE_X=", "VKBRIDGE_X", "VKBRIDGE_X =1", "VKBRIDGE_X= 1", "VKBRIDGE_X=a b",
            "VKBRIDGE_X==1", "VKBRIDGE_X=é", "VKBRIDGE_X=" + "a".repeat(201), "VKBRIDGE_" + "A".repeat(57) + "=1", "VKBRIDGE_X=1\u0000",
        )
        val r = run(playerEnv(bad.joinToString("\n") + "\nVKBRIDGE_GOOD=7\n"))
        assertEquals(mapOf("VKBRIDGE_GOOD" to "7"), serverView(override.readText()))
        assertEquals(bad.size, BridgeEnvPassthrough.parse(bad.joinToString("\n")).rejected.size)
        assertEquals(bad.size + 1, r.logLines.size)
    }

    @Test fun shellAndFormatMetacharactersAreRefused() {
        val bad = listOf(
            "VKBRIDGE_X=\$(id)", "VKBRIDGE_X=`id`", "VKBRIDGE_X=a;b", "VKBRIDGE_X=a|b", "VKBRIDGE_X=a&b", "VKBRIDGE_X=a>b", "VKBRIDGE_X=a<b",
            "VKBRIDGE_X=\"q\"", "VKBRIDGE_X='q'", "VKBRIDGE_X=a\\b", "VKBRIDGE_X=\${HOME}", "VKBRIDGE_X=a*b", "VKBRIDGE_X=a?b", "VKBRIDGE_X=(a)", "VKBRIDGE_X=a#b",
            "VKBRIDGE_X=\u001b[31m", "VKBRIDGE_X=\t1",
        )
        for (b in bad) {
            val p = BridgeEnvPassthrough.parse(b)
            assertTrue("accepted: ${b.replace("\u001b", "ESC")}", p.settings.isEmpty())
        }
        run(playerEnv(bad.joinToString("\n")))
        assertFalse(override.exists())
    }

    @Test fun newlinesInsideAValueCannotSmuggleAnotherLine() {
        for (text in listOf(
            "VKBRIDGE_A=1\nLD_PRELOAD=/data/x.so\n",
            "VKBRIDGE_A=1\r\nLD_PRELOAD=/data/x.so\r\n",
            "VKBRIDGE_A=1\rLD_PRELOAD=/data/x.so",
            "VKBRIDGE_A=1 LD_PRELOAD=/data/x.so",
            "VKBRIDGE_A=1\u0085LD_PRELOAD=/data/x.so",
            "VKBRIDGE_A=1\u0000\nLD_PRELOAD=/data/x.so",
        )) {
            override.delete()
            run(playerEnv(text))
            val written = override.takeIf { it.exists() }?.readText().orEmpty()
            assertFalse(text, written.contains("LD_PRELOAD"))
            assertFalse(written.contains("/data/x.so"))
            assertTrue(serverView(written).keys.all { it.startsWith("VKBRIDGE_") })
        }
    }

    @Test fun aLineThatLooksLikeOurMarkersIsNotACommandToTheFile() {
        val r = run(playerEnv("VKBRIDGE_A=1\n" + BridgeEnvPassthrough.END + "\nVKBRIDGE_B=2\n"))
        val text = override.readText()
        assertEquals(1, text.lines().count { it == BridgeEnvPassthrough.END })
        assertEquals(mapOf("VKBRIDGE_A" to "1", "VKBRIDGE_B" to "2"), serverView(text))
        assertEquals(1, r.logLines.size)
    }

    @Test fun otherKeysAndCommentsAreNotTouchedOrLogged() {
        val r = run(playerEnv("# VKBRIDGE_X=1\nvkbridge_x=1\nBL_VKBRIDGE=1\nXVKBRIDGE_X=1\nDXVK_HUD=fps\n"))
        assertFalse(override.exists())
        assertTrue(r.logLines.isEmpty())
    }

    @Test fun repeatedKeyUsesTheLaterLineAsTheGuestDoesAndSaysSo() {
        val r = run(playerEnv("VKBRIDGE_HITCH_MS=100\nVKBRIDGE_HITCH_MS=60\n"))
        assertEquals(mapOf("VKBRIDGE_HITCH_MS" to "60"), serverView(override.readText()))
        assertTrue(r.logLines.any { it.contains("VKBRIDGE_HITCH_MS is given twice") })
    }

    @Test fun anAbsurdNumberOfSettingsIsCapped() {
        val text = (1..100).joinToString("\n") { "VKBRIDGE_K$it=1" }
        val r = run(playerEnv(text))
        assertEquals(64, serverView(override.readText()).size)
        assertTrue(r.logLines.any { it.contains("more than 64") })
    }

    @Test fun allowedValueCharactersPass() {
        val p = BridgeEnvPassthrough.parse("VKBRIDGE_A=32:4\nVKBRIDGE_B=a,b,c\nVKBRIDGE_C=/data/x-y_z.1\nVKBRIDGE_D=1+2%3@4")
        assertEquals(4, p.settings.size)
        assertTrue(p.rejected.isEmpty())
    }
}
