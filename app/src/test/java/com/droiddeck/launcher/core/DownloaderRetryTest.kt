package com.droiddeck.launcher.core

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Retries, backoff and mirrors. A plain socket server answers per path from a script, so a test can
 * make one host fail, time out, cut a body short or serve a tampered file.
 */
class DownloaderRetryTest {
    private lateinit var server: ServerSocket
    private lateinit var dir: File
    private val requests = CopyOnWriteArrayList<String>()
    private val sleeps = CopyOnWriteArrayList<Long>()
    private val hits = HashMap<String, AtomicInteger>()

    /** Path -> answer for the n-th request (0-based) to that path. */
    @Volatile private var route: (path: String, n: Int, range: String?) -> Answer = { _, _, _ -> Answer.Status(404) }

    sealed class Answer {
        class Body(val bytes: ByteArray, val cutAt: Int = -1) : Answer()
        class Status(val code: Int) : Answer()
        object Hang : Answer()
    }

    private val base get() = "http://127.0.0.1:${server.localPort}"

    @Before fun setUp() {
        dir = Files.createTempDirectory("downloader-retry").toFile()
        server = ServerSocket(0)
        Thread {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    Thread { runCatching { handle(socket) } }.apply { isDaemon = true; start() }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        Downloader.policy = Downloader.Policy(4, 9, 1_000, 8_000, 2_000, 500)
        Downloader.sleeper = Downloader.Sleeper { sleeps.add(it) }
        Downloader.rawBase = "$base/raw"
        Downloader.jsdelivrBase = "$base/jsd"
        Downloader.githubBase = "$base/gh"
        Downloader.forgetFailure()
    }

    @After fun tearDown() {
        Downloader.policy = Downloader.DEFAULT_POLICY
        Downloader.sleeper = Downloader.Sleeper { Thread.sleep(it) }
        Downloader.rawBase = "https://raw.githubusercontent.com"
        Downloader.jsdelivrBase = "https://cdn.jsdelivr.net/gh"
        Downloader.githubBase = "https://github.com"
        server.close()
        dir.deleteRecursively()
    }

    private fun handle(socket: Socket) = socket.use {
        val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return
        var range: String? = null
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        requests.add(path + (range?.let { r -> " $r" } ?: ""))
        val n = synchronized(hits) { hits.getOrPut(path) { AtomicInteger() } }.getAndIncrement()
        val out = it.getOutputStream()
        when (val a = route(path, n, range)) {
            is Answer.Status -> out.write("HTTP/1.1 ${a.code} X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            is Answer.Hang -> { Thread.sleep(3_000); return }
            is Answer.Body -> {
                val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
                if (from != null) {
                    val rest = a.bytes.size - from
                    out.write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-${a.bytes.size - 1}/${a.bytes.size}\r\n" +
                        "Content-Length: $rest\r\nConnection: close\r\n\r\n").toByteArray())
                    out.write(a.bytes, from, rest)
                } else {
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${a.bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(a.bytes, 0, if (a.cutAt >= 0) a.cutAt else a.bytes.size)
                }
            }
        }
        out.flush()
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ---- pure parts ----

    @Test fun repositoryFilesGetTheMirrorsInOrder() {
        Downloader.rawBase = "https://raw.githubusercontent.com"
        Downloader.jsdelivrBase = "https://cdn.jsdelivr.net/gh"
        Downloader.githubBase = "https://github.com"
        val want = listOf(
            "https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/main/linuxfs.json",
            "https://cdn.jsdelivr.net/gh/Droid-Deck/DroidDeck-Components@main/linuxfs.json",
            "https://github.com/Droid-Deck/DroidDeck-Components/raw/main/linuxfs.json",
        )
        assertEquals(want, Downloader.candidates(want[0]))
        assertEquals(listOf(want[1], want[0], want[2]), Downloader.candidates(want[1]))
        assertEquals(listOf(want[2], want[0], want[1]), Downloader.candidates(want[2]))
    }

    @Test fun releaseAssetsApiAndQueriesHaveNoMirror() {
        Downloader.githubBase = "https://github.com"
        for (url in listOf(
            "https://github.com/Droid-Deck/Nightlies/releases/download/Dxvk-Linux/dxvk-2.7.1-linux.wcp",
            "https://api.github.com/repos/Droid-Deck/Nightlies/releases/tags/Dxvk-Linux",
            "https://raw.githubusercontent.com/o/r/main/catalog.json?checked=1",
            "https://raw.githubusercontent.com/o/r/main/../x.json",
            "https://example.com/file.json",
        )) assertEquals(url, listOf(url), Downloader.candidates(url))
    }

    @Test fun backoffDoublesWithJitterAndIsCapped() {
        val p = Downloader.Policy(10, 10, 1_000, 8_000, 1, 1)
        val r = Random(7)
        assertEquals(0, Downloader.backoffMs(p, 0, r))
        for ((round, full) in listOf(1 to 1_000L, 2 to 2_000L, 3 to 4_000L, 4 to 8_000L, 5 to 8_000L, 30 to 8_000L)) {
            repeat(50) {
                val d = Downloader.backoffMs(p, round, r)
                assertTrue("round $round: $d", d in full / 2..full)
            }
        }
    }

    @Test fun defaultsAreSensible() {
        val p = Downloader.DEFAULT_POLICY
        assertEquals(15_000, p.connectTimeoutMs)
        assertEquals(30_000, p.readTimeoutMs)
        assertTrue(p.rounds >= 3 && p.maxTries in 4..12)
        assertTrue(Downloader.retryable(503) && Downloader.retryable(429) && Downloader.retryable(408))
        assertFalse(Downloader.retryable(404) || Downloader.retryable(403) || Downloader.retryable(410))
    }

    // ---- text ----

    @Test fun serverErrorsAreRetriedWithBackoff() {
        route = { _, n, _ -> if (n < 2) Answer.Status(503) else Answer.Body("{\"ok\":1}".toByteArray()) }
        assertEquals("{\"ok\":1}", Downloader.downloadString("$base/plain/catalog.json"))
        assertEquals(3, requests.size)
        assertEquals(2, sleeps.size)
        assertTrue("waits grow: $sleeps", sleeps[0] in 500L..1_000L && sleeps[1] in 1_000L..2_000L)
        assertNull(Downloader.lastFailure())
    }

    @Test fun notFoundIsNotRetried() {
        route = { _, _, _ -> Answer.Status(404) }
        assertNull(Downloader.downloadString("$base/plain/missing.json"))
        assertEquals(1, requests.size)
        assertTrue(sleeps.isEmpty())
        val f = Downloader.lastFailure()
        assertNotNull(f)
        assertEquals(1, f!!.tries)
        assertTrue(f.last, f.last.endsWith("HTTP 404"))
    }

    @Test fun aTimedOutTryIsRetried() {
        route = { _, n, _ -> if (n == 0) Answer.Hang else Answer.Body("late".toByteArray()) }
        assertEquals("late", Downloader.downloadString("$base/plain/slow.json"))
        assertEquals(2, requests.size)
    }

    @Test fun catalogComesFromTheNextHostWhenRawFails() {
        route = { path, _, _ -> if (path.startsWith("/raw/")) Answer.Status(503) else Answer.Body("from ${path.substring(1, 4)}".toByteArray()) }
        assertEquals("from jsd", Downloader.downloadString("$base/raw/Droid-Deck/DroidDeck-Components/main/linuxfs.json"))
        assertEquals(listOf("/raw/Droid-Deck/DroidDeck-Components/main/linuxfs.json", "/jsd/Droid-Deck/DroidDeck-Components@main/linuxfs.json"), requests.toList())
        assertTrue("another host needs no wait", sleeps.isEmpty())
    }

    @Test fun allHostsDownGivesUpWithAReason() {
        route = { _, _, _ -> Answer.Status(502) }
        assertNull(Downloader.downloadString("$base/raw/o/r/main/linuxfs.json"))
        assertEquals("capped at maxTries", 9, requests.size)
        assertEquals("waits only between rounds", 2, sleeps.size)
        val f = Downloader.lastFailure()!!
        assertEquals(9, f.tries)
        assertEquals("HTTP 502", f.last.substringAfter(": "))
    }

    // ---- files ----

    @Test fun aCutOffFileIsResumedFromTheSameUrlInOneCall() {
        val data = ByteArray(200_000) { (it % 253).toByte() }
        route = { _, n, _ -> Answer.Body(data, if (n == 0) 70_000 else -1) }
        val target = File(dir, "pkg.tar")
        assertTrue(Downloader.downloadFile("$base/gh/o/r/releases/download/t/pkg.tar", target, false, null))
        assertArrayEquals(data, target.readBytes())
        assertEquals(listOf("/gh/o/r/releases/download/t/pkg.tar", "/gh/o/r/releases/download/t/pkg.tar bytes=70000-"), requests.toList())
    }

    @Test fun aTamperedFileFromOneHostIsSkippedForTheNext() {
        val good = ByteArray(50_000) { (it % 7).toByte() }
        val bad = good.copyOf().also { it[100] = 99 }
        route = { path, _, _ -> Answer.Body(if (path.startsWith("/raw/")) bad else good) }
        val target = File(dir, "pkg.wcp")
        assertTrue(Downloader.downloadFile("$base/raw/o/r/main/pkg.wcp", target, false, null, sha256(good)))
        assertArrayEquals(good, target.readBytes())
        assertEquals(2, requests.size)
    }

    @Test fun everyHostTamperedFailsAndLeavesNoFile() {
        val good = ByteArray(10_000) { 1 }
        route = { _, _, _ -> Answer.Body(ByteArray(10_000) { 2 }) }
        val target = File(dir, "pkg.wcp")
        assertFalse(Downloader.downloadFile("$base/raw/o/r/main/pkg.wcp", target, false, null, sha256(good)))
        assertFalse(target.exists())
        assertEquals("each host once: a mismatch is final for that host", 3, requests.size)
        assertEquals("checksum mismatch", Downloader.lastFailure()!!.last.substringAfter(": "))
    }

    @Test fun withoutAChecksumNoMirrorIsUsedForFiles() {
        route = { path, _, _ -> if (path.startsWith("/raw/")) Answer.Status(404) else Answer.Body(ByteArray(10)) }
        assertFalse(Downloader.downloadFile("$base/raw/o/r/main/icon.png", File(dir, "icon.png"), false, null))
        assertEquals(listOf("/raw/o/r/main/icon.png"), requests.toList())
    }
}
