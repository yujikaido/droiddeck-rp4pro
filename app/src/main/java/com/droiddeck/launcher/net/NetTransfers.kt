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
import java.util.concurrent.atomic.AtomicLong

/**
 * The Network page's copy queue: one job at a time, in the order they were added, on its own thread (a foreground
 * service holds the app up while it runs, so leaving the page or turning the screen off doesn't stop it).
 *
 * Resuming is by size: a file already there at the same size is skipped, a shorter one is carried on from its end.
 * So a dropped connection retries from the file it was on, and pressing Retry on a failed job (or copying the same
 * folder again) only fetches what's missing.
 */
object NetTransfers {
    private const val TAG = "NetTransfers"
    private val RETRY_WAITS_S = intArrayOf(2, 4, 8, 15, 30, 30, 30, 30)

    enum class Direction { DOWN, UP }
    enum class Status { QUEUED, SCANNING, COPYING, RETRYING, DONE, FAILED, CANCELLED }

    class Job internal constructor(
        val id: Long,
        val share: NetShare,
        val direction: Direction,
        /** DOWN: share paths to fetch. UP: device files to send. */
        internal val remoteSources: List<NetEntry>,
        internal val localSources: List<File>,
        /** DOWN: the device folder they go into. UP: the share folder they go into. */
        internal val localTarget: File?,
        internal val remoteTarget: String,
        val label: String,
        val destination: String,
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
        add(context, Job(ids.getAndIncrement(), share, Direction.DOWN, entries, emptyList(), intoDir, "", label, intoDir.path))
    }

    fun upload(context: Context, share: NetShare, files: List<File>, intoPath: String) {
        if (files.isEmpty()) return
        val label = if (files.size == 1) files[0].name else "${files.size} items"
        add(context, Job(ids.getAndIncrement(), share, Direction.UP, emptyList(), files, null, intoPath, label, share.address + (if (intoPath.isEmpty()) "" else "\\$intoPath")))
    }

    fun cancel(job: Job) {
        job.cancelled = true
        if (job.status == Status.QUEUED) job.status = Status.CANCELLED
    }

    fun retry(context: Context, job: Job) {
        if (job.active) return
        val again = Job(ids.getAndIncrement(), job.share, job.direction, job.remoteSources, job.localSources, job.localTarget, job.remoteTarget, job.label, job.destination)
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

    private class Step(val remote: String, val local: File, val size: Long, val modified: Long, val dir: Boolean)

    private fun run(job: Job) {
        if (job.cancelled) { job.status = Status.CANCELLED; idle(); return }
        val context = app ?: return
        val password = NetShares.password(context, job.share.id)
        var link: SmbLink? = null
        var steps: List<Step>? = null
        var at = 0
        var attempt = 0
        val window = ArrayDeque<Pair<Long, Long>>()
        while (true) {
            try {
                if (link == null) link = SmbLink.open(job.share, password)
                if (steps == null) {
                    job.status = Status.SCANNING
                    steps = plan(job, link)
                    job.filesTotal = steps.count { !it.dir }
                    job.bytesTotal = steps.sumOf { it.size }
                }
                job.status = Status.COPYING
                job.error = null
                while (at < steps.size) {
                    if (job.cancelled) break
                    val step = steps[at]
                    job.current = step.local.name
                    if (job.direction == Direction.DOWN) fetch(job, link, step, window) else send(job, link, step, window)
                    at++
                    attempt = 0
                }
                job.status = if (job.cancelled) Status.CANCELLED else Status.DONE
                job.current = ""
                job.rate = 0L
                break
            } catch (e: Throwable) {
                Log.w(TAG, "job ${job.id} (${job.label}) at step $at: ${e.message}", e)
                runCatching { link?.close() }
                link = null
                if (job.cancelled) { job.status = Status.CANCELLED; break }
                job.error = describeNetError(e)
                if (attempt >= RETRY_WAITS_S.size) { job.status = Status.FAILED; break }
                job.status = Status.RETRYING
                job.rate = 0L
                val wait = RETRY_WAITS_S[attempt++]
                for (i in 0 until wait * 10) { if (job.cancelled) break; Thread.sleep(100) }
                // the file it was on starts over from what reached the other side
                steps?.let { recount(job, it, at) }
            }
        }
        runCatching { link?.close() }
        idle()
    }

    /** Every folder and file to copy, folders before what's in them. */
    private fun plan(job: Job, link: SmbLink): List<Step> {
        val out = ArrayList<Step>()
        if (job.direction == Direction.DOWN) {
            val into = job.localTarget!!
            fun walk(e: NetEntry, local: File) {
                if (job.cancelled) return
                if (e.dir) {
                    out += Step(e.path, local, 0L, e.modified, true)
                    link.list(e.path).forEach { walk(it, File(local, it.name)) }
                } else out += Step(e.path, local, e.size, e.modified, false)
            }
            job.remoteSources.forEach { walk(it, File(into, it.name)) }
        } else {
            fun walk(f: File, remote: String) {
                if (job.cancelled) return
                if (f.isDirectory) {
                    out += Step(remote, f, 0L, f.lastModified(), true)
                    f.listFiles().orEmpty().sortedBy { it.name.lowercase() }.forEach { walk(it, SmbLink.join(remote, it.name)) }
                } else out += Step(remote, f, f.length(), f.lastModified(), false)
            }
            job.localSources.forEach { walk(it, SmbLink.join(job.remoteTarget, it.name)) }
        }
        return out
    }

    /** After a reconnect: bytes and files done are what the steps before [at] add up to. */
    private fun recount(job: Job, steps: List<Step>, at: Int) {
        val before = steps.subList(0, at)
        job.bytesDone = before.sumOf { it.size }
        job.filesDone = before.count { !it.dir }
    }

    private fun fetch(job: Job, link: SmbLink, step: Step, window: ArrayDeque<Pair<Long, Long>>) {
        if (step.dir) {
            if (!step.local.isDirectory && !step.local.mkdirs()) throw java.io.IOException("can't create ${step.local.path}")
            return
        }
        val have = if (step.local.isFile) step.local.length() else 0L
        if (have == step.size && step.local.isFile) {
            job.bytesDone += step.size
            job.filesDone++
            return
        }
        step.local.parentFile?.mkdirs()
        val from = if (have < step.size) have else 0L
        job.bytesDone += from
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
                    job.bytesDone += n
                    measure(job, window)
                }
            }
        }
        if (step.modified > 0) step.local.setLastModified(step.modified)
        job.filesDone++
    }

    private fun send(job: Job, link: SmbLink, step: Step, window: ArrayDeque<Pair<Long, Long>>) {
        if (step.dir) {
            link.mkdirs(step.remote)
            return
        }
        val have = link.sizeOf(step.remote)
        if (have == step.size) {
            job.bytesDone += step.size
            job.filesDone++
            return
        }
        SmbLink.parent(step.remote).takeIf { it.isNotEmpty() }?.let { link.mkdirs(it) }
        val from = if (have in 0 until step.size) have else 0L
        job.bytesDone += from
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
                    job.bytesDone += n
                    measure(job, window)
                }
            }
        }
        job.filesDone++
    }

    private fun measure(job: Job, window: ArrayDeque<Pair<Long, Long>>) {
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
