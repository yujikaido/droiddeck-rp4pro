package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.LogRedactor
import com.droiddeck.launcher.core.SessionLogCapture
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionArtifacts {
    private const val TAG = "SessionArtifacts"

    const val COMPLETE_MARKER = ".complete"

    private val SCRUBBED_MARKER = ".scrubbed-r${LogRedactor.RULES_VERSION}"
    private val OLD_SCRUBBED_MARKER = Regex("""\.scrubbed-(r?\d+)""")

    const val SCRUBBED_TREE_MARKER = ".scrubbed-tree"

    fun collect(context: Context, dir: File, reason: String) {
        try {
            val wayland = File(dir, "wayland.log")
            if (!wayland.exists()) {
                WaylandCompositor.currentLogFile()?.takeIf { it.isFile }?.let { src ->
                    src.copyTo(wayland, overwrite = true)
                }
            }
            LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
            val record = Record(dir)
            copySteamLogs(context, dir, record)
            copyStuckPipelines(context, dir)
            SessionLogCapture.dumpCrashBuffer(File(dir, "crash.log"))
            markScrubbed(scrubTree(record))
            SessionEvents.record("session.artifacts_collected", mapOf("reason" to reason), dir)
            File(dir, COMPLETE_MARKER).writeText("collected: $reason at ${now()}\n")
        } catch (e: Exception) {
            Log.w(TAG, "collecting session artifacts", e)
        }
    }

    @Synchronized
    fun scrubOlder(context: Context) {
        val current = SessionPaths.current()
        val dirs = LinuxRuntime.logDir(context).listFiles { f ->
            SessionPaths.isSessionFolder(f) && f != current && !File(f, SCRUBBED_MARKER).exists()
        }?.toList() ?: return
        if (dirs.isEmpty()) return
        LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        val workers = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(workers) { r ->
            Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "scrub-logs")
        }
        val done = try {
            pool.invokeAll(dirs.map { dir -> java.util.concurrent.Callable { scrubAndMark(dir) } })
                .count { f -> try { f.get() } catch (e: Exception) { false } }
        } finally {
            pool.shutdown()
        }
        val failed = dirs.size - done
        Log.i(TAG, "logs: scrubbed $done folders under r${LogRedactor.RULES_VERSION}" +
            if (failed > 0) ", $failed not fully (again at the next start)" else "")
    }

    fun unmarkMoved(dirs: List<File>) {
        dirs.filter { SessionPaths.isSessionFolder(it) }.forEach { dir ->
            dir.listFiles { f -> OLD_SCRUBBED_MARKER.matches(f.name) || f.name == SCRUBBED_TREE_MARKER }?.forEach { it.delete() }
        }
    }

    private class Scrubbed(val length: Long, val modified: Long)

    private class Record(val dir: File) {
        val files = HashMap<File, Scrubbed>()
        var failed = false
    }

    private fun scrubFolder(dir: File, record: Record) {
        dir.listFiles { f -> f.isFile && !f.name.startsWith(".") && f !in record.files }?.forEach { f ->
            if (!LogRedactor.isText(f)) return@forEach
            val done = scrubFile(dir, f)
            if (done == null) record.failed = true else record.files[f] = done
        }
    }

    private fun scrubFile(dir: File, f: File): Scrubbed? {
        val tmp = File(dir, ".${f.name}.scrub")
        return try {
            val before = Scrubbed(f.length(), f.lastModified())
            tmp.bufferedWriter().use { w -> LogRedactor.scrubTo(f, w) }
            val scrubbed = tmp.readBytes()
            if (scrubbed.contentEquals(f.readBytes())) {
                tmp.delete()
                before.takeIf { f.length() == it.length && f.lastModified() == it.modified }
            } else {
                if (!tmp.renameTo(f)) { f.writeBytes(scrubbed); tmp.delete() }
                Scrubbed(scrubbed.size.toLong(), f.lastModified()).takeIf { f.length() == it.length }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not scrub ${f.name}", e)
            tmp.delete()
            null
        }
    }

    private fun markScrubbed(record: Record): Boolean {
        val dir = record.dir
        val tree = File(dir, SCRUBBED_TREE_MARKER)
        dir.listFiles { f -> OLD_SCRUBBED_MARKER.matches(f.name) }?.forEach { it.delete() }
        if (record.failed) {
            tree.delete()
            Log.w(TAG, "$dir is not fully scrubbed; it is scrubbed again when shared")
            return false
        }
        return try {
            File(dir, SCRUBBED_MARKER).writeText("scrubbed ${now()}\n")
            val lines = StringBuilder("rules ${LogRedactor.RULES_VERSION}\n")
            record.files.forEach { (f, s) ->
                lines.append(s.length).append('\t').append(s.modified).append('\t')
                    .append(f.relativeTo(dir).path).append('\n')
            }
            tree.writeText(lines.toString())
            true
        } catch (e: Exception) {
            tree.delete()
            File(dir, SCRUBBED_MARKER).delete()
            false
        }
    }

    fun scrubbedFiles(dir: File): Set<String> {
        val lines = try { File(dir, SCRUBBED_TREE_MARKER).takeIf { it.isFile }?.readLines() } catch (e: Exception) { null }
            ?: return emptySet()
        if (lines.firstOrNull() != "rules ${LogRedactor.RULES_VERSION}") return emptySet()
        return lines.drop(1).mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val f = File(dir, parts[2])
            parts[2].takeIf { f.isFile && f.length().toString() == parts[0] && f.lastModified().toString() == parts[1] }
        }.toSet()
    }

    internal fun scrubAndMark(dir: File): Boolean = markScrubbed(scrubTree(Record(dir)))

    private fun scrubTree(record: Record): Record {
        record.dir.walkTopDown().filter { it.isDirectory }.forEach { scrubFolder(it, record) }
        return record
    }

    private fun copySteamLogs(context: Context, dir: File, record: Record) {
        val logs = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/logs")
        if (!logs.isDirectory) return
        val out = File(dir, "steam").apply { mkdirs() }
        logs.listFiles { f -> f.isFile && f.length() < 8L * 1024 * 1024 }?.forEach { src ->
            try {
                val dst = File(out, src.name)
                dst.bufferedWriter().use { w ->
                    src.forEachLine { line -> w.write(LogRedactor.redact(line)); w.newLine() }
                }
                record.files[dst] = Scrubbed(dst.length(), dst.lastModified())
            } catch (e: Exception) {
                Log.w(TAG, "could not scrub ${src.name}", e)
            }
        }
        Log.i(TAG, "collected ${out.listFiles()?.size ?: 0} Steam log(s), scrubbed, into $out")
    }

    /* The bridge's list of pipelines its compile watchdog cut off as stuck (hashes, the stand-in
     * chosen, the list's version), as it stands at the session's end: which game's shaders were cut
     * off, and with what. Kept across sessions, so only the newest folder gets it. */
    private fun copyStuckPipelines(context: Context, dir: File) {
        try {
            val src = File(com.droiddeck.launcher.gpu.VkBridgeComponent.cacheDir(context), "stuck-pipelines")
            if (!src.isFile) return
            if (src.length() > STUCK_PIPELINES_MAX_BYTES) {
                File(dir, "vkbridge-stuck-pipelines.txt").writeText("stuck-pipelines is ${src.length()} bytes; not copied\n")
                return
            }
            src.copyTo(File(dir, "vkbridge-stuck-pipelines.txt"), overwrite = true)
        } catch (e: Exception) {
            Log.w(TAG, "could not copy the bridge's stuck-pipelines list", e)
        }
    }

    private const val STUCK_PIPELINES_MAX_BYTES = 64L * 1024

    @Synchronized
    fun finishAbandoned(context: Context) {
        val parent = LinuxRuntime.logDir(context)
        val abandoned = parent.listFiles { f ->
            SessionPaths.isSessionFolder(f) && !File(f, COMPLETE_MARKER).exists()
        }?.sortedWith(SessionPaths.chronological) ?: return
        if (abandoned.isEmpty()) return
        val current = SessionPaths.current()
        LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        val exits = exitRecords(context)
        abandoned.forEachIndexed { i, dir ->
            if (dir == current) return@forEachIndexed
            val newest = i == abandoned.lastIndex
            try {
                File(dir, "ended-without-teardown.txt").writeText(
                    "This session's process ended without running its own teardown - killed by\n" +
                        "Android, a native crash, or the device going down - so the files below were\n" +
                        "gathered when the app next started, at ${now()}.\n" +
                        "The logs written during the session (session.log, app.log, wayland.log,\n" +
                        "audio.log, device.txt) were on disk already and are as they were left.\n" +
                        (if (newest) "" else "Steam's logs are not included: a later session has overwritten them.\n") +
                        "crash.log holds Android's crash buffer as of the next app start - if this\n" +
                        "session died of a crash, the entry is in there unless the device rebooted.\n" +
                        (if (newest) exitText(exits) else "")
                )
                val record = Record(dir)
                if (newest) { copySteamLogs(context, dir, record); copyStuckPipelines(context, dir) }
                SessionLogCapture.dumpCrashBuffer(File(dir, "crash.log"))
                SessionEvents.record("session.artifacts_recovered", mapOf("newest" to newest) +
                    if (newest && exits.isNotEmpty()) mapOf("android_exit" to exitReason(exits[0].reason)) else emptyMap(), dir)
                markScrubbed(scrubTree(record))
                File(dir, COMPLETE_MARKER).writeText("collected: late, at next app start, ${now()}\n")
                Log.i(TAG, "finished the abandoned session folder $dir")
            } catch (e: Exception) {
                Log.w(TAG, "could not finish $dir", e)
            }
        }
    }

    /** One of Android's records of how an app process ended (ApplicationExitInfo; sizes in KB). */
    internal class Exit(
        val time: Long, val pid: Int, val process: String, val reason: Int, val status: Int,
        val importance: Int, val pssKb: Long, val rssKb: Long, val description: String?,
    )

    /* Android keeps how the app's last processes ended (API 30+), newest first. A kill by the
     * low-memory killer, from the recents screen or by the vendor's battery manager never reaches
     * the crash buffer, so for those this is the only trace. Read before this start's own process
     * can end, so the newest record is the process the abandoned session ran in. */
    private fun exitRecords(context: Context): List<Exit> {
        if (android.os.Build.VERSION.SDK_INT < 30) return emptyList()
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return emptyList()
        return runCatching {
            am.getHistoricalProcessExitReasons(null, 0, EXITS_SHOWN).map { e ->
                Exit(e.timestamp, e.pid, e.processName, e.reason, e.status, e.importance, e.pss, e.rss, e.description)
            }
        }.onFailure { Log.w(TAG, "Android's exit records", it) }.getOrDefault(emptyList())
    }

    internal fun exitText(exits: List<Exit>): String {
        if (exits.isEmpty()) return ""
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return "\nHow Android says the app's last processes ended (newest first):\n" + exits.joinToString("") { e ->
            /* The exit() argument for a process that exited itself, the signal for every other one. */
            val status = when {
                e.reason == android.app.ApplicationExitInfo.REASON_EXIT_SELF -> ", exit status ${e.status}"
                e.status != 0 -> ", signal ${e.status}"
                else -> ""
            }
            val name = e.process.takeIf { it.contains(':') }?.let { " (${it.substringAfter(':')})" } ?: ""
            "  ${f.format(Date(e.time))} pid ${e.pid}$name: ${exitReason(e.reason)}$status, " +
                "${importance(e.importance)}, pss ${e.pssKb / 1024} MB, rss ${e.rssKb / 1024} MB" +
                (e.description?.takeIf { it.isNotBlank() }?.let { " - ${LogRedactor.redact(it.trim())}" } ?: "") + "\n"
        }
    }

    internal fun exitReason(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory (Android's low-memory killer)"
        android.app.ApplicationExitInfo.REASON_CRASH -> "app crash"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        android.app.ApplicationExitInfo.REASON_ANR -> "not responding"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "too much CPU or other resources"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user (force stop or swiped away)"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "the user's profile stopped"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a process it depended on died"
        android.app.ApplicationExitInfo.REASON_OTHER -> "killed by the system (other)"
        android.app.ApplicationExitInfo.REASON_FREEZER -> "killed while frozen"
        android.app.ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "the app was disabled or changed"
        android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "the app was updated"
        else -> "reason $reason"
    }

    /* How much Android valued the process when it ended: a cached or background one goes first. */
    private fun importance(importance: Int): String = when {
        importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "in the foreground"
        importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground service"
        importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "background service"
        importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "on top, screen off"
        importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "in the background (cached)"
        else -> "gone (importance $importance)"
    }

    private const val EXITS_SHOWN = 3

    @Synchronized
    fun prune(context: Context) {
        val current = SessionPaths.current()
        val finished = SessionPaths.sessionFolders(context).filter { it != current && File(it, COMPLETE_MARKER).exists() }
        val old = finished.dropLast(SessionPaths.KEEP_SESSIONS)
        old.forEach { com.droiddeck.launcher.core.FileUtils.delete(it) }
        if (old.isNotEmpty()) Log.i(TAG, "deleted ${old.size} session folder(s) past the newest ${SessionPaths.KEEP_SESSIONS}")
    }

    /* Also at each session's end, not only at app start: a day of sessions without the app being
     * closed (Android keeps it alive) went well past the newest KEEP_SESSIONS, 40 MB or so each. */
    fun pruneQuietly(context: Context) {
        try {
            prune(context)
        } catch (e: Exception) {
            Log.w(TAG, "pruning session folders", e)
        }
    }

    @Synchronized
    fun clearAll(context: Context): Int {
        val current = SessionPaths.current()
        val gone = SessionPaths.sessionFolders(context).filter { it != current }
        gone.forEach { com.droiddeck.launcher.core.FileUtils.delete(it) }
        com.droiddeck.launcher.core.FileUtils.delete(File(LinuxRuntime.logDir(context), SessionPaths.TOOLS_DIR))
        Log.i(TAG, "cleared ${gone.size} session folder(s)")
        return gone.size
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
