package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Year, genre and developer for the Retro page's games: libretro-database's metadat files (genre, releaseyear,
 * developer) per platform, matched by name as the covers are, cached in files/retro-info/<platform>.tsv. Arcade sets
 * take theirs from the MAME 2003-Plus metadata instead (RetroLibrary).
 */
object GameInfo {
    private const val TAG = "GameInfo"
    private const val RAW = "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat"
    private const val TTL_MS = 30L * 24 * 3600 * 1000
    private val KINDS = listOf("releaseyear", "genre", "developer")

    class Info(val year: String?, val genre: String?, val developer: String?) {
        fun line(): String = listOfNotNull(genre, year, developer).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun dir(context: Context) = File(context.filesDir, "retro-info")
    private fun cache(context: Context, platform: String) = File(dir(context), "$platform.tsv")

    /** The name as both sides are compared: tags, articles, punctuation and case dropped. */
    fun key(name: String): String = name
        .replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), " ")
        .replace(Regex("^\\d{3,4}\\s*-\\s*"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .split(' ').filter { it.isNotEmpty() && it != "the" && it != "a" }
        .joinToString(" ")

    fun cached(context: Context, platform: String): Boolean = cache(context, platform).isFile

    fun fresh(context: Context, platform: String): Boolean =
        cache(context, platform).let { it.isFile && System.currentTimeMillis() - it.lastModified() < TTL_MS }

    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "DroidDeck")
        if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) {
        Log.w(TAG, "$url: $e"); null
    }

    /** Downloads a platform's metadata and caches it; false when nothing came (offline, or no such files). */
    fun fetch(context: Context, platform: Platforms.Platform): Boolean {
        val name = platform.libretroName ?: return false
        val fields = HashMap<String, HashMap<String, String>>()
        var any = false
        for (kind in KINDS) {
            val text = get("$RAW/$kind/" + URLEncoder.encode("$name.dat", "UTF-8").replace("+", "%20")) ?: continue
            any = true
            val block = Regex("game \\(\\s*\\n\\s*comment \"([^\"]+)\"\\s*\\n\\s*$kind \"([^\"]*)\"")
            for (m in block.findAll(text)) {
                val k = key(m.groupValues[1])
                if (k.isNotEmpty()) fields.getOrPut(k) { HashMap() }.putIfAbsent(kind, m.groupValues[2])
            }
        }
        if (!any) return false
        dir(context).mkdirs()
        cache(context, platform.id).writeText(fields.entries.joinToString("\n") { (k, v) ->
            listOf(k, v["releaseyear"].orEmpty(), v["genre"].orEmpty(), v["developer"].orEmpty()).joinToString("\t")
        })
        Log.i(TAG, "${platform.id}: ${fields.size} games")
        return true
    }

    /** A platform's cached metadata, by key(). */
    fun load(context: Context, platform: String): Map<String, Info> {
        val f = cache(context, platform)
        if (!f.isFile) return emptyMap()
        return runCatching {
            f.readLines().mapNotNull { l ->
                val p = l.split('\t')
                if (p.size < 4) null else p[0] to Info(p[1].ifBlank { null }, p[2].ifBlank { null }, p[3].ifBlank { null })
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
