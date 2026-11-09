package com.droiddeck.launcher.session

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

class LoadingState(context: Context, private val steam: Boolean = true) {
    var visible by mutableStateOf(true)
    var step by mutableStateOf(context.getString(com.droiddeck.launcher.R.string.loadstate_starting))
        private set
    var topic by mutableStateOf(Topic.SESSION)
        private set
    var readable by mutableStateOf(false)
        private set
    var percent by mutableIntStateOf(-1)
    var elapsed by mutableStateOf("")
    var hint by mutableStateOf("")
    var ended by mutableStateOf(false)
    var endedDetail by mutableStateOf<String?>(null)
    /** The first-frame watchdog's line: which stage of the start stalled (FirstFrameWatch), or null. */
    var diagnosis by mutableStateOf<String?>(null)

    private val hints = context.resources.getStringArray(
        if (steam) com.droiddeck.launcher.R.array.loading_hints else com.droiddeck.launcher.R.array.loading_hints_desktop,
    )
    private val res = context.resources
    private val startedAt = SystemClock.elapsedRealtime()

    fun tick() {
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        elapsed = res.getString(com.droiddeck.launcher.R.string.loadstate_elapsed, seconds / 60, seconds % 60)
        hint = hints[((seconds / 8) % hints.size).toInt()]
    }

    fun say(line: String, topic: Topic, readable: Boolean) {
        step = line
        this.topic = topic
        this.readable = readable
    }

    fun showEnded(message: String, detail: String? = null) {
        visible = true
        ended = true
        step = message
        endedDetail = detail
    }

    private var installingAt = 0L

    fun update(context: Context, log: File?) {
        if (steam && !ended && SteamRepair.updateInstalling(SteamRepair.bootstrapLog(context, SessionPrefs.activeSteamClientArch(context)))) {
            val now = SystemClock.elapsedRealtime()
            if (installingAt == 0L) installingAt = now
            val seconds = (now - installingAt) / 1000
            step = res.getString(com.droiddeck.launcher.R.string.loadstate_steam_installing, seconds / 60, seconds % 60)
            topic = Topic.STEAM
            readable = true
            percent = -1
            return
        }
        installingAt = 0L
        val state = read(context, log) ?: return
        step = state.line
        topic = state.topic
        readable = state.readable
        percent = state.percent
    }

    private class Line(val line: String, val percent: Int, val topic: Topic, val readable: Boolean)

    private fun read(context: Context, log: File?): Line? {
        val text = tail(log) ?: return null
        val s = scan(text)
        val stepAt = s.stepAt
        val stepText = s.stepText
        val downloadAt = s.downloadAt
        val downloadPercent = s.downloadPercent
        val downloadMb = s.downloadMb
        val clientDownloadAt = s.clientDownloadAt
        val clientDownload = s.clientDownload
        val clientPercent = s.clientPercent
        return when {
            // The client's own update: megabytes like the step before it, when its line carries them (every language
            // writes "(N of M KB)" in its own words); otherwise only the percentage, which no language changes.
            downloadAt > stepAt && downloadPercent >= 0 -> Line(
                if (downloadMb != null) context.getString(com.droiddeck.launcher.R.string.loadstate_steam_client, downloadMb)
                else context.getString(com.droiddeck.launcher.R.string.loadstate_steam_update, downloadPercent),
                downloadPercent, Topic.STEAM, readable = true,
            )
            clientDownloadAt == stepAt && clientDownload != null -> Line(
                context.getString(com.droiddeck.launcher.R.string.loadstate_steam_client, clientDownload), clientPercent,
                Topic.STEAM, readable = true,
            )
            stepText != null -> Line(
                INSTALLING_PROTON.find(stepText)?.let { context.getString(com.droiddeck.launcher.R.string.loadstate_installing_proton, it.groupValues[1]) }
                    ?: stepText,
                -1, topicOf(stepText, steam), readableScriptStep(stepText),
            )
            else -> null
        }
    }

    enum class Topic { RUNTIME, SESSION, DESKTOP, STEAM, STEAM_STARTING, OTHER }

