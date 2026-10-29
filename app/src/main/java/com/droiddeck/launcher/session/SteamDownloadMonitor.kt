package com.droiddeck.launcher.session

import java.io.File
import java.io.RandomAccessFile

internal class SteamDownloadMonitor {
    private data class ManifestState(
        val bytesDownloaded: Long,
        val incomplete: Boolean,
    )

    private var previous = emptyMap<String, ManifestState>()
    private var lastProgressAt = 0L
    private var contentLogOffset = 0L
    private val activeContentUpdates = mutableSetOf<String>()
    private var lastContentActivityAt = 0L
    private var lastReceivedBytes = -1L
    private var lastReceivedAt = 0L
    private var lastNetworkAt = 0L
    private var bootstrapLogLength = UNREAD
    private var lastBootstrapGrowthAt = 0L

    fun poll(runtimeRoot: File, now: Long = System.currentTimeMillis(), receivedBytes: Long = -1L): Boolean {
        val current = manifests(runtimeRoot).associate { it.path to read(it) }
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
        return manifestActive || readContentActivity(runtimeRoot, now)
    }

    private fun receiving(receivedBytes: Long, now: Long): Boolean {
        val previousBytes = lastReceivedBytes
        val previousAt = lastReceivedAt
        lastReceivedBytes = receivedBytes
        lastReceivedAt = now
        if (receivedBytes < 0L || previousBytes < 0L || receivedBytes < previousBytes || now <= previousAt) return false
        return (receivedBytes - previousBytes) * 1000L / (now - previousAt) >= MIN_RECEIVE_BYTES_PER_SEC
    }

    // The client's own first-run update (several hundred MB) is not an app download, so the manifests and
    // content_log never show it. It counts as active while the app receives data or Steam's bootstrap log
    // grows; with network = false only the log counts (after the first frame, when the received data may
    // be the store or a game's download as well). Call after poll(), which samples the received bytes.
    fun clientUpdateActive(runtimeRoot: File, now: Long = System.currentTimeMillis(), network: Boolean = true): Boolean {
        val log = File(runtimeRoot, "root/.local/share/Steam/logs/bootstrap_log.txt")
        val length = if (log.isFile) log.length() else -1L
        if (bootstrapLogLength != UNREAD && length > bootstrapLogLength) lastBootstrapGrowthAt = now
        bootstrapLogLength = length
        return network && lastNetworkAt > 0L && now - lastNetworkAt <= CLIENT_UPDATE_GRACE_MS ||
            lastBootstrapGrowthAt > 0L && now - lastBootstrapGrowthAt <= CLIENT_UPDATE_GRACE_MS
    }

    fun reset() {
        previous = emptyMap()
        lastProgressAt = 0L
        contentLogOffset = 0L
        activeContentUpdates.clear()
        lastContentActivityAt = 0L
        lastReceivedBytes = -1L
        lastReceivedAt = 0L
        lastNetworkAt = 0L
        bootstrapLogLength = UNREAD
        lastBootstrapGrowthAt = 0L
    }

    private fun readContentActivity(runtimeRoot: File, now: Long): Boolean {
        val log = File(runtimeRoot, "root/.local/share/Steam/logs/content_log.txt")
        if (!log.isFile) return false
        if (log.length() < contentLogOffset) {
            contentLogOffset = 0L
            activeContentUpdates.clear()
            lastContentActivityAt = 0L
        }
        runCatching {
            RandomAccessFile(log, "r").use { input ->
                input.seek(contentLogOffset)
                while (true) {
                    val line = input.readLine() ?: break
                    when {
                        "Client version:" in line -> {
                            activeContentUpdates.clear()
                            lastContentActivityAt = 0L
                        }
                        CONTENT_UPDATE.find(line)?.let { match ->
                            val appId = match.groupValues[1]
                            val updateType = match.groupValues[2]
                            val state = match.groupValues[3]
                            val updateId = "$updateType:$appId"
                            if ("Running Update" in state && "Stopping" !in state) {
                                activeContentUpdates += updateId
                                lastContentActivityAt = now
                            } else {
                                activeContentUpdates -= updateId
                            }
                            true
                        } == true -> Unit
                        CURRENT_RATE.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { it > 0.0 } == true ->
                            lastContentActivityAt = now
                        "Downloading " in line && " chunks for depot " in line ->
                            lastContentActivityAt = now
                    }
                    if (activeContentUpdates.isNotEmpty()) lastContentActivityAt = now
                }
                contentLogOffset = input.filePointer
            }
        }
        return activeContentUpdates.isNotEmpty() && lastContentActivityAt > 0L &&
            now - lastContentActivityAt <= CONTENT_ACTIVITY_GRACE_MS
    }

    private fun manifests(runtimeRoot: File): List<File> {
        val steamRoots = linkedSetOf(File(runtimeRoot, "root/.local/share/Steam"))
        val primary = steamRoots.first()
        val libraryFolders = File(primary, "steamapps/libraryfolders.vdf")
        runCatching {
            PATH.findAll(libraryFolders.readText()).forEach { match ->
                steamRoots += File(runtimeRoot, match.groupValues[1].removePrefix("/"))
            }
        }
        return steamRoots.flatMap { root ->
            File(root, "steamapps").listFiles { file ->
                file.isFile && file.name.startsWith("appmanifest_") && file.name.endsWith(".acf")
            }.orEmpty().toList()
        }
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
        /** Whether the session notification says Steam is downloading its update: the start still counts as an
         * update, data or the bootstrap log is still coming, and the update's own download progress was seen in
         * the session log (LoadingState.steamUpdateDownloading). Without the last, every start said so for ~100 s. */
        fun clientUpdateShown(updating: Boolean, active: Boolean, progressSeen: Boolean) = updating && active && progressSeen

        const val MANIFEST_PROGRESS_GRACE_MS = 8_000L
        const val CONTENT_ACTIVITY_GRACE_MS = 90_000L
        const val CLIENT_UPDATE_GRACE_MS = 90_000L
        const val UNREAD = Long.MIN_VALUE
        const val MIN_RECEIVE_BYTES_PER_SEC = 64L * 1024L
        val PATH = Regex("\"path\"\\s+\"([^\"]+)\"")
        val CONTENT_UPDATE = Regex("""AppID (\d+) (App|Workshop|Shader) update changed : (.*)""")
        val CURRENT_RATE = Regex("""Current download rate: ([\d.]+) Mbps""")
    }
}
