package com.droiddeck.launcher.core

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Retries with backoff (restored from the v0.5.4.x line, fork 8cf7ef306). A plain socket server answers
 * per path from a script, so a test can make a request fail, refuse or cut a body short. The sleeper only
 * records the waits.
 */
class DownloaderRetryTest {
    private lateinit var server: ServerSocket
    private lateinit var dir: File
    private val requests = CopyOnWriteArrayList<String>()
    private val sleeps = CopyOnWriteArrayList<Long>()
    private val hits = HashMap<String, AtomicInteger>()

    /** Path -> answer for the n-th request (0-based) to that path. */
    @Volatile private var route: (path: String, n: Int) -> Answer = { _, _ -> Answer.Status(404) }

    sealed class Answer {
        class Body(val bytes: ByteArray, val cutAt: Int = -1, val etag: String? = null) : Answer()
        class Status(val code: Int) : Answer()
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
        Downloader.policy = Downloader.Policy(4, 9, 1_000, 8_000)
        Downloader.sleeper = Downloader.Sleeper { sleeps.add(it) }
    }

    @After fun tearDown() {
        Downloader.policy = Downloader.DEFAULT_POLICY
        Downloader.sleeper = Downloader.Sleeper { Thread.sleep(it) }
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
        when (val a = route(path, n)) {
            is Answer.Status -> out.write("HTTP/1.1 ${a.code} X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            is Answer.Body -> {
                val tag = a.etag?.let { e -> "ETag: $e\r\n" } ?: ""
                val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
                if (from != null) {
                    val rest = a.bytes.size - from
                    out.write(("HTTP/1.1 206 Partial Content\r\n${tag}Content-Range: bytes $from-${a.bytes.size - 1}/${a.bytes.size}\r\n" +
                        "Content-Length: $rest\r\nConnection: close\r\n\r\n").toByteArray())
                    out.write(a.bytes, from, rest)
                } else {
                    out.write("HTTP/1.1 200 OK\r\n${tag}Content-Length: ${a.bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(a.bytes, 0, if (a.cutAt >= 0) a.cutAt else a.bytes.size)
                }
            }
        }
        out.flush()
    }

    @Test fun backoffDoublesWithJitterAndIsCapped() {
        val p = Downloader.Policy(10, 10, 1_000, 8_000)
        val r = Random(7)
        assertEquals(0, Downloader.backoffMs(p, 0, r))
        for ((round, full) in listOf(1 to 1_000L, 2 to 2_000L, 3 to 4_000L, 4 to 8_000L, 5 to 8_000L, 30 to 8_000L)) {
            repeat(50) {
                val d = Downloader.backoffMs(p, round, r)
                assertTrue("round $round: $d", d in full / 2..full)
            }
        }
        assertEquals(0, Downloader.backoffMs(Downloader.SINGLE_TRY, 3, r))
    }

    @Test fun onlyAnswersThatMayPassAreRetried() {
        for (code in listOf(408, 425, 429, 500, 502, 503, 504)) assertTrue("$code", Downloader.retryable(code))
        for (code in listOf(400, 401, 403, 404, 410, 416)) assertFalse("$code", Downloader.retryable(code))
        val p = Downloader.DEFAULT_POLICY
        assertTrue(p.rounds >= 3 && p.maxTries in 4..12)
    }

    @Test fun serverErrorsAreRetriedWithBackoff() {
        route = { _, n -> if (n < 2) Answer.Status(503) else Answer.Body("catalog".toByteArray()) }
        assertEquals("catalog", Downloader.downloadString("$base/catalog.json"))
        assertEquals(listOf("/catalog.json", "/catalog.json", "/catalog.json"), requests.toList())
        // Rounds 1 and 2: about 1 s and 2 s, full jitter.
        assertTrue("waited ${sleeps.sum()} ms", sleeps.sum() in 1_500L..3_000L)
    }

    @Test fun notFoundIsNotRetried() {
        route = { _, _ -> Answer.Status(404) }
        assertNull(Downloader.downloadString("$base/missing.json"))
        assertFalse(Downloader.downloadFile("$base/missing.bin", File(dir, "missing.bin"), false, null))
        assertEquals(listOf("/missing.json", "/missing.bin"), requests.toList())
        assertTrue(sleeps.isEmpty())
    }

    @Test fun aRefusalIsNotRetried() {
        route = { _, _ -> Answer.Status(403) }
        assertNull(Downloader.downloadString("$base/forbidden.json"))
        assertFalse(Downloader.downloadFile("$base/forbidden.bin", File(dir, "forbidden.bin"), false, null))
        assertEquals(listOf("/forbidden.json", "/forbidden.bin"), requests.toList())
        assertTrue(sleeps.isEmpty())
    }

    @Test fun everyRoundFailingGivesUp() {
        route = { _, _ -> Answer.Status(503) }
        assertFalse(Downloader.downloadFile("$base/down.bin", File(dir, "down.bin"), false, null))
        assertEquals(4, requests.size)
        assertNull(Downloader.downloadString("$base/down.json"))
        assertEquals(8, requests.size)
    }

    @Test fun aCutOffFileIsResumedFromTheSameUrlInOneCall() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        route = { _, n -> Answer.Body(data, cutAt = if (n == 0) 50_000 else -1, etag = "\"v1\"") }
        val target = File(dir, "runtime.tar.zst")
        assertTrue(Downloader.downloadFile("$base/runtime.tar.zst", target, false, null))
        assertArrayEquals(data, target.readBytes())
        assertEquals(2, requests.size)
        assertTrue(requests[1], requests[1].startsWith("/runtime.tar.zst bytes="))
        assertTrue(requests[1], requests[1].removePrefix("/runtime.tar.zst bytes=").substringBefore('-').toInt() > 0)
    }

