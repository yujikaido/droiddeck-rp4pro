package com.droiddeck.launcher.core

import org.json.JSONObject
import java.security.MessageDigest

/**
 * MaliBridge: the DroidDeck-Components catalogs this build reads (fex.json, linuxfs-ubuntu.json, components.json,
 * desktop.json, steam-seed.json), pinned to one commit and shipped inside the APK (resources/catalog-pins).
 * They used to be read live from the repository's main branch, so every push there reached players untested
 * (the Ubuntu runtime was repointed four times in 10-28..10-31, and one of those left installs unrecoverable).
 * A newer catalog reaches players only through a deliberate bump (tools/release/bump_catalog_pins.py) in a
 * MaliBridge release. The opt-in Nightly component lists stay live and say so in the UI.
 */
object CatalogPins {
    const val FEX = "fex.json"
    const val RUNTIME = "linuxfs-ubuntu.json"
    const val WIN_COMPONENTS = "components.json"
    const val DESKTOP = "desktop.json"
    const val STEAM_SEED = "steam-seed.json"
    private const val DIR = "catalog-pins"
    private val SHA256 = Regex("[0-9a-f]{64}")

    private val manifest: JSONObject by lazy { JSONObject(String(resource("pins.json"), Charsets.UTF_8)) }

    /** The DroidDeck-Components commit the catalogs come from. */
    val commit: String get() = manifest.getString("commit")

    private fun resource(name: String): ByteArray =
        (CatalogPins::class.java.classLoader ?: ClassLoader.getSystemClassLoader()).getResourceAsStream("$DIR/$name")
            ?.use { it.readBytes() } ?: error("catalog pin $name is not in this build")

    /** The pinned catalog, checked against the sha256 the manifest records for it. */
    fun text(name: String): String {
        val bytes = resource(name)
        val want = manifest.getJSONObject("files").optString(name)
        val have = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(SHA256.matches(want) && want == have) { "catalog pin $name does not match its recorded sha256" }
        return String(bytes, Charsets.UTF_8)
    }

    /** A sha256 for a DroidDeck-Components asset the pinned components.json names without one; null when none is pinned. */
    fun assetSha256(url: String): String? =
        manifest.optJSONObject("assets")?.optString(url)?.lowercase()?.takeIf { SHA256.matches(it) }

    /** The sha256 an automatically picked Nightlies package must have ("Dxvk-Linux/dxvk-2.6.2-linux.wcp"). */
    fun nightlySha256(release: String, file: String): String? =
        manifest.optJSONObject("nightlies")?.optString("$release/$file")?.lowercase()?.takeIf { SHA256.matches(it) }

    /** Further copies of an asset upstream replaces in place or may drop; each is checked against the same sha256. */
    fun mirrors(url: String): List<String> {
        val a = manifest.optJSONObject("mirrors")?.optJSONArray(url) ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }.filter { it.startsWith("https://") }
    }
}
