package com.droiddeck.launcher.core

import java.io.File

object CpuCores {
    val all: List<Int> by lazy { (0 until Runtime.getRuntime().availableProcessors()).toList() }

    fun maxGhz(core: Int): Double? =
        FileUtils.readString(File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq"))
            ?.trim()?.toLongOrNull()?.let { it / 1_000_000.0 }

    fun parse(list: String): Set<Int> =
        list.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in all }.toSet()

    fun format(cores: Set<Int>): String = cores.sorted().joinToString(",")

    fun listOrAll(list: String): String {
        val cores = parse(list)
        return format(if (cores.isEmpty()) all.toSet() else cores)
    }

    fun gameLaunchList(list: String, clientPinned: Boolean): String =
        restrictionOrEmpty(list).ifEmpty { if (clientPinned) listOrAll("") else "" }

    /** A saved game-core list (even one naming every core) or the client override: the player chose. */
    fun playerPicked(gameList: String, clientPinned: Boolean): Boolean = clientPinned || gameList.isNotBlank()

    /** Each core's cpu_capacity (1024 = the fastest core at its top clock), null where the kernel gives none. */
    fun capacity(core: Int): Int? =
        FileUtils.readString(File("/sys/devices/system/cpu/cpu$core/cpu_capacity"))?.trim()?.toIntOrNull()

    fun restrictionOrEmpty(list: String): String {
        val cores = parse(list)
        return if (cores.isEmpty() || cores.size >= all.size) "" else format(cores)
    }
}
