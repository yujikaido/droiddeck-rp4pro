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

    fun update(context: Context, log: File?) {
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
        val stepText = s.stepText
        val clientDownload = s.clientDownload
        return when {
            s.downloadAt > s.stepAt && s.downloadPercent >= 0 -> Line(
                context.getString(com.droiddeck.launcher.R.string.loadstate_steam_update, s.downloadPercent), s.downloadPercent,
                Topic.STEAM, readable = true,
            )
            s.clientDownloadAt == s.stepAt && clientDownload != null -> Line(
                context.getString(com.droiddeck.launcher.R.string.loadstate_steam_client, clientDownload), s.clientPercent,
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

    internal class Scan(
        val stepAt: Int, val stepText: String?,
        val downloadAt: Int, val downloadPercent: Int,
        val clientDownloadAt: Int, val clientDownload: String?, val clientPercent: Int,
    )

    enum class Topic { RUNTIME, SESSION, DESKTOP, STEAM, STEAM_STARTING, OTHER }

    companion object {
        /**
         * Where the session log's tail stands: the last script step, and the Steam client update's progress after it.
         * The update's own text ("Downloading update (988 of 60708 KB)") is in the client's language, so a player
         * whose Steam is in Chinese saw no progress at all for the whole download. The update window's
         * "Set percent complete: N" lines are not translated. They count once one of them has moved past 0 since
         * the last step: an ordinary start without an update writes only "0" and "-1". Negative values (the window's
         * busy state while it unpacks and installs) are skipped, so the last percentage stays, as the English line does.
         */
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

        /** [updateDownloadSeen] for the session log [log]. */
        fun steamUpdateDownloading(log: File?): Boolean = tail(log)?.let(::updateDownloadSeen) == true

        internal fun scan(text: String): Scan {
            var stepAt = -1
            var stepText: String? = null
            var downloadAt = -1
            var downloadPercent = -1
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
                    val m = INSTALL_COUNT.find(line)
                    if (m != null) {
                        clientDownloadAt = at
                        clientDownload = m.groupValues[1]
                        clientPercent = m.groupValues[2].toInt() * 100 / maxOf(1, m.groupValues[3].toInt())
                    }
                    continue
                }
                val m = UPDATE_PROGRESS.find(line)
                if (m != null) {
                    downloadAt = at
                    val done = m.groupValues[1].toLong()
                    val total = maxOf(1L, m.groupValues[2].toLong())
                    downloadPercent = (done * 100 / total).toInt()
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
            return Scan(stepAt, stepText, downloadAt, downloadPercent, clientDownloadAt, clientDownload, clientPercent)
        }

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

        internal fun readableScriptStep(scriptStep: String): Boolean {
            val t = scriptStep.lowercase()
            return t.startsWith("download") || t.startsWith("checking the linux") || t.startsWith("unpacking") || t.startsWith("installing")
        }

        private const val TAIL_BYTES = 48L * 1024
        private val UPDATE_PROGRESS = Regex("""Downloading update \((\d+) of (\d+) KB\)""")
        private val INSTALL_COUNT = Regex("""downloading Steam: (\S+) \((\d+)/(\d+)\)""")
        /** droiddeck-session's "installing Proton 'X' (large download, several minutes)", shown in the player's language. */
        internal val INSTALLING_PROTON = Regex("""^installing Proton '([^']+)'""")
        private val UPDATE_PERCENT = Regex("""^\[[^\]]*\] Set percent complete: (-?\d+)\s*$""")
    }
}
