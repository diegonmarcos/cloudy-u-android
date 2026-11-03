package com.diegonmarcos.cloudwriter.core

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * #1418 the five bottom-nav tabs, IN THE ORDER THE OWNER SET. build.json::ui.bottom_nav is the
 * declaration the app draws; [check] is what holds that declaration to this order, so a reordered
 * or shortened bar fails a test instead of shipping.
 */
object WriterTabs {
    const val ENHANCER = "enhancer"
    const val DRAFTER = "drafter"
    const val HOME = "home"
    const val TRANSLATOR = "translator"
    const val GRAMMAR = "grammar"

    val ORDER = listOf(ENHANCER, DRAFTER, HOME, TRANSLATOR, GRAMMAR)

    /** Null when [declared] is exactly [ORDER]; otherwise the first difference, in words. */
    fun check(declared: List<String>): String? {
        if (declared.size != ORDER.size) return "expected ${ORDER.size} tabs, found ${declared.size}"
        val i = ORDER.indices.firstOrNull { declared[it] != ORDER[it] } ?: return null
        return "tab ${i + 1} is '${declared[i]}', expected '${ORDER[i]}'"
    }
}

/** #1418 what Load file accepts. */
enum class ImportKind { TXT, MD, HTML, DOCX, UNSUPPORTED }

object Import {
    /** By extension first (SAF names are reliable), then MIME. */
    fun kind(name: String?, mime: String?): ImportKind {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when {
            ext == "txt" || ext == "text" -> ImportKind.TXT
            ext == "md" || ext == "markdown" -> ImportKind.MD
            ext == "html" || ext == "htm" -> ImportKind.HTML
            ext == "docx" -> ImportKind.DOCX
            mime == "text/markdown" -> ImportKind.MD
            mime == "text/html" -> ImportKind.HTML
            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ImportKind.DOCX
            mime?.startsWith("text/") == true -> ImportKind.TXT
            else -> ImportKind.UNSUPPORTED
        }
    }

    /** A loaded file as editor HTML, or null when the kind cannot be read. */
    fun toHtml(kind: ImportKind, bytes: ByteArray): String? = when (kind) {
        ImportKind.TXT -> HtmlText.textToHtml(bytes.toString(Charsets.UTF_8))
        ImportKind.MD -> HtmlText.markdownToHtml(bytes.toString(Charsets.UTF_8))
        ImportKind.HTML -> bodyOf(bytes.toString(Charsets.UTF_8))
        ImportKind.DOCX -> Docx.text(bytes)?.let { HtmlText.textToHtml(it) }
        ImportKind.UNSUPPORTED -> null
    }

    /** The inside of <body> when there is one, without scripts or styles. */
    fun bodyOf(html: String): String {
        val body = Regex("(?is)<body[^>]*>(.*)</body>").find(html)?.groupValues?.get(1) ?: html
        return Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>").replace(body, "").trim()
    }
}

/**
 * #1418 "docx if simple": the words and paragraphs of word/document.xml (w:p, w:t, w:tab, w:br).
 * Tables read as their cells' paragraphs; images, comments and tracked changes are not read.
 */
object Docx {
    fun text(bytes: ByteArray): String? {
        val xml = runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { it.name == "word/document.xml" } ?: return null
                zip.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull() ?: return null
        return documentText(xml)
    }

    fun documentText(xml: String): String {
        val paragraphs = Regex("(?s)<w:p[ >].*?</w:p>|<w:p/>").findAll(xml).map { p ->
            Regex("(?s)<w:t(?:\\s[^>]*)?>(.*?)</w:t>|<w:tab/>|<w:br/>").findAll(p.value).joinToString("") { m ->
                when {
                    m.value == "<w:tab/>" -> "\t"
                    m.value == "<w:br/>" -> "\n"
                    else -> HtmlText.unescape(m.groupValues[1])
                }
            }
        }
        return paragraphs.joinToString("\n\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }
}

/** #1418 the OCR engine chosen in Writer > Configs. ML (on-device) is the default. */
enum class OcrEngine(val id: String) {
    ML("ml"),
    OPENROUTER("openrouter");

    companion object {
        val DEFAULT = ML
        fun of(id: String?): OcrEngine = values().firstOrNull { it.id == id } ?: DEFAULT
    }
}

object Ocr {
    const val PROMPT = "Transcribe all text in this image exactly as written, keeping line breaks. Reply with the text only, no commentary."
    const val NOTHING_READ = "no text found in the image"

    /** The engine as a [Route]: OpenRouter is the model route, ML the on-device one. */
    fun route(engine: OcrEngine): Route = if (engine == OcrEngine.OPENROUTER) Route.MODEL else Route.ML

    /**
     * Runs the chosen engine through the SAME routing every Writer tool uses ([Routing.run]):
     * ML runs on-device only; OpenRouter falls back to ML when it is blocked or fails, and says why.
     * A blank recognition is a failure ([NOTHING_READ]), so a fallback can still rescue it.
     */
    fun run(engine: OcrEngine, blocker: String?, openRouter: () -> Outcome, ml: () -> Outcome): Answer =
        Routing.run(route(engine), blocker, { nonBlank(openRouter()) }, { nonBlank(ml()) })

    private fun nonBlank(o: Outcome): Outcome = if (o.ok && o.text!!.isBlank()) Outcome.failed(NOTHING_READ) else o

    /** Where recognised text lands: appended to the box's HTML as its own paragraphs; a blank box takes it whole. */
    fun intoBox(boxHtml: String, recognised: String): String {
        val add = HtmlText.textToHtml(recognised.trim())
        if (add.isEmpty()) return boxHtml
        return if (HtmlText.htmlToText(boxHtml).isBlank()) add else boxHtml + add
    }
}
