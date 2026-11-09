package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuildWatermarkTest {
    @Test
    fun stableAndPreviewBuildsHaveNoWatermark() {
        assertNull(BuildWatermark.lines(pr = 0, ci = true, commit = "abcdef0123", title = "", branch = ""))
    }

    @Test
    fun prBuildShowsNumberHashAndTitle() {
        assertEquals(
            listOf("PR-1234 | abcdef0", "Fix: foo the bar"),
            BuildWatermark.lines(pr = 1234, ci = true, commit = "abcdef0123", title = "Fix: foo the bar", branch = "x"),
        )
    }

    @Test
    fun longTitlesTruncateAtEightyCharacters() {
        val lines = BuildWatermark.lines(pr = 7, ci = true, commit = "abcdef0", title = "a".repeat(120), branch = "")!!
        assertEquals(80, lines[1].length)
        assertEquals('…', lines[1].last())
    }

    @Test
    fun localBuildShowsBranch() {
        assertEquals(
            listOf("LOCAL | abcdef0", "feat/thing"),
            BuildWatermark.lines(pr = 0, ci = false, commit = "abcdef0123", title = "", branch = "feat/thing", maliStable = false, maliRelease = ""),
        )
    }

    @Test
    fun maliBridgeReleaseBuildHasNoWatermark() {
        assertNull(
            BuildWatermark.lines(
                pr = 0, ci = false, commit = "abcdef0123", title = "", branch = "hotfix/compat-tool-respect", maliStable = true,
            ),
        )
    }

    @Test
    fun maliBridgeTestBuildHasNoWatermark() {
        assertNull(
            BuildWatermark.lines(
                pr = 0, ci = false, commit = "abcdef0123", title = "", branch = "rel/v0.5.5.3-t4", maliStable = false,
                maliRelease = "v0.5.5.3-test4",
            ),
        )
    }

    @Test
    fun localDeveloperBuildKeepsWatermark() {
        assertEquals(
            listOf("LOCAL | abcdef0", "hotfix/compat-tool-respect"),
            BuildWatermark.lines(
                pr = 0, ci = false, commit = "abcdef0123", title = "", branch = "hotfix/compat-tool-respect", maliStable = false,
                maliRelease = "",
            ),
        )
    }

    @Test
    fun prBuildKeepsWatermarkEvenWithReleaseFlag() {
        assertEquals(
            listOf("PR-12 | abcdef0", "Fix"),
            BuildWatermark.lines(pr = 12, ci = true, commit = "abcdef0123", title = "Fix", branch = "", maliStable = true),
        )
    }
}
