package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import java.io.File

object DeviceSupport {
    fun adreno(): Boolean =
        File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()

    /**
     * Whether the Linux side draws through the Vulkan bridge (external/MaliBridge): asked for in Setup
     * and only on a GPU that is not an Adreno - an Adreno keeps Turnip whatever the setting says.
     */
    fun vkBridge(context: Context): Boolean =
        !adreno() && com.droiddeck.launcher.session.SessionPrefs.vkBridge(context)

    /** A session can draw: Adreno with Turnip, or anything else with the bridge turned on. */
    fun canDraw(context: Context): Boolean = adreno() || vkBridge(context)

    /** The chip as the device names it, for the card that explains the refusal. */
    fun gpuName(context: Context): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        return soc?.let { "$it (${Build.HARDWARE})" } ?: Build.HARDWARE.ifBlank { context.getString(R.string.gpu_this_gpu) }
    }

    fun lowLatencyWifiSafe(): Boolean = Build.VERSION.SDK_INT < 31 || Build.SOC_MODEL != "CQ8725S"
}
