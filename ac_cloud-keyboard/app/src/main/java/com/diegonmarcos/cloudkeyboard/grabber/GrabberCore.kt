package com.diegonmarcos.cloudkeyboard.grabber

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/*
 * #1754 Grabber, the pure part: no Android class is touched here, so every rule below is a JVM unit test
 * (GrabberCoreTest). The classifier decides what the media box shows, the router decides what an action does,
 * and ProcessTextReply decides whether the result goes back into the field the text was selected in.
 */

/** What the selected text looks like. ADDRESS and PLACE are the two that get a map, everything else gets the first image. */
enum class SelectionKind(val label: String) {
    EMPTY("EMPTY"), WORD("WORD"), PHRASE("PHRASE"), ADDRESS("ADDRESS"), PLACE("PLACE");

    val showsMap: Boolean get() = this == ADDRESS || this == PLACE
}

object GrabberClassifier {
    private val STRONG_STREET = setOf(
        "street", "st", "avenue", "ave", "road", "rd", "boulevard", "blvd", "highway", "hwy", "lane", "ln",
        "calle", "carrer", "avenida", "avda", "paseo", "plaza", "ronda", "camino", "carretera",
        "viale", "corso", "piazza", "rue", "allee", "allée", "chemin", "impasse",
        "strasse", "straße", "str", "gasse", "weg", "platz", "ufer",
        "rua", "travessa", "largo", "praça", "straat", "laan", "ulica", "ul",
    )
    /** Ordinary words too: a house number or a comma and a postcode has to be there before they count. */
    private val WEAK_STREET = setOf("drive", "dr", "court", "ct", "place", "pl", "way", "square", "sq", "via", "terrace", "close", "row", "park")
    private val PLACE_WORDS = setOf(
        "tower", "museum", "airport", "station", "park", "square", "bridge", "cathedral", "hotel", "hospital",
        "university", "stadium", "castle", "palace", "beach", "mountain", "mount", "lake", "island", "church",
        "mosque", "temple", "market", "harbour", "harbor", "gardens", "zoo", "aeroport", "aeropuerto", "estación",
        "estacion", "museo", "catedral", "torre", "parque", "basilica", "monastery", "fort", "theatre", "theater",
        "opera", "gallery", "library", "pyramid", "falls", "canyon", "valley", "forest", "pier", "lighthouse",
    )
    private val CONNECTORS = setOf("of", "de", "del", "la", "le", "el", "the", "du", "des", "di", "da", "and", "y", "et", "am", "an", "der", "den", "von")

    private val HOUSE_NUMBER = Regex("""(?<![\w])\d{1,5}[a-zA-Z]?(?![\w])""")
    private val POSTAL = Regex("""(?<!\d)\d{5}(?!\d)|(?<!\d)\d{4,5}-\d{3,4}(?!\d)|\b[A-Z]{1,2}\d[A-Z\d]? ?\d[A-Z]{2}\b|(?<!\d)\d{4} ?[A-Z]{2}\b""")
    /** A postcode next to its city or state: "08001 Barcelona", "Springfield, IL 62704", "London SW1A 2AA". */
    private val POSTAL_CITY = Regex("""(?<!\d)\d{5}(?:-\d{4})? \p{Lu}\p{L}{2,}|\b[A-Z]{2} \d{5}(?:-\d{4})?\b|\p{Lu}\p{L}{2,},? [A-Z]{1,2}\d[A-Z\d]? ?\d[A-Z]{2}\b|(?<!\d)\d{4} ?[A-Z]{2} \p{Lu}\p{L}{2,}""")
    private val SENTENCE_END = Regex("""[.!?]\s+\p{L}""")
    private val TOKEN = Regex("""[\p{L}\p{N}'’.-]+""")

    fun normalise(raw: String): String = raw.trim().replace(Regex("""\s+"""), " ")

