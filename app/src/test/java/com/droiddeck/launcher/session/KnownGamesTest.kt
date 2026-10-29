package com.droiddeck.launcher.session

import com.droiddeck.launcher.session.KnownGames.By
import com.droiddeck.launcher.session.KnownGames.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class KnownGamesTest {
    private val g710 = KnownGames.Device("Mali-G710", 32 to 1, 1 to 1, bridge = true)
    private val g925 = KnownGames.Device("Mali-G925-Immortalis MC12", 49 to 1, 1 to 3, bridge = true)
    private val adreno = KnownGames.Device("Adreno (TM) 750", null, 1 to 3, bridge = false)

    private val table = KnownGames.parse(
        """
        {"schema": 1, "version": 3, "games": {
          "220": [{"name": "Half-Life 2", "when": {"bridge": true, "vulkanBelow": "1.3"}, "dxvk": "1.10.3", "source": "a"}],
          "322330": [{"name": "DST", "when": {"bridge": true}, "env": {"PROTON_USE_WINED3D": "1"}, "source": "b"},
                     {"name": "DST", "when": {"gpu": ["Mali-G925"], "driverMin": "r48p0"}, "env": {"PROTON_USE_WINED3D": "0"},
                      "bridge": {"VKBRIDGE_HEAP_MAX_MB": "4096"}, "tool": "proton-11-arm64", "source": "c"}],
          "10": [{"name": "Bad tool", "tool": "steam-native"}],
          "11": [{"name": "Bad env", "env": {"VKBRIDGE_MODE": "safe"}}],
          "12": [{"name": "Bad driver", "when": {"driverMin": "new"}, "fexPreset": "STABILITY"}],
          "0": [{"name": "Not an app", "fexPreset": "STABILITY"}]
        }}
        """.trimIndent(),
    )!!

    @Test
    fun conditionsMatchGpuDriverVulkanAndBridge() {
        assertEquals(mapOf(KnownGames.DXVK to "dxvk-1.10.3-linux.wcp"), KnownGames.resolve(table, "220", g710)?.fields)
        assertNull(KnownGames.resolve(table, "220", g925))
        assertNull(KnownGames.resolve(table, "220", adreno))
        // Unknown Vulkan: a condition on it does not hold.
        assertNull(KnownGames.resolve(table, "220", g710.copy(vulkan = null)))
        assertEquals("1", KnownGames.resolve(table, "322330", g710)?.fields?.get("env:PROTON_USE_WINED3D"))
        val dst = KnownGames.resolve(table, "322330", g925)!!
        assertEquals("0", dst.fields["env:PROTON_USE_WINED3D"])
        assertEquals("Proton 11.0 (ARM64)", dst.fields[KnownGames.TOOL])
        assertTrue(dst.fields.getValue(KnownGames.BRIDGE).endsWith("VKBRIDGE_HEAP_MAX_MB=4096\n"))
        assertEquals(listOf("b", "c"), dst.sources)
        assertNull(KnownGames.resolve(table, "322330", g925.copy(driver = 47 to 9))?.fields?.get(KnownGames.TOOL))
        assertNull(KnownGames.resolve(table, "999", g925))
    }

    @Test
    fun badEntriesAndOtherSchemasAreLeftOut() {
        assertFalse(table.games.containsKey("10"))
        assertFalse(table.games.containsKey("11"))
        assertFalse(table.games.containsKey("12"))
        assertFalse(table.games.containsKey("0"))
        assertNull(KnownGames.parse("""{"schema": 2, "version": 9, "games": {}}"""))
        assertNull(KnownGames.parse("not json"))
    }

    @Test
    fun aNewerDownloadedTableWinsAnOlderOneDoesNot() {
        val older = table.copy(version = 2)
        val newer = table.copy(version = 4)
        assertEquals(4, KnownGames.choose(table, newer)?.version)
        assertEquals(3, KnownGames.choose(table, older)?.version)
        assertEquals(3, KnownGames.choose(table, null)?.version)
    }

    @Test
    fun maliReleaseFromDriverInfo() {
        assertEquals(32 to 1, KnownGames.maliRelease("v1.r32p1-01eac0.2b8d4e4f1a2cd9a"))
        assertNull(KnownGames.maliRelease("Qualcomm Technologies Inc. Adreno Vulkan Driver"))
    }

    @Test
    fun anUnsetFieldIsSetAndMarked() {
        val plan = KnownGames.plan(mapOf("env:A" to "1"), mapOf("env:A" to null), emptyMap())
        assertEquals(mapOf("env:A" to "1"), plan.set)
        assertEquals(mapOf("env:A" to Mark(By.MALIBRIDGE, "1")), plan.marks)
    }

    @Test
    fun somethingTheyAlreadySetIsThePlayers() {
        val plan = KnownGames.plan(mapOf("env:A" to "1"), mapOf("env:A" to "0"), emptyMap())
        assertFalse(plan.changes)
        assertEquals(By.PLAYER, plan.marks.getValue("env:A").by)
    }

    @Test
    fun aValueThePlayerChangedOrClearedStaysTheirsForGood() {
        val ours = mapOf("dxvk" to Mark(By.MALIBRIDGE, "dxvk-1.10.3-linux.wcp"))
        val changed = KnownGames.plan(mapOf("dxvk" to "dxvk-1.10.3-linux.wcp"), mapOf("dxvk" to "dxvk-2.7.1-linux.wcp"), ours)
        assertFalse(changed.changes)
        assertEquals(By.PLAYER, changed.marks.getValue("dxvk").by)
        // Cleared (or the game's profile reset): not put back.
        val cleared = KnownGames.plan(mapOf("dxvk" to "dxvk-1.10.3-linux.wcp"), mapOf("dxvk" to null), ours)
        assertFalse(cleared.changes)
        assertEquals(By.PLAYER, cleared.marks.getValue("dxvk").by)
        // And on later runs, whatever is there.
        val later = KnownGames.plan(mapOf("dxvk" to "dxvk-1.10.3-linux.wcp"), mapOf("dxvk" to null), cleared.marks)
        assertFalse(later.changes)
        assertEquals(cleared.marks, later.marks)
    }

    @Test
    fun whatMaliBridgeSetFollowsTheTable() {
        val ours = mapOf("env:A" to Mark(By.MALIBRIDGE, "1"))
        val same = KnownGames.plan(mapOf("env:A" to "1"), mapOf("env:A" to "1"), ours)
        assertFalse(same.changes)
        assertEquals(ours, same.marks)
        val updated = KnownGames.plan(mapOf("env:A" to "2"), mapOf("env:A" to "1"), ours)
        assertEquals(mapOf("env:A" to "2"), updated.set)
        assertEquals(Mark(By.MALIBRIDGE, "2"), updated.marks["env:A"])
        val withdrawn = KnownGames.plan(emptyMap(), mapOf("env:A" to "1"), ours)
        assertEquals(setOf("env:A"), withdrawn.clear)
        assertTrue(withdrawn.marks.isEmpty())
    }

    @Test
    fun marksSurviveTheFile() {
        val marks = mapOf("220" to mapOf("dxvk" to Mark(By.MALIBRIDGE, "dxvk-1.10.3-linux.wcp"), "env:A" to Mark(By.PLAYER, null)))
        assertEquals(marks, KnownGames.decodeMarks(KnownGames.encodeMarks(marks, 1).toString()))
    }

    @Test
    fun theShippedTableParsesAndEveryEntryCitesItsEvidence() {
        val text = File("src/main/assets/known-games.json").readText()
        val shipped = KnownGames.parse(text)
        assertNotNull(shipped)
        val raw = org.json.JSONObject(text).getJSONObject("games")
        assertEquals(raw.length(), shipped!!.games.size)
        for ((id, entries) in shipped.games) {
            assertEquals(raw.getJSONArray(id).length(), entries.size)
            entries.forEach { assertTrue("$id has no source", it.source.length > 20) }
        }
        // The Vulkan 1.1 phones' DXVK, on the G710 (DroidDeck #218).
        assertEquals("dxvk-1.10.3-linux.wcp", KnownGames.resolve(shipped, "220", g710)?.fields?.get(KnownGames.DXVK))
        assertNull(KnownGames.resolve(shipped, "220", g925))
        assertEquals("1", KnownGames.resolve(shipped, "322330", g925)?.fields?.get("env:PROTON_USE_WINED3D"))
        assertEquals(KnownGames.DEFAULT_TOOL, shipped.defaultTool)
        // Every shipped entry is for bridge sessions only: nothing is set on Adreno or with the bridge off.
        for ((id, entries) in shipped.games) entries.forEach { assertEquals("$id", true, it.condition.bridge) }
        for (id in shipped.games.keys) {
            assertNull(id, KnownGames.resolve(shipped, id, adreno))
            assertNull(id, KnownGames.resolve(shipped, id, g925.copy(bridge = false)))
        }
        assertEquals("STABILITY", KnownGames.resolve(shipped, "560430", g925)?.fields?.get(KnownGames.FEX))
    }

    @Test
    fun aChoiceForAllGamesIsThePlayers() {
        // Nothing set for the game, but the player set it for all games: a per-game value would override it.
        val plan = KnownGames.plan(mapOf("fex" to "STABILITY"), mapOf("fex" to null), emptyMap(), global = setOf("fex"))
        assertFalse(plan.changes)
        assertEquals(By.PLAYER, plan.marks.getValue("fex").by)
        // What MaliBridge set before goes, and the field is the player's from then on.
        val ours = mapOf("fex" to Mark(By.MALIBRIDGE, "STABILITY"))
        val later = KnownGames.plan(mapOf("fex" to "STABILITY"), mapOf("fex" to "STABILITY"), ours, global = setOf("fex"))
        assertEquals(setOf("fex"), later.clear)
        assertEquals(By.PLAYER, later.marks.getValue("fex").by)
    }

    @Test
    fun aStagedMarkIsMaliBridgesAtEitherValue() {
        val staged = Mark(By.MALIBRIDGE, "2", previous = "1", pending = true)
        // Not changed yet: changed now.
        val before = KnownGames.plan(mapOf("env:A" to "2"), mapOf("env:A" to "1"), mapOf("env:A" to staged))
        assertEquals(mapOf("env:A" to "2"), before.set)
        // Changed, final marks not written: just marked.
        val after = KnownGames.plan(mapOf("env:A" to "2"), mapOf("env:A" to "2"), mapOf("env:A" to staged))
        assertFalse(after.changes)
        assertEquals(Mark(By.MALIBRIDGE, "2"), after.marks["env:A"])
        // Something else there: the player's.
        assertEquals(By.PLAYER, KnownGames.plan(mapOf("env:A" to "2"), mapOf("env:A" to "3"), mapOf("env:A" to staged)).marks.getValue("env:A").by)
        // A take-back cut short: taken back (again), or already gone.
        val clearing = Mark(By.MALIBRIDGE, null, previous = "1", pending = true)
        assertEquals(setOf("env:A"), KnownGames.plan(emptyMap(), mapOf("env:A" to "1"), mapOf("env:A" to clearing)).clear)
        val gone = KnownGames.plan(emptyMap(), mapOf("env:A" to null), mapOf("env:A" to clearing))
        assertFalse(gone.changes)
        assertTrue(gone.marks.isEmpty())
    }

    @Test
    fun stagedMarksCoverEveryChange() {
        val plan = KnownGames.plan(mapOf("env:A" to "1", "env:B" to "2"), mapOf("env:A" to null, "env:B" to "5", "env:C" to "3"),
            mapOf("env:B" to Mark(By.MALIBRIDGE, "5"), "env:C" to Mark(By.MALIBRIDGE, "3")))
        val staged = KnownGamesApply.stagedMarks(plan, mapOf("env:A" to null, "env:B" to "5", "env:C" to "3"))
        assertEquals(Mark(By.MALIBRIDGE, "1", previous = null, pending = true), staged["env:A"])
        assertEquals(Mark(By.MALIBRIDGE, "2", previous = "5", pending = true), staged["env:B"])
        assertEquals(Mark(By.MALIBRIDGE, null, previous = "3", pending = true), staged["env:C"])
        assertEquals(staged, KnownGames.decodeMarks(KnownGames.encodeMarks(mapOf("1" to staged), 1).toString())["1"])
    }

    @Test
    fun aRunThatLeftSomethingUndoneIsTriedAgainLater() {
        val watch = KnownGamesApply.Watch(retryMs = 1000)
        val ids = setOf("220")
        assertTrue(watch.idsDue(ids, 0))
        watch.idsDone(ids, failed = true, at = 0)
        assertFalse(watch.idsDue(ids, 999))
        assertTrue(watch.idsDue(ids, 1000))
        watch.idsDone(ids, failed = false, at = 1000)
        assertFalse(watch.idsDue(ids, 999_999))
        assertTrue(watch.idsDue(ids + "294100", 1001))
    }

    @Test
    fun aProtonCountsOnceItReadsTheSameTwice() {
        val watch = KnownGamesApply.Watch(retryMs = 1000)
        assertFalse(watch.protonsDue("a@1")) // what the session started with
        assertFalse(watch.protonsDue("a@1"))
        assertFalse(watch.protonsDue("a@1|b@2:10")) // appeared: maybe still downloading
        assertFalse(watch.protonsDue("a@1|b@2:20")) // still changing
        assertTrue(watch.protonsDue("a@1|b@2:20")) // settled
        assertFalse(watch.protonsDue("a@1|b@2:20")) // once
        assertFalse(watch.protonsDue(null)) // a version file missing: not settled
        assertFalse(watch.protonsDue("a@1|b@3:30"))
        assertTrue(watch.protonsDue("a@1|b@3:30"))
    }
}
