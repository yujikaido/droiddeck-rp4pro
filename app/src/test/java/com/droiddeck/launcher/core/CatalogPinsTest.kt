package com.droiddeck.launcher.core

import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.FexCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MaliBridge: the pinned DroidDeck-Components snapshot (CatalogPins) is complete and is what the readers use. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CatalogPinsTest {
    @Test fun everyCatalogIsPinnedAndMatchesItsDigest() {
        for (name in listOf(CatalogPins.FEX, CatalogPins.RUNTIME, CatalogPins.WIN_COMPONENTS, CatalogPins.DESKTOP, CatalogPins.STEAM_SEED))
            assertTrue(name, CatalogPins.text(name).isNotEmpty())
        assertTrue(Regex("[0-9a-f]{40}").matches(CatalogPins.commit))
    }

    @Test fun everyPackageTheAppPicksByItselfHasAPinnedDigest() {
        val auto = ComponentsManager.FORCED_PACKAGES.map { it.release to it.file } +
            listOf("Dxvk-Linux" to ComponentsManager.DXVK_FOR_VULKAN_1_1, "Dxvk-Linux" to ComponentsManager.DXVK_FOR_VULKAN_1_3_NO_INT64)
        for ((release, file) in auto) assertNotNull("$release/$file", CatalogPins.nightlySha256(release, file))
    }

    @Test fun anAutoPackageIsTakenOnlyWithItsPinnedDigest() {
        val item = { digest: String -> ComponentsManager.CatalogItem("dxvk-2.6.2-linux.wcp", "dxvk", "Dxvk-Linux", "u", 1, digest) }
        val pin = CatalogPins.nightlySha256("Dxvk-Linux", "dxvk-2.6.2-linux.wcp")!!
        assertTrue(ComponentsManager.autoDigestMatches("Dxvk-Linux", item("sha256:$pin")))
        assertFalse(ComponentsManager.autoDigestMatches("Dxvk-Linux", item("sha256:" + "0".repeat(64))))
        // A package the player picks from the list has no pin and is taken as listed.
        assertTrue(ComponentsManager.autoDigestMatches("Dxvk-Linux", item("sha256:" + "0".repeat(64)), pinned = null))
    }

    @Test fun theFexArchiveAlsoComesFromItsMirrorCheckedByTheSameDigest() {
        val build = FexCatalog.parse(CatalogPins.text(CatalogPins.FEX))!!
        val env = FexCatalog.environment(build, listOf(build.url) + CatalogPins.mirrors(build.url))
        val urls = env.first { it.startsWith("BL_FEX_URLS=") }.removePrefix("BL_FEX_URLS=").split(' ')
        assertEquals(build.url, urls.first())
        assertTrue(urls.drop(1).isNotEmpty() && urls.drop(1).all { it.startsWith("https://github.com/") && it.endsWith(".tzst") })
        assertTrue(env.contains("BL_FEX_SHA256=${build.sha256}"))
    }

    // Release prep T11 (owner 11-04): the pin moved to DroidDeck-Components f1f0ad0a (one change: vcredist2010_dll is the full
    // VC++ 2010 runtime, CRT + MFC + ATL + OpenMP; its CRT DLLs are byte-identical to 4ba1759e's). Fails on t10 (4ba1759e: CRT only,
    // so a game needing mfc100/atl100/vcomp100 failed on t10 while v0.5.5.2, reading main live, had them since 11-04 17:35Z).
    @Test fun theVcRedist2010ComponentIsTheFullRuntimeWithItsPinnedDigest() {
        assertEquals("f1f0ad0a658640088fc1d706fb2f7d3ca37617df", CatalogPins.commit)
        val vc = org.json.JSONObject(CatalogPins.text(CatalogPins.WIN_COMPONENTS)).getJSONArray("components").let { all ->
            (0 until all.length()).map { all.getJSONObject(it) }.first { it.optString("name") == "vcredist2010_dll" }
        }
        val steps = vc.getJSONArray("steps").let { s -> (0 until s.length()).map { s.getJSONObject(it) } }
        val url = steps.first { it.optString("action") == "archive_extract" }.optString("url")
        assertEquals("https://github.com/Droid-Deck/DroidDeck-Components/releases/download/system-libraries-v1/vcredist2010_full__libs.tar.xz", url)
        assertEquals("01e4d753b96b665b2bddc292bb2b355a98adec8e63953a312bf05738b1370f8b", CatalogPins.assetSha256(url))
        val overridden = steps.filter { it.optString("action") == "override_dll" }.map { it.optString("dll") }.toSet()
        assertTrue(overridden.containsAll(setOf("msvcp100", "msvcr100", "mfc100", "mfc100u", "atl100", "vcomp100")))
    }

    @Test fun componentAssetsWithoutADigestInTheCatalogGetAPinnedOne() {
        val url = "https://github.com/Droid-Deck/DroidDeck-Components/releases/download/system-libraries-v1/dx8vb__libs.tar.xz"
        assertTrue(Regex("[0-9a-f]{64}").matches(CatalogPins.assetSha256(url)!!))
        assertNull(CatalogPins.assetSha256("https://example.com/other.tar.xz"))
    }
}
