package com.droiddeck.launcher.session

import java.io.File
import java.io.IOException

/**
 * Lets a player try the bridge server's switches (VKBRIDGE_BC_TRANSCODE, VKBRIDGE_BC_TX_BUDGET, ...) without a
 * custom APK. The server reads such a switch from the game's preset file, from `<bridge cache dir>/env` or from
 * its own environment, and none of those is reachable by a player; droiddeck-env only reaches the guest. At the
 * start of a session the `VKBRIDGE_NAME=value` lines of droiddeck-env are copied into `<bridge cache dir>/env`,
 * which the server reads again at each vkCreateDevice and logs line by line ("override from .../env: KEY=VAL").
 *
 * The copy lives in one marked block at the top of that file; the lines around it are never changed. The
 * server takes the FIRST line of a key (server_pcache.c vkb_srv_env_override), so a key that the file already
 * names is overridden by the block, and the old line is back in force when the player's line goes (the block
 * is dropped at the next session start). With no VKBRIDGE_ line in droiddeck-env and no block in the file, the
 * file is not touched at all (not created, not rewritten).
 *
 * Per-game preset files (`presets/<appid>.env`, KnownGamesApply) still beat the global file for their own game
 * in the server; a key named by both is reported in the log so a test arm is not silently the same as another.
 */
object BridgeEnvPassthrough {
    const val BEGIN = "# >>> droiddeck-env passthrough (written by the app at session start; lines here come from droiddeck-env)"
    const val END = "# <<< droiddeck-env passthrough"
    const val LOG_PREFIX = "bridge settings from droiddeck-env: "

    private const val MAX_SETTINGS = 64
    private const val MAX_FILE_BYTES = 64 * 1024
    private val LINE = Regex("^(VKBRIDGE_[A-Z0-9_]{1,56})=([A-Za-z0-9_.:,+/@%-]{1,200})$")

    data class Parsed(
        val settings: Map<String, String>,
        /** Lines that start with VKBRIDGE_ but are not an accepted `NAME=value` (1-based line number, text made printable). */
        val rejected: List<Pair<Int, String>>,
        /** Keys given twice in droiddeck-env: the later line wins, as it does for the guest. */
        val repeated: List<String>,
        val tooMany: Boolean,
    )

