package com.droiddeck.launcher.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.PipeShare
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet

/**
 * Lists a server's shares the way Windows' network folder does: DCE/RPC over the IPC$ srvsvc pipe, one bind and one
 * NetrShareEnum (level 1). Just enough of [MS-RPCE]/[MS-SRVS] for that call, so smbj needs no RPC library beside it.
 */
internal object ShareEnum {
    private const val BIND = 11
    private const val BIND_ACK = 12
    private const val REQUEST = 0
    private const val RESPONSE = 2
    private const val LAST_FRAG = 0x02
    private const val NETR_SHARE_ENUM = 15
    private const val STYPE_DISKTREE = 0
    private const val STYPE_SPECIAL = 0x80000000.toInt()

    fun list(session: Session, host: String): List<String> {
        val ipc = session.connectShare("IPC$") as PipeShare
        try {
            val pipe = ipc.open("srvsvc", SMB2ImpersonationLevel.Impersonation, EnumSet.of(AccessMask.MAXIMUM_ALLOWED), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
            try {
                val ack = pipe.transact(bind())
                if (ack.size < 16 || ack[2].toInt() != BIND_ACK) throw IllegalStateException("The server refused the share list (bind ${ack.getOrNull(2)})")
                var pdu = pipe.transact(request(host))
                val stub = ByteArrayOutputStream()
                while (true) {
                    val type = pdu[2].toInt()
                    if (type != RESPONSE) throw IllegalStateException("The server refused the share list (${faultText(pdu)})")
                    val fragLength = le16(pdu, 8)
                    stub.write(pdu, 24, fragLength - 24 - le16(pdu, 10))
                    if (pdu[3].toInt() and LAST_FRAG != 0) break
                    val buf = ByteArray(64 * 1024)
                    val n = pipe.read(buf)
                    pdu = buf.copyOf(n)
                }
                return parse(stub.toByteArray())
            } finally {
                runCatching { pipe.close() }
            }
        } finally {
            runCatching { ipc.close() }
        }
    }

    /** Folder shares only: no printers, no IPC$, none marked special or hidden with a $. */
    internal fun parse(stub: ByteArray): List<String> {
        val r = Ndr(stub)
        r.u32() // level
        r.u32() // union arm
        if (r.u32() == 0) return emptyList() // container pointer
        val count = r.u32()
        if (r.u32() == 0) return emptyList() // array pointer
        val max = r.u32()
        require(max in 0..10_000 && count <= max) { "bad share count $count/$max" }
        val entries = (0 until count).map { Triple(r.u32(), r.u32(), r.u32()) } // name ptr, type, remark ptr
        val names = entries.map { (namePtr, _, remarkPtr) ->
            val name = if (namePtr != 0) r.string() else ""
            if (remarkPtr != 0) r.string()
            name
        }
        return names.zip(entries).filter { (name, e) ->
            val type = e.second
            type and 0x0fffffff == STYPE_DISKTREE && type and STYPE_SPECIAL == 0 && name.isNotEmpty() && !name.endsWith("$")
        }.map { it.first }.sortedBy { it.lowercase() }
    }

    private fun bind(): ByteArray = pdu(BIND, 1) {
        it.putShort(4280).putShort(4280).putInt(0) // max xmit/recv frag, new association
        it.put(1).put(0).putShort(0) // one context
        it.putShort(0).put(1).put(0) // context 0, one transfer syntax
        uuid(it, "4b324fc8-1670-01d3-1278-5a47bf6ee188"); it.putShort(3).putShort(0) // srvsvc 3.0
        uuid(it, "8a885d04-1ceb-11c9-9fe8-08002b104860"); it.putInt(2) // NDR 2.0
    }

    private fun request(host: String): ByteArray {
        val stub = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        stub.putInt(0x00020000) // ServerName: unique pointer
        val server = "\\\\$host\u0000"
        stub.putInt(server.length).putInt(0).putInt(server.length)
        server.forEach { stub.putChar(it) }
        while (stub.position() % 4 != 0) stub.put(0)
        stub.putInt(1).putInt(1).putInt(0x00020004) // InfoStruct: level 1, arm 1, container pointer
        stub.putInt(0).putInt(0) // container: no entries, no buffer
        stub.putInt(-1) // PreferedMaximumLength: all of them
        stub.putInt(0x00020008).putInt(0) // ResumeHandle: start at 0
        val body = stub.array().copyOf(stub.position())
        return pdu(REQUEST, 2) {
            it.putInt(body.size).putShort(0).putShort(NETR_SHARE_ENUM.toShort()) // alloc hint, context 0, opnum
            it.put(body)
        }
    }

    /** A connection-oriented PDU: 16-byte header (v5.0, first+last fragment, little-endian NDR) then [body]. */
    private fun pdu(type: Int, callId: Int, body: (ByteBuffer) -> Unit): ByteArray {
        val b = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)
        b.put(5).put(0).put(type.toByte()).put(0x03).putInt(0x10).putShort(0).putShort(0).putInt(callId)
        body(b)
        val len = b.position()
        b.putShort(8, len.toShort())
        return b.array().copyOf(len)
    }

    private fun uuid(b: ByteBuffer, text: String) {
        val hex = text.replace("-", "")
        b.putInt(hex.substring(0, 8).toLong(16).toInt()).putShort(hex.substring(8, 12).toInt(16).toShort()).putShort(hex.substring(12, 16).toInt(16).toShort())
        for (i in 16 until 32 step 2) b.put(hex.substring(i, i + 2).toInt(16).toByte())
    }

    private fun faultText(pdu: ByteArray): String =
        if (pdu.size >= 28 && pdu[2].toInt() == 3) "fault 0x%08x".format(le32(pdu, 24)) else "reply ${pdu.getOrNull(2)}"

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or (b[at + 1].toInt() and 0xff shl 8)
    private fun le32(b: ByteArray, at: Int) = le16(b, at) or (le16(b, at + 2) shl 16)

    /** Reads NDR: aligned little-endian u32s and conformant varying UTF-16 strings. */
    private class Ndr(val b: ByteArray) {
        var at = 0
        fun u32(): Int { align(); require(at + 4 <= b.size) { "short share list" }; return le32(b, at).also { at += 4 } }
        fun string(): String {
            u32() // max count
            u32() // offset
            val actual = u32()
            require(actual in 0..4096 && at + actual * 2 <= b.size) { "bad share name" }
            val s = String(b, at, actual * 2, Charsets.UTF_16LE).trimEnd('\u0000')
            at += actual * 2
            return s
        }
        private fun align() { at = (at + 3) and 3.inv() }
    }
}
