package com.droiddeck.launcher.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.Hashes
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The Android side of the MaliBridge build's updates: settings and the last answer kept in
 * SharedPreferences, GitHub over HttpURLConnection, the download in the app's cache, and the
 * package manager's view of the downloaded APK. The rules themselves are in [MaliReleases].
 */
object MaliUpdater {
    private const val PREFS = "mali_updates"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_BLOCKED_UNTIL = "blocked_until"
    private const val KEY_RETRY_AT = "retry_at"
    private const val KEY_ETAG = "etag"
    private const val KEY_RELEASE = "release"
    private const val KEY_SHA = "sha256"
    private const val KEY_SHA_TAG = "sha256_tag"
    private const val KEY_READY_TAG = "ready_tag"
    private const val KEY_SELF_HASH = "self_hash"
    private const val KEY_SELF_HASH_AT = "self_hash_at"
    private const val DIR = "mali-updates"
    private const val USER_AGENT = "DroidDeck-Mali-app"

    val repo: String get() = BuildConfig.UPDATES_REPO

    /** True for the .mali build: its Updates tab follows MaliBridge's releases. */
    val forThisBuild: Boolean get() = repo.isNotBlank()

    val installedTag: String get() = BuildConfig.MALI_RELEASE

    fun enabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    fun wifiOnly(context: Context) = prefs(context).getBoolean(KEY_WIFI_ONLY, true)
    fun setWifiOnly(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_WIFI_ONLY, on).apply()
    fun lastCheck(context: Context) = prefs(context).getLong(KEY_LAST_CHECK, 0L)
    fun blockedUntil(context: Context) = prefs(context).getLong(KEY_BLOCKED_UNTIL, 0L)

