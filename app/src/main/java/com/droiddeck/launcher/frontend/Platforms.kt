package com.droiddeck.launcher.frontend

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject

/**
 * Daijishō's platform definitions (github.com/TapiocaFox/Daijishou, MIT; assets/daijishou/LICENSE.MD): the file types a
 * system takes, its libretro-thumbnails collection, and the Android emulators ("players") that run it, each as the
 * arguments of an `am start` (AndroidLaunch turns them into an Intent).
 */
object Platforms {
    private const val TAG = "Platforms"
    private const val DIR = "daijishou/platforms"

    class Player(val id: String, val name: String, val args: String, val accept: Regex?) {
        val pkg: String? = Regex("-n\\s+([\\w.]+)/").find(args)?.groupValues?.get(1)
        /** The emulator's own name, without Daijishō's "<platform> - " prefix; a RetroArch core first: "snes9x2010 (RetroArch)". */
        /** The installed app's own name, for players Daijisho names by package ("com.fastemulator.gba"). */
        @Volatile var appLabel: String? = null
        val label: String get() = appLabel?.takeIf { name.substringAfter(" - ", name).trim() == pkg } ?: baseLabel
        private val baseLabel: String = name.substringAfter(" - ", name).trim().let { n ->
            Regex("(?i)^retroarch\\s*(32|64)?\\s*-\\s*(.+)$").find(n)?.let { m ->
                val bits = m.groupValues[1]
                "${m.groupValues[2].trim()} (RetroArch${if (bits == "32") " 32" else ""})"
            } ?: n
        }
        /** The RetroArch core it names (-e LIBRETRO), or null for a standalone emulator. */
        val core: String? = Regex("LIBRETRO\\s+(\\S+)").find(args)?.groupValues?.get(1)
            ?.substringAfterLast('/')?.removeSuffix("_libretro_android.so")
    }

    class Platform(
        val id: String, val name: String, val shortname: String, val accept: Regex?,
        /** The libretro-thumbnails repository, "Nintendo_-_Game_Boy_Advance" */
        val collection: String?,
        /** The libretro-database system name, "Nintendo - Game Boy Advance" */
        val libretroName: String?,
        val players: List<Player>,
    )

    @Volatile private var cache: List<Platform>? = null

    fun all(context: Context): List<Platform> = cache ?: synchronized(this) { cache ?: load(context).also { cache = it } }

    fun byId(context: Context, id: String): Platform? = all(context).firstOrNull { it.id == id }

    private fun regex(s: String?): Regex? =
        s?.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }

    private fun load(context: Context): List<Platform> {
        val names = context.assets.list(DIR).orEmpty().filter { it.endsWith(".json") }.sorted()
        return names.mapNotNull { file ->
            runCatching {
                val j = JSONObject(context.assets.open("$DIR/$file").bufferedReader().use { it.readText() })
                val p = j.getJSONObject("platform")
                val sources = p.optJSONArray("scraperSourceList")
                val libretro = (0 until (sources?.length() ?: 0)).map { sources!!.getString(it) }
                    .firstOrNull { it.startsWith("LIBRETRO:") }?.removePrefix("LIBRETRO:")?.trim()
                val list = j.optJSONArray("playerList")
                val players = (0 until (list?.length() ?: 0)).map { i ->
                    val o = list!!.getJSONObject(i)
                    val args = o.optString("amStartArguments")
                    // ScummVM's own app takes a game id; RetroArch's ScummVM core files (.scummvm) hold one too.
                    val accept = if (args.contains(AndroidLaunch.SCUMMVM_ID)) Regex("^(.*)\\.(?:dpt|scummvm)$", RegexOption.IGNORE_CASE)
                        else regex(o.optString("acceptedFilenameRegex"))
                    Player(o.optString("uniqueId"), o.optString("name"), args, accept)
                }.filter { it.pkg != null }
                Platform(p.getString("uniqueId"), p.getString("name"), p.optString("shortname", p.getString("uniqueId")),
                    regex(p.optString("acceptedFilenameRegex")), libretro?.replace(' ', '_'), libretro, players)
            }.onFailure { Log.w(TAG, "$file: $it") }.getOrNull()
        }
    }

    private const val RETROARCH_64 = "com.retroarch.aarch64"
    private const val RETROARCH_32 = "com.retroarch.ra32"

    /**
     * The players whose emulator is installed, in Daijishō's order (its first is the usual choice). On a 64-bit device
     * with RetroArch 64 installed, RetroArch 32's cores are left out: they run in a second, separately set-up RetroArch
     * (its own cores and settings), and one never opened plays every game as a black screen.
     */
    fun installedPlayers(context: Context, platform: Platform): List<Player> {
        val pm = context.packageManager
        val have = HashMap<String, Boolean>()
        val installed = { pkg: String ->
            have.getOrPut(pkg) {
                try { pm.getPackageInfo(pkg, 0); true } catch (e: PackageManager.NameNotFoundException) { false }
            }
        }
        val skip32 = android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && installed(RETROARCH_64)
        return platform.players.filter { p ->
            val pkg = p.pkg ?: return@filter false
            installed(pkg) && !(skip32 && pkg == RETROARCH_32)
        }.onEach { p ->
            if (p.appLabel == null) p.appLabel = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p.pkg!!, 0)).toString() }.getOrNull()
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Folder names other frontends and handhelds use, by Daijishō platform id. */
    private val aliases = mapOf(
        "fc" to "nes", "famicom" to "nes", "nintendo" to "nes",
        "sfc" to "snes", "superfamicom" to "snes", "supernintendo" to "snes",
        "md" to "genesis", "megadrive" to "genesis", "segagenesis" to "genesis",
        "ps" to "psx", "ps1" to "psx", "playstation" to "psx", "sonyplaystation" to "psx",
        "playstation2" to "ps2", "playstationportable" to "psp",
        "gc" to "gc", "ngc" to "gc", "gamecube" to "gc",
        "sw" to "switch", "nsw" to "switch", "ns" to "switch",
        "psvita" to "vita", "vita" to "vita",
        "nds" to "nds", "ds" to "nds",
        "n3ds" to "3ds",
        "arcade" to "mame", "arcademame2003plus" to "mame", "mame2003plus" to "mame", "mame2003" to "mame",
        "snkneogeo" to "neogeo", "neogeo" to "neogeo",
        "gamegear" to "gg", "mastersystem" to "sms", "dreamcast" to "dc",
    )

    /** The platform a ROM folder holds, by its name. */
    fun forFolder(context: Context, folder: String): Platform? {
        val key = norm(folder)
        if (key.isEmpty()) return null
        aliases[key]?.let { id -> byId(context, id)?.let { return it } }
        val all = all(context)
        return all.firstOrNull { it.id == key } ?: all.firstOrNull { norm(it.shortname) == key } ?: all.firstOrNull { norm(it.name) == key }
    }
}
