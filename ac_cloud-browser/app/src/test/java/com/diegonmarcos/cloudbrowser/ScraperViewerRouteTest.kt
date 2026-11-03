package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.scraper.Highlighter
import com.diegonmarcos.superapp.scraper.Route
import com.diegonmarcos.superapp.scraper.Tok
import com.diegonmarcos.superapp.scraper.Viewer
import com.diegonmarcos.superapp.scraper.ViewerRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1427 which viewer a tapped file opens in, and the highlighter's tokens. */
class ScraperViewerRouteTest {
    private fun r(name: String, mime: String = "") = ViewerRoute.route(name, mime)

    @Test fun `code, css, js, json and text get the highlighted code viewer`() {
        assertEquals(Route(Viewer.CODE, "json"), r("data.JSON"))
        assertEquals(Route(Viewer.CODE, "js"), r("app.mjs"))
        assertEquals(Route(Viewer.CODE, "css"), r("site.css"))
        assertEquals(Route(Viewer.CODE, "txt"), r("notes.txt"))
        assertEquals(Route(Viewer.CODE, "code"), r("main.kt"))
        assertEquals(Route(Viewer.CODE, "xml"), r("feed.xml"))
    }

    @Test fun `images, audio and video get the media viewers`() {
        assertEquals(Viewer.IMAGE, r("a.webp").viewer)
        assertEquals(Viewer.IMAGE, r("a.JPG").viewer)
        assertEquals(Viewer.AUDIO, r("song.m4a").viewer)
        assertEquals(Viewer.AUDIO, r("song.opus").viewer)
        assertEquals(Viewer.VIDEO, r("clip.webm").viewer)
        assertEquals(Viewer.VIDEO, r("clip.mp4").viewer)
    }

    @Test fun `pages and archives open in the web view`() {
        assertEquals(Viewer.WEB, r("index.html").viewer)
        assertEquals(Viewer.WEB, r("Page A.mhtml").viewer)
        assertEquals(Viewer.WEB, r("logo.svg").viewer)
    }

    @Test fun `a name without a known extension routes by MIME`() {
        assertEquals(Viewer.IMAGE, r("photo", "image/png").viewer)
        assertEquals(Viewer.WEB, r("icon", "image/svg+xml").viewer)
        assertEquals(Viewer.AUDIO, r("s", "audio/mpeg").viewer)
        assertEquals(Viewer.VIDEO, r("v", "video/mp4").viewer)
        assertEquals(Route(Viewer.CODE, "json"), r("api", "application/ld+json"))
        assertEquals(Route(Viewer.CODE, "js"), r("bundle", "text/javascript; charset=utf-8"))
        assertEquals(Route(Viewer.CODE, "css"), r("style", "text/css"))
        assertEquals(Route(Viewer.CODE, "txt"), r("plain", "text/plain"))
        assertEquals(Viewer.WEB, r("index", "text/html"))
        assertEquals(Viewer.NONE, r("blob", "application/octet-stream").viewer)
        assertEquals(Viewer.NONE, r("blob").viewer)
    }

    @Test fun `the extension wins over a generic MIME`() {
        assertEquals(Route(Viewer.CODE, "js"), r("x.js", "application/octet-stream"))
    }

    private fun toks(text: String, lang: String) = Highlighter.spans(text, lang).map { text.substring(it.start, it.end) to it.kind }

    @Test fun `js keywords, strings, numbers and comments`() {
        val t = toks("const x = \"hi\"; // note\nreturn 42", "js")
        assertEquals(listOf("const" to Tok.KEYWORD, "\"hi\"" to Tok.STRING, "// note" to Tok.COMMENT, "return" to Tok.KEYWORD, "42" to Tok.NUMBER), t)
    }

    @Test fun `a keyword inside an identifier is not a keyword`() {
        assertEquals(emptyList<Pair<String, Tok>>(), toks("constant iffy x1", "js"))
    }

    @Test fun `json keys differ from string values`() {
        val t = toks("{\"a\": \"b\", \"n\": -1.5, \"t\": true}", "json")
        assertEquals(listOf("\"a\"" to Tok.KEY, "\"b\"" to Tok.STRING, "\"n\"" to Tok.KEY, "-1.5" to Tok.NUMBER, "\"t\"" to Tok.KEY, "true" to Tok.KEYWORD), t)
    }

    @Test fun `css properties, units and at-rules, not selectors`() {
        val t = toks("@media x { a:hover { color: red; margin: 4px; } } /* c */", "css")
        assertTrue(t.contains("@media" to Tok.KEYWORD))
        assertTrue(t.contains("color" to Tok.KEY))
        assertTrue(t.contains("4px" to Tok.NUMBER))
        assertTrue(t.contains("/* c */" to Tok.COMMENT))
        assertTrue(t.none { it.first == "a" || it.first == "hover" })
    }

    @Test fun `plain text has no tokens and spans never overlap`() {
        assertEquals(emptyList<Pair<String, Tok>>(), toks("const 1 \"x\"", "txt"))
        val s = Highlighter.spans("var a = 'x' + 2 /* y */ + `z`", "js")
        assertTrue(s.zipWithNext().all { (a, b) -> a.end <= b.start })
    }
}
