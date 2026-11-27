package com.droiddeck.launcher.frontend

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringWriter

class ArcadeTitlesTest {
    private val xml = """
        <?xml version="1.0"?>
        <mame build="0.78">
        	<game name="puckman" sourcefile="pacman.c">
        		<description>PuckMan (Japan set 1)</description>
        		<year>1980</year>
        		<manufacturer>Namco</manufacturer>
        		<rom name="namcopac.6e" size="4096"/>
        	</game>
        	<game name="Pacman" sourcefile="pacman.c" cloneof="puckman" romof="puckman">
        		<description>Pac-Man (Midway)</description>
        		<year>1980</year>
        		<manufacturer>[Namco] (Midway license)</manufacturer>
        	</game>
        	<game name="ddsom" sourcefile="cps2.c">
        		<description>Dungeons &amp; Dragons: Shadow over Mystara</description>
        		<year>1996</year>
        		<manufacturer>Capcom</manufacturer>
        	</game>
        	<game name="nodesc" sourcefile="x.c">
        	</game>
        </mame>
    """.trimIndent()

    private val ini = """
        ;### Based on CATVER.ini
        [FOLDER_SETTINGS]
        RootFolderIcon mame
        [Category]
        puckman=Maze / Collect
        ddsom=Fighter / Field
        [VerAdded]
        puckman=.36
    """.trimIndent()

    @Test fun readsSetsTitlesAndGenres() {
        val out = StringWriter()
        val n = ArcadeTitles.write(xml.reader().buffered(), ArcadeTitles.genres(ini.reader().buffered()), out)
        assertEquals(3, n)
        assertEquals(
            listOf(
                "puckman\tPuckMan (Japan set 1)\t1980\tNamco\tMaze / Collect",
                "pacman\tPac-Man (Midway)\t1980\t[Namco] (Midway license)\t",
                "ddsom\tDungeons & Dragons: Shadow over Mystara\t1996\tCapcom\tFighter / Field",
            ),
            out.toString().trimEnd('\n').lines(),
        )
    }

    /** With MAME_META=<dir holding mame2003-plus.xml and catver.ini> and MAME_EXPECT=<old bundled tsv> set: same rows. */
    @Test fun matchesTheOldBundledList() {
        val dir = System.getenv("MAME_META")?.let(::File)
        val expect = System.getenv("MAME_EXPECT")?.let(::File)
        assumeTrue(dir != null && expect != null && expect.isFile)
        val out = StringWriter()
        ArcadeTitles.write(File(dir, "mame2003-plus.xml").bufferedReader(), ArcadeTitles.genres(File(dir, "catver.ini").bufferedReader()), out)
        val got = out.toString().lines().filter { it.isNotEmpty() }.toSet()
        val want = expect!!.readLines().filter { it.isNotEmpty() }.toSet()
        assertEquals("missing: ${(want - got).take(5)} extra: ${(got - want).take(5)}", want, got)
    }
}
