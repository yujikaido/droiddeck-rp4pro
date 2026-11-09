package com.droiddeck.launcher.session

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

internal class SteamDownloadMonitor {
    private data class ManifestState(
        val bytesDownloaded: Long,
        val incomplete: Boolean,
    )

    private var previous = emptyMap<String, ManifestState>()
    private var lastProgressAt = 0L
    private var contentLogOffset = 0L
    private var contentLog: File? = null
    private val activeContentUpdates = mutableSetOf<String>()
    // Steps that only work the disk (reserving space, committing, verifying, moving a game) log nothing until they
    // end, for many minutes on an SD card: they count from the line that started them, not from the latest line.
    private val diskStepsSince = mutableMapOf<String, Long>()
    private var lastContentActivityAt = 0L
    private var lastReceivedBytes = -1L
    private var lastReceivedAt = 0L
    private var lastNetworkAt = 0L
    private var bootstrapLogLength = UNREAD
    private var lastBootstrapGrowthAt = 0L
    private var installCheckedLength = UNREAD
    private var installing = false
    private var installingSince = 0L

    fun poll(runtimeRoot: File, now: Long = System.currentTimeMillis(), receivedBytes: Long = -1L, client: String? = null,
             mounts: Map<String, File> = emptyMap()): Boolean {
        val current = manifests(runtimeRoot, mounts).associate { it.path to read(it) }
        val progressed = current.any { (path, state) ->
            state.incomplete && state.bytesDownloaded > (previous[path]?.bytesDownloaded ?: state.bytesDownloaded)
        }
        if (progressed) lastProgressAt = now
        previous = current
        val manifestActive = progressed || lastProgressAt > 0L && now - lastProgressAt <= MANIFEST_PROGRESS_GRACE_MS &&
            current.values.any { it.incomplete }
        if (receiving(receivedBytes, now)) {
            lastContentActivityAt = now
            lastNetworkAt = now
        }
        return manifestActive || readContentActivity(runtimeRoot, now, client)
    }

    private fun receiving(receivedBytes: Long, now: Long): Boolean {
        val previousBytes = lastReceivedBytes
        val previousAt = lastReceivedAt
        lastReceivedBytes = receivedBytes
        lastReceivedAt = now
        if (receivedBytes < 0L || previousBytes < 0L || receivedBytes < previousBytes || now <= previousAt) return false
        return (receivedBytes - previousBytes) * 1000L / (now - previousAt) >= MIN_RECEIVE_BYTES_PER_SEC
    }

    // The client's own update after its install (tens of MB) is not an app download, so the manifests and
    // content_log never show it. It counts as active while the app receives data or Steam's bootstrap log grows;
    // with network = false only the log counts (after the first frame the data may be a game's). Call after poll.
    // It also counts while the updater is installing what it downloaded (installingUpdate), for up to
    // CLIENT_INSTALL_MAX_MS: that step neither receives data nor writes the log.
    fun clientUpdateActive(runtimeRoot: File, now: Long = System.currentTimeMillis(), network: Boolean = true): Boolean {
        val log = File(runtimeRoot, "root/.local/share/Steam/logs/bootstrap_log.txt")
        val length = if (log.isFile) log.length() else -1L
        if (bootstrapLogLength != UNREAD && length > bootstrapLogLength) lastBootstrapGrowthAt = now
        bootstrapLogLength = length
        if (length != installCheckedLength) {
            installCheckedLength = length
            installing = length > 0L && installingUpdate(tail(log, length))
        }
        if (!installing) installingSince = 0L else if (installingSince == 0L) installingSince = now
        return network && lastNetworkAt > 0L && now - lastNetworkAt <= CLIENT_UPDATE_GRACE_MS ||
            lastBootstrapGrowthAt > 0L && now - lastBootstrapGrowthAt <= CLIENT_UPDATE_GRACE_MS ||
            installing && now - installingSince <= CLIENT_INSTALL_MAX_MS
    }

