package com.droiddeck.launcher.gpu

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.core.FileUtils
import java.io.File

/**
 * The Vulkan that games see through the Mali bridge: the version, shaderInt64 and the push-constant
 * room, which is what decides the newest DXVK that can start on it (ComponentsManager.bridgeDxvk).
 *
 * Read from <bridge cache dir>/apps-vulkan, written by the bridge after its self-test. For an
 * imported ICD, the cache is keyed by the selected library and no system-driver fallback is used;
 * otherwise fall back to the phone's own driver (VulkanInfo). The file's version counts only with promotion=on (the
 * server offers it above the driver's); otherwise, and in files without the field (servers from
 * before it, which wrote the promoted version even where it was not trusted), its driver_api does.
 * For the system driver, a file whose driver_api differs from the system driver is stale and left
 * out. null for missing values: a rule must not guess from them.
 */
data class AppVulkan(
    val major: Int,
    val minor: Int,
    val shaderInt64: Boolean?,
    val maxPushConstants: Int?,
    val source: String,
) {
    fun atLeast(major: Int, minor: Int): Boolean = this.major > major || (this.major == major && this.minor >= minor)

    val label: String
        get() = "Vulkan $major.$minor, shaderInt64 ${shaderInt64?.let { if (it) "yes" else "no" } ?: "?"}, " +
            "push constants ${maxPushConstants ?: "?"} ($source)"

    companion object {
        private const val TAG = "AppVulkan"
        const val BRIDGE_FILE = "apps-vulkan"

        /** null when this session does not draw through the bridge, or nothing could be read. */
        fun detect(context: Context): AppVulkan? {
            if (!DeviceSupport.vkBridge(context)) return null
            val imported = VkBridgeComponent.selectedDriverLibrary(context) != null
            val native = if (imported) emptyMap() else VulkanInfo.query()
            val file = File(VkBridgeComponent.cacheDir(context), BRIDGE_FILE)
            // Imported ICDs have their own content-keyed cache; the Android system loader reports
            // a different driver and must not reject that ICD's report or substitute its features.
            val found = selectReport(native, FileUtils.readString(file), imported)
            Log.i(TAG, found?.label ?: "unknown (driver query: ${native["error"] ?: "no api line"})")
            return found
        }

        internal fun selectReport(native: Map<String, String>, bridge: String?, imported: Boolean): AppVulkan? {
            val fromBridge = bridge?.let {
                fromBridgeFile(it, if (imported) null else native["device"], if (imported) null else native["api"])
            }
            return fromBridge ?: if (imported) null else fromDriver(native)
        }

        /**
         * The bridge's file: key=value lines (device, api, driver_api, promotion on/off, shader_int64
         * 0/1, max_push_constants). Ignored when it names another GPU than the driver's (a restored
         * backup, a moved phone), or another driver version than the driver's own.
         */
        internal fun fromBridgeFile(text: String, driverDevice: String?, driverApi: String? = null): AppVulkan? {
            val map = keyValues(text)
            val device = map["device"]
            if (device != null && driverDevice != null && device != driverDevice) return null
            val fileDriver = version(map["driver_api"])
            val native = version(driverApi)
            if (fileDriver != null && native != null && fileDriver != native) return null
            val promoted = map["promotion"]?.trim()?.lowercase() == "on"
            val (major, minor) = (if (promoted) version(map["api"]) else fileDriver ?: version(map["api"])) ?: return null
            return AppVulkan(major, minor, bool(map["shader_int64"]), map["max_push_constants"]?.toIntOrNull(),
                if (promoted) "bridge, promoted" else "bridge")
        }

        /** VulkanInfo's lines: api=1.1.177, shader_int64=yes|no, max_push_constants=128. */
        internal fun fromDriver(map: Map<String, String>): AppVulkan? {
            val (major, minor) = version(map["api"]) ?: return null
            return AppVulkan(major, minor, bool(map["shader_int64"]), map["max_push_constants"]?.toIntOrNull(), "driver")
        }

        private fun keyValues(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
        }.toMap()

        private fun version(v: String?): Pair<Int, Int>? {
            val parts = v?.trim()?.split('.') ?: return null
            if (parts.size < 2) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].toIntOrNull() ?: return null
            return major to minor
        }

        private fun bool(v: String?): Boolean? = when (v?.trim()?.lowercase()) {
            "1", "yes", "true" -> true
            "0", "no", "false" -> false
            else -> null
        }
    }
}
