package com.droiddeck.launcher.core

import java.io.BufferedWriter
import java.io.File
import java.util.function.Consumer

/**
 * A process's own output into a small file (the session's proot.log): proot and the guest's
 * env/init write there before the session script points everything at BL_LOG, and a proot that
 * dies in its first milliseconds leaves its reason nowhere else (Kirin 985, 20 ms, MaliBridge#10).
 *
 * The first [headBytes] go straight to the file; past that the last [tailBytes] are held and
 * written after it at [close], with a count of the lines left out between them. [recent] keeps
 * the last lines either way, for copying into app.log when the process ends right away.
 */
class ProcessLog(
    private val file: File,
    private val headBytes: Long = 128L * 1024,
    private val tailBytes: Long = 128L * 1024,
) : Consumer<String> {
    private var writer: BufferedWriter? = runCatching { file.bufferedWriter() }.getOrNull()
    private var written = 0L
    private val tail = ArrayDeque<String>()
    private var tailSize = 0L
    private var dropped = 0
    private val last = ArrayDeque<String>()

    @Synchronized
    override fun accept(line: String) {
        last.addLast(line)
        if (last.size > RECENT_LINES) last.removeFirst()
        val w = writer ?: return
        if (written < headBytes) {
            try {
                w.write(line); w.newLine(); w.flush()
            } catch (e: Exception) {
            }
            written += line.length + 1
            return
        }
        tail.addLast(line)
        tailSize += line.length + 1
        while (tailSize > tailBytes && tail.isNotEmpty()) {
            tailSize -= tail.removeFirst().length + 1
            dropped++
        }
    }

    /** The last lines seen (at most [RECENT_LINES]). */
    @Synchronized
    fun recent(): List<String> = last.toList()

    /** Writes the held tail and [footer], then closes the file; later lines are only kept in [recent]. */
    @Synchronized
    fun close(footer: String? = null) {
        val w = writer ?: return
        writer = null
        try {
            if (dropped > 0) w.write("[$dropped lines left out]\n")
            tail.forEach { w.write(it); w.newLine() }
            if (footer != null) w.write(footer + "\n")
            w.close()
        } catch (e: Exception) {
        }
        tail.clear()
        tailSize = 0
    }

    companion object {
        const val RECENT_LINES = 40
    }
}
