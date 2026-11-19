package com.diegonmarcos.cloudkeyboard.grabber

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1825 accessibility-free grabbing: the assist structure flattened to text blocks (sample structures), the assistant-role row, the
 * PROCESS_TEXT selection-menu entry in the manifest, the voice-interaction wiring, and the English + Spanish strings.
 */
class GrabberAssistTest {
    private fun n(text: String?, l: Int, t: Int, w: Int, h: Int, kids: List<AssistNode> = emptyList(), scrollY: Int = 0, visible: Boolean = true, pw: Boolean = false, desc: String? = null) =
        AssistNode(text, desc, l, t, w, h, 0, scrollY, visible, pw, kids)

    // a browser article: a window at the top-left, a status bar, a scrolled WebView with a heading and two paragraphs
    private val article = listOf(
        n(null, 0, 0, 1080, 2400, listOf(
            n("12:03", 20, 0, 120, 80),
            n(null, 0, 150, 1080, 2000, listOf(
                n("Why the sky is blue", 40, 100, 1000, 90),
                n("Rayleigh scattering explains it.", 40, 300, 1000, 60),
                n("   ", 40, 400, 1000, 60),
                n("Third paragraph, scrolled", 40, 1500, 1000, 60),
            ), scrollY = 100),
        )),
    )

    @Test fun absoluteBoundsAddTheParentOffsetAndSubtractItsScroll() {
        val b = AssistFlatten.flatten(article)
        val h = b.first { it.text == "Why the sky is blue" }
        // WebView at top 150, scrolled by 100: 150 + 100 - 100 = 150
        assertEquals(TextBlock("Why the sky is blue", 40, 150, 1040, 240), h)
        val p = b.first { it.text.startsWith("Rayleigh") }
        assertEquals(350, p.top); assertEquals(410, p.bottom)
    }

    @Test fun readingOrderEmptyAndOffscreenAreHandled() {
        val b = AssistFlatten.flatten(article, 1080, 2400)
        assertEquals(listOf("12:03", "Why the sky is blue", "Rayleigh scattering explains it.", "Third paragraph, scrolled"), b.map { it.text })
        assertFalse("blank text is no block", b.any { it.text.isBlank() })
        val small = AssistFlatten.flatten(article, 1080, 1000)
        assertFalse("the block below the screen is dropped", small.any { it.text.startsWith("Third") })
    }

    @Test fun invisiblePasswordZeroSizeAndDuplicateNodesAreDropped() {
        val tree = listOf(n(null, 0, 0, 1000, 1000, listOf(
            n("hidden", 0, 0, 100, 40, visible = false),
            n("secret", 0, 50, 100, 40, pw = true),
            n("zero", 0, 100, 0, 0),
            n("twice", 0, 150, 100, 40),
            n("twice", 0, 150, 100, 40),
            n(null, 0, 200, 100, 40, desc = "Back"),
            n("container with a label", 0, 250, 100, 40, listOf(n("inner", 0, 0, 50, 20), n(null, 0, 20, 50, 20, desc = "ignored: has children?"))),
            n(null, 0, 300, 100, 40, listOf(n(null, 0, 0, 10, 10)), desc = "not a leaf"),
        )))
        val t = AssistFlatten.flatten(tree).map { it.text }
        assertEquals(listOf("twice", "Back", "container with a label", "inner", "ignored: has children?"), t)
    }

    @Test fun pickingByTapAndByDragAndJoiningInReadingOrder() {
        val b = AssistFlatten.flatten(article)
        assertEquals("Why the sky is blue", AssistFlatten.hit(b, 100, 200)?.text)
        assertEquals(null, AssistFlatten.hit(b, 5, 1200))
        val dragged = AssistFlatten.inRect(b, 50, 140, 60, 380)
        assertEquals(listOf("Why the sky is blue", "Rayleigh scattering explains it."), dragged.map { it.text })
        // the picked set in any order joins in reading order, one per line
        assertEquals("Why the sky is blue\nRayleigh scattering explains it.", AssistFlatten.join(listOf(dragged[1], dragged[0])))
    }

    @Test fun theBlockCountIsBounded() {
        val many = listOf(n(null, 0, 0, 1000, 100000, (0 until 600).map { n("row $it", 0, it * 10, 100, 9) }))
        assertEquals(AssistFlatten.MAX_BLOCKS, AssistFlatten.flatten(many).size)
    }

    @Test fun theRoleRowStates() {
        assertEquals(GrabberAssistRole.State.HELD, GrabberAssistRole.state(34, true, true))
        assertEquals(GrabberAssistRole.State.NOT_HELD, GrabberAssistRole.state(34, true, false))
        assertEquals(GrabberAssistRole.State.UNAVAILABLE, GrabberAssistRole.state(34, false, false))
        assertEquals("before Android 10 the Secure setting decides", GrabberAssistRole.State.HELD, GrabberAssistRole.state(28, true, true))
        assertEquals("android.settings.VOICE_INPUT_SETTINGS", GrabberAssistRole.SETTINGS_ACTIONS.first())
        assertTrue(GrabberAssistRole.SETTINGS_ACTIONS.contains("ROLE_REQUEST"))
        assertEquals("every page is still tried", 4, GrabberAssistRole.SETTINGS_ACTIONS.size)
    }

