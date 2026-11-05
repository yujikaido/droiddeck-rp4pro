package com.droiddeck.launcher.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The Network page's copy queue: one job at a time, in the order they were added, on its own thread (a foreground
 * service holds the app up while it runs, so leaving the page or turning the screen off doesn't stop it). Inside a
 * job, files go [LANES] at a time, each lane on its own connection: most of a small file's time is waiting on the
 * network and the server's disks, so several in flight keep the link busy.
 *
 * A file already there with the same size and date is skipped; anything else is copied again. Each file is written
 * under a .ddpart name and only takes its real name once whole, so a half-copied file never passes for a finished
 * one, and a dropped connection carries on from the end of the .ddpart. So Retry (or copying a folder or a game
 * again, as installing an update does) only moves what's missing or changed.
 */
object NetTransfers {
    private const val TAG = "NetTransfers"
    private val RETRY_WAITS_S = intArrayOf(2, 4, 8, 15, 30, 30, 30, 30)
    private const val LANES = 4
    private const val PART = ".ddpart"
    /** Room left over after a copy, so the device isn't filled to the last byte. */
    private const val SPARE = 256L shl 20

    /** Not enough room on the other side, or a file name that isn't safe: retrying won't help, so the job stops at once. */
    private class NoSpace(message: String) : java.io.IOException(message)

    /** Files copied at a time (Settings). */
    fun lanes(context: Context): Int = context.getSharedPreferences("net_shares", Context.MODE_PRIVATE).getInt("lanes", LANES).coerceIn(1, 8)

    fun setLanes(context: Context, n: Int) = context.getSharedPreferences("net_shares", Context.MODE_PRIVATE).edit().putInt("lanes", n).apply()

    enum class Direction { DOWN, UP }
    enum class Status { QUEUED, SCANNING, COPYING, RETRYING, DONE, FAILED, CANCELLED }

    class Job internal constructor(
        val id: Long,
        val share: NetShare,
        val direction: Direction,
        /** DOWN: each share path and the device folder it goes into. Copied in this order, so a Steam game's
         * .acf goes last and the game never looks installed while its files are still arriving. */
        internal val downs: List<Pair<NetEntry, File>>,
        /** UP: each device file or folder and the share folder it goes into. */
        internal val ups: List<Pair<File, String>>,
        val label: String,
        val destination: String,
        /** Set for a Steam game install or backup, so its cover can show the progress. */
        val appId: Int? = null,
    ) {
        var status by mutableStateOf(Status.QUEUED)
            internal set
        var bytesDone by mutableLongStateOf(0L)
            internal set
        var bytesTotal by mutableLongStateOf(0L)
            internal set
        var filesDone by mutableIntStateOf(0)
            internal set
        var filesTotal by mutableIntStateOf(0)
            internal set
        var current by mutableStateOf("")
            internal set
        var error by mutableStateOf<String?>(null)
            internal set
        /** Bytes per second over the last few seconds. */
        var rate by mutableLongStateOf(0L)
            internal set
        @Volatile internal var cancelled = false
        /** Bytes actually moved (not skipped as already there), so a retry knows whether the last pass got anywhere. */
        internal val moved = AtomicLong(0L)
        val active: Boolean get() = status == Status.QUEUED || status == Status.SCANNING || status == Status.COPYING || status == Status.RETRYING
        val fraction: Float get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
    }

