package com.droiddeck.launcher.frontend

import android.content.Context
import android.os.Environment
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File

/**
 * The Retro page's library: ROM folders on internal storage and SD cards, each folder matched to a Daijishō platform
 * by its name, played in an installed Android emulator (one chosen per platform, Daijishō's first installed one
 * unless picked). Roots: SessionPrefs.romsDir when set, every "Roms*" folder at the top of a storage volume, and
 * the folders added here (prefs "retro", "roots").
 */
object RetroLibrary {
    const val ID_PREFIX = "android:"
    private const val PREFS = "retro"
    private const val MAX_DEPTH = 2

    class System(
        val platform: Platforms.Platform,
        val folders: List<File>,
        val games: List<Library.Rom>,
        val players: List<Platforms.Player>,
        val player: Platforms.Player?,
        /** The chosen Daijisho wallpaper pack's picture for this platform (WallpaperPacks), or null. */
        val wallpaper: File? = null,
        /** Year, genre and developer by ROM path, where known (GameInfo; arcade sets from the MAME 2003-Plus metadata). */
        val info: Map<String, GameInfo.Info> = emptyMap(),
    ) {
        val id: String get() = ID_PREFIX + platform.id
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun playerId(context: Context, platform: String): String? = prefs(context).getString("player.$platform", null)

    fun setPlayer(context: Context, platform: String, player: String) {
        prefs(context).edit().putString("player.$platform", player).apply()
    }

    /** Whether a system's games open as a list (the default) or a grid of box art. */
    fun listView(context: Context): Boolean = prefs(context).getBoolean("listView", true)

    fun setListView(context: Context, list: Boolean) {
        prefs(context).edit().putBoolean("listView", list).apply()
    }

    /** Whether the list shows a game's cover art (else its screenshot, the default). */
    fun listCover(context: Context): Boolean = prefs(context).getBoolean("listCover", false)

    fun setListCover(context: Context, cover: Boolean) {
        prefs(context).edit().putBoolean("listCover", cover).apply()
    }

    fun extraRoots(context: Context): List<String> =
        prefs(context).getString("roots", "").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Folders found on their own (romsDir, "Roms*") that the user took off the list. */
    private fun hiddenRoots(context: Context): Set<String> =
        prefs(context).getString("hiddenRoots", "").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun addRoot(context: Context, path: String) {
        val p = File(path).canonicalPath
        val hidden = hiddenRoots(context) - p
        val extra = (extraRoots(context) + p).distinct()
        prefs(context).edit().putString("roots", extra.joinToString("\n")).putString("hiddenRoots", hidden.joinToString("\n")).apply()
    }

    fun removeRoot(context: Context, path: String) {
        val p = File(path).canonicalPath
        val extra = extraRoots(context).filter { File(it).canonicalPath != p }
        prefs(context).edit().putString("roots", extra.joinToString("\n"))
            .putString("hiddenRoots", (hiddenRoots(context) + p).joinToString("\n")).apply()
    }

    /** Storage volumes: internal storage and each SD card or USB drive under /storage. */
    private fun volumes(): List<File> {
        val out = LinkedHashSet<File>()
        out.add(Environment.getExternalStorageDirectory())
        File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead() }?.forEach { out.add(it) }
        return out.toList()
    }

    fun roots(context: Context): List<File> {
        val out = LinkedHashSet<File>()
        SessionPrefs.romsDir(context).takeIf { it.isNotEmpty() }?.let { out.add(File(it)) }
        for (v in volumes()) v.listFiles()?.filter { it.isDirectory && it.name.lowercase().startsWith("rom") }?.forEach { out.add(it) }
        val hidden = hiddenRoots(context)
        val found = out.filter { it.isDirectory }.map { it.canonicalFile }.filter { it.path !in hidden }
        return (found + extraRoots(context).map { File(it) }.filter { it.isDirectory }.map { it.canonicalFile }).distinct()
    }

