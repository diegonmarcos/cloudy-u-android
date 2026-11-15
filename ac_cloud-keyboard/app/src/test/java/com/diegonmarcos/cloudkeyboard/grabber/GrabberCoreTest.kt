package com.diegonmarcos.cloudkeyboard.grabber

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1754 Grabber: the classifier (address or place versus text), the action routing, the text transforms, the PROCESS_TEXT result
 * for editable fields, the parsers of the three DuckDuckGo answers and of the geocoder, the tile arithmetic and the recents.
 * Pure JVM: GrabberCore.kt touches no Android class.
 */
class GrabberCoreTest {
    private fun kind(s: String) = GrabberClassifier.classify(s)

    // ── classifier ──────────────────────────────────────────────────────────

    @Test fun emptyAndBlankAreEmpty() {
        assertEquals(SelectionKind.EMPTY, kind(""))
        assertEquals(SelectionKind.EMPTY, kind("  \n\t "))
    }

    @Test fun oneWordIsAWord() {
        listOf("serendipity", "Paris", "don't", "café", "ünïcode", "42").forEach { assertEquals(it, SelectionKind.WORD, kind(it)) }
    }

    @Test fun ordinaryTextIsAPhrase() {
        listOf(
            "the quick brown fox",
            "Meeting at 5 pm tomorrow",
            "He walked down the street.",
            "Take the road to 5 miles, then turn",
            "I paid 12345 dollars to Alice, thanks",
            "Please drive 5 miles, then stop",
            "the park was nice",
            "It works. It really does. Trust me.",
        ).forEach { assertEquals(it, SelectionKind.PHRASE, kind(it)) }
    }

    @Test fun addressesAreAddresses() {
        listOf(
            "221B Baker Street, London",
            "10 Downing Street, London SW1A 2AA",
            "1600 Pennsylvania Avenue NW, Washington, DC 20500",
            "Carrer de Mallorca 401, 08013 Barcelona",
            "Calle Mayor 5",
            "Unter den Linden 77, 10117 Berlin",
            "Bahnhofstrasse 12, 8001 Zürich",
            "Rue de Rivoli, 75001 Paris",
            "Springfield, IL 62704",
            "London SW1A 2AA",
            "Via Roma 12, Milano",
            "Rua Augusta 1500, São Paulo",
            "12 Oak Drive, Boston",
            "  221B   Baker\nStreet,  London  ",
        ).forEach { assertEquals(it, SelectionKind.ADDRESS, kind(it)) }
    }

    @Test fun placesArePlaces() {
        listOf("Eiffel Tower", "Museo del Prado", "Heathrow Airport", "Central Park", "Barcelona, Spain", "Sagrada Familia Basilica")
            .forEach { assertEquals(it, SelectionKind.PLACE, kind(it)) }
    }

    @Test fun onlyAddressesAndPlacesGetAMap() {
        assertTrue(SelectionKind.ADDRESS.showsMap)
        assertTrue(SelectionKind.PLACE.showsMap)
        listOf(SelectionKind.EMPTY, SelectionKind.WORD, SelectionKind.PHRASE).forEach { assertFalse(it.name, it.showsMap) }
    }

    @Test fun aLongParagraphIsNeverAnAddress() {
        val prose = "On the road to Main Street we talked for 10 minutes, then Alice, who lives at 5 Elm Avenue, said goodbye. It was late. We left."
        assertEquals(SelectionKind.PHRASE, kind(prose))
    }

    @Test fun theQueryIsOneBoundedLine() {
        assertEquals("a b c", GrabberClassifier.query("  a\n b\t c "))
        val long = (1..200).joinToString(" ") { "word$it" }
        val q = GrabberClassifier.query(long, 60)
        assertTrue(q.length <= 60)
        assertFalse(q.endsWith(" "))
        assertTrue(long.startsWith(q))
    }

    // ── action routing ──────────────────────────────────────────────────────

