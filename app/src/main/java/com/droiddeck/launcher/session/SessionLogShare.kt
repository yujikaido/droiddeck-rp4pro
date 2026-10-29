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
        val files = folder.walkTopDown().filter { it.isFile }
            .filterNot { it.parentFile?.name == "steam" && it.length() > STEAM_LOG_MAX_BYTES }.toList()
        if (files.isEmpty()) return null
        val out = shareDir(context).apply { mkdirs() }
        pruneShared(out)
        val zip = File(out, "DroidDeck-${folder.name}.zip")
        val part = File(out, zip.name + ".part")
        LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        val live = liveSteamLogs(context, folder)
        val proton = protonLogs(context, files, out)
        val game = gameLogs(context, files, out)
        val logs = LinuxRuntime.logDir(context)
        val stores = recent(File(logs, STORES_DIR))
        val tools = recent(File(logs, SessionPaths.TOOLS_DIR))
        val total = (files + live + proton + stores + tools + game.map { it.first }).sumOf { it.length() }.coerceAtLeast(1L)
        var done = 0L
        var shown = -1
        fun advance(f: File) {
            done += f.length()
            val percent = (done * 100 / total).toInt()
            if (percent != shown) { shown = percent; onProgress?.invoke(percent / 100f) }
        }
        ZipOutputStream(part.outputStream().buffered()).use { z ->
            files.forEach { f -> addEntry(z, folder.name + "/" + f.relativeTo(folder).path, f); advance(f) }
            live.forEach { f -> addEntry(z, folder.name + "/steam/" + f.name, f); advance(f) }
            proton.forEach { f -> addEntry(z, folder.name + "/proton/" + f.name, f); advance(f) }
            game.forEach { (f, name) -> addEntry(z, folder.name + "/game/" + name, f); advance(f) }
            stores.forEach { f -> addEntry(z, "$STORES_DIR/" + f.name, f); advance(f) }
            tools.forEach { f -> addEntry(z, SessionPaths.TOOLS_DIR + "/" + f.name, f); advance(f) }
        }
        /* Renamed into place: an app still reading the zip shared before keeps the copy it opened. */
        if (!part.renameTo(zip)) { part.delete(); return null }
        return zip
    }

    private fun addEntry(z: ZipOutputStream, name: String, f: File) {
        z.putNextEntry(ZipEntry(name))
        if (LogRedactor.isText(f)) {
            val w = z.bufferedWriter()
            LogRedactor.scrubForShare(f, w)
            w.flush()
        } else {
            f.inputStream().use { it.copyTo(z) }
        }
        z.closeEntry()
    }

    private fun liveSteamLogs(context: Context, folder: File): List<File> {
        if (folder != SessionPaths.current() || File(folder, "steam").exists()) return emptyList()
        val logs = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/logs")
        return logs.listFiles()?.filter { it.isFile && it.length() <= STEAM_LOG_MAX_BYTES }.orEmpty()
    }

    /* Proton's own log (PROTON_LOG=1, the game options' toggle) lands in the guest's home as
     * steam-<appid>.log and is rewritten at each launch: the ones written while this session ran come
     * along, the last PROTON_LOG_MAX_BYTES of a longer one, so a report doesn't need a second ask. */
    private fun protonLogs(context: Context, files: List<File>, out: File): List<File> {
        val from = files.minOfOrNull { it.lastModified() } ?: return emptyList()
        val to = files.maxOf { it.lastModified() }
        val home = File(LinuxRuntime.rootDir(context), "root")
        val logs = home.listFiles { f -> f.isFile && PROTON_LOG.matches(f.name) && f.lastModified() in from..to }.orEmpty()
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
        val steam = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam")
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
        for (prefix in File(steam, "steamapps/compatdata").listFiles().orEmpty()) {
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
            ).flatten()) { f -> prefix.name + "/" + f.relativeTo(if (f.startsWith(appData)) appData else user).path }
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
                val path = f.relativeTo(library).path
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

    private fun tail(f: File, to: File): File {
        java.io.RandomAccessFile(f, "r").use { r ->
            r.seek(f.length() - PROTON_LOG_MAX_BYTES)
            r.readLine() // from the next whole line
            to.outputStream().buffered().use { o ->
                o.write("[first ${f.length() - PROTON_LOG_MAX_BYTES} bytes left out]\n".toByteArray())
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

    const val STORES_DIR = "stores"
    private const val RECENT_DAYS = 7

    private fun shareDir(context: Context): File = File(context.cacheDir, "share")

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
