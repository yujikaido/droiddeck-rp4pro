package com.droiddeck.launcher.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Chinese (Simplified, Traditional for Taiwan and Hong Kong) and Korean carry every string the app has:
 * a key added to values/ without them fails here instead of showing English to those players.
 * Strings marked translatable="false" are left out. The placeholders (%1$s, %2$d, %%) must match too,
 * since a missing or extra one throws when the string is formatted.
 */
class TranslationCoverageTest {
    private val res = File("src/main/res")
    private val complete = listOf("zh-rCN", "zh-rTW", "zh-rHK", "ko")

    private class Entry(val translatable: Boolean, val text: String)

    private fun load(folder: String): Map<String, Entry> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$folder/strings.xml"))
        val out = LinkedHashMap<String, Entry>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.tagName !in setOf("string", "plurals", "string-array")) continue
            out["${e.tagName}/${e.getAttribute("name")}"] = Entry(e.getAttribute("translatable") != "false", e.textContent)
        }
        return out
    }

    private fun placeholders(text: String) = PLACEHOLDER.findAll(text).map { it.value }.toSortedSet()

    @Test fun everyTranslatableStringIsInChineseAndKorean() {
        val base = load("values").filterValues { it.translatable }
        complete.forEach { locale ->
            val have = load("values-$locale")
            val missing = base.keys - have.keys
            assertTrue("values-$locale/strings.xml misses ${missing.size}: ${missing.take(40)}", missing.isEmpty())
        }
    }

    @Test fun placeholdersMatchTheEnglish() {
        val base = load("values").filterValues { it.translatable }.filterKeys { it.startsWith("string/") }
        complete.forEach { locale ->
            val have = load("values-$locale")
            val wrong = base.filter { (k, v) -> have[k]?.let { placeholders(it.text) != placeholders(v.text) } == true }.keys
            assertTrue("values-$locale/strings.xml has other placeholders than English in: $wrong", wrong.isEmpty())
        }
    }

    private companion object {
        val PLACEHOLDER = Regex("""%(\d+\$)?[sdf]|%%""")
    }
}
