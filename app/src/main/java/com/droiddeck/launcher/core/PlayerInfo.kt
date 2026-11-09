package com.droiddeck.launcher.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.VulkanInfo
import com.droiddeck.launcher.session.ProtonDefault
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import java.net.URLEncoder

/** The version the player knows the app by. */
internal fun appVersionLabel(): String = BuildConfig.MALIBRIDGE_VERSION.ifEmpty { BuildConfig.VERSION_NAME }

/** Puts [text] on the clipboard and says so. */
internal fun copyText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
}

/** The "Your device" line in Setup: chip, GPU, driver release, Vulkan, Android and a one-word verdict. */
object DeviceCard {
    enum class Verdict { SUPPORTED, LIMITED, UNKNOWN }

    data class Info(val soc: String, val gpu: String, val driver: String, val vulkan: String, val android: String)

    private val RELEASE = Regex("""\br\d+p\d+\b""")

    /** "r44p1" from the driver's build text; the plain driver version when it carries no release. */
    fun driverRevision(driverInfo: String?, driverVersion: String?): String =
        driverInfo?.let { RELEASE.find(it)?.value } ?: driverVersion.orEmpty().trim()

    fun verdict(support: GpuInfo.Support, bridge: Boolean, gpuKnown: Boolean): Verdict = when {
        !gpuKnown -> Verdict.UNKNOWN
        support == GpuInfo.Support.TESTED || (support == GpuInfo.Support.UNSUPPORTED && bridge) -> Verdict.SUPPORTED
        else -> Verdict.LIMITED
    }

    /** Reads the phone; the Vulkan query is native, so call it off the main thread. */
    fun line(context: Context): String {
        val vk = VulkanInfo.query()
        val gpu = GpuInfo.detect()
        val name = DeviceSupport.gpuName(context)
        val soc = if (Build.VERSION.SDK_INT >= 31) listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
            .filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" ") else Build.HARDWARE
        val known = vk["error"] == null && (vk["device"] != null || name.isNotBlank())
        val label = when (verdict(gpu.support, DeviceSupport.vkBridge(context), known)) {
            Verdict.SUPPORTED -> R.string.device_card_supported
            Verdict.LIMITED -> R.string.device_card_limited
            Verdict.UNKNOWN -> R.string.device_card_unknown
        }
        val info = Info(soc, vk["device"] ?: name, driverRevision(vk["driver_info"], vk["driver_version"]), vk["api"].orEmpty(), Build.VERSION.RELEASE)
        return line(info, context.getString(label))
    }

    fun line(info: Info, verdict: String): String = listOfNotNull(
        info.soc.ifBlank { null },
        info.gpu.ifBlank { null },
        info.driver.ifBlank { null }?.let { "driver $it" },
        info.vulkan.ifBlank { null }?.let { "Vulkan $it" },
        info.android.ifBlank { null }?.let { "Android $it" },
        verdict.ifBlank { null },
    ).joinToString(" · ")
}

/** The plain-text settings summary a player can paste into a forum post. */
object SettingsSummary {
    private val TOKEN = Regex("""[A-Za-z0-9+/_\-]{32,}""")
    private val LINK_QUERY = Regex("""(https?://[^\s?]+)\?\S*""")

    /** Only settings and versions: nothing from accounts, logins or keys is read. */
    fun text(context: Context): String {
        val steam = SessionService.MODE_STEAM
        val proton = ProtonDefault.state(context).let { st -> st.tools.firstOrNull { it.name == st.default }?.display }
        return text(listOf(
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "MaliBridge" to appVersionLabel(),
            "Mode" to if (DeviceSupport.vkBridge(context)) "MaliBridge" else "Native",
            "Safe mode" to if (SessionPrefs.vkBridgeSafe(context)) "on" else "off",
            "Resolution" to SessionPrefs.resolutionChoice(context, steam, com.droiddeck.launcher.session.SessionDisplay.panelSize(context)),
            "Proton" to proton,
            "DXVK" to if (SessionPrefs.bridgeProtonDxvk(context)) "Proton's own" else "Auto",
            "FEX preset" to SessionPrefs.fexPreset(context),
            "Steam menus" to SessionPrefs.steamMenus(context),
            "Compositing" to SessionPrefs.bridgeComposite(context),
            "FPS HUD" to if (SessionPrefs.hudEnabled(context)) "on" else "off",
        ))
    }

    fun text(fields: List<Pair<String, String?>>): String = buildString {
        for ((key, value) in fields) {
            val clean = value?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            append(key).append(": ").append(clean.replace(LINK_QUERY, "$1").replace(TOKEN, "[hidden]")).append('\n')
        }
    }
}

/** malibridge.com/submit with the device and version filled in. */
object ReportLink {
    const val BASE = "https://malibridge.com/submit"

    fun intent(context: Context): android.content.Intent = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse(url("${Build.MANUFACTURER} ${Build.MODEL}", DeviceSupport.gpuName(context), appVersionLabel())),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    fun url(device: String, gpu: String, version: String): String {
        fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20")
        val query = listOf("device" to device, "gpu" to gpu, "version" to version)
            .filter { it.second.isNotBlank() }
            .joinToString("&") { "${it.first}=${enc(it.second)}" }
        return if (query.isEmpty()) BASE else "$BASE?$query"
    }
}
