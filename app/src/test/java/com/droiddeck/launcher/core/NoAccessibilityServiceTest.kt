package com.droiddeck.launcher.core

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The MaliBridge build declares no accessibility service: Play Protect refuses to install an APK that
 * declares one when it is sideloaded ("could steal your info"), and v0.5.4.2 installed fine without it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NoAccessibilityServiceTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun theManifestListsNoAccessibilityService() {
        val listed = context.packageManager.queryIntentServices(
            Intent("android.accessibilityservice.AccessibilityService").setPackage(context.packageName),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        assertTrue(listed.joinToString { it.serviceInfo.name }, listed.isEmpty())
    }

    @Test fun noServiceAsksForTheAccessibilityBindPermission() {
        val services = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_SERVICES or PackageManager.MATCH_DISABLED_COMPONENTS,
        ).services.orEmpty()
        val binding = services.filter { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" }
        assertTrue(binding.joinToString { it.name }, binding.isEmpty())
    }

    @Test fun theSteamIntegrationOffersOnlyWhatNeedsNoSpecialAccess() {
        assertEquals(listOf("WIFI", "BRIGHTNESS", "HOME"), SteamIntegration.Feature.entries.map { it.name })
    }
}
