package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.appVersionLabel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class SetupAboutTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private var sends = 0

    @Test fun theSetupAboutLineShowsTheBuildVersion() {
        show()
        val expected = BuildConfig.MALIBRIDGE_VERSION.ifEmpty { BuildConfig.VERSION_NAME }
        assertEquals(expected, appVersionLabel())
        compose.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test fun theSendLogsRowIsInSetupAndRunsTheSameAction() {
        show()
        compose.onNodeWithText("Send").assertIsDisplayed().performClick()
        assertEquals(1, sends)
    }

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus()) {
                    FocusGlideHost { Box { SetupAboutGroup { sends++ } } }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
    }
}