    fun classify(raw: String): SelectionKind {
        val t = normalise(raw)
        if (t.isEmpty()) return SelectionKind.EMPTY
        if (isAddress(t)) return SelectionKind.ADDRESS
        if (isPlace(t)) return SelectionKind.PLACE
        return if (tokens(t).size <= 1) SelectionKind.WORD else SelectionKind.PHRASE
    }

    private fun tokens(t: String): List<String> = t.split(' ').filter { it.isNotBlank() }

    private fun words(t: String): List<String> = TOKEN.findAll(t).map { it.value.trim('.', '-', '\'', '’').lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toList()

    private val COMPOUND_SUFFIX = listOf("strasse", "straße", "gasse", "platz", "allee", "straat", "laan")
    private val NUMBER_TOKEN = Regex("""\d{1,5}[a-zA-Z]?""")

    private fun isStrong(w: String): Boolean {
        val l = w.lowercase(Locale.ROOT)
        return l in STRONG_STREET || COMPOUND_SUFFIX.any { l.length > it.length && l.endsWith(it) }
    }

    /**
     * A street word counts when it is written as a name (capitalised: "Baker Street", "Calle Mayor") or sits right next to a house
     * number ("12 main st"): the lowercase "road" or "drive" of ordinary prose does not.
     */
    private fun streetNamed(toks: List<String>, words: Set<String>, strong: Boolean): Boolean = toks.indices.any { i ->
        val w = toks[i].trim('.', '-', '\'', '’')
        val hit = if (strong) isStrong(w) else w.lowercase(Locale.ROOT) in words
        // a strong street word may also sit right next to a house number in lowercase ("12 main st"); an ordinary word may not
        hit && (w.firstOrNull()?.isUpperCase() == true ||
            (strong && ((i > 0 && NUMBER_TOKEN.matches(toks[i - 1])) || (i < toks.size - 1 && NUMBER_TOKEN.matches(toks[i + 1])))))
    }

    fun isAddress(t: String): Boolean {
        if (t.length > 160) return false
        val ws = words(t)
        if (ws.size < 2 || ws.size > 16) return false
        if (SENTENCE_END.findAll(t).count() >= 2) return false // prose, not a postal line
        val toks = TOKEN.findAll(t).map { it.value }.toList()
        val commas = t.count { it == ',' }
        val number = HOUSE_NUMBER.containsMatchIn(t)
        val postal = POSTAL.containsMatchIn(t)
        val strong = streetNamed(toks, emptySet(), strong = true)
        val weak = streetNamed(toks, WEAK_STREET, strong = false)
        val cityWord = Regex("""\p{Lu}\p{L}{2,}""").containsMatchIn(t)
        return when {
            strong && number -> true
            strong && (commas >= 1 && (postal || cityWord)) -> true
            weak && number && (commas >= 1 || postal) -> true
            POSTAL_CITY.containsMatchIn(t) -> true
            else -> false
        }
    }

    /** "Barcelona, Spain": a city and its country, region or state, nothing else in the text. */
    private fun cityComma(t: String): Boolean {
        val parts = t.split(',').map { it.trim() }
        if (parts.size != 2 || t.length > 60) return false
        return parts.all { p ->
            val ws = p.split(' ').filter { it.isNotEmpty() }
            ws.size in 1..3 && ws.all { w -> w.all { c -> c.isLetter() || c == '\'' || c == '-' || c == '.' } && (w.first().isUpperCase() || w.lowercase(Locale.ROOT) in CONNECTORS) } && ws.first().first().isUpperCase()
        }
    }

    fun isPlace(t: String): Boolean {
        if (t.length > 80) return false
        val parts = tokens(t)
        if (cityComma(t)) return true
        if (parts.size !in 2..6) return false
        val ws = words(t)
        if (ws.none { it in PLACE_WORDS }) return false
        // every significant word starts with a capital: "Eiffel Tower", "Museo del Prado", not "the park was nice"
        return parts.all { p ->
            val w = p.trim(',', '.', '(', ')').lowercase(Locale.ROOT)
            w in CONNECTORS || p.first().isUpperCase() || p.first().isDigit()
        }
    }

    /** The one query line sent to the engines: one line, no more than [max] characters, cut at a word. */
    fun query(raw: String, max: Int = 180): String {
        val t = normalise(raw)
        if (t.length <= max) return t
        val cut = t.substring(0, max)
        val at = cut.lastIndexOf(' ')
        return (if (at > max / 2) cut.substring(0, at) else cut).trim()
    }
}

/** The six groups of the actions toolbar, in the order they are drawn. */
enum class GrabberGroup(val label: String) {
    CLIPBOARD("Clipboard"), TEXT("Text"), SEARCH("Search"), AI("AI"), DRIVE("Drive"), WRITER("Writer")
}

/**
 * Every action of the toolbar. [transforms] is true when the action turns the text into another text: in an editable field
 * that result replaces the selection (RESULT_OK + EXTRA_PROCESS_TEXT); a read-only selection only shows it.
 */
enum class GrabberAction(val id: String, val label: String, val group: GrabberGroup, val transforms: Boolean) {
    COPY("copy", "Copy", GrabberGroup.CLIPBOARD, false),
    CUT("cut", "Cut", GrabberGroup.CLIPBOARD, true),
    SELECT_ALL("select_all", "Select all", GrabberGroup.CLIPBOARD, false),

