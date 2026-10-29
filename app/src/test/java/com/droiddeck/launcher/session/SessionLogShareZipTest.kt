package com.droiddeck.launcher.session

import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
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
        File(logs, "stores").apply { mkdirs() }.resolve("stores-2026-10-08.log").writeText("epic: f_token=zzzzzzzz12 window 32\n")
        File(logs, SessionPaths.TOOLS_DIR).apply { mkdirs() }.resolve("flatpak-install.log").writeText("== install\nCookie: steamLoginSecure=76561198000000000%7C%7Ceyabc\n")

        val zip = SessionLogShare.zipFolder(app, session)!!
        assertTrue(zip.path.startsWith(File(app.cacheDir, "share").path))
        val text = ZipFile(zip).use { z -> z.entries().toList().associate { e -> e.name to z.getInputStream(e).bufferedReader().readText() } }
        assertTrue(text.keys.containsAll(listOf("2026-10-08-01-steam/session.log", "stores/stores-2026-10-08.log", "tools/flatpak-install.log")))
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
}