    @Test fun toolbarHasEveryActionInTheOrderedGroups() {
        assertEquals(listOf("Clipboard", "Text", "Search", "AI", "Drive", "Writer"), GrabberGroup.entries.map { it.label })
        fun ids(g: GrabberGroup) = GrabberAction.of(g).map { it.id }
        assertEquals(listOf("copy", "cut", "select_all"), ids(GrabberGroup.CLIPBOARD))
        assertEquals(listOf("bold", "italic", "underline", "strike", "upper", "lower", "title", "sentence"), ids(GrabberGroup.TEXT))
        assertEquals(listOf("ddg", "google", "qwant"), ids(GrabberGroup.SEARCH))
        assertEquals(listOf("gemini", "claude", "cloud_si"), ids(GrabberGroup.AI))
        assertEquals(listOf("note", "task", "calendar"), ids(GrabberGroup.DRIVE))
        assertEquals(listOf("translate", "grammar", "enhance"), ids(GrabberGroup.WRITER))
        assertEquals(GrabberAction.entries.size, GrabberGroup.entries.sumOf { GrabberAction.of(it).size })
        assertEquals(GrabberAction.entries.size, GrabberAction.entries.map { it.id }.toSet().size)
        // the declaration order is the draw order: groups never interleave
        assertEquals(GrabberAction.entries.map { it.group }.distinct(), GrabberGroup.entries.toList())
    }

    @Test fun everyActionRoutesAndNothingThrows() {
        GrabberAction.entries.forEach { assertNotNull(it.id, GrabberRouter.route(it, "hello world")) }
    }

    @Test fun clipboardRoutes() {
        assertEquals(GrabberRoute.Clipboard("x y", false), GrabberRouter.route(GrabberAction.COPY, "x y"))
        assertEquals(GrabberRoute.Clipboard("x y", true), GrabberRouter.route(GrabberAction.CUT, "x y"))
        assertEquals(GrabberRoute.SelectAll, GrabberRouter.route(GrabberAction.SELECT_ALL, "x y"))
    }

    @Test fun textRoutesAreLocalTransforms() {
        assertEquals(GrabberRoute.Transform("HELLO"), GrabberRouter.route(GrabberAction.UPPER, "Hello"))
        assertEquals(GrabberRoute.Transform("hello"), GrabberRouter.route(GrabberAction.LOWER, "Hello"))
        assertEquals(GrabberRoute.Transform("Hello World"), GrabberRouter.route(GrabberAction.TITLE, "hELLO wORLD"))
        assertEquals(GrabberRoute.Transform("Hello. It works"), GrabberRouter.route(GrabberAction.SENTENCE, "HELLO. IT WORKS"))
        GrabberAction.of(GrabberGroup.TEXT).forEach { assertTrue(it.id, GrabberRouter.route(it, "abc") is GrabberRoute.Transform) }
    }

    @Test fun searchRoutesOpenTheEngineWithTheEncodedQuery() {
        fun url(a: GrabberAction) = ((GrabberRouter.route(a, "eiffel tower & paris") as GrabberRoute.Open).plan.primary.uri)
        assertEquals("https://duckduckgo.com/?q=eiffel+tower+%26+paris", url(GrabberAction.DUCKDUCKGO))
        assertEquals("https://www.google.com/search?q=eiffel+tower+%26+paris", url(GrabberAction.GOOGLE))
        assertEquals("https://www.qwant.com/?q=eiffel+tower+%26+paris", url(GrabberAction.QWANT))
        GrabberAction.of(GrabberGroup.SEARCH).forEach { assertEquals(GrabberRouter.VIEW, (GrabberRouter.route(it, "q") as GrabberRoute.Open).plan.primary.action) }
    }

    @Test fun askRoutesUseTheFleetIntentsAndCarryNoKey() {
        fun plan(a: GrabberAction) = (GrabberRouter.route(a, "why is the sky blue") as GrabberRoute.Open).plan
        assertEquals(GrabberRouter.GEMINI_PKG, plan(GrabberAction.GEMINI).primary.pkg)
        assertEquals(GrabberRouter.CLAUDE_PKG, plan(GrabberAction.CLAUDE).primary.pkg)
        assertEquals("com.diegonmarcos.cloudsuperintel", plan(GrabberAction.CLOUD_SI).primary.pkg)
        GrabberAction.of(GrabberGroup.AI).forEach {
            val p = plan(it)
            assertEquals(GrabberRouter.SEND, p.primary.action)
            assertEquals("text/plain", p.primary.mime)
            assertEquals(listOf(GrabberRouter.EXTRA_TEXT to "why is the sky blue"), p.primary.extras)
            // an intent and a web fallback only: nothing here can hold a credential
            assertFalse(p.primary.extras.any { (k, _) -> k.contains("key", ignoreCase = true) || k.contains("token", ignoreCase = true) })
        }
        assertEquals(Fallback.View("https://gemini.google.com/app"), plan(GrabberAction.GEMINI).fallback)
        assertEquals(Fallback.View("https://claude.ai/new?q=why+is+the+sky+blue"), plan(GrabberAction.CLAUDE).fallback)
        assertEquals(Fallback.Missing("Cloud SI"), plan(GrabberAction.CLOUD_SI).fallback)
    }