    @Test fun aCancelDuringTheWaitStopsWithoutAnotherTry() {
        route = { _, _ -> Answer.Status(503) }
        var cancelled = false
        Downloader.sleeper = Downloader.Sleeper { sleeps.add(it); cancelled = true }
        assertNull(Downloader.downloadString("$base/slow.json") { cancelled })
        assertEquals(1, requests.size)
    }

    @Test fun theSingleTryPolicyTriesOnce() {
        Downloader.policy = Downloader.SINGLE_TRY
        route = { _, _ -> Answer.Status(503) }
        assertNull(Downloader.downloadString("$base/once.json"))
        assertEquals(1, requests.size)
        assertTrue(sleeps.isEmpty())
    }

    private fun collectAttempts(): MutableList<String> {
        val lines = CopyOnWriteArrayList<String>()
        Downloader.attemptSink = Downloader.AttemptSink { lines.add(com.droiddeck.launcher.runtime.RuntimeInstallLog.attemptLine("test", it, 4_000_000_000L)) }
        return lines
    }

    @Test fun everyTryOfAFailingDownloadLeavesALineWithTheHostAndTheStatus() {
        val lines = collectAttempts()
        try {
            route = { _, _ -> Answer.Status(503) }
            assertFalse(Downloader.downloadFile("$base/down.bin", File(dir, "down.bin"), false, null))
        } finally { Downloader.attemptSink = null }
        assertEquals(lines.toString(), 4, lines.size)
        for (l in lines) {
            assertTrue(l, l.contains("host 127.0.0.1") && l.contains("HTTP 503") && l.contains("free storage 4000 MB"))
        }
    }

    @Test fun aCutOffBodyReportsBytesReceivedOfExpected() {
        val lines = collectAttempts()
        try {
            val data = ByteArray(200_000) { 1 }
            route = { _, n -> Answer.Body(data, cutAt = if (n == 0) 50_000 else -1, etag = "\"v1\"") }
            assertTrue(Downloader.downloadFile("$base/r.bin", File(dir, "r.bin"), false, null))
        } finally { Downloader.attemptSink = null }
        assertTrue(lines.toString(), lines[0].contains("received 50000 of 200000 bytes") && lines[0].contains("body ended early"))
        assertTrue(lines.toString(), lines.last().contains("DONE"))
    }

    @Test fun anUnreachableHostReportsTheExceptionClass() {
        val lines = collectAttempts()
        try {
            val port = server.localPort
            server.close()
            Downloader.policy = Downloader.SINGLE_TRY
            assertFalse(Downloader.downloadFile("http://127.0.0.1:$port/x.bin", File(dir, "x.bin"), false, null))
        } finally { Downloader.attemptSink = null }
        assertEquals(lines.toString(), 1, lines.size)
        assertTrue(lines[0], lines[0].contains("error ConnectException") && lines[0].contains("HTTP none"))
    }
}
