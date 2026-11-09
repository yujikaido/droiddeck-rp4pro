package com.droiddeck.launcher.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.droiddeck.launcher.HomeApp
import com.droiddeck.launcher.session.SessionPrefs

object SteamIntegration {
    const val ACTION_SETUP = "com.droiddeck.launcher.STEAM_INTEGRATION"
    const val EXTRA_FEATURE = "integration_feature"
    enum class Feature { WIFI, BRIGHTNESS, HOME }

    data class State(
        val wifi: Boolean = false,
        val locationPermission: Boolean = false,
        val locationOn: Boolean = false,
        val locationBlocked: Boolean = false,
        val brightness: Boolean = false,
        val home: Boolean = false,
        val suggested: Boolean = true,
    )

    fun read(context: Context) = State(
        wifi = SessionPrefs.wifiDiscoveryEnabled(context),
        locationPermission = WifiDiscovery.permissionGranted(context),
        locationOn = WifiDiscovery.locationEnabled(context),
        brightness = Settings.System.canWrite(context),
        home = HomeApp.isDefault(context),
        suggested = !SessionPrefs.sessionPrefs(context).getBoolean("steam_integration_seen", false),
    )

    fun dismissSuggestion(context: Context) {
        SessionPrefs.sessionPrefs(context).edit().putBoolean("steam_integration_seen", true).apply()
    }

    fun brightnessSettings(context: Context) = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
        Uri.parse("package:${context.packageName}"))
}