    @Test fun driveRoutesGoToNotesTasksAndTheCalendar() {
        fun plan(a: GrabberAction) = (GrabberRouter.route(a, "buy milk on friday") as GrabberRoute.Open).plan
        assertEquals(GrabberRouter.NOTES_PKG, plan(GrabberAction.NOTE).primary.pkg)
        assertEquals(Fallback.CopyAndLaunch(GrabberRouter.NOTES_PKG, "Cloud Notes"), plan(GrabberAction.NOTE).fallback)
        assertEquals(GrabberRouter.TASKS_PKG, plan(GrabberAction.TASK).primary.pkg)
        assertEquals(Fallback.CopyAndLaunch(GrabberRouter.TASKS_PKG, "Cloud Tasks"), plan(GrabberAction.TASK).fallback)
        val cal = plan(GrabberAction.CALENDAR).primary
        assertEquals(GrabberRouter.INSERT, cal.action)
        assertEquals(GrabberRouter.CALENDAR_EVENTS, cal.uri)
        assertEquals(listOf("title" to "buy milk on friday", "description" to "buy milk on friday"), cal.extras)
        assertTrue(GrabberRouter.PACKAGES.containsAll(listOf(GrabberRouter.NOTES_PKG, GrabberRouter.TASKS_PKG, GrabberRouter.GEMINI_PKG, GrabberRouter.CLAUDE_PKG, GrabberRouter.CLOUD_SI_PKG)))
    }

    @Test fun writerRoutesReuseTheThreeWriterEngines() {
        assertEquals(GrabberRoute.Engine(WriterEngine.TRANSLATE, "hola"), GrabberRouter.route(GrabberAction.TRANSLATE, "hola"))
        assertEquals(GrabberRoute.Engine(WriterEngine.GRAMMAR, "hola"), GrabberRouter.route(GrabberAction.GRAMMAR, "hola"))
        assertEquals(GrabberRoute.Engine(WriterEngine.ENHANCE, "hola"), GrabberRouter.route(GrabberAction.ENHANCE, "hola"))
    }

    // ── text transforms ─────────────────────────────────────────────────────

    @Test fun styleTransformsMapLettersAndKeepEverythingElse() {
        assertEquals("𝗔𝗮 1", GrabberText.bold("Aa 1").replace(GrabberText.bold("1"), "1"))
        assertEquals(GrabberText.bold("Hello, World 7").codePointCount(0, GrabberText.bold("Hello, World 7").length), "Hello, World 7".length)
        assertEquals("Hello", GrabberText.upper("hello").let { GrabberText.title(it) })
        assertEquals(0x1D5D4, GrabberText.bold("A").codePointAt(0))
        assertEquals(0x1D5EE, GrabberText.bold("a").codePointAt(0))
        assertEquals(0x1D7EC, GrabberText.bold("0").codePointAt(0))
        assertEquals(0x1D608, GrabberText.italic("A").codePointAt(0))
        assertEquals(0x1D622, GrabberText.italic("a").codePointAt(0))
        assertEquals("7", GrabberText.italic("7"))
        assertEquals("a̲b̲ c̲", GrabberText.underline("ab c"))
        assertEquals("a̶b̶ c̶", GrabberText.strike("ab c"))
        assertEquals("", GrabberText.bold(""))
    }

    @Test fun caseTransforms() {
        assertEquals("ÉCOLE", GrabberText.upper("école"))
        assertEquals("i like it", GrabberText.lower("I LIKE IT"))
        assertEquals("The Quick Brown-Fox", GrabberText.title("the quick brown-fox"))
        assertEquals("One. Two! Three? Four", GrabberText.sentence("ONE. TWO! THREE? FOUR"))
    }

    // ── the PROCESS_TEXT result ─────────────────────────────────────────────

    @Test fun theExtrasAreTheAndroidOnes() {
        assertEquals("android.intent.extra.PROCESS_TEXT", ProcessTextReply.EXTRA_PROCESS_TEXT)
        assertEquals("android.intent.extra.PROCESS_TEXT_READONLY", ProcessTextReply.EXTRA_PROCESS_TEXT_READONLY)
        assertEquals(-1, ProcessTextReply.RESULT_OK)
        assertEquals(0, ProcessTextReply.RESULT_CANCELED)
    }