    /** The VKBRIDGE_ lines of droiddeck-env that the server may be given. Anything else in the file is none of our business. */
    fun parse(text: String?): Parsed {
        val settings = LinkedHashMap<String, String>()
        val rejected = ArrayList<Pair<Int, String>>()
        val repeated = ArrayList<String>()
        var tooMany = false
        text.orEmpty().lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (!line.startsWith("VKBRIDGE_")) return@forEachIndexed
            val m = LINE.matchEntire(line)
            if (m == null) {
                rejected.add(i + 1 to printable(line))
                return@forEachIndexed
            }
            val (key, value) = m.destructured
            if (!settings.containsKey(key) && settings.size >= MAX_SETTINGS) {
                tooMany = true
                return@forEachIndexed
            }
            if (settings.put(key, value) != null && key !in repeated) repeated.add(key)
        }
        return Parsed(settings, rejected, repeated, tooMany)
    }

    private fun printable(s: String): String =
        s.take(60).map { if (it.code in 0x20..0x7e) it else '?' }.joinToString("") + if (s.length > 60) "..." else ""

    /** The key of a line of the override file the way the server reads it (server_pcache.c vkb_srv_env_reload), or null. */
    private fun serverKey(line: String): String? {
        val s = line.trimStart(' ', '\t')
        if (s.isEmpty() || s.startsWith("#")) return null
        val eq = s.indexOf('=')
        return if (eq <= 0) null else s.substring(0, eq)
    }

    /** [text] without the managed block; null for "no block there". An opening line without its end is dropped alone. */
    internal fun withoutBlock(text: String): String? {
        val start = text.indexOf(BEGIN)
        if (start < 0 || (start > 0 && text[start - 1] != '\n')) return null
        val endAt = text.indexOf(END, start)
        if (endAt < 0) {
            val eol = text.indexOf('\n', start)
            return text.substring(0, start) + if (eol < 0) "" else text.substring(eol + 1)
        }
        val eol = text.indexOf('\n', endAt)
        return text.substring(0, start) + if (eol < 0) "" else text.substring(eol + 1)
    }

    /** The new content of the override file; null = leave the file exactly as it is (or absent). */
    internal fun merged(current: String?, settings: Map<String, String>): String? {
        val stripped = current?.let(::withoutBlock)
        val rest = stripped ?: current.orEmpty()
        if (settings.isEmpty()) return stripped
        val block = buildString {
            append(BEGIN).append('\n')
            settings.forEach { (k, v) -> append(k).append('=').append(v).append('\n') }
            append(END).append('\n')
        }
        return block + rest
    }

    data class Result(val changed: Boolean, val logLines: List<String>)

    /**
     * [playerEnv] is droiddeck-env (null before Android 11), [overrideFile] `<bridge cache dir>/env`, [presetsDir] the
     * bridge's `presets/` folder. Returns the lines for session.log.
     */
    fun apply(playerEnv: File?, overrideFile: File, presetsDir: File?): Result {
        val parsed = parse(playerEnv?.takeIf { it.isFile && it.length() <= MAX_FILE_BYTES }?.let { runCatching { it.readText() }.getOrNull() })
        val before: String? = overrideFile.takeIf { it.isFile && it.length() <= MAX_FILE_BYTES }?.let { runCatching { it.readText() }.getOrNull() }
        val after = merged(before, parsed.settings)
        var changed = false
        if (after != null && after != before) {
            if (after.isEmpty() && before != null) {
                changed = overrideFile.delete()
            } else {
                writeAtomic(overrideFile, after)
                changed = true
            }
        }
        val log = ArrayList<String>()
        if (parsed.settings.isNotEmpty()) {
            log.add(LOG_PREFIX + parsed.settings.entries.joinToString(", ") { "${it.key}=${it.value}" })
            val old = LinkedHashMap<String, String>()
            stripBlockOrSelf(before).lines().forEach { l ->
                serverKey(l)?.let { old.putIfAbsent(it, l.substringAfter('=').trimEnd('\r')) }
            }
            // first line of a key wins in the server, so these are the old lines that the player's now shadow
            parsed.settings.forEach { (k, v) ->
                old[k]?.let { log.add("$LOG_PREFIX$k=$v replaces $k=$it in the override file (${overrideFile.path}); the old line is back when the line leaves droiddeck-env") }
            }
            presetKeys(presetsDir, parsed.settings.keys).forEach { (file, key) ->
                log.add("$LOG_PREFIX$key is also set by presets/$file; for that game the game's preset file wins")
            }
        }
        parsed.repeated.forEach { log.add("${LOG_PREFIX}$it is given twice; the later line is used") }
        parsed.rejected.forEach { (n, text) -> log.add("${LOG_PREFIX}ignored line $n (not VKBRIDGE_NAME=value with letters, digits and _.:,+/@%- only): $text") }
        if (parsed.tooMany) log.add("${LOG_PREFIX}more than $MAX_SETTINGS settings; the rest are ignored")
        return Result(changed, log)
    }

    private fun stripBlockOrSelf(text: String?): String = text?.let { withoutBlock(it) ?: it }.orEmpty()

    private fun presetKeys(dir: File?, keys: Set<String>): List<Pair<String, String>> {
        val files = dir?.listFiles { f -> f.isFile && f.name.endsWith(".env") }?.sortedBy { it.name } ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (f in files) {
            if (f.length() > MAX_FILE_BYTES) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val named = text.lines().mapNotNull(::serverKey).filter { it in keys }.toSet()
            named.sorted().forEach { out.add(f.name to it) }
        }
        return out
    }

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        val staged = File(file.parentFile, file.name + ".staged")
        staged.writeText(text)
        if (!staged.renameTo(file)) {
            staged.delete()
            throw IOException("could not write ${file.name}")
        }
    }
}
