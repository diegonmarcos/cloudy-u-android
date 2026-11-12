package helium314.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1698 ac_cloud-keyboard build.json::ui is THE nav declaration (drawn by KeyboardChrome through libs:bottomnav NavDecl, like
 * every fleet app). KbTab only maps its ids to settings routes, so it must hold exactly the declared tree: the same bottom tabs
 * in order, the same pages at every depth with the same ids and labels, and every declared page has a real route.
 */
class KbNavDeclTest {
    private fun buildJson(): String = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "ac_cloud-keyboard/build.json") }.first { it.isFile }.readText()

    /** A minimal JSON reader (objects, arrays, strings, numbers, literals): unit tests have no org.json. */
    private class J(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun v(): Any? {
            ws()
            val c = s[i]
            if (c == '{') {
                i++; val m = LinkedHashMap<String, Any?>(); ws()
                if (s[i] == '}') { i++; return m }
                var more = true
                while (more) { ws(); val k = str(); ws(); i++; m[k] = v(); ws(); more = s[i] == ','; i++ }
                return m
            }
            if (c == '[') {
                i++; val l = ArrayList<Any?>(); ws()
                if (s[i] == ']') { i++; return l }
                var more = true
                while (more) { l.add(v()); ws(); more = s[i] == ','; i++ }
                return l
            }
            if (c == '"') return str()
            val a = i
            while (i < s.length && s[i] !in ",}] \n\r\t") i++
            return s.substring(a, i)
        }
        fun str(): String {
            i++; val b = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (s[i]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t')
                        'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> b.append(s[i])
                    }
                } else b.append(s[i])
                i++
            }
            i++; return b.toString()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tree(pages: List<Any?>?): List<String> = pages.orEmpty().flatMap { p ->
        val m = p as Map<String, Any?>
        listOf("${m["id"]}=${m["label"]}") + tree(m["pages"] as List<Any?>?).map { "  $it" }
    }
    private fun kbTree(pages: List<NavPageDecl>): List<String> = pages.flatMap { p -> listOf("${p.id}=${p.label}") + kbTree(p.children).map { "  $it" } }

    @Suppress("UNCHECKED_CAST")
    @Test fun kbTabIsExactlyTheBuildJsonUiDeclaration() {
        val ui = (J(buildJson()).v() as Map<String, Any?>)["ui"] as Map<String, Any?>
        assertEquals(KbTab.entries.map { it.id }, ui["bottom_nav"])
        val sections = (ui["sections"] as List<Any?>).map { it as Map<String, Any?> }
        for (t in KbTab.entries) {
            val sec = sections.first { it["id"] == t.id }
            assertEquals(t.label, sec["label"])
            assertEquals("tab ${t.id}", tree(sec["pages"] as List<Any?>), kbTree(t.pages))
        }
        assertTrue(KbTab.entries.all { t -> t.allPages.all { it.route.isNotBlank() } })
    }
}
