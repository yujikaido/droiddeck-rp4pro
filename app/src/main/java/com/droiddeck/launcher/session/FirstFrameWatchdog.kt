package com.droiddeck.launcher.session

import java.io.File
import org.json.JSONObject

/**
 * The loading screen's first-frame watchdog. "Waiting for Steam's first frame" said nothing about
 * where a start had stopped: Galaxy A55 sat on it for 7 minutes with Xwayland inside the GPU
 * driver, DroidDeck #1655 (Galaxy Tab A11+) with every frame refused by the compositor's driver, and the
 * players could only send a log and wait. After [LIMIT_S] seconds of the Steam client starting without a
 * frame on screen, the stage that stalled is named on the loading screen and in the session's events:
 * a GPU call that has not returned (the bridge's report), gamescope, Xwayland, frames the screen
 * refuses, Steam's process, its interface (steamwebhelper), or its window.
 */
object FirstFrameWatchdog {
    const val LIMIT_S = 60
    /** How often the diagnosis is made again while the loading screen stays up. */
    const val EVERY_S = 5

    enum class Stage(val key: String) {
        GPU_WAIT("gpu_wait"),
        GAMESCOPE("gamescope"),
        XWAYLAND("xwayland"),
        SCREEN_REFUSED("screen_refused"),
        STEAM_PROCESS("steam_process"),
        STEAM_UI("steam_ui"),
        STEAM_WINDOW("steam_window"),
    }

    /** The compositor's counters (WaylandCompositor.nativeFirstFrameProbe, firstframe.h order). */
    data class Compositor(val gamescope: Int, val gpuFrames: Int, val refused: Int, val cpuCopies: Int, val onScreen: Int) {
        companion object {
            fun of(values: IntArray, filled: Int): Compositor? =
                if (filled < 5 || values.size < 5) null else Compositor(values[0], values[1], values[2], values[3], values[4])
        }
    }

    /** A request a process has sent the bridge that has not returned (the report's stuck_call). */
    data class Stuck(val process: String, val call: String, val seconds: Int, val gpuWait: Boolean)

    data class Verdict(val stage: Stage, val stuck: Stuck? = null)

    /**
     * [processes]: the programs running (their command's base name), null when /proc could not be read;
     * [xwmUp]: gamescope's window manager reached Xwayland (its "xwm:" lines), null without the session log;
     * [compositor]: null when the compositor gave no counters; [stuck]: from the bridge's report.
     */
    fun diagnose(processes: Set<String>?, xwmUp: Boolean?, compositor: Compositor?, stuck: List<Stuck>): Verdict {
        stuck.maxByOrNull { it.seconds }?.let { return Verdict(Stage.GPU_WAIT, it) }
        if (processes != null && processes.none { it.startsWith("gamescope") }) return Verdict(Stage.GAMESCOPE)
        if (compositor != null && compositor.gamescope == 0) return Verdict(Stage.GAMESCOPE)
        if (processes != null && "Xwayland" !in processes) return Verdict(Stage.XWAYLAND)
        if (xwmUp == false) return Verdict(Stage.XWAYLAND)
        if (compositor != null && compositor.gpuFrames > 0 && compositor.refused > 0 && compositor.onScreen == 0 &&
            compositor.cpuCopies == 0
        ) return Verdict(Stage.SCREEN_REFUSED)
        if (processes != null && "steam" !in processes) return Verdict(Stage.STEAM_PROCESS)
        if (processes != null && "steamwebhelper" !in processes) return Verdict(Stage.STEAM_UI)
        return Verdict(Stage.STEAM_WINDOW)
    }

    /** The base names of the commands of the processes this app can see (its own and the session's). */
    fun processNames(proc: File = File("/proc")): Set<String>? {
        val dirs = proc.listFiles { f -> f.name.all(Char::isDigit) } ?: return null
        val names = HashSet<String>()
        for (dir in dirs) {
            val raw = runCatching { File(dir, "cmdline").readBytes() }.getOrNull() ?: continue
            val first = raw.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
            val base = first.substringAfterLast('/').substringAfterLast('\\')
            if (base.isNotEmpty()) names += base
        }
        return names.takeIf { it.isNotEmpty() }
    }

    /** Whether gamescope's X window manager has reached Xwayland (null: no log to tell). */
    fun xwmUp(sessionLog: String?): Boolean? = sessionLog?.let { "xwm:" in it }

    /** The requests the bridge's report says have not returned (malibridge-report.json, stuck_call). */
    fun stuckCalls(reportJson: String?): List<Stuck> {
        if (reportJson.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(reportJson) }.getOrNull() ?: return emptyList()
        val procs = root.optJSONArray("processes") ?: return emptyList()
        val out = ArrayList<Stuck>()
        for (i in 0 until procs.length()) {
            val p = procs.optJSONObject(i) ?: continue
            if (!p.optBoolean("alive", false)) continue
            val s = p.optJSONObject("stuck_call") ?: continue
            val call = s.optString("call", "")
            if (call.isEmpty()) continue
            val name = p.optString("name", "").ifEmpty { p.optString("comm", "?") }
            out += Stuck(name, call, s.optDouble("s", 0.0).toInt(), s.optBoolean("gpu_wait", false))
        }
        return out
    }

    /** One line for the logs (English, whatever the app's language). */
    fun logLine(v: Verdict, seconds: Int): String = buildString {
        append("no first frame after ").append(seconds).append(" s: ").append(v.stage.key)
        v.stuck?.let { append(" (").append(it.process).append(" in ").append(it.call).append(" for ").append(it.seconds).append(" s)") }
    }
}
