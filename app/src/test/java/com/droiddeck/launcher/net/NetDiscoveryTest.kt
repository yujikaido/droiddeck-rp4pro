package com.droiddeck.launcher.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class NetDiscoveryTest {
    /** A node status reply: header, the 34-byte name, type/class/ttl/length, then 18-byte name entries. */
    private fun nodeStatus(vararg names: Triple<String, Int, Boolean>): ByteArray {
        val b = ByteArray(57 + names.size * 18)
        b[56] = names.size.toByte()
        names.forEachIndexed { i, (name, suffix, group) ->
            val at = 57 + i * 18
            name.padEnd(15).forEachIndexed { j, c -> b[at + j] = c.code.toByte() }
            b[at + 15] = suffix.toByte()
            if (group) b[at + 16] = 0x80.toByte()
        }
        return b
    }

    @Test fun fileServerNameWins() {
        val b = nodeStatus(Triple("WORKGROUP", 0x00, true), Triple("DESKTOP-PC", 0x00, false), Triple("DESKTOP-PC", 0x20, false))
        assertEquals("DESKTOP-PC", NetDiscovery.parseNodeStatus(b, b.size))
    }

    @Test fun groupOnlyIsNoName() {
        val b = nodeStatus(Triple("WORKGROUP", 0x00, true))
        assertNull(NetDiscovery.parseNodeStatus(b, b.size))
        assertNull(NetDiscovery.parseNodeStatus(ByteArray(20), 20))
    }

    /** NetrShareEnum level 1 reply: two folder shares, IPC$, a printer and a hidden C$ (only the folders are kept). */
    @Test fun shareListKeepsFolders() {
        val out = java.io.ByteArrayOutputStream()
        fun u32(v: Int) { for (i in 0 until 4) out.write(v ushr (8 * i) and 0xff) }
        fun str(s: String) {
            val z = "$s\u0000"
            u32(z.length); u32(0); u32(z.length)
            out.write(z.toByteArray(Charsets.UTF_16LE))
            while (out.size() % 4 != 0) out.write(0)
        }
        val shares = listOf("edrive" to 0, "IPC$" to (3 or 0x80000000.toInt()), "Laser" to 1, "C$" to 0x80000000.toInt(), "Games" to 0)
        u32(1); u32(1); u32(0x20000) // level, arm, container
        u32(shares.size); u32(0x20004); u32(shares.size) // count, array pointer, max count
        shares.forEach { (_, type) -> u32(0x20008); u32(type); u32(0x2000c) }
        shares.forEach { (name, _) -> str(name); str("remark") }
        u32(shares.size); u32(0); u32(0) // total, resume, status
        assertEquals(listOf("edrive", "Games"), ShareEnum.parse(out.toByteArray()))
    }

    /** Against a real server, when DD_SMB_TEST=host:port,user,password is set (an impacket smbserver works). */
    @Test fun listsRealServer() {
        val spec = System.getenv("DD_SMB_TEST")
        assumeTrue(spec != null)
        val (host, user, pw) = spec!!.split(',')
        val got = SmbLink.shares(NetShare("t", "", host, "", user, ""), pw)
        println("shares on $host: $got")
        assertEquals(System.getenv("DD_SMB_EXPECT")?.split(',') ?: got, got)
    }

    /** NetBIOS names of real machines, when DD_NBNS_TEST=address,address is set. */
    @Test fun namesRealServers() {
        val spec = System.getenv("DD_NBNS_TEST")
        assumeTrue(spec != null)
        spec!!.split(',').forEach { println("name of $it: ${NetDiscovery.netbiosName(it)}") }
    }
}
