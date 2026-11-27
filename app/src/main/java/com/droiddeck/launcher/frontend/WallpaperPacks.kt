package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Daijishō's platform wallpaper packs (github.com/TapiocaFox/Daijishou, themes/platform_wallpapers_packs): one picture
 * per platform, made by the community. Downloaded on request, as Daijishō itself does, into files/retro-wallpapers/<pack>;
 * none ship in the APK. A pack's index.json matches pictures by platform unique id or shortname, with a default picture.
 */
object WallpaperPacks {
    private const val TAG = "WallpaperPacks"
    private const val API = "https://api.github.com/repos/TapiocaFox/Daijishou/contents/themes/platform_wallpapers_packs"
    private const val RAW = "https://raw.githubusercontent.com/TapiocaFox/Daijishou/main/themes/platform_wallpapers_packs"
    private const val LIST_TTL_MS = 7L * 24 * 3600 * 1000
    private const val PREFS = "retro"

    private fun root(context: Context) = File(context.filesDir, "retro-wallpapers")
    private fun packDir(context: Context, pack: String) = File(root(context), pack.replace(Regex("[^A-Za-z0-9._ -]"), "_"))

    fun chosen(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("wallpaperPack", null)?.takeIf { it.isNotEmpty() }

    fun setChosen(context: Context, pack: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("wallpaperPack", pack ?: "").apply()
    }

    fun installed(context: Context, pack: String): Boolean = File(packDir(context, pack), "index.json").isFile

    private fun get(url: String): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "DroidDeck")
        if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
    } catch (e: Exception) {
        Log.w(TAG, "$url: $e"); null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** The packs' names (cached for a week; the cache, or the chosen pack alone, when offline). */
    fun list(context: Context): List<String> {
        val cache = File(root(context), "packs.txt")
        if (cache.isFile && System.currentTimeMillis() - cache.lastModified() < LIST_TTL_MS) return cache.readLines().filter { it.isNotBlank() }
        val body = get(API)?.toString(Charsets.UTF_8)
        val names = body?.let { b -> runCatching { JSONArray(b).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("type") == "dir" }.map { it.getString("name") } } }.getOrNull() }
        if (names.isNullOrEmpty()) return if (cache.isFile) cache.readLines().filter { it.isNotBlank() } else listOfNotNull(chosen(context))
        root(context).mkdirs()
        cache.writeText(names.joinToString("\n"))
        return names
    }

    /** Downloads a pack's index and pictures; true when the index and at least one picture arrived. */
    fun download(context: Context, pack: String): Boolean {
        val dir = packDir(context, pack)
        val index = get("$RAW/${enc(pack)}/index.json") ?: return false
        val j = runCatching { JSONObject(index.toString(Charsets.UTF_8)) }.getOrNull() ?: return false
        dir.mkdirs()
        val files = LinkedHashSet<String>()
        j.optJSONArray("wallpaperList")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("filename")?.takeIf { it.isNotBlank() }?.let { files.add(it) } }
        j.optString("defaultWallpaperFilename").takeIf { it.isNotBlank() }?.let { files.add(it) }
        var got = 0
        for (f in files) {
            val target = File(dir, f)
            if (target.isFile && target.length() > 0) { got++; continue }
            val data = get("$RAW/${enc(pack)}/${enc(f)}") ?: continue
            target.writeBytes(data); got++
        }
        if (got == 0) return false
        File(dir, "index.json").writeBytes(index)
        Log.i(TAG, "$pack: $got pictures")
        return true
    }

    /** Names packs use for a platform besides its own id and shortname. */
    private val packNames = mapOf(
        "mame" to listOf("arcade", "mame2003plus"), "neogeo" to listOf("mvs"), "vita" to listOf("psvita"), "sms" to listOf("master"),
        "genesis" to listOf("megadrive", "md"), "switch" to listOf("nsw"),
    )

    /** The chosen pack's picture for a platform: by unique id, then shortname, then the pack's default. */
    fun forPlatform(context: Context, platform: Platforms.Platform): File? {
        val pack = chosen(context) ?: return null
        val dir = packDir(context, pack)
        val j = runCatching { JSONObject(File(dir, "index.json").readText()) }.getOrNull() ?: return null
        val list = j.optJSONArray("wallpaperList")
        val entries = (0 until (list?.length() ?: 0)).mapNotNull { list!!.optJSONObject(it) }
        val ids = listOf(platform.id, platform.shortname) + (packNames[platform.id] ?: emptyList())
        val hit = ids.firstNotNullOfOrNull { id ->
            entries.firstOrNull { it.optString("matchPlatformUniqueId").equals(id, true) || it.optString("matchPlatformShortname").equals(id, true) }
        }
        val name = hit?.optString("filename")?.takeIf { it.isNotBlank() }
            ?: j.optString("defaultWallpaperFilename").takeIf { j.optBoolean("hasDefaultWallpaper", true) && it.isNotBlank() }
        return name?.let { File(dir, it) }?.takeIf { it.isFile }
    }
}