    BOLD("bold", "Bold", GrabberGroup.TEXT, true),
    ITALIC("italic", "Italic", GrabberGroup.TEXT, true),
    UNDERLINE("underline", "Underline", GrabberGroup.TEXT, true),
    STRIKE("strike", "Strike", GrabberGroup.TEXT, true),
    UPPER("upper", "UPPER", GrabberGroup.TEXT, true),
    LOWER("lower", "lower", GrabberGroup.TEXT, true),
    TITLE("title", "Title Case", GrabberGroup.TEXT, true),
    SENTENCE("sentence", "Sentence case", GrabberGroup.TEXT, true),

    DUCKDUCKGO("ddg", "DuckDuckGo", GrabberGroup.SEARCH, false),
    GOOGLE("google", "Google", GrabberGroup.SEARCH, false),
    QWANT("qwant", "Qwant", GrabberGroup.SEARCH, false),

    GEMINI("gemini", "Ask Gemini", GrabberGroup.AI, false),
    CLAUDE("claude", "Ask Claude", GrabberGroup.AI, false),
    CLOUD_SI("cloud_si", "Ask Cloud SI", GrabberGroup.AI, false),

    NOTE("note", "Add to note", GrabberGroup.DRIVE, false),
    TASK("task", "Add to task", GrabberGroup.DRIVE, false),
    CALENDAR("calendar", "Add to calendar", GrabberGroup.DRIVE, false),

    TRANSLATE("translate", "Translate", GrabberGroup.WRITER, true),
    GRAMMAR("grammar", "Grammar fix", GrabberGroup.WRITER, true),
    ENHANCE("enhance", "Enhance", GrabberGroup.WRITER, true);

