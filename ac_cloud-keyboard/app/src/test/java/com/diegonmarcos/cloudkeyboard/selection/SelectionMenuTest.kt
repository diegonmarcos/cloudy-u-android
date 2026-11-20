package com.diegonmarcos.cloudkeyboard.selection

import com.diegonmarcos.superapp.translate.WriterMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1824 the selection menu: the manifest declares the four writer entries (+ Ask Cloud Grabber), each routes to its mode, replace-in-place only when editable. */
class SelectionMenuTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val strings = File("src/main/res/values/strings.xml").readText()
    private val stringsEs = File("src/main/res/values-es/strings.xml").readText()

    private fun activity(name: String): String {
        val a = manifest.indexOf("<activity android:name=\"$name\"")
        assertTrue("$name declared", a >= 0)
        return manifest.substring(a, manifest.indexOf("</activity>", a))
    }

    @Test fun manifestDeclaresTheFourWriterEntriesWithExactLabels() {
        // #1829 the owner's names: "C <name>"
        val labels = mapOf("translator" to "C Translator", "grammar" to "C Grammar", "enhancer" to "C Enhancer", "drafter" to "C Drafter")
        assertEquals(labels.values.toList(), SelectionMenu.ENTRIES.map { it.label })
        assertEquals(labels.keys.toList(), SelectionMenu.ENTRIES.map { it.key })
        for (e in SelectionMenu.ENTRIES) {
            val key = e.key
            val body = activity(".selection.${e.activity}")
            assertTrue(body.contains("android:exported=\"true\""))
            assertTrue(body.contains("android:label=\"@string/selection_${key}_label\""))
            assertTrue(body.contains("android:icon=\"@mipmap/ic_launcher\""))
            assertTrue(body.contains("android.intent.action.PROCESS_TEXT") && body.contains("android:mimeType=\"text/plain\""))
            assertTrue(strings.contains("<string name=\"selection_${key}_label\">${e.label}</string>"))
            assertTrue(stringsEs.contains("<string name=\"selection_${key}_label\">${e.label}</string>"))
        }
    }

    @Test fun askCloudGrabberLabelKeepsTheOldString() {
        val g = activity(".grabber.GrabberActivity")
        assertTrue(g.contains("@string/grabber_ask_label") && g.contains("android.intent.action.PROCESS_TEXT"))
        assertTrue(strings.contains("<string name=\"grabber_ask_label\">C Grabber</string>"))
        assertTrue(stringsEs.contains("<string name=\"grabber_ask_label\">C Grabber</string>"))
        assertTrue(strings.contains("<string name=\"grabber_menu_label\">Cloud Keyboard</string>"))
    }

    /** #1829 the menu is C Grabber, C Translator, C Grammar, C Enhancer, C Writer, C Drafter: ordered by intent-filter priority. */
    @Test fun theMenuEntriesAreNamedAndOrderedWithGrabberFirst() {
        val priorities = SelectionMenu.MENU_ORDER.map { (name, want) ->
            val body = activity(name)
            val p = Regex("""<intent-filter android:priority="(-?\d+)">\s*<action android:name="android\.intent\.action\.PROCESS_TEXT" />""").find(body)
            assertTrue("$name: its PROCESS_TEXT filter carries a priority", p != null)
            assertEquals(name, want, p!!.groupValues[1].toInt())
            want
        }
        assertEquals("strictly descending, Grabber on top", priorities.sortedDescending(), priorities)
        assertEquals("never above 0 (a non-privileged app is clamped there)", 0, priorities.max())
        assertEquals(".grabber.GrabberActivity", SelectionMenu.MENU_ORDER.first().first)
        assertTrue(strings.contains("<string name=\"writer_share_label\">C Writer</string>"))
        val labels = SelectionMenu.MENU_ORDER.map { (name, _) -> Regex("""android:label="@string/([a-z_]+)"""").find(activity(name))!!.groupValues[1] }
            .map { key -> Regex("<string name=\"$key\">([^<]*)</string>").find(strings)!!.groupValues[1] }
        assertEquals(listOf("C Grabber", "C Translator", "C Grammar", "C Enhancer", "C Writer", "C Drafter"), labels)
    }

    @Test fun sixProcessTextEntriesInTotalFromTheKeyboardAreFiveActivitiesPlusWriter() {
        // Ask Cloud SI lives in Cloud SI; here: Ask Cloud Grabber + four writer entries (+ the old Writer share entry, rule 16)
        val n = Regex("""<activity android:name="[^"]+"(?:(?!</activity>).)*?android\.intent\.action\.PROCESS_TEXT""", RegexOption.DOT_MATCHES_ALL).findAll(manifest).count()
        assertEquals(6, n)
    }

    @Test fun eachEntryRoutesToItsMode() {
        assertEquals(WriterMode.TRANSLATE, SelectionMenu.modeOf("com.diegonmarcos.cloudkeyboard.selection.SelectionTranslatorActivity"))
        assertEquals(WriterMode.GRAMMAR, SelectionMenu.modeOf("SelectionGrammarActivity"))
        assertEquals(WriterMode.ENHANCE, SelectionMenu.modeOf("x.SelectionEnhancerActivity"))
        assertEquals(WriterMode.DRAFT, SelectionMenu.modeOf("x.SelectionDrafterActivity"))
        assertNull(SelectionMenu.modeOf("x.OtherActivity")); assertNull(SelectionMenu.modeOf(null))
        val src = File("src/main/java/com/diegonmarcos/cloudkeyboard/selection/SelectionActivities.kt").readText()
        for (e in SelectionMenu.ENTRIES) assertTrue(src.contains("class ${e.activity} : SelectionPanelActivity(WriterMode.${e.mode.name})"))
        assertTrue(src.contains("EXTRA_PROCESS_TEXT") && src.contains("EXTRA_PROCESS_TEXT_READONLY"))
    }

    @Test fun replaceInPlaceReturnsTheResultOnlyWhenNotReadOnly() {
        val ok = SelectionMenu.reply(readOnly = false, result = "fixed")
        assertTrue(ok.replacesSelection); assertEquals(-1, ok.resultCode); assertEquals("fixed", ok.text)
        assertFalse(SelectionMenu.reply(readOnly = true, result = "fixed").replacesSelection)
        assertFalse(SelectionMenu.reply(readOnly = false, result = "").replacesSelection)
        assertFalse(SelectionMenu.reply(readOnly = false, result = null).replacesSelection)
    }

    /** Rule 16 parity: the shared panel's modes only grew (Grammar added at the end). */
    @Test fun writerModesOnlyGrew() {
        assertEquals(listOf("translate", "enhance", "draft", "grammar"), WriterMode.values().map { it.id })
    }
}
