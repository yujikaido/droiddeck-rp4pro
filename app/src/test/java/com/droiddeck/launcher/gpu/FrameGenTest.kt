package com.droiddeck.launcher.gpu

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FrameGenTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("frame_gen", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun offUntilPicked() {
        assertEquals(FrameGen.Mode.OFF, FrameGen.mode(context))
    }

    @Test fun adaptiveTargetIsKept() {
        FrameGen.set(context, FrameGen.Mode(FrameGen.ENGINE_LSFG, target = 90))
        assertEquals(FrameGen.Mode(FrameGen.ENGINE_LSFG, target = 90), FrameGen.mode(context))
    }

    @Test fun fixedMultiplierReplacesATarget() {
        FrameGen.set(context, FrameGen.Mode(FrameGen.ENGINE_LSFG, target = 120))
        FrameGen.set(context, FrameGen.Mode(FrameGen.ENGINE_LSFG, 3))
        assertEquals(FrameGen.Mode(FrameGen.ENGINE_LSFG, 3), FrameGen.mode(context))
    }

    @Test fun onlyLsfgIsAdaptive() {
        context.getSharedPreferences("frame_gen", Context.MODE_PRIVATE).edit()
            .putString("engine", FrameGen.ENGINE_DDFG).putInt("multiplier", 4).putInt("target", 60).commit()
        assertEquals(FrameGen.Mode(FrameGen.ENGINE_DDFG, 4), FrameGen.mode(context))
    }

    @Test fun aSavedWinFgChoiceBecomesDdFg() {
        context.getSharedPreferences("frame_gen", Context.MODE_PRIVATE).edit()
            .putString("engine", "winfg").putInt("multiplier", 2).commit()
        assertEquals(FrameGen.Mode(FrameGen.ENGINE_DDFG, 2), FrameGen.mode(context))
    }

    @Test fun noFrameGenerationInTheDesktopOrInPictureInPicture() {
        val chosen = FrameGen.Mode(FrameGen.ENGINE_LSFG, 3)
        assertEquals(FrameGen.Mode.OFF, FrameGen.armedMode(com.droiddeck.launcher.session.SessionService.MODE_DESKTOP, false, chosen))
        assertEquals(FrameGen.Mode.OFF, FrameGen.armedMode("steam", true, chosen))
        assertEquals(chosen, FrameGen.armedMode("steam", false, chosen))
        assertEquals(chosen, FrameGen.armedMode("run", false, chosen))
        assertEquals(chosen, FrameGen.armedMode(null, false, chosen))
    }

    @Test fun noFrameGenerationOnTheSteamMenu() {
        val chosen = FrameGen.Mode(FrameGen.ENGINE_DDFG, 2)
        val steam = com.droiddeck.launcher.session.SessionService.MODE_STEAM
        assertEquals(FrameGen.Mode.OFF, FrameGen.armedMode(steam, false, chosen, steamClientFocused = true))
        assertEquals(chosen, FrameGen.armedMode(steam, false, chosen, steamClientFocused = false))
        assertEquals(chosen, FrameGen.armedMode(steam, false, chosen))
        // a program run has no Steam client to be on
        assertEquals(chosen, FrameGen.armedMode("run", false, chosen, steamClientFocused = true))
    }

    @Test fun steamClientFocusFromTheFocusSnapshot() {
        // Big Picture focused over a game in the base layer
        assertTrue(FrameGen.steamClientFocused(769L, listOf(769L)))
        assertTrue(FrameGen.steamClientFocused(769L, listOf(413091L, 769L)))
        assertTrue(FrameGen.steamClientFocused(null, listOf(769L)))
        assertFalse(FrameGen.steamClientFocused(413091L, listOf(413091L, 769L)))
        assertFalse(FrameGen.steamClientFocused(null, emptyList()))      // loading: nothing known yet
        assertFalse(FrameGen.steamClientFocused(null, listOf(413091L, 769L)))
        assertTrue(com.droiddeck.launcher.agent.AgentGuest.steamClientFocused(
            org.json.JSONObject("""{"focusedApp":769,"focusableApps":[769],"baselayerAppIds":[413091,769]}""")))
        assertTrue(com.droiddeck.launcher.agent.AgentGuest.steamClientFocused(
            org.json.JSONObject("""{"focusedApp":null,"focusableApps":[769]}""")))
        assertFalse(com.droiddeck.launcher.agent.AgentGuest.steamClientFocused(
            org.json.JSONObject("""{"focusedApp":413091,"focusableApps":[413091,769]}""")))
        assertFalse(com.droiddeck.launcher.agent.AgentGuest.steamClientFocused(org.json.JSONObject("""{"focusedApp":null,"focusableApps":[]}""")))
        assertFalse(com.droiddeck.launcher.agent.AgentGuest.steamClientFocused(null))
    }
}