    companion object {
        fun of(group: GrabberGroup): List<GrabberAction> = entries.filter { it.group == group }
    }
}

/** The three Writer engines (libs:writer-ui WriterToolRunner), named without touching that library from the pure part. */
enum class WriterEngine { TRANSLATE, GRAMMAR, ENHANCE }

/** One intent, described as data so the routing can be tested; GrabberActions turns it into a real Intent. */
data class IntentSpec(
    val action: String,
    val pkg: String? = null,
    val uri: String? = null,
    val mime: String? = null,
    val extras: List<Pair<String, String>> = emptyList(),
)

/** What to do when the primary intent has no taker (the app is not installed). */
sealed interface Fallback {
    data class View(val url: String) : Fallback
    /** The app takes no text intent: put the text on the clipboard and open the app. */
    data class CopyAndLaunch(val pkg: String, val app: String) : Fallback
    data class Missing(val app: String) : Fallback
}

data class OpenPlan(val label: String, val primary: IntentSpec, val fallback: Fallback)

sealed interface GrabberRoute {
    data class Clipboard(val text: String, val remove: Boolean) : GrabberRoute
    data object SelectAll : GrabberRoute
    data class Transform(val result: String) : GrabberRoute
    data class Open(val plan: OpenPlan) : GrabberRoute
    data class Engine(val engine: WriterEngine, val text: String) : GrabberRoute
}

object GrabberRouter {
    const val GEMINI_PKG = "com.google.android.apps.bard"
    const val CLAUDE_PKG = "com.anthropic.claude"
    const val CLOUD_SI_PKG = "com.diegonmarcos.cloudsuperintel"
    const val NOTES_PKG = "com.diegonmarcos.cloudnotes"
    const val TASKS_PKG = "com.diegonmarcos.cloudagenda"
    const val SEND = "android.intent.action.SEND"
    const val VIEW = "android.intent.action.VIEW"
    const val INSERT = "android.intent.action.INSERT"
    const val EXTRA_TEXT = "android.intent.extra.TEXT"
    const val CALENDAR_EVENTS = "content://com.android.calendar/events"

    /** Packages the app may resolve intents for (the manifest's <queries>), kept next to the routes that use them. */
    val PACKAGES = listOf(GEMINI_PKG, CLAUDE_PKG, CLOUD_SI_PKG, NOTES_PKG, TASKS_PKG)

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun searchUrl(a: GrabberAction, text: String): String {
        val q = enc(GrabberClassifier.query(text))
        return when (a) {
            GrabberAction.DUCKDUCKGO -> "https://duckduckgo.com/?q=$q"
            GrabberAction.GOOGLE -> "https://www.google.com/search?q=$q"
            GrabberAction.QWANT -> "https://www.qwant.com/?q=$q"
            else -> error("not a search action: $a")
        }
    }

    private fun send(label: String, pkg: String, text: String, fallback: Fallback) =
        GrabberRoute.Open(OpenPlan(label, IntentSpec(SEND, pkg = pkg, mime = "text/plain", extras = listOf(EXTRA_TEXT to text)), fallback))