    @Test fun anEditableFieldGetsEveryTransformedResultThroughResultOk() {
        GrabberAction.entries.filter { it.transforms }.forEach { a ->
            val r = ProcessTextReply.decide(a, readOnly = false, result = "NEW TEXT")
            assertEquals(a.id, ProcessTextReply.RESULT_OK, r.resultCode)
            assertEquals(a.id, "NEW TEXT", r.text)
            assertTrue(a.id, r.replacesSelection)
        }
    }

    @Test fun aReadOnlySelectionIsNeverReplaced() {
        GrabberAction.entries.forEach { a ->
            val r = ProcessTextReply.decide(a, readOnly = true, result = "NEW TEXT")
            assertFalse(a.id, r.replacesSelection)
            assertEquals(a.id, ProcessTextReply.RESULT_CANCELED, r.resultCode)
            assertNull(a.id, r.text)
        }
    }

    @Test fun actionsThatDoNotTransformLeaveTheFieldAloneEvenWhenEditable() {
        GrabberAction.entries.filterNot { it.transforms }.forEach { a ->
            assertFalse(a.id, ProcessTextReply.decide(a, readOnly = false, result = "x").replacesSelection)
        }
        // the transforming set is exactly: cut, the text group and the writer group
        assertEquals(
            setOf("cut", "bold", "italic", "underline", "strike", "upper", "lower", "title", "sentence", "translate", "grammar", "enhance"),
            GrabberAction.entries.filter { it.transforms }.map { it.id }.toSet(),
        )
    }

    @Test fun anEngineThatAnsweredNothingReturnsNothing() {
        assertFalse(ProcessTextReply.decide(GrabberAction.TRANSLATE, readOnly = false, result = null).replacesSelection)
        assertFalse(ProcessTextReply.cancelled().replacesSelection)
    }

    @Test fun cutReturnsAnEmptyStringThatDeletesTheSelection() {
        val r = ProcessTextReply.decide(GrabberAction.CUT, readOnly = false, result = "")
        assertTrue(r.replacesSelection)
        assertEquals("", r.text)
    }

    /** The activity and the model really use that rule: read the sources, the way this repo's other structural testers do. */
    @Test fun theActivityAnswersResultOkWithExtraProcessTextAndReadsTheReadOnlyExtra() {
        val dir = File("src/main/java/com/diegonmarcos/cloudkeyboard/grabber")
        val activity = File(dir, "GrabberActivities.kt").readText()
        val body = activity.substring(activity.indexOf("class GrabberActivity"), activity.indexOf("object GrabberShots"))
        assertTrue(body.contains("intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)"))
        assertTrue(body.contains("getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)"))
        assertTrue(body.contains("setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, reply.text))"))
        assertTrue("only a reply that replaces the selection may end the activity", body.contains("if (!reply.replacesSelection) return"))
        assertTrue(body.contains("model.readOnly = readOnly"))
        val model = File(dir, "GrabberModel.kt").readText()
        assertTrue("every transform goes through the one rule", model.contains("ProcessTextReply.decide(action, readOnly, result)"))
        assertTrue(model.contains("onReply(reply)"))
        val ui = File(dir, "GrabberUi.kt").readText()
        assertTrue("the sheet hands replies on", ui.contains("model.run(a, onReply)"))
    }

