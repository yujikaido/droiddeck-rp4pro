package com.droiddeck.launcher.session

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.io.RandomAccessFile

/**
 * Runs [FirstFrameWatchdog] for a Steam session's loading screen: from the moment the Steam client is
 * started, after [FirstFrameWatchdog.LIMIT_S] seconds without a first frame it reads what the compositor,
 * the bridge's report, the session log and /proc say (off the main thread, every
 * [FirstFrameWatchdog.EVERY_S] s) and hands the loading screen a line saying which stage stalled.
 * The first diagnosis, and each change of stage, goes to events.jsonl (frame.stalled) and the compositor's log.
 */
class FirstFrameWatch(private val context: Context, private val show: (String?) -> Unit) {
    private var steamAt = 0L
    private var lastRun = 0L
    @Volatile private var busy = false
    private var logged: FirstFrameWatchdog.Stage? = null
    @Volatile private var shown = false

    /**
     * Called by the loading screen's poll (main thread). [downloading]: the screen shows a download's
     * progress (Steam updating itself after it started): the clock starts again once it is done.
     */
    fun tick(topic: LoadingState.Topic, steam: Boolean, firstFrameSeen: Boolean, downloading: Boolean = false) {
        if (!steam || firstFrameSeen) return
        val now = SystemClock.elapsedRealtime()
        if (downloading) {
            if (steamAt != 0L) steamAt = now
            if (shown) {
                shown = false
                show(null)
            }
            return
        }
        if (steamAt == 0L) {
            if (topic != LoadingState.Topic.STEAM_STARTING) return
            steamAt = now
        }
        val seconds = ((now - steamAt) / 1000).toInt()
        if (seconds < FirstFrameWatchdog.LIMIT_S || busy || now - lastRun < FirstFrameWatchdog.EVERY_S * 1000L) return
        lastRun = now
        busy = true
        Thread({
            try {
                val line = runCatching { diagnose(seconds) }.onFailure { Log.w(TAG, "first-frame watchdog", it) }.getOrNull()
                if (line != null) {
                    shown = true
                    show(line)
                }
            } finally {
                busy = false
            }
        }, "first-frame-watch").start()
    }

    private fun diagnose(seconds: Int): String {
        val counters = IntArray(8)
        val filled = runCatching { WaylandCompositor.nativeFirstFrameProbe(counters) }.getOrDefault(0)
        val dir = SessionState.logFile?.parentFile ?: SessionState.logDirectory
        val verdict = FirstFrameWatchdog.diagnose(
            processes = FirstFrameWatchdog.processNames(),
            xwmUp = FirstFrameWatchdog.xwmUp(head(SessionState.logFile, 512 * 1024)),
            compositor = FirstFrameWatchdog.Compositor.of(counters, filled),
            stuck = FirstFrameWatchdog.stuckCalls(dir?.let { head(File(it, "malibridge-report.json"), 4 * 1024 * 1024) }),
        )
        if (verdict.stage != logged) {
            logged = verdict.stage
            val line = FirstFrameWatchdog.logLine(verdict, seconds)
            Log.w(TAG, line)
            runCatching { WaylandCompositor.nativeLog("firstframe", line) }
            SessionEvents.record(
                "frame.stalled",
                mapOf(
                    "stage" to verdict.stage.key, "after_s" to seconds,
                    "process" to verdict.stuck?.process, "call" to verdict.stuck?.call, "call_s" to verdict.stuck?.seconds,
                    "compositor" to counters.take(filled).joinToString(","),
                ),
            )
        }
        return context.getString(R.string.ff_stalled, seconds, reason(verdict))
    }

    private fun reason(v: FirstFrameWatchdog.Verdict): String = when (v.stage) {
        FirstFrameWatchdog.Stage.GPU_WAIT -> {
            val s = v.stuck!!
            context.getString(if (s.gpuWait) R.string.ff_gpu_wait else R.string.ff_gpu_call, s.process, s.call, s.seconds)
        }
        FirstFrameWatchdog.Stage.GAMESCOPE -> context.getString(R.string.ff_gamescope)
        FirstFrameWatchdog.Stage.XWAYLAND -> context.getString(R.string.ff_xwayland)
        FirstFrameWatchdog.Stage.SCREEN_REFUSED -> context.getString(R.string.ff_screen_refused)
        FirstFrameWatchdog.Stage.STEAM_PROCESS -> context.getString(R.string.ff_steam_process)
        FirstFrameWatchdog.Stage.STEAM_UI -> context.getString(R.string.ff_steam_ui)
        FirstFrameWatchdog.Stage.STEAM_WINDOW -> context.getString(R.string.ff_steam_window)
    }

    private fun head(file: File?, max: Int): String? = runCatching {
        if (file == null || !file.isFile) return null
        RandomAccessFile(file, "r").use { raf ->
            val n = minOf(raf.length(), max.toLong()).toInt()
            val buf = ByteArray(n)
            raf.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
    }.getOrNull()

    private companion object {
        const val TAG = "FirstFrameWatch"
    }
}
