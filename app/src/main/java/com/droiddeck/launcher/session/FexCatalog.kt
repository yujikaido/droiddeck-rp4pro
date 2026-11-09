package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.DownloadSource
import com.droiddeck.launcher.core.CatalogPins
import org.json.JSONObject

/**
 * The FEX build DroidDeck-Components' fex.json names for x86 programs. MaliBridge reads the copy pinned in this
 * build (CatalogPins), not the live file: upstream's main moved it on every FEX build, and the session installed
 * whatever it named. Components' build-fex.yml replaces the archive on its single `fex` release with each build,
 * so the pinned archive also has a mirror (CatalogPins.mirrors); every source is checked against the same sha256.
 * A release per build (fex-2610-r5) is still read.
 */
object FexCatalog {
    private const val RELEASES = "https://github.com/Droid-Deck/DroidDeck-Components/releases/download/"
    private val TAG_FORMAT = Regex("""fex-\d+-r\d+""")
    private val VERSION_FORMAT = Regex("""\d+-r\d+""")
    private val SHA256 = Regex("[0-9a-f]{64}")

    data class Build(val tag: String, val url: String, val sha256: String)

    private val pinned: Build? by lazy { runCatching { parse(CatalogPins.text(CatalogPins.FEX)) }.getOrNull() }

    internal fun parse(text: String): Build? = runCatching {
        val o = JSONObject(text)
        val release = o.getString("release")
        val url = o.getString("url")
        val sha = o.getString("sha256").lowercase()
        val version = o.optString("version")
        val tag = when {
            TAG_FORMAT.matches(release) -> release
            release == "fex" && VERSION_FORMAT.matches(version) -> "fex-$version"
            else -> return@runCatching null
        }
        Build(tag, url, sha).takeIf { url == "$RELEASES$release/fex.tzst" && SHA256.matches(sha) }
    }.getOrNull()

    /** The pinned build; [context] is kept for the callers. */
    @Suppress("UNUSED_PARAMETER")
    fun cached(context: Context): Build? = pinned

    /** The pinned build. Nothing is read from the network: a new fex.json reaches players only with a MaliBridge release. */
    @Suppress("UNUSED_PARAMETER")
    fun refresh(context: Context, now: Long = System.currentTimeMillis()): Build? = pinned

    /** For droiddeck-fex-update: the build and every source its archive may come from (it checks the sha256). */
    fun environment(build: Build, sources: List<String> = DownloadSource.candidates(build.url) + CatalogPins.mirrors(build.url)): List<String> = listOf(
        "BL_FEX_TAG=${build.tag}",
        "BL_FEX_SHA256=${build.sha256}",
        "BL_FEX_URLS=" + sources.joinToString(" "),
    )
}
