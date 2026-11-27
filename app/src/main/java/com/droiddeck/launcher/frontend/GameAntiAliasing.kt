package com.droiddeck.launcher.frontend

import java.io.File

/**
 * A per-game anti-aliasing switch for games that keep it in their own settings file. On the Retroid
 * Pocket 4 Pro's Mali-G77, Ys Origin's 4x anti-aliasing costs about 30 % of the GPU's time (three
 * copies of the 4x back buffer a frame for its glare and refraction): off, the same room runs at 77 fps
 * instead of 45. The game page offers 4x / off; the change applies from the game's next start.
 *
 * Only the value of the one key is changed, byte for byte, so the rest of the file stays as the game
 * wrote it (CRLF, order, comments). Before the first change a copy is kept next to it (".droiddeck-aa").
 */
object GameAntiAliasing {
    data class Spec(val file: String, val section: String, val key: String, val on: String, val off: String)

    /** Steam app id -> where the game keeps the setting, relative to its Wine prefix (pfx). */
    val known: Map<Long, Spec> = mapOf(
        207350L to Spec("drive_c/users/steamuser/Saved Games/FALCOM/yso_win/yso_win.ini", "graphics", "MultiSampleType", "4", "0"),
    )

    fun file(prefix: File, spec: Spec) = File(prefix, spec.file)

    /** true: anti-aliasing on, false: off, null: the game, its file or its key is not there. */
    fun state(appId: Long, prefix: File?): Boolean? {
        val spec = known[appId] ?: return null
        val f = prefix?.let { file(it, spec) }?.takeIf { it.isFile } ?: return null
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
        val (s, e) = find(bytes, spec) ?: return null
        return String(bytes, s, e - s, Charsets.ISO_8859_1) != spec.off
    }

    /** Sets the game's anti-aliasing; returns the state now in the file, or null when it could not be set. */
    fun set(appId: Long, prefix: File?, on: Boolean): Boolean? {
        val spec = known[appId] ?: return null
        val f = prefix?.let { file(it, spec) }?.takeIf { it.isFile } ?: return null
        val bytes = f.readBytes()
        val (start, end) = find(bytes, spec) ?: return null
        val keep = File(f.parentFile, f.name + ".droiddeck-aa")
        if (!keep.exists()) runCatching { keep.writeBytes(bytes) }
        val value = (if (on) spec.on else spec.off).toByteArray(Charsets.ISO_8859_1)
        val out = bytes.copyOfRange(0, start) + value + bytes.copyOfRange(end, bytes.size)
        val tmp = File(f.parentFile, f.name + ".droiddeck-tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(f)) {
            f.writeBytes(out)
            tmp.delete()
        }
        return state(appId, prefix)
    }

    /** The value's byte range of section's key (case-insensitive names), or null. */
    internal fun find(bytes: ByteArray, spec: Spec): Pair<Int, Int>? {
        val text = String(bytes, Charsets.ISO_8859_1)
        var inSection = false
        var at = 0
        while (at < text.length) {
            val lineEnd = text.indexOf('\n', at).let { if (it < 0) text.length else it }
            val contentEnd = if (lineEnd > at && text[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
            val line = text.substring(at, contentEnd)
            val t = line.trim()
            if (t.startsWith("[") && t.endsWith("]")) {
                inSection = t.substring(1, t.length - 1).trim().equals(spec.section, ignoreCase = true)
            } else if (inSection) {
                val eq = line.indexOf('=')
                if (eq > 0 && line.substring(0, eq).trim().equals(spec.key, ignoreCase = true)) {
                    var s = at + eq + 1
                    while (s < contentEnd && text[s] == ' ') s++
                    var e = contentEnd
                    while (e > s && text[e - 1] == ' ') e--
                    return s to e
                }
            }
            at = lineEnd + 1
        }
        return null
    }
}