    fun route(a: GrabberAction, text: String): GrabberRoute = when (a) {
        GrabberAction.COPY -> GrabberRoute.Clipboard(text, remove = false)
        GrabberAction.CUT -> GrabberRoute.Clipboard(text, remove = true)
        GrabberAction.SELECT_ALL -> GrabberRoute.SelectAll
        GrabberAction.BOLD -> GrabberRoute.Transform(GrabberText.bold(text))
        GrabberAction.ITALIC -> GrabberRoute.Transform(GrabberText.italic(text))
        GrabberAction.UNDERLINE -> GrabberRoute.Transform(GrabberText.underline(text))
        GrabberAction.STRIKE -> GrabberRoute.Transform(GrabberText.strike(text))
        GrabberAction.UPPER -> GrabberRoute.Transform(GrabberText.upper(text))
        GrabberAction.LOWER -> GrabberRoute.Transform(GrabberText.lower(text))
        GrabberAction.TITLE -> GrabberRoute.Transform(GrabberText.title(text))
        GrabberAction.SENTENCE -> GrabberRoute.Transform(GrabberText.sentence(text))
        GrabberAction.DUCKDUCKGO, GrabberAction.GOOGLE, GrabberAction.QWANT -> {
            val url = searchUrl(a, text)
            GrabberRoute.Open(OpenPlan(a.label, IntentSpec(VIEW, uri = url), Fallback.Missing("a browser")))
        }
        GrabberAction.GEMINI -> send(a.label, GEMINI_PKG, text, Fallback.View("https://gemini.google.com/app"))
        GrabberAction.CLAUDE -> send(a.label, CLAUDE_PKG, text, Fallback.View("https://claude.ai/new?q=" + enc(GrabberClassifier.query(text, 1500))))
        GrabberAction.CLOUD_SI -> send(a.label, CLOUD_SI_PKG, text, Fallback.Missing("Cloud SI"))
        GrabberAction.NOTE -> send(a.label, NOTES_PKG, text, Fallback.CopyAndLaunch(NOTES_PKG, "Cloud Notes"))
        GrabberAction.TASK -> send(a.label, TASKS_PKG, text, Fallback.CopyAndLaunch(TASKS_PKG, "Cloud Tasks"))
        GrabberAction.CALENDAR -> GrabberRoute.Open(OpenPlan(a.label,
            IntentSpec(INSERT, uri = CALENDAR_EVENTS, extras = listOf("title" to GrabberClassifier.query(text, 80), "description" to text)),
            Fallback.Missing("a calendar")))
        GrabberAction.TRANSLATE -> GrabberRoute.Engine(WriterEngine.TRANSLATE, text)
        GrabberAction.GRAMMAR -> GrabberRoute.Engine(WriterEngine.GRAMMAR, text)
        GrabberAction.ENHANCE -> GrabberRoute.Engine(WriterEngine.ENHANCE, text)
    }
}

/** The local text transforms (Text group). Pure, and every one keeps the length-in-characters meaning of the input. */
object GrabberText {
    private fun mapLetters(s: String, upper: Int, lower: Int, digit: Int?): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            i += Character.charCount(c)
            when {
                c in 'A'.code..'Z'.code -> sb.appendCodePoint(upper + (c - 'A'.code))
                c in 'a'.code..'z'.code -> sb.appendCodePoint(lower + (c - 'a'.code))
                digit != null && c in '0'.code..'9'.code -> sb.appendCodePoint(digit + (c - '0'.code))
                else -> sb.appendCodePoint(c)
            }
        }
        return sb.toString()
    }

    /** Mathematical sans-serif bold (U+1D5D4 / U+1D5EE / digits U+1D7EC). */
    fun bold(s: String): String = mapLetters(s, 0x1D5D4, 0x1D5EE, 0x1D7EC)

    /** Mathematical sans-serif italic (U+1D608 / U+1D622). */
    fun italic(s: String): String = mapLetters(s, 0x1D608, 0x1D622, null)

    private fun combine(s: String, mark: Char): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            i += Character.charCount(c)
            sb.appendCodePoint(c)
            if (!Character.isWhitespace(c)) sb.append(mark)
        }
        return sb.toString()
    }

    /** A combining low line (U+0332) after every visible character. */
    fun underline(s: String): String = combine(s, '\u0332')

    /** A combining long stroke overlay (U+0336) after every visible character. */
    fun strike(s: String): String = combine(s, '\u0336')

    fun upper(s: String): String = s.uppercase(Locale.ROOT)
    fun lower(s: String): String = s.lowercase(Locale.ROOT)

    fun title(s: String): String {
        val sb = StringBuilder()
        var start = true
        for (ch in s) {
            if (ch.isLetter()) { sb.append(if (start) ch.titlecaseChar() else ch.lowercaseChar()); start = false }
            else { sb.append(ch); start = ch.isWhitespace() || ch == '-' || ch == '/' || ch == '(' }
        }
        return sb.toString()
    }

    fun sentence(s: String): String {
        val sb = StringBuilder()
        var cap = true
        for (ch in s.lowercase(Locale.ROOT)) {
            if (cap && ch.isLetter()) { sb.append(ch.titlecaseChar()); cap = false }
            else { sb.append(ch); if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') cap = true }
        }
        return sb.toString()
    }
}

/** What the activity answers to the app the text was selected in. */
data class ProcessTextReply(val resultCode: Int, val text: String?) {
    val replacesSelection: Boolean get() = resultCode == RESULT_OK && text != null

