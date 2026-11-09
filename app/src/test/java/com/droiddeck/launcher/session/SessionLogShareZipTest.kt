package com.droiddeck.launcher.session

import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionLogShareZipTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun aLargeSteamLogIsRedactedInTheSharedArchive() {
        val session = File(LinuxRuntime.logDir(app), "2026-10-08-02-steam").apply { mkdirs() }
        val raw = File(session, "steam-42.log")
        val earlySecret = "early-sessionid-abcdef123456"
        val trailingSecret = "late-refresh-token-abcdef123456"
        try {
            // One long line exercises the streaming limit; the final ordinary line must still be redacted.
            raw.bufferedWriter().use { out ->
                out.write("sessionid=$earlySecret")
                val chunk = "x".repeat(8192)
                repeat(8192) { out.write(chunk) }
                out.write("\nrefresh_token=$trailingSecret\n")
            }
            assertTrue(raw.length() > 64L * 1024 * 1024)

            val zip = SessionLogShare.zipFolder(app, session)!!
            ZipFile(zip).use { archive ->
                val entry = archive.getEntry("${session.name}/steam-42.log")!!
                archive.getInputStream(entry).use { input ->
                    // Only inspect the bounded scrubbed prefix: the pre-fix raw archive is over 64 MiB.
                    val expected = "${com.droiddeck.launcher.core.LogRedactor.OVERSIZED_LINE}\n"
                    val prefix = ByteArray(expected.toByteArray(StandardCharsets.UTF_8).size)
                    var offset = 0
                    while (offset < prefix.size) {
                        val count = input.read(prefix, offset, prefix.size - offset)
                        assertTrue("zip entry ended before its scrubbed prefix", count > 0)
                        offset += count
                    }
                    assertEquals(expected, String(prefix, StandardCharsets.UTF_8))
                    input.bufferedReader().use { reader ->
                        val trailing = requireNotNull(reader.readLine())
                        assertFalse("trailing secret survived", trailing.contains(trailingSecret))
                        assertEquals("refresh_token=<redacted:token>", trailing)
                        assertEquals(null, reader.readLine())
                    }
                }
            }
        } finally {
            SessionLogShare.clear(app)
            session.deleteRecursively()
        }
    }

    @Test fun everyTextFileInTheZipIsScrubbedAndTheStoresAndToolsLogsComeAlong() {
        val logs = LinuxRuntime.logDir(app)
        val session = File(logs, "2026-10-08-01-steam").apply { mkdirs() }
        File(session, "session.log").writeText(
            "fetch https://gog-cdn-fastly.gog.com/token=nva=1791500000~dirs=/x~token=0a1b2c3d4e5f/content-system/v2/store/1/ab/cd/f\n" +
                "fetch https://epicgames-download1.akamaized.net/Builds/Org/a/ChunksV4/12/ABCD.chunk?__token__=exp=1~hmac=deadbeefcafe\n" +
                "login refresh_token=abcdef123456 user someone@example.com code=AUTHCODE123\n" +
                "Authorization: Bearer abc.def.ghi\n" +
                "kept: EResult 5, app 1086940, steamapps/common/Game\n"
        )
        // StoreLogFiles.prune, started by App.onCreate, deletes a stores log named for a day over a week old.
        val storesLog = "stores-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".log"
        File(logs, "stores").apply { mkdirs() }.resolve(storesLog).writeText("epic: f_token=zzzzzzzz12 window 32\n")
        File(logs, SessionPaths.TOOLS_DIR).apply { mkdirs() }.resolve("flatpak-install.log").writeText("== install\nCookie: steamLoginSecure=76561198000000000%7C%7Ceyabc\n")

        val zip = SessionLogShare.zipFolder(app, session)!!
        assertTrue(zip.path.startsWith(File(app.cacheDir, "share").path))
        val text = ZipFile(zip).use { z -> z.entries().toList().associate { e -> e.name to z.getInputStream(e).bufferedReader().readText() } }
        assertTrue(text.keys.containsAll(listOf("2026-10-08-01-steam/session.log", "stores/$storesLog", "tools/flatpak-install.log")))
        val all = text.values.joinToString("\n")
        for (secret in listOf("0a1b2c3d4e5f", "deadbeefcafe", "abcdef123456", "someone@example.com", "AUTHCODE123", "abc.def.ghi", "zzzzzzzz12", "eyabc")) {
            assertFalse("$secret survived", all.contains(secret))
        }
        assertTrue(all.contains("kept: EResult 5, app 1086940, steamapps/common/Game"))
        assertTrue(all.contains("https://gog-cdn-fastly.gog.com/"))

        // A zip just handed to another app stays (it may still be reading it); an hour-old one goes.
        SessionLogShare.clear(app)
        assertTrue(zip.exists())
        zip.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)
        SessionLogShare.clear(app)
        assertFalse(zip.exists())
    }

    @Test fun protonLogsWrittenDuringTheSessionComeAlongScrubbedAndALongOneIsCut() {
        val session = File(LinuxRuntime.logDir(app), "2026-10-22-01-steam").apply { mkdirs() }
        val start = 1_790_000_000_000L
        File(session, "session.log").apply { writeText("start\n"); setLastModified(start) }
        File(session, "events.jsonl").apply { writeText("{}\n"); setLastModified(start + 600_000) }
        val home = File(LinuxRuntime.rootDir(app), "root").apply { mkdirs() }
        File(home, "steam-560430.log").apply {
            writeText("err:d3d:wined3d_check_gl_call invalid camera matrix\nlogin someone@example.com\n"); setLastModified(start + 60_000)
        }
        File(home, "steam-413150.log").apply { writeText("an older run\n"); setLastModified(start - 86_400_000) }
        File(home, "steam-268910.log").apply {
            writeText("x".repeat(100) + "\n" + "0024:fixme:line\n".repeat(600_000) + "the end\n"); setLastModified(start + 120_000)
        }

        val zip = SessionLogShare.zipFolder(app, session)!!
        val text = ZipFile(zip).use { z -> z.entries().toList().associate { e -> e.name to z.getInputStream(e).bufferedReader().readText() } }
        val p = "2026-10-22-01-steam/proton/"
        assertTrue(text.keys.containsAll(listOf(p + "steam-560430.log", p + "steam-268910.log")))
        assertFalse(text.containsKey(p + "steam-413150.log"))
        assertTrue(text.getValue(p + "steam-560430.log").contains("invalid camera matrix"))
        assertFalse(text.getValue(p + "steam-560430.log").contains("someone@example.com"))
        val long = text.getValue(p + "steam-268910.log")
        assertTrue(long.startsWith("[first ") && long.endsWith("the end\n"))
        assertFalse(long.contains("xxxx"))
        assertTrue(long.length <= 8 * 1024 * 1024 + 64)
        SessionLogShare.clear(app)
    }

    @Test fun theGamesOwnLogsFromTheSessionComeAlongScrubbed() {
        val session = File(LinuxRuntime.logDir(app), "2026-10-24-05-desktop").apply { mkdirs() }
        val start = 1_790_100_000_000L
        File(session, "session.log").apply { writeText("start\n"); setLastModified(start) }
        File(session, "events.jsonl").apply { writeText("{}\n"); setLastModified(start + 600_000) }
        val appData = File(LinuxRuntime.rootDir(app), "root/.local/share/Steam/steamapps/compatdata/294100/pfx/drive_c/users/steamuser/AppData")
        File(appData, "LocalLow/Ludeon Studios/RimWorld by Ludeon Studios").apply { mkdirs() }.let { dir ->
            File(dir, "Player.log").apply { writeText("Loading game\nlogin someone@example.com\n"); setLastModified(start + 60_000) }
            File(dir, "Player-prev.log").apply { writeText("an older run\n"); setLastModified(start - 86_400_000) }
        }
        File(appData, "Local/b1/Saved/Logs").apply { mkdirs() }.resolve("b1.log").apply { writeText("Fatal error\n"); setLastModified(start + 90_000) }

        val zip = SessionLogShare.zipFolder(app, session)!!
        val text = ZipFile(zip).use { z -> z.entries().toList().associate { e -> e.name to z.getInputStream(e).bufferedReader().readText() } }
        val g = "2026-10-24-05-desktop/game/294100/"
        val player = g + "LocalLow/Ludeon Studios/RimWorld by Ludeon Studios/Player.log"
        assertTrue(text.keys.containsAll(listOf(player, g + "Local/b1/Saved/Logs/b1.log")))
        assertFalse(text.containsKey(g + "LocalLow/Ludeon Studios/RimWorld by Ludeon Studios/Player-prev.log"))
        assertTrue(text.getValue(player).contains("Loading game"))
        assertFalse(text.getValue(player).contains("someone@example.com"))
        SessionLogShare.clear(app)
    }

    @Test fun battleNetAndBlizzardLogsComeAlongScrubbedAndNewestFirst() {
        val session = File(LinuxRuntime.logDir(app), "2026-10-25-05-battle-net").apply { mkdirs() }
        File(session, "session.log").writeText("droiddeck-battlenet: open (from the menu)\n")
        val root = LinuxRuntime.rootDir(app)
        File(root, "root/.cache/droiddeck/battlenet").apply { mkdirs() }.apply {
            resolve("battlenet.log").writeText("launching WoW\n")
            resolve("other.log").writeText("other\n")
        }
        val drive = File(com.droiddeck.launcher.runtime.BattleNet.prefix(app), "pfx/drive_c")
        val client = File(drive, "users/steamuser/AppData/Local/Battle.net/Logs").apply { mkdirs() }
        val now = System.currentTimeMillis()
        for (i in 1..8) client.resolve("battle.net-$i.log").apply { writeText("client $i accountId=1:0:5ca1ab1e\n"); setLastModified(now - i * 60_000L) }
        client.resolve("battle.net-old.log").apply { writeText("old\n"); setLastModified(now - 30L * 86_400_000L) }
        File(drive, "ProgramData/Battle.net/Agent/Agent.9824/Logs").apply { mkdirs() }.resolve("Agent-1.log").writeText("agent someone@example.com\n")
        File(drive, "ProgramData/Battle.net/Agent/Agent.9900/Logs").apply { mkdirs() }.resolve("Agent-1.log").writeText("agent 9900\n")
        File(drive, "ProgramData/Battle.net/Setup/bna_2/Logs").apply { mkdirs() }.resolve("battle.net-setup-1.log").writeText("setup\n")

        val zip = SessionLogShare.zipFolder(app, session)!!
        val text = ZipFile(zip).use { z -> z.entries().toList().associate { e -> e.name to z.getInputStream(e).bufferedReader().readText() } }
        assertTrue(text.keys.containsAll(listOf("battlenet/droiddeck/battlenet.log", "battlenet/agent/Agent.9824/Agent-1.log", "battlenet/agent/Agent.9900/Agent-1.log", "battlenet/setup/bna_2/battle.net-setup-1.log")))
        assertFalse(text.containsKey("battlenet/droiddeck/other.log"))
        assertEquals((1..6).map { "battlenet/client/battle.net-$it.log" }.toSet(), text.keys.filter { it.startsWith("battlenet/client/") }.toSet())
        val all = text.values.joinToString("\n")
        assertFalse(all.contains("5ca1ab1e"))
        assertFalse(all.contains("someone@example.com"))
        SessionLogShare.clear(app)
    }

    @Test fun theSharePassChangesNothingInACleanLine() {
        val line = "dxvk: Device 0x5 (Adreno 750) https://github.com/doitsujin/dxvk/releases code 403"
        assertEquals(line, com.droiddeck.launcher.core.LogRedactor.redactForShare(line))
    }

    private fun olderFolder(parent: File, name: String, marker: String = ".scrubbed-2"): File {
        val dir = File(parent, name).apply { mkdirs() }
        File(dir, marker).writeText("scrubbed earlier\n")
        File(dir, SessionArtifacts.SCRUBBED_TREE_MARKER).writeText("rules 2\n")
        File(dir, "steam").apply { mkdirs() }.resolve("webhelper_js.txt").writeText(
            "[2026-10-01 20:00:00] SteamUI: INFO: Login: OnLoginStateChange someone.masked@example.com 2 1 0 0\n" +
                "[2026-10-01 20:00:01] SteamUI: INFO: Login: OnLoginStateChange maskeduser42 2 1 0 0\n" +
                "[2026-10-01 20:00:02] SteamUI: INFO: Login: OnLoginStateChange  0 1 0 0\n" +
                "0024:trace:seh:dispatch_exception code=c0000005 flags=0\n"
        )
        return dir
    }

    private fun assertScrubbed(dir: File) {
        val text = File(dir, "steam/webhelper_js.txt").readText()
        for (secret in listOf("someone.masked", "example.com", "maskeduser42")) assertFalse("$secret survived in $dir", text.contains(secret))
        assertTrue(text.contains("Login: OnLoginStateChange <redacted:account> 2 1 0 0"))
        assertTrue(text.contains("Login: OnLoginStateChange  0 1 0 0"))
        assertTrue(text.contains("code=c0000005"))
        val markers = dir.list()!!.filter { it.startsWith(".scrubbed-") && it != SessionArtifacts.SCRUBBED_TREE_MARKER }
        assertEquals("$dir", listOf(".scrubbed-r${com.droiddeck.launcher.core.LogRedactor.RULES_VERSION}"), markers)
        assertTrue(File(dir, SessionArtifacts.SCRUBBED_TREE_MARKER).readText()
            .startsWith("rules ${com.droiddeck.launcher.core.LogRedactor.RULES_VERSION}\n"))
    }

    @Test fun aFolderMarkedUnderOlderRulesIsScrubbedAgainAtStart() {
        val dir = olderFolder(LinuxRuntime.logDir(app), "2026-10-01-01-steam")
        SessionArtifacts.scrubOlder(app)
        assertScrubbed(dir)
    }

    @Test fun everyMigratedFolderIsScrubbedUnderTheCurrentRulesInOneStart() {
        val legacy = LinuxRuntime.legacyLogDir().apply { mkdirs() }
        val names = (1..9).map { "2026-10-0${it}-01-steam" }
        names.forEachIndexed { i, name ->
            olderFolder(legacy, name, marker = listOf(".scrubbed-2", ".scrubbed-1", ".scrubbed-r${com.droiddeck.launcher.core.LogRedactor.RULES_VERSION}")[i % 3])
        }
        File(legacy, "Saves").apply { mkdirs() }.resolve("keep.txt").writeText("save\n")

        LogMigration.run(app)
        SessionArtifacts.scrubOlder(app)

        val logs = LinuxRuntime.logDir(app)
        for (name in names) assertScrubbed(File(logs, name))
        assertEquals(listOf("Saves"), legacy.list()!!.toList())
    }

    @Test fun aSessionThatNeverRanBringsTheLastOneThatDid() {
        val logs = LinuxRuntime.logDir(app)
        val older = File(logs, "2026-10-29-01-steam").apply { mkdirs() }
        File(older, "session.log").writeText("an older run\n")
        val ran = File(logs, "2026-10-29-02-steam").apply { mkdirs() }
        File(ran, "session.log").writeText("the run with the problem\n")
        File(ran, "vkbridge-server.log").writeText("server\n")
        val cancelled = File(logs, "2026-10-29-03-steam").apply { mkdirs() }
        File(cancelled, "events.jsonl").writeText("{\"event\":\"session.cancelled\"}\n")
        File(cancelled, "crash.log").writeText("\n")

        val names = ZipFile(SessionLogShare.zipFolder(app, cancelled)!!).use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.containsAll(listOf("2026-10-29-03-steam/events.jsonl", "2026-10-29-02-steam/session.log", "2026-10-29-02-steam/vkbridge-server.log")))
        assertFalse(names.any { it.startsWith("2026-10-29-01-steam/") })

        // A session that ran is sent alone
        val alone = ZipFile(SessionLogShare.zipFolder(app, ran)!!).use { z -> z.entries().toList().map { it.name } }
        assertFalse(alone.any { it.startsWith("2026-10-29-01-steam/") || it.startsWith("2026-10-29-03-steam/") })
    }

    /* Exit-at-start diagnostics (droiddeck-game-env: a title that closed at its start without a frame gets Proton's log for its
     * next launch, into <session>/exit-diag/): each file comes along scrubbed, a long one cut to its last 4 MB (the end holds the exit). */
    @Test fun exitDiagnosticLogsComeAlongScrubbedAndALongOneIsCutToItsEnd() {
        val session = File(LinuxRuntime.logDir(app), "2026-11-05-03-steam").apply { mkdirs() }
        File(session, "session.log").writeText("start\n")
        val diag = File(session, "exit-diag").apply { mkdirs() }
        File(diag, "steam-424840.log").writeText("x".repeat(100) + "\n" + "0024:trace:loaddll:line\n".repeat(300_000) + "login someone@example.com\nthe exit\n")
        File(diag, "LittleNightmares_d3d11.log").writeText("info:  DXVK: v2.7\n")
        try {
            val zip = SessionLogShare.zipFolder(app, session)!!
            val text = ZipFile(zip).use { z -> z.entries().toList().associate { e -> e.name to z.getInputStream(e).bufferedReader().readText() } }
            val p = "2026-11-05-03-steam/exit-diag/"
            assertEquals("info:  DXVK: v2.7\n", text.getValue(p + "LittleNightmares_d3d11.log"))
            val long = text.getValue(p + "steam-424840.log")
            assertTrue(long.startsWith("[first ") && long.endsWith("the exit\n"))
            assertFalse(long.contains("xxxx"))
            assertFalse(long.contains("someone@example.com"))
            assertTrue(long.length <= 4 * 1024 * 1024 + 64)
        } finally {
            SessionLogShare.clear(app)
            session.deleteRecursively()
        }
    }
}
