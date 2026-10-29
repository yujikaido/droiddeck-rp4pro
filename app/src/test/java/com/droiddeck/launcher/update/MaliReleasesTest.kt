package com.droiddeck.launcher.update

import com.droiddeck.launcher.update.MaliReleases.ApkFacts
import com.droiddeck.launcher.update.MaliReleases.Asset
import com.droiddeck.launcher.update.MaliReleases.Fetch
import com.droiddeck.launcher.update.MaliReleases.Problem
import com.droiddeck.launcher.update.MaliReleases.Response
import com.droiddeck.launcher.update.MaliReleases.Standing
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class MaliReleasesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val repo = "ASEbrahim/MaliBridge"
    private val dl = "https://github.com/$repo/releases/download"
    private val sha = "ab".repeat(32)

    private fun asset(name: String, size: Long = 100, id: Long = 1, digest: String? = null, url: String = "$dl/v0.5.5/$name") =
        Asset(id, name, url, size, digest)

    private fun releaseJson(
        tag: String = "v0.5.5",
        assets: List<JSONObject> = listOf(
            assetJson("DroidDeck-MaliBridge-$tag.apk", 1000),
            assetJson("DroidDeck-MaliBridge-$tag.apk.sha256", 90),
        ),
    ) = JSONObject()
        .put("tag_name", tag)
        .put("name", "MaliBridge $tag")
        .put("body", "## Fixes\n- Balatro's **text**\n")
        .put("html_url", "https://github.com/$repo/releases/tag/$tag")
        .put("published_at", "2026-10-25T10:00:00Z")
        .put("draft", false)
        .put("prerelease", false)
        .put("assets", JSONArray(assets))

    private fun assetJson(name: String, size: Long, tag: String = "v0.5.5", digest: String? = null, url: String = "$dl/$tag/$name") =
        JSONObject().put("id", name.hashCode().toLong()).put("name", name).put("size", size)
            .put("state", "uploaded").put("browser_download_url", url).apply { if (digest != null) put("digest", digest) }

    // ---- versions ---------------------------------------------------------------------------

    @Test fun versionsCompareNumerically() {
        assertTrue(MaliReleases.compare("v0.5.4", "v0.5.3.1")!! > 0)
        assertTrue(MaliReleases.compare("v0.10", "v0.9.9")!! > 0)
        assertTrue(MaliReleases.compare("v0.5.3.1", "v0.5.3")!! > 0)
        assertEquals(0, MaliReleases.compare("v0.5", "0.5.0"))
        assertTrue(MaliReleases.compare("v1.0", "v0.99")!! > 0)
    }

    @Test fun letterSuffixesComeAfterTheirNumber() {
        assertTrue(MaliReleases.compare("v0.5.4b", "v0.5.4")!! > 0)
        assertTrue(MaliReleases.compare("v0.5.4c", "v0.5.4b")!! > 0)
        assertTrue(MaliReleases.compare("v0.5.5", "v0.5.4b")!! > 0)
    }

    @Test fun preReleasesComeBeforeTheRelease() {
        assertTrue(MaliReleases.compare("v0.6-rc1", "v0.6")!! < 0)
        assertTrue(MaliReleases.compare("v0.6-rc2", "v0.6-rc1")!! > 0)
        assertTrue(MaliReleases.compare("v0.6-rc.10", "v0.6-rc.9")!! > 0)
        assertTrue(MaliReleases.compare("v0.6-rc1", "v0.5.9")!! > 0)
    }

    @Test fun nonReleaseVersionsDoNotCompare() {
        assertNull(MaliReleases.compare("v0.5.1-101-g25058f5", "v0.5.4"))
        assertNull(MaliReleases.compare("", "v0.5.4"))
        assertNull(MaliReleases.compare("latest", "v0.5.4"))
        assertNull(MaliReleases.compare("v0.5.4", "0.4.0 beta"))
        assertEquals(MaliReleases.Version.parse("v0.5"), MaliReleases.Version.parse("v0.5.0"))
        assertEquals(MaliReleases.Version.parse("v0.5")!!.hashCode(), MaliReleases.Version.parse("v0.5.0")!!.hashCode())
    }

    @Test fun standingFollowsTheTags() {
        val newer = MaliReleases.parseRelease(releaseJson("v0.5.5"), repo)
        assertEquals(Standing.NEWER, MaliReleases.standing("v0.5.4", newer))
        assertEquals(Standing.CURRENT, MaliReleases.standing("v0.5.5", newer))
        assertEquals(Standing.CURRENT, MaliReleases.standing("v0.5.6", newer))
        assertEquals(Standing.UNKNOWN, MaliReleases.standing("", newer))
        val bare = MaliReleases.parseRelease(releaseJson("v0.5.5", emptyList()), repo)
        assertEquals(Standing.NO_APK, MaliReleases.standing("v0.5.4", bare))
    }

    // ---- release and assets -----------------------------------------------------------------

    @Test fun readsTheRelease() {
        val r = MaliReleases.parseRelease(releaseJson(), repo)
        assertEquals("v0.5.5", r.tag)
        assertEquals("MaliBridge v0.5.5", r.title)
        assertEquals("DroidDeck-MaliBridge-v0.5.5.apk", r.apk!!.name)
        assertEquals(1000L, r.apk!!.size)
        assertEquals("DroidDeck-MaliBridge-v0.5.5.apk.sha256", r.checksum!!.name)
        assertEquals(1_792_922_400_000L, r.publishedAt)
    }

    @Test fun refusesDraftsPreReleasesAndOddTags() {
        for (o in listOf(releaseJson().put("draft", true), releaseJson().put("prerelease", true), releaseJson("nightly"))) {
            try { MaliReleases.parseRelease(o, repo); fail() } catch (_: IOException) {}
        }
    }

    @Test fun ignoresAssetsOutsideTheRepository() {
        val r = MaliReleases.parseRelease(
            releaseJson(assets = listOf(
                assetJson("DroidDeck-MaliBridge-v0.5.5.apk", 1000, url = "https://example.com/DroidDeck-MaliBridge-v0.5.5.apk"),
                assetJson("DroidDeck-MaliBridge-v0.5.5.apk", 1000, url = "https://github.com/someone/MaliBridge/releases/download/v0.5.5/x.apk"),
            )),
            repo,
        )
        assertNull(r.apk)
    }

    @Test fun readsGithubsAssetDigest() {
        val r = MaliReleases.parseRelease(
            releaseJson(assets = listOf(assetJson("DroidDeck-MaliBridge-v0.5.5.apk", 1000, digest = "sha256:" + sha.uppercase()))),
            repo,
        )
        assertEquals(sha, r.apk!!.digest)
        assertNull(r.checksum)
    }

    @Test fun picksTheApkNamedAfterTheTag() {
        val assets = listOf(
            asset("DroidDeck-MaliBridge-v0.5.5-test.apk"),
            asset("DroidDeck-MaliBridge-v0.5.5.apk"),
            asset("DroidDeck-MaliBridge-v0.5.5.apk.sha256"),
            asset("vkbridge-client.tar.zst"),
        )
        assertEquals("DroidDeck-MaliBridge-v0.5.5.apk", MaliReleases.pickApk(assets, "v0.5.5")!!.name)
        assertEquals("DroidDeck-MaliBridge-v0.5.3-test.apk", MaliReleases.pickApk(listOf(asset("DroidDeck-MaliBridge-v0.5.3-test.apk")), "v0.5.3")!!.name)
        assertNull(MaliReleases.pickApk(listOf(asset("DroidDeck-MaliBridge-v0.5.3-test.apk")), "v0.5.3.1"))
        assertNull(MaliReleases.pickApk(listOf(asset("DroidDeck-MaliBridge-v0.5.4.apk")), "v0.5.5"))
    }

    @Test fun picksNoApkWhenItWouldBeAGuess() {
        assertNull(MaliReleases.pickApk(listOf(asset("DroidDeck-MaliBridge-v0.5.5-a.apk"), asset("DroidDeck-MaliBridge-v0.5.5-b.apk")), "v0.5.5"))
        assertNull(MaliReleases.pickApk(listOf(asset("DroidDeck.apk"), asset("DroidDeck-MaliBridge-v0.5.5.apk.sha256")), "v0.5.5"))
        assertNull(MaliReleases.pickApk(listOf(asset("DroidDeck-MaliBridge-v0.5.5.apk", size = 0)), "v0.5.5"))
        assertNull(MaliReleases.pickApk(listOf(asset("droiddeck-malibridge-v0.5.5.apk")), "v0.5.5"))
    }

    @Test fun picksTheChecksumForTheApk() {
        val apk = "DroidDeck-MaliBridge-v0.5.5.apk"
        assertEquals("$apk.sha256", MaliReleases.pickChecksum(listOf(asset("SHA256SUMS"), asset("$apk.sha256")), apk)!!.name)
        assertEquals("SHA256SUMS", MaliReleases.pickChecksum(listOf(asset("SHA256SUMS")), apk)!!.name)
        assertNull(MaliReleases.pickChecksum(listOf(asset("other.apk.sha256")), apk))
    }

    @Test fun readsChecksumFiles() {
        val apk = "DroidDeck-MaliBridge-v0.5.5.apk"
        assertEquals(sha, MaliReleases.parseChecksum("$sha  $apk\n", apk))
        assertEquals(sha, MaliReleases.parseChecksum("${sha.uppercase()} *$apk", apk))
        assertEquals(sha, MaliReleases.parseChecksum("$sha\n", apk))
        assertEquals(sha, MaliReleases.parseChecksum("$sha  out/$apk", apk))
        assertEquals(sha, MaliReleases.parseChecksum("${"cd".repeat(32)}  other.apk\n$sha  $apk", apk))
        assertNull(MaliReleases.parseChecksum("${"cd".repeat(32)}  other.apk", apk))
        assertNull(MaliReleases.parseChecksum("abc  $apk", apk))
        assertNull(MaliReleases.parseChecksum("", apk))
    }

    // ---- network (faked) --------------------------------------------------------------------

    private class FakeHttp(vararg answers: Response) : MaliReleases.Http {
        val queue = ArrayDeque(answers.toList())
        val asked = mutableListOf<Pair<String, Map<String, String>>>()
        override fun get(url: String, headers: Map<String, String>, maxBytes: Int): Response {
            asked += url to headers
            return queue.removeFirst()
        }
    }

    @Test fun fetchesLatestWithoutAToken() {
        val http = FakeHttp(Response(200, mapOf("ETag" to "\"abc\""), releaseJson().toString()))
        val f = MaliReleases.fetchLatest(http, repo, null, 0L) as Fetch.Fresh
        assertEquals("v0.5.5", f.release.tag)
        assertEquals("\"abc\"", f.etag)
        val (url, headers) = http.asked.single()
        assertEquals("https://api.github.com/repos/ASEbrahim/MaliBridge/releases/latest", url)
        assertFalse(headers.keys.any { it.equals("Authorization", true) })
        assertFalse(headers.containsKey("If-None-Match"))
        assertTrue(headers.containsKey("User-Agent"))
    }

    @Test fun sendsTheEtagAndAcceptsNotModified() {
        val http = FakeHttp(Response(304, emptyMap(), ""))
        assertTrue(MaliReleases.fetchLatest(http, repo, "\"abc\"", 0L) is Fetch.NotModified)
        assertEquals("\"abc\"", http.asked.single().second["If-None-Match"])
    }

    @Test fun waitsForTheRateLimitReset() {
        val now = 1_000_000_000L
        val reset = now / 1000 + 1800
        val http = FakeHttp(Response(403, mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "$reset"), ""))
        val f = MaliReleases.fetchLatest(http, repo, null, now) as Fetch.Limited
        assertEquals(reset * 1000, f.until)
    }

    @Test fun honoursRetryAfter() {
        val now = 5_000_000L
        val http = FakeHttp(Response(429, mapOf("Retry-After" to "120"), ""))
        assertEquals(now + 120_000, (MaliReleases.fetchLatest(http, repo, null, now) as Fetch.Limited).until)
    }

    @Test fun aForbiddenThatIsNotTheLimitIsAFailure() {
        val http = FakeHttp(Response(403, mapOf("x-ratelimit-remaining" to "42"), ""))
        assertEquals(403, (MaliReleases.fetchLatest(http, repo, null, 0L) as Fetch.Failed).code)
        assertEquals(404, (MaliReleases.fetchLatest(FakeHttp(Response(404, emptyMap(), "")), repo, null, 0L) as Fetch.Failed).code)
    }

    @Test fun expectedChecksumComesFromTheChecksumAsset() {
        val r = MaliReleases.parseRelease(releaseJson(), repo)
        val http = FakeHttp(Response(200, emptyMap(), "$sha  DroidDeck-MaliBridge-v0.5.5.apk\n"))
        assertEquals(sha, MaliReleases.expectedSha256(http, r))
        assertEquals(r.checksum!!.url, http.asked.single().first)
    }

    @Test fun anUnreadableChecksumAssetStopsTheUpdate() {
        val r = MaliReleases.parseRelease(releaseJson(), repo)
        for (answer in listOf(Response(404, emptyMap(), ""), Response(200, emptyMap(), "$sha  other.apk"))) {
            try { MaliReleases.expectedSha256(FakeHttp(answer), r); fail() } catch (_: IOException) {}
        }
    }

    @Test fun checksumAssetAndGithubDigestMustAgree() {
        val r = MaliReleases.parseRelease(
            releaseJson(assets = listOf(
                assetJson("DroidDeck-MaliBridge-v0.5.5.apk", 1000, digest = "sha256:" + "cd".repeat(32)),
                assetJson("DroidDeck-MaliBridge-v0.5.5.apk.sha256", 90),
            )),
            repo,
        )
        try { MaliReleases.expectedSha256(FakeHttp(Response(200, emptyMap(), sha)), r); fail() } catch (_: IOException) {}
    }

    @Test fun withoutAChecksumAssetGithubsDigestIsUsedOrNone() {
        val withDigest = MaliReleases.parseRelease(
            releaseJson(assets = listOf(assetJson("DroidDeck-MaliBridge-v0.5.5.apk", 1000, digest = "sha256:$sha"))), repo,
        )
        assertEquals(sha, MaliReleases.expectedSha256(FakeHttp(), withDigest))
        val bare = MaliReleases.parseRelease(releaseJson(assets = listOf(assetJson("DroidDeck-MaliBridge-v0.5.5.apk", 1000))), repo)
        assertNull(MaliReleases.expectedSha256(FakeHttp(), bare))
    }

    // ---- download (faked) -------------------------------------------------------------------

    private val payload = ByteArray(1000) { (it * 7).toByte() }

    private class FakeRanged(val answer: (Long) -> MaliReleases.Body) : MaliReleases.Ranged {
        val froms = mutableListOf<Long>()
        override fun open(url: String, from: Long): MaliReleases.Body { froms += from; return answer(from) }
    }

    private fun bytes(b: ByteArray): InputStream = ByteArrayInputStream(b)

    @Test fun downloadsAFreshFile() {
        val part = tmp.newFile("a.part").apply { delete() }
        val net = FakeRanged { MaliReleases.Body(200, null, bytes(payload)) }
        MaliReleases.download("u", part, payload.size.toLong(), net)
        assertTrue(part.readBytes().contentEquals(payload))
        assertEquals(listOf(0L), net.froms)
    }

    @Test fun continuesAPartialDownload() {
        val part = tmp.newFile("b.part").apply { writeBytes(payload.copyOf(400)) }
        val net = FakeRanged { from -> MaliReleases.Body(206, "bytes $from-999/1000", bytes(payload.copyOfRange(from.toInt(), 1000))) }
        MaliReleases.download("u", part, 1000, net)
        assertEquals(listOf(400L), net.froms)
        assertTrue(part.readBytes().contentEquals(payload))
    }

    @Test fun startsOverWhenTheServerIgnoresTheRange() {
        val part = tmp.newFile("c.part").apply { writeBytes(ByteArray(300) { 9 }) }
        MaliReleases.download("u", part, 1000, FakeRanged { MaliReleases.Body(200, null, bytes(payload)) })
        assertTrue(part.readBytes().contentEquals(payload))
    }

    @Test fun startsOverAfterRangeNotSatisfiable() {
        val part = tmp.newFile("d.part").apply { writeBytes(ByteArray(300) { 9 }) }
        val net = FakeRanged { from ->
            if (from > 0) MaliReleases.Body(416, null, bytes(ByteArray(0))) else MaliReleases.Body(200, null, bytes(payload))
        }
        MaliReleases.download("u", part, 1000, net)
        assertEquals(listOf(300L, 0L), net.froms)
        assertTrue(part.readBytes().contentEquals(payload))
    }

    @Test fun failuresNameTheirReasonForThePlayersLanguage() {
        val reasons = listOf(releaseJson().put("draft", true), releaseJson("nightly")).map { o ->
            (runCatching { MaliReleases.parseRelease(o, repo) }.exceptionOrNull() as MaliReleases.Failure).let { it.reason to it.arg }
        }
        assertEquals(listOf(MaliReleases.Reason.NOT_STABLE to "", MaliReleases.Reason.BAD_TAG to "nightly"), reasons)
        val part = tmp.newFile("r.part").apply { delete() }
        try {
            MaliReleases.download("u", part, 1000, FakeRanged { MaliReleases.Body(503, null, bytes(ByteArray(0))) })
            fail()
        } catch (e: MaliReleases.Failure) {
            assertEquals(MaliReleases.Reason.HTTP, e.reason)
            assertEquals("503", e.arg)
            assertEquals("The download failed (HTTP 503)", e.message)
        }
    }

    @Test fun aCutConnectionKeepsWhatArrived() {
        val part = tmp.newFile("e.part").apply { delete() }
        try {
            MaliReleases.download("u", part, 1000, FakeRanged { MaliReleases.Body(200, null, bytes(payload.copyOf(600))) })
            fail()
        } catch (_: IOException) {}
        assertEquals(600L, part.length())
    }

    @Test fun aLargerDownloadThanPublishedIsDropped() {
        val part = tmp.newFile("f.part").apply { delete() }
        try {
            MaliReleases.download("u", part, 500, FakeRanged { MaliReleases.Body(200, null, bytes(payload)) })
            fail()
        } catch (_: IOException) {}
        assertFalse(part.exists())
    }

    @Test fun aWrongContentRangeIsNotAppended() {
        val part = tmp.newFile("g.part").apply { writeBytes(payload.copyOf(400)) }
        try {
            MaliReleases.download("u", part, 1000, FakeRanged { MaliReleases.Body(206, "bytes 0-999/1000", bytes(payload)) })
            fail()
        } catch (_: IOException) {}
        assertEquals(400L, part.length())
    }

    @Test fun aCompleteFileIsNotDownloadedAgain() {
        val part = tmp.newFile("h.part").apply { writeBytes(payload) }
        val net = FakeRanged { fail(); throw IllegalStateException() }
        MaliReleases.download("u", part, 1000, net)
        assertTrue(net.froms.isEmpty())
    }

    // ---- verification -----------------------------------------------------------------------

    private val mine = setOf("a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc")
    private val pkg = "com.droiddeck.launcher.mali"

    @Test fun acceptsTheSameAppWithTheSameKey() {
        assertNull(MaliReleases.apkProblem(ApkFacts(pkg, 13, mine.map { it.uppercase() }.toSet()), pkg, 13, mine))
        assertNull(MaliReleases.apkProblem(ApkFacts(pkg, 14, mine), pkg, 13, mine))
    }

    @Test fun refusesAnotherKey() {
        assertEquals(Problem.WRONG_SIGNER, MaliReleases.apkProblem(ApkFacts(pkg, 13, setOf("b".repeat(64))), pkg, 13, mine))
        assertEquals(Problem.WRONG_SIGNER, MaliReleases.apkProblem(ApkFacts(pkg, 13, mine + "b".repeat(64)), pkg, 13, mine))
        assertEquals(Problem.NO_SIGNER, MaliReleases.apkProblem(ApkFacts(pkg, 13, emptySet()), pkg, 13, mine))
        assertEquals(Problem.NO_SIGNER, MaliReleases.apkProblem(ApkFacts(pkg, 13, mine), pkg, 13, emptySet()))
    }

    @Test fun acceptsAKeyRotatedFromThisOne() {
        val rotated = ApkFacts(pkg, 13, setOf("c".repeat(64)), mine + "c".repeat(64))
        assertNull(MaliReleases.apkProblem(rotated, pkg, 13, mine))
    }

    @Test fun refusesAnotherPackageOrAnOlderBuild() {
        assertEquals(Problem.WRONG_PACKAGE, MaliReleases.apkProblem(ApkFacts("com.droiddeck.launcher", 13, mine), pkg, 13, mine))
        assertEquals(Problem.OLDER_VERSION_CODE, MaliReleases.apkProblem(ApkFacts(pkg, 12, mine), pkg, 13, mine))
        assertEquals(Problem.UNREADABLE, MaliReleases.apkProblem(null, pkg, 13, mine))
        assertEquals(Problem.UNREADABLE, MaliReleases.apkProblem(ApkFacts(null, 13, mine), pkg, 13, mine))
    }

    @Test fun checksumMustMatchWhenPublished() {
        assertNull(MaliReleases.checksumProblem(sha, sha.uppercase()))
        assertEquals(Problem.NO_CHECKSUM, MaliReleases.checksumProblem(sha, null))
        assertEquals(Problem.CHECKSUM, MaliReleases.checksumProblem(sha, "cd".repeat(32)))
    }

    @Test fun onlyTheMaliBuildFollowsMaliBridgeReleases() {
        assertEquals(com.droiddeck.launcher.BuildConfig.APPLICATION_ID.endsWith(".mali"), MaliUpdater.forThisBuild)
        if (MaliUpdater.forThisBuild) assertEquals("ASEbrahim/MaliBridge", MaliUpdater.repo)
        assertTrue(MaliReleases.Version.parse(com.droiddeck.launcher.BuildConfig.MALI_RELEASE) != null)
    }

    // ---- notes ------------------------------------------------------------------------------

    @Test fun notesBecomeAShortPlainExcerpt() {
        val n = MaliReleases.notesExcerpt("## Fixes\r\n\r\n\r\n- Balatro's **text** ([#12](https://x))\n* `RE2` faster\n<!-- hidden -->\n![shot](a.png)")
        assertEquals("Fixes\n\n• Balatro's text (#12)\n• RE2 faster", n)
        val long = MaliReleases.notesExcerpt("word ".repeat(400), 100)
        assertTrue(long.length <= 101 && long.endsWith("…"))
    }
}
