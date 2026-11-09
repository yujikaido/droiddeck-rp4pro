package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.TextureFiltering
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
class LowSpecPresetTest {
    private lateinit var context: Context
    private val panel = 2960 to 1848
    private val mode = SessionService.MODE_STEAM

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("low_spec_preset", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun applyWritesExactlyTheListedPrefs() {
        SessionPrefs.setResolutionChoice(context, mode, "1080p")
        SessionPrefs.setTextureAnisotropy(context, 16)
        SessionPrefs.setTextureLodBias(context, TextureFiltering.LOD_BIAS_AUTO)
        SessionPrefs.setHdr(context, mode, true)
        LowSpecPreset.apply(context, mode, panel)

        assertTrue(LowSpecPreset.active(context, mode))
        assertEquals("540p", SessionPrefs.resolutionChoice(context, mode, panel))
        assertEquals(0, SessionPrefs.textureAnisotropy(context))
        assertEquals(TextureFiltering.LOD_BIAS_OFF, SessionPrefs.textureLodBias(context))
        assertTrue("HDR is not part of the preset", SessionPrefs.hdr(context, mode))
    }

    @Test fun resetRestoresWhatThePlayerHad() {
        SessionPrefs.setResolutionChoice(context, mode, "1080p")
        SessionPrefs.setTextureAnisotropy(context, 16)
        SessionPrefs.setTextureLodBias(context, TextureFiltering.LOD_BIAS_AUTO)
        LowSpecPreset.apply(context, mode, panel)
        LowSpecPreset.reset(context, mode, panel)

        assertFalse(LowSpecPreset.active(context, mode))
        assertEquals("1080p", SessionPrefs.resolutionChoice(context, mode, panel))
        assertEquals(16, SessionPrefs.textureAnisotropy(context))
        assertEquals(TextureFiltering.LOD_BIAS_AUTO, SessionPrefs.textureLodBias(context))
    }

    @Test fun applyingTwiceKeepsTheFirstBackup() {
        SessionPrefs.setResolutionChoice(context, mode, "900p")
        LowSpecPreset.apply(context, mode, panel)
        LowSpecPreset.apply(context, mode, panel)
        LowSpecPreset.reset(context, mode, panel)
        assertEquals("900p", SessionPrefs.resolutionChoice(context, mode, panel))
    }

    @Test fun resetWithoutApplyChangesNothing() {
        SessionPrefs.setResolutionChoice(context, mode, "900p")
        LowSpecPreset.reset(context, mode, panel)
        assertEquals("900p", SessionPrefs.resolutionChoice(context, mode, panel))
    }

    @Test fun nothingChangesUntilApplied() {
        assertFalse(LowSpecPreset.active(context, mode))
        assertEquals("720p", SessionPrefs.resolutionChoice(context, mode, panel))
    }
}