    private val notGames = setOf("srm", "sav", "state", "txt", "nfo", "png", "jpg", "jpeg", "xml", "dat", "rtc", "cfg", "ini", "db", "pdf", "mp4", "pup")

    /** Arcade BIOS sets that sit beside the games in MAME and FinalBurn folders. */
    private val biosSets = setOf("neogeo", "pgm", "qsound", "skns", "cpzn1", "cpzn2", "decocass", "isgsm", "naomi", "awbios", "hng64",
        "nmk004", "ym2608", "bubsys", "konamigx", "megaplay", "stvbios", "coh1000c", "coh1002m", "coh3002c", "playch10")

    private val arcade = setOf("mame", "neogeo", "fbneo", "cps1", "cps2", "cps3", "mame2003", "arcade")

    fun isArcade(platform: String) = platform in arcade

    private class ArcadeSet(val title: String, val info: GameInfo.Info)

    @Volatile private var arcadeSets: Map<String, ArcadeSet>? = null
    @Volatile private var arcadeStamp = 0L

    /** MAME and FinalBurn sets: title, year, maker, genre (ArcadeTitles' download; until then the set name is shown). */
    private fun arcadeSet(context: Context, set: String): ArcadeSet? {
        val f = ArcadeTitles.file(context)
        val stamp = if (f.isFile) f.lastModified() else 0L
        if (stamp != arcadeStamp) synchronized(this) { if (stamp != arcadeStamp) { arcadeSets = null; arcadeStamp = stamp } }
        val map = arcadeSets ?: synchronized(this) {
            arcadeSets ?: runCatching {
                f.bufferedReader().useLines { lines ->
                    lines.mapNotNull { l ->
                        val p = l.split('\t')
                        if (p.size < 2) null else p[0] to ArcadeSet(p[1], GameInfo.Info(
                            p.getOrNull(2)?.takeIf { it.isNotBlank() }, p.getOrNull(4)?.takeIf { it.isNotBlank() }, p.getOrNull(3)?.takeIf { it.isNotBlank() }))
                    }.toMap()
                }
            }.getOrDefault(emptyMap()).also { arcadeSets = it }
        }
        return map[set.lowercase()]
    }

    private fun arcadeTitle(context: Context, set: String): String? = arcadeSet(context, set)?.title

    private val pspTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** A PSP ISO's own title (PARAM.SFO), remembered by path, size and date. */
    private fun pspTitle(file: File): String? {
        if (file.extension.lowercase() != "iso") return null
        val key = "${file.path}|${file.length()}|${file.lastModified()}"
        pspTitles[key]?.let { return it.ifEmpty { null } }
        val title = PspIso.read(file)?.title?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        pspTitles[key] = title
        return title.ifEmpty { null }
    }

