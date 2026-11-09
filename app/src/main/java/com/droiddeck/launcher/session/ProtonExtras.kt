package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.R
import android.content.Context
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object ProtonExtras {
    private const val TAG = "ProtonExtras"
    private const val NEED_BYTES = 4L * 1024 * 1024 * 1024
    private const val RELEASES = "https://api.github.com/repos/%s/releases?per_page=15"
    private const val RELEASE_TAG = "https://api.github.com/repos/%s/releases/tags/%s"
    const val CHECK_EVERY_MS = 60L * 60 * 1000
    @Volatile
    var installInProgress = false
        private set
    @Volatile
    private var replacing = false

    class Tool(val id: String, val name: String, val prefix: String, val repo: String, val assetPattern: Regex)

    internal data class Asset(val tag: String, val name: String, val url: String, val sha512: String?, val size: Long,
                             val sha256: String? = null)

    const val OWN = "droiddeck"

    /**
     * The DroidDeck-Proton builds MaliBridge validated on Mali, IN PREFERENCE ORDER: the first is the default, the build the app
     * installs on its own (a fresh install, a player with no validated build) and the guest starts new titles and moves
     * titles on; the others stay validated and selectable (offered as an update in the Proton list, installed when the player
     * asks, never moved to on their own). 11-04: 11-11.10 first, the build our tablets ran (owner decision, release prep
     * T11); 11-11.11 second (player logs only). The app downloads, installs and updates to these
     * only; a newer upstream build is never fetched on its own (11-04: 11-12.1 appeared upstream on 11-03, v0.5.5.2 put every
     * player's games on it and deleted 11-11.x, and the first player who got it lost most of his games). Written to the guest
     * (/etc/droiddeck/proton-validated, see SessionFiles) for steam-compatibility and droiddeck-proton-extra, which carry the
     * same list as a fallback (tools/tests/test_proton_pin.py checks all three). 11-11.10: Tab S11 Ultra 11-04; 11-11.11: 11-11.10
     * plus one 32-bit loader fix, same GE-Proton11-11 base and prefix version, player logs on Mali run games on it.
     */
    val VALIDATED_OWN = listOf("DroidDeck-Proton-11-11.10", "DroidDeck-Proton-11-11.11")
    const val VALIDATED_GUEST_FILE = "etc/droiddeck/proton-validated"

    /** The list in force; null = no pin (the newest upstream build, as before). Tests replace it. */
    @Volatile
    internal var validatedOwn: List<String>? = VALIDATED_OWN

    internal fun isValidated(name: String, validated: List<String>? = validatedOwn): Boolean =
        validated == null || validated.any { versionKey(it) == versionKey(name) }

    /** The newest validated tag, or null when there is no pin. */
    internal fun pinnedLatest(validated: List<String>? = validatedOwn): String? =
        validated?.maxWithOrNull { a, b -> compareVersions(a, b) }

    /** The default validated tag (the first of the list), or null when there is no pin: what the app installs on its own. */
    internal fun pinnedDefault(validated: List<String>? = validatedOwn): String? = validated?.firstOrNull()

    val tools = listOf(
        Tool("ge", "GE-Proton", "GE-Proton", "GloriousEggroll/proton-ge-custom", Regex("aarch64\\.tar\\.(gz|xz)$")),
        Tool("cachyos", "proton-cachyos", "proton-cachyos", "CachyOS/proton-cachyos", Regex("arm64\\.tar\\.(gz|xz)$")),
        Tool("droiddeck", "DroidDeck-Proton", "DroidDeck-Proton", "Droid-Deck/DroidDeck-Proton", Regex("aarch64\\.tar\\.(gz|xz)$")),
    )

    private fun home(context: Context) = File(LinuxRuntime.rootDir(context), "root")
    private fun requests(context: Context) = File(home(context), ".bl-proton-extra")
    private fun toolsDir(context: Context) = File(home(context), ".local/share/Steam/compatibilitytools.d")
    private fun releasesFile(context: Context) = File(context.filesDir, "proton-extras.json")
    private fun latestFile(context: Context) = File(context.filesDir, "proton-latest.json")

    fun installed(context: Context, tool: Tool): String? = installedIn(toolsDir(context), tool).lastOrNull()

    fun installedAll(context: Context, tool: Tool): List<String> = installedIn(toolsDir(context), tool)

    internal fun installedIn(dir: File, tool: Tool): List<String> =
        dir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(tool.prefix) && File(it, "toolmanifest.vdf").isFile }
            ?.map { it.name }
            ?.sortedWith { a, b -> compareVersions(a, b) }
            ?: emptyList()

    private val ARCH = Regex("(?i)aarch64|arm64|x86_64|x86-64|amd64")
    private val NUMBER = Regex("\\d+")

    internal fun versionKey(name: String): List<Long> =
        NUMBER.findAll(ARCH.replace(name, " ")).mapNotNull { it.value.toLongOrNull() }.toList()

    internal fun compareVersions(a: String, b: String): Int {
        val x = versionKey(a)
        val y = versionKey(b)
        for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return x[i].compareTo(y[i])
        return if (x.size != y.size) x.size.compareTo(y.size) else a.compareTo(b)
    }

    internal fun updateFor(installed: List<String>, latest: String?): String? {
        if (latest == null || installed.isEmpty()) return null
        val newest = installed.maxWithOrNull { a, b -> compareVersions(a, b) } ?: return null
        val x = versionKey(latest)
        val y = versionKey(newest)
        for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return if (x[i] > y[i]) latest else null
        return if (x.size > y.size) latest else null
    }

    fun installedTags(context: Context, tool: Tool): List<String> {
        val recorded = readJson(releasesFile(context))
        return installedAll(context, tool).map { recorded.optString(it).ifEmpty { it } }
    }

    fun update(context: Context, tool: Tool): String? {
        val installed = installedTags(context, tool)
        if (tool.id == OWN && validatedOwn != null) return pinnedUpdate(installed)
        return updateFor(installed, cachedLatest(context, tool))
    }

    /** What the app installs on its own. Under the pin, for DroidDeck-Proton only a validated build for a player who has
     *  DroidDeck-Proton builds but none validated (players v0.5.5.2 moved to 11-12.1); a newer validated build is offered in
     *  the Proton list ([update]) and installed when the player asks, next to the one his games are on. */
    fun autoUpdate(context: Context, tool: Tool): String? {
        if (tool.id != OWN || validatedOwn == null) return update(context, tool)
        val installed = installedTags(context, tool)
        return if (installed.isNotEmpty() && installed.none { isValidated(it) }) pinnedDefault() else null
    }

    /** Under the pin: the newest validated build when it is newer than every validated build installed, or the default validated
     *  build when the player has DroidDeck-Proton builds but none of them is validated (it is installed NEXT to them; nothing is
     *  moved or removed). */
    internal fun pinnedUpdate(installed: List<String>, validated: List<String>? = validatedOwn): String? {
        val latest = pinnedLatest(validated) ?: return null
        if (installed.isEmpty()) return null
        val good = installed.filter { isValidated(it, validated) }
        if (good.isEmpty()) return pinnedDefault(validated)
        return updateFor(good, latest)
    }

    fun missing(context: Context, tool: Tool): String? =
        if (tool.id == OWN && !declined(context) && installedAll(context, tool).isEmpty()) cachedLatest(context, tool) else null

    private fun choices(context: Context) = context.getSharedPreferences("proton-auto-updates", Context.MODE_PRIVATE)

    fun declined(context: Context): Boolean = choices(context).getBoolean("$OWN.declined", false)

    fun setDeclined(context: Context, declined: Boolean) {
        choices(context).edit().putBoolean("$OWN.declined", declined).apply()
    }

    fun cachedLatest(context: Context, tool: Tool): String? =
        if (tool.id == OWN && validatedOwn != null) pinnedDefault()
        else readJson(latestFile(context)).optJSONObject(tool.id)?.optString("tag")?.ifEmpty { null }

    private val checkLock = Any()
    private const val RETRY_MS = 15L * 60 * 1000
    private const val USER_SETTINGS = "user_settings.py"
    private var lastAttempt = 0L

    fun checkLatest(context: Context, force: Boolean = false, now: Long = System.currentTimeMillis()): Boolean {
        synchronized(checkLock) {
            val file = latestFile(context)
            val cache = readJson(file)
            val due = tools.filter { tool ->
                if (tool.id == OWN && validatedOwn != null) return@filter false
                val at = cache.optJSONObject(tool.id)?.optLong("at", 0L) ?: 0L
                force || (now - at) !in 0 until CHECK_EVERY_MS
            }
            if (due.isEmpty() || !force && (now - lastAttempt) in 0 until RETRY_MS) return false
            lastAttempt = now
            for (tool in due) {
                val tag = findLatestAsset(tool)?.tag ?: continue
                cache.put(tool.id, JSONObject().put("tag", tag).put("at", now))
            }
            runCatching { file.writeText(cache.toString()) }
            return true
        }
    }

    private fun record(context: Context, tool: Tool, dir: String?, tag: String) {
        runCatching {
            val latest = readJson(latestFile(context))
            val known = latest.optJSONObject(tool.id)?.optString("tag")
            if (known.isNullOrEmpty() || compareVersions(tag, known) >= 0) {
                latest.put(tool.id, JSONObject().put("tag", tag).put("at", System.currentTimeMillis()))
                latestFile(context).writeText(latest.toString())
            }
            if (dir != null) {
                val releases = readJson(releasesFile(context))
                val present = toolsDir(context).list()?.toSet() ?: emptySet()
                releases.keys().asSequence().toList().filter { it !in present }.forEach { releases.remove(it) }
                releasesFile(context).writeText(releases.put(dir, tag).toString())
            }
        }.onFailure { Log.w(TAG, "record ${tool.id} $tag", it) }
    }

    private fun readJson(file: File): JSONObject =
        runCatching { JSONObject(FileUtils.readString(file) ?: "{}") }.getOrDefault(JSONObject())

    fun queued(context: Context, tool: Tool): Boolean =
        requestLines(context).any { it.trim() == tool.id || it.trim().startsWith(tool.id + " ") }

    /** Asks for [tool] through the request file the session script reads at the next session start (droiddeck-proton-extra). */
    fun enqueue(context: Context, tool: Tool) {
        if (queued(context, tool)) return
        val file = requests(context)
        file.parentFile?.mkdirs()
        val line = if (tool.id == OWN) pinnedDefault()?.let { "${tool.id} --tag $it" } ?: tool.id else tool.id
        file.writeText((requestLines(context) + line).joinToString("\n") + "\n")
    }

    fun unqueue(context: Context, tool: Tool) {
        val lines = requestLines(context).filterNot { it.trim() == tool.id || it.trim().startsWith(tool.id + " ") }
        val file = requests(context)
        if (lines.isEmpty()) file.delete()
        else file.writeText(lines.joinToString("\n") + "\n")
    }

    fun remove(context: Context, tool: Tool, dir: String? = null) {
        val name = if (dir != null) dir.takeIf { it in installedAll(context, tool) } ?: return else installed(context, tool) ?: return
        if (File(toolsDir(context), name).deleteRecursively()) ComponentsManager.forgetProton(context, name)
    }

    fun removeOlder(context: Context, tool: Tool) {
        installedAll(context, tool).dropLast(1).forEach {
            if (File(toolsDir(context), it).deleteRecursively()) ComponentsManager.forgetProton(context, it)
        }
    }

    /**
     * After DroidDeck-Proton build [placed] was installed: it takes the DXVK/VKD3D picks and user_settings.py of the build the
     * player used last (when it has none of its own), and older builds that nothing uses any more are removed. Nothing is moved:
     * a game, the default and the app's per-game picks stay on the build they are on (11-04: moving them to a new build broke
     * most of a player's games, and Proton cannot take a prefix back once a newer build upgraded it).
     */
    internal fun settleAfterInstall(context: Context, tool: Tool, placed: String?) {
        val all = installedAll(context, tool)
        val target = placed?.takeIf { it in all } ?: return
        val previous = all.filter { it != target }.maxWithOrNull { a, b -> compareVersions(a, b) }
        if (previous != null) {
            runCatching { ComponentsManager.carryOver(context, previous, target) }.onFailure { Log.w(TAG, "components of $previous", it) }
            runCatching { keepUserSettings(context, previous, target) }.onFailure { Log.w(TAG, "user settings of $previous", it) }
        }
        tidy(context)
    }

    /** Builds of [tool] that may go: not the newest installed, not the newest validated one, not the default one, and used by nothing. */
    internal fun removable(context: Context, tool: Tool): List<String> {
        val all = installedAll(context, tool)
        if (all.size < 2) return emptyList()
        val default = pinnedDefault()
        val keep = setOfNotNull(all.lastOrNull(), all.filter { isValidated(it) }.lastOrNull(),
            default?.let { d -> all.lastOrNull { versionKey(it) == versionKey(d) } })
        val used = BuildUse.read(LinuxRuntime.rootDir(context))
        return all.filter { it !in keep && !used.uses(it, internalName(File(toolsDir(context), it))) }
    }

    private val INTERNAL_NAME = Regex("\"compat_tools\"\\s*\\{\\s*\"([^\"]+)\"")

    /** The name Steam knows the tool by (compatibilitytool.vdf's key), or null when it cannot be read. */
    internal fun internalName(dir: File): String? =
        runCatching { INTERNAL_NAME.find(File(dir, "compatibilitytool.vdf").readText())?.groupValues?.get(1) }.getOrNull()

    /** What uses a Proton build: Steam's mapping (config.vdf, every game and the default), steam-compatibility's default and
     *  moves, and the app's own picks (request.json, games.json). Anything that cannot be read counts as "in use". */
    internal class BuildUse(private val texts: List<String>, private val unreadable: Boolean) {
        fun uses(dir: String, internal: String?): Boolean {
            if (unreadable || internal == null) return true
            return texts.any { text -> text.contains("\"$internal\"") || text.contains("\"$dir\"") }
        }

        companion object {
            fun read(root: File): BuildUse {
                val home = File(root, "root")
                val config = File(home, ".local/share/Steam/config/config.vdf")
                val compat = File(home, ".local/share/droiddeck-compat")
                val texts = mutableListOf<String>()
                var unreadable = false
                if (config.exists()) {
                    val text = runCatching { config.readText() }.getOrNull()
                    if (text == null) unreadable = true
                    else Regex("\"CompatToolMapping\"[\\s\\S]*").find(text)?.value?.let { texts.add(it) }
                }
                for (name in listOf("request.json", "games.json", "known-games.json")) {
                    val file = File(compat, name)
                    if (!file.exists()) continue
                    val text = runCatching { file.readText() }.getOrNull()
                    if (text == null) unreadable = true else texts.add(text)
                }
                File(compat, "state.json").takeIf { it.exists() }?.let { file ->
                    val state = runCatching { JSONObject(file.readText()) }.getOrNull()
                    if (state == null) unreadable = true
                    else {
                        // "tools" lists every installed tool and "own_builds" every build ever seen: those say nothing about use.
                        for (key in listOf("default", "auto", "fallbacks", "pending_fallbacks", "new_title_tools", "chosen")) {
                            state.opt(key)?.let { texts.add(JSONObject().put(key, it).toString()) }
                        }
                    }
                }
                return BuildUse(texts, unreadable)
            }
        }
    }

    private fun keepUserSettings(context: Context, old: String, newest: String) {
        val from = File(File(toolsDir(context), old), USER_SETTINGS)
        val to = File(File(toolsDir(context), newest), USER_SETTINGS)
        if (from.isFile && !to.exists()) from.copyTo(to)
    }

    /** Removes DroidDeck-Proton builds that nothing uses (see [removable]); a build a game, the default or a pick is on stays. */
    fun tidy(context: Context) {
        val tool = tools.firstOrNull { it.id == OWN } ?: return
        val gone = removable(context, tool).takeIf { it.isNotEmpty() } ?: return
        replacing = true
        try {
            for (old in gone) {
                if (SessionState.running) break
                if (File(toolsDir(context), old).deleteRecursively()) ComponentsManager.forgetProton(context, old)
                else Log.w(TAG, "could not remove $old")
            }
        } finally {
            replacing = false
        }
    }

    fun tidyNeeded(context: Context): Boolean = tools.firstOrNull { it.id == OWN }?.let { removable(context, it).isNotEmpty() } ?: false

    fun awaitReplacement(timeoutMs: Long) {
        val until = System.currentTimeMillis() + timeoutMs
        while (replacing && System.currentTimeMillis() < until) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
        }
    }

    @Synchronized
    /** [want]: the tag to install (an update the player tapped, or what [autoUpdate] / [missing] named); under the pin a tag
     *  that is not validated is ignored. Null: the default validated build first. */
    fun install(context: Context, tool: Tool, onProgress: (String, Int) -> Unit, want: String? = null): String? {
        if (installInProgress) return context.getString(R.string.pextra_busy)
        installInProgress = true
        return try { installNow(context, tool, onProgress, want) } finally { installInProgress = false }
    }

    private fun installNow(context: Context, tool: Tool, onProgress: (String, Int) -> Unit, want: String?): String? {
        if (SessionState.running) return context.getString(R.string.pextra_stop_session)
        if (!LinuxRuntime.isInstalled(context)) return context.getString(R.string.user_apps_runtime_required)

        val asset = findLatestAsset(tool, want) ?: return context.getString(R.string.pextra_no_release, tool.name)
        val downloads = File(context.filesDir, "proton-downloads").apply { mkdirs() }
        val archive = File(downloads, "${tool.id}-${asset.name}")
        if (asset.size > 0 && archive.length() > asset.size) archive.delete()
        val missing = (asset.size - archive.length()).coerceAtLeast(0L)
        if (StatFs(LinuxRuntime.rootDir(context).path).availableBytes < NEED_BYTES + missing) {
            return context.getString(R.string.pextra_no_space, tool.name)
        }

        val downloading = context.getString(R.string.pextra_downloading, tool.name, asset.tag)
        onProgress(downloading, 0)
        var downloaded = false
        for (attempt in 0 until 5) {
            downloaded = Downloader.downloadFile(asset.url, archive, true, asset.sha256) { fraction ->
                onProgress(downloading, if (fraction < 0) -1 else (fraction * 100f).toInt().coerceIn(0, 100))
            }
            if (downloaded) break
            if (attempt < 4) {
                onProgress(context.getString(R.string.pextra_retrying, attempt + 2, 5), -1)
                try { Thread.sleep(3_000) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return context.getString(R.string.pextra_interrupted)
                }
            }
        }
        if (!downloaded) return context.getString(R.string.pextra_download_failed)
        if (asset.size > 0 && archive.length() != asset.size) {
            if (archive.length() > asset.size) {
                archive.delete()
                return context.getString(R.string.pextra_too_big)
            }
            return context.getString(R.string.pextra_incomplete)
        }

        onProgress(context.getString(R.string.pextra_verifying), -1)
        val verified = when {
            asset.sha256 != null -> asset.sha256.equals(Hashes.sha256(archive), ignoreCase = true)
            asset.sha512 != null -> {
                val expected = Downloader.downloadDirect(asset.sha512)?.let { Regex("(?i)\\b[0-9a-f]{128}\\b").find(it)?.value }
                    ?: return context.getString(R.string.pextra_checksum_unreadable)
                expected.equals(Hashes.sha512(archive), ignoreCase = true)
            }
            else -> {
                archive.delete()
                return context.getString(R.string.pextra_no_checksum, tool.name, asset.tag)
            }
        }
        if (!verified) {
            archive.delete()
            return context.getString(R.string.pextra_checksum_mismatch)
        }

        if (SessionState.running) return context.getString(R.string.pextra_session_started)
        val installing = context.getString(R.string.pextra_installing, tool.name)
        onProgress(installing, -1)
        return try {
            val root = LinuxRuntime.rootDir(context)
            LinuxRuntime.writeAccounts(context)
            com.droiddeck.launcher.session.SessionFiles.stage(context, root)
            val runtimeDir = File(context.filesDir, ".proton-install-rt").apply { mkdirs() }
            val guest = listOf(
                "/usr/bin/env", "-i", "HOME=/root", "USER=root", "PATH=/usr/local/bin:/usr/bin:/bin",
                "LANG=C.UTF-8", "XDG_DATA_HOME=/root/.local/share",
                "/usr/local/bin/droiddeck-proton-extra", "/root/.local/share/Steam", archive.absolutePath,
            )
            val command = LinuxRuntime.command(context, null, runtimeDir, null, guest)
            val process = ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
            val hostEnv = process.environment()
            hostEnv["PROOT_LOADER"] = LinuxRuntime.prootLoader(context).path
            hostEnv["PROOT_TMP_DIR"] = LinuxRuntime.prootTempDir(context).path
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { hostEnv["LD_LIBRARY_PATH"] = it }
            val child = process.start()
            var placed: String? = null
            child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    Log.i(TAG, line)
                    line.substringAfter("droiddeck-proton-extra: installed ", "").takeIf { it.isNotEmpty() }?.let { placed = it }
                    if (line.contains("unpacking", ignoreCase = true) || line.contains("adopted", ignoreCase = true)) {
                        onProgress(installing, -1)
                    }
                }
            }
            val status = child.waitFor()
            if (status != 0) context.getString(R.string.pextra_install_failed, tool.name, status)
            else {
                archive.delete()
                unqueue(context, tool)
                record(context, tool, placed, asset.tag)
                if (tool.id == OWN) settleAfterInstall(context, tool, placed)
                if (EsyncPacks.enabled(context)) {
                    onProgress(context.getString(R.string.esync_fetching), -1)
                    try {
                        EsyncPacks.fetchWanted(context, root, onProgress)
                    } catch (t: Throwable) {
                        Log.w(TAG, "sync pack for ${tool.name}", t)
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "install ${tool.id}", e)
            e.message ?: context.getString(R.string.pextra_could_not_install, tool.name)
        }
    }

    /** Under the pin, the tags tried in turn (a release can vanish): [want] when it is validated, then the list in its order. */
    internal fun pinnedTries(want: String?, pinned: List<String>): List<String> =
        (listOfNotNull(want?.let { w -> pinned.firstOrNull { versionKey(it) == versionKey(w) } }) + pinned).distinct()

    private fun findLatestAsset(tool: Tool, want: String? = null): Asset? {
        val pinned = validatedOwn
        if (tool.id == OWN && pinned != null) {
            for (tag in pinnedTries(want, pinned)) {
                val body = Downloader.downloadString(RELEASE_TAG.format(tool.repo, tag)) ?: continue
                val asset = runCatching { pickAsset(tool, JSONArray().put(JSONObject(body)), pinned) }
                    .onFailure { Log.w(TAG, "release metadata for $tag", it) }.getOrNull()
                if (asset != null) return asset
            }
            return null
        }
        val releases = Downloader.downloadString(RELEASES.format(tool.repo)) ?: return null
        return try {
            pickAsset(tool, JSONArray(releases))
        } catch (e: Exception) {
            Log.w(TAG, "release metadata for ${tool.id}", e)
            null
        }
    }

    internal fun pickAsset(tool: Tool, array: JSONArray, validated: List<String>? = if (tool.id == OWN) validatedOwn else null): Asset? {
        for (i in 0 until array.length()) {
            val release = array.getJSONObject(i)
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) continue
            if (validated != null && !isValidated(release.optString("tag_name"), validated)) continue
            val assets = release.optJSONArray("assets") ?: continue
            val entries = (0 until assets.length()).map { assets.getJSONObject(it) }
            val archive = entries.sortedBy { it.optString("name") }.firstOrNull { tool.assetPattern.containsMatchIn(it.optString("name")) } ?: continue
            val name = archive.optString("name")
            val stem = name.substringBefore(".tar")
            val checksum = entries.firstOrNull {
                it.optString("name").startsWith(stem) && it.optString("name").contains("sha512", ignoreCase = true)
            }?.optString("browser_download_url")?.takeIf { it.startsWith("http") }
            return Asset(
                release.optString("tag_name"), name,
                archive.optString("browser_download_url").takeIf { it.startsWith("http") } ?: continue, checksum,
                archive.optLong("size", 0L),
                Hashes.githubSha256(archive.optString("digest")),
            )
        }
        return null
    }

    private fun requestLines(context: Context): List<String> =
        FileUtils.readString(requests(context))?.lines()?.filter { it.isNotBlank() } ?: emptyList()
}