    companion object {
        internal fun topicOf(scriptStep: String, steam: Boolean): Topic {
            val t = scriptStep.lowercase()
            return when {
                "linux runtime" in t -> Topic.RUNTIME
                "starting the session" in t -> Topic.SESSION
                !steam && "desktop" in t -> Topic.DESKTOP
                steam && "starting the steam client" in t -> Topic.STEAM_STARTING
                steam && ("steam" in t || "client" in t || "proton" in t || "library" in t || "compatibility" in t) -> Topic.STEAM
                else -> Topic.OTHER
            }
        }

        internal fun clientDownload(line: String): Pair<String, Int>? {
            val count = INSTALL_COUNT.find(line) ?: return null
            val mb = INSTALL_MEGABYTES.find(line.substring(count.range.last + 1))
            val done = mb?.groupValues?.get(1)?.toLongOrNull()
            val total = mb?.groupValues?.get(2)?.toLongOrNull()
            if (done != null && total != null && total > 0) {
                return "$done / $total MB" to (done * 100 / total).toInt().coerceIn(0, 100)
            }
            val part = count.groupValues[2].toIntOrNull() ?: return null
            val all = count.groupValues[3].toIntOrNull() ?: return null
            return count.groupValues[1] to (part.toLong() * 100 / maxOf(1, all)).coerceIn(0L, 100L).toInt()
        }

        internal class Scan(
            val stepAt: Int, val stepText: String?,
            val downloadAt: Int, val downloadPercent: Int,
            val clientDownloadAt: Int, val clientDownload: String?, val clientPercent: Int,
            /** "done / total MB" from the update's own "(N of M KB)" line while that line is the latest progress, else null. */
            val downloadMb: String? = null,
        )

        /** The update's "(N of M KB)" in any language: the first and the last number in the brackets, grouping
         * separators ("518,727") dropped. Null when the line is not one. */
        internal fun updateKilobytes(line: String): Pair<Long, Long>? {
            val m = UPDATE_PROGRESS.find(line) ?: return null
            val done = m.groupValues[1].filter(Char::isDigit).toLongOrNull() ?: return null
            val total = m.groupValues[2].filter(Char::isDigit).toLongOrNull() ?: return null
            return done to total
        }

        /** The session log's last [TAIL_BYTES], or null when it can't be read. */
        internal fun tail(log: File?): String? {
            if (log == null || !log.isFile) return null
            return try {
                RandomAccessFile(log, "r").use { file ->
                    val length = file.length()
                    val want = minOf(length, TAIL_BYTES)
                    file.seek(length - want)
                    val bytes = ByteArray(want.toInt())
                    file.readFully(bytes)
                    String(bytes, StandardCharsets.UTF_8)
                }
            } catch (e: Exception) {
                null
            }
        }

        /** True once [text] (the session log's tail) shows the Steam client update's own download progress after the
         * last step, the same progress the loading screen shows. An ordinary start (no update) never does. */
        internal fun updateDownloadSeen(text: String): Boolean {
            val s = scan(text)
            return s.downloadAt > s.stepAt && s.downloadPercent >= 0
        }

        fun steamUpdateDownloading(log: File?): Boolean = tail(log)?.let(::updateDownloadSeen) == true

        /**
         * Where the session log's tail stands: the last script step, and the Steam client update's progress after it.
         * The update's own text ("Downloading update (988 of 60708 KB)") is in the client's language, so a player
         * whose Steam is in Chinese saw no progress for the whole download. The update window's
         * "Set percent complete: N" lines are not translated. They count once one has moved past 0 since the last
         * step: an ordinary start writes only "0" and "-1". Negative values are skipped, so the last percentage stays
         * while the update unpacks.
         */
        internal fun scan(text: String): Scan {
            var stepAt = -1
            var stepText: String? = null
            var downloadAt = -1
            var downloadPercent = -1
            var downloadMb: String? = null
            var percentAt = -1
            var percentValue = -1
            var percentMoved = false
            var clientDownloadAt = -1
            var clientDownload: String? = null
            var clientPercent = -1
            var offset = 0
            for (line in text.split('\n')) {
                val at = offset
                offset += line.length + 1
                if (line.startsWith("== STEP ")) {
                    stepAt = at
                    stepText = line.substringAfter("== STEP ").substringAfter(' ')
                    percentMoved = false
                    clientDownload(line)?.let { (what, percent) ->
                        clientDownloadAt = at
                        clientDownload = what
                        clientPercent = percent
                    }
                    continue
                }
                val kb = updateKilobytes(line)
                if (kb != null) {
                    downloadAt = at
                    val total = maxOf(1L, kb.second)
                    downloadPercent = (kb.first * 100 / total).toInt().coerceIn(0, 100)
                    downloadMb = "${kb.first / 1000} / ${total / 1000} MB"
                    continue
                }
                val p = UPDATE_PERCENT.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (p !in 0..100) continue
                if (p > 0) percentMoved = true
                percentAt = at
                percentValue = p
            }
            if (percentMoved && percentAt > stepAt && percentAt > downloadAt) {
                downloadAt = percentAt
                downloadPercent = percentValue
            }
            // Done: the last kilobyte line stops short of the total, the percentage says 100.
            if (downloadPercent >= 100) downloadMb = null
            return Scan(stepAt, stepText, downloadAt, downloadPercent, clientDownloadAt, clientDownload, clientPercent, downloadMb)
        }

        internal fun readableScriptStep(scriptStep: String): Boolean {
            val t = scriptStep.lowercase()
            return t.startsWith("download") || t.startsWith("checking the linux") || t.startsWith("unpacking") || t.startsWith("installing")
        }

        private const val TAIL_BYTES = 48L * 1024
        private val UPDATE_PROGRESS = Regex("""^\[[^\]]*\] [^(\d]*\([^\d()]*(\d[\d,.]*)[^\d()]+(\d[\d,.]*) ?KB\)""")
        private val UPDATE_PERCENT = Regex("""^\[[^\]]*\] Set percent complete: (-?\d+)\s*$""")
        private val INSTALL_COUNT = Regex("""downloading Steam: (\S+) \((\d+)/(\d+)\)""")
        /** droiddeck-session's "installing Proton 'X' (large download, several minutes)", shown in the player's language. */
        internal val INSTALLING_PROTON = Regex("""^installing Proton '([^']+)'""")
        private val INSTALL_MEGABYTES = Regex("""^, (\d+) of (\d+) MB""")
    }
}
