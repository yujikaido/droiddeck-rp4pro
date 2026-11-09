package com.droiddeck.launcher.gpu

import android.content.Context
import com.droiddeck.launcher.core.FileUtils
import java.io.File

/**
 * The GPU's own name on a phone that is not an Adreno one ("Mali-G720-Immortalis MC12"), instead of the chip code
 * ("mt6989"). Sources, cheapest first, none of them a new query at app start:
 *  1. the bridge server's `apps-vulkan` file in the app's cache directory (its `device=` line is the first GPU's
 *     Vulkan name; written at every bridge start, so it exists from the first session on);
 *  2. the Vulkan name the app has already read this run ([VulkanInfo.cachedOrNull]);
 *  3. a table from the chip's hardware name to the GPU family (without the core count, which varies between
 *     phones with the same chip), built from device reports of real phones.
 */
object MaliGpuNames {
    /** Hardware name (Build.HARDWARE / ro.board.platform, lower case) to the GPU family. Each entry was seen in a
     *  device report with that GPU; the core count is left out because it differs between phones on one chip. */
    internal val FAMILY = mapOf(
        "mt6989" to "Mali-G720-Immortalis", "mt6991" to "Mali-G925-Immortalis", "mt6993" to "Mali-G1-Ultra",
        "mt6985" to "Mali-G715-Immortalis", "mt6983" to "Mali-G710", "mt6897" to "Mali-G615", "mt6899" to "Mali-G720",
        "mt6895" to "Mali-G610", "mt6886" to "Mali-G610", "mt6879" to "Mali-G610", "mt6878" to "Mali-G615",
        "mt6877" to "Mali-G68", "mt6833" to "Mali-G57", "mt6789" to "Mali-G57",
        "mt8797" to "Mali-G77", "mt8798" to "Mali-G710", "mt8795" to "Mali-G610", "mt8799" to "Mali-G925-Immortalis",
        "mt8910" to "Mali-G1-Ultra",
        "s5e8835" to "Mali-G68", "s5e8845" to "Xclipse 530", "s5e8855" to "Xclipse 540", "s5e9945" to "Xclipse 940",
        "exynos2100" to "Mali-G78", "samsungexynos9810" to "Mali-G72",
        "tegu" to "Mali-G715", "raven" to "Mali-G78",
        "o1_asic" to "Mali-G925-Immortalis", "xring_o3_asic" to "Mali-G2-Ultra-NX",
        "kirin9000" to "Mali-G78", "kirin9000e" to "Mali-G78", "kirin810" to "Mali-G52", "kirin9000we" to "Maleoon 910",
        "ums9620_2h10" to "Mali-G57",
    )

    private val NOT_A_GPU = Regex("(?i)llvmpipe|swiftshader|software|lavapipe")

    /** A Vulkan device name worth showing: not blank, not a software renderer, not an Adreno name (those keep their own path). */
    internal fun usable(name: String?): String? =
        name?.trim()?.takeIf { it.isNotEmpty() && !NOT_A_GPU.containsMatchIn(it) && !it.startsWith("Adreno", ignoreCase = true) }

    internal fun fromAppsFile(text: String?): String? =
        text?.lineSequence()?.firstOrNull { it.startsWith("device=") }?.substringAfter('=').let { usable(it) }

    internal fun fromTable(vararg hardware: String): String? =
        hardware.firstNotNullOfOrNull { FAMILY[it.trim().lowercase()] }

    /** The name to show, in the order of the sources above; null when none knows (the caller keeps the chip name). */
    internal fun choose(appsFile: String?, vulkanDevice: String?, hardware: String, platform: String): String? =
        fromAppsFile(appsFile) ?: usable(vulkanDevice) ?: fromTable(hardware, platform)

    fun resolve(context: Context): String? = choose(
        FileUtils.readString(File(VkBridgeComponent.cacheDir(context), AppVulkan.BRIDGE_FILE)),
        VulkanInfo.cachedOrNull()?.get("device"),
        android.os.Build.HARDWARE,
        GpuInfo.systemProperty("ro.board.platform"),
    )
}
