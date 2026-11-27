package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.Writer
import java.net.HttpURLConnection
import java.net.URL

/**
 * Arcade sets' titles, years, makers and genres, downloaded at first use from the MAME 2003-Plus metadata
 * (mame2003-plus.xml and catver.ini, github.com/libretro/mame2003-plus-libretro) and cached as
 * files/retro-arcade/arcade-titles.tsv: set, title, year, maker, genre. Not shipped in the APK, since that data
 * carries MAME's own terms rather than the GPL.
 */
object ArcadeTitles {
    private const val TAG = "ArcadeTitles"
    private const val RAW = "https://raw.githubusercontent.com/libretro/mame2003-plus-libretro/master/metadata"

    fun file(context: Context) = File(File(context.filesDir, "retro-arcade"), "arcade-titles.tsv")

    fun cached(context: Context): Boolean = file(context).let { it.isFile && it.length() > 0 }

    private fun <T> open(url: String, read: (BufferedReader) -> T): T? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "DroidDeck")
        if (c.responseCode == 200) c.inputStream.bufferedReader().use(read) else null
    } catch (e: Exception) {
        Log.w(TAG, "$url: $e"); null
    }

    /** Downloads both files (about 24 MB, read as a stream) and writes the cache; false when either is missing. */
    fun fetch(context: Context): Boolean {
        val genres = open("$RAW/catver.ini") { genres(it) } ?: return false
        val out = file(context)
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".part")
        val n = open("$RAW/mame2003-plus.xml") { xml -> tmp.bufferedWriter().use { w -> write(xml, genres, w) } }
        if (n == null || n == 0) { tmp.delete(); return false }
        if (!tmp.renameTo(out)) { tmp.delete(); return false }
        Log.i(TAG, "$n arcade sets")
        return true
    }

    /** catver.ini's [Category] section: set to genre. */
    fun genres(ini: BufferedReader): Map<String, String> {
        val map = HashMap<String, String>()
        var inCategory = false
        ini.forEachLine { l ->
            val line = l.trim()
            if (line.startsWith("[")) inCategory = line == "[Category]"
            else if (inCategory && '=' in line && !line.startsWith(";")) map[line.substringBefore('=').trim()] = line.substringAfter('=').trim()
        }
        return map
    }

    private val gameTag = Regex("<game name=\"([^\"]+)\"")
    private fun field(tag: String) = Regex("<$tag>([^<]*)</$tag>")
    private val description = field("description")
    private val year = field("year")
    private val manufacturer = field("manufacturer")

    private fun unescape(s: String) =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun clean(s: String) = unescape(s).replace('\t', ' ').trim()

    /** The xml's games as cache lines, one per set with a title; returns how many were written. */
    fun write(xml: BufferedReader, genres: Map<String, String>, out: Writer): Int {
        var n = 0
        var set: String? = null
        var title: String? = null
        var y = ""
        var maker = ""
        fun flush() {
            val s = set; val t = title
            if (s != null && t != null) {
                out.write(listOf(s, t, y, maker, genres[s].orEmpty()).joinToString("\t")); out.write("\n"); n++
            }
            set = null; title = null; y = ""; maker = ""
        }
        xml.forEachLine { line ->
            gameTag.find(line)?.let { flush(); set = it.groupValues[1].lowercase() }
            if (set != null) {
                if (title == null) description.find(line)?.let { title = clean(it.groupValues[1]) }
                if (y.isEmpty()) year.find(line)?.let { y = clean(it.groupValues[1]) }
                if (maker.isEmpty()) manufacturer.find(line)?.let { maker = clean(it.groupValues[1]) }
            }
            if ("</game>" in line) flush()
        }
        flush()
        return n
    }
}
