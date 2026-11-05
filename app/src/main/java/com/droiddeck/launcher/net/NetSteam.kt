package com.droiddeck.launcher.net

import android.content.Context

/** A Steam game in a share's steamapps folder: its appmanifest .acf, and its common/ folder when that's there too. */
data class NetSteamGame(
    val appId: Int,
    val name: String,
    val installDir: String,
    /** SizeOnDisk from the .acf (0 when it doesn't say). */
    val size: Long,
    val acf: NetEntry,
    val dir: NetEntry?,
    /** Steam's buildid from the .acf: it goes up with each update (0 when it doesn't say). */
    val build: Long = 0L,
)

/** What a share's steamapps folder holds. [loose] are common/ folders no .acf names: Steam can't take those as installed. */
data class NetSteamShelf(val steamapps: String, val games: List<NetSteamGame>, val loose: List<String>)

/**
 * Steam games on a share, laid out as a Steam library is on a PC or a card: steamapps\appmanifest_<id>.acf beside
 * steamapps\common\<installdir>. Copying both into a device library is all Steam needs to list the game as installed.
 */
object NetSteam {
    private val APPID = Regex("^\\s*\"appid\"\\s*\"(\\d+)\"", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val NAME = Regex("^\\s*\"name\"\\s*\"([^\"]*)\"", RegexOption.MULTILINE)
    private val INSTALL_DIR = Regex("^\\s*\"installdir\"\\s*\"([^\"]*)\"", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val BUILD = Regex("^\\s*\"buildid\"\\s*\"(\\d+)\"", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val SIZE = Regex("^\\s*\"SizeOnDisk\"\\s*\"(\\d+)\"", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    private const val PREFS = "net_shares"

    /** The steamapps folder chosen for [share], or null to look for one. */
    fun savedPath(context: Context, share: NetShare): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("steam:${share.id}", null)

    fun savePath(context: Context, share: NetShare, path: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("steam:${share.id}", path).apply()

    /** Forgets the chosen folder, so the Steam games tab looks for one again. */
    fun forgetPath(context: Context, share: NetShare) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("steam:${share.id}").apply()

    /** A folder named steamapps at the share's root or one folder down (RP4Pro\steamapps), or null. */
    fun find(link: SmbLink): String? {
        val root = link.list("")
        root.firstOrNull { it.dir && it.name.equals("steamapps", ignoreCase = true) }?.let { return it.path }
        // a cluttered share can have hundreds of folders; the likely names are looked in first and the rest only up to a point
        val likely = Regex("rp4|retroid|droid|deck|steam|game|handheld", RegexOption.IGNORE_CASE)
        val dirs = root.filter { it.dir }.sortedBy { if (likely.containsMatchIn(it.name)) 0 else 1 }.take(60)
        for (d in dirs) {
            val hit = runCatching { link.list(d.path) }.getOrNull()?.firstOrNull { it.dir && it.name.equals("steamapps", ignoreCase = true) }
            if (hit != null) return hit.path
        }
        return null
    }

    fun scan(link: SmbLink, steamapps: String): NetSteamShelf {
        val top = link.list(steamapps)
        val common = top.firstOrNull { it.dir && it.name.equals("common", ignoreCase = true) }
        val folders = common?.let { link.list(it.path).filter { e -> e.dir } }.orEmpty()
        val byName = folders.associateBy { it.name.lowercase() }
        val games = top.asSequence()
            .filter { !it.dir && it.name.startsWith("appmanifest_", ignoreCase = true) && it.name.endsWith(".acf", ignoreCase = true) }
            .mapNotNull { acf ->
                val text = runCatching { link.readText(acf.path) }.getOrNull() ?: return@mapNotNull null
                val appId = APPID.find(text)?.groupValues?.get(1)?.toIntOrNull()
                    ?: acf.name.substring(12, acf.name.length - 4).toIntOrNull() ?: return@mapNotNull null
                if (appId in com.droiddeck.launcher.frontend.Library.NOT_GAMES) return@mapNotNull null
                val dir = INSTALL_DIR.find(text)?.groupValues?.get(1)?.trim().orEmpty()
                // Steam on the device acts on installdir (plays, updates, uninstalls it): only a plain folder name will do
                if (!SmbLink.safeName(dir) || !SmbLink.safeName(acf.name)) return@mapNotNull null
                NetSteamGame(
                    appId, NAME.find(text)?.groupValues?.get(1)?.trim().orEmpty().ifEmpty { dir.ifEmpty { "App $appId" } }, dir,
                    SIZE.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L, acf, byName[dir.lowercase()], buildOf(text),
                )
            }
            .sortedBy { it.name.lowercase() }
            .toList()
        val named = games.mapNotNull { it.dir?.name?.lowercase() }.toSet()
        val loose = folders.filter { it.name.lowercase() !in named && !it.name.startsWith("Proton", ignoreCase = true) && !it.name.startsWith("SteamLinuxRuntime") }
            .map { it.name }
        return NetSteamShelf(steamapps, games, loose)
    }

    /** The buildid in an .acf's text, or 0. */
    fun buildOf(acf: String): Long = BUILD.find(acf)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    /** A game's poster from Steam's CDN, for games that aren't on this device yet (so Steam hasn't cached any art). */
    fun posterUrl(appId: Int) = "https://cdn.cloudflare.steamstatic.com/steam/apps/$appId/library_600x900.jpg"

    fun headerUrl(appId: Int) = "https://cdn.cloudflare.steamstatic.com/steam/apps/$appId/header.jpg"
}
