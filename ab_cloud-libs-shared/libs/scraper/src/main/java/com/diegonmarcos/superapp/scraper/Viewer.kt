package com.diegonmarcos.superapp.scraper

/** How a file opens in-app: the browser is the viewer. */
enum class Viewer { CODE, IMAGE, AUDIO, VIDEO, WEB, NONE }

data class Route(val viewer: Viewer, val lang: String = "")

/** #1427 which in-app viewer a file opens in, from its name and (when known) its MIME type. */
object ViewerRoute {
    private val CODE = mapOf(
        "json" to "json", "js" to "js", "mjs" to "js", "cjs" to "js", "ts" to "js", "css" to "css",
        "txt" to "txt", "log" to "txt", "md" to "txt", "csv" to "txt",
        "xml" to "xml", "kt" to "code", "java" to "code", "py" to "code", "sh" to "code",
        "c" to "code", "cpp" to "code", "h" to "code", "rs" to "code", "go" to "code", "yaml" to "code", "yml" to "code", "toml" to "code",
    )
    private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "avif", "heic")
    private val AUDIO = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
    private val VIDEO = setOf("mp4", "webm", "mkv", "3gp", "mov", "m4v")
    private val WEB = setOf("html", "htm", "mhtml", "mht", "svg", "pdf")

    fun route(name: String, mime: String = ""): Route {
        val ext = name.substringAfterLast('.', "").lowercase()
        when {
            ext in IMAGE -> return Route(Viewer.IMAGE)
            ext in AUDIO -> return Route(Viewer.AUDIO)
            ext in VIDEO -> return Route(Viewer.VIDEO)
            ext in WEB -> return Route(Viewer.WEB)
            ext in CODE -> return Route(Viewer.CODE, CODE.getValue(ext))
        }
        val m = mime.lowercase().substringBefore(';').trim()
        return when {
            m.startsWith("image/svg") -> Route(Viewer.WEB)
            m.startsWith("image/") -> Route(Viewer.IMAGE)
            m.startsWith("audio/") -> Route(Viewer.AUDIO)
            m.startsWith("video/") -> Route(Viewer.VIDEO)
            m == "text/html" || m == "multipart/related" || m == "application/pdf" -> Route(Viewer.WEB)
            m == "application/json" || m.endsWith("+json") -> Route(Viewer.CODE, "json")
            m.contains("javascript") -> Route(Viewer.CODE, "js")
            m == "text/css" -> Route(Viewer.CODE, "css")
            m.endsWith("/xml") || m.endsWith("+xml") -> Route(Viewer.CODE, "xml")
            m.startsWith("text/") -> Route(Viewer.CODE, "txt")
            else -> Route(Viewer.NONE)
        }
    }
}

enum class Tok { COMMENT, STRING, NUMBER, KEYWORD, KEY, TAG }

data class Span(val start: Int, val end: Int, val kind: Tok)

/** #1427 a small syntax highlighter: token spans for js, json, css, xml and generic code; plain text has none. */
object Highlighter {
    const val MAX_CHARS = 200_000

    private val STR = """"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'"""
    private val NUM = """-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?"""
    private fun rules(lang: String): List<Pair<Tok, Regex>> = when (lang) {
        "json" -> listOf(
            Tok.KEY to Regex(""""(?:\\.|[^"\\\n])*"(?=\s*:)"""),
            Tok.STRING to Regex(""""(?:\\.|[^"\\\n])*""""),
            Tok.NUMBER to Regex(NUM),
            Tok.KEYWORD to Regex("""\b(?:true|false|null)\b"""),
        )
        "js" -> listOf(
            Tok.COMMENT to Regex("""//[^\n]*|/\*[\s\S]*?\*/"""),
            Tok.STRING to Regex("$STR|`(?:\\\\.|[^`\\\\])*`"),
            Tok.KEYWORD to Regex("""\b(?:const|let|var|function|return|if|else|for|while|do|switch|case|break|continue|class|extends|new|import|export|from|default|async|await|try|catch|finally|throw|true|false|null|undefined|this|typeof|instanceof|of|in)\b"""),
            Tok.NUMBER to Regex(NUM),
        )
        "css" -> listOf(
            Tok.COMMENT to Regex("""/\*[\s\S]*?\*/"""),
            Tok.STRING to Regex(STR),
            Tok.KEYWORD to Regex("""@[a-zA-Z-]+|!important"""),
            Tok.KEY to Regex("""[a-zA-Z-]+(?=\s*:[^{}]*[;}])"""),
            Tok.NUMBER to Regex("""-?\b\d+(?:\.\d+)?(?:px|em|rem|%|vh|vw|s|ms|deg)?|#[0-9a-fA-F]{3,8}\b"""),
        )
        "xml" -> listOf(
            Tok.COMMENT to Regex("""<!--[\s\S]*?-->"""),
            Tok.TAG to Regex("""</?[a-zA-Z][\w:.-]*|/?>"""),
            Tok.STRING to Regex(STR),
        )
        "code" -> listOf(
            Tok.COMMENT to Regex("""//[^\n]*|/\*[\s\S]*?\*/|#[^\n]*"""),
            Tok.STRING to Regex(STR),
            Tok.KEYWORD to Regex("""\b(?:fun|val|var|def|class|object|return|if|else|for|while|import|package|fn|let|func|true|false|null|None|True|False)\b"""),
            Tok.NUMBER to Regex(NUM),
        )
        else -> emptyList()
    }

    fun spans(text: String, lang: String): List<Span> {
        val rs = rules(lang)
        if (rs.isEmpty()) return emptyList()
        val t = if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
        val matchers = rs.map { (k, r) -> k to r.toPattern().matcher(t) }
        val out = ArrayList<Span>()
        var i = 0
        while (i < t.length) {
            var hit: Span? = null
            for ((k, m) in matchers) {
                m.region(i, t.length)
                if (m.lookingAt() && m.end() > i) { hit = Span(i, m.end(), k); break }
            }
            if (hit != null) { out += hit; i = hit.end }
            else {
                // skip a whole identifier so a keyword or number never starts mid-word
                val c = t[i]
                if (c.isLetterOrDigit() || c == '_' || c == '$') { while (i < t.length && (t[i].isLetterOrDigit() || t[i] == '_' || t[i] == '$')) i++ } else i++
            }
        }
        return out
    }
}
