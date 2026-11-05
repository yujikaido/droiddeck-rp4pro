package com.droiddeck.launcher.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** A computer on the local network that answers on the SMB port. [name] is what it calls itself, or null when it doesn't say. */
data class NetServer(val address: String, val name: String?) {
    val label: String get() = name?.let { "$it · $address" } ?: address
}

/**
 * Finds SMB servers on the network the device is on, for Add share: Bonjour (_smb._tcp, which Macs, TrueNAS and most NAS
 * boxes announce) plus a quick knock on port 445 across the local /24, which also finds Windows PCs that announce nothing
 * Android can hear. Names come from Bonjour, then NetBIOS, then the router's DNS.
 */
object NetDiscovery {
    private const val SMB_PORT = 445
    private const val KNOCK_MS = 350
    private const val BONJOUR_MS = 3_500L

    /** Scans for a few seconds, calling [onUpdate] (from a background thread) with the whole list, sorted, each time it grows. */
    suspend fun scan(ctx: Context, onUpdate: (List<NetServer>) -> Unit) {
        val found = linkedMapOf<String, NetServer>()
        val lock = Any()
        fun add(address: String, name: String?) {
            val list = synchronized(lock) {
                val old = found[address]
                if (old != null && (old.name != null || name == null)) return
                found[address] = NetServer(address, name ?: old?.name)
                found.values.sortedWith(compareBy({ it.name == null }, { it.name?.lowercase() }, { ipOrder(it.address) }))
            }
            onUpdate(list)
        }
        val pool = Executors.newFixedThreadPool(64).asCoroutineDispatcher()
        try {
            withContext(pool) {
                coroutineScope {
                    launch { bonjour(ctx) { address, name -> add(address, name) } }
                    sweep(ctx) { address ->
                        add(address, null)
                        launch { (netbiosName(address) ?: dnsName(address))?.let { add(address, it) } }
                    }
                }
            }
        } finally {
            pool.close()
        }
    }

    /** Knocks on port 445 of every other address in the device's /24 (or smaller) network. */
    private suspend fun sweep(ctx: Context, onOpen: CoroutineScope.(String) -> Unit) = coroutineScope {
        val (own, prefix) = localAddress(ctx) ?: return@coroutineScope
        val bits = ByteArray(4).also { System.arraycopy(own.address, 0, it, 0, 4) }
        val base = bits.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        val size = 1L shl (32 - prefix.coerceIn(24, 30))
        val first = base and (size - 1).inv()
        for (n in first + 1 until first + size - 1) {
            if (n == base) continue
            val address = "${n shr 24 and 0xff}.${n shr 16 and 0xff}.${n shr 8 and 0xff}.${n and 0xff}"
            launch {
                val open = runCatching { Socket().use { it.connect(InetSocketAddress(address, SMB_PORT), KNOCK_MS) } }.isSuccess
                if (open) onOpen(address)
            }
        }
    }

    /** The device's IPv4 address and prefix on the network it's using, or null with none (no Wi-Fi). */
    private fun localAddress(ctx: Context): Pair<Inet4Address, Int>? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val link = cm.getLinkProperties(cm.activeNetwork ?: return null) ?: return null
        return link.linkAddresses.firstNotNullOfOrNull { la ->
            (la.address as? Inet4Address)?.takeIf { it.isSiteLocalAddress }?.let { it to la.prefixLength }
        }
    }

    /** Listens for _smb._tcp announcements for a few seconds, resolving each one (Android resolves one at a time). */
    private suspend fun bonjour(ctx: Context, onFound: (String, String) -> Unit) {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return
        val queue = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { queue.add(info) }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        if (runCatching { nsd.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }.isFailure) return
        try {
            val end = System.currentTimeMillis() + BONJOUR_MS
            while (System.currentTimeMillis() < end) {
                val info = queue.poll() ?: run { delay(100); null } ?: continue
                resolve(nsd, info)?.let { onFound(it, info.serviceName) }
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION") // resolveService: its replacement needs API 34
    private fun resolve(nsd: NsdManager, info: NsdServiceInfo): String? {
        val done = java.util.concurrent.ArrayBlockingQueue<Result<NsdServiceInfo>>(1)
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) { done.offer(Result.success(resolved)) }
                override fun onResolveFailed(failed: NsdServiceInfo, code: Int) { done.offer(Result.failure(IllegalStateException("resolve $code"))) }
            })
        }.onFailure { return null }
        val host = done.poll(2, java.util.concurrent.TimeUnit.SECONDS)?.getOrNull()?.host
        return (host as? Inet4Address)?.hostAddress
    }

    /** The server's NetBIOS name (a node status query to UDP 137), which Windows and Samba answer. */
    internal fun netbiosName(address: String, timeoutMs: Int = 800): String? = runCatching {
        DatagramSocket().use { sock ->
            sock.soTimeout = timeoutMs
            sock.send(DatagramPacket(NODE_STATUS, NODE_STATUS.size, InetAddress.getByName(address), 137))
            val buf = ByteArray(1024)
            val reply = DatagramPacket(buf, buf.size)
            sock.receive(reply)
            parseNodeStatus(buf, reply.length)
        }
    }.getOrNull()

    /** NBSTAT for "*": transaction id, no flags, one question, the encoded name, type NBSTAT (0x21), class IN. */
    private val NODE_STATUS: ByteArray = ByteArray(50).also { b ->
        b[0] = 0x44; b[1] = 0x44
        b[5] = 1
        b[12] = 0x20
        val name = ByteArray(16).also { it[0] = '*'.code.toByte() }
        name.forEachIndexed { i, c ->
            b[13 + 2 * i] = ('A'.code + (c.toInt() shr 4 and 0xf)).toByte()
            b[14 + 2 * i] = ('A'.code + (c.toInt() and 0xf)).toByte()
        }
        b[47] = 0x21
        b[49] = 1
    }

    /** The first unique name in a node status reply: the file server name (suffix 0x20), else the workstation name. */
    internal fun parseNodeStatus(buf: ByteArray, len: Int): String? {
        val count = buf.getOrNull(56)?.toInt()?.and(0xff) ?: return null
        val names = (0 until count).mapNotNull { i ->
            val at = 57 + i * 18
            if (at + 18 > len) return@mapNotNull null
            val group = buf[at + 16].toInt() and 0x80 != 0
            if (group) null else String(buf, at, 15, Charsets.US_ASCII).trim() to (buf[at + 15].toInt() and 0xff)
        }
        return (names.firstOrNull { it.second == 0x20 } ?: names.firstOrNull { it.second == 0x00 })?.first?.takeIf { it.isNotEmpty() }
    }

    /** The name the router's DNS has for it, without the domain, or null when it only knows the number. */
    private fun dnsName(address: String): String? = runCatching {
        InetAddress.getByName(address).canonicalHostName.takeIf { it != address }?.substringBefore('.')?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun ipOrder(address: String): Long = address.split('.').fold(0L) { acc, p -> (acc shl 8) or (p.toLongOrNull() ?: 0L) }
}
