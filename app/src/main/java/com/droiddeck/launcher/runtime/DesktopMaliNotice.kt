package com.droiddeck.launcher.runtime

import java.io.File

/** Under the Mali bridge KWin has only the bridge's stand-in render node, which the host's dma-buf feedback cannot
 *  name: an unpatched KWin finds no GPU, so droiddeck-desktop runs its software (QPainter) compositor there. KWin 6.8
 *  removed that compositor. DroidDeck's own KWin 6.8 build (desktop package r3+) carries
 *  0002-select-explicit-nested-render-node.patch and takes the stand-in node from KWIN_RENDER_NODES, compositing on
 *  Zink over the bridge (droiddeck-desktop does that), so it needs no notice. A KWin 6.8+ without that patch would
 *  start into a black screen, so the player gets a notice first. Older KWin, such as the Kubuntu package of the
 *  Ubuntu runtime (KWin 6.6.6, marked "opengl" for Adreno), still has QPainter and needs no notice.
 *  Before the desktop is installed there is no KWin to look at; the package that will be installed is DroidDeck's,
 *  whose KWin always carries patch 0002 (tools/desktop-kde/build-kwin.sh, tools/ubuntu/build-debs.sh), so no notice:
 *  it showed there on a Galaxy A35 (Mali-G68), and the desktop then installed and drew on the GPU. */
internal object DesktopMaliNotice {
    const val MARKER = "usr/share/droiddeck/kwin-compositor"
    private val LIB_DIRS = listOf("usr/lib/aarch64-linux-gnu", "usr/lib")
    private val LIBKWIN = Regex("""libkwin\.so\.(\d+)\.(\d+)(?:\.\d+)*""")

    /** First KWin version without the QPainter compositor. */
    private val NO_QPAINTER_FROM = 6 to 8

    /** [kwin] is the installed KWin's (major, minor) when known; it decides. Without it, an installed package's
     *  compositor marker decides ("opengl": the session would keep software, which KWin 6.8 lacks); with neither,
     *  nothing is installed yet and DroidDeck's package (patch 0002) will be: no notice. */
    fun needed(maliBridge: Boolean, marker: String?, kwin: Pair<Int, Int>? = null,
               explicitNode: () -> Boolean = { false }): Boolean {
        if (!maliBridge) return false
        if (kwin != null) return compareValuesBy(kwin, NO_QPAINTER_FROM, { it.first }, { it.second }) >= 0 && !explicitNode()
        return marker?.trim() == "opengl"
    }

    /** The string patch 0002 logs; it is in the library only when the patch is. */
    private val EXPLICIT_NODE = "explicitly selected nested render node".toByteArray()

    /** Whether the runtime's libkwin carries patch 0002 (takes KWIN_RENDER_NODES when the host can't name a device). */
    fun kwinTakesExplicitNode(root: File): Boolean = runCatching {
        LIB_DIRS.asSequence()
            .mapNotNull { dir -> File(root, dir).listFiles { f -> f.isFile && LIBKWIN.matches(f.name) }?.toList() }
            .flatten()
            .any { contains(it, EXPLICIT_NODE) }
    }.getOrDefault(false)

    /** Streams [file] looking for [needle]; the window keeps a match that spans two reads. */
    internal fun contains(file: File, needle: ByteArray): Boolean {
        val buf = ByteArray(1 shl 16)
        var carry = 0
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf, carry, buf.size - carry)
                if (n <= 0) return false
                val end = carry + n
                outer@ for (i in 0..end - needle.size) {
                    for (j in needle.indices) if (buf[i + j] != needle[j]) continue@outer
                    return true
                }
                carry = minOf(needle.size - 1, end)
                System.arraycopy(buf, end - carry, buf, 0, carry)
            }
        }
    }

    fun marker(root: File): String? = runCatching { File(root, MARKER).readText() }.getOrNull()

    /** (major, minor) of the libkwin.so.X.Y.Z the runtime holds, or null when no desktop is installed. */
    fun kwinVersion(root: File): Pair<Int, Int>? = runCatching {
        LIB_DIRS.asSequence()
            .mapNotNull { File(root, it).list() }
            .flatMap { it.asSequence() }
            .mapNotNull { LIBKWIN.matchEntire(it) }
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .maxWithOrNull(compareBy({ it.first }, { it.second }))
    }.getOrNull()
}
