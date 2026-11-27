package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * Cartridge games named by their contents: a ROM's CRC32 looked up in libretro-database's No-Intro list for its
 * platform (metadat/no-intro/<system>.dat, cached in files/retro-crc/<platform>.tsv), so "STARFOX.SMC" is
 * "Star Fox (USA)" and gets that game's cover and details. The CRC is tried in each form a dump takes: as is,
 * without a copier header (SNES 512 bytes, NES "NES\x1A" 16 bytes, SMD 512 bytes), SMD de-interleaved, N64 in
 * big-endian order. CRCs are kept by path, size and date (files/retro-crc/files.tsv).
 */
object NoIntro {
    private const val TAG = "NoIntro"
    private const val RAW = "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/no-intro"
    private const val MAX_SIZE = 64L shl 20
    private const val TTL_MS = 60L * 24 * 3600 * 1000

    /** Platforms whose games are cartridge dumps No-Intro lists. */
    val platforms = setOf("nes", "fds", "snes", "gb", "gbc", "gba", "genesis", "32x", "sms", "gg", "n64", "nds", "pce",
        "ngp", "ngpc", "ws", "wsc", "lynx", "atari2600", "atari7800", "vb", "pokemini", "sg1000", "coleco")

    private fun dir(context: Context) = File(context.filesDir, "retro-crc")
    private fun cache(context: Context, platform: String) = File(dir(context), "$platform.tsv")

    fun fresh(context: Context, platform: String): Boolean =
        cache(context, platform).let { it.isFile && System.currentTimeMillis() - it.lastModified() < TTL_MS }

    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 120_000
        c.setRequestProperty("User-Agent", "DroidDeck")
        if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) {
        Log.w(TAG, "$url: $e"); null
    }

    /** Downloads a platform's No-Intro list and caches crc -> name; false when it could not. */
    fun fetch(context: Context, platform: Platforms.Platform): Boolean {
        if (platform.id !in platforms) return false
        val name = platform.libretroName ?: return false
        val text = get("$RAW/" + URLEncoder.encode("$name.dat", "UTF-8").replace("+", "%20")) ?: return false
        val rom = Regex("rom \\( name \"([^\"]+)\" size \\d+ crc ([0-9A-Fa-f]{8})")
        val out = StringBuilder()
        var n = 0
        for (m in rom.findAll(text)) {
            out.append(m.groupValues[2].uppercase()).append('\t').append(m.groupValues[1].substringBeforeLast('.')).append('\n'); n++
        }
        if (n == 0) return false
        dir(context).mkdirs()
        cache(context, platform.id).writeText(out.toString())
        lists.remove(platform.id)
        Log.i(TAG, "${platform.id}: $n dumps")
        return true
    }

    private val lists = ConcurrentHashMap<String, Map<String, String>>()

    private fun list(context: Context, platform: String): Map<String, String> = lists.getOrPut(platform) {
        val f = cache(context, platform)
        if (!f.isFile) emptyMap() else runCatching {
            f.readLines().mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        }.getOrDefault(emptyMap())
    }

    @Volatile private var known: ConcurrentHashMap<String, List<String>>? = null
    @Volatile private var dirty = false

    private fun fileKey(f: File) = "${f.path}|${f.length()}|${f.lastModified()}"

    private fun crcCache(context: Context): ConcurrentHashMap<String, List<String>> = known ?: synchronized(this) {
        known ?: ConcurrentHashMap<String, List<String>>().also { map ->
            runCatching {
                File(dir(context), "files.tsv").takeIf { it.isFile }?.readLines()?.forEach { l ->
                    val p = l.split('\t')
                    if (p.size >= 2) map[p[0]] = p.drop(1).filter { it.isNotBlank() }
                }
            }
            known = map
        }
    }

    /** Writes the CRCs worked out since the last save (after a scan). */
    fun save(context: Context) {
        if (!dirty) return
        val map = crcCache(context)
        dir(context).mkdirs()
        runCatching { File(dir(context), "files.tsv").writeText(map.entries.joinToString("\n") { (k, v) -> (listOf(k) + v).joinToString("\t") }) }
        dirty = false
    }

    private fun hex(c: CRC32) = "%08X".format(c.value)

    private fun crcOf(b: ByteArray, from: Int = 0, to: Int = b.size) = CRC32().apply { update(b, from, to - from) }.let { hex(it) }

    /** The CRCs a dump can match: as is and in its other forms. */
    private fun candidates(file: File): List<String> {
        val ext = file.extension.lowercase()
        if (ext == "zip") return runCatching {
            ZipFile(file).use { z -> z.entries().toList().filter { !it.isDirectory }.maxByOrNull { it.size }?.crc?.takeIf { it >= 0 }?.let { listOf("%08X".format(it)) } }
        }.getOrNull().orEmpty()
        if (ext in setOf("7z", "rar", "chd", "iso", "cso", "bin", "cue")) return emptyList()
        if (file.length() > MAX_SIZE || file.length() <= 0) return emptyList()
        val b = file.readBytes()
        val out = LinkedHashSet<String>()
        out.add(crcOf(b))
        if (b.size % 1024 == 512 && b.size > 512) out.add(crcOf(b, 512))
        if (b.size > 16 && b[0] == 'N'.code.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'S'.code.toByte() && b[3].toInt() == 0x1A) out.add(crcOf(b, 16))
        if (ext == "smd" || ext == "md") smdToBin(b)?.let { out.add(crcOf(it)) }
        if (ext in setOf("n64", "v64", "z64") || (b.size > 4 && (b[0].toInt() and 0xff) in setOf(0x37, 0x40))) n64ToZ64(b)?.let { out.add(crcOf(it)) }
        return out.toList()
    }

    /** SMD: a 512-byte header, then 16 KB blocks holding the odd bytes first and the even bytes second. */
    private fun smdToBin(b: ByteArray): ByteArray? {
        if (b.size <= 512 || (b.size - 512) % 16384 != 0) return null
        val out = ByteArray(b.size - 512)
        var blk = 0
        while (blk < out.size) {
            val src = 512 + blk
            for (i in 0 until 8192) {
                out[blk + i * 2 + 1] = b[src + i]
                out[blk + i * 2] = b[src + 8192 + i]
            }
            blk += 16384
        }
        return out
    }

    /** N64 in big-endian (.z64) order from byte-swapped (.v64) or little-endian (.n64) dumps. */
    private fun n64ToZ64(b: ByteArray): ByteArray? {
        if (b.size < 4 || b.size % 4 != 0) return null
        val first = b[0].toInt() and 0xff
        val out = b.copyOf()
        when (first) {
            0x37 -> for (i in out.indices step 2) { out[i] = b[i + 1]; out[i + 1] = b[i] }
            0x40 -> for (i in out.indices step 4) { out[i] = b[i + 3]; out[i + 1] = b[i + 2]; out[i + 2] = b[i + 1]; out[i + 3] = b[i] }
            else -> return null
        }
        return out
    }

    /** The No-Intro name of a cartridge dump ("Star Fox (USA)"), or null when its platform's list is missing or it is not on it. */
    fun name(context: Context, platform: String, file: File): String? {
        if (platform !in platforms) return null
        val list = list(context, platform)
        if (list.isEmpty()) return null
        val cache = crcCache(context)
        val key = fileKey(file)
        val crcs = cache[key] ?: runCatching { candidates(file) }.getOrDefault(emptyList()).also { cache[key] = it; dirty = true }
        return crcs.firstNotNullOfOrNull { list[it] }
    }
}
