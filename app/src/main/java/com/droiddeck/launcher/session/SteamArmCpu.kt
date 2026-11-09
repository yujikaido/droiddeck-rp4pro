package com.droiddeck.launcher.session

import java.io.File

/** Valve's ARM Steam client needs LSE atomics (ARMv8.1+): on an ARMv8.0 CPU (Cortex-A53/A57/A72/A73,
 *  Exynos M-cores) it dies with SIGILL right after its 362 MB download. Same rule as
 *  x64_arm_webhelper_cpu_supported in droiddeck-steam-x64: any "Features" line without `atomics`
 *  rules it out; no Features line at all (cpuinfo unreadable) does not. */
object SteamArmCpu {
    fun supported(cpuInfo: String?): Boolean = cpuInfo.orEmpty().lineSequence()
        .filter { it.substringBefore(':').trim() == "Features" && ':' in it }
        .none { line -> "atomics" !in line.substringAfter(':').trim().split(Regex("\\s+")) }

    fun supported(): Boolean = supported(runCatching { File("/proc/cpuinfo").readText() }.getOrNull())

    /** Whether to stop before the download: the ARM client is the chosen one and the CPU cannot run it. */
    fun blocks(clientArch: String, cpuInfo: String?): Boolean = clientArch == "arm64" && !supported(cpuInfo)

    const val FAILURE_CODE = "STEAM_CPU_UNSUPPORTED"
}
