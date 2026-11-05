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
 * Resuming is by size: a file already there at the same size is skipped, a shorter one is carried on from its end.
 * So a dropped connection retries from the file it was on, and pressing Retry on a failed job (or copying the same
 * folder again) only fetches what's missing.
 */
object NetTransfers {
    private const val TAG = "NetTransfers"
    private val RETRY_WAITS_S = intArrayOf(2, 4, 8, 15, 30, 30, 30, 30)
    private const val LANES = 4

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
                }
                job.status = Status.COPYING
                job.error = null
                // each pass counts from zero: files already there (whole or in part) add themselves as they're skipped
                job.bytesDone = 0L
                job.filesDone = 0
                lanes(job, steps.filter { it.dir }, password, window, 1)
                lanes(job, steps.filter { !it.dir && !it.last }, password, window, LANES)
                lanes(job, steps.filter { !it.dir && it.last }, password, window, 1)
                job.status = if (job.cancelled) Status.CANCELLED else Status.DONE
                job.current = ""
                job.rate = 0L
                break
            } catch (e: Throwable) {
                Log.w(TAG, "job ${job.id} (${job.label}): ${e.message}", e)
                if (job.cancelled) { job.status = Status.CANCELLED; break }
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

    private fun fetch(job: Job, link: SmbLink, step: Step, window: ArrayDeque<Pair<Long, Long>>) {
        if (step.dir) {
            if (!step.local.isDirectory && !step.local.mkdirs()) throw java.io.IOException("can't create ${step.local.path}")
            return
        }
        val have = if (step.local.isFile) step.local.length() else 0L
        if (have == step.size && step.local.isFile) {
            advance(job, step.size, 1)
            return
        }
        step.local.parentFile?.mkdirs()
        val from = if (have < step.size) have else 0L
        advance(job, from, 0)
        link.openRead(step.remote).use { src ->
            RandomAccessFile(step.local, "rw").use { out ->
                if (from == 0L) out.setLength(0L)
                out.seek(from)
                val buf = ByteArray(link.chunk)
                var pos = from
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
        if (step.modified > 0) step.local.setLastModified(step.modified)
        advance(job, 0L, 1)
    }

    private fun send(job: Job, link: SmbLink, step: Step, window: ArrayDeque<Pair<Long, Long>>) {
        if (step.dir) {
            link.mkdirs(step.remote)
            return
        }
        val have = link.sizeOf(step.remote)
        if (have == step.size) {
            advance(job, step.size, 1)
            return
        }
        SmbLink.parent(step.remote).takeIf { it.isNotEmpty() }?.let { link.mkdirs(it) }
        val from = if (have in 0 until step.size) have else 0L
        advance(job, from, 0)
        link.openWrite(step.remote).use { dst ->
            if (from == 0L && have > 0L) dst.setLength(0L)
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
            }
        }
        advance(job, 0L, 1)
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
