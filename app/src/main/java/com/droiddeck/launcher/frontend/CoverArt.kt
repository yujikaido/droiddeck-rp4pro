package com.droiddeck.launcher.frontend

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.droiddeck.launcher.core.DownloadSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object CoverArt {
    private const val TAG = "CoverArt"
    private const val MISS_RETRY_MS = 3L * 24 * 3600 * 1000
    private const val INDEX_TTL_MS = 14L * 24 * 3600 * 1000
    private const val MAX_PER_PASS = 40

    private val collections = mapOf(
        "rpcs3" to listOf("Sony_-_PlayStation_3"),
        "armsx2" to listOf("Sony_-_PlayStation_2"),
        "duckstation" to listOf("Sony_-_PlayStation"),
        "ppsspp" to listOf("Sony_-_PlayStation_Portable"),
        "dolphin" to listOf("Nintendo_-_GameCube", "Nintendo_-_Wii"),
        "melonds" to listOf("Nintendo_-_Nintendo_DS"),
        "cemu" to listOf("Nintendo_-_Wii_U"),
    )

    /** The libretro-thumbnails collections for an emulator id: the Linux emulators' own, a Retro platform's from Daijishō. */
    private fun collectionsOf(context: Context, emulatorId: String): List<String>? =
        collections[emulatorId] ?: RetroLibrary.collectionsFor(context, emulatorId)

    private fun root(context: Context) = File(context.filesDir, "covers")

    private fun keyOf(rom: Library.Rom): String =
        rom.hostPath.name.replace(Regex("[^A-Za-z0-9._() -]"), "_").take(150)

    private fun coverFile(context: Context, rom: Library.Rom) = File(root(context), "${rom.emulatorId}/${keyOf(rom)}.img")
    private fun missFile(context: Context, rom: Library.Rom) = File(root(context), "${rom.emulatorId}/${keyOf(rom)}.miss")

    fun cached(context: Context, rom: Library.Rom): File? = coverFile(context, rom).takeIf { it.isFile && it.length() > 0 }

    private fun snapFile(context: Context, rom: Library.Rom) = File(root(context), "${rom.emulatorId}/${keyOf(rom)}.snap")

    /** The game's screenshot (libretro Named_Snaps), fetched with its cover; the list view's backdrop. */
    fun snap(context: Context, rom: Library.Rom): File? = snapFile(context, rom).takeIf { it.isFile && it.length() > 0 }

    private fun wanted(context: Context, rom: Library.Rom): Boolean {
        if (rom.art != null || cached(context, rom) != null || collectionsOf(context, rom.emulatorId) == null) return false
        val miss = missFile(context, rom)
        return !(miss.isFile && System.currentTimeMillis() - miss.lastModified() < MISS_RETRY_MS)
    }

    fun fetchMissing(context: Context, roms: List<Library.Rom>): Boolean {
        var found = false
        var tries = 0
        for (rom in roms) {
            if (!wanted(context, rom)) continue
            if (++tries > MAX_PER_PASS) break
            val target = coverFile(context, rom)
            if (rom.emulatorId == "rpcs3" && try { copyPs3Icon(rom.hostPath, target) } catch (e: Exception) { false }) {
                Log.i(TAG, "${rom.name}: ICON0.PNG from the disc")
                missFile(context, rom).delete()
                found = true
                continue
            }
            val url = try { findUrl(context, rom) } catch (e: Exception) { Log.w(TAG, "${rom.name}: $e"); null }
            if (url != null && download(url, target)) {
                Log.i(TAG, "${rom.name}: $url")
                if ("/Named_Boxarts/" in url) download(url.replace("/Named_Boxarts/", "/Named_Snaps/"), snapFile(context, rom))
                missFile(context, rom).delete()
                found = true
            } else {
                runCatching { missFile(context, rom).apply { parentFile?.mkdirs(); writeText(url ?: "") } }
                    .onFailure { Log.w(TAG, "${rom.name}: miss marker not written: $it") }
            }
        }
        return found
    }

    private fun findUrl(context: Context, rom: Library.Rom): String? {
        val fileName = rom.hostPath.nameWithoutExtension.removeSuffix(".dec")
        // the file's name, its No-Intro name (libretro names its thumbnails so), then the name shown (a PSP disc's own title, an arcade set's)
        val dump = rom.emulatorId.takeIf { it.startsWith(RetroLibrary.ID_PREFIX) }?.let { NoIntro.name(context, it.removePrefix(RetroLibrary.ID_PREFIX), rom.hostPath) }
        for (name in listOfNotNull(fileName, dump, rom.name).distinct()) for (collection in collectionsOf(context, rom.emulatorId).orEmpty()) {
            val exact = thumbnailUrl(collection, name)
            if (exists(exact)) return exact
            nearest(name, names(context, collection))?.let { return thumbnailUrl(collection, it) }
        }
        return when (rom.emulatorId) {
            "armsx2", "android:ps2" -> ps2Serial(rom.hostPath)?.let { "https://raw.githubusercontent.com/xlenore/ps2-covers/main/covers/default/$it.jpg" }
            "dolphin", "android:gc", "android:wii" -> nintendoDiscId(rom.hostPath)?.let { id ->
                val region = when (id.getOrNull(3)) { 'P' -> "EN"; 'J' -> "JA"; 'K' -> "KO"; else -> "US" }
                "https://art.gametdb.com/wii/cover/$region/$id.png"
            }
            else -> null
        }?.takeIf { exists(it) }
    }

    private fun thumbName(title: String) = title.replace(Regex("[&*/:`<>?\\\\|\"]"), "_")

    private fun thumbnailUrl(collection: String, title: String) =
        "https://raw.githubusercontent.com/libretro-thumbnails/$collection/master/Named_Boxarts/" +
            URLEncoder.encode("${thumbName(title)}.png", "UTF-8").replace("+", "%20")


    private fun tags(name: String): List<String> = Regex("[(\\[]([^)\\]]*)[)\\]]").findAll(name).map { it.groupValues[1].lowercase() }.toList()

    private fun base(name: String): String = name
        .replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), " ")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .split(' ').filter { it.isNotEmpty() && it != "the" && it != "a" }
        .joinToString(" ")

    private fun nearest(name: String, candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        val want = base(name)
        if (want.isEmpty()) return null
        val same = candidates.filter { base(it) == want }.ifEmpty { subtitled(want, candidates) }
        if (same.isEmpty()) return null
        val region = tags(name).firstOrNull { it in setOf("usa", "europe", "japan", "world", "korea") } ?: "usa"
        return same.firstOrNull { region in tags(it) } ?: same.firstOrNull { "usa" in tags(it) } ?: same.first()
    }

    private fun digits(words: Set<String>) = words.filter { w -> w.any { it.isDigit() } }.toSet()

    /**
     * Names that add a subtitle to the file's (Donkey Kong Country 2 -> Donkey Kong Country 2 - Diddy's Kong Quest):
     * every word of the file's name present, the numbers the same (a sequel is not its first game), at most five words
     * more; the fewest extra words win.
     */
    private fun subtitled(want: String, candidates: List<String>): List<String> {
        val w = want.split(' ').toSet()
        val fits = candidates.mapNotNull { c ->
            val h = base(c).split(' ').toSet()
            if (h.containsAll(w) && digits(h) == digits(w) && h.size - w.size <= 5) c to (h.size - w.size) else null
        }
        val least = fits.minOfOrNull { it.second } ?: return emptyList()
        return fits.filter { it.second == least }.map { it.first }
    }

    private fun names(context: Context, collection: String): List<String> {
        val cache = File(root(context), "index-$collection.txt")
        if (cache.isFile && System.currentTimeMillis() - cache.lastModified() < INDEX_TTL_MS) return cache.readLines()
        val list = try {
            val top = JSONArray(get("https://api.github.com/repos/libretro-thumbnails/$collection/contents/") ?: return cachedOr(cache))
            val sha = (0 until top.length()).map { top.getJSONObject(it) }.firstOrNull { it.optString("name") == "Named_Boxarts" }
                ?.optString("sha") ?: return cachedOr(cache)
            val tree = JSONObject(get("https://api.github.com/repos/libretro-thumbnails/$collection/git/trees/$sha") ?: return cachedOr(cache))
                .getJSONArray("tree")
            (0 until tree.length()).map { tree.getJSONObject(it).optString("path") }
                .filter { it.endsWith(".png") }.map { it.removeSuffix(".png") }
        } catch (e: Exception) {
            Log.w(TAG, "index $collection: $e"); return cachedOr(cache)
        }
        cache.parentFile?.mkdirs()
        cache.writeText(list.joinToString("\n"))
        return list
    }

    private fun cachedOr(cache: File): List<String> = if (cache.isFile) cache.readLines() else emptyList()


    private fun isoFile(file: File, path: String, maxSize: Int): ByteArray? {
        if (!file.name.endsWith(".iso", ignoreCase = true)) return null
        RandomAccessFile(file, "r").use { f ->
            fun read(lba: Long, length: Int): ByteArray = ByteArray(length).also { f.seek(lba * 2048); f.readFully(it) }
            fun le32(b: ByteArray, at: Int) = (b[at].toLong() and 0xff) or ((b[at + 1].toLong() and 0xff) shl 8) or
                ((b[at + 2].toLong() and 0xff) shl 16) or ((b[at + 3].toLong() and 0xff) shl 24)
            val pvd = read(16, 2048)
            if (String(pvd, 1, 5, Charsets.US_ASCII) != "CD001") return null
            var lba = le32(pvd, 156 + 2)
            var size = le32(pvd, 156 + 10)
            val parts = path.split('/')
            for ((i, part) in parts.withIndex()) {
                val dir = read(lba, size.toInt().coerceAtMost(256 * 1024))
                var at = 0
                var hit = false
                while (at < dir.size) {
                    val len = dir[at].toInt() and 0xff
                    if (len == 0) { at = (at / 2048 + 1) * 2048; continue }
                    val name = String(dir, at + 33, dir[at + 32].toInt() and 0xff, Charsets.US_ASCII).substringBefore(';')
                    if (name.equals(part, ignoreCase = true)) {
                        lba = le32(dir, at + 2); size = le32(dir, at + 10); hit = true; break
                    }
                    at += len
                }
                if (!hit) return null
                if (i == parts.lastIndex) return if (size in 1..maxSize) read(lba, size.toInt()) else null
            }
        }
        return null
    }

    private fun copyPs3Icon(file: File, target: File): Boolean {
        val png = isoFile(file, "PS3_GAME/ICON0.PNG", 4 * 1024 * 1024) ?: return false
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".part")
        tmp.writeBytes(png)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.path, bounds)
        return if (bounds.outWidth > 0 && tmp.renameTo(target)) true else { tmp.delete(); false }
    }

    private fun ps2Serial(file: File): String? {
        val cnf = String(isoFile(file, "SYSTEM.CNF", 4096) ?: return null, Charsets.US_ASCII)
        val m = Regex("BOOT2\\s*=\\s*cdrom0:\\\\?([A-Z]{4})_(\\d{3})\\.(\\d{2})").find(cnf) ?: return null
        return "${m.groupValues[1]}-${m.groupValues[2]}${m.groupValues[3]}"
    }

    private fun nintendoDiscId(file: File): String? {
        // RVZ/WIA keep a copy of the disc header 0x10 into their second header, which starts at 0x48
        val offset = when (file.extension.lowercase()) { "iso", "gcm" -> 0L; "wbfs" -> 0x200L; "rvz", "wia" -> 0x58L; else -> return null }
        RandomAccessFile(file, "r").use { f ->
            val b = ByteArray(6); f.seek(offset); f.readFully(b)
            val id = String(b, Charsets.US_ASCII)
            return id.takeIf { it.all { c -> c.isLetterOrDigit() } }
        }
    }


    private fun open(url: String, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DroidDeck")
        }

    private fun <T> through(url: String, attempt: (String, Boolean) -> T?): T? {
        for (source in DownloadSource.candidates(url)) {
            val result = attempt(source, source == url)
            if (result != null) {
                DownloadSource.succeeded(source)
                return result
            }
        }
        DownloadSource.failed()
        return null
    }

    private fun exists(url: String): Boolean = through(url) { source, original -> head(source, original) } ?: false

    private fun head(url: String, original: Boolean): Boolean? = try {
        val c = open(url, "HEAD")
        val code = c.responseCode
        c.disconnect()
        when {
            code == 200 -> true
            code == 404 || code == 410 -> if (original) false else null
            else -> null
        }
    } catch (e: Exception) { null }

    private fun get(url: String): String? = through(url) { source, original -> text(source, original) }

    private fun text(url: String, original: Boolean): String? = try {
        val c = open(url)
        val code = c.responseCode
        when {
            code == 200 -> c.inputStream.bufferedReader().use { it.readText() }.takeIf { it.isNotEmpty() }
            code == 404 || code == 410 -> { c.disconnect(); if (original) "" else null }
            else -> { c.disconnect(); null }
        }
    } catch (e: Exception) { Log.w(TAG, "$url: $e"); null }

    private const val MAX_SIDE = 600
    private const val SHRINK_OVER = 200_000L

    /**
     * Box art as the pages show it: libretro's scans run to several MB; anything over 200 KB is redrawn at most 600 px
     * on its long side as WebP (alpha kept). True when the file was rewritten.
     */
    fun shrink(file: File): Boolean {
        if (file.length() <= SHRINK_OVER) return false
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val long = maxOf(bounds.outWidth, bounds.outHeight)
            if (long <= 0) return false
            var sample = 1
            while (long / (sample * 2) >= MAX_SIDE) sample *= 2
            val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
            val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
            val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
            val tmp = File(file.path + ".small")
            val format = if (android.os.Build.VERSION.SDK_INT >= 30) android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
                else @Suppress("DEPRECATION") android.graphics.Bitmap.CompressFormat.WEBP
            val ok = tmp.outputStream().use { bmp.compress(format, 85, it) }
            if (bmp !== decoded) bmp.recycle()
            decoded.recycle()
            if (ok && tmp.length() in 1 until file.length()) tmp.renameTo(file) else { tmp.delete(); false }
        } catch (e: Exception) { Log.w(TAG, "shrink ${file.name}: $e"); false }
    }

    /** Shrinks the covers already cached for the given emulator ids' prefix (the Retro page's: "android:"); count rewritten. */
    fun shrinkCached(context: Context, prefix: String): Int {
        var n = 0
        root(context).listFiles()?.filter { it.isDirectory && it.name.startsWith(prefix) }?.forEach { d ->
            d.listFiles()?.filter { it.isFile && (it.name.endsWith(".img") || it.name.endsWith(".snap")) }?.forEach { if (shrink(it)) n++ }
        }
        if (n > 0) Log.i(TAG, "shrank $n covers")
        return n
    }

    private fun download(url: String, target: File, depth: Int = 0): Boolean =
        through(url) { source, original -> fetch(source, target, original, depth) } ?: false

    private fun fetch(url: String, target: File, original: Boolean, depth: Int): Boolean? {
        return try {
            val c = open(url)
            when (c.responseCode) {
                200 -> {
                    target.parentFile?.mkdirs()
                    val tmp = File(target.path + ".part")
                    c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(tmp.path, bounds)
                    // libretro-thumbnails keeps some boxarts as symlinks: the file holds the name of the image it stands for
                    if (bounds.outWidth <= 0 && tmp.length() in 1..511 && depth < 3) {
                        val link = tmp.readText().trim()
                        tmp.delete()
                        if ((link.endsWith(".png", true) || link.endsWith(".jpg", true)) && '/' !in link)
                            return download(url.substringBeforeLast('/') + "/" + URLEncoder.encode(link, "UTF-8").replace("+", "%20"), target, depth + 1)
                        return false
                    }
                    if (bounds.outWidth > 0) shrink(tmp)
                    when {
                        bounds.outWidth > 0 && tmp.renameTo(target) -> true
                        original -> { tmp.delete(); false }
                        else -> { tmp.delete(); null }
                    }
                }
                404, 410 -> { c.disconnect(); if (original) false else null }
                else -> { c.disconnect(); null }
            }
        } catch (e: Exception) { Log.w(TAG, "$url: $e"); null }
    }
}
