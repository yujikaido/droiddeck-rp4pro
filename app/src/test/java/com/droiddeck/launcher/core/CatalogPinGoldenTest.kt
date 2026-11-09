package com.droiddeck.launcher.core

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.runtime.RuntimeAutoUpdate
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.FexCatalog
import com.droiddeck.launcher.session.WinComponents
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MaliBridge golden test (11-04, CATALOG-PIN): DroidDeck-Components' main moving must not change what this build
 * installs. Every request goes to a local "upstream" whose catalogs have all moved on (a new FEX build, Ubuntu rc5,
 * other Windows components, other desktop packages, a new Proton seed, a rebuilt DXVK package under the same name);
 * each reader must still give the snapshot pinned in this build (resources/catalog-pins), and read none of it live.
 * On rel/v0.5.5.3-t8 every test here fails: the app read those catalogs from main.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CatalogPinGoldenTest {
    private lateinit var server: ServerSocket
    private val requests = CopyOnWriteArrayList<String>()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun pinned(name: String): JSONObject = JSONObject(
        javaClass.classLoader!!.getResourceAsStream("catalog-pins/$name")!!.use { String(it.readBytes(), Charsets.UTF_8) })

    private val moved = mapOf(
        "fex.json" to """{"schemaVersion":1,"category":"fex","release":"fex","version":"2610-r10",
            "url":"https://github.com/Droid-Deck/DroidDeck-Components/releases/download/fex/fex.tzst","sha256":"${"a".repeat(64)}"}""",
        "linuxfs-ubuntu.json" to """{"schemaVersion":1,"category":"linux-runtime","release":"ubuntu-r1-rc5","version":"ubuntu-r1-rc5",
            "url":"https://github.com/Droid-Deck/DroidDeck-Components/releases/download/ubuntu-r1-rc5/ubuntu-r1-rc5.tar.zst","sha256":"${"b".repeat(64)}","size":1}""",
        "components.json" to """{"components":[{"name":"MovedUpstream","description":"pushed to main","status":"ready","steps":[]}]}""",
        "desktop.json" to """{"packages":[{"id":"desktop","name":"Moved","version":"r2","kind":"tar",
            "url":"https://github.com/Droid-Deck/DroidDeck-Components/releases/download/steamdeck-desktop-r2/desktop.tar.zst","sha256":"${"c".repeat(64)}"}]}""",
        "steam-seed.json" to """{"packages":[{"id":"proton-arm64","name":"Proton","version":"99999999","kind":"tar",
            "url":"https://github.com/Droid-Deck/DroidDeck-Components/releases/download/x/p.tar.zst","sha256":"${"d".repeat(64)}"}]}""",
    )

    @Before fun setUp() {
        server = ServerSocket(0)
        Thread {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    Thread { runCatching { answer(socket) } }.apply { isDaemon = true; start() }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        Downloader.policy = Downloader.SINGLE_TRY
        Downloader.testRedirect = java.util.function.UnaryOperator { "http://127.0.0.1:${server.localPort}/" + it.substringAfter("://") }
    }

    /** A moved catalog for a path ending in its name, 404 for anything else. */
    private fun answer(socket: Socket) = socket.use {
        val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = input.readLine()?.split(' ')?.getOrNull(1)?.substringBefore('?') ?: return
        while (input.readLine()?.isNotEmpty() == true) Unit
        requests += path
        val body = moved.entries.firstOrNull { e -> path.endsWith("/" + e.key) }?.value?.toByteArray()
        val out = it.getOutputStream()
        if (body == null) out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        else {
            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
        }
        out.flush()
    }

    @After fun tearDown() {
        Downloader.testRedirect = null
        Downloader.policy = Downloader.DEFAULT_POLICY
        server.close()
    }

    private fun assertNotRead(name: String) =
        assertTrue("$name was read from upstream: $requests", requests.none { it.endsWith("/$name") })

    @Test fun theFexBuildIsThePinnedOne() {
        val pin = pinned("fex.json")
        val build = FexCatalog.refresh(context, now = Long.MAX_VALUE / 2)
        assertNotNull(build)
        assertEquals("fex-" + pin.getString("version"), build!!.tag)
        assertEquals(pin.getString("sha256"), build.sha256)
        assertEquals(build, FexCatalog.cached(context))
        assertNotRead("fex.json")
    }

    @Test fun theRuntimeIsThePinnedOne() {
        val pin = pinned("linuxfs-ubuntu.json")
        val release = LinuxRuntimeInstaller.fetchRelease()
        assertNotNull(release)
        assertEquals(pin.getString("version"), release!!.version)
        assertEquals(pin.getString("sha256"), release.sha256)
        assertEquals(pin.getString("url"), release.url)
        assertNotRead("linuxfs-ubuntu.json")
    }

    @Test fun theDesktopPackagesAreThePinnedOnes() {
        val pin = pinned("desktop.json").getJSONArray("packages")
        val entries = DesktopCatalog.fetch()
        assertNotNull(entries)
        assertEquals((0 until pin.length()).map { pin.getJSONObject(it).getString("id") }, entries!!.map { it.id })
        assertEquals((0 until pin.length()).map { pin.getJSONObject(it).getString("version") }, entries.map { it.version })
        assertNotRead("desktop.json")
    }

    @Test fun theProtonSeedIsThePinnedOne() {
        val pin = pinned("steam-seed.json").getJSONArray("packages").getJSONObject(0)
        val seed = DesktopCatalog.fetch(DesktopCatalog.STEAM_SEED_URL)?.firstOrNull { it.id == DesktopCatalog.PROTON_SEED_ID }
        assertNotNull(seed)
        assertEquals(pin.getString("version"), seed!!.version)
        assertEquals(pin.getString("sha256"), seed.sha256)
        assertNotRead("steam-seed.json")
    }

    @Test fun theWindowsComponentsAreThePinnedOnes() {
        val pin = pinned("components.json").getJSONArray("components")
        val names = (0 until pin.length()).map { pin.getJSONObject(it).getString("name") }.toSet()
        val list = WinComponents.fetch(context)
        assertNotNull(list)
        assertFalse(list!!.any { it.name == "MovedUpstream" })
        assertTrue(list.map { it.name }.containsAll(names))
        assertEquals(list.map { it.name }, WinComponents.cached(context)?.map { it.name })
        assertNotRead("components.json")
    }

    @Test fun anAutoDxvkRebuiltUnderTheSameNameIsNotTaken() {
        // The live listing now carries a different build under the name the app picks by itself (Vulkan 1.3 without int64).
        val file = ComponentsManager.DXVK_FOR_VULKAN_1_3_NO_INT64
        val cache = File(context.filesDir, "components/catalog.json").apply { parentFile!!.mkdirs() }
        cache.writeText(JSONObject().put("fetchedAt", 1).put("items", JSONArray().put(JSONObject()
            .put("file", file).put("comp", "dxvk").put("release", "Dxvk-Linux").put("size", 1)
            .put("url", "https://github.com/The412Banner/Nightlies/releases/download/Dxvk-Linux/$file")
            .put("digest", "sha256:" + "e".repeat(64)))).toString())
        val why = ComponentsManager.fetchPackage(context, "dxvk", file, "Dxvk-Linux")
        assertTrue(why, why!!.contains("not the build MaliBridge pinned"))
        assertFalse(File(context.filesDir, "components/packages/$file").exists())
        assertTrue("the rebuilt package was downloaded: $requests", requests.none { it.endsWith("/$file") })
    }

    @Test fun anInstalledRuntimeIsNeverSwappedByItself() {
        // Neither on the bridge nor on any other GPU: a runtime other than the pinned one is only offered (Update).
        assertFalse(RuntimeAutoUpdate.allowed(bridge = true))
        assertFalse(RuntimeAutoUpdate.allowed(bridge = false))
        // And upstream's rc5 is never what the app offers over an installed rc4.
        assertEquals(pinned("linuxfs-ubuntu.json").getString("version"), LinuxRuntimeInstaller.fetchRelease()?.version)
    }
}
