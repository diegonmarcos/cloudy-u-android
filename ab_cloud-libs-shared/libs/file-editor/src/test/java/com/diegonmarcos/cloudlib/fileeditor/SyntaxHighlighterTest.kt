package com.diegonmarcos.cloudlib.fileeditor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the REAL asset (src/main/assets/file-editor-languages.json — Gradle
 * runs unit tests with the module directory as the working directory), so a
 * language entry that stops parsing, or an extension that stops resolving,
 * fails here and not on the first file the owner opens.
 */
class SyntaxHighlighterTest {

    private val highlighter = SyntaxHighlighter.fromJson(File("../../../data/fleet/" + SyntaxHighlighter.DATASET + ".json").readText())

    private fun kinds(text: String, name: String): List<Pair<String, TokenKind>> {
        val lang = highlighter.languageFor(name)
        return highlighter.tokenize(text, lang).map { text.substring(it.start, it.end) to it.kind }
    }

    @Test fun everyDeclaredExtensionResolvesToItsLanguage() {
        val catalog = kotlinx.serialization.json.Json.parseToJsonElement(File("../../../data/fleet/" + SyntaxHighlighter.DATASET + ".json").readText())
        val langs = catalog.jsonObject["languages"]!!.jsonArray
        assertTrue(langs.size >= 10)
        for (l in langs) {
            val id = l.jsonObject["id"]!!.jsonPrimitive.content
            for (ext in l.jsonObject["extensions"]!!.jsonArray) {
                assertEquals(id, highlighter.languageFor("file." + ext.jsonPrimitive.content)?.id)
            }
        }
        assertNull(highlighter.languageFor("README"))
        assertNull(highlighter.languageFor("archive.unknownext"))
        assertEquals("kotlin", highlighter.languageFor("Weird.KT")?.id)
    }

    @Test fun kotlinCommentsStringsNumbersKeywords() {
        val src = "val x = 42 // answer\n/* block\n */ fun f() = \"s\\\"q\" + 'c' + value"
        val got = kinds(src, "a.kt")
        assertEquals(
            listOf("val" to TokenKind.KEYWORD, "42" to TokenKind.NUMBER, "// answer" to TokenKind.COMMENT,
                "/* block\n */" to TokenKind.COMMENT, "fun" to TokenKind.KEYWORD, "\"s\\\"q\"" to TokenKind.STRING, "'c'" to TokenKind.STRING),
            got,
        )
    }

    @Test fun keywordsNeedWordBoundariesAndNumbersDoNotStartInsideWords() {
        val got = kinds("value1 val val2 x42 4.5", "a.kt")
        assertEquals(listOf("val" to TokenKind.KEYWORD, "4.5" to TokenKind.NUMBER), got)
    }

    @Test fun unterminatedStringStopsAtLineEndAndUnterminatedBlockRunsToEnd() {
        assertEquals(listOf("\"open" to TokenKind.STRING, "val" to TokenKind.KEYWORD), kinds("\"open\nval", "a.kt"))
        assertEquals(listOf("/* never closed\nval" to TokenKind.COMMENT), kinds("/* never closed\nval", "a.kt"))
    }

    @Test fun markdownHeadingsAndFences() {
        val got = kinds("# Title\ntext `code` here\n```\nfenced\n```\n## Sub", "notes.md")
        assertEquals(listOf("# Title" to TokenKind.HEADING, "`code`" to TokenKind.STRING, "```\nfenced\n```" to TokenKind.COMMENT, "## Sub" to TokenKind.HEADING), got)
    }

    @Test fun shellHashCommentsAndJsonLiterals() {
        assertEquals(listOf("if" to TokenKind.KEYWORD, "# c" to TokenKind.COMMENT), kinds("if x # c", "run.sh"))
        assertEquals(listOf("\"a\"" to TokenKind.STRING, "1" to TokenKind.NUMBER, "true" to TokenKind.KEYWORD), kinds("{\"a\": [1, true]}", "x.json"))
    }

    @Test fun noLanguageMeansNoTokens() {
        assertTrue(highlighter.tokenize("val x", null).isEmpty())
        assertTrue(highlighter.tokenize("", highlighter.languageFor("a.kt")).isEmpty())
    }
}

private val kotlinx.serialization.json.JsonElement.jsonObject get() = this as kotlinx.serialization.json.JsonObject
private val kotlinx.serialization.json.JsonElement.jsonArray get() = this as kotlinx.serialization.json.JsonArray
private val kotlinx.serialization.json.JsonElement.jsonPrimitive get() = this as kotlinx.serialization.json.JsonPrimitive
