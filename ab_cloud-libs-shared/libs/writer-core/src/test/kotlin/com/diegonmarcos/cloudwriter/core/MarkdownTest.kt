package com.diegonmarcos.cloudwriter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test fun `the outline lists headings with their line offsets and skips fenced code`() {
        val text = "# A\ntext\n## B ##\n```\n# not\n```\n### C"
        assertEquals(
            listOf(Heading(1, "A", 0), Heading(2, "B", 9), Heading(3, "C", 31)),
            Markdown.outline(text),
        )
    }

    @Test fun `what is not a heading`() {
        assertEquals(emptyList<Heading>(), Markdown.outline("#nospace\n####### seven\n  plain\n#"))
        assertEquals(listOf(Heading(1, "C#", 0)), Markdown.outline("# C#"))
        assertEquals(listOf(Heading(6, "six", 0)), Markdown.outline("###### six"))
        assertEquals(listOf(Heading(2, "after", 8)), Markdown.outline("```\n```\n## after"))
    }

    @Test fun `find returns every case-insensitive match, without overlap`() {
        assertEquals(listOf(0, 4, 8), Markdown.find("Abc abc ABC", "abc"))
        assertEquals(listOf(0), Markdown.find("aaa", "aa"))
        assertEquals(listOf(0, 2), Markdown.find("aaaa", "aa"))
        assertEquals(emptyList<Int>(), Markdown.find("x", ""))
        assertEquals(emptyList<Int>(), Markdown.find("x", "y"))
    }

    @Test fun `wrap and unwrap a selection`() {
        assertEquals(Edit("**hello** world", 2, 7), Markdown.wrap("hello world", 0, 5, "**"))
        assertEquals(Edit("hello world", 0, 5), Markdown.wrap("**hello** world", 2, 7, "**"))
        assertEquals(Edit("a**b", 2, 2), Markdown.wrap("ab", 1, 1, "*"))
        assertEquals(Edit("_hello_", 1, 6), Markdown.wrap("hello", 5, 0, "_"))
        assertEquals(Edit("*ab*", 1, 3), Markdown.wrap("ab", -3, 10, "*"))
        assertEquals(Edit("****hello** world", 4, 9), Markdown.wrap("**hello world", 2, 7, "**"))
        assertEquals(Edit("`a`", 1, 2), Markdown.wrap("a", 0, 1, "`"))
        assertEquals(Edit("a", 0, 1), Markdown.wrap("`a`", 1, 2, "`"))
    }

    @Test fun `unwrapping needs the marker on both sides`() {
        assertEquals(Edit("**ab***c*", 2, 4), Markdown.wrap("ab*c*", 0, 2, "**"))
        assertEquals(Edit("**x* y", 2, 3), Markdown.wrap("*x y", 1, 2, "*"))
        assertEquals(Edit("x****", 3, 3), Markdown.wrap("x", 4, 5, "**"))
    }

    @Test fun `prefix every touched line, or strip it when all carry it`() {
        assertEquals(Edit("- a\n- b\nc", 0, 7), Markdown.prefixLines("a\nb\nc", 0, 3, "- "))
        assertEquals(Edit("a\nb\nc", 0, 3), Markdown.prefixLines("- a\n- b\nc", 0, 7, "- "))
        assertEquals(Edit("- - a\n- b", 0, 9), Markdown.prefixLines("- a\nb", 0, 5, "- "))
        assertEquals(Edit("one\n> two", 4, 9), Markdown.prefixLines("one\ntwo", 5, 5, "> "))
        assertEquals(Edit("> one\ntwo", 0, 5), Markdown.prefixLines("one\ntwo", 3, 3, "> "))
        assertEquals(Edit("1. x", 0, 4), Markdown.prefixLines("x", 1, 0, "1. "))
        assertEquals(Edit("> ", 0, 2), Markdown.prefixLines("", 0, 0, "> "))
    }

    @Test fun `headings set, change and toggle off on the caret line`() {
        assertEquals(Edit("# title\nbody", 7, 7), Markdown.heading("title\nbody", 2, 2, 1))
        assertEquals(Edit("title\nbody", 5, 5), Markdown.heading("# title\nbody", 0, 0, 1))
        assertEquals(Edit("## title", 8, 8), Markdown.heading("# title", 3, 3, 2))
        assertEquals(Edit("a\n### b", 7, 7), Markdown.heading("a\nb", 2, 2, 3))
        assertEquals(Edit("a\n# b\nc", 5, 5), Markdown.heading("a\nb\nc", 3, 2, 1))
    }

    @Test fun `spans style headings, quotes, markers, bold, italic and code`() {
        val text = "# T\n**b** and *i* `c`\n> q\n- item"
        assertEquals(
            listOf(
                Span(0, 3, SpanKind.HEADING1),
                Span(4, 9, SpanKind.BOLD),
                Span(14, 17, SpanKind.ITALIC),
                Span(18, 21, SpanKind.CODE),
                Span(22, 25, SpanKind.QUOTE),
                Span(26, 27, SpanKind.MARKER),
            ),
            Markdown.spans(text),
        )
    }

    @Test fun `heading levels map to three styles and numbered lists are markers`() {
        assertEquals(listOf(Span(0, 4, SpanKind.HEADING2), Span(5, 11, SpanKind.HEADING3)), Markdown.spans("## a\n#### b"))
        assertEquals(listOf(Span(2, 4, SpanKind.MARKER)), Markdown.spans("  1. x"))
        assertEquals(listOf(Span(0, 3, SpanKind.ITALIC)), Markdown.spans("_a_"))
        assertEquals(emptyList<Span>(), Markdown.spans("snake_case_name and 2 * 3 * 4"))
        assertEquals(emptyList<Span>(), Markdown.spans(">no space"))
    }

    @Test fun `spans on one start order by end`() {
        val s = Markdown.spans("> **b**")
        assertEquals(listOf(Span(0, 7, SpanKind.QUOTE), Span(2, 7, SpanKind.BOLD)), s)
        val t = Markdown.spans("**a** x")
        assertEquals(listOf(Span(0, 5, SpanKind.BOLD)), t)
    }

    @Test fun `the title is the first non-blank line, bare, at most 80 characters`() {
        assertEquals("My Doc", Markdown.title("\n\n## My *Doc*\nbody"))
        assertEquals("", Markdown.title(""))
        assertEquals("", Markdown.title(" \n  \n"))
        assertEquals(80, Markdown.title("a".repeat(100)).length)
        assertEquals("plain `code`".replace("`", ""), Markdown.title("  plain `code`  "))
    }

    @Test fun `the snippet is the rest, flattened and capped`() {
        assertEquals("Sub x y", Markdown.snippet("Title\n# Sub  **x**\n\n> y"))
        assertEquals("", Markdown.snippet("only one line"))
        assertEquals("", Markdown.snippet("\n\nTitle\n"))
        assertEquals(10, Markdown.snippet("t\n" + "a ".repeat(200), 10).length)
        assertTrue(Markdown.snippet("t\n" + "a ".repeat(200)).length == 140)
    }

    @Test fun `words count whitespace-separated runs`() {
        assertEquals(3, Markdown.words("  a b\n c "))
        assertEquals(0, Markdown.words(""))
        assertEquals(1, Markdown.words("one"))
    }
}