    private fun file(p: String) = File(p).readText()

    @Test fun theSelectionMenuEntryIsRegisteredForPlainText() {
        val mf = file("src/main/AndroidManifest.xml")
        val act = Regex("""<activity android:name="\.grabber\.GrabberActivity".*?</activity>""", RegexOption.DOT_MATCHES_ALL).find(mf)?.value.orEmpty()
        assertTrue("exported", act.contains("android:exported=\"true\""))
        assertTrue(act.contains("<action android:name=\"android.intent.action.PROCESS_TEXT\" />"))
        assertTrue("category default", act.contains("<category android:name=\"android.intent.category.DEFAULT\" />"))
        assertTrue("text/plain", act.contains("<data android:mimeType=\"text/plain\" />"))
        // the Writer entry stays
        assertTrue(Regex("""\.WriterShareActivity.*?PROCESS_TEXT""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(mf))
    }

    @Test fun theAssistantIsWiredLikeThePlatformWantsIt() {
        val mf = file("src/main/AndroidManifest.xml")
        val vis = Regex("""<service android:name="\.grabber\.GrabberVoiceService".*?</service>""", RegexOption.DOT_MATCHES_ALL).find(mf)?.value.orEmpty()
        assertTrue(vis.contains("android.permission.BIND_VOICE_INTERACTION"))
        assertTrue(vis.contains("android.service.voice.VoiceInteractionService"))
        assertTrue(vis.contains("android:name=\"android.voice_interaction\" android:resource=\"@xml/grabber_voice_interaction\""))
        assertTrue(Regex("""<service android:name="\.grabber\.GrabberVoiceSessionService"[^>]*BIND_VOICE_INTERACTION""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(mf))
        assertTrue(mf.contains("android:name=\".grabber.GrabberAssistActivity\""))
        val xml = file("src/main/res/xml/grabber_voice_interaction.xml")
        assertTrue(xml.contains("android:sessionService=\"com.diegonmarcos.cloudkeyboard.grabber.GrabberVoiceSessionService\""))
        assertTrue(xml.contains("android:recognitionService=\"com.diegonmarcos.cloudkeyboard.grabber.GrabberRecognitionService\""))
        assertTrue(xml.contains("android:supportsAssist=\"true\""))
        val kt = file("src/main/java/com/diegonmarcos/cloudkeyboard/grabber/GrabberAssist.kt")
        assertTrue(kt.contains("override fun onHandleAssist("))
        assertTrue(kt.contains("override fun onHandleScreenshot("))
        assertTrue(kt.contains("RoleManager.ROLE_ASSISTANT"))
        assertTrue(kt.contains("rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)"))
        // the OCR fallback and the capture fallback
        val act = file("src/main/java/com/diegonmarcos/cloudkeyboard/grabber/GrabberAssistActivity.kt")
        assertTrue(act.contains("MlKitOcr.read("))
        assertTrue(act.contains("GrabberScreenActivity::class.java"))
        // the page: role row + settings deep link + capture fallback
        val page = file("src/main/java/com/diegonmarcos/cloudkeyboard/grabber/GrabberScreens.kt")
        assertTrue(page.contains("GrabberAssistRole.current(ctx)"))
        assertTrue(page.contains("GrabberAssistRole.openSettings(ctx)"))
        assertTrue(page.contains("R.string.grabber_capture_fallback"))
    }

    @Test fun everyNewStringExistsInEnglishAndSpanish() {
        val en = file("src/main/res/values/strings.xml"); val es = file("src/main/res/values-es/strings.xml")
        val keys = Regex("""<string name="((?:grabber_diag|grabber_free|grabber_role|grabber_capture|grabber_assist)\w*)"""").findAll(en).map { it.groupValues[1] }.toSet()
        assertTrue(keys.size >= 30)
        keys.forEach { assertTrue("es is missing $it", es.contains("<string name=\"$it\"")) }
        assertTrue(es.contains("grabber_a11y_label") && es.contains("grabber_a11y_description"))
        assertTrue("the limit is stated", en.contains("never lets an app react by itself to a selection") && es.contains("nunca deja que una app reaccione sola"))
        // every string the code reads is declared
        val used = Regex("""R\.string\.((?:grabber_diag|grabber_free|grabber_role|grabber_capture|grabber_assist)\w*)""")
        File("src/main/java/com/diegonmarcos/cloudkeyboard/grabber").listFiles()!!.filter { it.name.endsWith(".kt") }
            .flatMap { f -> used.findAll(f.readText()).map { it.groupValues[1] }.toList() }
            .forEach { assertTrue("$it is not in strings.xml", keys.contains(it)) }
    }
}
