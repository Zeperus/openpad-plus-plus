package io.github.zeperus.openpad.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The UI is English with a German translation; every user-visible string lives in resources. */
class LocalizationTest {
    private val res = File("src/main/res")

    private class Entry(val kind: String, val text: String)

    private fun load(dir: String): Map<String, Entry> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val out = LinkedHashMap<String, Entry>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            when (n.nodeName) {
                "string" -> out[n.attributes.getNamedItem("name").nodeValue] = Entry("string", n.textContent)
                "plurals" -> out[n.attributes.getNamedItem("name").nodeValue] = Entry("plurals", n.textContent)
            }
        }
        return out
    }

    private val english get() = load("values")
    private val german get() = load("values-de")

    @Test fun `every English string has a German translation and nothing is left over`() {
        assertEquals("missing in German", emptyList<String>(), english.keys.filter { it !in german })
        assertEquals("only in German", emptyList<String>(), german.keys.filter { it !in english })
    }

    @Test fun `translations are not empty and keep their placeholders`() {
        val placeholder = Regex("%(\\d+\\$)?[sd]")
        for ((key, en) in english) {
            val de = german.getValue(key)
            assertTrue("$key is empty", de.text.isNotBlank())
            if (key == "app_name") continue
            assertEquals("placeholders of $key", placeholder.findAll(en.text).map { it.value }.sorted().toList(), placeholder.findAll(de.text).map { it.value }.sorted().toList())
        }
    }

    @Test fun `the examples from the specification read naturally`() {
        val de = german
        assertEquals("+ Neue Notiz", de.getValue("new_note").text)
        assertEquals("+ Neue Checkliste", de.getValue("new_checklist").text)
        assertEquals("FAVORITEN", de.getValue("section_favorites").text)
        assertEquals("ZULETZT VERWENDET", de.getValue("section_recent").text)
        assertEquals("DATEIEN", de.getValue("section_files").text)
        assertEquals("PAPIERKORB", de.getValue("section_trash").text)
        assertEquals("Wiederherstellen", de.getValue("action_restore").text)
        assertEquals("Sitzung fortsetzen", de.getValue("startup_resume_session").text)
        assertEquals("Sitzung + leere Notiz", de.getValue("startup_resume_blank").text)
        assertEquals("Leere Notiz", de.getValue("startup_blank_note").text)
        assertTrue(de.getValue("action_smart_checklist_on").text.contains("Intelligente Checkliste"))
    }

    @Test fun `no user-visible text is written into the Compose code`() {
        val letters = Regex("""(Text\(\s*|text = |contentDescription = |label = |title = |placeholder = \{ Text\()"[^"$]*\p{L}{3,}""")
        val offenders = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) ->
                val code = line.substringBefore("//")
                !code.trimStart().startsWith("*") && !code.trimStart().startsWith("/*") && letters.containsMatchIn(code) && "testTag" !in code
            }.map { "${file.name}:${it.index + 1}: ${it.value.trim()}" }
        }.toList()
        assertEquals("hardcoded strings: $offenders", emptyList<String>(), offenders)
    }
}