    fun autoCheckDue(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        enabled(context) && now >= blockedUntil(context) && now >= prefs(context).getLong(KEY_RETRY_AT, 0L) &&
            now - lastCheck(context) !in 0 until MaliReleases.AUTO_INTERVAL_MS

    fun cached(context: Context): MaliReleases.Release? {
        val raw = prefs(context).getString(KEY_RELEASE, null) ?: return null
        return runCatching { MaliReleases.parseRelease(JSONObject(raw), repo) }.getOrNull()
    }

    sealed class Check {
        class Done(val release: MaliReleases.Release) : Check()
        class Limited(val until: Long) : Check()
        class Failed(val message: String, val cause: Throwable? = null) : Check()
    }

    /**
     * Asks GitHub for the newest release (throttled: an answer from the last minute is reused).
     * Only an answer counts as the last check; a failure (no connection at start) retries
     * automatically after half an hour instead of after the full interval.
     */
    fun check(context: Context, now: Long = System.currentTimeMillis()): Check {
        val p = prefs(context)
        val blocked = blockedUntil(context)
        if (now < blocked) return Check.Limited(blocked)
        val cached = cached(context)
        if (cached != null && now - lastCheck(context) in 0 until MaliReleases.MANUAL_INTERVAL_MS) return Check.Done(cached)
        val fetch = try {
            MaliReleases.fetchLatest(http, repo, p.getString(KEY_ETAG, null).takeIf { cached != null }, now)
        } catch (e: Exception) {
            p.edit().putLong(KEY_RETRY_AT, now + MaliReleases.FAILED_RETRY_MS).apply()
            return Check.Failed(e.message ?: e.javaClass.simpleName, e)
        }
        return when (fetch) {
            is MaliReleases.Fetch.Fresh -> {
                p.edit().putLong(KEY_LAST_CHECK, now).putLong(KEY_BLOCKED_UNTIL, 0L)
                    .putString(KEY_ETAG, fetch.etag).putString(KEY_RELEASE, fetch.raw).apply()
                Check.Done(fetch.release)
            }
            MaliReleases.Fetch.NotModified -> {
                p.edit().putLong(KEY_LAST_CHECK, now).putLong(KEY_BLOCKED_UNTIL, 0L).apply()
                cached?.let { Check.Done(it) } ?: Check.Failed("HTTP 304")
            }
            is MaliReleases.Fetch.Limited -> {
                p.edit().putLong(KEY_BLOCKED_UNTIL, fetch.until).apply()
                Check.Limited(fetch.until)
            }
            is MaliReleases.Fetch.Failed -> {
                p.edit().putLong(KEY_RETRY_AT, fetch.retryAt).apply()
                Check.Failed("HTTP ${fetch.code}")
            }
        }
    }

    /**
     * NEWER only when the release is really another APK: a build whose recorded tag fell behind
     * (tools/release/mali-release.txt not raised) would otherwise be offered itself forever, so a
     * release whose checksum is this APK's own counts as CURRENT.
     */
    fun standing(context: Context, release: MaliReleases.Release): MaliReleases.Standing {
        val s = MaliReleases.standing(installedTag, release)
        if (s != MaliReleases.Standing.NEWER && s != MaliReleases.Standing.UNKNOWN) return s
        val sha = runCatching { expectedSha(context, release) }.getOrNull() ?: release.apk?.digest ?: return s
        return if (selfHash(context) == sha) MaliReleases.Standing.CURRENT else s
    }

    private fun knownSha(context: Context, release: MaliReleases.Release): String? {
        val p = prefs(context)
        if (p.getString(KEY_SHA_TAG, null) == shaKey(release)) return p.getString(KEY_SHA, null)
        return release.apk?.digest
    }

    private fun expectedSha(context: Context, release: MaliReleases.Release): String? {
        val p = prefs(context)
        if (p.getString(KEY_SHA_TAG, null) == shaKey(release)) return p.getString(KEY_SHA, null)
        val sha = MaliReleases.expectedSha256(http, release)
        // Kept only once known: a checksum uploaded after the APK is read at the next check.
        if (sha != null) p.edit().putString(KEY_SHA_TAG, shaKey(release)).putString(KEY_SHA, sha).apply()
        return sha
    }

    /** A re-uploaded APK (same tag, new asset) gets its checksum read again. */
    private fun shaKey(release: MaliReleases.Release) = "${release.tag}:${release.apk?.id}:${release.apk?.size}"

    private fun selfHash(context: Context): String? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val p = prefs(context)
        if (p.getLong(KEY_SELF_HASH_AT, -1L) == info.lastUpdateTime) return@runCatching p.getString(KEY_SELF_HASH, null)
        val hash = Hashes.sha256(File(context.applicationInfo.sourceDir))
        p.edit().putLong(KEY_SELF_HASH_AT, info.lastUpdateTime).putString(KEY_SELF_HASH, hash).apply()
        hash
    }.getOrNull()

    fun onWifi(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)

    // ---- the downloaded APK -----------------------------------------------------------------

    private fun dir(context: Context) = File(context.cacheDir, DIR)

    private fun finalFile(context: Context, release: MaliReleases.Release) =
        File(dir(context), release.apk!!.name)

    private fun partFile(context: Context, release: MaliReleases.Release) =
        File(dir(context), "${release.apk!!.id}-${release.apk.size}.part")

    /** The verified APK of [release] when it is already downloaded. */
    fun ready(context: Context, release: MaliReleases.Release): File? {
        if (release.apk == null || prefs(context).getString(KEY_READY_TAG, null) != release.tag) return null
        return finalFile(context, release).takeIf { it.isFile && it.length() == release.apk.size }
    }

    /** Drops downloads of other releases (an installed update leaves its APK behind otherwise). */
    fun cleanup(context: Context, keep: MaliReleases.Release?) {
        val names = keep?.apk?.let { setOf(it.name, "${it.id}-${it.size}.part") }.orEmpty()
        dir(context).listFiles()?.filter { it.name !in names }?.forEach { FileUtils.delete(it) }
        if (keep == null || prefs(context).getString(KEY_READY_TAG, null) != keep.tag) {
            prefs(context).edit().remove(KEY_READY_TAG).apply()
        }
    }

    class Refused(val problem: MaliReleases.Problem) : IOException(problem.name)

