package com.droiddeck.launcher.stores.epic

import android.os.Looper
import org.robolectric.RuntimeEnvironment
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresNative
import com.droiddeck.launcher.stores.StoresState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Without the native store engine (a build made without the Rust step), Epic's sign-in used to open
 * the login page and close it at once with no word. It now says why and opens nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EpicSignInEngineTest {
    @Test fun signInWithoutTheEngineExplainsInsteadOfOpeningAPage() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse("the JVM has no libdroiddeckstores.so", StoresNative.ensureLoaded())
        StoresState.problems.remove(Store.EPIC)
        EpicBackend.signIn(app)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("no login page is started", shadowOf(app).nextStartedActivity)
        val line = StoresState.problems[Store.EPIC].orEmpty()
        assertTrue(line, line.contains("Epic") && line.contains("store engine"))
        assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("store engine"))
    }
}
