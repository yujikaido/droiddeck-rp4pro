package com.droiddeck.launcher.update

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant

/**
 * The MaliBridge build's updates: the newest GitHub release of the MaliBridge repository (not
 * DroidDeck's catalog), its APK and checksum assets, and the checks a downloaded APK must pass.
 * Nothing here touches Android, so the rules are unit-tested with a fake network.
 */
object MaliReleases {
    const val AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000
    const val MANUAL_INTERVAL_MS = 60L * 1000
    private const val RATE_LIMITED_FALLBACK_MS = 60L * 60 * 1000
    const val FAILED_RETRY_MS = 30L * 60 * 1000
    private const val APK_PREFIX = "DroidDeck-MaliBridge-"
    private const val MAX_CHECKSUM_BYTES = 4096
    private val APK_NAME = Regex("""^DroidDeck-MaliBridge-v[0-9][0-9A-Za-z.+_-]*\.apk$""")
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

    // ---- versions ---------------------------------------------------------------------------

    /**
     * A release tag such as v0.5.4, v0.5.3.1, v0.5.4b or v0.6-rc1, or an RP4 Pro build's fix42 (its fix number,
     * compared as a number: fix43 is newer than fix42). Dotted numbers compare one by
     * one (missing ones count as 0, so v0.5 equals v0.5.0); letters after a number come after it
     * (0.5.4 < 0.5.4b < 0.5.4c); a pre-release (-rc1) comes before the same version without one.
     */
    class Version private constructor(
        private val parts: List<Pair<Long, String>>,
        private val pre: List<String>?,
    ) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            for (i in 0 until maxOf(parts.size, other.parts.size)) {
                val a = parts.getOrElse(i) { 0L to "" }
                val b = other.parts.getOrElse(i) { 0L to "" }
                val n = a.first.compareTo(b.first)
                if (n != 0) return n
                val l = a.second.compareTo(b.second)
                if (l != 0) return l
            }
            return comparePre(pre, other.pre)
        }

        override fun equals(other: Any?) = other is Version && compareTo(other) == 0
        override fun hashCode() = parts.dropLastWhile { it == 0L to "" }.hashCode() * 31 + (pre?.hashCode() ?: 0)

        companion object {
            private val FORM = Regex("""^(?:[vV]|fix)?([0-9]+[a-z]*(?:\.[0-9]+[a-z]*)*)(?:-([0-9A-Za-z.]+))?$""")
            private val PART = Regex("""^([0-9]+)([a-z]*)$""")

            fun parse(text: String?): Version? {
                val m = FORM.matchEntire(text?.trim() ?: return null) ?: return null
                val parts = m.groupValues[1].split('.').map { p ->
                    val pm = PART.matchEntire(p) ?: return null
                    (pm.groupValues[1].toLongOrNull() ?: return null) to pm.groupValues[2]
                }
                val pre = m.groupValues[2].takeIf { it.isNotEmpty() }?.split('.')
                if (pre != null && pre.any { it.isEmpty() }) return null
                return Version(parts, pre)
            }

            private fun comparePre(a: List<String>?, b: List<String>?): Int = when {
                a == null && b == null -> 0
                a == null -> 1
                b == null -> -1
                else -> {
                    var r = 0
                    for (i in 0 until maxOf(a.size, b.size)) {
                        val x = a.getOrNull(i) ?: return -1
                        val y = b.getOrNull(i) ?: return 1
                        val xn = x.toLongOrNull()
                        val yn = y.toLongOrNull()
                        r = when {
                            xn != null && yn != null -> xn.compareTo(yn)
                            xn != null -> -1
                            yn != null -> 1
                            else -> x.compareTo(y)
                        }
                        if (r != 0) break
                    }
                    r
                }
            }
        }
    }

    /** Positive when [a] is newer than [b], null when either is not a release version. */
    fun compare(a: String?, b: String?): Int? {
        val x = Version.parse(a) ?: return null
        val y = Version.parse(b) ?: return null
        return x.compareTo(y)
    }

    /**
     * A failure the Updates page names in the player's language from [reason] and [arg]; the message
     * stays English for the logs.
     */
    class Failure(val reason: Reason, val arg: String, message: String) : IOException(message)

    enum class Reason {
        NOT_STABLE, BAD_TAG, CHECKSUM_UNREADABLE, CHECKSUM_UNLISTED, CHECKSUM_DISAGREE, HTTP, STOPPED,
        TOO_LARGE, CUT_SHORT, NO_CONTINUE, NOT_MOVED, ANSWER_TOO_LARGE, NO_APK,
    }

    // ---- the release ------------------------------------------------------------------------

    class Asset(val id: Long, val name: String, val url: String, val size: Long, val digest: String?)

    class Release(
        val tag: String,
        val title: String,
        val notes: String,
        val pageUrl: String,
        val publishedAt: Long,
        val apk: Asset?,
        val checksum: Asset?,
    )

    enum class Standing { NEWER, CURRENT, UNKNOWN, NO_APK }

    /** Where the installed build stands against [release]; UNKNOWN when either tag can't be read. */
    fun standing(installedTag: String, release: Release): Standing {
        if (release.apk == null) return Standing.NO_APK
        val c = compare(release.tag, installedTag) ?: return Standing.UNKNOWN
        return if (c > 0) Standing.NEWER else Standing.CURRENT
    }

    fun parseRelease(root: JSONObject, repo: String): Release {
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) {
            throw Failure(Reason.NOT_STABLE, "", "The newest release is a draft or a pre-release")
        }
        val tag = root.optString("tag_name")
        if (Version.parse(tag) == null) throw Failure(Reason.BAD_TAG, tag, "The newest release has an unexpected tag ($tag)")
        val prefix = "https://github.com/$repo/releases/download/"
        val list = root.optJSONArray("assets")
        val assets = (0 until (list?.length() ?: 0)).mapNotNull { i ->
            val a = list!!.optJSONObject(i) ?: return@mapNotNull null
            val url = a.optString("browser_download_url")
            val state = a.optString("state", "uploaded")
            if (!url.startsWith(prefix) || state != "uploaded") return@mapNotNull null
            Asset(
                a.optLong("id"),
                a.optString("name"),
                url,
                a.optLong("size"),
                githubDigest(if (a.isNull("digest")) null else a.optString("digest")),
            )
        }
        val apk = pickApk(assets, tag)
        return Release(
            tag,
            root.optString("name").ifBlank { tag },
            if (root.isNull("body")) "" else root.optString("body"),
            root.optString("html_url"),
            runCatching { Instant.parse(root.optString("published_at")).toEpochMilli() }.getOrDefault(0L),
            apk,
            apk?.let { pickChecksum(assets, it.name) },
        )
    }

    private fun githubDigest(d: String?): String? =
        d?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substring(7)?.takeIf { SHA256.matches(it) }?.lowercase()

    /**
     * The release's APK: DroidDeck-MaliBridge-<tag>.apk when it is there, else the one APK whose
     * name starts with the tag (DroidDeck-MaliBridge-<tag>-test.apk), or, for an RP4 Pro build, ends
     * with it (DroidDeck-MaliBridge-v0.5.4.1-rp4pro-fix43.apk for fix43). An APK named after another
     * tag is none: a release that kept last version's APK would otherwise install it as this one.
     */
    fun pickApk(assets: List<Asset>, tag: String): Asset? {
        val candidates = assets.filter { APK_NAME.matches(it.name) && it.size > 0 }
        candidates.firstOrNull { it.name == "$APK_PREFIX$tag.apk" }?.let { return it }
        val forTag = candidates.filter {
            it.name.startsWith("$APK_PREFIX$tag-") || it.name.startsWith("$APK_PREFIX$tag.") || it.name.endsWith("-$tag.apk")
        }
        return forTag.singleOrNull()
    }

    fun pickChecksum(assets: List<Asset>, apkName: String): Asset? =
        assets.firstOrNull { it.name == "$apkName.sha256" }
            ?: assets.firstOrNull { it.name == "$apkName.sha256sum" }
            ?: assets.firstOrNull { it.name.equals("SHA256SUMS", ignoreCase = true) || it.name.equals("SHA256SUMS.txt", ignoreCase = true) }

    /**
     * The APK's SHA-256 from a checksum file: `<hex>`, `<hex>  <name>` or `<hex> *<name>` lines
     * (sha256sum's output). A line naming another file does not count.
     */
    fun parseChecksum(text: String, apkName: String): String? {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val fields = line.split(Regex("\\s+"), limit = 2)
            val hex = fields[0]
            if (!SHA256.matches(hex)) continue
            val name = fields.getOrNull(1)?.trim()?.removePrefix("*")?.substringAfterLast('/')
            if (name == null || name.isEmpty() || name == apkName) return hex.lowercase()
        }
        return null
    }

    // ---- network ----------------------------------------------------------------------------

    class Response(val code: Int, headers: Map<String, String>, val body: String) {
        val headers: Map<String, String> = headers.mapKeys { it.key.lowercase() }
        fun header(name: String): String? = headers[name.lowercase()]
    }

    fun interface Http {
        fun get(url: String, headers: Map<String, String>, maxBytes: Int): Response
    }

    sealed class Fetch {
        class Fresh(val release: Release, val etag: String?, val raw: String) : Fetch()
        object NotModified : Fetch()
        class Limited(val until: Long) : Fetch()
        class Failed(val code: Int, val retryAt: Long) : Fetch()
    }

    fun latestUrl(repo: String) = "https://api.github.com/repos/$repo/releases/latest"

    /**
     * releases/latest without a token: the unauthenticated limit is 60 requests an hour per
     * address, so the last answer's ETag goes with each request (a 304 does not use it up), and
     * a 403/429 with the limit spent waits for the reset time GitHub gives.
     */
    fun fetchLatest(http: Http, repo: String, etag: String?, now: Long): Fetch {
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            put("User-Agent", "DroidDeck-Mali-app")
            if (!etag.isNullOrBlank()) put("If-None-Match", etag)
        }
        val r = http.get(latestUrl(repo), headers, 1 shl 20)
        return when (r.code) {
            200 -> {
                val release = parseRelease(JSONObject(r.body), repo)
                Fetch.Fresh(release, r.header("ETag"), r.body)
            }
            304 -> Fetch.NotModified
            403, 429 -> limitedUntil(r, now)?.let { Fetch.Limited(it) } ?: Fetch.Failed(r.code, now + FAILED_RETRY_MS)
            else -> Fetch.Failed(r.code, now + FAILED_RETRY_MS)
        }
    }

    /** When GitHub's rate limit lets the next request through, or null for a 403 that isn't one. */
    fun limitedUntil(r: Response, now: Long): Long? {
        r.header("Retry-After")?.trim()?.toLongOrNull()?.let { return now + it.coerceIn(1, 24 * 3600) * 1000 }
        val spent = r.header("X-RateLimit-Remaining")?.trim() == "0"
        val reset = r.header("X-RateLimit-Reset")?.trim()?.toLongOrNull()
        if (spent && reset != null) return maxOf(reset * 1000, now + MANUAL_INTERVAL_MS)
        if (spent || r.code == 429) return now + RATE_LIMITED_FALLBACK_MS
        return null
    }

    /** The checksum the release publishes for its APK, or null when it ships none. Throws when it ships one that can't be read. */
    fun expectedSha256(http: Http, release: Release): String? {
        val apk = release.apk ?: return null
        val fromFile = release.checksum?.let { asset ->
            val r = http.get(asset.url, mapOf("User-Agent" to "DroidDeck-Mali-app"), MAX_CHECKSUM_BYTES)
            if (r.code != 200) throw Failure(Reason.CHECKSUM_UNREADABLE, "${r.code}", "The release's checksum file could not be read (HTTP ${r.code})")
            parseChecksum(r.body, apk.name) ?: throw Failure(Reason.CHECKSUM_UNLISTED, apk.name, "The release's checksum file does not list ${apk.name}")
        }
        if (fromFile != null && apk.digest != null && fromFile != apk.digest) {
            throw Failure(Reason.CHECKSUM_DISAGREE, apk.name, "The release's checksum file and GitHub disagree about ${apk.name}")
        }
        return fromFile ?: apk.digest
    }

    // ---- download ---------------------------------------------------------------------------

    class Body(val code: Int, val contentRange: String?, val stream: InputStream) : AutoCloseable {
        override fun close() = stream.close()
    }

    fun interface Ranged {
        /** GETs [url], asking for the bytes from [from] on when it is above 0. */
        fun open(url: String, from: Long): Body
    }

    /**
     * Downloads [url] into [part], continuing a partial file when the server answers the Range
     * request with 206 from that offset, starting over on a 200. Returns once [part] holds
     * exactly [size] bytes. A cut connection keeps the bytes so far for the next attempt.
     */
    fun download(url: String, part: File, size: Long, net: Ranged, cancelled: () -> Boolean = { false }, progress: (Long) -> Unit = {}) {
        require(size > 0)
        if (part.length() > size) part.delete()
        if (part.length() == size) { progress(size); return }
        for (attempt in 0..1) {
            val from = part.length()
            net.open(url, from).use { body ->
                val append = when {
                    from > 0 && body.code == 206 && body.contentRange?.trim()?.startsWith("bytes $from-") == true -> true
                    body.code == 200 -> false
                    body.code == 416 && attempt == 0 -> { part.delete(); null }
                    else -> throw Failure(Reason.HTTP, "${body.code}", "The download failed (HTTP ${body.code})")
                } ?: return@use
                FileOutputStream(part, append).use { out ->
                    var done = if (append) from else 0L
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (cancelled()) throw Failure(Reason.STOPPED, "", "The download was stopped")
                        val n = body.stream.read(buf)
                        if (n < 0) break
                        if (done + n > size) {
                            out.close(); part.delete()
                            throw Failure(Reason.TOO_LARGE, "", "The download is larger than the release says")
                        }
                        out.write(buf, 0, n)
                        done += n
                        progress(done)
                    }
                }
                if (part.length() != size) throw Failure(Reason.CUT_SHORT, "", "The download stopped early; it continues next time")
                return
            }
        }
        throw Failure(Reason.NO_CONTINUE, "", "The download could not continue")
    }

    // ---- verification -----------------------------------------------------------------------

    enum class Problem { CHECKSUM, NO_CHECKSUM, WRONG_PACKAGE, OLDER_VERSION_CODE, NO_SIGNER, WRONG_SIGNER, UNREADABLE }

    /** What Android's package manager reads from a downloaded APK. */
    class ApkFacts(
        val packageName: String?,
        val versionCode: Long,
        val signers: Set<String>,
        val signerHistory: Set<String> = emptySet(),
    )

    /** No checksum published (no .sha256 asset, no GitHub digest) is a refusal too: nothing would catch a damaged download. */
    fun checksumProblem(actual: String, expected: String?): Problem? = when {
        expected == null -> Problem.NO_CHECKSUM
        !expected.equals(actual, ignoreCase = true) -> Problem.CHECKSUM
        else -> null
    }

    /**
     * The downloaded APK installs over this one only when it is this package, is not an older
     * versionCode, and is signed by this app's own certificate (or rotated from it: a single new
     * signer whose history includes the installed one). Anything else is refused before Android
     * is asked.
     */
    fun apkProblem(facts: ApkFacts?, packageName: String, versionCode: Long, installedSigners: Set<String>): Problem? {
        if (facts == null || facts.packageName == null) return Problem.UNREADABLE
        if (facts.packageName != packageName) return Problem.WRONG_PACKAGE
        if (facts.versionCode < versionCode) return Problem.OLDER_VERSION_CODE
        if (facts.signers.isEmpty() || installedSigners.isEmpty()) return Problem.NO_SIGNER
        return if (signersMatch(installedSigners, facts.signers, facts.signerHistory)) null else Problem.WRONG_SIGNER
    }

    fun signersMatch(installed: Set<String>, candidate: Set<String>, candidateHistory: Set<String> = emptySet()): Boolean {
        val mine = installed.map { it.lowercase() }.toSet()
        val theirs = candidate.map { it.lowercase() }.toSet()
        if (mine.isEmpty() || theirs.isEmpty()) return false
        if (mine == theirs) return true
        return mine.size == 1 && theirs.size == 1 && candidateHistory.any { it.equals(mine.single(), ignoreCase = true) }
    }

    // ---- notes ------------------------------------------------------------------------------

    /** The release body as a short plain-text excerpt: Markdown marks and links reduced to their text. */
    fun notesExcerpt(body: String, maxChars: Int = 600): String {
        val text = body.replace("\r", "")
            .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")
            .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
            .lineSequence()
            .map { it.trim().replace(Regex("""^#{1,6}\s*"""), "").replace(Regex("""^[-*+]\s+"""), "• ") }
            .map { it.replace("**", "").replace("__", "").replace("`", "") }
            .fold(mutableListOf<String>()) { acc, l -> if (!(l.isEmpty() && acc.lastOrNull()?.isEmpty() != false)) acc += l; acc }
            .joinToString("\n").trim()
        if (text.length <= maxChars) return text
        val cut = text.substring(0, maxChars)
        val at = cut.lastIndexOfAny(charArrayOf('\n', ' ')).takeIf { it > maxChars / 2 } ?: maxChars
        return cut.substring(0, at).trimEnd() + "…"
    }
}