    /** The name shown: tags dropped, a release number ("0028 - ") and underscores gone; arcade sets and PSP discs by their own title. */
    private fun displayTitle(context: Context, platform: String, stem: String, file: File? = null): String {
        val isArcade = platform in arcade
        val name = when {
            isArcade -> arcadeTitle(context, stem) ?: stem
            platform == "psp" && file != null -> pspTitle(file) ?: stem
            platform in NoIntro.platforms && file != null -> NoIntro.name(context, platform, file) ?: stem
            else -> stem
        }
        return name.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "")
            .let { if (isArcade) it else it.replace(Regex("^\\d{3,4}\\s*-\\s*"), "") }
            .replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifEmpty { stem }
    }

    /** A folder's games: the files its player (or else the platform) takes, a .cue/.m3u standing for the files it names. */
    private fun games(context: Context, platform: Platforms.Platform, player: Platforms.Player?, folder: File): List<Library.Rom> {
        val accept = player?.accept ?: platform.accept
        val files = ArrayList<File>()
        folder.walkTopDown().maxDepth(MAX_DEPTH).filter { it.isFile && !it.name.startsWith(".") }.forEach { files.add(it) }
        val sheets = files.filter { it.extension.lowercase() in setOf("cue", "m3u", "gdi") }
        val named = sheets.flatMap { s -> runCatching { s.readLines().take(200) }.getOrDefault(emptyList()) }
            .flatMap { line -> Regex("[^\"\\\\/]+\\.[A-Za-z0-9]{2,4}").findAll(line).map { it.value.trim().lowercase() } }.toSet()
        return files.filter { f ->
            val ext = f.extension.lowercase()
            ext !in notGames && (accept == null || accept.matches(f.name)) &&
                !(ext == "zip" && f.nameWithoutExtension.lowercase() in biosSets) &&
                !(f.name.lowercase() in named && sheets.none { it == f })
        }.sortedBy { it.name.lowercase() }.map { f ->
            val rom = Library.Rom(displayTitle(context, platform.id, f.nameWithoutExtension, f), f, f.absolutePath, ID_PREFIX + platform.id)
            CoverArt.cached(context, rom)?.let { Library.Rom(rom.name, rom.hostPath, rom.guestPath, rom.emulatorId, it) } ?: rom
        }
    }

    fun scan(context: Context): List<System> {
        val byPlatform = LinkedHashMap<String, MutableList<File>>()
        for (root in roots(context)) {
            root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val p = Platforms.forFolder(context, dir.name) ?: return@forEach
                byPlatform.getOrPut(p.id) { ArrayList() }.add(dir)
            }
        }
        return byPlatform.mapNotNull { (id, folders) ->
            val platform = Platforms.byId(context, id) ?: return@mapNotNull null
            val players = Platforms.installedPlayers(context, platform).filter { AndroidLaunch.usable(it) }
            val chosen = playerId(context, id)?.let { pid -> players.firstOrNull { it.id == pid } } ?: byFolderName(platform, players, folders) ?: players.firstOrNull()
            val games = folders.flatMap { games(context, platform, chosen, it) }
            if (games.isEmpty()) return@mapNotNull null
            val info = HashMap<String, GameInfo.Info>()
            if (id in arcade) games.forEach { g -> arcadeSet(context, g.hostPath.nameWithoutExtension)?.let { info[g.hostPath.path] = it.info } }
            else {
                val known = GameInfo.load(context, id)
                if (known.isNotEmpty()) games.forEach { g -> (known[GameInfo.key(g.hostPath.nameWithoutExtension)] ?: known[GameInfo.key(g.name)])?.let { info[g.hostPath.path] = it } }
            }
            System(platform, folders, games, players, chosen, WallpaperPacks.forPlatform(context, platform), info)
        }.sortedWith(compareBy({ it.platform.id in arcade }, { it.platform.name.lowercase() })).also { NoIntro.save(context) }
    }

    /**
     * A RetroArch core the folder is named after ("Arcade - Mame 2003 Plus" -> mame2003_plus), the longest such name.
     * A folder named just for the platform ("scummvm") names no core: its first player stays the choice.
     */
    private fun byFolderName(platform: Platforms.Platform, players: List<Platforms.Player>, folders: List<File>): Platforms.Player? {
        val own = setOf(platform.id, platform.shortname).map { it.lowercase().replace(Regex("[^a-z0-9]"), "") }
        val names = folders.map { it.name.lowercase().replace(Regex("[^a-z0-9]"), "") }.filter { it !in own }
        return players.filter { p -> p.core != null && names.any { n -> p.core.lowercase().replace(Regex("[^a-z0-9]"), "").let { c -> c.length >= 4 && n.contains(c) } } }
            .maxByOrNull { it.core!!.length }
    }

    fun collectionsFor(context: Context, emulatorId: String): List<String>? {
        if (!emulatorId.startsWith(ID_PREFIX)) return null
        return Platforms.byId(context, emulatorId.removePrefix(ID_PREFIX))?.collection?.let { listOf(it) }
    }
}