    /** Downloads [release]'s APK (continuing an earlier partial download) and verifies it. */
    fun prepare(context: Context, release: MaliReleases.Release, cancelled: () -> Boolean, progress: (Int) -> Unit): File {
        val apk = release.apk ?: throw MaliReleases.Failure(MaliReleases.Reason.NO_APK, "", "no APK")
        ready(context, release)?.let { return it }
        cleanup(context, release)
        dir(context).mkdirs()
        val sha = expectedSha(context, release)
        val part = partFile(context, release)
        var last = -1
        MaliReleases.download(apk.url, part, apk.size, ranged, cancelled) { done ->
            val pct = (done * 100 / apk.size).toInt()
            if (pct != last) { last = pct; progress(pct) }
        }
        try {
            verify(context, part, sha)
        } catch (e: Refused) {
            FileUtils.delete(part)
            throw e
        }
        val target = finalFile(context, release)
        FileUtils.delete(target)
        if (!part.renameTo(target)) throw MaliReleases.Failure(MaliReleases.Reason.NOT_MOVED, "", "The download could not be moved into place")
        prefs(context).edit().putString(KEY_READY_TAG, release.tag).apply()
        return target
    }

    /** Checks [file] again right before installing it; a file that fails is deleted. */
    fun verifyReady(context: Context, release: MaliReleases.Release, file: File) {
        try {
            verify(context, file, knownSha(context, release))
        } catch (e: Refused) {
            FileUtils.delete(file)
            prefs(context).edit().remove(KEY_READY_TAG).apply()
            throw e
        }
    }

    private fun verify(context: Context, file: File, sha: String?) {
        MaliReleases.checksumProblem(Hashes.sha256(file), sha)?.let { throw Refused(it) }
        val problem = MaliReleases.apkProblem(
            archiveFacts(context, file),
            context.packageName,
            BuildConfig.VERSION_CODE.toLong(),
            installedSigners(context),
        )
        if (problem != null) throw Refused(problem)
    }

    @Suppress("DEPRECATION")
    private fun archiveFacts(context: Context, file: File): MaliReleases.ApkFacts? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return null
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val (current, history) = certificates(info)
        return MaliReleases.ApkFacts(info.packageName, code, current, history)
    }

    private fun installedSigners(context: Context): Set<String> = runCatching {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        certificates(context.packageManager.getPackageInfo(context.packageName, flags)).first
    }.getOrDefault(emptySet())

    /** The package's current signers, and (for a single rotated signer) its certificate history. */
    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Pair<Set<String>, Set<String>> {
        if (Build.VERSION.SDK_INT >= 28) {
            val s = info.signingInfo ?: return emptySet<String>() to emptySet()
            if (s.hasMultipleSigners()) return s.apkContentsSigners.orEmpty().map(::digest).toSet() to emptySet()
            val history = s.signingCertificateHistory.orEmpty().map(::digest)
            val current = s.apkContentsSigners.orEmpty().map(::digest).ifEmpty { history.takeLast(1) }
            return current.toSet() to history.toSet()
        }
        return info.signatures.orEmpty().map(::digest).toSet() to emptySet()
    }

    private fun digest(sig: android.content.pm.Signature): String =
        MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---- network ----------------------------------------------------------------------------

    private val http = MaliReleases.Http { url, headers, maxBytes ->
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            val code = c.responseCode
            val body = if (code == 200) {
                c.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (out.size() + n > maxBytes) throw MaliReleases.Failure(MaliReleases.Reason.ANSWER_TOO_LARGE, "", "The answer from GitHub is too large")
                        out.write(buf, 0, n)
                    }
                    out.toString(Charsets.UTF_8.name())
                }
            } else ""
            val hs = c.headerFields.orEmpty().filterKeys { it != null }.mapValues { it.value.firstOrNull().orEmpty() }
            MaliReleases.Response(code, hs, body)
        } finally {
            c.disconnect()
        }
    }

    private val ranged = MaliReleases.Ranged { url, from ->
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Accept", "application/octet-stream")
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        val code = c.responseCode
        val stream = if (code == 200 || code == 206) c.inputStream else (c.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0)))
        MaliReleases.Body(code, c.getHeaderField("Content-Range"), object : java.io.FilterInputStream(stream) {
            override fun close() { runCatching { super.close() }; c.disconnect() }
        })
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
