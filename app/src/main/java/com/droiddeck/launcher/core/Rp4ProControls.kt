package com.droiddeck.launcher.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import org.json.JSONObject

object Rp4ProControls {
    private const val TAG = "Rp4ProControls"

    data class State(val wifi: Boolean?, val bluetooth: Boolean?)

    fun supported() = Build.MODEL == "Retroid Pocket 4 Pro"

    fun state(context: Context) = State(wifiOn(context), bluetoothOn(context))

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

    fun json(context: Context): JSONObject? {
        if (!supported()) return null
        val s = state(context)
        return JSONObject().put("wifi", s.wifi == true).put("bluetooth", s.bluetooth == true)
    }
}