    private fun tail(log: File, length: Long): String = runCatching {
        RandomAccessFile(log, "r").use { input ->
            val start = maxOf(0L, length - BOOTSTRAP_TAIL_BYTES)
            val bytes = ByteArray((length - start).toInt())
            input.seek(start)
            input.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrDefault("")

    fun reset() {
        lastNetworkAt = 0L
        bootstrapLogLength = UNREAD
        lastBootstrapGrowthAt = 0L
        previous = emptyMap()
        lastProgressAt = 0L
        contentLogOffset = 0L
        contentLog = null
        activeContentUpdates.clear()
        diskStepsSince.clear()
        lastContentActivityAt = 0L
        lastReceivedBytes = -1L
        lastReceivedAt = 0L
        installCheckedLength = UNREAD
        installing = false
        installingSince = 0L
    }

    private fun readContentActivity(runtimeRoot: File, now: Long, client: String?): Boolean {
        val homes = client?.let { listOf(if (it == "x86_64") STEAM_HOMES[1] else STEAM_HOMES[0]) } ?: STEAM_HOMES
        val log = homes.map { File(runtimeRoot, "$it/logs/content_log.txt") }.maxByOrNull { it.lastModified() }!!
        if (!log.isFile) return false
        if (log != contentLog || log.length() < contentLogOffset) {
            contentLog = log
            contentLogOffset = 0L
            activeContentUpdates.clear()
            lastContentActivityAt = 0L
        }
        runCatching {
            FileInputStream(log).use { input ->
                input.channel.position(contentLogOffset)
                val reader = input.bufferedReader(Charsets.ISO_8859_1)
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        "Client version:" in line -> {
                            activeContentUpdates.clear()
                            diskStepsSince.clear()
                            lastContentActivityAt = 0L
                        }
                        " update changed : " in line && CONTENT_UPDATE.find(line)?.let { match ->
                            val appId = match.groupValues[1]
                            val updateType = match.groupValues[2]
                            val state = match.groupValues[3]
                            val updateId = "$updateType:$appId"
                            if ("Running Update" in state && "Stopping" !in state) {
                                activeContentUpdates += updateId
                                lastContentActivityAt = now
                                if (DISK_STEPS.any { it in state }) diskStepsSince.getOrPut(updateId) { now }
                                else diskStepsSince -= updateId
                            } else {
                                activeContentUpdates -= updateId
                                diskStepsSince -= updateId
                            }
                            true
                        } == true -> Unit
                        APP_STATE.find(line)?.let { match ->
                            val moveId = "Move:${match.groupValues[1]}"
                            if ("Moving Folder" in match.groupValues[2]) {
                                activeContentUpdates += moveId
                                lastContentActivityAt = now
                                diskStepsSince.getOrPut(moveId) { now }
                            } else {
                                activeContentUpdates -= moveId
                                diskStepsSince -= moveId
                            }
                            true
                        } == true -> Unit
                        "Current download rate: " in line &&
                            CURRENT_RATE.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { it > 0.0 } == true ->
                            lastContentActivityAt = now
                        "Downloading " in line && " chunks for depot " in line ->
                            lastContentActivityAt = now
                    }
                    if (activeContentUpdates.isNotEmpty()) lastContentActivityAt = now
                }
                contentLogOffset = input.channel.position()
            }
        }
        return activeContentUpdates.isNotEmpty() && lastContentActivityAt > 0L &&
            now - lastContentActivityAt <= CONTENT_ACTIVITY_GRACE_MS ||
            diskStepsSince.values.any { now - it <= DISK_STEP_MAX_MS }
    }

    private fun manifests(runtimeRoot: File, mounts: Map<String, File>): List<File> {
        val steamRoots = linkedSetOf<File>()
        for (home in STEAM_HOMES) {
            val primary = File(runtimeRoot, home)
            steamRoots += primary
            val libraryFolders = File(primary, "steamapps/libraryfolders.vdf")
            runCatching {
                PATH.findAll(libraryFolders.readText()).forEach { match ->
                    steamRoots += hostPath(runtimeRoot, match.groupValues[1], mounts)
                }
            }
        }
        return steamRoots.flatMap { root ->
            File(root, "steamapps").listFiles { file ->
                file.isFile && file.name.startsWith("appmanifest_") && file.name.endsWith(".acf")
            }.orEmpty().toList()
        }
    }

