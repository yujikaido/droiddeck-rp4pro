package com.droiddeck.launcher.session

enum class SessionLaunchFailure {
    STEAM_ILLEGAL_INSTRUCTION,
    VULKAN_DEVICE_MISSING,
    /** droiddeck-session found no Mali bridge server (no socket after its wait) and ended the Steam session. */
    BRIDGE_NOT_USABLE,
    DESKTOP_RUNTIME_MISMATCH;

    companion object {
        private val ANSI = Regex("\u001b\\[[0-?]*[ -/]*[@-~]")
        private val STEAM_EXIT = Regex("== steam exited rc=(\\d+)")
        private val QT_TOO_OLD = Regex("version [`'\"]Qt_6\\.\\d+'? not found")

        fun find(lines: Sequence<String>): SessionLaunchFailure? {
            var steamStarted = false
            var illegalInstruction = false
            var missingDevice = false
            var runtimeMismatch = false
            var bridgeMissing = false
            lines.forEach { raw ->
                val line = ANSI.replace(raw, "").trim()
                when {
                    line.startsWith("== droiddeck-session ") -> {
                        steamStarted = false
                        illegalInstruction = false
                        missingDevice = false
                        runtimeMismatch = false
                        bridgeMissing = false
                    }
                    line.startsWith("== vulkan driver: the Steam session cannot start without the Mali bridge") -> bridgeMissing = true
                    QT_TOO_OLD.containsMatchIn(line) -> runtimeMismatch = true
                    line.startsWith("== STEP ") && line.contains("starting the Steam client") -> {
                        steamStarted = true
                        illegalInstruction = false
                    }
                    STEAM_EXIT.matches(line) && steamStarted ->
                        illegalInstruction = STEAM_EXIT.matchEntire(line)?.groupValues?.get(1) == "132"
                    line.startsWith("[gamescope]") && line.contains("vulkan:") -> when {
                        line.contains("failed to find physical device") -> missingDevice = true
                        line.contains("selecting physical device") -> missingDevice = false
                    }
                }
            }
            return when {
                runtimeMismatch -> DESKTOP_RUNTIME_MISMATCH
                illegalInstruction -> STEAM_ILLEGAL_INSTRUCTION
                bridgeMissing -> BRIDGE_NOT_USABLE
                missingDevice -> VULKAN_DEVICE_MISSING
                else -> null
            }
        }
    }
}
