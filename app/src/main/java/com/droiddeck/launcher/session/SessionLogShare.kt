package com.droiddeck.launcher.session

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.LogRedactor
import com.droiddeck.launcher.runtime.BattleNet
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SessionLogShare {
    private val main = Handler(Looper.getMainLooper())

    var progress by mutableStateOf<Float?>(null)
        private set

    fun prepare(context: Context, folder: () -> File?, done: (File?) -> Unit) {
        if (progress != null) return
        progress = 0f
        Thread({
            val zip = runCatching { folder()?.let { dir -> zipFolder(context, dir) { p -> main.post { if (progress != null) progress = p } } } }
                .onFailure { Log.w("SessionLogShare", "could not package session logs", it) }
                .getOrNull()
            main.post { progress = null; done(zip) }
        }, "share-logs").start()
    }

    fun latest(context: Context): File? = SessionPaths.sessionFolders(context).lastOrNull()


    fun zipFolder(context: Context, folder: File, onProgress: ((Float) -> Unit)? = null): File? {
        if (!folder.isDirectory) return null
        val files = sessionFiles(folder)
        if (files.isEmpty()) return null
        // A session that never ran (start cancelled during the runtime download, or stopped at once) holds
        // almost nothing; the problem the player means is in the last one that ran, so that one comes along.
        val earlier = if (ran(folder)) null else lastRunBefore(context, folder)
        val earlierFiles = earlier?.let(::sessionFiles).orEmpty()
        val out = shareDir(context).apply { mkdirs() }
        pruneShared(out)
        val zip = File(out, "DroidDeck-${folder.name}.zip")
        val part = File(out, zip.name + ".part")
        LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        val diag = exitDiagCut(folder, files, out)
        val live = liveSteamLogs(context, folder)
        val proton = protonLogs(context, files, out)
        val game = gameLogs(context, files, out)
        val logs = LinuxRuntime.logDir(context)
        val stores = recent(File(logs, STORES_DIR))
        val tools = recent(File(logs, SessionPaths.TOOLS_DIR))
        val battleNet = battleNetLogs(context)
        val total = (files + earlierFiles + live + proton + game.map { it.first } + stores + tools + battleNet.map { it.second }).sumOf { it.length() }.coerceAtLeast(1L)
        var done = 0L
        var shown = -1
        fun advance(f: File) {
            done += f.length()
            val percent = (done * 100 / total).toInt()
            if (percent != shown) { shown = percent; onProgress?.invoke(percent / 100f) }
        }
        ZipOutputStream(part.outputStream().buffered()).use { z ->
            files.forEach { f -> addEntry(z, folder.name + "/" + f.relativeTo(folder).invariantSeparatorsPath, diag[f] ?: f); advance(f) }
            if (earlier != null) earlierFiles.forEach { f -> addEntry(z, earlier.name + "/" + f.relativeTo(earlier).invariantSeparatorsPath, f); advance(f) }
            live.forEach { f -> addEntry(z, folder.name + "/steam/" + f.name, f); advance(f) }
            proton.forEach { f -> addEntry(z, folder.name + "/proton/" + f.name, f); advance(f) }
            game.forEach { (f, name) -> addEntry(z, folder.name + "/game/" + name, f); advance(f) }
            stores.forEach { f -> addEntry(z, "$STORES_DIR/" + f.name, f); advance(f) }
            tools.forEach { f -> addEntry(z, SessionPaths.TOOLS_DIR + "/" + f.name, f); advance(f) }
            battleNet.forEach { (name, f) -> addEntry(z, "$BATTLENET_DIR/$name", f); advance(f) }
        }
        /* Renamed into place: an app still reading the zip shared before keeps the copy it opened. */
        if (!part.renameTo(zip)) { part.delete(); return null }
        return zip
    }

    /** Every entry is scrubbed line by line, whatever its size: a raw copy can carry tokens out of the device. */
    private fun sessionFiles(folder: File): List<File> = folder.walkTopDown().filter { it.isFile }
        .filterNot { it.parentFile?.name == "steam" && it.length() > STEAM_LOG_MAX_BYTES }.toList()

    /** The session got as far as starting its programs: session.log is written from then on. */
    internal fun ran(folder: File): Boolean = File(folder, "session.log").isFile

    /** The newest session before [folder] that ran, if any. */
    internal fun lastRunBefore(context: Context, folder: File): File? {
        val all = SessionPaths.sessionFolders(context)
        val at = all.indexOfFirst { it.canonicalPath == folder.canonicalPath }
        return (if (at < 0) all else all.take(at)).lastOrNull { it.canonicalPath != folder.canonicalPath && ran(it) }
    }

    private fun addEntry(z: ZipOutputStream, name: String, f: File) {
        z.putNextEntry(ZipEntry(name))
        val w = z.bufferedWriter()
        LogRedactor.scrubForShare(f, w)
        w.flush()
        z.closeEntry()
    }

    private fun liveSteamLogs(context: Context, folder: File): List<File> {
        if (folder != SessionPaths.current() || File(folder, "steam").exists()) return emptyList()
        val logs = File(SessionPrefs.activeSteamRoot(context), "logs")
        return logs.listFiles()?.filter { it.isFile && it.length() <= STEAM_LOG_MAX_BYTES }.orEmpty()
    }

    /* Proton's own log (PROTON_LOG=1, the game options' toggle) lands in the guest's home as
     * steam-<appid>.log and is rewritten at each launch: the ones written while this session ran come
     * along, the last PROTON_LOG_MAX_BYTES of a longer one, so a report doesn't need a second ask. */
    private fun protonLogs(context: Context, files: List<File>, out: File): List<File> {
        val from = files.minOfOrNull { it.lastModified() } ?: return emptyList()
        val to = files.maxOf { it.lastModified() }
        val homes = listOf("root", "root/.droiddeck-x64").map { File(LinuxRuntime.rootDir(context), it) }
        val logs = homes.flatMap { home -> home.listFiles { f -> f.isFile && PROTON_LOG.matches(f.name) && f.lastModified() in from..to }.orEmpty().toList() }
        return logs.sortedBy { it.name }.map { f -> if (f.length() <= PROTON_LOG_MAX_BYTES) f else tail(f, File(out, f.name)) }
    }

    /* The games' own logs written while this session ran, from their Proton prefixes: Unity's
     * Player.log / Player-prev.log and its crash folders' error.log, Unreal's Saved/Logs and
     * Saved/Crashes, Documents/My Games (SOMA's hpl.log, Frictional's and Bethesda's games), and
     * Unreal games that keep Saved/ in their install folder (Black Myth: Wukong's b1/Saved) - the
     * files a report otherwise needs a second ask for. Never a crash dump, only text. The last
     * PROTON_LOG_MAX_BYTES of a longer one. Entry names: <appid>/<path under AppData or the user
     * folder> for a prefix, <appid or "added">/<path under the library> for an install folder. */
    private fun gameLogs(context: Context, files: List<File>, out: File): List<Pair<File, String>> {
        val from = files.minOfOrNull { it.lastModified() } ?: return emptyList()
        val to = maxOf(files.maxOf { it.lastModified() }, System.currentTimeMillis())
        val steams = listOf("arm64", "x86_64").map { SessionPrefs.steamRoot(context, it) }.filter { it.isDirectory }
        val steam = steams.firstOrNull() ?: return emptyList()
        val found = mutableListOf<Pair<File, String>>()
        val seen = HashSet<String>()
        fun take(candidates: Sequence<File>, name: (File) -> String) {
            for (f in candidates) {
                if (found.size >= GAME_LOGS_MAX) return
                if (!f.isFile || f.lastModified() !in from..to) continue
                // Proton's "My Documents" is a link to "Documents": one copy.
                if (!seen.add(runCatching { f.canonicalPath }.getOrDefault(f.absolutePath))) continue
                val entry = name(f)
                val kept = if (f.length() <= PROTON_LOG_MAX_BYTES) f else tail(f, File(out, entry.replace('/', '_')))
                found += kept to entry
            }
        }
        for (prefix in steams.flatMap { File(it, "steamapps/compatdata").listFiles().orEmpty().toList() }) {
            val user = File(prefix, "pfx/drive_c/users/steamuser")
            val appData = File(user, "AppData")
            if (!user.isDirectory) continue
            take(sequenceOf(
                File(appData, "LocalLow").listFiles().orEmpty().asSequence().flatMap { it.listFiles().orEmpty().asSequence() }
                    .flatMap { sequenceOf(File(it, "Player.log"), File(it, "Player-prev.log")) },
                File(appData, "Local").listFiles().orEmpty().asSequence()
                    .flatMap { File(it, "Saved/Logs").listFiles { f -> f.name.endsWith(".log") }.orEmpty().asSequence() },
                File(appData, "Local").listFiles().orEmpty().asSequence()
                    .flatMap { File(it, "Saved/Crashes").listFiles().orEmpty().asSequence() }.flatMap(::unrealCrash),
                File(appData, "Local/Temp").listFiles().orEmpty().asSequence().flatMap { it.listFiles().orEmpty().asSequence() }
                    .flatMap { File(it, "Crashes").listFiles().orEmpty().asSequence() }.map { File(it, "error.log") },
                sequenceOf("Documents", "My Documents").flatMap { docs ->
                    File(user, "$docs/My Games").walkTopDown().maxDepth(3)
                        .filter { it.isFile && (it.extension == "log" || it.extension == "txt") }
                },
            ).flatten()) { f -> prefix.name + "/" + f.relativeTo(if (f.startsWith(appData)) appData else user).invariantSeparatorsPath }
        }
        for ((library, appIds) in installLibraries(context, steam)) {
            // Two directory levels per library (<game>/ and <game>/<project>/): a few hundred
            // listings for a big library, and never into the games' own data.
            val saved = library.listFiles { f -> f.isDirectory }.orEmpty().asSequence().flatMap { game ->
                sequenceOf(File(game, "Saved")) + game.listFiles { f -> f.isDirectory && f.name !in NOT_PROJECTS }.orEmpty()
                    .asSequence().map { File(it, "Saved") }
            }.filter { it.isDirectory }
            take(saved.flatMap { s ->
                File(s, "Logs").listFiles { f -> f.name.endsWith(".log") }.orEmpty().asSequence() +
                    File(s, "Crashes").listFiles().orEmpty().asSequence().flatMap(::unrealCrash)
            }) { f ->
                val path = f.relativeTo(library).invariantSeparatorsPath
                (appIds[path.substringBefore('/')] ?: "added") + "/" + path
            }
        }
        return found
    }

    /* An Unreal crash report folder's text: its logs and CrashContext.runtime-xml, never the .dmp. */
    private fun unrealCrash(dir: File): Sequence<File> =
        dir.listFiles { f -> f.name.endsWith(".log") || f.name == "CrashContext.runtime-xml" }.orEmpty().asSequence()

    /* Install folders to look in, each with its games' app ids by folder name: Steam's own library,
     * the SD card's and the games folders' Steam libraries, and the added games' folders. */
    private fun installLibraries(context: Context, steam: File): List<Pair<File, Map<String, String>>> {
        val steamRoots = mutableListOf(steam)
        val added = mutableListOf<File>()
        runCatching {
            GameStorage.effective(context)?.let { steamRoots += File(it.path) }
            GameStorage.gamesFolderLibraries(context).forEach { steamRoots += it.host }
            com.droiddeck.launcher.frontend.AddedGames.roots(context).forEach { added += it.host }
        }.onFailure { Log.w("SessionLogShare", "game libraries", it) }
        val out = steamRoots.distinctBy { it.absolutePath }.mapNotNull { root ->
            val apps = File(root, "steamapps")
            val common = File(apps, "common").takeIf { it.isDirectory } ?: return@mapNotNull null
            val ids = HashMap<String, String>()
            apps.listFiles { f -> APP_MANIFEST.matches(f.name) }.orEmpty().forEach { m ->
                val dir = runCatching { INSTALL_DIR.find(m.readText())?.groupValues?.get(1) }.getOrNull() ?: return@forEach
                ids[dir] = APP_MANIFEST.matchEntire(m.name)!!.groupValues[1]
            }
            common to ids
        }
        return out + added.filter { it.isDirectory }.distinctBy { it.absolutePath }.map { it to emptyMap() }
    }

    /* Exit-at-start diagnostics (droiddeck-game-env take_exit_diag): the launch after a title closed without a frame writes
     * Proton's log (+loaddll, +seh: large) into <session>/exit-diag/. Each file comes along cut to its last
     * EXIT_DIAG_MAX_BYTES, where the exit is; the cut copies live in the share folder and go with it (pruneShared). */
    private fun exitDiagCut(folder: File, files: List<File>, out: File): Map<File, File> {
        val dir = File(folder, EXIT_DIAG_DIR)
        return files.filter { it.parentFile == dir && it.length() > EXIT_DIAG_MAX_BYTES }
            .associateWith { f -> tail(f, File(out, "$EXIT_DIAG_DIR-${f.name}"), EXIT_DIAG_MAX_BYTES) }
    }

    private fun tail(f: File, to: File, keep: Long = PROTON_LOG_MAX_BYTES): File {
        java.io.RandomAccessFile(f, "r").use { r ->
            r.seek(f.length() - keep)
            r.readLine() // from the next whole line
            to.outputStream().buffered().use { o ->
                o.write("[first ${f.length() - keep} bytes left out]\n".toByteArray())
                val buf = ByteArray(64 * 1024)
                while (true) { val n = r.read(buf); if (n < 0) break; o.write(buf, 0, n) }
            }
        }
        return to
    }

    private val PROTON_LOG = Regex("steam-[0-9]+\\.log")
    private const val GAME_LOGS_MAX = 24
    private val APP_MANIFEST = Regex("appmanifest_([0-9]+)\\.acf")
    private val INSTALL_DIR = Regex("\"installdir\"\\s+\"([^\"]+)\"")
    private val NOT_PROJECTS = setOf("Binaries", "Content", "Engine", "Saved")
    private const val PROTON_LOG_MAX_BYTES = 8L * 1024 * 1024
    private const val STEAM_LOG_MAX_BYTES = 8L * 1024 * 1024
    private const val EXIT_DIAG_DIR = "exit-diag"
    private const val EXIT_DIAG_MAX_BYTES = 4L * 1024 * 1024

    const val STORES_DIR = "stores"
    private const val RECENT_DAYS = 7

    private fun shareDir(context: Context): File = File(context.cacheDir, "share")

    private const val BATTLENET_DIR = "battlenet"

    private fun battleNetLogs(context: Context): List<Pair<String, File>> {
        val drive = File(BattleNet.prefix(context), "pfx/drive_c")
        val data = File(drive, "ProgramData/Battle.net")
        fun logsUnder(dir: File, prefix: String) = dir.listFiles { f -> f.isDirectory && f.name.startsWith(prefix) }.orEmpty().map { File(it, "Logs") }
        return newest("droiddeck", listOf(File(LinuxRuntime.rootDir(context), "root/.cache/droiddeck/battlenet")), 2, setOf("battlenet.log", "spawned.log")) +
            newest("client", listOf(File(drive, "users/steamuser/AppData/Local/Battle.net/Logs")), 6) +
            newest("agent", listOf(File(data, "Agent/Logs")) + logsUnder(File(data, "Agent"), "Agent."), 10, byDir = true) +
            newest("setup", logsUnder(File(data, "Setup"), ""), 3, byDir = true)
    }

    private fun newest(folder: String, dirs: List<File>, count: Int, names: Set<String>? = null, byDir: Boolean = false): List<Pair<String, File>> {
        val since = System.currentTimeMillis() - RECENT_DAYS * 86_400_000L
        return dirs.flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(".log") && (names == null || f.name in names) && f.lastModified() >= since && f.length() <= STEAM_LOG_MAX_BYTES }.orEmpty().toList()
        }.sortedByDescending { it.lastModified() }.take(count).map { f ->
            (if (byDir) "$folder/${f.parentFile?.parentFile?.name}/${f.name}" else "$folder/${f.name}") to f
        }
    }

    private fun recent(dir: File): List<File> {
        val since = System.currentTimeMillis() - RECENT_DAYS * 86_400_000L
        return dir.listFiles { f -> f.isFile && f.lastModified() >= since }?.sortedBy { it.name }.orEmpty()
    }

    /* Zips handed to another app stay until it has had time to read them ("Resource acquisition
     * failed" on HyperOS when the app's next start cleared the folder mid-share): only old ones go. */
    fun clear(context: Context) {
        pruneShared(shareDir(context))
        File(context.cacheDir, "shared-logs").deleteRecursively()
    }

    private fun pruneShared(dir: File) {
        val before = System.currentTimeMillis() - SHARED_KEEP_MS
        dir.listFiles()?.filter { it.lastModified() < before }?.forEach { it.deleteRecursively() }
    }

    private const val SHARED_KEEP_MS = 60L * 60 * 1000

    fun shareIntent(context: Context, zip: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".logs", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, zip.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(zip.name, uri)
        return Intent.createChooser(send, context.getString(R.string.logshare_chooser)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