    private fun hostPath(runtimeRoot: File, guest: String, mounts: Map<String, File>): File {
        val path = guest.trimEnd('/')
        for ((mount, host) in mounts) {
            if (path == mount) return host
            if (path.startsWith("$mount/")) return File(host, path.removePrefix("$mount/"))
        }
        return File(runtimeRoot, path.removePrefix("/"))
    }

    private fun read(file: File): ManifestState {
        val text = runCatching { file.readText() }.getOrDefault("")
        val flags = value(text, "StateFlags")?.toLongOrNull() ?: 0L
        val downloaded = value(text, "BytesDownloaded")?.toLongOrNull() ?: 0L
        val total = value(text, "BytesToDownload")?.toLongOrNull() ?: 0L
        return ManifestState(downloaded, flags != 4L && (total <= 0L || downloaded < total))
    }

    private fun value(text: String, key: String): String? =
        Regex("\"$key\"\\s+\"([^\"]*)\"").find(text)?.groupValues?.get(1)

    companion object {
        /** Whether the notification says Steam is downloading its update: still counted as one, data or the
         * bootstrap log still coming, and the update's own progress seen in the session log. */
        fun clientUpdateShown(updating: Boolean, active: Boolean, progressSeen: Boolean) = updating && active && progressSeen

        const val CLIENT_UPDATE_GRACE_MS = 90_000L
        const val CLIENT_INSTALL_MAX_MS = 15L * 60L * 1000L
        const val BOOTSTRAP_TAIL_BYTES = 128L * 1024L
        const val UNREAD = Long.MIN_VALUE
        /** Whether the updater, in its latest start in this bootstrap log text, has begun unpacking and installing
         * a client update and not yet shut down to start it. Both lines are untranslated, so this holds in any
         * Steam language. The install writes nothing until it ends (7 s on one phone; far longer when the
         * phone is slow or the updater window is held up), so without this a background session was paused with it
         * half done once the log had been quiet for CLIENT_UPDATE_GRACE_MS. */
        fun installingUpdate(text: String): Boolean {
            val session = text.substring(maxOf(0, text.lastIndexOf("] Startup - ")))
            val unpack = session.lastIndexOf("uninstalled manifest found")
            return unpack >= 0 && session.indexOf("] Shutdown", unpack) < 0
        }

        fun clientInstallPending(runtimeRoot: File): Boolean {
            val home = File(runtimeRoot, "root")
            val steam = File(home, ".local/share/Steam")
            val stamped = File(steam, "package/droiddeck-installed").isFile ||
                File(steam, "package/bannerlator-installed").isFile
            return !(stamped && File(steam, "steamrtarm64/steam").isFile) || File(home, ".bl-steam-repair").exists()
        }

        private const val MANIFEST_PROGRESS_GRACE_MS = 8_000L
        private const val CONTENT_ACTIVITY_GRACE_MS = 90_000L
        const val DISK_STEP_MAX_MS = 3L * 60L * 60L * 1000L
        val DISK_STEPS = listOf("Preallocating", "Committing", "Verifying", "Validating")
        private val APP_STATE = Regex("""AppID (\d+) state changed : (.*)""")
        private const val MIN_RECEIVE_BYTES_PER_SEC = 64L * 1024L
        private val PATH = Regex("\"path\"\\s+\"([^\"]+)\"")
        private val STEAM_HOMES = listOf("root/.local/share/Steam", "root/.droiddeck-x64/.local/share/Steam")
        private val CONTENT_UPDATE = Regex("""AppID (\d+) (App|Workshop|Shader) update changed : (.*)""")
        private val CURRENT_RATE = Regex("""Current download rate: ([\d.]+) Mbps""")
    }
}
