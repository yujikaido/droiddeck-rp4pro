package com.droiddeck.launcher.frontend

import java.io.File
import java.io.RandomAccessFile

/**
 * A PSP ISO's own title and disc id, from PSP_GAME/PARAM.SFO on its ISO 9660 file system: what names a game whose file
 * is called "b-ys7uu". Plain .iso only (a CSO is compressed); null when the disc does not have one.
 */
object PspIso {
    class Sfo(val title: String?, val discId: String?)

    private const val SECTOR = 2048L

    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
        ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    /** A directory's entry by name: (extent sector, size), or null. */
    private fun find(f: RandomAccessFile, dirSector: Int, dirSize: Int, name: String): Pair<Int, Int>? {
        val buf = ByteArray(dirSize.coerceAtMost(64 * 1024))
        f.seek(dirSector * SECTOR); f.readFully(buf)
        var o = 0
        while (o < buf.size) {
            val len = buf[o].toInt() and 0xff
            if (len == 0) { o = ((o / SECTOR.toInt()) + 1) * SECTOR.toInt(); continue }
            if (o + 33 > buf.size) break
            val nameLen = buf[o + 32].toInt() and 0xff
            if (o + 33 + nameLen > buf.size) break
            val n = String(buf, o + 33, nameLen, Charsets.US_ASCII).substringBefore(';')
            if (n.equals(name, ignoreCase = true)) return le32(buf, o + 2) to le32(buf, o + 10)
            o += len
        }
        return null
    }

    fun read(file: File): Sfo? = runCatching {
        if (file.extension.lowercase() != "iso") return null
        RandomAccessFile(file, "r").use { f ->
            val pvd = ByteArray(SECTOR.toInt())
            f.seek(16 * SECTOR); f.readFully(pvd)
            if (String(pvd, 1, 5, Charsets.US_ASCII) != "CD001") return null
            val rootSector = le32(pvd, 156 + 2)
            val rootSize = le32(pvd, 156 + 10)
            val game = find(f, rootSector, rootSize, "PSP_GAME") ?: return null
            val sfoAt = find(f, game.first, game.second, "PARAM.SFO") ?: return null
            val sfo = ByteArray(sfoAt.second.coerceIn(0, 64 * 1024))
            f.seek(sfoAt.first * SECTOR); f.readFully(sfo)
            if (sfo.size < 20 || sfo[0].toInt() != 0 || sfo[1] != 'P'.code.toByte() || sfo[2] != 'S'.code.toByte() || sfo[3] != 'F'.code.toByte()) return null
            val keys = le32(sfo, 8)
            val data = le32(sfo, 12)
            val count = le32(sfo, 16)
            val values = HashMap<String, String>()
            for (i in 0 until count.coerceAtMost(64)) {
                val e = 20 + i * 16
                if (e + 16 > sfo.size) break
                val keyAt = keys + le16(sfo, e)
                val fmt = le16(sfo, e + 2)
                val len = le32(sfo, e + 4)
                val at = data + le32(sfo, e + 12)
                if (keyAt >= sfo.size || at + len > sfo.size || fmt != 0x0204) continue
                var k = keyAt
                while (k < sfo.size && sfo[k].toInt() != 0) k++
                val key = String(sfo, keyAt, k - keyAt, Charsets.US_ASCII)
                values[key] = String(sfo, at, len, Charsets.UTF_8).trimEnd('\u0000').trim()
            }
            Sfo(values["TITLE"]?.takeIf { it.isNotBlank() }, values["DISC_ID"]?.takeIf { it.isNotBlank() })
        }
    }.getOrNull()
}
