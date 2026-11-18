package com.diegonmarcos.cloudkeyboard.grabber

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1793 Grabber on selection: the trigger (which selection counts, the debounce, no re-open of the same selection), and the
 * wiring that keeps both ways opt-in (switches off by default, an accessibility service that reads selection events only).
 */
class GrabberAutoTest {
    private val own = "com.diegonmarcos.cloudkeyboard"
    private val app = "com.example.reader"

    @Test fun theSelectedPartInEitherDirection() {
        assertEquals("hello", SelectionTrigger.selected("hello world", 0, 5))
        assertEquals("hello", SelectionTrigger.selected("hello world", 5, 0))
        assertEquals("world", SelectionTrigger.selected("hello world", 6, 11))
        assertNull("collapsed", SelectionTrigger.selected("hello world", 3, 3))
        assertNull("out of range", SelectionTrigger.selected("hello", 2, 9))
        assertNull("no index", SelectionTrigger.selected("hello", -1, 3))
        assertNull(SelectionTrigger.selected(null, 0, 2))
    }

    @Test fun onlyARealSelectionOutsideThisAppAndOutsidePasswordsCounts() {
        val t = SelectionTrigger()
        assertEquals("Paris", t.accept("  Paris \n", app, own, false))
        assertNull("password field", t.accept("hunter22", app, own, true))
        assertNull("this app's own field", t.accept("Paris", own, own, false))
        assertNull("one character", t.accept("a", app, own, false))
        assertNull("punctuation only", t.accept(" ... -- ", app, own, false))
        assertNull("too long", t.accept("x".repeat(2001), app, own, false))
        assertEquals("ok", t.accept("ok", app, own, false))
        assertNull(t.accept(null, app, own, false))
    }

    @Test fun theSelectionMustBeSteadyForTheDebounce() {
        val t = SelectionTrigger(debounceMs = 900)
        assertEquals(900L, t.offer("Par", app, 0))
        assertNull("too early", t.due(500))
        assertEquals(1500L, t.offer("Paris", app, 600)) // the handle moved: the wait starts again
        assertNull("the first look is stale", t.due(1000))
        assertEquals("Paris", t.due(1500))
        assertNull("nothing pending any more", t.due(1600))
    }

    @Test fun aCollapsedSelectionCancelsWhatWasPending() {
        val t = SelectionTrigger(debounceMs = 900)
        t.offer("Paris", app, 0)
        assertNull(t.offer(null, app, 100))
        assertNull(t.due(2000))
        assertNull(t.pendingText())
    }

    @Test fun theSelectionThatJustOpenedDoesNotOpenAgain() {
        val t = SelectionTrigger(debounceMs = 900, repeatGapMs = 10_000)
        t.offer("Paris", app, 0); assertEquals("Paris", t.due(900))
        // back from the panel, the app reports the same selection again: no loop
        t.offer("Paris", app, 2000); assertNull(t.due(2900))
        // another app, the same text: a new grab
        t.offer("Paris", "com.example.other", 3000); assertEquals("Paris", t.due(3900))
        // the same selection much later: a new grab
        t.offer("Paris", "com.example.other", 30_000); assertEquals("Paris", t.due(30_900))
        // a new text right away: a new grab
        t.offer("London", "com.example.other", 31_000); assertEquals("London", t.due(31_900))
    }

    @Test fun passwordInputTypesAreRecognised() {
        assertTrue(SelectionTrigger.isPasswordInput(0x00000081)) // TEXT | PASSWORD
        assertTrue(SelectionTrigger.isPasswordInput(0x000000e1)) // TEXT | WEB_PASSWORD
        assertTrue(SelectionTrigger.isPasswordInput(0x00000091)) // TEXT | VISIBLE_PASSWORD
        assertTrue(SelectionTrigger.isPasswordInput(0x00000012)) // NUMBER | PASSWORD
        assertFalse(SelectionTrigger.isPasswordInput(0x00000001)) // plain text
        assertFalse(SelectionTrigger.isPasswordInput(0x00000021)) // email
        assertFalse(SelectionTrigger.isPasswordInput(0x00000002)) // number
    }

    @Test fun bothWaysAreOptInAndTheServiceReadsSelectionEventsOnly() {
        val dir = File("src/main/java/com/diegonmarcos/cloudkeyboard/grabber")
        val auto = File(dir, "GrabberAuto.kt").readText()
        assertTrue("accessibility switch off by default", auto.contains("getBoolean(KEY_A11Y, false)"))
        assertTrue("keyboard switch off by default", auto.contains("getBoolean(KEY_IME, false)"))
        assertTrue("the service does nothing while its switch is off", auto.contains("if (!GrabberAuto.a11yOn(this)) return"))
        assertTrue(auto.contains("e.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED"))
        assertTrue("an automatic open never writes into the field", auto.contains(".putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)"))
        val xml = File("src/main/res/xml/grabber_accessibility.xml").readText()
        assertTrue(xml.contains("android:accessibilityEventTypes=\"typeViewTextSelectionChanged\""))
        assertTrue(xml.contains("android:canRetrieveWindowContent=\"false\""))
        val mf = File("src/main/AndroidManifest.xml").readText()
        val svc = Regex("""<service android:name="\.grabber\.GrabberSelectionService".*?</service>""", RegexOption.DOT_MATCHES_ALL).find(mf)?.value.orEmpty()
        assertTrue(svc.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"))
        assertTrue(svc.contains("@xml/grabber_accessibility"))
        assertTrue("declared in perms.needs (perms-guard P1)", File("../build.json").readText().contains("\"accessibility\""))
        // the page: a plain disclosure of what it reads and the deep link to Android's Accessibility settings (through libs:perms)
        val page = File(dir, "GrabberScreens.kt").readText()
        assertTrue(page.contains("What it reads: Android's text-selection events and nothing else."))
        assertTrue(page.contains("openPermEntry(ctx, \"accessibility\")"))
        assertTrue(page.contains("GrabberAuto.serviceEnabled(ctx)"))
        // the keyboard path and the toolbar key are wired in the app
        val appKt = File("src/main/java/com/diegonmarcos/cloudkeyboard/App.kt").readText()
        assertTrue(appKt.contains("GrabberLink.opener ="))
        assertTrue(appKt.contains("GrabberLink.selectionHook ="))
    }
}