    @Test fun theManifestRegistersTheMenuEntryForPlainText() {
        val mf = File("src/main/AndroidManifest.xml").readText()
        val act = Regex("""<activity android:name="\.grabber\.GrabberActivity".*?</activity>""", RegexOption.DOT_MATCHES_ALL).find(mf)?.value.orEmpty()
        assertTrue(act.contains("android:exported=\"true\""))
        assertTrue(Regex("""android\.intent\.action\.PROCESS_TEXT".*?text/plain""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(act))
        assertTrue(act.contains("@string/grabber_menu_label"))
        assertTrue(File("src/main/res/values/strings.xml").readText().contains("<string name=\"grabber_menu_label\">Cloud Keyboard</string>"))
        // the Writer's own PROCESS_TEXT entry is still there (rule 16: nothing removed)
        val writer = Regex("""<activity android:name="\.WriterShareActivity".*?</activity>""", RegexOption.DOT_MATCHES_ALL).find(mf)?.value.orEmpty()
        assertTrue(writer.contains("android.intent.action.PROCESS_TEXT"))
    }

    @Test fun theCapturePathIsDeclaredPerThePermsGuard() {
        val mf = File("src/main/AndroidManifest.xml").readText()
        assertTrue(mf.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"))
        assertTrue(Regex("\\.grabber\\.GrabberCaptureService\"[^>]*foregroundServiceType=\"mediaProjection\"").containsMatchIn(mf))
        assertTrue(mf.contains("android.permission.BIND_QUICK_SETTINGS_TILE"))
        // the notice the service shows needs the notifications grant the keyboard already declares
        assertTrue(File("../build.json").readText().contains("\"notifications\""))
    }

    // ── parsers ─────────────────────────────────────────────────────────────

    private val html = """
        <div class="result results_links results_links_deep result--ad"><h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/y.js?ad_provider=bingv7aa&amp;u3=x">Ad title</a></h2><a class="result__snippet" href="x">Ad text</a></div>
        <div class="result results_links results_links_deep web-result "><h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FEiffel_Tower&amp;rut=abc">Eiffel Tower - <b>Wikipedia</b></a></h2><a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">The Eiffel Tower is a wrought-iron lattice tower &amp; landmark &#x27;in&#x27; Paris.</a></div>
        <div class="result results_links web-result "><h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2F">Second</a></h2></div>
    """.trimIndent()

    @Test fun firstHtmlResultSkipsAdsAndDecodesTheTarget() {
        val hit = GrabberParsers.firstHtmlResult(html)
        assertNotNull(hit)
        assertEquals("Eiffel Tower - Wikipedia", hit!!.title)
        assertEquals("https://en.wikipedia.org/wiki/Eiffel_Tower", hit.url)
        assertEquals("The Eiffel Tower is a wrought-iron lattice tower & landmark 'in' Paris.", hit.snippet)
        assertEquals("en.wikipedia.org", hit.host)
    }

    private val lite = """
        <table><tr><td valign="top">1.&nbsp;</td><td><a rel="nofollow" href="//duckduckgo.com/y.js?ad_provider=x" class='result-link'>Sponsored</a></td></tr>
        <tr><td valign="top">2.&nbsp;</td><td><a rel="nofollow" href="https://en.wikipedia.org/wiki/Rayleigh_scattering" class='result-link'>Rayleigh scattering - <b>Wikipedia</b></a></td></tr>
        <tr><td>&nbsp;</td><td class='result-snippet'>Rayleigh scattering is the scattering &amp; redirection of light.</td></tr></table>
    """.trimIndent()

    @Test fun firstLiteResultIsTheFallbackWhenTheHtmlPageIsWalled() {
        val hit = GrabberParsers.firstLiteResult(lite)
        assertNotNull(hit)
        assertEquals("Rayleigh scattering - Wikipedia", hit!!.title)
        assertEquals("https://en.wikipedia.org/wiki/Rayleigh_scattering", hit.url)
        assertEquals("Rayleigh scattering is the scattering & redirection of light.", hit.snippet)
        assertNull(GrabberParsers.firstLiteResult("<html>no results</html>"))
        // the network layer tries html, then lite, then the instant answer
        val net = File("src/main/java/com/diegonmarcos/cloudkeyboard/grabber/GrabberNet.kt").readText()
        assertTrue(net.indexOf("html.duckduckgo.com/html") < net.indexOf("lite.duckduckgo.com/lite"))
        assertTrue(net.indexOf("lite.duckduckgo.com/lite") < net.indexOf("api.duckduckgo.com"))
    }

    @Test fun anHtmlPageWithoutResultsIsNull() {
        assertNull(GrabberParsers.firstHtmlResult("<html><body>If this error persists, please let us know</body></html>"))
        assertNull(GrabberParsers.firstHtmlResult(""))
    }

    @Test fun instantAnswerReadsTheAbstractThenTheFirstTopic() {
        val a = GrabberParsers.instantAnswer("""{"Heading":"Eiffel Tower","AbstractText":"A tower in Paris.","AbstractURL":"https://en.wikipedia.org/wiki/Eiffel_Tower"}""")
        assertEquals("Eiffel Tower", a!!.title)
        assertEquals("https://en.wikipedia.org/wiki/Eiffel_Tower", a.url)
        val t = GrabberParsers.instantAnswer("""{"AbstractText":"","RelatedTopics":[{"Text":"Paris - capital of France","FirstURL":"https://duckduckgo.com/Paris"}]}""")
        assertEquals("Paris", t!!.title)
        assertNull(GrabberParsers.instantAnswer("""{"AbstractText":"","RelatedTopics":[]}"""))
        assertNull(GrabberParsers.instantAnswer("not json"))
    }

    @Test fun imageEndpointToken_andFirstImage() {
        assertEquals("4-123456789012345678901234567890123", GrabberParsers.vqd("""<script>vqd="4-123456789012345678901234567890123";</script>"""))
        assertEquals("4-99887766", GrabberParsers.vqd("""x?vqd=4-99887766&q=a"""))
        assertNull(GrabberParsers.vqd("<html></html>"))
        val img = GrabberParsers.firstImage("""{"results":[{"image":"https://x.example/a.jpg","thumbnail":"https://t.example/a.jpg","title":"A &amp; B","url":"https://x.example/page"}]}""")
        assertEquals("https://t.example/a.jpg", img!!.thumbUrl)
        assertEquals("https://x.example/a.jpg", img.fullUrl)
        assertEquals("A & B", img.title)
        assertNull(GrabberParsers.firstImage("""{"results":[]}"""))
    }

    @Test fun geocoderAnswerGivesThePoint() {
        val g = GrabberParsers.firstGeo("""[{"lat":"48.8582599","lon":"2.2945006","display_name":"Tour Eiffel, Paris"}]""")
        assertEquals(48.8582599, g!!.lat, 1e-9)
        assertEquals(2.2945006, g.lon, 1e-9)
        assertEquals("Tour Eiffel, Paris", g.name)
        assertNull(GrabberParsers.firstGeo("[]"))
    }

    // ── tiles and recents ───────────────────────────────────────────────────

    @Test fun tilesCoverTheWindowAroundThePoint() {
        val plan = TileMath.plan(0.0, 0.0, 1, 256, 256)
        assertEquals(setOf(0 to 0, 1 to 0, 0 to 1, 1 to 1), plan.map { it.x to it.y }.toSet())
        assertEquals(-128.0, plan.first { it.x == 0 && it.y == 0 }.dx, 1e-6)
        assertEquals(128.0, plan.first { it.x == 1 && it.y == 1 }.dy, 1e-6)
        val one = TileMath.plan(0.0, 0.0, 0, 256, 256)
        assertEquals(1, one.size)
        assertEquals("https://tile.openstreetmap.org/0/0/0.png", TileMath.url(one[0]))
        // a street-level window is at most 3 x 3 tiles
        val city = TileMath.plan(48.8582599, 2.2945006, 16, GrabberNet_MAP_W, GrabberNet_MAP_H)
        assertTrue(city.size in 4..9)
        // the point is the centre of the window
        val t = city.first()
        val cx = TileMath.worldX(2.2945006, 16) - (t.x * 256 - t.dx)
        assertEquals(GrabberNet_MAP_W / 2.0, cx, 1e-6)
    }

    @Test fun recentsAreNewestFirstDedupedAndCapped() {
        var l = emptyList<Recent>()
        l = GrabberRecents.push(l, Recent("a", "WORD", "page", 1))
        l = GrabberRecents.push(l, Recent("b", "WORD", "page", 2))
        l = GrabberRecents.push(l, Recent("a", "WORD", "menu", 3))
        assertEquals(listOf("a", "b"), l.map { it.text })
        assertEquals("menu", l.first().source)
        assertEquals(l, GrabberRecents.push(l, Recent("  ", "WORD", "page", 4)))
        (1..60).forEach { l = GrabberRecents.push(l, Recent("t$it", "WORD", "page", it.toLong())) }
        assertEquals(GrabberRecents.MAX, l.size)
        assertEquals("t60", l.first().text)
        assertEquals(l.size - 1, GrabberRecents.remove(l, "t60").size)
    }

    private companion object {
        // GrabberNet touches android classes on load; its two map constants are restated and pinned by the source test below
        const val GrabberNet_MAP_W = 480
        const val GrabberNet_MAP_H = 240
    }

    @Test fun theRestatedMapSizeIsTheNetworkLayersOne() {
        val net = File("src/main/java/com/diegonmarcos/cloudkeyboard/grabber/GrabberNet.kt").readText()
        assertTrue(net.contains("const val MAP_W = $GrabberNet_MAP_W"))
        assertTrue(net.contains("const val MAP_H = $GrabberNet_MAP_H"))
    }
}
