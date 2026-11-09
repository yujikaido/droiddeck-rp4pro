package com.droiddeck.launcher.ui

import android.content.res.Configuration
import com.droiddeck.launcher.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ResolutionHintTest {
    private fun hint(tag: String): String {
        val context = RuntimeEnvironment.getApplication()
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return context.createConfigurationContext(config).getString(R.string.display_resolution_hint)
    }

    @Test fun theHintTellsWhereAbove720pIs() {
        assertTrue(hint("en"), hint("en").contains("Custom") && hint("en").contains("720p"))
    }

    @Test fun everyLocaleHasItWithTheNumbers() {
        for (tag in listOf("zh-CN", "zh-TW", "zh-HK", "ko")) {
            val text = hint(tag)
            assertTrue("$tag: $text", text.contains("720p") && text.contains("3840") && text != hint("en"))
        }
    }
}
