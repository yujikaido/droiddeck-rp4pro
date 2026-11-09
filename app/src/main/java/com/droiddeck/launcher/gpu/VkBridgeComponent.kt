package com.droiddeck.launcher.gpu

import android.content.Context
import android.os.Process
import android.util.Log
import com.droiddeck.launcher.core.HostProcess
import com.droiddeck.launcher.core.SessionPart
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class VkBridgeComponent(private val socket: File, private val logFile: File) : SessionPart() {
    @Volatile private var pid = -1
    @Volatile private var exited = false
    @Volatile private var stopping = false

    override fun start() {
        killServer()
        claimSocket()
        stopping = false
        latest = this
        killed = null
        val binary = File(app().applicationInfo.nativeLibraryDir, BINARY)
        if (!binary.isFile) {
            Log.e(TAG, "server missing at $binary; the session will have no Vulkan")
            return
        }
        socket.parentFile?.mkdirs()
        val cacheDir = cacheDir(app())
        writeMode(cacheDir, com.droiddeck.launcher.session.SessionPrefs.vkBridgeMode(app()))
        if (VkBridgeNative.start(app(), socket, logFile, cacheDir)) {
            Log.i(TAG, "server ready in the app's process at $socket")
            return
        }
        socket.delete()
        exited = false
        val cmd = listOf(binary.absolutePath, "--socket", socket.absolutePath, "--log", logFile.absolutePath,
            "--cache-dir", cacheDir.absolutePath)
            .joinToString(" ") { it.replace("\\", "\\\\").replace(" ", "\\ ") }
        val env = arrayOf("HOME=" + app().filesDir)
        pid = HostProcess.start(cmd, env, app().filesDir, { status ->
            val byAndroid = isKilledBySystem(status, stopping)
            val low = byAndroid && runCatching { lowMemoryAtEnd(logTail(logFile)) }.getOrDefault(false)
            if (byAndroid && latest === this) killed = Killed(status, low)
            exited = true
            Log.w(TAG, "server exited with status $status")
            runCatching {
                logFile.appendText("== vkbridge server exited with status $status\n")
                if (byAndroid) logFile.appendText("== killed by the system while the session ran" +
                    (if (low) ", with memory low" else "") + " (not stopped by the app)\n")
            }
        }, { line -> Log.i(TAG, line) })
        val began = System.currentTimeMillis()
        val deadline = began + 15_000L
        while (!socket.exists() && !exited && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50L) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        val waited = System.currentTimeMillis() - began
        Log.i(TAG, if (socket.exists()) "server ready (pid $pid) at $socket after $waited ms"
            else "server NOT ready (pid $pid, exited=$exited) after $waited ms; see $logFile")
        if (!socket.exists() && VkBridgeNative.start(app(), socket, logFile, cacheDir)) Log.i(TAG, "server ready in the app's process at $socket")
    }

    override fun stop() {
        stopping = true
        killServer()
        synchronized(owners) {
            if (owners.remove(socket.absolutePath, this) && !VkBridgeNative.serving) socket.delete()
        }
    }

    internal fun claim() {
        synchronized(owners) { owners[socket.absolutePath] = this }
    }

    internal fun claimSocket() {
        synchronized(owners) {
            claim()
            if (!VkBridgeNative.serving) socket.delete()
        }
    }

    private fun killServer() {
        if (pid > 0) {
            stopping = true
            Process.killProcess(pid)
            pid = -1
        }
    }

    override fun suspendPid(): Int = pid

    /** Waits up to [ms] for this server's exit to be noted when its process is already gone (the session can
     *  end a moment before the exit callback runs). */
    internal fun awaitExitNoted(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (!exited && !stopping && pid > 0 && !processAlive(pid) && System.currentTimeMillis() < until) {
            try { Thread.sleep(25L) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); return }
        }
    }

    /** How the bridge server ended when the system killed it while the session ran: its exit status, and
     *  whether its log said memory was low at the end. */
    data class Killed(val status: Int, val lowMemory: Boolean)

    companion object {
        private const val TAG = "VkBridge"
        private const val CACHE_DIR = "vkbridge-pipelines"
        private const val MODE_FILE = "mode"
        private const val STUCK_PIPELINES = "stuck-pipelines"
        private val owners = ConcurrentHashMap<String, VkBridgeComponent>()
        private const val SIGKILL_STATUS = 128 + 9
        @Volatile private var latest: VkBridgeComponent? = null
        @Volatile private var killed: Killed? = null

        /** The current session's bridge server, if the system killed it while the session ran (*  Android's low-memory killer takes the server, and the game goes with it). Waits briefly for an exit
         *  that has happened but is not noted yet. */
        fun killedDuringSession(waitMs: Long = 1_000L): Killed? {
            latest?.awaitExitNoted(waitMs)
            return killed
        }

        /** SIGKILL that the app did not send: Android's low-memory killer or its phantom-process limits. A crash
         *  is another signal and has its own reports; the app's own stop kills with SIGKILL too. */
        fun isKilledBySystem(status: Int, stopping: Boolean): Boolean = status == SIGKILL_STATUS && !stopping

        /** True when the server's last word on memory was "low": the last `mem:` line marked `(low)`, or a
         *  "memory low:" warning not followed by the line that says it is back. */
        fun lowMemoryAtEnd(lines: Sequence<String>): Boolean {
            var low = false
            for (line in lines) {
                when {
                    line.contains(" mem: rss ") -> low = line.contains(" (low) |")
                    line.contains(" memory low: ") -> low = true
                    line.contains(" memory: ") && line.contains(" again") -> low = false
                }
            }
            return low
        }

        private fun logTail(file: File, bytes: Long = 64L * 1024): Sequence<String> {
            val size = file.length()
            val text = java.io.RandomAccessFile(file, "r").use { f ->
                val start = maxOf(0L, size - bytes)
                f.seek(start)
                val buf = ByteArray((size - start).toInt())
                f.readFully(buf)
                String(buf, Charsets.ISO_8859_1)
            }
            return text.lineSequence()
        }

        private fun processAlive(pid: Int): Boolean {
            val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return false
            val state = stat.substringAfterLast(')').trim().firstOrNull()
            return state != null && state != 'Z' && state != 'X'
        }

        fun cacheDir(context: Context): File {
            val dir = File(context.filesDir, CACHE_DIR)
            val old = File(context.cacheDir, CACHE_DIR)
            if (old.isDirectory) migrate(old, dir)
            dir.mkdirs()
            return dir
        }

        /** The bridge's heap cap for Performance's VRAM cap: the player's value, but never above the
         *  server's own safe-mode default (half the RAM, 2048-4096 MB, on devices with 12.5 GB or less),
         *  which a guest VKBRIDGE_HEAP_MAX_MB would otherwise replace. */
        fun heapCapMb(vramMb: Int, safe: Boolean, ramMb: Long): Int {
            if (vramMb <= 0) return 0
            val default = if (safe && ramMb in 1..12_800) ((ramMb / 2) and 511L.inv()).coerceIn(2048, 4096).toInt() else 0
            return if (default > 0) minOf(vramMb, default) else vramMb
        }

        fun memTotalMb(): Long = runCatching {
            File("/proc/meminfo").useLines { lines ->
                lines.first { it.startsWith("MemTotal:") }.split(Regex("\\s+"))[1].toLong() / 1024
            }
        }.getOrDefault(0L)

        fun stuckPipelines(context: Context): File = File(File(context.filesDir, CACHE_DIR), STUCK_PIPELINES)

        fun writeMode(dir: File, mode: String) {
            val file = File(dir, MODE_FILE)
            if (runCatching { file.readText().trim() }.getOrNull() == mode) return
            val tmp = File(dir, "$MODE_FILE.tmp")
            runCatching {
                tmp.writeText(mode + "\n")
                if (!tmp.renameTo(file)) error("rename failed")
                Log.i(TAG, "bridge mode: $mode")
            }.onFailure { Log.w(TAG, "could not write $file: $it"); tmp.delete() }
        }

        private fun migrate(old: File, dir: File) {
            val moved = if (!dir.exists()) old.renameTo(dir) else false
            if (!moved) {
                dir.mkdirs()
                old.listFiles()?.forEach { f ->
                    val to = File(dir, f.name)
                    if (to.exists()) return@forEach
                    if (!f.renameTo(to)) runCatching { f.copyRecursively(to, overwrite = false) }
                }
                old.deleteRecursively()
            }
            Log.i(TAG, "bridge cache moved from $old to $dir")
        }

        fun stopOrder(parts: List<SessionPart>): Pair<List<SessionPart>, List<SessionPart>> =
            parts.reversed().partition { it !is VkBridgeComponent }

        const val BINARY = "libvkbridge_server.so"
        const val GUEST_ICD = "/usr/local/share/vkbridge/droidbridge_icd.json"
        const val GUEST_LIB = "/usr/local/lib/vkbridge/libvulkan_droidbridge.so"
    }
}
