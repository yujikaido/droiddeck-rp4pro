package com.droiddeck.launcher.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import com.droiddeck.launcher.R
import org.json.JSONObject

object Rp4ProControls {
    private const val TAG = "Rp4ProControls"
    // Retroid's SystemUI tiles keep these in Settings.System; only system apps may write them.
    private const val FAN_MODE = "fan_mode"
    private const val PERFORMANCE_MODE = "performance_mode"

    data class State(val fan: Int?, val performance: Int?, val wifi: Boolean?, val bluetooth: Boolean?)

    fun supported(context: Context) = fanMode(context) != null || performanceMode(context) != null

    fun fanMode(context: Context) = Settings.System.getString(context.contentResolver, FAN_MODE)?.toIntOrNull()

    fun performanceMode(context: Context) = Settings.System.getString(context.contentResolver, PERFORMANCE_MODE)?.toIntOrNull()

    fun state(context: Context) = State(fanMode(context), performanceMode(context), wifiOn(context), bluetoothOn(context))

    @StringRes fun fanLabel(mode: Int?) = when (mode) {
        0 -> R.string.rp4_fan_off
        1 -> R.string.rp4_fan_quiet
        2 -> R.string.rp4_fan_balance
        3 -> R.string.rp4_fan_performance
        4 -> R.string.rp4_fan_smart
        5 -> R.string.rp4_fan_sport
        6 -> R.string.rp4_fan_custom
        else -> R.string.rp4_unknown
    }

    @StringRes fun performanceLabel(mode: Int?) = when (mode) {
        0 -> R.string.rp4_perf_standard
        1 -> R.string.rp4_perf_performance
        2 -> R.string.rp4_perf_high
        else -> R.string.rp4_unknown
    }

    fun wifiOn(context: Context): Boolean? = try {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    } catch (e: Exception) { null }

    // setWifiEnabled still works for apps targeting API 28 and lower.
    @Suppress("DEPRECATION")
    fun setWifi(context: Context, on: Boolean): Boolean = try {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).setWifiEnabled(on)
    } catch (e: Exception) {
        Log.w(TAG, "Could not switch Wi-Fi", e)
        false
    }

    @SuppressLint("MissingPermission")
    fun bluetoothOn(context: Context): Boolean? = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
    } catch (e: Exception) { null }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun setBluetooth(context: Context, on: Boolean): Boolean = try {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null -> false
            on -> adapter.enable()
            else -> adapter.disable()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not switch Bluetooth", e)
        false
    }

    // Retroid's Fan and Performance tiles live in the quick settings panel.
    @SuppressLint("WrongConstant")
    fun openQuickSettings(context: Context): Boolean = try {
        val service = context.getSystemService("statusbar")
        service.javaClass.getMethod("expandSettingsPanel").invoke(service)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not open quick settings", e)
        false
    }

    fun json(context: Context): JSONObject? {
        if (!supported(context)) return null
        val s = state(context)
        return JSONObject()
            .put("fan", s.fan ?: -1).put("fanLabel", context.getString(fanLabel(s.fan)))
            .put("performance", s.performance ?: -1).put("performanceLabel", context.getString(performanceLabel(s.performance)))
            .put("wifi", s.wifi == true).put("bluetooth", s.bluetooth == true)
    }
}