    companion object {
        /** Activity.RESULT_OK / RESULT_CANCELED, restated so the pure part needs no Android class. */
        const val RESULT_OK = -1
        const val RESULT_CANCELED = 0
        /** Intent.EXTRA_PROCESS_TEXT and EXTRA_PROCESS_TEXT_READONLY. */
        const val EXTRA_PROCESS_TEXT = "android.intent.extra.PROCESS_TEXT"
        const val EXTRA_PROCESS_TEXT_READONLY = "android.intent.extra.PROCESS_TEXT_READONLY"

        /**
         * The rule of the feature: when the field is editable ([readOnly] false) an action that transforms the text returns its
         * [result] through RESULT_OK + EXTRA_PROCESS_TEXT, which replaces the selection; a read-only selection, or an action that
         * does not transform (search, ask, add to a note), returns nothing and the sheet stays.
         */
        fun decide(action: GrabberAction, readOnly: Boolean, result: String?): ProcessTextReply =
            if (!readOnly && action.transforms && result != null) ProcessTextReply(RESULT_OK, result)
            else ProcessTextReply(RESULT_CANCELED, null)

        /** Closing without applying anything. */
        fun cancelled(): ProcessTextReply = ProcessTextReply(RESULT_CANCELED, null)
    }
}

/** The recent grabs, newest first, one entry per text, capped. */
data class Recent(val text: String, val kind: String, val source: String, val at: Long)

object GrabberRecents {
    const val MAX = 30

    fun push(list: List<Recent>, r: Recent, max: Int = MAX): List<Recent> {
        if (r.text.isBlank()) return list
        return (listOf(r) + list.filterNot { it.text == r.text }).take(max)
    }

    fun remove(list: List<Recent>, text: String): List<Recent> = list.filterNot { it.text == text }
}

/** Slippy-map tile arithmetic (the standard Web Mercator scheme the fleet's map style also uses). */
object TileMath {
    const val TILE = 256

    class Tile(val x: Int, val y: Int, val z: Int, val dx: Double, val dy: Double)

    fun worldX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z) * TILE

    fun worldY(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * (1 shl z) * TILE
    }

    /** The tiles covering a [w] x [h] pixel window centred on [lat],[lon]; [Tile.dx]/[Tile.dy] place each one inside that window. */
    fun plan(lat: Double, lon: Double, z: Int, w: Int, h: Int): List<Tile> {
        val n = 1 shl z
        val left = worldX(lon, z) - w / 2.0
        val top = worldY(lat, z) - h / 2.0
        val x0 = Math.floor(left / TILE).toInt()
        val x1 = Math.floor((left + w - 1) / TILE).toInt()
        val y0 = Math.floor(top / TILE).toInt()
        val y1 = Math.floor((top + h - 1) / TILE).toInt()
        val out = ArrayList<Tile>()
        for (ty in y0..y1) for (tx in x0..x1) {
            if (ty < 0 || ty >= n) continue
            out += Tile(((tx % n) + n) % n, ty, z, tx * TILE - left, ty * TILE - top)
        }
        return out
    }

    fun url(t: Tile): String = "https://tile.openstreetmap.org/${t.z}/${t.x}/${t.y}.png"
}

