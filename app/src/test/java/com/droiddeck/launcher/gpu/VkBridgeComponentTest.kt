package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.core.SessionPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

class VkBridgeComponentTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun anEarlierSessionsBridgeStoppingLateKeepsTheNewSessionsSocket() {
        val socket = tmp.newFile("server.sock")
        val old = VkBridgeComponent(socket, tmp.newFile("old.log"))
        val new = VkBridgeComponent(socket, tmp.newFile("new.log"))
        old.claim()
        new.claim()
        old.stop()
        assertTrue(socket.exists())
        new.stop()
        assertFalse(socket.exists())
    }

    @Test
    fun anEarlierSessionsBridgeStoppingWhileTheNextOneStartsNeverRemovesTheNewSocket() {
        val socket = File(tmp.root, "server.sock")
        repeat(2000) { round ->
            val old = VkBridgeComponent(socket, File(tmp.root, "old.log"))
            val new = VkBridgeComponent(socket, File(tmp.root, "new.log"))
            old.claimSocket()
            socket.createNewFile()
            val barrier = CyclicBarrier(2)
            val stopping = thread {
                barrier.await()
                old.stop()
            }
            barrier.await()
            new.claimSocket()
            socket.createNewFile()
            stopping.join()
            assertTrue("round $round", socket.exists())
            new.stop()
        }
    }

    @Test
    fun aBridgeStoppedWithNoNewerOneRemovesItsSocket() {
        val socket = tmp.newFile("server.sock")
        val bridge = VkBridgeComponent(socket, tmp.newFile("bridge.log"))
        bridge.claim()
        bridge.stop()
        assertFalse(socket.exists())
        socket.createNewFile()
        bridge.stop()
        assertTrue(socket.exists())
    }

    @Test
    fun theBridgeStopsAfterEveryOtherPartInReverseStartOrder() {
        class Part : SessionPart() {
            override fun start() {}
            override fun stop() {}
        }
        val bridge = VkBridgeComponent(tmp.newFile("server.sock"), tmp.newFile("bridge.log"))
        val clocks = Part()
        val audio = Part()
        val (first, last) = VkBridgeComponent.stopOrder(listOf(bridge, clocks, audio))
        assertEquals(listOf(audio, clocks), first)
        assertEquals(listOf(bridge), last)
        assertEquals(Pair(listOf(audio), emptyList<SessionPart>()), VkBridgeComponent.stopOrder(listOf(audio)))
    }

    @Test
    fun onlyASigkillTheAppDidNotSendCountsAsTheSystemKillingTheServer() {
        assertTrue(VkBridgeComponent.isKilledBySystem(137, stopping = false))
        assertFalse(VkBridgeComponent.isKilledBySystem(137, stopping = true))
        assertFalse(VkBridgeComponent.isKilledBySystem(139, stopping = false))
        assertFalse(VkBridgeComponent.isKilledBySystem(134, stopping = false))
        assertFalse(VkBridgeComponent.isKilledBySystem(0, stopping = false))
        assertFalse(VkBridgeComponent.isKilledBySystem(-1, stopping = false))
    }

    @Test
    fun theServersLastMemoryLineMarkedLowMeansMemoryWasLow() {
        // the XRing O3's last minute before Android killed the server, in v0.5.4's format
        val log = sequenceOf(
            "00:54:07.840 vkbridge-server I mem: rss 3129M (anon 95M file 3033M shmem 0M) heap 848M fds 176 | free 1500M swap 6100M | live 2817M/228:",
            "00:54:12.841 vkbridge-server W memory low: 1279 MB free of 11377 MB (swap free 6079 MB); the server holds 3129 MB",
            "00:54:12.841 vkbridge-server I mem: rss 3129M (anon 95M file 3033M shmem 0M) heap 848M fds 176 | free 1279M swap 6079M (low) | live 2817M/228:",
            "== vkbridge server exited with status 137",
        )
        assertTrue(VkBridgeComponent.lowMemoryAtEnd(log))
    }

    @Test
    fun memoryThatCameBackOrWasNeverLowIsNotLow() {
        val back = sequenceOf(
            "10:00:00.000 vkbridge-server W memory low: 900 MB free of 8000 MB (swap free 100 MB); the server holds 2000 MB",
            "10:00:30.000 vkbridge-server I memory: 3000 MB free of 8000 MB again; idle descriptor pools are kept again",
        )
        assertFalse(VkBridgeComponent.lowMemoryAtEnd(back))
        val laterLine = sequenceOf(
            "10:00:00.000 vkbridge-server I mem: rss 900M (anon 95M file 800M shmem 0M) heap 300M fds 10 | free 700M swap 100M (low) | live 1M/1:",
            "10:00:05.000 vkbridge-server I mem: rss 900M (anon 95M file 800M shmem 0M) heap 300M fds 10 | free 4000M swap 100M | live 1M/1:",
        )
        assertFalse(VkBridgeComponent.lowMemoryAtEnd(laterLine))
        assertFalse(VkBridgeComponent.lowMemoryAtEnd(sequenceOf("== vkbridge server exited with status 137")))
        assertFalse(VkBridgeComponent.lowMemoryAtEnd(emptySequence()))
    }

    @Test
    fun noSessionsServerKilledMeansNoReason() {
        assertEquals(null, VkBridgeComponent.killedDuringSession(waitMs = 0L))
    }
}
