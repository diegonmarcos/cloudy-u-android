package com.diegonmarcos.cloudsuperintel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Ask AI · Cloud SI: intent parsing, mime routing, the size cap, and the manifest guard for the filters. */
class AskAiTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test fun viewTakesTheDataUriOfContentAndFileOnly() {
        val r = AskAi.parse(AskAi.VIEW, "application/pdf", "content://x/1", emptyList(), null, null)!!
        assertEquals(listOf("content://x/1"), r.uris)
        assertEquals(listOf("file:///sdcard/a.png"), AskAi.parse(AskAi.VIEW, "image/png", "file:///sdcard/a.png", emptyList(), null, null)!!.uris)
        assertNull(AskAi.parse(AskAi.VIEW, null, "https://example.org/a", emptyList(), null, null))
    }

    @Test fun sendTakesOneStreamAndItsText() {
        val r = AskAi.parse(AskAi.SEND, "image/jpeg", null, listOf("content://m/9"), "caption", null)!!
        assertEquals(listOf("content://m/9"), r.uris)
        assertEquals("caption", r.text)
        assertEquals("only text", AskAi.parse(AskAi.SEND, "text/plain", null, emptyList(), "only text", null)!!.text)
    }

    @Test fun sendMultipleKeepsEachDistinctStreamUpToTheLimit() {
        val many = (1..15).map { "content://m/$it" } + "content://m/1"
        val r = AskAi.parse(AskAi.SEND_MULTIPLE, "*/*", null, many, null, null)!!
        assertEquals(AskAi.MAX_FILES, r.uris.size)
        assertEquals(r.uris.distinct(), r.uris)
    }

    @Test fun processTextIsTheSelectedText() {
        val r = AskAi.parse(AskAi.PROCESS_TEXT, null, null, emptyList(), null, "selected words")!!
        assertEquals("selected words", r.text)
        assertTrue(r.uris.isEmpty())
        assertEquals("text/plain", r.mime)
    }

    @Test fun nothingToAskAboutOrAnotherActionIsNull() {
        assertNull(AskAi.parse(AskAi.SEND, "text/plain", null, emptyList(), "  ", null))
        assertNull(AskAi.parse("android.intent.action.MAIN", null, "content://x/1", emptyList(), "t", "t"))
        assertNull(AskAi.parse(null, null, null, emptyList(), null, null))
    }

    @Test fun aLongSelectionIsCut() {
        assertEquals(AskAi.MAX_TEXT, AskAi.parse(AskAi.PROCESS_TEXT, null, null, emptyList(), null, "a".repeat(AskAi.MAX_TEXT + 5))!!.text!!.length)
    }

    @Test fun mimeRoutesToItsExtractor() {
        assertEquals(AskAi.Kind.IMAGE, AskAi.route("image/heic"))
        assertEquals(AskAi.Kind.PDF, AskAi.route("application/pdf"))
        assertEquals(AskAi.Kind.PDF, AskAi.route("Application/PDF; charset=x"))
        assertEquals(AskAi.Kind.TEXT, AskAi.route("text/markdown"))
        assertEquals(AskAi.Kind.TEXT, AskAi.route("application/json"))
        assertEquals(AskAi.Kind.DOC, AskAi.route("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals(AskAi.Kind.AUDIO, AskAi.route("audio/mpeg"))
        assertEquals(AskAi.Kind.VIDEO, AskAi.route("video/mp4"))
        assertEquals(AskAi.Kind.OTHER, AskAi.route("application/zip"))
        assertEquals(AskAi.Kind.OTHER, AskAi.route(null))
    }

    @Test fun audioAndVideoSayTranscriptionIsUnavailableAndOtherFilesCarryNameSizeMime() {
        assertTrue(AskAi.note(AskAi.Kind.AUDIO, "a.mp3", "audio/mpeg", 5)!!.contains("transcription is unavailable"))
        assertTrue(AskAi.note(AskAi.Kind.VIDEO, "a.mp4", "video/mp4", 5)!!.contains("a.mp4"))
        assertEquals("[attached: z.zip (application/zip, 7 bytes)]", AskAi.note(AskAi.Kind.OTHER, "z.zip", "application/zip", 7))
        assertNull(AskAi.note(AskAi.Kind.IMAGE, "a.png", "image/png", 1))
    }

    @Test fun theSizeCapRefusesWithAClearMessage() {
        assertNull(AskAi.capError("a.pdf", AskAi.MAX_BYTES))
        val e = AskAi.capError("big.mp4", AskAi.MAX_BYTES + 1)!!
        assertTrue(e, e.contains("big.mp4") && e.contains("12.0 MB") && e.contains("Nothing was attached"))
        assertNull(AskAi.totalError(0, AskAi.MAX_TOTAL_BYTES))
        assertNotNull(AskAi.totalError(AskAi.MAX_TOTAL_BYTES, 1))
    }

    @Test fun theComposerIsPrefilledAndHasTheFiveQuickActions() {
        assertEquals("Explain this", AskAi.compose("", emptyList(), null))
        assertEquals("Explain this\n\n[n]\n\n---\nbody", AskAi.compose("Explain this", listOf("[n]"), "body"))
        assertEquals(listOf("Summarize", "Explain", "Translate", "Extract text", "Describe image"), AskAi.quick().map { it.label })
    }

    private fun filters(): List<String> =
        Regex("<intent-filter[^>]*>.*?</intent-filter>", RegexOption.DOT_MATCHES_ALL).findAll(activityBody()).map { it.value }.toList()

    private fun activityBody(): String {
        val a = manifest.indexOf("android:name=\".AskAiActivity\"")
        assertTrue("AskAiActivity declared", a >= 0)
        return manifest.substring(a, manifest.indexOf("</activity>", a))
    }

    private fun has(action: String, mime: String) = filters().any { it.contains("android.intent.action.$action\"") && it.contains("android:mimeType=\"$mime\"") && it.contains("android.intent.category.DEFAULT") }

    @Test fun manifestListsAskAiInTheChooserShareSheetAndSelectionToolbar() {
        assertTrue(activityBody().contains("@string/ask_cloud_si_label"))
        // #1829 renamed "C Ask SI", last in the selection menu (priority -6, after Cloud Keyboard's six entries)
        assertTrue(File("src/main/res/values/strings.xml").readText().contains(">C Ask SI<"))
        assertTrue(File("src/main/res/values-es/strings.xml").readText().contains(">C Ask SI<"))
        assertTrue(Regex("""<intent-filter android:priority="-6">\s*<action android:name="android\.intent\.action\.PROCESS_TEXT" />""").containsMatchIn(activityBody()))
        assertTrue(File("src/main/res/values/strings.xml").readText().contains(">Ask AI · Cloud SI<"))
        assertTrue(has("VIEW", "*/*"))
        val view = filters().first { it.contains("action.VIEW") }
        assertTrue(view.contains("android:scheme=\"content\"") && view.contains("android:scheme=\"file\""))
        for (a in listOf("SEND", "SEND_MULTIPLE")) for (m in listOf("*/*", "text/plain", "text/uri-list", "image/*")) assertTrue("$a $m", has(a, m))
        assertTrue(has("PROCESS_TEXT", "text/plain"))
    }

    @Test fun theShareSheetHasOneEntryMainActivityKeepsNoSendFilter() {
        val main = manifest.substring(manifest.indexOf("android:name=\".MainActivity\""), manifest.indexOf("android:name=\".AskAiActivity\""))
        assertTrue(!main.contains("android.intent.action.SEND"))
    }

    @Test fun askAiAddsNoStoragePermission() {
        for (p in listOf("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO"))
            assertTrue(p, !manifest.contains("android.permission.$p"))
        assertEquals(1, Regex("android.permission.READ_MEDIA_IMAGES").findAll(manifest).count())
    }

    // #1824 the share target's MIME routes: text, link list, one image, several images, and the selection toolbar
    @Test fun shareRoutesByMime() {
        val t = AskAi.parse("android.intent.action.SEND", "text/plain", null, emptyList(), "hello", null)!!
        assertEquals("hello", t.text); assertTrue(t.uris.isEmpty())
        val l = AskAi.parse("android.intent.action.SEND", "text/uri-list", null, emptyList(), "https://example.org/a", null)!!
        assertEquals("https://example.org/a", l.text); assertEquals(AskAi.Kind.TEXT, AskAi.route(l.mime))
        val one = AskAi.parse("android.intent.action.SEND", "image/png", null, listOf("content://x/1"), null, null)!!
        assertEquals(listOf("content://x/1"), one.uris); assertEquals(AskAi.Kind.IMAGE, AskAi.route(one.mime))
        val many = AskAi.parse("android.intent.action.SEND_MULTIPLE", "image/*", null, listOf("content://x/1", "content://x/2"), null, null)!!
        assertEquals(2, many.uris.size); assertEquals(AskAi.Kind.IMAGE, AskAi.route(many.mime))
        val sel = AskAi.parse("android.intent.action.PROCESS_TEXT", null, null, emptyList(), null, "picked")!!
        assertEquals("picked", sel.text); assertEquals("text/plain", sel.mime)
    }

    @Test fun aSharedUrlIsAttachedAsALink() {
        assertTrue(AskAi.isUrl("https://example.org/a?b=1")); assertTrue(!AskAi.isUrl("see https://example.org")); assertTrue(!AskAi.isUrl(null))
        assertEquals("Explain this\n\n---\n[link] https://example.org", AskAi.compose("", emptyList(), "https://example.org"))
    }
}
