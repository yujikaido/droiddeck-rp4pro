package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import java.io.File

object DeviceSupport {
    private val adrenoGpu by lazy {
        File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()
    }

    private val maliGpu by lazy { !adrenoGpu && File("/dev/mali0").exists() }

    fun adreno(): Boolean = adrenoGpu

    fun mali(): Boolean = maliGpu

    /** On a Mali phone the bridge is the only way to draw (Turnip needs an Adreno), so a "Mali bridge: off"
     * in Setup is ignored there; only the hidden pref vkBridgeForceOff=true (debugging) really turns it off. */
    fun vkBridge(context: Context): Boolean =
        vkBridgeFor(adreno(), mali(), com.droiddeck.launcher.session.SessionPrefs.vkBridge(context),
            com.droiddeck.launcher.session.SessionPrefs.vkBridgeForceOff(context))

    /** True when the Setup switch is off on a Mali phone and the bridge is used anyway. */
    fun bridgeTurnedOn(context: Context): Boolean =
        bridgeTurnedOnFor(adreno(), mali(), com.droiddeck.launcher.session.SessionPrefs.vkBridge(context),
            com.droiddeck.launcher.session.SessionPrefs.vkBridgeForceOff(context))

    internal fun bridgeTurnedOnFor(adreno: Boolean, mali: Boolean, switchOn: Boolean, forceOff: Boolean): Boolean =
        mali && !adreno && !switchOn && !forceOff

    internal fun vkBridgeFor(adreno: Boolean, mali: Boolean, switchOn: Boolean, forceOff: Boolean): Boolean =
        !adreno && (switchOn || bridgeTurnedOnFor(adreno, mali, switchOn, forceOff))

    const val BRIDGE_TURNED_ON_LINE = "Mali bridge was off in Setup; turned on for this session"

    fun canDraw(context: Context): Boolean = adreno() || vkBridge(context)

    /** The GPU's name. Adreno phones: the chip label as before. Others: the GPU itself ("Mali-G720-Immortalis MC12"),
     *  or the chip label when it is not known yet. */
    fun gpuName(context: Context): String =
        (if (adreno()) null else com.droiddeck.launcher.gpu.MaliGpuNames.resolve(context)) ?: chipLabel(context)

    /** "SOC_MODEL (hardware)": the chip as the system names it; what logs and uploads carry. */
    fun chipLabel(context: Context): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        return soc?.let { "$it (${Build.HARDWARE})" } ?: Build.HARDWARE.ifBlank { context.getString(R.string.gpu_this_gpu) }
    }

    fun lowLatencyWifiSafe(): Boolean = Build.VERSION.SDK_INT < 31 || Build.SOC_MODEL != "CQ8725S"
}
