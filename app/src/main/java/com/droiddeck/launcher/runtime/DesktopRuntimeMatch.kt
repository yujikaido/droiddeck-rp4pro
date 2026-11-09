package com.droiddeck.launcher.runtime

/** Which Linux runtime a desktop package can run on. From desktop-kde-r3 the package is built for the
 *  Ubuntu runtime (its Qt is newer than the Arch runtime r9 ships), so on r9 Plasma ends at once with
 *  "version `Qt_6.12' not found". Older packages ran on either. A tag or runtime this does not
 *  recognise is not blocked. */
internal object DesktopRuntimeMatch {
    private val TAG = Regex("desktop-kde-r(\\d+)(?:\\D.*)?")
    private const val UBUNTU_FROM = 3

    fun needsUbuntuRuntime(packTag: String?): Boolean =
        packTag != null && (TAG.matchEntire(packTag.trim())?.groupValues?.get(1)?.toIntOrNull() ?: 0) >= UBUNTU_FROM

    fun isUbuntuRuntime(runtimeVersion: String): Boolean = runtimeVersion.trim().startsWith("ubuntu-")

    /** False when the package needs the Ubuntu runtime and the installed runtime is another one. */
    fun matches(packTag: String?, runtimeVersion: String?): Boolean {
        val runtime = runtimeVersion?.trim().orEmpty()
        return runtime.isEmpty() || !needsUbuntuRuntime(packTag) || isUbuntuRuntime(runtime)
    }
}