    val jobs = mutableStateListOf<Job>()
    private val ids = AtomicLong(1)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "net-transfers").apply { isDaemon = true } }
    private var app: Context? = null

    fun download(context: Context, share: NetShare, entries: List<NetEntry>, intoDir: File) {
        if (entries.isEmpty()) return
        val label = if (entries.size == 1) entries[0].name else "${entries.size} items"
        add(context, Job(ids.getAndIncrement(), share, Direction.DOWN, entries.map { it to intoDir }, emptyList(), label, intoDir.path))
    }

    fun upload(context: Context, share: NetShare, files: List<File>, intoPath: String) {
        if (files.isEmpty()) return
        val label = if (files.size == 1) files[0].name else "${files.size} items"
        add(context, Job(ids.getAndIncrement(), share, Direction.UP, emptyList(), files.map { it to intoPath }, label, share.address + (if (intoPath.isEmpty()) "" else "\\$intoPath")))
    }

    /** A Steam game from the share into a device library: its common/ folder, then its .acf. */
    fun installSteamGame(context: Context, share: NetShare, game: NetSteamGame, library: File, libraryLabel: String) {
        val steamapps = File(library, "steamapps")
        val downs = listOfNotNull(
            game.dir?.let { it to File(steamapps, "common") },
            game.acf to steamapps,
        )
        add(context, Job(ids.getAndIncrement(), share, Direction.DOWN, downs, emptyList(), game.name, libraryLabel, game.appId))
    }

    /** A Steam game on this device to the share's steamapps folder: its common/ folder, then its .acf. */
    fun backUpSteamGame(context: Context, share: NetShare, appId: Int, name: String, gameDir: File, acf: File, steamapps: String) {
        val ups = listOf(gameDir to SmbLink.join(steamapps, "common"), acf to steamapps)
        add(context, Job(ids.getAndIncrement(), share, Direction.UP, emptyList(), ups, name, share.address + "\\" + steamapps, appId))
    }

    fun cancel(job: Job) {
        job.cancelled = true
        if (job.status == Status.QUEUED) job.status = Status.CANCELLED
    }

    fun retry(context: Context, job: Job) {
        if (job.active) return
        val again = Job(ids.getAndIncrement(), job.share, job.direction, job.downs, job.ups, job.label, job.destination, job.appId)
        main.post { jobs.remove(job) }
        add(context, again)
    }

    fun clearFinished() = main.post { jobs.removeAll { !it.active } }

    val busy: Boolean get() = jobs.any { it.active }

    private fun add(context: Context, job: Job) {
        app = context.applicationContext
        main.post {
            jobs.add(job)
            NetTransferService.start(context.applicationContext)
        }
        worker.execute { run(job) }
    }

    /** [last]: a Steam game's .acf, copied after everything else so the game never looks installed half-copied. */
    private class Step(val remote: String, val local: File, val size: Long, val modified: Long, val dir: Boolean, val last: Boolean = false)

    private fun run(job: Job) {
        if (job.cancelled) { job.status = Status.CANCELLED; idle(); return }
        val context = app ?: return
        val password = NetShares.password(context, job.share.id)
        var steps: List<Step>? = null
        var attempt = 0
        val window = ArrayDeque<Pair<Long, Long>>()
        while (true) {
            val movedBefore = job.moved.get()
            try {
                if (steps == null) {
                    job.status = Status.SCANNING
                    steps = SmbLink.open(job.share, password).use { plan(job, it) }
                    job.filesTotal = steps.count { !it.dir }
                    job.bytesTotal = steps.sumOf { it.size }
                    SmbLink.open(job.share, password).use { checkSpace(context, job, it, steps) }
                }
                job.status = Status.COPYING
                job.error = null
                // each pass counts from zero: files already there (whole or in part) add themselves as they're skipped
                job.bytesDone = 0L
                job.filesDone = 0
                lanes(job, steps.filter { it.dir }, password, window, 1)
                lanes(job, steps.filter { !it.dir && !it.last }, password, window, lanes(context))
                lanes(job, steps.filter { !it.dir && it.last }, password, window, 1)
                job.status = if (job.cancelled) Status.CANCELLED else Status.DONE
                job.current = ""
                job.rate = 0L
                break
            } catch (e: Throwable) {
                Log.w(TAG, "job ${job.id} (${job.label}): ${e.message}", e)
                if (job.cancelled) { job.status = Status.CANCELLED; break }
                if (e is NoSpace) { job.error = e.message; job.status = Status.FAILED; break }
                job.error = describeNetError(e)
                if (job.moved.get() > movedBefore) attempt = 0
                if (attempt >= RETRY_WAITS_S.size) { job.status = Status.FAILED; break }
                job.status = Status.RETRYING
                job.rate = 0L
                val wait = RETRY_WAITS_S[attempt++]
                for (i in 0 until wait * 10) { if (job.cancelled) break; Thread.sleep(100) }
            }
        }
        idle()
    }

    /**
     * Copies [steps] on up to [count] threads, each with its own connection, taking the next step as each finishes.
     * The first failure stops the others and is thrown here; folders go on one lane so parents come first.
     */
    private fun lanes(job: Job, steps: List<Step>, password: String, window: ArrayDeque<Pair<Long, Long>>, count: Int) {
        if (steps.isEmpty() || job.cancelled) return
        val next = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val threads = (0 until minOf(count, steps.size)).map { i ->
            Thread({
                try {
                    SmbLink.open(job.share, password).use { link ->
                        while (!job.cancelled && failure.get() == null) {
                            val k = next.getAndIncrement()
                            if (k >= steps.size) break
                            val step = steps[k]
                            if (!step.dir) job.current = step.local.name
                            if (job.direction == Direction.DOWN) fetch(job, link, step, window) else send(job, link, step, window)
                        }
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                }
            }, "net-lane-$i").apply { isDaemon = true }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        failure.get()?.let { throw it }
    }

    /** Every folder and file to copy, folders before what's in them. */
    private fun plan(job: Job, link: SmbLink): List<Step> {
        val out = ArrayList<Step>()
        var last = false
        if (job.direction == Direction.DOWN) {
            fun walk(e: NetEntry, local: File) {
                if (job.cancelled) return
                if (!SmbLink.safeName(e.name)) throw NoSpace("The share sent a file name that isn't safe to copy: ${e.name.take(60)}")
                if (e.dir) {
                    out += Step(e.path, local, 0L, e.modified, true)
                    link.list(e.path).forEach { walk(it, File(local, it.name)) }
                } else out += Step(e.path, local, e.size, e.modified, false, last)
            }
            job.downs.forEachIndexed { i, (e, into) -> last = job.appId != null && i == job.downs.lastIndex; walk(e, File(into, e.name)) }
        } else {
            fun walk(f: File, remote: String) {
                if (job.cancelled) return
                if (f.isDirectory) {
                    out += Step(remote, f, 0L, f.lastModified(), true)
                    f.listFiles().orEmpty().sortedBy { it.name.lowercase() }.forEach { walk(it, SmbLink.join(remote, it.name)) }
                } else out += Step(remote, f, f.length(), f.lastModified(), false, last)
            }
            job.ups.forEachIndexed { i, (f, into) -> last = job.appId != null && i == job.ups.lastIndex; walk(f, SmbLink.join(into, f.name)) }
        }
        return out
    }

    /** Same file: same size, and the same date give or take 2 s (FAT keeps dates to 2 s; a date of 0 means unknown). */
    private fun same(size: Long, modified: Long, step: Step): Boolean =
        size == step.size && (step.modified <= 0L || modified <= 0L || kotlin.math.abs(modified - step.modified) <= 2_000L)

    private fun fetch(job: Job, link: SmbLink, step: Step, window: ArrayDeque<Pair<Long, Long>>) {
        if (step.dir) {
            if (!step.local.isDirectory && !step.local.mkdirs()) throw java.io.IOException("can't create ${step.local.path}")
            return
        }
        if (step.local.isFile && same(step.local.length(), step.local.lastModified(), step)) {
            advance(job, step.size, 1)
            return
        }
        step.local.parentFile?.mkdirs()
        val part = File(step.local.path + PART)
        val from = if (part.isFile && part.length() <= step.size) part.length() else 0L
        advance(job, from, 0)
        var pos = from
        link.openRead(step.remote).use { src ->
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(from)
                out.seek(from)
                val buf = ByteArray(link.chunk)
                while (pos < step.size) {
                    if (job.cancelled) return
                    val n = src.read(buf, pos, 0, minOf(buf.size.toLong(), step.size - pos).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    pos += n
                    advance(job, n.toLong(), 0)
                    job.moved.addAndGet(n.toLong())
                    measure(job, window)
                }
            }
        }
        if (pos < step.size) throw java.io.IOException("${step.remote} ended early")
        if (step.local.exists() && !step.local.delete()) throw java.io.IOException("can't replace ${step.local.path}")
        if (!part.renameTo(step.local)) throw java.io.IOException("can't rename ${part.path}")
        if (step.modified > 0) step.local.setLastModified(step.modified)
        advance(job, 0L, 1)
    }

    private fun send(job: Job, link: SmbLink, step: Step, window: ArrayDeque<Pair<Long, Long>>) {
        if (step.dir) {
            link.mkdirs(step.remote)
            return
        }
        val there = link.stat(step.remote)
        if (there != null && same(there.size, there.modified, step)) {
            advance(job, step.size, 1)
            return
        }
        SmbLink.parent(step.remote).takeIf { it.isNotEmpty() }?.let { link.mkdirs(it) }
        val partPath = step.remote + PART
        val have = link.stat(partPath)?.size ?: -1L
        val from = if (have in 0..step.size) have else 0L
        advance(job, from, 0)
        link.openWrite(partPath).use { dst ->
            if (have != from) dst.setLength(0L)
            RandomAccessFile(step.local, "r").use { input ->
                input.seek(from)
                val buf = ByteArray(link.chunk)
                var pos = from
                while (pos < step.size) {
                    if (job.cancelled) return
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), step.size - pos).toInt())
                    if (n <= 0) break
                    dst.write(buf, pos, 0, n)
                    pos += n
                    advance(job, n.toLong(), 0)
                    job.moved.addAndGet(n.toLong())
                    measure(job, window)
                }
                if (pos < step.size) throw java.io.IOException("${step.local.path} ended early")
            }
            link.finish(dst, step.remote, step.modified)
        }
        advance(job, 0L, 1)
    }

    /**
     * Stops a copy that can't fit before it starts: what still has to move (files not already there) against the free
     * space where it's going, less a little to spare on the device.
     */
    private fun checkSpace(context: Context, job: Job, link: SmbLink, steps: List<Step>) {
        val files = steps.filter { !it.dir }
        fun size(n: Long) = android.text.format.Formatter.formatShortFileSize(context, n)
        if (job.direction == Direction.DOWN) {
            val need = files.sumOf { st ->
                if (st.local.isFile && st.local.length() == st.size) 0L
                else st.size - File(st.local.path + PART).let { if (it.isFile && it.length() <= st.size) it.length() else 0L }
            }
            var dir: File? = job.downs.firstOrNull()?.second
            while (dir != null && !dir.exists()) dir = dir.parentFile
            val free = dir?.usableSpace ?: return
            if (need > 0L && need + SPARE > free) throw NoSpace(context.getString(com.droiddeck.launcher.R.string.net_no_space_device, size(need), size(free)))
        } else {
            val free = link.freeSpace() ?: return
            if (job.bytesTotal <= free) return
            // only worth asking file by file when the whole lot wouldn't fit
            val need = files.sumOf { st -> if (link.stat(st.remote)?.size == st.size) 0L else st.size }
            if (need > free) throw NoSpace(context.getString(com.droiddeck.launcher.R.string.net_no_space_share, size(need), size(free)))
        }
    }

    /** The lanes add to the same counters. */
    private fun advance(job: Job, bytes: Long, files: Int) = synchronized(job) {
        if (bytes != 0L) job.bytesDone += bytes
        if (files != 0) job.filesDone += files
    }

    private fun measure(job: Job, window: ArrayDeque<Pair<Long, Long>>) = synchronized(window) {
        val now = System.nanoTime()
        window.addLast(now to job.bytesDone)
        while (window.size > 2 && now - window.first().first > 4_000_000_000L) window.removeFirst()
        val (t0, b0) = window.first()
        if (now - t0 > 500_000_000L) job.rate = (job.bytesDone - b0) * 1_000_000_000L / (now - t0)
        NetTransferService.progress()
    }

    private fun idle() {
        main.post { if (!busy) NetTransferService.stop() else NetTransferService.progress() }
    }
}
