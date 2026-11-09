package com.droiddeck.launcher.runtime

import android.os.Environment
import android.util.Log
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.session.SessionEvents
import java.net.URI

/**
 * Says why a runtime install failed. One line per download try (host, HTTP status or exception, bytes
 * received of expected, free storage, which source), written to the app log and to the session's
 * events.jsonl, so a Send-logs zip from a failed first Play shows the cause (a player log had none).
 */
object RuntimeInstallLog {
    private const val TAG = "RuntimeInstallLog"
    const val EVENT = "runtime.install.diag"

    /** What the installer is doing now ("runtime-archive upstream", "runtime-archive pinned-mirror 1", "addon"). */
    @Volatile var stage: String = "addon"

    fun freeBytes(): Long = try {
        Environment.getDataDirectory().usableSpace
    } catch (e: Exception) {
        Log.w(TAG, "could not read the free storage", e)
        -1L
    }

    private fun host(url: String): String = try {
        URI(url).host ?: "unknown host"
    } catch (e: Exception) {
        Log.w(TAG, "unreadable url in a download line", e)
        "unreadable url"
    }

    private fun mb(bytes: Long) = if (bytes < 0) "unknown" else "${bytes / 1_000_000} MB"

    fun attemptLine(stage: String, a: Downloader.Attempt, freeBytes: Long): String {
        val route = if (a.source == a.original) "direct" else "mirror of ${host(a.original)}"
        return "runtime install: $stage ${a.outcome}: host ${host(a.source)} ($route), " +
            "HTTP ${if (a.httpStatus < 0) "none" else a.httpStatus.toString()}, " +
            "error ${a.error ?: "none"}, " +
            "received ${a.bytes} of ${if (a.total < 0) "unknown" else a.total.toString()} bytes, " +
            "free storage ${mb(freeBytes)}"
    }

    fun sink(): Downloader.AttemptSink = Downloader.AttemptSink { a ->
        val st = stage
        val free = freeBytes()
        emit(attemptLine(st, a, free), mapOf(
            "stage" to st, "host" to host(a.source), "source" to a.source, "original" to a.original,
            "http" to a.httpStatus, "error" to a.error, "bytes" to a.bytes, "expected" to a.total,
            "freeBytes" to free, "outcome" to a.outcome,
        ))
    }

    /** A failure that has no download try behind it (the pinned catalog, a pack, an extract). */
    @JvmStatic fun failure(what: String, e: Throwable?) {
        val free = freeBytes()
        val why = if (e == null) "no further detail" else e.javaClass.simpleName + ": " + e.message
        emit("runtime install: $what failed: $why, free storage ${mb(free)}",
            mapOf("stage" to what, "error" to why, "freeBytes" to free))
    }

    private fun emit(line: String, fields: Map<String, Any?>) {
        Log.w(TAG, line)
        SessionEvents.record(EVENT, fields + ("line" to line))
    }

    @JvmStatic fun begin() {
        stage = "addon"
        Downloader.attemptSink = sink()
    }

    @JvmStatic fun end() {
        Downloader.attemptSink = null
    }

    @JvmStatic fun stage(value: String) { stage = value }
}