/** One text-search hit and one image hit, as read from DuckDuckGo. */
data class SearchHit(val title: String, val snippet: String, val url: String, val source: String) {
    val host: String get() = runCatching { java.net.URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
}

data class ImageHit(val thumbUrl: String, val fullUrl: String, val title: String, val pageUrl: String)

data class GeoHit(val lat: Double, val lon: Double, val name: String)

/** The parsers of the three DuckDuckGo answers and of the OSM geocoder. Pure text in, hit out: tested against fixtures. */
object GrabberParsers {
    private fun entities(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#x27;", "'").replace("&#39;", "'").replace("&nbsp;", " ").replace("&hellip;", "…")
        .let { t -> Regex("""&#(\d+);""").replace(t) { m -> m.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: m.value } }
        .let { t -> Regex("""&#x([0-9a-fA-F]+);""").replace(t) { m -> m.groupValues[1].toIntOrNull(16)?.let { c -> String(Character.toChars(c)) } ?: m.value } }

    fun clean(html: String): String = entities(html.replace(Regex("""<[^>]+>"""), "")).replace(Regex("""\s+"""), " ").trim()

    /** DuckDuckGo wraps outbound links as //duckduckgo.com/l/?uddg=<encoded url>&rut=..., this returns the target. */
    fun target(href: String): String {
        val h = entities(href)
        val at = h.indexOf("uddg=")
        if (at >= 0) {
            val raw = h.substring(at + 5).substringBefore('&')
            return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        }
        return if (h.startsWith("//")) "https:$h" else h
    }

    /** The first organic result of html.duckduckgo.com/html; ads (result--ad) are skipped. */
    fun firstHtmlResult(html: String): SearchHit? {
        val title = Regex("""<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippet = Regex("""<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>|<div[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        for (m in title.findAll(html)) {
            val head = html.substring(0, m.range.first)
            val blockAt = head.lastIndexOf("class=\"result ")
            if (blockAt >= 0 && head.substring(blockAt, minOf(head.length, blockAt + 200)).contains("result--ad")) continue
            val url = target(m.groupValues[1])
            if (url.contains("duckduckgo.com/y.js") || url.isBlank()) continue
            val sn = snippet.find(html, m.range.last)?.let { s -> s.groupValues[1].ifEmpty { s.groupValues[2] } }.orEmpty()
            val t = clean(m.groupValues[2])
            if (t.isEmpty()) continue
            return SearchHit(t, clean(sn), url, "DuckDuckGo")
        }
        return null
    }

    /** api.duckduckgo.com instant answer: the abstract, else the first related topic. */
    fun instantAnswer(json: String): SearchHit? = runCatching {
        val o = org.json.JSONObject(json)
        val abs = o.optString("AbstractText")
        if (abs.isNotBlank() && o.optString("AbstractURL").isNotBlank())
            return@runCatching SearchHit(o.optString("Heading").ifBlank { abs.take(60) }, abs, o.optString("AbstractURL"), "DuckDuckGo instant answer")
        val topics = o.optJSONArray("RelatedTopics")
        if (topics != null) for (i in 0 until topics.length()) {
            val t = topics.optJSONObject(i) ?: continue
            val text = t.optString("Text"); val url = t.optString("FirstURL")
            if (text.isNotBlank() && url.isNotBlank()) return@runCatching SearchHit(text.substringBefore(" - ").take(80), text, url, "DuckDuckGo instant answer")
        }
        null
    }.getOrNull()

    /** The vqd token the image endpoint asks for, from the page that carries it. */
    fun vqd(html: String): String? = Regex("""vqd=["']?([\d]+-[\d-]+)""").find(html)?.groupValues?.get(1)

    /** The first image of duckduckgo.com/i.js. */
    fun firstImage(json: String): ImageHit? = runCatching {
        val arr = org.json.JSONObject(json).optJSONArray("results") ?: return@runCatching null
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val thumb = r.optString("thumbnail"); val image = r.optString("image")
            if (thumb.isNotBlank() || image.isNotBlank())
                return@runCatching ImageHit(thumb.ifBlank { image }, image.ifBlank { thumb }, clean(r.optString("title")), r.optString("url"))
        }
        null
    }.getOrNull()

    /** Nominatim search (jsonv2): the first place. */
    fun firstGeo(json: String): GeoHit? = runCatching {
        val a = org.json.JSONArray(json)
        val o = a.optJSONObject(0) ?: return@runCatching null
        GeoHit(o.getString("lat").toDouble(), o.getString("lon").toDouble(), o.optString("display_name"))
    }.getOrNull()
}
