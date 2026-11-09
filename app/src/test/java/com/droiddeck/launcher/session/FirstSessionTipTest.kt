package com.droiddeck.launcher.session

import android.content.Context
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
class FirstSessionTipTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("first_session_tip", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun theTipIsDueOnceAndNeverAgain() {
        assertTrue(FirstSessionTip.due(context))
        assertFalse(FirstSessionTip.due(context))
        assertFalse(FirstSessionTip.due(context))
    }

    @Test fun showingTheTipLeavesTheSessionPrefsAlone() {
        val before = context.getSharedPreferences("session", Context.MODE_PRIVATE).all
        FirstSessionTip.due(context)
        assertTrue(before == context.getSharedPreferences("session", Context.MODE_PRIVATE).all)
    }
}
