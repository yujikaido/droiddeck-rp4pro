package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.gpu.VkBridgeComponent
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class KnownGamesApplyTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun table() {
        // A downloaded copy newer than the shipped table is the one used.
        File(context.filesDir, "known-games.json").writeText(
            """
            {"schema": 1, "version": 999, "games": {
              "4242": [{"name": "Test Game", "env": {"FOO": "1"}, "fexPreset": "STABILITY", "source": "test"}],
              "4343": [{"name": "Tool Game", "tool": "proton-11-arm64", "source": "test"}],
              "4444": [{"name": "DXVK Game", "dxvk": "1.10.3", "source": "test"}],
              "4545": [{"name": "Bridge Game", "bridge": {"VKBRIDGE_HEAP_MAX_MB": "4096"}, "source": "test"}]
            }}
            """.trimIndent(),
        )
    }

    private fun env(id: String) = GameEnvironmentStore.read(context).games[id].orEmpty()

    @Test
    fun setsWhatIsUnsetAndSaysSo() {
        val lines = KnownGamesApply.apply(context, listOf("4242"), download = false).lines
        assertEquals("1", env("4242")["FOO"])
        assertEquals("STABILITY", GameEnvironment.gameFexPreset(GameEnvironmentStore.read(context), "4242"))
        assertTrue(lines.toString(), lines.any { "Test Game (4242): set by MaliBridge" in it && "FOO=1" in it })
        assertTrue(KnownGamesApply.note(context, "4242")!!.contains("FOO=1"))
        // A second run changes nothing and says nothing.
        assertTrue(KnownGamesApply.apply(context, listOf("4242"), download = false).lines.isEmpty())
    }

    @Test
    fun neverTakesBackThePlayersChange() {
        KnownGamesApply.apply(context, listOf("4242"), download = false)
        GameEnvironmentStore.save(context, GameEnvironment.withScopeValue(GameEnvironmentStore.read(context), "4242", "FOO", "0"))
        KnownGamesApply.apply(context, listOf("4242"), download = false)
        assertEquals("0", env("4242")["FOO"])
        assertEquals(KnownGames.By.PLAYER, KnownGamesApply.marks(context)["4242"]?.get("env:FOO")?.by)
        assertEquals(false, KnownGamesApply.note(context, "4242")?.contains("FOO"))
    }

    @Test
    fun aProfileResetIsThePlayersChoiceToo() {
        KnownGamesApply.apply(context, listOf("4242"), download = false)
        runCatching { GameProfileManager.reset(context, "4242") } // the environment is cleared first, whatever else fails here
        KnownGamesApply.apply(context, listOf("4242"), download = false)
        assertTrue(env("4242").isEmpty())
        assertNull(KnownGamesApply.note(context, "4242"))
    }

    @Test
    fun aSettingAlreadyThereBeforeIsLeftAlone() {
        GameEnvironmentStore.save(context, GameEnvironment.withScopeValue(GameEnvironmentStore.read(context), "4242", "FOO", "7"))
        val lines = KnownGamesApply.apply(context, listOf("4242"), download = false).lines
        assertEquals("7", env("4242")["FOO"])
        assertTrue(lines.toString(), lines.any { "player's own choice kept" in it && "FOO=1" in it })
    }

    @Test
    fun theToolIsASuggestionForTheGuestAndAnAppPickWins() {
        KnownGamesApply.apply(context, listOf("4343"), download = false)
        val guest = File(LinuxRuntime.rootDir(context), "root/.local/share/droiddeck-compat/known-games.json")
        val entry = JSONObject(guest.readText()).getJSONObject("games").getJSONObject("4343")
        assertEquals("Proton 11.0 (ARM64)", entry.getString("dir"))
        assertEquals(true, entry.getBoolean("valve"))
        // The player says "use the general one" for that game in the app.
        ProtonDefault.requestGame(context, "4343", null)
        KnownGamesApply.apply(context, listOf("4343"), download = false)
        assertEquals(KnownGames.By.PLAYER, KnownGamesApply.marks(context)["4343"]?.get(KnownGames.TOOL)?.by)
    }

    @Test
    fun aChoiceForAllGamesIsThePlayersAndNotOverridden() {
        // FOO set in the Default profile, and a general FEX preset other than the default.
        GameEnvironmentStore.save(context, GameEnvironment.withScopeValue(GameEnvironmentStore.read(context), "", "FOO", "0"))
        SessionPrefs.setFexPreset(context, "COMPATIBILITY")
        val lines = KnownGamesApply.apply(context, listOf("4242"), download = false).lines
        assertTrue(env("4242").isEmpty())
        val marks = KnownGamesApply.marks(context)["4242"].orEmpty()
        assertEquals(KnownGames.By.PLAYER, marks["env:FOO"]?.by)
        assertEquals(KnownGames.By.PLAYER, marks[KnownGames.FEX]?.by)
        assertTrue(lines.toString(), lines.any { "player's own choice kept" in it && "FEX preset STABILITY" in it })
    }

    @Test
    fun whatMaliBridgeSetGoesWhenThePlayerSetsItForAllGames() {
        KnownGamesApply.apply(context, listOf("4242"), download = false)
        GameEnvironmentStore.save(context, GameEnvironment.withScopeValue(GameEnvironmentStore.read(context), "", "FOO", "0"))
        KnownGamesApply.apply(context, listOf("4242"), download = false)
        assertNull(env("4242")["FOO"])
        assertEquals(KnownGames.By.PLAYER, KnownGamesApply.marks(context)["4242"]?.get("env:FOO")?.by)
        // The FEX preset, not set for all games, stays MaliBridge's.
        assertEquals("STABILITY", GameEnvironment.gameFexPreset(GameEnvironmentStore.read(context), "4242"))
    }

    @Test
    fun aRunCutShortLeavesTheFieldsMaliBridgesToFinish() {
        // Marks staged by a run that stopped before the environment was saved (FOO not there), and one that
        // stopped after it was saved but before the final marks (the FEX preset there).
        GameEnvironmentStore.save(context, GameEnvironment.withGameFexPreset(GameEnvironmentStore.read(context), "4242", "STABILITY"))
        val staged = mapOf("4242" to mapOf(
            "env:FOO" to KnownGames.Mark(KnownGames.By.MALIBRIDGE, "1", previous = null, pending = true),
            KnownGames.FEX to KnownGames.Mark(KnownGames.By.MALIBRIDGE, "STABILITY", previous = null, pending = true),
        ))
        File(context.filesDir, "known-games-applied.json").writeText(KnownGames.encodeMarks(staged, 999).toString())
        KnownGamesApply.apply(context, listOf("4242"), download = false)
        assertEquals("1", env("4242")["FOO"])
        assertEquals(
            mapOf("env:FOO" to KnownGames.Mark(KnownGames.By.MALIBRIDGE, "1"), KnownGames.FEX to KnownGames.Mark(KnownGames.By.MALIBRIDGE, "STABILITY")),
            KnownGamesApply.marks(context)["4242"],
        )
    }

    @Test
    fun aDownloadStillToDoIsSaidAsFailedAndLeftUnmarked() {
        val result = KnownGamesApply.apply(context, listOf("4444"), download = false)
        assertTrue(result.failed)
        assertTrue(result.lines.toString(), result.lines.any { "DXVK 1.10.3 not set" in it })
        assertNull(KnownGamesApply.marks(context)["4444"])
        assertFalse(KnownGamesApply.apply(context, listOf("4242"), download = false).failed)
    }

    @Test
    fun whatCouldNotBeTakenBackStaysMaliBridgesToTakeBackLater() {
        KnownGamesApply.apply(context, listOf("4545"), download = false)
        val preset = File(VkBridgeComponent.cacheDir(context), "presets/4545.env")
        assertTrue(preset.isFile)
        // The table no longer has the game, and the preset cannot be deleted for now.
        File(context.filesDir, "known-games.json").writeText("""{"schema": 1, "version": 1000, "games": {}}""")
        val presets = preset.parentFile!!
        presets.setWritable(false)
        try {
            val result = KnownGamesApply.apply(context, listOf("4545"), download = false)
            assertTrue(result.failed)
            assertEquals(KnownGames.By.MALIBRIDGE, KnownGamesApply.marks(context)["4545"]?.get(KnownGames.BRIDGE)?.by)
        } finally {
            presets.setWritable(true)
        }
        KnownGamesApply.apply(context, listOf("4545"), download = false)
        assertFalse(preset.exists())
        assertNull(KnownGamesApply.marks(context)["4545"])
    }
}
